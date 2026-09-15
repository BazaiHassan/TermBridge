//go:build desktop

package main

import (
	"bytes"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/test"

	"termbridge/agent/internal/agent"
	"termbridge/agent/internal/store"
)

// TestWindowStates renders the window with Fyne's software painter in the
// pairing, paired and live states. With TERMBRIDGE_SNAPSHOT_DIR set it writes
// the PNGs for design review; otherwise it only checks that nothing panics.
func TestWindowStates(t *testing.T) {
	a := test.NewTempApp(t)
	a.Settings().SetTheme(newTheme())
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	u := newUI(a)
	ag, err := agent.New(agent.Config{Store: st, Events: u})
	if err != nil {
		t.Fatal(err)
	}
	u.bind(ag) // no paired phones yet: opens the QR code
	u.w.Resize(fyne.NewSize(440, 720))
	u.Listening([]string{"127.0.0.1:7423", "192.168.100.9:7423"})
	snapshot(t, u.w, "1-pairing.png")

	d, err := st.AddDevice(bytes.Repeat([]byte{7}, 32), "Pixel 8", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	u.Paired(d)
	snapshot(t, u.w, "2-paired.png")

	u.PeerConnected("Pixel 8")
	u.SessionOpened("Pixel 8", 1)
	snapshot(t, u.w, "3-live.png")
}

func snapshot(t *testing.T, w fyne.Window, name string) {
	t.Helper()
	img := w.Canvas().Capture()
	dir := os.Getenv("TERMBRIDGE_SNAPSHOT_DIR")
	if dir == "" {
		return
	}
	f, err := os.Create(filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
}
