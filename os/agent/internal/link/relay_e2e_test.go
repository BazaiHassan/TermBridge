package link_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"

	"termbridge/agent/internal/link"
	"termbridge/agent/internal/session"
	"termbridge/agent/internal/transport"
	"termbridge/internal/identity"
	"termbridge/internal/proto"
	"termbridge/relay/hub"
	"termbridge/relay/ws"
)

var discard = slog.New(slog.NewTextHandler(io.Discard, nil))

// tap is a TCP proxy in front of the relay that records every byte the
// relay receives from or sends to the phone.
type tap struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (t *tap) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.buf.Write(p)
}

func (t *tap) bytes() []byte {
	t.mu.Lock()
	defer t.mu.Unlock()
	return bytes.Clone(t.buf.Bytes())
}

func startTap(t *testing.T, target string) (*tap, string) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	tp := &tap{}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				u, err := net.Dial("tcp", target)
				if err != nil {
					return
				}
				defer u.Close()
				go func() { _, _ = io.Copy(io.MultiWriter(u, tp), c) }()
				_, _ = io.Copy(io.MultiWriter(c, tp), u)
			}(c)
		}
	}()
	return tp, ln.Addr().String()
}

func startRelay(t *testing.T) string {
	t.Helper()
	srv := httptest.NewServer((&ws.Server{Hub: hub.New(hub.Config{}), Logger: discard}).Handler())
	t.Cleanup(srv.Close)
	return srv.Listener.Addr().String()
}

// startRelayedAgent connects a real agent (link + Noise) to the relay.
func startRelayedAgent(t *testing.T, relayAddr string, trusted noise.DHKey) (ed25519.PrivateKey, []byte) {
	t.Helper()
	agentEd := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{5}, 32))
	priv, pub, err := identity.NoiseKey(agentEd)
	if err != nil {
		t.Fatal(err)
	}
	opts := link.Options{
		Sessions: session.NewManager(session.Config{Shell: "/bin/sh"}),
		Ack:      proto.HelloAck{V: proto.Version, Agent: "relay-e2e", OS: runtime.GOOS, Hostname: "box"},
	}
	ctx, cancel := context.WithCancel(context.Background())
	status := make(chan transport.RelayStatus, 8)
	done := make(chan struct{})
	go func() {
		defer close(done)
		transport.RunRelay(ctx, transport.RelayConfig{
			URL:      "ws://" + relayAddr,
			Identity: agentEd,
			Static:   noise.DHKey{Private: priv, Public: pub},
			Authorize: func(key, _ []byte) (transport.Peer, bool) {
				return transport.Peer{Name: "phone"}, bytes.Equal(key, trusted.Public)
			},
			Handler: func(ctx context.Context, c transport.Conn, _ transport.Peer) { _ = link.Serve(ctx, c, opts) },
			Logger:  discard,
			OnStatus: func(s transport.RelayStatus) {
				select {
				case status <- s:
				default:
				}
			},
		})
	}()
	t.Cleanup(func() { cancel(); <-done })
	select {
	case s := <-status:
		if !s.Connected {
			t.Fatalf("agent could not connect to relay: %v", s.Err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("agent never reached the relay")
	}
	agentStatic, err := identity.NoisePublic(agentEd.Public().(ed25519.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	return agentEd, agentStatic
}

// TestRelayedShellAndRelayIsBlind is the architecture's §6/§8.4 requirement:
// a full session works through the relay, and the relay never carries a
// single byte of plaintext.
func TestRelayedShellAndRelayIsBlind(t *testing.T) {
	relayAddr := startRelay(t)
	tp, tapAddr := startTap(t, relayAddr)
	phone := deviceKey(t, "phone")
	agentEd, agentStatic := startRelayedAgent(t, relayAddr, phone)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	url := transport.RelayClientURL("ws://"+tapAddr, agentEd.Public().(ed25519.PublicKey))
	c, err := transport.Dial(ctx, url, agentStatic, phone, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close("done")
	send := func(f proto.Frame) {
		b, _ := proto.Encode(f)
		if err := c.WriteWire(ctx, b); err != nil {
			t.Fatal(err)
		}
	}
	recv := func() proto.Frame {
		f, err := c.ReadFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	hello, _ := proto.JSONFrame(proto.OpHello, 0, proto.Hello{V: 1, Client: "blind-test-client"})
	send(hello)
	if f := recv(); f.Op != proto.OpHelloAck {
		t.Fatalf("got %s", f.Op)
	}
	open, _ := proto.JSONFrame(proto.OpSessionOpen, 0, proto.SessionOpen{Cols: 80, Rows: 24})
	send(open)
	id, err := proto.ParseSessionOpened(recv())
	if err != nil {
		t.Fatal(err)
	}
	send(proto.Data(id, []byte("echo TOP-SECRET-$((40+2))-MARKER; exit\n")))
	var out strings.Builder
	for {
		f := recv()
		if f.Op == proto.OpSessionExit {
			break
		}
		out.Write(f.Payload)
	}
	if !strings.Contains(out.String(), "TOP-SECRET-42-MARKER") {
		t.Fatalf("shell output %q", out.String())
	}

	seen := tp.bytes()
	if len(seen) < 512 {
		t.Fatalf("tap saw only %d bytes: traffic did not go through the relay", len(seen))
	}
	for _, plain := range []string{"TOP-SECRET", "blind-test-client", "echo", "relay-e2e", "HELLO"} {
		if bytes.Contains(seen, []byte(plain)) {
			t.Fatalf("relay carried plaintext %q", plain)
		}
	}
}

func TestRelayReportsOfflineAgent(t *testing.T) {
	relayAddr := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	unknown := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))
	c, _, err := websocket.Dial(ctx, "ws://"+relayAddr+"/client?agent="+unknown, &websocket.DialOptions{Subprotocols: []string{proto.Subprotocol}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	_, _, err = c.Read(ctx)
	if websocket.CloseStatus(err) != 4004 {
		t.Fatalf("close = %v, want 4004 agent offline", err)
	}
}

func TestRelayRejectsForgedAgent(t *testing.T) {
	relayAddr := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws://"+relayAddr+"/agent", &websocket.DialOptions{Subprotocols: []string{proto.RelaySubprotocol}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	if _, _, err := c.Read(ctx); err != nil { // challenge
		t.Fatal(err)
	}
	victim := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{8}, 32)).Public().(ed25519.PublicKey)
	forged, _ := json.Marshal(proto.RelayMsg{
		Type:    "hello",
		AgentID: base64.StdEncoding.EncodeToString(victim),
		Sig:     base64.StdEncoding.EncodeToString(make([]byte, ed25519.SignatureSize)),
	})
	if err := c.Write(ctx, websocket.MessageText, forged); err != nil {
		t.Fatal(err)
	}
	_, _, err = c.Read(ctx)
	if websocket.CloseStatus(err) != 4001 {
		t.Fatalf("close = %v, want 4001 authentication failed", err)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("relay never answered")
	}
}
