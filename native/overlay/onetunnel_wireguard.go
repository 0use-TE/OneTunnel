// SPDX-License-Identifier: GPL-3.0-or-later
package outbound

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"time"
)

// Forward custom startup through ParseProxy's wrapper. Parsing a profile must
// remain side-effect free; only an active VPN calls this method.
func (p *autoCloseProxyAdapter) OneTunnelStartInbound() error {
	if bridge, ok := p.ProxyAdapter.(interface{ OneTunnelStartInbound() error }); ok {
		return bridge.OneTunnelStartInbound()
	}
	return nil
}

func (w *WireGuard) OneTunnelStartInbound() error {
	if len(w.option.ListenPorts) == 0 {
		return nil
	}
	ctx, cancel := context.WithTimeout(w.runCtx, 30*time.Second)
	defer cancel()
	return w.init(ctx)
}

func (w *WireGuard) oneTunnelListen(stack ipStack) error {
	return w.oneTunnelForwarder.start(w.runCtx, func(network, address string) (net.Listener, error) {
		port, err := strconv.Atoi(address[1:])
		if err != nil || network != "tcp" || port < 1 || port > 65535 {
			return nil, errors.New("invalid tunnel TCP port")
		}
		// An unspecified address with network tcp binds both address families
		// within this virtual stack. It never opens an Android/Wi-Fi port.
		return stack.ListenTCP(w.runCtx, "tcp", netip.AddrPortFrom(netip.Addr{}, uint16(port)))
	})
}
