//go:build linux

package autostart

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// systemctl runs `systemctl --user args…`; tests replace it.
var systemctl = func(args ...string) error {
	out, err := exec.Command("systemctl", append([]string{"--user"}, args...)...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl --user %s: %v: %s", strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

// haveSystemd reports whether this login has a systemd user manager; tests
// replace it.
var haveSystemd = func() bool {
	_, err := os.Stat("/run/systemd/system")
	return err == nil && os.Getenv("XDG_RUNTIME_DIR") != ""
}

// Enable registers e for the next login and says where. Background programs
// get a systemd user unit, which also restarts them after a crash; GUI
// programs, and systems without systemd, get an XDG autostart entry.
func Enable(e Entry) (string, error) {
	if err := e.validate(); err != nil {
		return "", err
	}
	if e.Kind == Background && haveSystemd() {
		p, err := unitPath(e)
		if err != nil {
			return "", err
		}
		if err := writeFile(p, unitFile(e)); err != nil {
			return "", err
		}
		if err := systemctl("daemon-reload"); err != nil {
			return "", err
		}
		if err := systemctl("enable", unitName(e)); err != nil {
			return "", err
		}
		return fmt.Sprintf("systemd user unit %s (start it now with: systemctl --user start %s)", p, unitName(e)), nil
	}
	p, err := desktopPath(e)
	if err != nil {
		return "", err
	}
	if err := writeFile(p, desktopFile(e)); err != nil {
		return "", err
	}
	return "autostart entry " + p, nil
}

// Disable removes both forms, since the system may have changed since
// Enable. Disabling what was never enabled is not an error.
func Disable(e Entry) error {
	var errs []error
	if p, err := unitPath(e); err == nil && exists(p) {
		if haveSystemd() {
			if err := systemctl("disable", unitName(e)); err != nil {
				errs = append(errs, err)
			}
		}
		if err := os.Remove(p); err != nil {
			errs = append(errs, err)
		}
		if haveSystemd() {
			_ = systemctl("daemon-reload")
		}
	}
	if p, err := desktopPath(e); err == nil {
		if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// Enabled reports whether e is registered in either form.
func Enabled(e Entry) bool {
	u, err1 := unitPath(e)
	d, err2 := desktopPath(e)
	return (err1 == nil && exists(u)) || (err2 == nil && exists(d))
}

func configHome() (string, error) {
	if d := os.Getenv("XDG_CONFIG_HOME"); d != "" {
		return d, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".config"), nil
}

func unitName(e Entry) string { return e.ID + ".service" }

func unitPath(e Entry) (string, error) {
	c, err := configHome()
	return filepath.Join(c, "systemd", "user", unitName(e)), err
}

func desktopPath(e Entry) (string, error) {
	c, err := configHome()
	return filepath.Join(c, "autostart", e.ID+".desktop"), err
}

func unitFile(e Entry) string {
	words := make([]string, 0, len(e.Args)+1)
	for _, w := range append([]string{e.Exe}, e.Args...) {
		words = append(words, systemdQuote(w))
	}
	return fmt.Sprintf(`[Unit]
Description=%s: reach this computer's terminal from your phone

[Service]
ExecStart=%s
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
`, e.Name, strings.Join(words, " "))
}

func desktopFile(e Entry) string {
	words := make([]string, 0, len(e.Args)+1)
	for _, w := range append([]string{e.Exe}, e.Args...) {
		words = append(words, desktopQuote(w))
	}
	return fmt.Sprintf(`[Desktop Entry]
Type=Application
Name=%s
Comment=Start %s when you log in
Exec=%s
Terminal=false
X-GNOME-Autostart-enabled=true
`, e.Name, e.Name, strings.Join(words, " "))
}

// systemdQuote escapes one ExecStart word: specifiers (%) and variables ($)
// are doubled, and words with spaces or quotes are double-quoted.
func systemdQuote(s string) string {
	s = strings.NewReplacer("%", "%%", "$", "$$").Replace(s)
	if s != "" && !strings.ContainsAny(s, " \t\"'\\;") {
		return s
	}
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s) + `"`
}

// desktopQuote escapes one Exec word per the Desktop Entry spec: field codes
// (%) are doubled, and words with reserved characters are double-quoted with
// ", `, $ and \ backslash-escaped.
func desktopQuote(s string) string {
	s = strings.ReplaceAll(s, "%", "%%")
	if s != "" && !strings.ContainsAny(s, " \t\n\"'\\><~|&;$*?#()`") {
		return s
	}
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`, "`", "\\`", `$`, `\$`).Replace(s) + `"`
}

func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
