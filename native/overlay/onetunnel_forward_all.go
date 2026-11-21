// SPDX-License-Identifier: GPL-3.0-or-later
package outbound

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"time"
)

type oneTunnelFallback func(src, dst netip.AddrPort) (func(net.Conn), bool)
type oneTunnelRegisterFallback func(context.Context, oneTunnelFallback, oneTunnelFallback) (func(), error)

// Explicit TCP listeners take priority. The fallback handles only the remaining
// flows; disabled saved mappings stay closed even while all ports are enabled.
func (g *oneTunnelForwarder) fallback(ctx context.Context, udp bool) oneTunnelFallback {
	return func(src, dst netip.AddrPort) (func(net.Conn), bool) {
		g.mu.Lock()
		allowed := g.all && !g.closed && ctx.Err() == nil && dst.Port() != 0 && !g.blocked[dst.Port()]
		g.mu.Unlock()
		if !allowed {
			return nil, true
		}
		port := g.targets[dst.Port()]
		if port == 0 {
			port = int(dst.Port())
		}
		return func(conn net.Conn) { g.take(ctx, conn, port, udp) }, true
	}
}

func (g *oneTunnelForwarder) take(ctx context.Context, incoming net.Conn, port int, udp bool) {
	g.mu.Lock()
	if g.closed || ctx.Err() != nil || len(g.active) >= 64 {
		g.mu.Unlock()
		_ = incoming.Close()
		return
	}
	g.active[incoming] = nil
	g.mu.Unlock()
	if udp {
		go g.forwardUDP(ctx, incoming, port)
	} else {
		go g.forward(ctx, incoming, port)
	}
}

// Each incoming UDP flow has its own connected loopback socket, preserving
// datagram boundaries and keeping replies isolated between peers and ports.
func (g *oneTunnelForwarder) forwardUDP(ctx context.Context, incoming net.Conn, port int) {
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
	local, err = (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "udp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
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
	stop := context.AfterFunc(ctx, func() { _ = incoming.Close(); _ = local.Close() })
	defer stop()
	_ = local.SetReadDeadline(time.Now().Add(g.udpIdle))
	done := make(chan struct{}, 2)
	pump := func(dst, src net.Conn, toLocal bool) {
		defer func() { done <- struct{}{} }()
		packet := make([]byte, 65535)
		for {
			n, err := src.Read(packet)
			if err != nil {
				return
			}
			_ = local.SetReadDeadline(time.Now().Add(g.udpIdle))
			if toLocal {
				_ = local.SetWriteDeadline(time.Now().Add(10 * time.Second))
			}
			if _, err = dst.Write(packet[:n]); err != nil {
				return
			}
		}
	}
	go pump(local, incoming, true)
	go pump(incoming, local, false)
	<-done
	_ = incoming.Close()
	_ = local.Close()
	<-done
}
