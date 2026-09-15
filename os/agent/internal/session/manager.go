// Package session owns the agent's PTY-backed shells and moves their output
// to the network with low-latency coalescing (PROTOCOL.md §5).
package session

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"

	"termbridge/agent/internal/pty"
	"termbridge/internal/proto"
)

const (
	// DefaultMaxSessions is the per-agent session limit.
	DefaultMaxSessions = 8
	// DefaultWindow is the output coalescing window.
	DefaultWindow = 5 * time.Millisecond
	// MaxChunk bounds the DATA payloads produced by a session.
	MaxChunk = 32 << 10

	readSize = 4096
	maxDim   = 1000
	// exitDrain is how long output may keep flowing after the shell exits
	// (background jobs can hold the PTY open) before the session is closed.
	exitDrain = 200 * time.Millisecond
)

// ErrTooManySessions is returned by Open when the session limit is reached.
var ErrTooManySessions = errors.New("session: too many sessions")

// Config configures a Manager.
type Config struct {
	Shell       string        // empty: pty.DefaultShell()
	MaxSessions int           // 0: DefaultMaxSessions; never above 255
	Window      time.Duration // 0: DefaultWindow
	Logger      *slog.Logger
}

// Manager starts sessions and hands out session IDs. It is safe for
// concurrent use.
type Manager struct {
	cfg  Config
	mu   sync.Mutex
	live map[uint8]*Session
}

// NewManager returns a Manager with cfg's zero values replaced by defaults.
func NewManager(cfg Config) *Manager {
	if cfg.MaxSessions <= 0 {
		cfg.MaxSessions = DefaultMaxSessions
	}
	cfg.MaxSessions = min(cfg.MaxSessions, 255)
	if cfg.Window <= 0 {
		cfg.Window = DefaultWindow
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Manager{cfg: cfg, live: make(map[uint8]*Session)}
}

// Open starts a shell in a new PTY. The caller must Run the session and then
// Release it.
func (m *Manager) Open(req proto.SessionOpen) (*Session, error) {
	shell := req.Shell
	if shell == "" {
		shell = m.cfg.Shell
	}
	var args []string
	if shell == "" {
		shell, args = pty.DefaultShell()
	} else {
		args = pty.LoginArgs(shell)
	}
	cwd := req.Cwd
	if cwd == "" {
		if home, err := os.UserHomeDir(); err == nil {
			cwd = home
		}
	}
	cols, rows := clampSize(req.Cols, req.Rows)

	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.live) >= m.cfg.MaxSessions {
		return nil, fmt.Errorf("%w (limit %d)", ErrTooManySessions, m.cfg.MaxSessions)
	}
	term, err := pty.Start(shell, args, Env(os.Environ()), cwd, cols, rows)
	if err != nil {
		return nil, err
	}
	s := &Session{id: m.freeIDLocked(), term: term, m: m}
	m.live[s.id] = s
	m.cfg.Logger.Info("session started", "session", s.id, "shell", shell, "cols", cols, "rows", rows)
	return s, nil
}

// Count returns the number of live sessions.
func (m *Manager) Count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.live)
}

func (m *Manager) freeIDLocked() uint8 {
	for id := 1; id <= 255; id++ {
		if _, used := m.live[uint8(id)]; !used {
			return uint8(id)
		}
	}
	panic("session: no free ID below MaxSessions") // unreachable: MaxSessions <= 255
}

func (m *Manager) release(id uint8) {
	m.mu.Lock()
	delete(m.live, id)
	m.mu.Unlock()
}

// Env derives a shell environment from base: it drops variables that
// describe the agent's own terminal and advertises an xterm-compatible one.
func Env(base []string) []string {
	drop := map[string]bool{
		"TERM": true, "COLORTERM": true, "TERM_PROGRAM": true, "TERM_PROGRAM_VERSION": true,
		"VTE_VERSION": true, "TMUX": true, "TMUX_PANE": true, "STY": true, "WINDOWID": true,
		"COLUMNS": true, "LINES": true,
	}
	env := make([]string, 0, len(base)+3)
	for _, kv := range base {
		if k, _, _ := strings.Cut(kv, "="); !drop[k] {
			env = append(env, kv)
		}
	}
	return append(env, "TERM=xterm-256color", "COLORTERM=truecolor", "TERMBRIDGE=1")
}

func clampSize(cols, rows uint16) (uint16, uint16) {
	if cols == 0 {
		cols = 80
	}
	if rows == 0 {
		rows = 24
	}
	return min(cols, maxDim), min(rows, maxDim)
}

// Session is one shell running in a PTY.
type Session struct {
	id          uint8
	term        pty.Terminal
	m           *Manager
	releaseOnce sync.Once
}

// ID returns the session ID (1…255).
func (s *Session) ID() uint8 { return s.id }

// Write sends input to the shell. Input is never buffered or coalesced.
func (s *Session) Write(p []byte) (int, error) { return s.term.Write(p) }

// Resize changes the terminal size.
func (s *Session) Resize(cols, rows uint16) error {
	return s.term.Resize(clampSize(cols, rows))
}

// Close hangs up the shell; Run then returns with its exit code.
func (s *Session) Close() error { return s.term.Close() }

// Release frees the session ID. Call it after the peer has been sent
// SESSION_EXIT so the ID cannot be reused before that (PROTOCOL.md §4.3).
func (s *Session) Release() {
	s.releaseOnce.Do(func() {
		s.m.release(s.id)
		s.m.cfg.Logger.Info("session released", "session", s.id)
	})
}

// Run forwards output to send until the shell exits or ctx ends, then
// returns the exit code. send gets a slice that is only valid during the
// call; it may block, which stops reading the PTY (backpressure).
func (s *Session) Run(ctx context.Context, send func(context.Context, []byte) error) (int, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	// Peer gone or agent stopping: hang up, which also unblocks read().
	stop := context.AfterFunc(ctx, func() { _ = s.term.Close() })
	defer stop()
	go func() {
		_, _ = s.term.Wait()
		timer := time.NewTimer(exitDrain)
		defer timer.Stop()
		select {
		case <-timer.C:
			_ = s.term.Close()
		case <-ctx.Done():
		}
	}()

	chunks := make(chan chunk, 4)
	go s.read(chunks)
	err := coalesce(ctx, chunks, func(p []byte) error { return send(ctx, p) }, s.m.cfg.Window, MaxChunk)
	if err != nil {
		_ = s.term.Close()
	}
	for c := range chunks { // let read() finish
		proto.PutBuffer(c.buf)
	}
	code, werr := s.term.Wait()
	_ = s.term.Close() // releases the PTY; never signals a reaped process
	if err != nil && !errors.Is(err, context.Canceled) {
		return code, fmt.Errorf("session %d output: %w", s.id, err)
	}
	return code, werr
}

// read copies PTY output into pooled buffers until the PTY fails.
func (s *Session) read(out chan<- chunk) {
	defer close(out)
	for {
		buf := proto.GetBuffer(readSize)
		b := (*buf)[:readSize]
		n, err := s.term.Read(b)
		if n > 0 {
			*buf = b[:n]
			out <- chunk{buf: buf}
		} else {
			proto.PutBuffer(buf)
		}
		if err != nil {
			return
		}
	}
}
