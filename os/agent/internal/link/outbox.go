package link

import (
	"context"
	"errors"
	"fmt"
	"sync"

	"termbridge/agent/internal/transport"
	"termbridge/internal/proto"
)

// maxControlQueued caps queued control frames; a peer that floods PINGs
// without reading PONGs is disconnected instead of growing the queue.
const maxControlQueued = 256

var (
	errOutboxClosed = errors.New("link: send queue closed")
	errControlFlood = errors.New("link: control queue overflow")
)

// outbox is the per-connection send queue (PROTOCOL.md §5).
//
// Control frames jump the queue so keepalive survives bulk output. Session
// frames stay FIFO so SESSION_OPENED precedes a session's DATA and
// SESSION_EXIT follows it. Session frames are budgeted by the capacity of
// the pooled buffers they hold; when the budget is spent, producers block,
// which stops the PTY pumps instead of growing memory without bound.
type outbox struct {
	mu      sync.Mutex
	ctrl    []*[]byte
	data    []*[]byte
	used    int // buffer capacity held by queued and in-flight data
	limit   int
	waiting int           // producers blocked on the budget
	space   chan struct{} // closed and replaced to wake every waiter
	closed  bool
	wake    chan struct{} // cap 1: wakes the writer
}

func newOutbox(limit int) *outbox {
	return &outbox{limit: limit, space: make(chan struct{}), wake: make(chan struct{}, 1)}
}

func encode(f proto.Frame) (*[]byte, error) {
	buf := proto.GetBuffer(proto.HeaderSize + len(f.Payload))
	b, err := proto.AppendEncode(*buf, f)
	if err != nil {
		proto.PutBuffer(buf)
		return nil, err
	}
	*buf = b
	return buf, nil
}

// control queues a connection-level frame ahead of all session data. It
// never blocks.
func (o *outbox) control(f proto.Frame) error {
	buf, err := encode(f)
	if err != nil {
		return err
	}
	o.mu.Lock()
	switch {
	case o.closed:
		err = errOutboxClosed
	case len(o.ctrl) >= maxControlQueued:
		err = errControlFlood
	default:
		o.ctrl = append(o.ctrl, buf)
	}
	o.mu.Unlock()
	if err != nil {
		proto.PutBuffer(buf)
		return err
	}
	o.signal()
	return nil
}

// session queues a session-scoped frame, blocking while the queue is over
// budget. One frame is always admitted into an empty queue.
func (o *outbox) session(ctx context.Context, f proto.Frame) error {
	buf, err := encode(f)
	if err != nil {
		return err
	}
	size := cap(*buf)
	o.mu.Lock()
	for !o.closed && o.used > 0 && o.used+size > o.limit {
		space := o.space
		o.waiting++
		o.mu.Unlock()
		select {
		case <-space:
		case <-ctx.Done():
			o.mu.Lock()
			o.waiting--
			o.mu.Unlock()
			proto.PutBuffer(buf)
			return ctx.Err()
		}
		o.mu.Lock()
		o.waiting--
	}
	if o.closed {
		o.mu.Unlock()
		proto.PutBuffer(buf)
		return errOutboxClosed
	}
	o.data = append(o.data, buf)
	o.used += size
	o.mu.Unlock()
	o.signal()
	return nil
}

func (o *outbox) signal() {
	select {
	case o.wake <- struct{}{}:
	default:
	}
}

// run is the single writer. It returns nil once the outbox is closed and
// drained, or the first write error.
func (o *outbox) run(ctx context.Context, c transport.Conn) error {
	for {
		buf, isData, done := o.pop()
		if done {
			return nil
		}
		if buf == nil {
			select {
			case <-o.wake:
				continue
			case <-ctx.Done():
				o.fail()
				return ctx.Err()
			}
		}
		err := c.WriteWire(ctx, *buf)
		if isData {
			o.release(cap(*buf))
		}
		proto.PutBuffer(buf)
		if err != nil {
			o.fail()
			return fmt.Errorf("write: %w", err)
		}
	}
}

func (o *outbox) pop() (buf *[]byte, isData, done bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	switch {
	case len(o.ctrl) > 0:
		buf, o.ctrl[0], o.ctrl = o.ctrl[0], nil, o.ctrl[1:]
	case len(o.data) > 0:
		buf, o.data[0], o.data = o.data[0], nil, o.data[1:]
		isData = true
	case o.closed:
		done = true
	}
	return buf, isData, done
}

func (o *outbox) release(n int) {
	o.mu.Lock()
	o.used -= n
	o.wakeProducersLocked()
	o.mu.Unlock()
}

// close stops accepting frames; the writer drains what is queued and exits.
func (o *outbox) close() {
	o.mu.Lock()
	o.closed = true
	o.wakeProducersLocked()
	o.mu.Unlock()
	o.signal()
}

// fail closes the outbox after a write error and drops everything queued.
func (o *outbox) fail() {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.closed = true
	for _, b := range o.ctrl {
		proto.PutBuffer(b)
	}
	for _, b := range o.data {
		proto.PutBuffer(b)
	}
	o.ctrl, o.data, o.used = nil, nil, 0
	o.wakeProducersLocked()
}

func (o *outbox) wakeProducersLocked() {
	if o.waiting > 0 {
		close(o.space)
		o.space = make(chan struct{})
	}
}
