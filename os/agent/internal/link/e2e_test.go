package link_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"errors"
	"net/http"
	"net/netip"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"

	"termbridge/agent/internal/link"
	"termbridge/agent/internal/session"
	"termbridge/agent/internal/transport"
	"termbridge/internal/identity"
	"termbridge/internal/proto"
)

type agentUnderTest struct {
	url         string
	agentStatic []byte // what a phone derives from the agent ID
}

func deviceKey(t *testing.T, label string) noise.DHKey {
	t.Helper()
	seed := sha256.Sum256([]byte(label))
	k, err := noise.DH25519.GenerateKeypair(bytes.NewReader(seed[:]))
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// startAgent runs the real LAN server + Noise + link on 127.0.0.1; only
// `trusted` may connect.
func startAgent(t *testing.T, trusted noise.DHKey, allow func(netip.Addr) bool) agentUnderTest {
	t.Helper()
	agentEd := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{9}, 32))
	priv, pub, err := identity.NoiseKey(agentEd)
	if err != nil {
		t.Fatal(err)
	}
	reg := link.NewRegistry(0, nil, nil)
	t.Cleanup(reg.Close)
	opts := link.Options{
		Registry: reg,
		Sessions: session.NewManager(session.Config{Shell: "/bin/sh"}),
		Ack:      proto.HelloAck{V: proto.Version, Agent: "e2e", OS: runtime.GOOS, Hostname: "box"},
	}
	srv, err := transport.Listen(transport.ServerConfig{
		Addrs:     []string{"127.0.0.1:0"},
		Static:    noise.DHKey{Private: priv, Public: pub},
		AllowPeer: allow,
		Authorize: func(key, _ []byte) (transport.Peer, bool) {
			return transport.Peer{Name: "test phone"}, bytes.Equal(key, trusted.Public)
		},
		Handler: func(ctx context.Context, c transport.Conn, p transport.Peer) {
			o := opts
			o.PeerKey = p.Key
			_ = link.Serve(ctx, c, o)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() { served <- srv.Serve(ctx) }()
	t.Cleanup(func() {
		cancel()
		if err := <-served; err != nil {
			t.Errorf("Serve: %v", err)
		}
	})
	fromID, err := identity.NoisePublic(agentEd.Public().(ed25519.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	return agentUnderTest{url: "ws://" + srv.Addrs()[0].String() + transport.Path, agentStatic: fromID}
}

func TestEndToEndEncryptedShell(t *testing.T) {
	phone := deviceKey(t, "phone")
	a := startAgent(t, phone, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	c, err := transport.Dial(ctx, a.url, a.agentStatic, phone, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close("test over")
	send := func(f proto.Frame) {
		b, err := proto.Encode(f)
		if err != nil {
			t.Fatal(err)
		}
		if err := c.WriteWire(ctx, b); err != nil {
			t.Fatal(err)
		}
	}
	jsonFrame := func(op proto.Opcode, v any) proto.Frame {
		f, err := proto.JSONFrame(op, proto.ControlSession, v)
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	recv := func() proto.Frame {
		f, err := c.ReadFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return f
	}

	send(jsonFrame(proto.OpHello, proto.Hello{V: 1, Client: "e2e/1"}))
	if f := recv(); f.Op != proto.OpHelloAck {
		t.Fatalf("got %s, want HELLO_ACK", f.Op)
	}
	send(jsonFrame(proto.OpSessionOpen, proto.SessionOpen{Cols: 80, Rows: 24}))
	id, err := proto.ParseSessionOpened(recv())
	if err != nil {
		t.Fatal(err)
	}
	send(proto.Data(id, []byte("echo he''llo encrypted; exit\n")))
	var out strings.Builder
	for {
		f := recv()
		if f.Op == proto.OpSessionExit {
			break
		}
		out.Write(f.Payload)
	}
	if !strings.Contains(out.String(), "hello encrypted") {
		t.Fatalf("output %q", out.String())
	}
}

func TestUnpairedDeviceIsDroppedSilently(t *testing.T) {
	a := startAgent(t, deviceKey(t, "phone"), nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := transport.Dial(ctx, a.url, a.agentStatic, deviceKey(t, "stranger"), nil)
	if !errors.Is(err, transport.ErrRejected) {
		t.Fatalf("stranger dial = %v, want ErrRejected", err)
	}
}

func TestWrongAgentKeyFailsHandshake(t *testing.T) {
	phone := deviceKey(t, "phone")
	a := startAgent(t, phone, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	impostor := deviceKey(t, "impostor agent").Public // phone expects another agent key
	if _, err := transport.Dial(ctx, a.url, impostor, phone, nil); !errors.Is(err, transport.ErrRejected) {
		t.Fatalf("dial with wrong agent key = %v, want ErrRejected", err)
	}
}

func TestRejectsClientWithoutSubprotocol(t *testing.T) {
	a := startAgent(t, deviceKey(t, "phone"), nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, a.url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	_, _, err = ws.Read(ctx)
	if websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("read err = %v, want policy violation close", err)
	}
}

func TestRejectsPeerOutsideLAN(t *testing.T) {
	a := startAgent(t, deviceKey(t, "phone"), func(netip.Addr) bool { return false })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, a.url, &websocket.DialOptions{Subprotocols: []string{proto.Subprotocol}})
	if err == nil {
		t.Fatal("dial succeeded from a disallowed peer")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("resp = %v, want 403", resp)
	}
}
