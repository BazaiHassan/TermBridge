//go:build windows

package autostart

import (
	"os"
	"strconv"
	"testing"

	"golang.org/x/sys/windows/registry"
)

func TestRunKeyRoundTrip(t *testing.T) {
	e := Entry{
		ID: "io.termbridge.test." + strconv.Itoa(os.Getpid()), Name: "TermBridge test",
		Exe: `C:\Program Files\TermBridge\termbridge.exe`, Args: []string{"run", "--lan-only"},
	}
	t.Cleanup(func() { _ = Disable(e) })
	if Enabled(e) {
		t.Fatal("enabled before Enable")
	}
	if _, err := Enable(e); err != nil {
		t.Fatal(err)
	}
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if err != nil {
		t.Fatal(err)
	}
	got, _, err := k.GetStringValue(e.ID)
	k.Close()
	if want := `"C:\Program Files\TermBridge\termbridge.exe" run --lan-only`; err != nil || got != want {
		t.Fatalf("Run value %q (%v), want %q", got, err, want)
	}
	if err := Disable(e); err != nil || Enabled(e) {
		t.Fatalf("Disable: %v, still enabled: %v", err, Enabled(e))
	}
	if err := Disable(e); err != nil {
		t.Fatalf("second Disable: %v", err)
	}
}
