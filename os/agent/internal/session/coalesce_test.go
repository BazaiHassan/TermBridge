package session

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"termbridge/internal/proto"
)

func mkChunk(s string) chunk {
	buf := proto.GetBuffer(len(s))
	*buf = append(*buf, s...)
	return chunk{buf: buf}
}

type recorder struct{ frames chan string }

func newRecorder() *recorder { return &recorder{frames: make(chan string, 256)} }

func (r *recorder) send(p []byte) error {
	r.frames <- string(bytes.Clone(p))
	return nil
}

func (r *recorder) next(t *testing.T, within time.Duration) string {
	t.Helper()
	select {
	case f := <-r.frames:
		return f
	case <-time.After(within):
		t.Fatalf("no frame within %v", within)
		return ""
	}
}

func (r *recorder) none(t *testing.T, during time.Duration) {
	t.Helper()
	select {
	case f := <-r.frames:
		t.Fatalf("unexpected frame %q", f)
	case <-time.After(during):
	}
}

func runCoalesce(t *testing.T, window time.Duration, maxChunk int) (chan<- chunk, *recorder, <-chan error) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	in, rec, done := make(chan chunk, 256), newRecorder(), make(chan error, 1)
	go func() { done <- coalesce(ctx, in, rec.send, window, maxChunk) }()
	return in, rec, done
}

func TestCoalesceFirstChunkIsImmediate(t *testing.T) {
	// With an hour-long window only the idle fast path can deliver.
	in, rec, _ := runCoalesce(t, time.Hour, 1024)
	in <- mkChunk("a")
	if got := rec.next(t, time.Second); got != "a" {
		t.Fatalf("got %q", got)
	}
	in <- mkChunk("b")
	rec.none(t, 50*time.Millisecond) // inside the window: held
}

func TestCoalesceMergesBurst(t *testing.T) {
	in, rec, done := runCoalesce(t, 20*time.Millisecond, 1024)
	for range 200 {
		in <- mkChunk("x")
	}
	close(in)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	close(rec.frames)
	var all strings.Builder
	n := 0
	for f := range rec.frames {
		all.WriteString(f)
		n++
	}
	if all.String() != strings.Repeat("x", 200) {
		t.Fatalf("lost or reordered bytes: %q", all.String())
	}
	if n > 10 {
		t.Fatalf("burst of 200 chunks became %d frames", n)
	}
}

func TestCoalesceFlushesAtMaxChunk(t *testing.T) {
	in, rec, done := runCoalesce(t, time.Hour, 8)
	in <- mkChunk("a")
	rec.next(t, time.Second)
	in <- mkChunk("bbbb")
	in <- mkChunk("cccc")
	if got := rec.next(t, time.Second); got != "bbbbcccc" {
		t.Fatalf("got %q, want full chunk", got)
	}
	in <- mkChunk("dd")
	close(in)
	if got := rec.next(t, time.Second); got != "dd" {
		t.Fatalf("got %q, want tail on close", got)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestCoalesceReturnsToIdle(t *testing.T) {
	in, rec, _ := runCoalesce(t, 100*time.Millisecond, 1024)
	in <- mkChunk("a")
	rec.next(t, time.Second)
	time.Sleep(300 * time.Millisecond) // one quiet window closes it
	start := time.Now()
	in <- mkChunk("b")
	rec.next(t, time.Second)
	if waited := time.Since(start); waited > 50*time.Millisecond {
		t.Fatalf("echo after idle took %v; want immediate", waited)
	}
}
