package session

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"termbridge/internal/proto"
)

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) send(_ context.Context, p []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.b.Write(p)
	return nil
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

type runResult struct {
	code int
	err  error
}

func runSession(ctx context.Context, s *Session, out *syncBuffer) <-chan runResult {
	done := make(chan runResult, 1)
	go func() {
		code, err := s.Run(ctx, out.send)
		done <- runResult{code, err}
	}()
	return done
}

func await(t *testing.T, done <-chan runResult) runResult {
	t.Helper()
	select {
	case r := <-done:
		return r
	case <-time.After(10 * time.Second):
		t.Fatal("session did not end")
		return runResult{}
	}
}

func TestSessionEchoAndExitCode(t *testing.T) {
	m := NewManager(Config{Shell: "/bin/sh"})
	s, err := m.Open(proto.SessionOpen{Cols: 80, Rows: 24})
	if err != nil {
		t.Fatal(err)
	}
	var out syncBuffer
	done := runSession(context.Background(), s, &out)
	// $((6*7)) proves the shell evaluated the line, not just the tty echo.
	if _, err := s.Write([]byte("echo hello-$((6*7)); exit 7\n")); err != nil {
		t.Fatal(err)
	}
	r := await(t, done)
	if r.err != nil || r.code != 7 {
		t.Fatalf("exit = %d, %v; want 7", r.code, r.err)
	}
	if !strings.Contains(out.String(), "hello-42") {
		t.Fatalf("output %q lacks hello-42", out.String())
	}
	if m.Count() != 1 {
		t.Fatal("session released before Release()")
	}
	s.Release()
	if m.Count() != 0 {
		t.Fatal("session not released")
	}
}

func TestCancelHangsUpSession(t *testing.T) {
	m := NewManager(Config{Shell: "/bin/sh"})
	s, err := m.Open(proto.SessionOpen{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Release()
	ctx, cancel := context.WithCancel(context.Background())
	done := runSession(ctx, s, &syncBuffer{})
	cancel()
	if r := await(t, done); r.code != 128+1 {
		t.Fatalf("exit = %d, want 129 (SIGHUP)", r.code)
	}
}

func TestLimitAndIDReuse(t *testing.T) {
	m := NewManager(Config{Shell: "/bin/sh", MaxSessions: 2})
	a, err := m.Open(proto.SessionOpen{})
	if err != nil {
		t.Fatal(err)
	}
	b, err := m.Open(proto.SessionOpen{})
	if err != nil {
		t.Fatal(err)
	}
	if a.ID() != 1 || b.ID() != 2 {
		t.Fatalf("ids = %d, %d; want 1, 2", a.ID(), b.ID())
	}
	if _, err := m.Open(proto.SessionOpen{}); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("third open: %v", err)
	}
	_ = a.Close()
	await(t, runSession(context.Background(), a, &syncBuffer{}))
	a.Release()
	c, err := m.Open(proto.SessionOpen{})
	if err != nil {
		t.Fatal(err)
	}
	if c.ID() != 1 {
		t.Fatalf("reused id = %d, want 1", c.ID())
	}
	for _, s := range []*Session{b, c} {
		_ = s.Close()
		await(t, runSession(context.Background(), s, &syncBuffer{}))
		s.Release()
	}
}

func TestEnvAdvertisesXterm(t *testing.T) {
	env := Env([]string{"TERM=dumb", "TMUX=/tmp/x", "HOME=/home/u"})
	joined := strings.Join(env, "\n")
	for _, want := range []string{"TERM=xterm-256color", "COLORTERM=truecolor", "HOME=/home/u"} {
		if !strings.Contains(joined, want) {
			t.Errorf("env lacks %s", want)
		}
	}
	for _, gone := range []string{"TERM=dumb", "TMUX="} {
		if strings.Contains(joined, gone) {
			t.Errorf("env still has %s", gone)
		}
	}
}
