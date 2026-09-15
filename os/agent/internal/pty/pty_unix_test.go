//go:build linux || darwin

package pty

import (
	"bytes"
	"os"
	"strings"
	"testing"
	"time"
)

func start(t *testing.T, script string) Terminal {
	t.Helper()
	term, err := Start("/bin/sh", []string{"-c", script}, os.Environ(), "", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = term.Close() })
	return term
}

// readAll collects output until the terminal reports EOF/EIO.
func readAll(t *testing.T, term Terminal) string {
	t.Helper()
	done := make(chan string, 1)
	go func() {
		var out bytes.Buffer
		buf := make([]byte, 1024)
		for {
			n, err := term.Read(buf)
			out.Write(buf[:n])
			if err != nil {
				done <- out.String()
				return
			}
		}
	}()
	select {
	case s := <-done:
		return s
	case <-time.After(10 * time.Second):
		t.Fatal("timed out reading pty")
		return ""
	}
}

func TestStartOutputAndExitCode(t *testing.T) {
	term := start(t, "printf hello; exit 3")
	if out := readAll(t, term); !strings.Contains(out, "hello") {
		t.Fatalf("output %q lacks hello", out)
	}
	if code, err := term.Wait(); err != nil || code != 3 {
		t.Fatalf("exit = %d, %v; want 3", code, err)
	}
}

func TestResizeReachesChild(t *testing.T) {
	term := start(t, "sleep 0.3; stty size")
	if err := term.Resize(132, 43); err != nil {
		t.Fatal(err)
	}
	if out := readAll(t, term); !strings.Contains(out, "43 132") {
		t.Fatalf("stty size = %q, want 43 132", out)
	}
}

func TestSignalledExitCode(t *testing.T) {
	term := start(t, "kill -TERM $$")
	readAll(t, term)
	if code, _ := term.Wait(); code != 128+15 {
		t.Fatalf("exit = %d, want 143", code)
	}
}

func TestCloseHangsUp(t *testing.T) {
	term := start(t, "sleep 30")
	if err := term.Close(); err != nil {
		t.Fatal(err)
	}
	done := make(chan int, 1)
	go func() { code, _ := term.Wait(); done <- code }()
	select {
	case code := <-done:
		if code != 128+1 {
			t.Fatalf("exit = %d, want 129 (SIGHUP)", code)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("process survived hang-up")
	}
	if err := term.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
}

func TestLoginArgs(t *testing.T) {
	if got := LoginArgs("/usr/bin/zsh"); len(got) != 1 || got[0] != "-l" {
		t.Errorf("zsh: %v", got)
	}
	if got := LoginArgs("/usr/bin/python3"); got != nil {
		t.Errorf("python3: %v", got)
	}
}
