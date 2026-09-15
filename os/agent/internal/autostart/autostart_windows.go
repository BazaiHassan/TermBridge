//go:build windows

package autostart

import (
	"errors"
	"fmt"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`

// Enable adds e to the current user's Run key, which Windows starts at the
// next sign-in.
func Enable(e Entry) (string, error) {
	if err := e.validate(); err != nil {
		return "", err
	}
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err != nil {
		return "", fmt.Errorf("open Run key: %w", err)
	}
	defer k.Close()
	if err := k.SetStringValue(e.ID, commandLine(e)); err != nil {
		return "", fmt.Errorf("write Run value: %w", err)
	}
	return `HKCU\` + runKey + `\` + e.ID, nil
}

// Disable removes e from the Run key; a running instance keeps running.
func Disable(e Entry) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open Run key: %w", err)
	}
	defer k.Close()
	if err := k.DeleteValue(e.ID); err != nil && !errors.Is(err, registry.ErrNotExist) {
		return fmt.Errorf("delete Run value: %w", err)
	}
	return nil
}

// Enabled reports whether e is in the Run key.
func Enabled(e Entry) bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(e.ID)
	return err == nil
}

func commandLine(e Entry) string {
	return windows.ComposeCommandLine(append([]string{e.Exe}, e.Args...))
}
