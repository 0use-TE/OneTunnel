package outbound

import (
	"bytes"
	"context"
	"net"
	"net/netip"
	"testing"
	"time"
)

func testOneTunnelUDPBackend(t *testing.T) *net.UDPConn {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	go func() {
		packet := make([]byte, 65535)
		for {
			n, peer, err := conn.ReadFromUDP(packet)
			if err != nil {
				return
			}
			_, _ = conn.WriteToUDP(append([]byte("reply:"), packet[:n]...), peer)
		}
	}()
	return conn
}

// A connected UDP endpoint representing the virtual stack, plus its peer.
func testOneTunnelUDPPair(t *testing.T) (*net.UDPConn, *net.UDPConn) {
	t.Helper()
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	endpoint, err := net.DialUDP("udp4", nil, peer.LocalAddr().(*net.UDPAddr))
	if err != nil {
		_ = peer.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = endpoint.Close(); _ = peer.Close() })
	return endpoint, peer
}

func testOneTunnelUDPExchange(t *testing.T, peer *net.UDPConn, endpoint net.Addr, body []byte) {
	t.Helper()
	_ = peer.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err := peer.WriteTo(body, endpoint); err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, 65535)
	n, _, err := peer.ReadFromUDP(packet)
	if err != nil || !bytes.Equal(packet[:n], append([]byte("reply:"), body...)) {
		t.Fatalf("UDP datagram/reply changed: got %d bytes, error %v", n, err)
	}
}

func TestOneTunnelAllPortsUDPPreservesDatagramsPeersAndIdleCleanup(t *testing.T) {
	backend := testOneTunnelUDPBackend(t)
	g, err := newOneTunnelForwarder(nil, true)
	if err != nil {
		t.Fatal(err)
	}
	g.udpIdle = 250 * time.Millisecond
	t.Cleanup(g.close)
	var udp oneTunnelFallback
	err = g.start(context.Background(), func(string, string) (net.Listener, error) {
		t.Fatal("all-port mode created a per-port listener")
		return nil, nil
	}, func(_ context.Context, _, handler oneTunnelFallback) (func(), error) {
		udp = handler
		return func() {}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	port := uint16(backend.LocalAddr().(*net.UDPAddr).Port)
	for i := 0; i < 2; i++ {
		endpoint, peer := testOneTunnelUDPPair(t)
		serve, intercept := udp(netip.AddrPortFrom(netip.MustParseAddr("100.64.0.1"), uint16(40000+i)), netip.AddrPortFrom(netip.MustParseAddr("100.64.0.2"), port))
		if !intercept || serve == nil {
			t.Fatal("random UDP port not accepted")
		}
		serve(endpoint)
		for _, packet := range [][]byte{{}, {byte(i), 0, 255}, bytes.Repeat([]byte{byte(i)}, 4096)} {
			testOneTunnelUDPExchange(t, peer, endpoint.LocalAddr(), packet)
		}
	}
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		g.mu.Lock()
		active := len(g.active)
		g.mu.Unlock()
		if active == 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("idle UDP flows retained host sockets")
}

func TestOneTunnelAllPortsMappingsExcludeDisabledPortsAndCloseStaleCallbacks(t *testing.T) {
	disabled := false
	g, err := newOneTunnelForwarder([]OneTunnelListenPort{{Port: 8022, Enabled: &disabled}}, true)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(g.close)
	var tcp, udp oneTunnelFallback
	unregistered := false
	err = g.start(context.Background(), nil, func(_ context.Context, tHandler, uHandler oneTunnelFallback) (func(), error) {
		tcp, udp = tHandler, uHandler
		return func() { unregistered = true }, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, handler := range []oneTunnelFallback{tcp, udp} {
		for _, port := range []uint16{0, 8022} {
			if serve, _ := handler(netip.AddrPort{}, netip.AddrPortFrom(netip.MustParseAddr("100.64.0.2"), port)); serve != nil {
				t.Fatal("disabled port was reopened by wildcard")
			}
		}
		if serve, _ := handler(netip.AddrPort{}, netip.MustParseAddrPort("100.64.0.2:55555")); serve == nil {
			t.Fatal("unlisted random port stayed closed")
		}
	}
	serve, _ := tcp(netip.AddrPort{}, netip.MustParseAddrPort("100.64.0.2:55555"))
	g.close()
	if !unregistered {
		t.Fatal("fallback was not removed")
	}
	incoming, peer := net.Pipe()
	defer peer.Close()
	serve(incoming)
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1)); err == nil {
		t.Fatal("callback accepted connection after close")
	}
	if serve, _ := udp(netip.AddrPort{}, netip.MustParseAddrPort("100.64.0.2:55555")); serve != nil {
		t.Fatal("closed forwarder accepted UDP")
	}
}

func TestOneTunnelAllPortsUDPUsesExplicitRemap(t *testing.T) {
	backend := testOneTunnelUDPBackend(t)
	g, err := newOneTunnelForwarder([]OneTunnelListenPort{{Port: 8080, LocalPort: backend.LocalAddr().(*net.UDPAddr).Port}}, true)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(g.close)
	var udp oneTunnelFallback
	err = g.start(context.Background(), func(string, string) (net.Listener, error) { return testOneTunnelListener(t), nil },
		func(_ context.Context, _, handler oneTunnelFallback) (func(), error) {
			udp = handler
			return func() {}, nil
		})
	if err != nil {
		t.Fatal(err)
	}
	endpoint, peer := testOneTunnelUDPPair(t)
	serve, _ := udp(netip.MustParseAddrPort("100.64.0.1:40000"), netip.MustParseAddrPort("100.64.0.2:8080"))
	serve(endpoint)
	testOneTunnelUDPExchange(t, peer, endpoint.LocalAddr(), []byte("mapped-udp"))
}
