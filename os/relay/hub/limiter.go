package hub

import (
	"context"
	"sync"
	"time"
)

// Limiter is a token bucket shared by all sessions of one agent, so a busy
// agent cannot take more than its share of the relay's bandwidth.
type Limiter struct {
	mu     sync.Mutex
	rate   float64 // bytes per second
	burst  float64
	tokens float64
	last   time.Time
}

// NewLimiter allows bytesPerSecond with a one-second burst.
func NewLimiter(bytesPerSecond int) *Limiter {
	r := float64(bytesPerSecond)
	return &Limiter{rate: r, burst: r, tokens: r, last: time.Now()}
}

// Wait blocks until n bytes may pass or ctx ends. Chunks larger than the
// burst are charged as one full burst.
func (l *Limiter) Wait(ctx context.Context, n int) error {
	need := min(float64(n), l.burst)
	for {
		l.mu.Lock()
		now := time.Now()
		l.tokens = min(l.burst, l.tokens+now.Sub(l.last).Seconds()*l.rate)
		l.last = now
		if l.tokens >= need {
			l.tokens -= need
			l.mu.Unlock()
			return nil
		}
		wait := time.Duration((need - l.tokens) / l.rate * float64(time.Second))
		l.mu.Unlock()
		t := time.NewTimer(min(wait, 100*time.Millisecond))
		select {
		case <-ctx.Done():
			t.Stop()
			return ctx.Err()
		case <-t.C:
		}
	}
}
