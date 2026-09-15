// Package pty is a platform-neutral pseudo-terminal: creack/pty on Unix and
// ConPTY on Windows (phase 3). Start and DefaultShell are implemented per
// platform:
//
//	func Start(shell string, args, env []string, cwd string, cols, rows uint16) (Terminal, error)
//	func DefaultShell() (shell string, args []string)
package pty

// Terminal is a running process attached to a pseudo-terminal.
type Terminal interface {
	// Read returns process output. It fails once the process side of the
	// terminal is closed and all output has been drained, or after Close.
	Read(p []byte) (int, error)
	// Write sends input to the process.
	Write(p []byte) (int, error)
	// Resize changes the window size; the foreground process gets SIGWINCH.
	Resize(cols, rows uint16) error
	// Wait blocks until the process exits and returns its exit code. A
	// process killed by signal N reports 128+N, like a POSIX shell.
	Wait() (exitCode int, err error)
	// Close hangs the terminal up. It is safe to call more than once.
	Close() error
}
