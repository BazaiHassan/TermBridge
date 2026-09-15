//go:build linux || darwin

package pty

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	cpty "github.com/creack/pty"
)

// killGrace is how long a hung-up process group may take to exit before it
// is sent SIGKILL.
const killGrace = 3 * time.Second

type unixTerminal struct {
	ptmx *os.File
	cmd  *exec.Cmd

	done     chan struct{} // closed once the process has been reaped
	exitCode int
	waitErr  error

	closeOnce sync.Once
}

// Start runs shell in a new session whose controlling terminal is a fresh
// PTY of the given size.
func Start(shell string, args, env []string, cwd string, cols, rows uint16) (Terminal, error) {
	cmd := exec.Command(shell, args...)
	cmd.Env = env
	cmd.Dir = cwd
	// StartWithSize sets Setsid+Setctty: the shell leads its own session and
	// process group, so a hang-up reaches its jobs too.
	ptmx, err := cpty.StartWithSize(cmd, &cpty.Winsize{Cols: cols, Rows: rows})
	if err != nil {
		return nil, fmt.Errorf("start %s in pty: %w", shell, err)
	}
	t := &unixTerminal{ptmx: ptmx, cmd: cmd, done: make(chan struct{})}
	go t.reap()
	return t, nil
}

// reap waits for the process so it never lingers as a zombie, whether or not
// anyone calls Wait.
func (t *unixTerminal) reap() {
	err := t.cmd.Wait()
	t.exitCode, t.waitErr = exitCode(t.cmd.ProcessState, err)
	close(t.done)
}

func exitCode(ps *os.ProcessState, err error) (int, error) {
	if ps == nil {
		return -1, fmt.Errorf("wait for shell: %w", err)
	}
	if ws, ok := ps.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
		return 128 + int(ws.Signal()), nil
	}
	return ps.ExitCode(), nil
}

func (t *unixTerminal) Read(p []byte) (int, error)  { return t.ptmx.Read(p) }
func (t *unixTerminal) Write(p []byte) (int, error) { return t.ptmx.Write(p) }

func (t *unixTerminal) Resize(cols, rows uint16) error {
	// TIOCSWINSZ makes the kernel send SIGWINCH to the terminal's foreground
	// process group — the process that has to redraw (vim, htop, readline).
	if err := cpty.Setsize(t.ptmx, &cpty.Winsize{Cols: cols, Rows: rows}); err != nil {
		return fmt.Errorf("resize pty: %w", err)
	}
	return nil
}

func (t *unixTerminal) Wait() (int, error) {
	<-t.done
	return t.exitCode, t.waitErr
}

func (t *unixTerminal) Close() error {
	var err error
	t.closeOnce.Do(func() {
		select {
		case <-t.done:
			// Already reaped: its PID may have been reused, never signal it.
		default:
			pgid := -t.cmd.Process.Pid
			_ = syscall.Kill(pgid, syscall.SIGHUP)
			go t.killAfterGrace(pgid)
		}
		err = t.ptmx.Close()
	})
	return err
}

func (t *unixTerminal) killAfterGrace(pgid int) {
	timer := time.NewTimer(killGrace)
	defer timer.Stop()
	select {
	case <-t.done:
	case <-timer.C:
		_ = syscall.Kill(pgid, syscall.SIGKILL)
	}
}

// DefaultShell returns $SHELL (or /bin/bash) started as a login shell so the
// user's profile is loaded.
func DefaultShell() (string, []string) {
	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/bash"
	}
	return shell, LoginArgs(shell)
}

// LoginArgs returns the arguments that start shell as a login shell, or nil
// when shell is not a known POSIX-style shell.
func LoginArgs(shell string) []string {
	switch filepath.Base(shell) {
	case "bash", "zsh", "sh", "dash", "ksh", "mksh", "fish", "tcsh", "csh":
		return []string{"-l"}
	}
	return nil
}
