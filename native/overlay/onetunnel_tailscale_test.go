//go:build with_gvisor && !no_tailscale

package outbound_test

import (
	"context"
	"testing"

	"github.com/metacubex/mihomo/adapter"
)

func TestOneTunnelTailscaleActionThroughParserWrapper(t *testing.T) {
	proxy, err := adapter.ParseProxy(map[string]any{"name": "TEST-TAIL", "type": "tailscale", "hostname": "test-device", "state-dir": "test-identity"})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	inbound, ok := proxy.Adapter().(interface{ OneTunnelStartInbound() error })
	if !ok {
		t.Fatal("parser wrapper hides inbound startup")
	}
	if err := inbound.OneTunnelStartInbound(); err != nil {
		t.Fatal("unconfigured inbound startup must remain a no-op", err)
	}
	bridge, ok := proxy.Adapter().(interface {
		OneTunnelAction(context.Context, string) (map[string]any, error)
	})
	if !ok {
		t.Fatal("parser wrapper hides the Tailscale control interface")
	}
	// Rejected before tsnet starts: no network, auth request, or identity files.
	_, err = bridge.OneTunnelAction(context.Background(), "invalid-test-action")
	if err == nil || err.Error() != "unsupported action" {
		t.Fatalf("action did not reach Tailscale: %v", err)
	}
}

func TestOneTunnelTailscaleInboundPortsThroughParser(t *testing.T) {
	base := func(ports any) map[string]any {
		return map[string]any{
			"name": "TEST-TAIL", "type": "tailscale", "state-dir": "test-identity", "listen-ports": ports,
		}
	}
	proxy, err := adapter.ParseProxy(base([]map[string]any{{"port": 8022, "local-port": 8022}}))
	if err != nil {
		t.Fatal(err)
	}
	_ = proxy.Close() // Construction/close never starts tsnet or opens a listener.
	for _, ports := range []any{[]map[string]any{{"port": 0}}, []map[string]any{{"port": 8022}, {"port": 8022}}, []map[string]any{{"port": 8022, "local-port": 65536}}} {
		proxy, err := adapter.ParseProxy(base(ports))
		if err == nil {
			_ = proxy.Close()
			t.Fatal("parser accepted invalid inbound ports")
		}
	}
}
