//go:build linux

package autostart

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// fakeSystemd replaces systemctl and the systemd probe for one test.
func fakeSystemd(t *testing.T, present bool) *[]string {
	t.Helper()
	calls := &[]string{}
	oldCtl, oldHave := systemctl, haveSystemd
	systemctl = func(args ...string) error {
		*calls = append(*calls, strings.Join(args, " "))
		return nil
	}
	haveSystemd = func() bool { return present }
	t.Cleanup(func() { systemctl, haveSystemd = oldCtl, oldHave })
	return calls
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestBackgroundEntryIsASystemdUserUnit(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	calls := fakeSystemd(t, true)
	e := Entry{
		ID: "io.termbridge.agent", Name: "TermBridge", Kind: Background,
		Exe:  "/opt/Term Bridge/termbridge",
		Args: []string{"run", "--shell", "/bin/zsh", "100%", "$HOME"},
	}
	if Enabled(e) {
		t.Fatal("enabled before Enable")
	}
	if _, err := Enable(e); err != nil {
		t.Fatal(err)
	}
	unit := read(t, filepath.Join(dir, "systemd", "user", "io.termbridge.agent.service"))
	for _, want := range []string{
		`ExecStart="/opt/Term Bridge/termbridge" run --shell /bin/zsh 100%% $$HOME`,
		"Restart=on-failure",
		"WantedBy=default.target",
	} {
		if !strings.Contains(unit, want) {
			t.Errorf("unit lacks %q:\n%s", want, unit)
		}
	}
	if !slices.Equal(*calls, []string{"daemon-reload", "enable io.termbridge.agent.service"}) {
		t.Fatalf("systemctl calls %q", *calls)
	}
	if !Enabled(e) {
		t.Fatal("not enabled after Enable")
	}
	if err := Disable(e); err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(*calls, "disable io.termbridge.agent.service") || Enabled(e) {
		t.Fatalf("still enabled after Disable; calls %q", *calls)
	}
	if err := Disable(e); err != nil {
		t.Fatalf("second Disable: %v", err)
	}
}

func TestGUIEntryIsAnXDGAutostartEntry(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	calls := fakeSystemd(t, true)
	e := Entry{ID: "io.termbridge.desktop", Name: "TermBridge", Exe: `/home/a b/bin/termbridge-desktop`, Kind: GUI}
	if _, err := Enable(e); err != nil {
		t.Fatal(err)
	}
	entry := read(t, filepath.Join(dir, "autostart", "io.termbridge.desktop.desktop"))
	if !strings.Contains(entry, `Exec="/home/a b/bin/termbridge-desktop"`) || !strings.Contains(entry, "Type=Application") {
		t.Fatalf("entry:\n%s", entry)
	}
	if len(*calls) != 0 {
		t.Fatalf("a GUI entry touched systemd: %q", *calls)
	}
	if err := Disable(e); err != nil || Enabled(e) {
		t.Fatalf("Disable: %v, enabled %v", err, Enabled(e))
	}
}

func TestBackgroundFallsBackToXDGWithoutSystemd(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	fakeSystemd(t, false)
	e := Entry{ID: "io.termbridge.agent", Name: "TermBridge", Exe: "/usr/bin/termbridge", Args: []string{"run"}, Kind: Background}
	if _, err := Enable(e); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(read(t, filepath.Join(dir, "autostart", "io.termbridge.agent.desktop")), "Exec=/usr/bin/termbridge run") {
		t.Fatal("no XDG entry without systemd")
	}
}

func TestQuoting(t *testing.T) {
	for in, want := range map[string]string{
		"plain":    "plain",
		"a b":      `"a b"`,
		`say "hi"`: `"say \"hi\""`,
		"50%":      "50%%",
	} {
		if got := systemdQuote(in); got != want {
			t.Errorf("systemdQuote(%q) = %s, want %s", in, got, want)
		}
	}
	if got := desktopQuote("cost $5"); got != `"cost \$5"` {
		t.Errorf("desktopQuote = %s", got)
	}
	if err := (Entry{ID: "a/b", Exe: "/x"}).validate(); err == nil {
		t.Error("an ID with a slash was accepted")
	}
}
