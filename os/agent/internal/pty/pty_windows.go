//go:build windows

package pty

import "errors"

// ErrUnsupported is returned until the ConPTY backend lands in phase 3.
var ErrUnsupported = errors.New("pty: Windows ConPTY support arrives in phase 3")

// Start is not implemented on Windows yet.
func Start(shell string, args, env []string, cwd string, cols, rows uint16) (Terminal, error) {
	return nil, ErrUnsupported
}

// DefaultShell returns the Windows default shell.
func DefaultShell() (string, []string) { return "powershell.exe", nil }

// LoginArgs has no meaning on Windows.
func LoginArgs(string) []string { return nil }
