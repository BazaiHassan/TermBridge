package link

import (
	"bytes"
	"context"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"termbridge/agent/internal/session"
	"termbridge/internal/proto"
)

// pipeConn is an in-memory transport.Conn; the test plays the app.
type pipeConn struct {
	toAgent   chan []byte
	fromAgent chan []byte
	closed    chan struct{}
	once      sync.Once
}

func newPipe() *pipeConn {
	return &pipeConn{toAgent: make(chan []byte, 64), fromAgent: make(chan []byte, 4096), closed: make(chan struct{})}
}

func (p *pipeConn) ReadFrame(ctx context.Context) (proto.Frame, error) {
	select {
	case b := <-p.toAgent:
		return proto.Decode(b)
	case <-p.closed:
		return proto.Frame{}, io.EOF
	case <-ctx.Done():
		return proto.Frame{}, ctx.Err()
	}
}

func (p *pipeConn) WriteWire(ctx context.Context, wire []byte) error {
	select {
	case p.fromAgent <- bytes.Clone(wire):
		return nil
	case <-p.closed:
		return io.ErrClosedPipe
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (p *pipeConn) Close(string) error {
	p.once.Do(func() { close(p.closed) })
	return nil
}

func (p *pipeConn) RemoteAddr() string { return "192.168.1.50:40000" }

type app struct {
	t    *testing.T
	pipe *pipeConn
	done chan struct{} // closed when Serve returns; err holds its result
	err  error
	mgr  *session.Manager
	reg  *Registry
}

func startLink(t *testing.T) *app {
	t.Helper()
	mgr := session.NewManager(session.Config{Shell: "/bin/sh"})
	reg := NewRegistry(0, nil, nil)
	t.Cleanup(reg.Close)
	return connect(t, mgr, reg, "phone-key")
}

// connect starts a second connection to the same agent, as device key.
func connect(t *testing.T, mgr *session.Manager, reg *Registry, key string) *app {
	t.Helper()
	pipe := newPipe()
	opts := Options{
		Sessions: mgr, Registry: reg, PeerKey: []byte(key),
		Ack: proto.HelloAck{V: proto.Version, Agent: "test-agent", OS: "linux", Hostname: "box"},
	}
	a := &app{t: t, pipe: pipe, done: make(chan struct{}), mgr: mgr, reg: reg}
	go func() {
		a.err = Serve(context.Background(), pipe, opts)
		close(a.done)
	}()
	t.Cleanup(func() { _ = pipe.Close("test over"); a.wait() })
	return a
}

func (a *app) send(f proto.Frame) {
	a.t.Helper()
	b, err := proto.Encode(f)
	if err != nil {
		a.t.Fatal(err)
	}
	a.pipe.toAgent <- b
}

func (a *app) sendJSON(op proto.Opcode, v any) {
	a.t.Helper()
	f, err := proto.JSONFrame(op, proto.ControlSession, v)
	if err != nil {
		a.t.Fatal(err)
	}
	a.send(f)
}

func (a *app) recv() proto.Frame {
	a.t.Helper()
	select {
	case b := <-a.pipe.fromAgent:
		f, err := proto.Decode(b)
		if err != nil {
			a.t.Fatal(err)
		}
		return f
	case <-time.After(10 * time.Second):
		a.t.Fatal("no frame from agent")
		return proto.Frame{}
	}
}

func (a *app) expect(op proto.Opcode) proto.Frame {
	a.t.Helper()
	f := a.recv()
	if f.Op != op {
		a.t.Fatalf("got %s %q, want %s", f.Op, f.Payload, op)
	}
	return f
}

func (a *app) hello() {
	a.t.Helper()
	a.sendJSON(proto.OpHello, proto.Hello{V: 1, Client: "test/1"})
	var ack proto.HelloAck
	if err := proto.ParseJSON(a.expect(proto.OpHelloAck), &ack); err != nil || ack.Agent != "test-agent" {
		a.t.Fatalf("ack %+v, %v", ack, err)
	}
}

func (a *app) open() uint8 {
	a.t.Helper()
	a.sendJSON(proto.OpSessionOpen, proto.SessionOpen{Cols: 80, Rows: 24})
	id, err := proto.ParseSessionOpened(a.expect(proto.OpSessionOpened))
	if err != nil {
		a.t.Fatal(err)
	}
	return id
}

// untilExit collects a session's output up to its SESSION_EXIT.
func (a *app) untilExit(id uint8) (string, int32) {
	a.t.Helper()
	var out strings.Builder
	for {
		f := a.recv()
		switch {
		case f.Op == proto.OpData && f.Session == id:
			out.Write(f.Payload)
		case f.Op == proto.OpSessionExit && f.Session == id:
			code, err := proto.ParseSessionExit(f)
			if err != nil {
				a.t.Fatal(err)
			}
			return out.String(), code
		default:
			a.t.Fatalf("unexpected %s for session %d", f.Op, f.Session)
		}
	}
}

func (a *app) wait() error {
	select {
	case <-a.done:
		return a.err
	case <-time.After(10 * time.Second):
		a.t.Fatal("Serve did not return")
		return nil
	}
}

func errorCode(t *testing.T, f proto.Frame) string {
	t.Helper()
	var e proto.ErrorMsg
	if err := proto.ParseJSON(f, &e); err != nil {
		t.Fatal(err)
	}
	return e.Code
}

func TestFirstFrameMustBeHello(t *testing.T) {
	a := startLink(t)
	a.send(proto.Ping(1))
	if code := errorCode(t, a.expect(proto.OpError)); code != proto.CodeHelloRequired {
		t.Fatalf("code = %s", code)
	}
	if err := a.wait(); err == nil {
		t.Fatal("Serve accepted a connection without HELLO")
	}
}

func TestUnsupportedVersion(t *testing.T) {
	a := startLink(t)
	a.sendJSON(proto.OpHello, proto.Hello{V: 2, Client: "future"})
	if code := errorCode(t, a.expect(proto.OpError)); code != proto.CodeUnsupportedVersion {
		t.Fatalf("code = %s", code)
	}
}

func TestPingPong(t *testing.T) {
	a := startLink(t)
	a.hello()
	a.send(proto.Ping(1757851200123))
	ts, err := proto.ParseTimestamp(a.expect(proto.OpPong))
	if err != nil || ts != 1757851200123 {
		t.Fatalf("pong ts = %d, %v", ts, err)
	}
}

func TestSessionLifecycle(t *testing.T) {
	a := startLink(t)
	a.hello()
	id := a.open()
	a.send(proto.Resize(id, 100, 30))
	// he''llo: the shell prints hello, the tty echo of the input does not.
	a.send(proto.Data(id, []byte("echo he''llo; stty size; exit 7\n")))
	out, code := a.untilExit(id)
	if code != 7 {
		t.Fatalf("exit = %d, want 7", code)
	}
	for _, want := range []string{"hello", "30 100"} {
		if !strings.Contains(out, want) {
			t.Errorf("output %q lacks %q", out, want)
		}
	}
}

func TestSessionCloseFromApp(t *testing.T) {
	a := startLink(t)
	a.hello()
	id := a.open()
	a.send(proto.SessionClose(id))
	if _, code := a.untilExit(id); code != 128+1 {
		t.Fatalf("exit = %d, want 129 (SIGHUP)", code)
	}
}

func TestUnknownOpcodeIsReportedNotFatal(t *testing.T) {
	a := startLink(t)
	a.hello()
	a.send(proto.Frame{Op: 0x7F})
	if code := errorCode(t, a.expect(proto.OpError)); code != proto.CodeUnsupportedOpcode {
		t.Fatalf("code = %s", code)
	}
	a.send(proto.Ping(9))
	a.expect(proto.OpPong)
}

func TestDisconnectKeepsSessionAndSameDeviceReattaches(t *testing.T) {
	a := startLink(t)
	a.hello()
	id := a.open()
	// Output produced while nobody is connected must be replayed.
	a.send(proto.Data(id, []byte("sleep 0.5; echo AWAY-$((20+22))\n")))
	_ = a.pipe.Close("wifi dropped")
	if err := a.wait(); err != nil {
		t.Fatalf("Serve = %v", err)
	}
	if a.reg.Count() != 1 {
		t.Fatalf("session did not survive the disconnect (count %d)", a.reg.Count())
	}
	time.Sleep(900 * time.Millisecond)

	b := connect(t, a.mgr, a.reg, "phone-key")
	b.hello()
	b.send(proto.SessionAttach(id, 100, 30))
	b.expect(proto.OpAttached)
	var out strings.Builder
	deadline := time.After(5 * time.Second)
	for !strings.Contains(out.String(), "AWAY-42") {
		select {
		case raw := <-b.pipe.fromAgent:
			f, _ := proto.Decode(raw)
			if f.Op == proto.OpData {
				out.Write(f.Payload)
			}
		case <-deadline:
			t.Fatalf("replay lacks AWAY-42: %q", out.String())
		}
	}
	b.send(proto.Data(id, []byte("exit 3\n")))
	if _, code := b.untilExit(id); code != 3 {
		t.Fatalf("exit = %d, want 3", code)
	}
}

func TestOtherDeviceCannotAttach(t *testing.T) {
	a := startLink(t)
	a.hello()
	id := a.open()
	_ = a.pipe.Close("gone")
	a.wait()
	stranger := connect(t, a.mgr, a.reg, "other-key")
	stranger.hello()
	stranger.send(proto.SessionAttach(id, 80, 24))
	f := stranger.expect(proto.OpError)
	if code := errorCode(t, f); code != proto.CodeUnknownSession || f.Session != id {
		t.Fatalf("got %s for session %d", code, f.Session)
	}
}

func TestResumeWindowHangsUpDetachedSession(t *testing.T) {
	mgr := session.NewManager(session.Config{Shell: "/bin/sh"})
	reg := NewRegistry(200*time.Millisecond, nil, nil)
	t.Cleanup(reg.Close)
	a := connect(t, mgr, reg, "phone-key")
	a.hello()
	a.open()
	_ = a.pipe.Close("gone")
	a.wait()
	deadline := time.Now().Add(5 * time.Second)
	for reg.Count() > 0 {
		if time.Now().After(deadline) {
			t.Fatal("detached session outlived the resume window")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if mgr.Count() != 0 {
		t.Fatalf("%d session IDs still reserved", mgr.Count())
	}
}

func TestExitWhileDetachedIsReportedOnAttach(t *testing.T) {
	a := startLink(t)
	a.hello()
	id := a.open()
	a.send(proto.Data(id, []byte("sleep 0.3; exit 5\n")))
	_ = a.pipe.Close("gone")
	a.wait()
	time.Sleep(800 * time.Millisecond)
	b := connect(t, a.mgr, a.reg, "phone-key")
	b.hello()
	b.send(proto.SessionAttach(id, 80, 24))
	b.expect(proto.OpAttached)
	if _, code := b.untilExit(id); code != 5 {
		t.Fatalf("exit = %d, want 5", code)
	}
}
