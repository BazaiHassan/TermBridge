// Package statusline keeps a live indicator on the last line of the agent's
// terminal so the user cannot forget the machine is reachable
// (architecture §8.8). It doubles as the log writer: each log line clears the
// indicator, prints, and redraws it, so the two never interleave.
package statusline

import (
	"fmt"
	"io"
	"net"
	"os"
	"slices"
	"strings"
	"sync"
)

// Line is the indicator. When the output is not a terminal it only passes
// log lines through.
type Line struct {
	mu        sync.Mutex
	w         io.Writer
	tty       bool
	drawn     bool
	listening string
	peers     map[string]int // connected peer → open sessions
}

// New draws on f if f is a terminal.
func New(f *os.File) *Line {
	fi, err := f.Stat()
	return &Line{w: f, tty: err == nil && fi.Mode()&os.ModeCharDevice != 0, peers: make(map[string]int)}
}

// Write implements io.Writer for the logger.
func (l *Line) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.clear()
	n, err := l.w.Write(p)
	l.draw()
	return n, err
}

// Listening records the addresses shown while no device is connected.
func (l *Line) Listening(addrs []string) {
	l.update(func() { l.listening = strings.Join(addrs, ", ") })
}

// PeerConnected implements link.Events.
func (l *Line) PeerConnected(peer string) { l.update(func() { l.peers[peer] = 0 }) }

// PeerDisconnected implements link.Events.
func (l *Line) PeerDisconnected(peer string) { l.update(func() { delete(l.peers, peer) }) }

// SessionOpened implements link.Events.
func (l *Line) SessionOpened(peer string, _ uint8) {
	l.update(func() {
		if _, ok := l.peers[peer]; ok {
			l.peers[peer]++
		}
	})
}

// SessionClosed implements link.Events.
func (l *Line) SessionClosed(peer string, _ uint8) {
	l.update(func() {
		if l.peers[peer] > 0 {
			l.peers[peer]--
		}
	})
}

// Close removes the indicator for good.
func (l *Line) Close() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.clear()
	l.tty = false
}

func (l *Line) update(fn func()) {
	l.mu.Lock()
	defer l.mu.Unlock()
	fn()
	l.clear()
	l.draw()
}

func (l *Line) clear() {
	if l.tty && l.drawn {
		_, _ = io.WriteString(l.w, "\r\x1b[2K")
		l.drawn = false
	}
}

func (l *Line) draw() {
	if !l.tty {
		return
	}
	_, _ = io.WriteString(l.w, l.render())
	l.drawn = true
}

func (l *Line) render() string {
	sessions := 0
	hosts := make([]string, 0, len(l.peers))
	for peer, n := range l.peers {
		sessions += n
		if host, _, err := net.SplitHostPort(peer); err == nil {
			peer = host
		}
		hosts = append(hosts, peer)
	}
	slices.Sort(hosts)
	hosts = slices.Compact(hosts)
	who := strings.Join(hosts, ", ")
	switch {
	case sessions > 0:
		return fmt.Sprintf("\x1b[1;97;41m ● LIVE \x1b[0m \x1b[1m%d shell session%s open\x1b[0m from %s · Ctrl+C stops the agent",
			sessions, plural(sessions), who)
	case len(l.peers) > 0:
		return fmt.Sprintf("\x1b[30;43m ● CONNECTED \x1b[0m %s · no shell open", who)
	default:
		return fmt.Sprintf("\x1b[2m○ waiting for devices on %s\x1b[0m", l.listening)
	}
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}
