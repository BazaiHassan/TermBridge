//go:build windows

package pty

import (
	"bytes"
	"io"
	"os"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
	"unsafe"
)

func comspec() string {
	if c := os.Getenv("ComSpec"); c != "" {
		return c
	}
	return "cmd.exe"
}

func TestConPTYRunsCommandAndReportsExitCode(t *testing.T) {
	term, err := Start(comspec(), []string{"/c", "echo hello-conpty& exit /b 3"}, os.Environ(), "", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()

	var out bytes.Buffer
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		_, _ = io.Copy(&out, term)
	}()
	code, err := term.Wait()
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-drained:
	case <-time.After(10 * time.Second):
		t.Fatal("output never reached EOF after the process exited")
	}
	if code != 3 {
		t.Fatalf("exit code %d, want 3", code)
	}
	if !strings.Contains(out.String(), "hello-conpty") {
		t.Fatalf("output %q lacks the echo", out.String())
	}
}

func TestConPTYResizeThenCloseEndsTheShell(t *testing.T) {
	shell, args := DefaultShell()
	term, err := Start(shell, args, os.Environ(), "", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	go func() { _, _ = io.Copy(io.Discard, term) }()
	if err := term.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	if err := term.Close(); err != nil {
		t.Fatal(err)
	}
	exited := make(chan struct{})
	go func() {
		_, _ = term.Wait()
		close(exited)
	}()
	select {
	case <-exited:
	case <-time.After(killGrace + 10*time.Second):
		t.Fatal("the shell survived Close")
	}
	if err := term.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
	if err := term.Resize(80, 24); err != nil {
		t.Fatalf("Resize after Close: %v", err)
	}
}

func TestEnvBlockEncoding(t *testing.T) {
	p, err := envBlock([]string{"A=1", "PATH=C:\\x"})
	if err != nil {
		t.Fatal(err)
	}
	want := "A=1\x00PATH=C:\\x\x00\x00"
	if got := string(utf16.Decode(unsafe.Slice(p, len(want)))); got != want {
		t.Fatalf("block %q, want %q", got, want)
	}
	if _, err := envBlock([]string{"BAD=\x00"}); err == nil {
		t.Fatal("NUL inside an entry was accepted")
	}
}
