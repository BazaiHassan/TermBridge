package hub

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestOpenRequiresAgentAndRespectsLimit(t *testing.T) {
	h := New(Config{MaxSessions: 1, AcceptTimeout: 100 * time.Millisecond})
	ctx := context.Background()
	if _, err := h.Open(ctx, "A"); !errors.Is(err, ErrAgentOffline) {
		t.Fatalf("offline agent: %v", err)
	}
	tokens := make(chan string, 4)
	h.Register("A", func(_ context.Context, tok string) error { tokens <- tok; return nil }, func() {})
	p, err := h.Open(ctx, "A")
	if err != nil {
		t.Fatal(err)
	}
	if tok := <-tokens; tok == "" {
		t.Fatal("agent not notified")
	}
	if _, err := h.Open(ctx, "A"); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("second session: %v", err)
	}
	if _, _, err := p.Wait(ctx); !errors.Is(err, ErrNoAnswer) {
		t.Fatalf("wait without accept: %v", err)
	}
	p.Close()
	if _, err := h.Open(ctx, "A"); err != nil {
		t.Fatalf("slot not released: %v", err)
	}
}

func TestReconnectReplacesControlChannel(t *testing.T) {
	h := New(Config{})
	kicked := false
	h.Register("A", func(context.Context, string) error { return errors.New("old channel") }, func() { kicked = true })
	got := make(chan struct{}, 1)
	_, unregister := h.Register("A", func(context.Context, string) error { got <- struct{}{}; return nil }, func() {})
	if !kicked {
		t.Fatal("old control channel was not kicked")
	}
	if _, err := h.Open(context.Background(), "A"); err != nil {
		t.Fatal(err)
	}
	<-got
	unregister()
	if h.Online("A") {
		t.Fatal("agent still online after unregister")
	}
}

func TestAcceptUnknownToken(t *testing.T) {
	if _, ok := New(Config{}).Accept("nope", nil); ok {
		t.Fatal("unknown token accepted")
	}
}

func TestLimiterPacesTraffic(t *testing.T) {
	l := NewLimiter(1000)
	ctx := context.Background()
	start := time.Now()
	_ = l.Wait(ctx, 1000) // the initial burst passes at once
	if time.Since(start) > 50*time.Millisecond {
		t.Fatal("burst was throttled")
	}
	_ = l.Wait(ctx, 500)
	if d := time.Since(start); d < 400*time.Millisecond {
		t.Fatalf("500 bytes over budget passed after %v", d)
	}
}
