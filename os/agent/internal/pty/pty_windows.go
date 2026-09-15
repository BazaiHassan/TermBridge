//go:build windows

package pty

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

// ErrUnsupported is returned on Windows releases without ConPTY.
var ErrUnsupported = errors.New("pty: ConPTY needs Windows 10 version 1809 or later")

// killGrace is how long a process may take to exit after its console closed
// before it is terminated.
const killGrace = 3 * time.Second

// conPTY is a process attached to a Windows pseudo console. The console
// renders the process's screen as VT sequences on out and turns VT input on
// in back into console input, so the rest of the agent sees what a Unix PTY
// would give it.
type conPTY struct {
	console windows.Handle
	in      *os.File // our end of the console's input
	out     *os.File // our end of the console's output
	process windows.Handle

	done     chan struct{} // closed once the process has exited
	exitCode int
	waitErr  error

	mu          sync.Mutex
	exited      bool // process handle closed: never use it again
	consoleOpen bool

	closeOnce sync.Once
}

// Start runs shell attached to a new pseudo console of the given size.
func Start(shell string, args, env []string, cwd string, cols, rows uint16) (Terminal, error) {
	// The generated wrappers panic when the API is missing, so check first.
	if windows.NewLazySystemDLL("kernel32.dll").NewProc("CreatePseudoConsole").Find() != nil {
		return nil, ErrUnsupported
	}
	var inRead, inWrite, outRead, outWrite windows.Handle
	if err := windows.CreatePipe(&inRead, &inWrite, nil, 0); err != nil {
		return nil, fmt.Errorf("create input pipe: %w", err)
	}
	if err := windows.CreatePipe(&outRead, &outWrite, nil, 0); err != nil {
		closeHandles(inRead, inWrite)
		return nil, fmt.Errorf("create output pipe: %w", err)
	}
	var console windows.Handle
	err := windows.CreatePseudoConsole(coord(cols, rows), inRead, outWrite, 0, &console)
	closeHandles(inRead, outWrite) // the console keeps its own duplicates
	if err != nil {
		closeHandles(inWrite, outRead)
		return nil, fmt.Errorf("create pseudo console: %w", err)
	}
	t := &conPTY{
		console:     console,
		in:          os.NewFile(uintptr(inWrite), "conpty-in"),
		out:         os.NewFile(uintptr(outRead), "conpty-out"),
		done:        make(chan struct{}),
		consoleOpen: true,
	}
	pi, err := spawn(console, shell, args, env, cwd)
	if err != nil {
		// Output end first, so closing the console cannot wait on a reader.
		_ = t.out.Close()
		_ = t.in.Close()
		t.shutConsole()
		return nil, err
	}
	_ = windows.CloseHandle(pi.Thread)
	t.process = pi.Process
	go t.reap()
	return t, nil
}

func spawn(console windows.Handle, shell string, args, env []string, cwd string) (*windows.ProcessInformation, error) {
	path, err := exec.LookPath(shell)
	if err != nil {
		return nil, fmt.Errorf("find %s: %w", shell, err)
	}
	cmdline, err := windows.UTF16PtrFromString(windows.ComposeCommandLine(append([]string{path}, args...)))
	if err != nil {
		return nil, fmt.Errorf("command line: %w", err)
	}
	var dir *uint16
	if cwd != "" {
		if dir, err = windows.UTF16PtrFromString(cwd); err != nil {
			return nil, fmt.Errorf("working directory: %w", err)
		}
	}
	block, err := envBlock(env)
	if err != nil {
		return nil, err
	}

	attrs, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return nil, fmt.Errorf("attribute list: %w", err)
	}
	defer attrs.Delete()
	// The attribute's value is the console handle itself, not a pointer to it.
	if err := attrs.Update(windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, *(*unsafe.Pointer)(unsafe.Pointer(&console)), unsafe.Sizeof(console)); err != nil {
		return nil, fmt.Errorf("attach pseudo console: %w", err)
	}
	si := windows.StartupInfoEx{ProcThreadAttributeList: attrs.List()}
	si.Cb = uint32(unsafe.Sizeof(si))
	// Empty std handles: otherwise a child of an agent whose own output is
	// redirected (a service, a log file) inherits those instead of the console.
	si.Flags = windows.STARTF_USESTDHANDLES

	var pi windows.ProcessInformation
	flags := uint32(windows.EXTENDED_STARTUPINFO_PRESENT | windows.CREATE_UNICODE_ENVIRONMENT)
	if err := windows.CreateProcess(nil, cmdline, nil, nil, false, flags, block, dir, &si.StartupInfo, &pi); err != nil {
		return nil, fmt.Errorf("start %s: %w", shell, err)
	}
	return &pi, nil
}

