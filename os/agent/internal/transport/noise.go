package transport

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"

	"termbridge/internal/proto"
)

// CipherSuite is Noise_IK_25519_ChaChaPoly_BLAKE2s (architecture §8.1).
var CipherSuite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2s)

// Prologue binds the handshake to this protocol and version.
const Prologue = "TermBridge/1"

// HandshakeTimeout bounds the two handshake messages.
const HandshakeTimeout = 10 * time.Second

// ErrRejected reports a failed handshake or an unauthorized device.
var ErrRejected = errors.New("transport: handshake rejected")

// Peer is the authenticated device on the other end.
type Peer struct {
	Key  []byte // X25519 Noise static key
	Name string
}

// Authorizer decides, from the initiator's static key and the payload of its
// first handshake message, whether it may connect. Returning false drops the
// connection without any reply.
type Authorizer func(remoteStatic, payload []byte) (Peer, bool)

// accept runs the responder side of the IK handshake:
//
//	<- s               (the phone learned our static key from the QR code)
//	-> e, es, s, ss    msg1: the phone's static key and payload, encrypted
//	<- e, ee, se       msg2: sent only if the phone is authorized
func accept(ctx context.Context, mc *messageConn, static noise.DHKey, authorize Authorizer) (*noiseConn, Peer, error) {
	ctx, cancel := context.WithTimeout(ctx, HandshakeTimeout)
	defer cancel()
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite:   CipherSuite,
		Random:        rand.Reader,
		Pattern:       noise.HandshakeIK,
		Prologue:      []byte(Prologue),
		StaticKeypair: static,
	})
	if err != nil {
		return nil, Peer{}, fmt.Errorf("noise state: %w", err)
	}
	msg1, err := mc.ReadMessage(ctx)
	if err != nil {
		return nil, Peer{}, fmt.Errorf("%w: read: %v", ErrRejected, err)
	}
	payload, _, _, err := hs.ReadMessage(nil, msg1)
	if err != nil {
		return nil, Peer{}, fmt.Errorf("%w: %v", ErrRejected, err)
	}
	peer, ok := authorize(hs.PeerStatic(), payload)
	if !ok {
		return nil, Peer{}, fmt.Errorf("%w: unknown device", ErrRejected)
	}
	peer.Key = hs.PeerStatic()
	msg2, recv, send, err := hs.WriteMessage(nil, nil)
	if err != nil {
		return nil, Peer{}, fmt.Errorf("noise msg2: %w", err)
	}
	if err := mc.WriteMessage(ctx, msg2); err != nil {
		return nil, Peer{}, fmt.Errorf("send msg2: %w", err)
	}
	return &noiseConn{mc: mc, send: send, recv: recv}, peer, nil
}

// Dial connects to an agent as a device: it authenticates the agent by
// agentStatic (derived from the agent ID in the QR code) and sends payload,
// encrypted, in the first handshake message. Used by Go test clients.
func Dial(ctx context.Context, url string, agentStatic []byte, device noise.DHKey, payload []byte) (Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, HandshakeTimeout)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{Subprotocols: []string{proto.Subprotocol}})
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", url, err)
	}
	ws.SetReadLimit(proto.MaxMessage)
	mc := &messageConn{c: ws, remote: url}
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite:   CipherSuite,
		Random:        rand.Reader,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(Prologue),
		StaticKeypair: device,
		PeerStatic:    agentStatic,
	})
	if err != nil {
		_ = mc.Drop()
		return nil, fmt.Errorf("noise state: %w", err)
	}
	msg1, _, _, err := hs.WriteMessage(nil, payload)
	if err == nil {
		err = mc.WriteMessage(ctx, msg1)
	}
	if err != nil {
		_ = mc.Drop()
		return nil, fmt.Errorf("send msg1: %w", err)
	}
	msg2, err := mc.ReadMessage(ctx)
	if err != nil {
		_ = mc.Drop()
		return nil, fmt.Errorf("%w: %v", ErrRejected, err)
	}
	_, send, recv, err := hs.ReadMessage(nil, msg2)
	if err != nil {
		_ = mc.Drop()
		return nil, fmt.Errorf("%w: %v", ErrRejected, err)
	}
	return &noiseConn{mc: mc, send: send, recv: recv}, nil
}

// noiseConn encrypts every frame into one Noise transport message. Nonces
// advance per message, so reads and writes must each stay on one goroutine,
// which the link's read loop and single writer guarantee.
type noiseConn struct {
	mc         *messageConn
	send, recv *noise.CipherState
}

func (n *noiseConn) ReadFrame(ctx context.Context) (proto.Frame, error) {
	msg, err := n.mc.ReadMessage(ctx)
	if err != nil {
		return proto.Frame{}, err
	}
	plain, err := n.recv.Decrypt(nil, nil, msg)
	if err != nil {
		return proto.Frame{}, fmt.Errorf("transport: decrypt: %w", err)
	}
	return proto.Decode(plain)
}

func (n *noiseConn) WriteWire(ctx context.Context, wire []byte) error {
	buf := proto.GetBuffer(len(wire) + 16)
	defer proto.PutBuffer(buf)
	sealed, err := n.send.Encrypt((*buf)[:0], nil, wire)
	if err != nil {
		return fmt.Errorf("transport: encrypt: %w", err)
	}
	return n.mc.WriteMessage(ctx, sealed)
}

func (n *noiseConn) Close(reason string) error { return n.mc.Close(reason) }
func (n *noiseConn) RemoteAddr() string        { return n.mc.remote }
