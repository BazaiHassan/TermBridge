// Package autostart starts TermBridge when the user logs in: a systemd user
// unit or an XDG autostart entry on Linux, a LaunchAgent on macOS, the HKCU
// Run key on Windows. Everything is per user and needs no administrator
// rights; the agent never runs as root.
//
// Enabling registers the program for the next login; it does not start it
// now, and disabling does not stop a running instance.
package autostart

import (
	"errors"
	"strings"
)

// Kind says how the program behaves, which picks the mechanism on Linux.
type Kind int

const (
	// Background has no window, like `termbridge run`.
	Background Kind = iota
	// GUI opens a window, like the desktop app, and needs a graphical session.
	GUI
)

// Entry is a program to start at login.
type Entry struct {
	// ID is stable and safe in file names, e.g. "io.termbridge.agent".
	ID   string
	Name string // shown to the user
	Exe  string // absolute path
	Args []string
	Kind Kind
}

// ErrUnsupported is returned where no mechanism exists.
var ErrUnsupported = errors.New("autostart: not supported on this system")

func (e Entry) validate() error {
	if e.ID == "" || strings.ContainsAny(e.ID, `/\ `) {
		return errors.New("autostart: invalid entry ID")
	}
	if e.Exe == "" {
		return errors.New("autostart: missing executable")
	}
	return nil
}