// envBlock encodes env for CreateProcess: NUL-terminated UTF-16 "key=value"
// strings followed by one more NUL.
func envBlock(env []string) (*uint16, error) {
	var b []uint16
	for _, kv := range env {
		if strings.ContainsRune(kv, 0) {
			return nil, fmt.Errorf("environment entry %q contains NUL", kv)
		}
		b = append(b, utf16.Encode([]rune(kv))...)
		b = append(b, 0)
	}
	if len(b) == 0 {
		b = append(b, 0)
	}
	b = append(b, 0)
	return &b[0], nil
}

func coord(cols, rows uint16) windows.Coord {
	clamp := func(v uint16) int16 { return int16(min(v, 0x7fff)) }
	return windows.Coord{X: clamp(cols), Y: clamp(rows)}
}

func closeHandles(hs ...windows.Handle) {
	for _, h := range hs {
		_ = windows.CloseHandle(h)
	}
}

// reap waits for the process, then closes the console: it flushes the last
// output and closes its end of the output pipe, so Read drains everything and
// then reports io.EOF, as on Unix.
func (t *conPTY) reap() {
	if _, err := windows.WaitForSingleObject(t.process, windows.INFINITE); err != nil {
		t.exitCode, t.waitErr = -1, fmt.Errorf("wait for shell: %w", err)
	} else {
		var code uint32
		if err := windows.GetExitCodeProcess(t.process, &code); err != nil {
			t.exitCode, t.waitErr = -1, fmt.Errorf("shell exit code: %w", err)
		} else {
			t.exitCode = int(code)
		}
	}
	t.mu.Lock()
	t.exited = true
	_ = windows.CloseHandle(t.process)
	t.mu.Unlock()
	close(t.done)
	t.shutConsole()
}

// shutConsole closes the pseudo console once. Clients still attached get
// CTRL_CLOSE_EVENT. It may block until the output pipe is drained or closed.
func (t *conPTY) shutConsole() {
	t.mu.Lock()
	open := t.consoleOpen
	t.consoleOpen = false
	t.mu.Unlock()
	if open {
		windows.ClosePseudoConsole(t.console)
	}
}

func (t *conPTY) Read(p []byte) (int, error)  { return t.out.Read(p) }
func (t *conPTY) Write(p []byte) (int, error) { return t.in.Write(p) }

func (t *conPTY) Resize(cols, rows uint16) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.consoleOpen {
		return nil
	}
	if err := windows.ResizePseudoConsole(t.console, coord(cols, rows)); err != nil {
		return fmt.Errorf("resize pseudo console: %w", err)
	}
	return nil
}

func (t *conPTY) Wait() (int, error) {
	<-t.done
	return t.exitCode, t.waitErr
}

func (t *conPTY) Close() error {
	var err error
	t.closeOnce.Do(func() {
		// Our pipe ends first: no more input reaches the shell, and closing
		// the console cannot block on output nobody reads.
		err = errors.Join(t.in.Close(), t.out.Close())
		t.shutConsole()
		go t.killAfterGrace()
	})
	return err
}

func (t *conPTY) killAfterGrace() {
	timer := time.NewTimer(killGrace)
	defer timer.Stop()
	select {
	case <-t.done:
	case <-timer.C:
		t.mu.Lock()
		if !t.exited {
			_ = windows.TerminateProcess(t.process, 1)
		}
		t.mu.Unlock()
	}
}

// DefaultShell prefers PowerShell 7 (pwsh), then Windows PowerShell, then
// %ComSpec% (cmd.exe).
func DefaultShell() (string, []string) {
	for _, s := range []string{"pwsh.exe", "powershell.exe"} {
		if p, err := exec.LookPath(s); err == nil {
			return p, []string{"-NoLogo"}
		}
	}
	if c := os.Getenv("ComSpec"); c != "" {
		return c, nil
	}
	return "cmd.exe", nil
}

// LoginArgs has no meaning on Windows.
func LoginArgs(string) []string { return nil }
