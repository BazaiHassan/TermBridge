package link

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"time"

	"termbridge/agent/internal/session"
	"termbridge/internal/proto"
)

const (
	// DefaultResumeWindow is how long a detached session keeps running.
	DefaultResumeWindow = 15 * time.Minute
	// DefaultReplayBuffer bounds the output held for a detached session.
	DefaultReplayBuffer = 256 << 10

	truncatedNotice = "\r\n\x1b[2m[TermBridge: earlier output was dropped while you were away]\x1b[0m\r\n"
)

var errGone = errors.New("link: session is gone")

// Registry keeps sessions alive across connections (PROTOCOL.md §4.8). A
// session belongs to the device key that opened it; when its connection
// ends it keeps running for the resume window with its output buffered, and
// a new connection from the same device re-attaches it. One Registry per
// agent; safe for concurrent use.
type Registry struct {
	ctx      context.Context // lives as long as the agent; pumps run on it
	cancel   context.CancelFunc
	window   time.Duration
	bufLimit int
	events   Events
	log      *slog.Logger
	wg       sync.WaitGroup

	mu       sync.Mutex
	sessions map[uint8]*detachable
}

// NewRegistry returns a registry; zero window means DefaultResumeWindow and
// nil events/logger are allowed.
func NewRegistry(window time.Duration, events Events, log *slog.Logger) *Registry {
	if window <= 0 {
		window = DefaultResumeWindow
	}
	if events == nil {
		events = nopEvents{}
	}
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Registry{
		ctx: ctx, cancel: cancel, window: window, bufLimit: DefaultReplayBuffer,
		events: events, log: log, sessions: make(map[uint8]*detachable),
	}
}

// Close hangs up every session and waits until they are gone.
func (r *Registry) Close() {
	r.cancel()
	r.wg.Wait()
}

// Count returns the number of sessions, attached or not.
func (r *Registry) Count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions)
}

// CloseUnless hangs up the sessions whose owner fails keep, e.g. after
// `termbridge revoke`.
func (r *Registry) CloseUnless(keep func(owner []byte) bool) {
	r.mu.Lock()
	var doomed []*detachable
	for _, d := range r.sessions {
		if !keep(d.owner) {
			doomed = append(doomed, d)
		}
	}
	r.mu.Unlock()
	for _, d := range doomed {
		_ = d.s.Close()
	}
}

// adopt starts pumping a new session's output. It starts detached: the
// caller announces SESSION_OPENED and then attaches, so no early output is
// lost or reordered.
func (r *Registry) adopt(s *session.Session, owner []byte, peer string) *detachable {
	d := &detachable{r: r, id: s.ID(), s: s, owner: bytes.Clone(owner), peer: peer}
	r.mu.Lock()
	r.sessions[d.id] = d
	r.mu.Unlock()
	r.events.SessionOpened(peer, d.id)
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		code, err := s.Run(r.ctx, d.deliver)
		if err != nil {
			r.log.Info("session output ended", "session", d.id, "err", err)
		}
		d.finish(int32(code))
	}()
	return d
}

// find returns the session id if owner may attach it.
func (r *Registry) find(id uint8, owner []byte) *detachable {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d := r.sessions[id]; d != nil && bytes.Equal(d.owner, owner) {
		return d
	}
	return nil
}

func (r *Registry) release(d *detachable) {
	r.mu.Lock()
	if r.sessions[d.id] == d {
		delete(r.sessions, d.id)
	}
	r.mu.Unlock()
	d.s.Release()
	r.events.SessionClosed(d.peer, d.id)
}

// detachable is one session and where its output currently goes.
type detachable struct {
	r     *Registry
	id    uint8
	s     *session.Session
	owner []byte
	peer  string

	mu        sync.Mutex
	out       *outbox // the attached connection; nil while detached
	buf       []byte  // output held while detached
	truncated bool
	exited    bool
	code      int32
	expired   bool
	released  bool
	expiry    *time.Timer
}

