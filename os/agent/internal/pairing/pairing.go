// Package pairing implements the one-time pairing code and the QR code that
// carries it (architecture §3.1, PROTOCOL.md §8).
package pairing

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

const (
	// CodeSize is the pairing code length in bytes.
	CodeSize = 6
	// Lifetime is how long a code stays valid.
	Lifetime = 120 * time.Second
	// maxFailures burns the code after this many wrong guesses.
	maxFailures = 5
)

// Payload is the JSON encoded in the pairing QR code.
type Payload struct {
	V       int      `json:"v"`
	AgentID string   `json:"agent_id"`
	Name    string   `json:"name"`
	Code    string   `json:"code"`
	LAN     []string `json:"lan"`
	WAN     []string `json:"wan,omitempty"`   // public addresses verified reachable
	Relay   string   `json:"relay,omitempty"` // relay base URL (PROTOCOL.md §9)
}

// Window is a single-use pairing code with an expiry. Safe for concurrent use.
type Window struct {
	mu       sync.Mutex
	code     []byte
	expires  time.Time
	used     bool
	failures int
	now      func() time.Time
}

// NewWindow opens a pairing window with a fresh random code.
func NewWindow() (*Window, error) { return newWindow(time.Now) }

func newWindow(now func() time.Time) (*Window, error) {
	code := make([]byte, CodeSize)
	if _, err := rand.Read(code); err != nil {
		return nil, fmt.Errorf("pairing code: %w", err)
	}
	return &Window{code: code, expires: now().Add(Lifetime), now: now}, nil
}

// Code returns the base64 code for the QR payload.
func (w *Window) Code() string { return base64.StdEncoding.EncodeToString(w.code) }

// Expires returns when the code stops being accepted.
func (w *Window) Expires() time.Time { return w.expires }

// Active reports whether the code can still be used.
func (w *Window) Active() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.activeLocked()
}

func (w *Window) activeLocked() bool {
	return !w.used && w.failures < maxFailures && w.now().Before(w.expires)
}

// Consume accepts code at most once, and only before expiry. Wrong guesses
// count against the window, which closes after a few.
func (w *Window) Consume(code string) bool {
	got, err := base64.StdEncoding.DecodeString(code)
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.activeLocked() {
		return false
	}
	if err != nil || subtle.ConstantTimeCompare(got, w.code) != 1 {
		w.failures++
		return false
	}
	w.used = true
	return true
}

// RenderQR draws data as a QR code using half-block characters (two modules
// per character cell), black on white whatever the terminal theme is.
func RenderQR(data string) (string, error) {
	q, err := qrcode.New(data, qrcode.Medium)
	if err != nil {
		return "", fmt.Errorf("encode QR: %w", err)
	}
	q.DisableBorder = true
	bitmap := q.Bitmap()
	const quiet = 2
	n := len(bitmap)
	dark := func(x, y int) bool {
		return x >= 0 && y >= 0 && x < n && y < n && bitmap[y][x]
	}
	var b strings.Builder
	for y := -quiet; y < n+quiet; y += 2 {
		b.WriteString("\x1b[30;107m")
		for x := -quiet; x < n+quiet; x++ {
			switch top, bottom := dark(x, y), dark(x, y+1); {
			case top && bottom:
				b.WriteString("█")
			case top:
				b.WriteString("▀")
			case bottom:
				b.WriteString("▄")
			default:
				b.WriteByte(' ')
			}
		}
		b.WriteString("\x1b[0m\n")
	}
	return b.String(), nil
}
