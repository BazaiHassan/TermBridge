package proto

import "sync"

// Buffer size classes. Small frames (keystroke echo, control) dominate, and a
// queued 3-byte echo must not pin a 64 KiB buffer. The largest class fits a
// whole encrypted message.
var sizeClasses = [...]int{256, 4096, MaxMessage}

var pools [len(sizeClasses)]sync.Pool

func init() {
	for i, size := range sizeClasses {
		pools[i].New = func() any {
			b := make([]byte, 0, size)
			return &b
		}
	}
}

// GetBuffer returns an empty buffer with capacity of at least n. Return it
// with PutBuffer once it is no longer referenced.
func GetBuffer(n int) *[]byte {
	for i, size := range sizeClasses {
		if n <= size {
			b := pools[i].Get().(*[]byte)
			*b = (*b)[:0]
			return b
		}
	}
	b := make([]byte, 0, n)
	return &b
}

// PutBuffer recycles b. Buffers that grew past their class are dropped.
func PutBuffer(b *[]byte) {
	for i, size := range sizeClasses {
		if cap(*b) == size {
			*b = (*b)[:0]
			pools[i].Put(b)
			return
		}
	}
}
