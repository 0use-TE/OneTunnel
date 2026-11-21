package outbound

import (
	"context"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func testOneTunnelListener(t *testing.T) *net.TCPListener {
	t.Helper()
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return ln
}

func testOneTunnelStart(t *testing.T, ctx context.Context, localPort int) (*oneTunnelForwarder, *net.TCPConn) {
	t.Helper()
	g, err := newOneTunnelForwarder([]OneTunnelListenPort{{Port: 8022, LocalPort: localPort}}, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(g.close)
	var tailnet *net.TCPListener
	err = g.start(ctx, func(network, address string) (net.Listener, error) {
		if network != "tcp" || address != ":8022" {
			t.Fatalf("not an explicit tailnet port: %s %s", network, address)
		}
		tailnet = testOneTunnelListener(t)
		return tailnet, nil
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	client, err := net.DialTCP("tcp4", nil, tailnet.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	_ = client.SetDeadline(time.Now().Add(4 * time.Second))
	t.Cleanup(func() { _ = client.Close() })
	return g, client
}

func TestOneTunnelForwarderPreservesBidirectionalDataAndHalfClose(t *testing.T) {
	backend := testOneTunnelListener(t)
	finished := make(chan error, 1)
	go func() {
		conn, err := backend.AcceptTCP()
		if err != nil {
			finished <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
		_, err = io.WriteString(conn, "SSH-2.0-Termux-test\r\n")
		if err != nil {
			finished <- err
			return
		}
		body, err := io.ReadAll(conn)
		if err == nil {
			_, err = conn.Write(append([]byte("reply:"), body...))
		}
		finished <- err
	}()
	_, client := testOneTunnelStart(t, context.Background(), backend.Addr().(*net.TCPAddr).Port)
	banner := make([]byte, len("SSH-2.0-Termux-test\r\n"))
	if _, err := io.ReadFull(client, banner); err != nil {
		t.Fatal(err)
	}
	if string(banner) != "SSH-2.0-Termux-test\r\n" {
		t.Fatal("SSH banner changed")
	}
	payload := strings.Repeat("binary\x00\xff-data", 10000)
	if _, err := io.WriteString(client, payload); err != nil {
		t.Fatal(err)
	}
	if err := client.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	response, err := io.ReadAll(client)
	if err != nil {
		t.Fatal(err)
	}
	if string(response) != "reply:"+payload {
		t.Fatal("forwarded bytes changed or half-close lost response")
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
}

func TestOneTunnelForwarderCancellationClosesListenerAndActiveStreams(t *testing.T) {
	backend := testOneTunnelListener(t)
	serverDone := make(chan error, 1)
	go func() {
		conn, err := backend.AcceptTCP()
		if err != nil {
			serverDone <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
		_, err = io.WriteString(conn, "ready")
		if err == nil {
			_, err = io.Copy(io.Discard, conn)
		}
		serverDone <- err
	}()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	g, client := testOneTunnelStart(t, ctx, backend.Addr().(*net.TCPAddr).Port)
	if _, err := io.ReadFull(client, make([]byte, 5)); err != nil {
		t.Fatal(err)
	}
	cancel()
	if _, err := io.ReadAll(client); err != nil {
		t.Fatal(err)
	}
	if err := <-serverDone; err != nil {
		t.Fatal(err)
	}
	g.close() // Closing again must be harmless.
	g.mu.Lock()
	listeners := append([]net.Listener(nil), g.listeners...)
	g.mu.Unlock()
	for _, ln := range listeners {
		if c, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second); err == nil {
			_ = c.Close()
			t.Fatal("listener survived cancellation")
		}
	}
}

func TestOneTunnelForwarderUnreachableLocalServiceFailsClosed(t *testing.T) {
	backend := testOneTunnelListener(t)
	port := backend.Addr().(*net.TCPAddr).Port
	_ = backend.Close()
	_, client := testOneTunnelStart(t, context.Background(), port)
	_, err := client.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("missing local service unexpectedly returned data")
	}
	if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("failed local dial left incoming stream hanging")
	}
}

func TestOneTunnelForwarderBindFailureRollsBackAllPorts(t *testing.T) {
	g, err := newOneTunnelForwarder([]OneTunnelListenPort{{Port: 8022}, {Port: 8080, LocalPort: 9000}}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer g.close()
	first := testOneTunnelListener(t)
	called := 0
	err = g.start(context.Background(), func(network, address string) (net.Listener, error) {
		called++
		if address != ":"+strconv.Itoa(g.ports[called-1].Port) {
			t.Fatal("wrong port")
		}
		if called == 1 {
			return first, nil
		}
		return nil, errors.New("test bind failure")
	}, nil)
	if err == nil {
		t.Fatal("bind failure was hidden")
	}
	if c, err := first.Accept(); err == nil {
		_ = c.Close()
		t.Fatal("earlier listener not closed")
	}
}

func TestOneTunnelForwarderValidationAndDefaultOff(t *testing.T) {
	for _, ports := range [][]OneTunnelListenPort{
		{{Port: 0}}, {{Port: -1}}, {{Port: 65536}}, {{Port: 8022, LocalPort: -1}}, {{Port: 8022, LocalPort: 65536}}, {{Port: 8022}, {Port: 8022}}, make([]OneTunnelListenPort, 17),
	} {
		if _, err := newOneTunnelForwarder(ports, false); err == nil {
			t.Fatal("accepted invalid or duplicate ports")
		}
	}
	g, err := newOneTunnelForwarder([]OneTunnelListenPort{{Port: 8022}}, false)
	if err != nil || g.ports[0].LocalPort != 8022 {
		t.Fatal("same-port default missing")
	}
	g, err = newOneTunnelForwarder(nil, false)
	if err != nil {
		t.Fatal(err)
	}
	err = g.start(context.Background(), func(string, string) (net.Listener, error) {
		t.Fatal("default config opened a listener")
		return nil, nil
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	g.close()
}
