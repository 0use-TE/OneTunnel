// SPDX-License-Identifier: GPL-3.0-or-later
package outbound

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"time"

	"github.com/metacubex/mipstack"
	wireguard "github.com/metacubex/sing-wireguard"
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
	if len(w.option.ListenPorts) == 0 && !w.option.ListenAllPorts {
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
	}, func(ctx context.Context, tcp, udp oneTunnelFallback) (func(), error) {
		return oneTunnelRegisterStackFallback(ctx, stack, tcp, udp)
	})
}

func oneTunnelRegisterStackFallback(ctx context.Context, stack ipStack, tcp, udp oneTunnelFallback) (func(), error) {
	local, ok := stack.(interface{ LocalAddresses() []netip.Addr })
	if !ok {
		return nil, errors.New("stack has no local address information")
	}
	addresses := local.LocalAddresses()
	isLocal := func(dst netip.AddrPort) bool {
		for _, address := range addresses {
			if dst.Addr().Unmap() == address.Unmap() {
				return true
			}
		}
		return false
	}
	if mips, ok := stack.(*mipstack.Stack); ok {
		tcpForwarder, err := mipstack.NewTCPForwarder(mips, mipstack.TCPForwarderOptions{MaxInFlight: 64}, func(request *mipstack.TCPForwarderRequest) {
			flow := request.Flow()
			if !isLocal(flow.Destination) {
				_ = request.Reject()
				return
			}
			serve, intercept := tcp(flow.Source, flow.Destination)
			if !intercept || serve == nil {
				_ = request.Reject()
				return
			}
			handshake, cancel := context.WithTimeout(ctx, 10*time.Second)
			conn, err := request.Accept(handshake)
			cancel()
			if err == nil {
				serve(conn)
			}
		})
		if err != nil {
			return nil, err
		}
		udpForwarder, err := mipstack.NewUDPForwarder(mips, mipstack.UDPForwarderOptions{}, func(request *mipstack.UDPForwarderRequest) {
			flow := request.Flow()
			if !isLocal(flow.Destination) {
				_ = request.Reject()
				return
			}
			serve, intercept := udp(flow.Source, flow.Destination)
			if !intercept || serve == nil {
				_ = request.Reject()
				return
			}
			conn, err := request.Accept()
			if err == nil {
				serve(conn)
			}
		})
		if err != nil {
			_ = tcpForwarder.Close()
			return nil, err
		}
		return func() { _ = tcpForwarder.Close(); _ = udpForwarder.Close() }, nil
	}
	if device, ok := stack.(wireguard.RegisterForward); ok {
		handler := newOneTunnelStackForwarder(isLocal, tcp, udp)
		if err := device.RegisterForward(wireguard.ForwardOptions{Handler: handler}); err != nil {
			handler.close()
			return nil, err
		}
		return handler.close, nil
	}
	return nil, errors.New("stack does not support all-port forwarding")
}
