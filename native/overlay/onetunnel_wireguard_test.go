//go:build with_gvisor

package outbound

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
	wgconn "github.com/metacubex/wireguard-go/conn"
	"github.com/metacubex/wireguard-go/device"
	"golang.org/x/crypto/curve25519"
)

// Exercise real encrypted WireGuard packets over loopback UDP, through the
// actual adapter's virtual TCP listeners to a host service. No root or tailnet.
func TestOneTunnelWireGuardEncryptedInbound(t *testing.T) {
	for _, mode := range []string{ipStackAuto, ipStackGVisor} {
		t.Run(mode, func(t *testing.T) {
			backend := testOneTunnelListener(t)
			backendDone := make(chan error, 3)
			go func() {
				for i := 0; i < 3; i++ {
					c, err := backend.Accept()
					if err != nil {
						backendDone <- err
						return
					}
					go func() {
						defer c.Close()
						_ = c.SetDeadline(time.Now().Add(15 * time.Second))
						_, err := io.WriteString(c, "SSH-2.0-local-test\r\n")
						if err == nil {
							var data []byte
							data, err = io.ReadAll(c)
							if err == nil && len(data) > 0 {
								_, err = c.Write(append([]byte("reply:"), data...))
							}
						}
						backendDone <- err
					}()
				}
			}()
			serverKey := bytes.Repeat([]byte{1}, 32)
			clientKey := bytes.Repeat([]byte{2}, 32)
			serverPublic, _ := curve25519.X25519(serverKey, curve25519.Basepoint)
			clientPublic, _ := curve25519.X25519(clientKey, curve25519.Basepoint)
			serverStack, err := newIPStack(IPStackOption{Mode: ipStackGVisor}, []netip.Prefix{
				netip.MustParsePrefix("10.77.0.1/32"), netip.MustParsePrefix("fd00:77::1/128"),
			}, 1280)
			if err != nil {
				t.Fatal(err)
			}
			serverTun, err := newWireguardDevice(serverStack)
			if err != nil {
				_ = serverStack.Close()
				t.Fatal(err)
			}
			server := device.NewDevice(serverTun, wgconn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""), 1)
			t.Cleanup(server.Close)
			if err := server.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=10.77.0.2/32\nallowed_ip=fd00:77::2/128\n", hex.EncodeToString(serverKey), hex.EncodeToString(clientPublic))); err != nil {
				t.Fatal(err)
			}
			if err := serverTun.Start(); err != nil {
				t.Fatal(err)
			}
			if err := server.Up(); err != nil {
				t.Fatal(err)
			}
			config, err := server.IpcGet()
			if err != nil {
				t.Fatal(err)
			}
			var udpPort int
			for _, line := range strings.Split(config, "\n") {
				if strings.HasPrefix(line, "listen_port=") {
					udpPort, _ = strconv.Atoi(strings.TrimPrefix(line, "listen_port="))
				}
			}
			if udpPort == 0 {
				t.Fatal("server UDP port unavailable")
			}
			wg, err := NewWireGuard(WireGuardOption{
				Name: "WG-inbound-test", Ip: "10.77.0.2/32", Ipv6: "fd00:77::2/128", MTU: 1280,
				PrivateKey: base64.StdEncoding.EncodeToString(clientKey), PersistentKeepalive: 1,
				IPStack: IPStackOption{Mode: mode},
				Peers: []WireGuardPeerOption{{Server: "127.0.0.1", Port: udpPort,
					PublicKey: base64.StdEncoding.EncodeToString(serverPublic), AllowedIPs: []string{"10.77.0.1/32", "fd00:77::1/128"}}},
				ListenPorts: []OneTunnelListenPort{{Port: 8022, LocalPort: backend.Addr().(*net.TCPAddr).Port}},
			})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = wg.Close() })
			if wg.initOk.Load() {
				t.Fatal("constructor started the tunnel")
			}
			if err := wg.OneTunnelStartInbound(); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			dial := func(ip string, port uint16) (net.Conn, error) {
				return serverStack.DialTCP(ctx, "tcp", netip.AddrPort{}, netip.AddrPortFrom(netip.MustParseAddr(ip), port))
			}
			readBanner := func(c net.Conn) {
				t.Helper()
				_ = c.SetDeadline(time.Now().Add(10 * time.Second))
				banner := make([]byte, len("SSH-2.0-local-test\r\n"))
				if _, err := io.ReadFull(c, banner); err != nil {
					t.Fatal(err)
				}
				if string(banner) != "SSH-2.0-local-test\r\n" {
					t.Fatal("wrong SSH banner")
				}
			}
			for _, ip := range []string{"10.77.0.2", "fd00:77::2"} {
				c, err := dial(ip, 8022)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = c.Close() })
				readBanner(c)
				body := strings.Repeat("test\x00\xff", 20000)
				if _, err := io.WriteString(c, body); err != nil {
					t.Fatal(err)
				}
				if err := c.(interface{ CloseWrite() error }).CloseWrite(); err != nil {
					t.Fatal(err)
				}
				data, err := io.ReadAll(c)
				if err != nil || string(data) != "reply:"+body {
					t.Fatalf("bidirectional/half-close failed: %v", err)
				}
				_ = c.Close()
				if err := <-backendDone; err != nil {
					t.Fatal(err)
				}
			}
			// The same live adapter must retain outbound TCP after enabling inbound.
			outListener, err := serverStack.ListenTCP(ctx, "tcp", netip.MustParseAddrPort("10.77.0.1:9090"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = outListener.Close() })
			outDone := make(chan error, 1)
			go func() {
				c, err := outListener.Accept()
				if err == nil {
					_, err = io.WriteString(c, "outbound-ok")
					_ = c.Close()
				}
				outDone <- err
			}()
			out, err := wg.DialContext(ctx, &C.Metadata{NetWork: C.TCP, DstIP: netip.MustParseAddr("10.77.0.1"), DstPort: 9090})
			if err != nil {
				t.Fatal(err)
			}
			_ = out.SetDeadline(time.Now().Add(10 * time.Second))
			data, err := io.ReadAll(out)
			_ = out.Close()
			if err != nil || string(data) != "outbound-ok" {
				t.Fatalf("outbound regression: %v", err)
			}
			if err := <-outDone; err != nil {
				t.Fatal(err)
			}
			if c, err := dial("10.77.0.2", 8023); err == nil {
				_ = c.Close()
				t.Fatal("unconfigured port is open")
			}
			active, err := dial("10.77.0.2", 8022)
			if err != nil {
				t.Fatal(err)
			}
			defer active.Close()
			readBanner(active)
			if err := wg.Close(); err != nil {
				t.Fatal(err)
			}
			// Closing the tunnel must close the associated host service socket.
			if err := <-backendDone; err != nil {
				t.Fatal(err)
			}
			wg.oneTunnelForwarder.mu.Lock()
			closed := wg.oneTunnelForwarder.closed
			wg.oneTunnelForwarder.mu.Unlock()
			if !closed {
				t.Fatal("inbound survived WireGuard shutdown")
			}
		})
	}
}
