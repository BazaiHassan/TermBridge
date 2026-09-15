//go:build !windows

package statusline

import "os"

// enableVT: Unix terminals understand VT sequences already.
func enableVT(*os.File) bool { return true }
