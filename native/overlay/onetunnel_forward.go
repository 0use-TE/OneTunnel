// SPDX-License-Identifier: GPL-3.0-or-later
package outbound

import (
	"context"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// OneTunnelListenPort exposes an explicitly selected TCP port inside a tunnel.
// The target is always IPv4 loopback; no LAN listener or arbitrary host.
type OneTunnelListenPort struct {
	Port      int   `proxy:"port"`
	LocalPort int   `proxy:"local-port,omitempty"`
	Enabled   *bool `proxy:"enabled,omitempty"`
}

type oneTunnelForwarder struct {
	ports      []OneTunnelListenPort
	mu         sync.Mutex
	closed     bool
	started    bool
	listeners  []net.Listener
	active     map[net.Conn]net.Conn
	cancel     context.CancelFunc
	all        bool
	targets    map[uint16]int
	blocked    map[uint16]bool
	unregister func()
	udpIdle    time.Duration
}

func newOneTunnelForwarder(ports []OneTunnelListenPort, all bool) (*oneTunnelForwarder, error) {
	if len(ports) > 16 {
		return nil, errors.New("at most 16 inbound ports are supported")
	}
	g := &oneTunnelForwarder{active: make(map[net.Conn]net.Conn), all: all,
		targets: make(map[uint16]int), blocked: make(map[uint16]bool), udpIdle: time.Minute}
	seen := make(map[int]bool)
	for _, port := range ports {
		if port.LocalPort == 0 {
			port.LocalPort = port.Port
		}
		if port.Port < 1 || port.Port > 65535 || port.LocalPort < 1 || port.LocalPort > 65535 || seen[port.Port] {
			return nil, errors.New("invalid or duplicate inbound port")
		}
		seen[port.Port] = true
		if port.Enabled != nil && !*port.Enabled {
			g.blocked[uint16(port.Port)] = true
			continue
		}
		g.targets[uint16(port.Port)] = port.LocalPort
		g.ports = append(g.ports, port)
	}
	return g, nil
}

// start receives a tunnel's virtual listener, never net.Listen. Bind every configured
// listener before accepting, so a bind failure does not partially apply a list.
func (g *oneTunnelForwarder) start(parent context.Context, listen func(string, string) (net.Listener, error), register oneTunnelRegisterFallback) error {
	if g == nil || (len(g.ports) == 0 && !g.all) {
		return nil
	}
	g.mu.Lock()
	if g.closed || g.started || parent.Err() != nil {
		g.mu.Unlock()
		return errors.New("inbound forwarder unavailable")
	}
	g.started = true
	ctx, cancel := context.WithCancel(parent)
	g.cancel = cancel
	g.mu.Unlock()
	for _, port := range g.ports {
		listener, err := listen("tcp", ":"+strconv.Itoa(port.Port))
		if err != nil {
			g.close()
			return errors.New("inbound listener failed")
		}
		g.mu.Lock()
		if g.closed || ctx.Err() != nil {
			g.mu.Unlock()
			_ = listener.Close()
			g.close()
			return errors.New("inbound forwarder stopped")
		}
		g.listeners = append(g.listeners, listener)
		g.mu.Unlock()
	}
	if g.all {
		if register == nil {
			g.close()
			return errors.New("all-port forwarding unavailable")
		}
		unregister, err := register(ctx, g.fallback(ctx, false), g.fallback(ctx, true))
		if err != nil {
			if unregister != nil {
				unregister()
			}
			g.close()
			return errors.New("all-port forwarding failed")
		}
		g.mu.Lock()
		if g.closed || ctx.Err() != nil {
			g.mu.Unlock()
			if unregister != nil {
				unregister()
			}
			g.close()
			return errors.New("inbound forwarder stopped")
		}
		g.unregister = unregister
		g.mu.Unlock()
	}
	g.mu.Lock()
	listeners := append([]net.Listener(nil), g.listeners...)
	g.mu.Unlock()
	for index, listener := range listeners {
		go g.accept(ctx, listener, g.ports[index].LocalPort)
	}
	go func() { <-ctx.Done(); g.close() }()
	return nil
}

func (g *oneTunnelForwarder) accept(ctx context.Context, listener net.Listener, port int) {
	for {
		incoming, err := listener.Accept()
		if err != nil {
			return
		}
		g.take(ctx, incoming, port, false)
	}
}

func (g *oneTunnelForwarder) forward(ctx context.Context, incoming net.Conn, port int) {
	var local net.Conn
	defer func() {
		_ = incoming.Close()
		if local != nil {
			_ = local.Close()
		}
		g.mu.Lock()
		delete(g.active, incoming)
		g.mu.Unlock()
	}()
	var err error
	// Do not use the outbound's proxy dialer for a device-local service. In the
	// Android app this UID is excluded from the VPN, including loopback sockets.
	local, err = (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return
	}
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return
	}
	g.active[incoming] = local
	g.mu.Unlock()

	done := make(chan error, 2)
	pump := func(dst, src net.Conn) {
		_, err := io.Copy(dst, src)
		if writer, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = writer.CloseWrite()
		} else {
			_ = dst.Close()
		}
		done <- err
	}
	go pump(local, incoming)
	go pump(incoming, local)
	for i := 0; i < 2; i++ {
		if err := <-done; err != nil {
			_ = incoming.Close()
			_ = local.Close()
		}
	}
}

func (g *oneTunnelForwarder) close() {
	if g == nil {
		return
	}
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return
	}
	g.closed = true
	listeners := g.listeners
	connections := make([]net.Conn, 0, 2*len(g.active))
	for incoming, local := range g.active {
		connections = append(connections, incoming)
		if local != nil {
			connections = append(connections, local)
		}
	}
	cancel := g.cancel
	unregister := g.unregister
	g.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if unregister != nil {
		unregister()
	}
	for _, listener := range listeners {
		_ = listener.Close()
	}
	for _, connection := range connections {
		_ = connection.Close()
	}
}
