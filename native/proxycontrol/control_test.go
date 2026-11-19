package proxycontrol

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	_ "github.com/metacubex/mihomo/hub/executor" // config's linkname hooks
)

func testProxies(t *testing.T, selected string) map[string]C.Proxy {
	t.Helper()
	raw := config.DefaultRawConfig()
	raw.Rule = []string{"MATCH,Proxy"}
	raw.Proxy = []map[string]any{{"name": "secret-node", "type": "socks5", "server": "not-exported.invalid", "port": 1080, "password": "dummy-secret"}}
	raw.ProxyGroup = []map[string]any{
		{"name": "Proxy", "type": "select", "proxies": []string{"DIRECT", "REJECT", "Auto", "secret-node"}, "default-selected": selected},
		{"name": "Auto", "type": "url-test", "proxies": []string{"DIRECT", "REJECT"}, "url": "https://example.invalid/204", "interval": 0, "expected-status": "204"},
		{"name": "Provider", "type": "select", "use": []string{"remote", "remote"}},
	}
	raw.ProxyProvider = map[string]map[string]any{"remote": {"type": "inline", "payload": []map[string]any{{"name": "dynamic", "type": "socks5", "server": "provider.invalid", "port": 1080}}}}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		for _, p := range cfg.Proxies {
			_ = p.Close()
		}
		for _, p := range cfg.Providers {
			if closer, ok := p.(io.Closer); ok {
				_ = closer.Close()
			}
		}
	})
	return cfg.Proxies
}

func TestSnapshotOnlyExposesSafeFieldsAndProviderMembers(t *testing.T) {
	proxies := testProxies(t, "DIRECT")
	s := Snapshot(proxies, "Proxy")
	if len(s.Groups) != 3 || len(s.Nodes) != 4 {
		t.Fatalf("wrong grouping: %+v", s)
	}
	data, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"dummy-secret", "not-exported.invalid", "provider.invalid", "password", "server", "port", "GLOBAL"} {
		if strings.Contains(string(data), secret) {
			t.Fatalf("snapshot exports private field %s", secret)
		}
	}
	provider := Snapshot(proxies, "Provider")
	if len(provider.Nodes) != 1 || provider.Nodes[0].Name != "dynamic" {
		t.Fatalf("provider members missing: %+v", provider)
	}
	if _, err := Member(proxies, "Provider", "dynamic"); err != nil {
		t.Fatal(err)
	}
}

func TestSelectionValidatesMembershipAndKeepsAutomaticStrategies(t *testing.T) {
	p := testProxies(t, "DIRECT")
	if err := Select(p, "Proxy", "REJECT"); err != nil {
		t.Fatal(err)
	}
	for _, g := range Snapshot(p, "Proxy").Groups {
		if g.Name == "Proxy" && g.Now != "REJECT" {
			t.Fatal("selection did not apply")
		}
	}
	for _, pair := range [][2]string{{"Proxy", "missing"}, {"Auto", "DIRECT"}, {"missing", "DIRECT"}} {
		if err := Select(p, pair[0], pair[1]); err == nil {
			t.Fatal("invalid or automatic selection accepted")
		}
	}
	if err := Select(p, "Provider", "dynamic"); err != nil {
		t.Fatal(err)
	}
}

func TestSelectionRestoresFromPersistedDefault(t *testing.T) {
	p := testProxies(t, "REJECT")
	for _, g := range Snapshot(p, "Proxy").Groups {
		if g.Name == "Proxy" && g.Now != "REJECT" {
			t.Fatal("persisted choice lost")
		}
	}
}

func TestAutomaticGroupMeasurementsUseItsOwnURLAndExpectedStatus(t *testing.T) {
	p := testProxies(t, "DIRECT")
	url, expected := TestOptions(p, "Auto")
	if url != "https://example.invalid/204" || !expected.Check(204) || expected.Check(200) {
		t.Fatal("automatic strategy health-check settings lost")
	}
	url, _ = TestOptions(p, "Proxy")
	if url != C.DefaultTestURL {
		t.Fatal("manual group default test URL lost")
	}
}

func TestURLTestUsesChosenOutboundAndRecordsRealHistory(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	p := testProxies(t, "DIRECT")
	proxy, err := Member(p, "Proxy", "DIRECT")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := proxy.URLTest(ctx, server.URL, nil); err != nil {
		t.Fatal(err)
	}
	if len(proxy.DelayHistory()) != 1 {
		t.Fatal("real test history missing")
	}
	reject, _ := Member(p, "Proxy", "REJECT")
	if _, err := reject.URLTest(ctx, server.URL, nil); err == nil {
		t.Fatal("reject outbound bypassed")
	}
}

func TestURLTestCancellationDoesNotWaitForServer(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(started); <-release }))
	defer server.Close()
	defer close(release)
	proxy, _ := Member(testProxies(t, "DIRECT"), "Proxy", "DIRECT")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := proxy.URLTest(ctx, server.URL, nil); done <- err }()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("test did not reach local server")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancellation ignored")
		}
	case <-time.After(time.Second):
		t.Fatal("network test blocked cancellation")
	}
}
