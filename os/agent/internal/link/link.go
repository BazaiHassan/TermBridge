// Package link serves one connected peer: it enforces the HELLO handshake,
// routes frames to that peer's sessions and multiplexes their output back
// over the connection (PROTOCOL.md §4–5).
package link

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"time"

	"termbridge/agent/internal/session"
	"termbridge/agent/internal/transport"
	"termbridge/internal/proto"
)

const (
	defaultHelloTimeout = 10 * time.Second
	defaultIdleTimeout  = 50 * time.Second // three missed 15 s keepalives + slack
	defaultQueueLimit   = 4 << 20
	flushTimeout        = 2 * time.Second
	maxClientLen        = 64
)

// Events observes peers and sessions, e.g. for the agent's status line.
type Events interface {
	PeerConnected(peer string)
	PeerDisconnected(peer string)
	SessionOpened(peer string, id uint8)
	SessionClosed(peer string, id uint8)
}

// Options configures Serve.
type Options struct {
	Sessions     *session.Manager
	Ack          proto.HelloAck // sent in reply to HELLO
	PeerName     string         // paired device name; defaults to the remote address
	Logger       *slog.Logger
	Events       Events        // optional
	HelloTimeout time.Duration // 0: 10 s
	IdleTimeout  time.Duration // 0: 50 s
	QueueLimit   int           // bytes of queued output; 0: 4 MiB
}

