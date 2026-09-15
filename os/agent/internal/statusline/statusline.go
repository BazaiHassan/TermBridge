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

	"termbridge/agent/internal/transport"
)

// Line is the indicator. When the output is not a terminal it only passes
// log lines through.
type Line struct {
	mu        sync.Mutex
	w         io.Writer
	tty       bool
	drawn     bool
	listening string
	relay     string
	peers     map[string]int  // peer → open sessions (attached or detached)
	away      map[string]bool // peers whose shells run detached
}

// New draws on f if f is a terminal.
func New(f *os.File) *Line {
	fi, err := f.Stat()
	tty := err == nil && fi.Mode()&os.ModeCharDevice != 0 && enableVT(f)
	return &Line{w: f, tty: tty, peers: make(map[string]int), away: make(map[string]bool)}
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
func (l *Line) PeerConnected(peer string) {
	l.update(func() {
		if _, ok := l.peers[peer]; !ok {
			l.peers[peer] = 0
		}
		delete(l.away, peer)
	})
}

// PeerDisconnected implements link.Events.
func (l *Line) PeerDisconnected(peer string) {
	l.update(func() {
		if l.peers[peer] == 0 {
			delete(l.peers, peer)
		} else {
			l.away[peer] = true // its shells keep running until the resume window ends
		}
	})
}

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
		if l.peers[peer] == 0 && l.away[peer] {
			delete(l.peers, peer)
			delete(l.away, peer)
		}
	})
}

// RelayChanged shows the relay state while the agent is idle.
func (l *Line) RelayChanged(s transport.RelayStatus) {
	l.update(func() {
		switch {
		case s.Connected && s.Reachable:
			l.relay = "relay ✓ · direct ✓"
		case s.Connected:
			l.relay = "relay ✓"
		default:
			l.relay = "relay ✗"
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
		detached := ""
		if len(l.away) > 0 {
			detached = " (detached, waiting for the phone)"
		}
		return fmt.Sprintf("\x1b[1;97;41m ● LIVE \x1b[0m \x1b[1m%d shell session%s open\x1b[0m from %s%s · Ctrl+C stops the agent",
			sessions, plural(sessions), who, detached)
	case len(l.peers) > 0:
		return fmt.Sprintf("\x1b[30;43m ● CONNECTED \x1b[0m %s · no shell open", who)
	default:
		relay := ""
		if l.relay != "" {
			relay = " · " + l.relay
		}
		return fmt.Sprintf("\x1b[2m○ waiting for devices on %s%s\x1b[0m", l.listening, relay)
	}
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}
