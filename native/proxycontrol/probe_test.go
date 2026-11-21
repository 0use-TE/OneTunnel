package proxycontrol

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

const policyProfile = `
proxies: []
proxy-providers:
  first:
    type: inline
    payload: [{name: first-node, type: socks5, server: private-server.invalid, port: 1080, password: private-password}]
    health-check: {enable: false, url: 'https://first.invalid/204', timeout: 12000, expected-status: 204}
  second:
    type: inline
    payload: [{name: second-node, type: socks5, server: private-server.invalid, port: 1080}]
    health-check: {enable: false, url: 'https://second.invalid/check', timeout: '2500', expected-status: '200/204'}
proxy-groups:
  - {name: Mixed, type: select, proxies: [DIRECT], use: [first, second]}
  - {name: Explicit, type: select, use: [first, second], url: 'https://group.invalid/204', expected-status: 204, timeout: 20000}
  - {name: Local, type: select, proxies: [DIRECT], url: 'https://local.invalid/check', timeout: 15000, expected-status: 200}
rules: ['MATCH,Mixed']
`

func policyProxies(t *testing.T) map[string]C.Proxy {
	t.Helper()
	raw, err := config.UnmarshalRawConfig([]byte(policyProfile))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		for _, p := range cfg.Proxies {
			_ = p.Close()
		}
		for _, p := range cfg.Providers {
			if c, ok := p.(io.Closer); ok {
				_ = c.Close()
			}
		}
	})
	return cfg.Proxies
}

func TestProbeUsesEachMembersProviderURLStatusAndTimeout(t *testing.T) {
	p := policyProxies(t)
	policies := ReadProbePolicies(policyProfile)
	first := policies.Options(p, "Mixed", "first-node")
	second := policies.Options(p, "Mixed", "second-node")
	if first.URL != "https://first.invalid/204" || first.Timeout != 12*time.Second || !first.Expected.Check(204) || first.Expected.Check(200) {
		t.Fatalf("first provider lost: %+v", first)
	}
	if second.URL != "https://second.invalid/check" || second.Timeout != 2500*time.Millisecond || !second.Expected.Check(200) || second.Expected.Check(403) {
		t.Fatalf("second provider used first provider settings: %+v", second)
	}
	direct := policies.Options(p, "Mixed", "DIRECT")
	if direct.Timeout != 5*time.Second || direct.URL != "https://first.invalid/204" {
		t.Fatalf("compatible provider/default mismatch: %+v", direct)
	}
	s := SnapshotWithPolicies(p, "Mixed", policies)
	for _, n := range s.Nodes {
		if n.Name == "second-node" && (n.TimeoutMS != 2500 || n.ProbeHost != "second.invalid") {
			t.Fatalf("UI timeout/URL mismatch: %+v", n)
		}
	}
}

func TestExplicitGroupURLAndCompatibleTimeoutAreRespected(t *testing.T) {
	p := policyProxies(t)
	policies := ReadProbePolicies(policyProfile)
	for _, node := range []string{"first-node", "second-node"} {
		o := policies.Options(p, "Explicit", node)
		if o.URL != "https://group.invalid/204" || !o.Expected.Check(204) || o.Expected.Check(200) {
			t.Fatalf("explicit group task lost: %+v", o)
		}
	}
	o := policies.Options(p, "Local", "DIRECT")
	if o.Timeout != 15*time.Second || o.URL != "https://local.invalid/check" || !o.Expected.Check(200) {
		t.Fatalf("manual group config lost: %+v", o)
	}
	for _, v := range []any{0, -1, nil, "invalid"} {
		if probeTimeout(v) != 5*time.Second {
			t.Fatalf("bad default: %v", v)
		}
	}
	if probeTimeout("999999999999999") != MaxProbeTimeout {
		t.Fatal("timeout is not bounded")
	}
	sharedURL := strings.Replace(policyProfile, "https://group.invalid/204', expected-status: 204", "https://first.invalid/204', expected-status: 200", 1)
	shared := ReadProbePolicies(sharedURL).Options(p, "Explicit", "first-node")
	if !shared.Expected.Check(204) || shared.Expected.Check(200) {
		t.Fatal("group overwrote same-URL provider response policy")
	}
}

func TestFailedProbeDoesNotMeanOrdinaryRequestsThroughNodeFail(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/probe" {
			<-r.Context().Done()
			return
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	proxy, _ := Member(testProxies(t, "DIRECT"), "Proxy", "DIRECT")
	r := Measure(context.Background(), proxy, ProbeOptions{URL: server.URL + "/probe", Timeout: 80 * time.Millisecond})
	if r.Status != "timeout" {
		t.Fatalf("expected probe timeout: %+v", r)
	}
	r = Measure(context.Background(), proxy, ProbeOptions{URL: server.URL + "/app", Timeout: time.Second})
	if r.Status != "ok" || r.Delay == 0 {
		t.Fatalf("working node rejected after failed endpoint: %+v", r)
	}
}

func TestProbeHTTPMethodAndExpectedResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			t.Errorf("not Mihomo HTTP HEAD: %s", r.Method)
		}
		w.WriteHeader(403)
	}))
	defer server.Close()
	proxy, _ := Member(testProxies(t, "DIRECT"), "Proxy", "DIRECT")
	expected, _ := utils.NewUnsignedRanges[uint16]("204")
	r := Measure(context.Background(), proxy, ProbeOptions{URL: server.URL, Timeout: time.Second, Expected: expected})
	if r.Status != "failed" || r.Reason != "status" {
		t.Fatalf("HTTP mismatch hidden: %+v", r)
	}
	r = Measure(context.Background(), proxy, ProbeOptions{URL: server.URL, Timeout: time.Second})
	if r.Status != "ok" {
		t.Fatalf("no expected-status should accept response: %+v", r)
	}
}

type probeFailureProxy struct {
	C.Proxy
	err error
}

func (p probeFailureProxy) URLTest(context.Context, string, utils.IntRanges[uint16]) (uint16, error) {
	return 0, p.err
}

func TestProbeFailureCategoriesAndDiagnosticsDoNotExposePrivateURL(t *testing.T) {
	o := ProbeOptions{URL: "https://private-user:private-password@test.invalid/private-path?token=private-token", Timeout: time.Second}
	for _, tc := range []struct {
		err    error
		reason string
	}{
		{&net.DNSError{Err: "private-server.invalid", Name: "private-name.invalid"}, "dns"},
		{x509.UnknownAuthorityError{}, "tls"},
		{context.DeadlineExceeded, "timeout"},
	} {
		r := Measure(context.Background(), probeFailureProxy{err: tc.err}, o)
		if r.Reason != tc.reason || r.Host != "test.invalid" {
			t.Fatalf("wrong safe diagnostic: %+v", r)
		}
		data, _ := json.Marshal(r)
		if strings.Contains(string(data), "private-") {
			t.Fatalf("private probe details leaked: %s", data)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r := Measure(ctx, probeFailureProxy{err: context.Canceled}, o)
	if r.Status != "cancelled" {
		t.Fatalf("cancelled test shown as node failure: %+v", r)
	}
}
