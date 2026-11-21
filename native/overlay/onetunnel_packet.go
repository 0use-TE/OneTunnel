// SPDX-License-Identifier: GPL-3.0-or-later
package outbound

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
)

type oneTunnelFlow struct{ source, destination netip.AddrPort }

// Adapt the pinned gVisor device's packet callbacks to a bounded datagram
// connection. Every peer/destination tuple owns an independent relay.
type oneTunnelStackForwarder struct {
	isLocal  func(netip.AddrPort) bool
	tcp, udp oneTunnelFallback
	mu       sync.Mutex
	closed   bool
	flows    map[oneTunnelFlow]*oneTunnelPacketConn
}

func newOneTunnelStackForwarder(local func(netip.AddrPort) bool, tcp, udp oneTunnelFallback) *oneTunnelStackForwarder {
	return &oneTunnelStackForwarder{isLocal: local, tcp: tcp, udp: udp, flows: make(map[oneTunnelFlow]*oneTunnelPacketConn)}
}

func (h *oneTunnelStackForwarder) NewConnection(ctx context.Context, conn net.Conn, metadata M.Metadata) error {
	h.mu.Lock()
	closed := h.closed
	h.mu.Unlock()
	if !closed && h.isLocal(metadata.Destination.AddrPort()) && ctx.Err() == nil {
		serve, intercept := h.tcp(metadata.Source.AddrPort(), metadata.Destination.AddrPort())
		if intercept && serve != nil {
			serve(conn)
			return nil
		}
	}
	_ = conn.Close()
	return errors.New("tunnel destination not forwarded")
}

func (h *oneTunnelStackForwarder) NewPacket(ctx context.Context, _ netip.AddrPort, packet *buf.Buffer, metadata M.Metadata,
	init func(N.PacketConn) N.PacketWriter) {
	defer packet.Release()
	flow := oneTunnelFlow{metadata.Source.AddrPort(), metadata.Destination.AddrPort()}
	if !h.isLocal(flow.destination) || ctx.Err() != nil {
		return
	}
	serve, intercept := h.udp(flow.source, flow.destination)
	if !intercept || serve == nil {
		return
	}
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return
	}
	conn := h.flows[flow]
	created := conn == nil
	if created {
		if len(h.flows) >= 64 {
			h.mu.Unlock()
			return
		}
		conn = &oneTunnelPacketConn{flow: flow, packets: make(chan []byte, 8), done: make(chan struct{})}
		conn.writer = init(conn)
		h.flows[flow] = conn
		conn.onClose = func() {
			h.mu.Lock()
			if h.flows[flow] == conn {
				delete(h.flows, flow)
			}
			h.mu.Unlock()
		}
	}
	// Do not block the stack's packet delivery loop when a peer is flooding.
	select {
	case conn.packets <- append([]byte(nil), packet.Bytes()...):
	default:
	}
	h.mu.Unlock()
	if created {
		serve(conn)
	}
}

func (h *oneTunnelStackForwarder) close() {
	h.mu.Lock()
	h.closed = true
	connections := make([]*oneTunnelPacketConn, 0, len(h.flows))
	for _, conn := range h.flows {
		connections = append(connections, conn)
	}
	h.mu.Unlock()
	for _, conn := range connections {
		_ = conn.Close()
	}
}

type oneTunnelPacketConn struct {
	flow    oneTunnelFlow
	packets chan []byte
	done    chan struct{}
	once    sync.Once
	onClose func()
	writer  N.PacketWriter
}

func (c *oneTunnelPacketConn) Read(bytes []byte) (int, error) {
	select {
	case <-c.done:
		return 0, net.ErrClosed
	case packet := <-c.packets:
		if len(packet) > len(bytes) {
			return copy(bytes, packet), io.ErrShortBuffer
		}
		return copy(bytes, packet), nil
	}
}
func (c *oneTunnelPacketConn) Write(bytes []byte) (int, error) {
	select {
	case <-c.done:
		return 0, net.ErrClosed
	default:
	}
	if err := c.writer.WritePacket(buf.As(bytes), M.SocksaddrFromNet(c.LocalAddr())); err != nil {
		return 0, err
	}
	return len(bytes), nil
}
func (c *oneTunnelPacketConn) Close() error {
	c.once.Do(func() {
		close(c.done)
		if c.onClose != nil {
			c.onClose()
		}
	})
	return nil
}
func (c *oneTunnelPacketConn) LocalAddr() net.Addr {
	return net.UDPAddrFromAddrPort(c.flow.destination)
}
func (c *oneTunnelPacketConn) RemoteAddr() net.Addr             { return net.UDPAddrFromAddrPort(c.flow.source) }
func (c *oneTunnelPacketConn) SetDeadline(time.Time) error      { return errors.ErrUnsupported }
func (c *oneTunnelPacketConn) SetReadDeadline(time.Time) error  { return errors.ErrUnsupported }
func (c *oneTunnelPacketConn) SetWriteDeadline(time.Time) error { return errors.ErrUnsupported }
func (c *oneTunnelPacketConn) ReadPacket(packet *buf.Buffer) (M.Socksaddr, error) {
	n, err := c.Read(packet.FreeBytes())
	packet.Truncate(packet.Len() + n)
	return M.SocksaddrFromNet(c.RemoteAddr()), err
}
func (c *oneTunnelPacketConn) WritePacket(packet *buf.Buffer, _ M.Socksaddr) error {
	defer packet.Release()
	_, err := c.Write(packet.Bytes())
	return err
}