// deliver is the session's output sink. It never loses output to a dead
// connection's closed queue: that output is held for the next attach.
func (d *detachable) deliver(ctx context.Context, p []byte) error {
	for {
		d.mu.Lock()
		out := d.out
		if out == nil {
			d.hold(p)
			d.mu.Unlock()
			return nil
		}
		d.mu.Unlock()
		err := out.session(ctx, proto.Data(d.id, p))
		if err == nil || ctx.Err() != nil {
			return err
		}
		d.detach(out) // that connection is closing: hold the output instead
	}
}

// hold appends p to the replay buffer, dropping the oldest bytes past the
// limit. d.mu must be held.
func (d *detachable) hold(p []byte) {
	limit := d.r.bufLimit
	if len(p) >= limit {
		d.buf = append(d.buf[:0], p[len(p)-limit:]...)
		d.truncated = true
		return
	}
	if over := len(d.buf) + len(p) - limit; over > 0 {
		d.buf = append(d.buf[:0], d.buf[over:]...)
		d.truncated = true
	}
	d.buf = append(d.buf, p...)
}

// attach makes out the session's connection. Held output is replayed first
// (after SESSION_ATTACHED when announce is set); an exit that happened while
// detached is reported and the session released.
func (d *detachable) attach(ctx context.Context, out *outbox, announce bool) error {
	d.mu.Lock()
	if d.released {
		d.mu.Unlock()
		return errGone
	}
	if d.expiry != nil {
		d.expiry.Stop()
		d.expiry = nil
	}
	d.out = nil // take it over from any previous connection
	send := func(f proto.Frame) error { return out.session(ctx, f) }
	var err error
	if announce {
		err = send(proto.SessionAttached(d.id))
	}
	if err == nil && d.truncated {
		err = send(proto.Data(d.id, []byte(truncatedNotice)))
	}
	for len(d.buf) > 0 && err == nil {
		n := min(len(d.buf), session.MaxChunk)
		err = send(proto.Data(d.id, d.buf[:n]))
		d.buf = d.buf[n:]
	}
	if err != nil {
		d.startExpiryLocked()
		d.mu.Unlock()
		return err
	}
	d.buf, d.truncated = nil, false
	if !d.exited {
		d.out = out
		d.mu.Unlock()
		return nil
	}
	d.released = true
	code := d.code
	d.mu.Unlock()
	_ = send(proto.SessionExit(d.id, code))
	d.r.release(d)
	return nil
}

// detach disconnects out; the session keeps running for the resume window.
func (d *detachable) detach(out *outbox) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.out == out {
		d.out = nil
		d.startExpiryLocked()
	}
}

func (d *detachable) attachedTo(out *outbox) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.out == out
}

func (d *detachable) startExpiryLocked() {
	if d.expiry == nil && !d.released {
		d.expiry = time.AfterFunc(d.r.window, d.expire)
	}
}

// expire ends a session nobody came back for.
func (d *detachable) expire() {
	d.mu.Lock()
	if d.out != nil || d.released {
		d.mu.Unlock()
		return
	}
	d.expiry = nil
	d.expired = true
	exited := d.exited
	if exited {
		d.released = true
	}
	d.mu.Unlock()
	if exited {
		d.r.release(d)
		return
	}
	d.r.log.Info("resume window over; hanging up", "session", d.id, "peer", d.peer)
	_ = d.s.Close() // finish() releases it
}

// finish runs when the shell has exited and its output is drained.
func (d *detachable) finish(code int32) {
	d.mu.Lock()
	d.exited, d.code = true, code
	if out := d.out; out != nil {
		d.out = nil
		d.released = true
		d.mu.Unlock()
		_ = out.session(d.r.ctx, proto.SessionExit(d.id, code))
		d.r.release(d)
		return
	}
	if d.expired || d.r.ctx.Err() != nil {
		d.released = true
		d.mu.Unlock()
		d.r.release(d)
		return
	}
	d.startExpiryLocked() // keep the exit status for the device until the window ends
	d.mu.Unlock()
}
