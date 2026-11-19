package outbound_test

import (
	"testing"

	"github.com/metacubex/mihomo/adapter"
)

func TestOneTunnelWireGuardInboundThroughParser(t *testing.T) {
	base := func() map[string]any {
		return map[string]any{
			"name": "WG-test", "type": "wireguard", "ip": "10.77.0.2/32",
			"private-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
			"public-key":  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
			"server":      "192.0.2.1", "port": 51820,
		}
	}
	proxy, err := adapter.ParseProxy(base())
	if err != nil {
		t.Fatal(err)
	}
	bridge, ok := proxy.Adapter().(interface{ OneTunnelStartInbound() error })
	if !ok {
		t.Fatal("wrapper hides inbound startup")
	}
	if err := bridge.OneTunnelStartInbound(); err != nil {
		t.Fatal("default-off startup performed network work", err)
	}
	_ = proxy.Close()
	valid := base()
	valid["listen-ports"] = []map[string]any{{"port": 8022, "local-port": 8022}}
	proxy, err = adapter.ParseProxy(valid)
	if err != nil {
		t.Fatal(err)
	}
	_ = proxy.Close() // Preparation must not initialize a WireGuard device.
	for _, ports := range []any{[]map[string]any{{"port": 0}}, []map[string]any{{"port": 8022}, {"port": 8022}}, []map[string]any{{"port": 8022, "local-port": 65536}}} {
		invalid := base()
		invalid["listen-ports"] = ports
		proxy, err := adapter.ParseProxy(invalid)
		if err == nil {
			_ = proxy.Close()
			t.Fatal("accepted invalid inbound ports")
		}
	}
}
