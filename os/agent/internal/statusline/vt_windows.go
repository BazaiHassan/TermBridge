//go:build windows

package statusline

import (
	"os"

	"golang.org/x/sys/windows"
)

// enableVT turns on VT sequence processing, which the classic Windows console
// (conhost) leaves off. False when f is not a console that supports it.
func enableVT(f *os.File) bool {
	h := windows.Handle(f.Fd())
	var mode uint32
	if err := windows.GetConsoleMode(h, &mode); err != nil {
		return false
	}
	return windows.SetConsoleMode(h, mode|windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING) == nil
}
