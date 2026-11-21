//go:build with_gvisor && !no_tailscale

// SPDX-License-Identifier: GPL-3.0-or-later
// Copied into the pinned Mihomo outbound package by prepare-core.sh.
package outbound

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"

	"github.com/metacubex/tailscale/tsnet"
	"github.com/metacubex/tailscale/types/nettype"
)

func (t *Tailscale) OneTunnelStartInbound() error {
	if len(t.option.ListenPorts) == 0 && !t.option.ListenAllPorts {
		return nil
	}
	return t.start()
}

type oneTunnelTailscaleServer interface {
	TailscaleIPs() (netip.Addr, netip.Addr)
	RegisterFallbackTCPHandler(tsnet.FallbackTCPHandler) func()
	RegisterFallbackUDPHandler(tsnet.FallbackUDPHandler) func()
}

func (t *Tailscale) oneTunnelListen() error {
	return t.oneTunnelForwarder.start(t.ctx, t.server.Listen, func(ctx context.Context, tcp, udp oneTunnelFallback) (func(), error) {
		return oneTunnelRegisterTailscaleFallback(ctx, t.server, tcp, udp), nil
	})
}

func oneTunnelRegisterTailscaleFallback(ctx context.Context, server oneTunnelTailscaleServer, tcp, udp oneTunnelFallback) func() {
	local := func(dst netip.AddrPort) bool {
		if ctx.Err() != nil {
			return false
		}
		ipv4, ipv6 := server.TailscaleIPs()
		return dst.Addr().IsValid() && (dst.Addr() == ipv4 || dst.Addr() == ipv6)
	}
	unregisterTCP := server.RegisterFallbackTCPHandler(func(src, dst netip.AddrPort) (func(net.Conn), bool) {
		if !local(dst) {
			return nil, true
		}
		return tcp(src, dst)
	})
	unregisterUDP := server.RegisterFallbackUDPHandler(func(src, dst netip.AddrPort) (func(nettype.ConnPacketConn), bool) {
		if !local(dst) {
			return nil, true
		}
		serve, intercept := udp(src, dst)
		if serve == nil {
			return nil, intercept
		}
		return func(conn nettype.ConnPacketConn) { serve(conn) }, intercept
	})
	return func() { unregisterTCP(); unregisterUDP() }
}

// ParseProxy wraps every outbound in autoCloseProxyAdapter. Forward the narrow
// control method explicitly: embedding ProxyAdapter does not promote methods
// that are absent from that interface.
func (p *autoCloseProxyAdapter) OneTunnelAction(ctx context.Context, action string) (map[string]any, error) {
	bridge, ok := p.ProxyAdapter.(interface {
		OneTunnelAction(context.Context, string) (map[string]any, error)
	})
	if !ok {
		return nil, errors.New("not a Tailscale adapter")
	}
	return bridge.OneTunnelAction(ctx, action)
}

// OneTunnelAction is intentionally narrower than the Tailscale LocalAPI.
// It shares the exact tsnet identity used by the routing adapter.
func (t *Tailscale) OneTunnelAction(ctx context.Context, action string) (map[string]any, error) {
	if action != "status" && action != "login" && action != "logout" {
		return nil, errors.New("unsupported action")
	}
	if err := t.start(); err != nil {
		return nil, fmt.Errorf("ts_start: %w", err)
	}
	lc, err := t.server.LocalClient()
	if err != nil {
		return nil, fmt.Errorf("ts_local_client: %w", err)
	}
	if action == "logout" {
		if err := lc.Logout(ctx); err != nil {
			return nil, err
		}
	}
	if action == "login" {
		status, err := lc.Status(ctx)
		if err != nil {
			return nil, err
		}
		if status.BackendState != "Running" {
			if err := lc.StartLoginInteractive(ctx); err != nil {
				return nil, err
			}
			// AuthURL is produced asynchronously. Return immediately so status
			// polling can deliver it without blocking the serialized core channel.
		}
	}
	status, err := lc.Status(ctx)
	if err != nil {
		return nil, err
	}
	authURL := status.AuthURL
	if status.BackendState == "Running" {
		authURL = ""
	}
	addresses := make([]string, 0, len(status.TailscaleIPs))
	for _, ip := range status.TailscaleIPs {
		addresses = append(addresses, ip.String())
	}
	peers := make([]map[string]any, 0, len(status.Peer))
	for _, peer := range status.Peer {
		ips := make([]string, 0, len(peer.TailscaleIPs))
		for _, ip := range peer.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		peers = append(peers, map[string]any{"name": peer.HostName, "dnsName": peer.DNSName, "online": peer.Online, "ips": ips})
	}
	sort.Slice(peers, func(i, j int) bool { return peers[i]["name"].(string) < peers[j]["name"].(string) })
	if len(peers) > 100 {
		peers = peers[:100]
	}
	return map[string]any{"name": t.Name(), "state": status.BackendState, "authUrl": authURL, "ips": addresses, "peers": peers}, nil
}