func (o Options) withDefaults() Options {
	if o.Logger == nil {
		o.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if o.Events == nil {
		o.Events = nopEvents{}
	}
	if o.HelloTimeout <= 0 {
		o.HelloTimeout = defaultHelloTimeout
	}
	if o.IdleTimeout <= 0 {
		o.IdleTimeout = defaultIdleTimeout
	}
	if o.QueueLimit <= 0 {
		o.QueueLimit = defaultQueueLimit
	}
	return o
}

var errProtocol = errors.New("link: protocol violation")

type link struct {
	conn    transport.Conn
	opts    Options
	log     *slog.Logger
	peer    string
	out     *outbox
	greeted bool

	mu    sync.Mutex
	owned map[uint8]*session.Session
	pumps sync.WaitGroup
}

// Serve runs the protocol on c until the peer disconnects, the peer violates
// the protocol or ctx ends. Every session opened on c is hung up before
// Serve returns. A clean disconnect returns nil.
func Serve(ctx context.Context, c transport.Conn, opts Options) error {
	opts = opts.withDefaults()
	l := &link{
		conn:  c,
		opts:  opts,
		peer:  cmp.Or(opts.PeerName, c.RemoteAddr()),
		out:   newOutbox(opts.QueueLimit),
		owned: make(map[uint8]*session.Session),
	}
	l.log = opts.Logger.With("peer", l.peer, "remote", c.RemoteAddr())

	sctx, scancel := context.WithCancel(ctx)
	defer scancel()
	// The writer outlives sctx so that a final ERROR still gets flushed.
	wctx, wcancel := context.WithCancel(context.WithoutCancel(ctx))
	defer wcancel()
	writerDone := make(chan error, 1)
	go func() {
		err := l.out.run(wctx, c)
		if err != nil {
			scancel() // unblock the read loop
		}
		writerDone <- err
	}()

	err := l.readLoop(sctx)
	scancel()
	l.pumps.Wait()
	l.out.close()
	flush := time.AfterFunc(flushTimeout, wcancel)
	werr := <-writerDone
	flush.Stop()
	_ = c.Close(closeReason(err))

	if l.greeted {
		opts.Events.PeerDisconnected(l.peer)
		l.log.Info("peer disconnected")
	}
	switch {
	case errors.Is(err, io.EOF), ctx.Err() != nil:
		return nil
	case errors.Is(err, context.Canceled) && werr != nil:
		return werr
	}
	return err
}

func closeReason(err error) string {
	if errors.Is(err, errProtocol) {
		return "protocol violation"
	}
	return "bye"
}

func (l *link) readLoop(ctx context.Context) error {
	if err := l.handshake(ctx); err != nil {
		return err
	}
	for {
		rctx, cancel := context.WithTimeout(ctx, l.opts.IdleTimeout)
		f, err := l.conn.ReadFrame(rctx)
		cancel()
		if err != nil {
			return err
		}
		if err := l.dispatch(ctx, f); err != nil {
			return err
		}
	}
}

func (l *link) handshake(ctx context.Context) error {
	hctx, cancel := context.WithTimeout(ctx, l.opts.HelloTimeout)
	defer cancel()
	f, err := l.conn.ReadFrame(hctx)
	if err != nil {
		return fmt.Errorf("await HELLO: %w", err)
	}
	if f.Op != proto.OpHello {
		return l.fatal(proto.CodeHelloRequired, "first frame must be HELLO, got "+f.Op.String())
	}
	var h proto.Hello
	if err := proto.ParseJSON(f, &h); err != nil {
		return l.fatal(proto.CodeBadFrame, "invalid HELLO payload")
	}
	if h.V != proto.Version {
		return l.fatal(proto.CodeUnsupportedVersion, fmt.Sprintf("agent speaks protocol v%d", proto.Version))
	}
	ack, err := proto.JSONFrame(proto.OpHelloAck, proto.ControlSession, l.opts.Ack)
	if err != nil {
		return err
	}
	if err := l.out.control(ack); err != nil {
		return err
	}
	if len(h.Client) > maxClientLen {
		h.Client = h.Client[:maxClientLen]
	}
	l.greeted = true
	l.log.Info("peer connected", "client", h.Client)
	l.opts.Events.PeerConnected(l.peer)
	return nil
}

// dispatch handles one frame. Only fatal conditions return an error.
func (l *link) dispatch(ctx context.Context, f proto.Frame) error {
	if err := f.Validate(); err != nil {
		return l.sendError(f.Session, proto.CodeBadFrame, err.Error())
	}
	switch f.Op {
	case proto.OpData:
		if s := l.session(f.Session); s != nil {
			if _, err := s.Write(f.Payload); err != nil {
				l.log.Debug("input dropped", "session", f.Session, "err", err)
			}
		}
	case proto.OpResize:
		cols, rows, _ := proto.ParseResize(f) // length already validated
		if s := l.session(f.Session); s != nil {
			if err := s.Resize(cols, rows); err != nil {
				l.log.Debug("resize failed", "session", f.Session, "err", err)
			}
		}
	case proto.OpSessionOpen:
		return l.openSession(ctx, f)
	case proto.OpSessionClose:
		if s := l.session(f.Session); s != nil {
			_ = s.Close() // the pump reports SESSION_EXIT
		}
	case proto.OpPing:
		return l.out.control(proto.Frame{Op: proto.OpPong, Session: proto.ControlSession, Payload: f.Payload})
	case proto.OpPong, proto.OpHello:
		// v1 agents send no PINGs; a repeated HELLO is harmless.
	default:
		return l.sendError(proto.ControlSession, proto.CodeUnsupportedOpcode, f.Op.String())
	}
	return nil
}

func (l *link) openSession(ctx context.Context, f proto.Frame) error {
	// Both possible replies travel the FIFO session queue so they reach the
	// app in request order (PROTOCOL.md §4.2).
	reply := func(fr proto.Frame) error { return l.out.session(ctx, fr) }

	var req proto.SessionOpen
	if err := proto.ParseJSON(f, &req); err != nil {
		return l.replyError(reply, proto.CodeBadFrame, "invalid SESSION_OPEN payload")
	}
	s, err := l.opts.Sessions.Open(req)
	if err != nil {
		code := proto.CodeSessionOpenFailed
		if errors.Is(err, session.ErrTooManySessions) {
			code = proto.CodeTooManySessions
		}
		l.log.Warn("session open failed", "err", err)
		return l.replyError(reply, code, err.Error())
	}
	l.mu.Lock()
	l.owned[s.ID()] = s
	l.mu.Unlock()
	if err := reply(proto.SessionOpened(s.ID())); err != nil {
		_ = s.Close() // the pump still runs to release the session
	}
	l.pumps.Add(1)
	go l.pump(ctx, s)
	return nil
}

func (l *link) pump(ctx context.Context, s *session.Session) {
	defer l.pumps.Done()
	defer s.Release() // only after SESSION_EXIT is queued: IDs are not reused early
	id := s.ID()
	l.opts.Events.SessionOpened(l.peer, id)
	defer l.opts.Events.SessionClosed(l.peer, id)

	code, err := s.Run(ctx, func(ctx context.Context, p []byte) error {
		return l.out.session(ctx, proto.Data(id, p))
	})
	if ctx.Err() == nil {
		if qerr := l.out.session(ctx, proto.SessionExit(id, int32(code))); qerr != nil {
			err = errors.Join(err, qerr)
		}
	}
	l.mu.Lock()
	delete(l.owned, id)
	l.mu.Unlock()
	if err != nil {
		l.log.Info("session ended", "session", id, "exit", code, "err", err)
	} else {
		l.log.Info("session ended", "session", id, "exit", code)
	}
}

func (l *link) session(id uint8) *session.Session {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.owned[id] // nil for 0, unknown or already-exited IDs: dropped (§4.7)
}

func (l *link) sendError(sid uint8, code, msg string) error {
	f, err := proto.JSONFrame(proto.OpError, sid, proto.ErrorMsg{Code: code, Msg: msg})
	if err != nil {
		return err
	}
	return l.out.control(f)
}

func (l *link) replyError(reply func(proto.Frame) error, code, msg string) error {
	f, err := proto.JSONFrame(proto.OpError, proto.ControlSession, proto.ErrorMsg{Code: code, Msg: msg})
	if err != nil {
		return err
	}
	return reply(f)
}

// fatal reports a protocol violation to the peer and ends the connection.
func (l *link) fatal(code, msg string) error {
	_ = l.sendError(proto.ControlSession, code, msg)
	l.log.Warn("protocol violation", "code", code, "msg", msg)
	return fmt.Errorf("%w: %s", errProtocol, code)
}

type nopEvents struct{}

func (nopEvents) PeerConnected(string)        {}
func (nopEvents) PeerDisconnected(string)     {}
func (nopEvents) SessionOpened(string, uint8) {}
func (nopEvents) SessionClosed(string, uint8) {}
