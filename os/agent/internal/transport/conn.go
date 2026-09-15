// Package transport carries TermBridge frames between the agent and its
// peers: binary WebSocket messages, each one Noise transport message holding
// one frame (PROTOCOL.md §1). The relay (phase 5) plugs in below Conn.
package transport

import (
	"context"
	"errors"
	"io"

	"github.com/coder/websocket"

	"termbridge/internal/proto"
)

// Conn is an established, authenticated, encrypted frame connection.
type Conn interface {
	// ReadFrame returns the next frame; its payload is owned by the caller.
	// A clean close by the peer is reported as io.EOF.
	ReadFrame(ctx context.Context) (proto.Frame, error)
	// WriteWire sends one encoded frame. Not safe for concurrent use.
	WriteWire(ctx context.Context, wire []byte) error
	// Close tears the connection down.
	Close(reason string) error
	// RemoteAddr identifies the peer in logs.
	RemoteAddr() string
}

var errTextMessage = errors.New("transport: text message received; TermBridge is binary-only")

// messageConn moves raw binary WebSocket messages.
type messageConn struct {
	c      *websocket.Conn
	remote string
}

func (m *messageConn) ReadMessage(ctx context.Context) ([]byte, error) {
	typ, b, err := m.c.Read(ctx)
	if err != nil {
		switch websocket.CloseStatus(err) {
		case websocket.StatusNormalClosure, websocket.StatusGoingAway:
			return nil, io.EOF
		}
		return nil, err
	}
	if typ != websocket.MessageBinary {
		return nil, errTextMessage
	}
	return b, nil // freshly allocated by Read: the caller owns it
}

func (m *messageConn) WriteMessage(ctx context.Context, b []byte) error {
	return m.c.Write(ctx, websocket.MessageBinary, b)
}

func (m *messageConn) Close(reason string) error {
	const maxReason = 120 // close frames carry at most 123 bytes of reason
	if len(reason) > maxReason {
		reason = reason[:maxReason]
	}
	return m.c.Close(websocket.StatusNormalClosure, reason)
}

// Drop closes the TCP connection without a close handshake: rejected peers
// learn nothing (architecture §8.3).
func (m *messageConn) Drop() error { return m.c.CloseNow() }
