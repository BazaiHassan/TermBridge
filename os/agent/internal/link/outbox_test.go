package link

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"termbridge/internal/proto"
)

// gateConn lets a test decide when each write completes.
type gateConn struct {
	gate    chan struct{}
	written chan []byte
	fail    error
}

func newGateConn() *gateConn {
	return &gateConn{gate: make(chan struct{}, 64), written: make(chan []byte, 64)}
}

func (g *gateConn) ReadFrame(ctx context.Context) (proto.Frame, error) {
	<-ctx.Done()
	return proto.Frame{}, ctx.Err()
}

func (g *gateConn) WriteWire(ctx context.Context, wire []byte) error {
	if g.fail != nil {
		return g.fail
	}
	select {
	case <-g.gate:
	case <-ctx.Done():
		return ctx.Err()
	}
	g.written <- bytes.Clone(wire)
	return nil
}

func (g *gateConn) Close(string) error { return nil }
func (g *gateConn) RemoteAddr() string { return "test" }

func (g *gateConn) nextOp(t *testing.T) proto.Opcode {
	t.Helper()
	select {
	case b := <-g.written:
		return proto.Opcode(b[0])
	case <-time.After(2 * time.Second):
		t.Fatal("nothing written")
		return 0
	}
}

func startOutbox(t *testing.T, limit int, c *gateConn) (*outbox, <-chan error) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	o := newOutbox(limit)
	done := make(chan error, 1)
	go func() { done <- o.run(ctx, c) }()
	return o, done
}

func TestOutboxBlocksProducersOverBudget(t *testing.T) {
	c := newGateConn()
	// 3000-byte payloads use 4096-byte buffers: two fit an 8192 budget.
	o, _ := startOutbox(t, 8192, c)
	payload := make([]byte, 3000)
	ctx := context.Background()
	for range 2 {
		if err := o.session(ctx, proto.Data(1, payload)); err != nil {
			t.Fatal(err)
		}
	}
	third := make(chan error, 1)
	go func() { third <- o.session(ctx, proto.Data(1, payload)) }()
	select {
	case err := <-third:
		t.Fatalf("third frame admitted over budget (err %v)", err)
	case <-time.After(100 * time.Millisecond):
	}
	c.gate <- struct{}{} // one write completes and frees its budget
	select {
	case err := <-third:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("producer not woken after write")
	}
}

func TestOutboxControlJumpsQueue(t *testing.T) {
	c := newGateConn()
	o, _ := startOutbox(t, 1<<20, c)
	ctx := context.Background()
	_ = o.session(ctx, proto.Data(1, []byte("a")))
	time.Sleep(20 * time.Millisecond) // writer now blocked on the first frame
	_ = o.session(ctx, proto.Data(1, []byte("b")))
	_ = o.control(proto.Pong(1))
	for range 3 {
		c.gate <- struct{}{}
	}
	got := []proto.Opcode{c.nextOp(t), c.nextOp(t), c.nextOp(t)}
	want := []proto.Opcode{proto.OpData, proto.OpPong, proto.OpData}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("write order %v, want %v", got, want)
		}
	}
}

func TestOutboxDrainsOnClose(t *testing.T) {
	c := newGateConn()
	o, done := startOutbox(t, 1<<20, c)
	_ = o.control(proto.Pong(7))
	o.close()
	c.gate <- struct{}{}
	if op := c.nextOp(t); op != proto.OpPong {
		t.Fatalf("wrote %s", op)
	}
	if err := <-done; err != nil {
		t.Fatalf("run = %v, want nil after drain", err)
	}
	if err := o.control(proto.Pong(8)); !errors.Is(err, errOutboxClosed) {
		t.Fatalf("control after close = %v", err)
	}
}

func TestOutboxWriteFailureReleasesProducers(t *testing.T) {
	c := newGateConn()
	c.fail = errors.New("broken pipe")
	o, done := startOutbox(t, 1, c)
	ctx := context.Background()
	_ = o.session(ctx, proto.Data(1, []byte("a")))
	if err := <-done; err == nil {
		t.Fatal("run survived write error")
	}
	if err := o.session(ctx, proto.Data(1, []byte("b"))); !errors.Is(err, errOutboxClosed) {
		t.Fatalf("session after failure = %v", err)
	}
}
