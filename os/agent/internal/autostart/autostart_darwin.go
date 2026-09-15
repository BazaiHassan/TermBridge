//go:build darwin

package autostart

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// Enable writes a LaunchAgent that launchd runs at the next login. A
// background agent is restarted if it crashes.
func Enable(e Entry) (string, error) {
	if err := e.validate(); err != nil {
		return "", err
	}
	p, err := plistPath(e)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(p, plist(e), 0o644); err != nil {
		return "", err
	}
	return "LaunchAgent " + p, nil
}

// Disable removes the LaunchAgent; a running instance keeps running until
// logout.
func Disable(e Entry) error {
	p, err := plistPath(e)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// Enabled reports whether the LaunchAgent exists.
func Enabled(e Entry) bool {
	p, err := plistPath(e)
	if err != nil {
		return false
	}
	_, err = os.Stat(p)
	return err == nil
}

func plistPath(e Entry) (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", e.ID+".plist"), nil
}

func plist(e Entry) []byte {
	esc := func(s string) string {
		var b bytes.Buffer
		_ = xml.EscapeText(&b, []byte(s))
		return b.String()
	}
	var args bytes.Buffer
	for _, a := range append([]string{e.Exe}, e.Args...) {
		fmt.Fprintf(&args, "\t\t<string>%s</string>\n", esc(a))
	}
	keepAlive := ""
	if e.Kind == Background {
		keepAlive = "\t<key>KeepAlive</key>\n\t<dict>\n\t\t<key>SuccessfulExit</key>\n\t\t<false/>\n\t</dict>\n"
	}
	// Interactive: App Nap must not throttle a terminal someone is typing into.
	return []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>%s</string>
	<key>ProgramArguments</key>
	<array>
%s	</array>
	<key>RunAtLoad</key>
	<true/>
%s	<key>ProcessType</key>
	<string>Interactive</string>
</dict>
</plist>
`, esc(e.ID), args.String(), keepAlive))
}
