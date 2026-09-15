package session

import (
	"context"
	"time"

	"termbridge/internal/proto"
)

// chunk is one PTY read held in a pooled buffer.
type chunk struct{ buf *[]byte }

// coalesce forwards PTY output from in to send.
//
// When output has been idle, the next chunk is sent immediately so keystroke
// echo never waits. That send opens a window: anything arriving within it is
// merged and flushed when the window ends or the buffer reaches maxChunk. The
// window stays open while output keeps coming, so bulk output (cat of a big
// file) becomes one frame per window instead of one per read, and it closes
// after one quiet window.
func coalesce(ctx context.Context, in <-chan chunk, send func([]byte) error, window time.Duration, maxChunk int) error {
	pending := make([]byte, 0, maxChunk)
	var (
		timer *time.Timer
		tick  <-chan time.Time // nil while the window is closed
	)
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()
	flush := func() error {
		if len(pending) == 0 {
			return nil
		}
		err := send(pending)
		pending = pending[:0]
		return err
	}
	// Only called with the window closed, i.e. the timer fired and was
	// drained (or never started), so Reset is safe on every Go version.
	openWindow := func() {
		if timer == nil {
			timer = time.NewTimer(window)
		} else {
			timer.Reset(window)
		}
		tick = timer.C
	}

	for {
		select {
		case c, ok := <-in:
			if !ok {
				return flush()
			}
			err := consume(*c.buf, tick == nil, &pending, maxChunk, send, flush)
			proto.PutBuffer(c.buf)
			if err != nil {
				return err
			}
			if tick == nil {
				openWindow()
			}
		case <-tick:
			if len(pending) == 0 {
				tick = nil // a whole quiet window: back to idle
				continue
			}
			if err := flush(); err != nil {
				return err
			}
			timer.Reset(window)
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

// consume sends data straight away when idle, otherwise appends it to
// pending, flushing whenever pending reaches maxChunk.
func consume(data []byte, idle bool, pending *[]byte, maxChunk int, send func([]byte) error, flush func() error) error {
	if idle {
		for len(data) > 0 {
			n := min(len(data), maxChunk)
			if err := send(data[:n]); err != nil {
				return err
			}
			data = data[n:]
		}
		return nil
	}
	for len(data) > 0 {
		n := min(maxChunk-len(*pending), len(data))
		*pending = append(*pending, data[:n]...)
		data = data[n:]
		if len(*pending) == maxChunk {
			if err := flush(); err != nil {
				return err
			}
		}
	}
	return nil
}
