package pairing

import (
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestCodeIsSingleUse(t *testing.T) {
	w, err := NewWindow()
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := base64.StdEncoding.DecodeString(w.Code()); len(b) != CodeSize {
		t.Fatalf("code %q is not %d bytes", w.Code(), CodeSize)
	}
	if !w.Consume(w.Code()) {
		t.Fatal("valid code rejected")
	}
	if w.Consume(w.Code()) || w.Active() {
		t.Fatal("code accepted twice")
	}
}

func TestCodeExpires(t *testing.T) {
	c := &clock{t: time.Now()}
	w, _ := newWindow(c.now)
	c.t = c.t.Add(Lifetime + time.Second)
	if w.Consume(w.Code()) {
		t.Fatal("expired code accepted")
	}
}

func TestWrongGuessesCloseWindow(t *testing.T) {
	w, _ := NewWindow()
	for range maxFailures {
		if w.Consume("AAAAAAAA") {
			t.Fatal("wrong code accepted")
		}
	}
	if w.Consume(w.Code()) {
		t.Fatal("code still usable after repeated wrong guesses")
	}
}

func TestRenderQRIsSquareHalfBlocks(t *testing.T) {
	out, err := RenderQR(`{"v":1,"agent_id":"` + strings.Repeat("A", 44) + `","code":"AAECAwQF","lan":["192.168.1.20:7423"]}`)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	width := len([]rune(stripANSI(lines[0])))
	if width < 25 || len(lines) < width/2 {
		t.Fatalf("unexpected QR size: %d lines × %d cols", len(lines), width)
	}
}

func stripANSI(s string) string {
	s = strings.ReplaceAll(s, "\x1b[30;107m", "")
	return strings.ReplaceAll(s, "\x1b[0m", "")
}
