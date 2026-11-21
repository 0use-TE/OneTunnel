//go:build with_gvisor && !no_tailscale

package outbound

import (
	"context"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/metacubex/tailscale/tsnet"
)

type testOneTunnelTailServer struct {
	tcp     tsnet.FallbackTCPHandler
	udp     tsnet.FallbackUDPHandler
	removed int
}

func (*testOneTunnelTailServer) TailscaleIPs() (netip.Addr, netip.Addr) {
	return netip.MustParseAddr("100.64.0.2"), netip.MustParseAddr("fd7a:115c:a1e0::2")
}
func (s *testOneTunnelTailServer) RegisterFallbackTCPHandler(cb tsnet.FallbackTCPHandler) func() {
	s.tcp = cb
	return func() { s.removed++; s.tcp = nil }
}
func (s *testOneTunnelTailServer) RegisterFallbackUDPHandler(cb tsnet.FallbackUDPHandler) func() {
	s.udp = cb
	return func() { s.removed++; s.udp = nil }
}

func TestOneTunnelTailscaleAllPortsTCPUDPAndOwnAddressFilter(t *testing.T) {
	tcpBackend := testOneTunnelListener(t)
	go func() {
		conn, err := tcpBackend.Accept()
		if err == nil {
			defer conn.Close()
			_, _ = io.Copy(conn, conn)
		}
	}()
	udpBackend := testOneTunnelUDPBackend(t)
	g, err := newOneTunnelForwarder(nil, true)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(g.close)
	server := new(testOneTunnelTailServer)
	err = g.start(context.Background(), nil, func(ctx context.Context, tcp, udp oneTunnelFallback) (func(), error) {
		return oneTunnelRegisterTailscaleFallback(ctx, server, tcp, udp), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	source := netip.MustParseAddrPort("100.64.0.1:40000")
	for _, ip := range []string{"192.0.2.1", "127.0.0.1", "100.64.0.3"} {
		dest := netip.AddrPortFrom(netip.MustParseAddr(ip), 55555)
		if serve, _ := server.tcp(source, dest); serve != nil {
			t.Fatal("TCP forwarded non-node address")
		}
		if serve, _ := server.udp(source, dest); serve != nil {
			t.Fatal("UDP forwarded non-node address")
		}
	}
	endpoint, peer := net.Pipe()
	defer peer.Close()
	tcpServe, _ := server.tcp(source, netip.AddrPortFrom(netip.MustParseAddr("100.64.0.2"), uint16(tcpBackend.Addr().(*net.TCPAddr).Port)))
	if tcpServe == nil {
		t.Fatal("random TCP port rejected")
	}
	tcpServe(endpoint)
	_ = peer.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err := peer.Write([]byte("adb-tcp")); err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, 7)
	if _, err := io.ReadFull(peer, packet); err != nil || string(packet) != "adb-tcp" {
		t.Fatal("TCP bridge failed", err)
	}
	for _, ip := range []string{"100.64.0.2", "fd7a:115c:a1e0::2"} {
		incoming, client := testOneTunnelUDPPair(t)
		serve, _ := server.udp(source, netip.AddrPortFrom(netip.MustParseAddr(ip), uint16(udpBackend.LocalAddr().(*net.UDPAddr).Port)))
		if serve == nil {
			t.Fatal("random UDP port rejected")
		}
		serve(incoming)
		testOneTunnelUDPExchange(t, client, incoming.LocalAddr(), []byte("tail-udp"))
	}
	g.close()
	if server.removed != 2 || server.tcp != nil || server.udp != nil {
		t.Fatal("Tailscale callbacks survived shutdown")
	}
}
