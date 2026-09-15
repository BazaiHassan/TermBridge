// Package hub pairs phone connections with agents on the relay
// (PROTOCOL.md §9). It never looks at message content.
package hub

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Errors returned by Open.
var (
	ErrAgentOffline    = errors.New("hub: agent offline")
	ErrTooManySessions = errors.New("hub: too many sessions for this agent")
	ErrNoAnswer        = errors.New("hub: agent did not answer")
)

// Config sets the per-agent limits.
type Config struct {
	MaxSessions    int           // concurrent sessions per agent; 0 means 5
	BytesPerSecond int           // relayed bytes per second per agent; 0 means 10 Mbit/s
	AcceptTimeout  time.Duration // how long the agent has to open the data channel; 0 means 10 s
}

func (c Config) withDefaults() Config {
	if c.MaxSessions <= 0 {
		c.MaxSessions = 5
	}
	if c.BytesPerSecond <= 0 {
		c.BytesPerSecond = 10_000_000 / 8
	}
	if c.AcceptTimeout <= 0 {
		c.AcceptTimeout = 10 * time.Second
	}
	return c
}

// Notify asks an agent, over its control channel, to open a data channel
// for token.
type Notify func(ctx context.Context, token string) error

// Agent is a registered agent control channel.
type Agent struct {
	ID       string
	notify   Notify
	kick     context.CancelFunc // closes the control channel when replaced
	limiter  *Limiter
	sessions int
}

// Limiter returns the agent's shared bandwidth budget.
func (a *Agent) Limiter() *Limiter { return a.limiter }

// Hub is safe for concurrent use.
type Hub struct {
	cfg     Config
	mu      sync.Mutex
	agents  map[string]*Agent
	pending map[string]*Pending
}

// New returns an empty hub.
func New(cfg Config) *Hub {
	return &Hub{cfg: cfg.withDefaults(), agents: make(map[string]*Agent), pending: make(map[string]*Pending)}
}

// Register installs a control channel for id, replacing (and kicking) any
// previous one. kick must close that control channel. The returned func
// unregisters it.
func (h *Hub) Register(id string, notify Notify, kick context.CancelFunc) (*Agent, func()) {
	a := &Agent{ID: id, notify: notify, kick: kick, limiter: NewLimiter(h.cfg.BytesPerSecond)}
	h.mu.Lock()
	if old := h.agents[id]; old != nil {
		old.kick()
		a.limiter = old.limiter // keep the budget across reconnects
		a.sessions = old.sessions
	}
	h.agents[id] = a
	h.mu.Unlock()
	return a, func() {
		h.mu.Lock()
		if h.agents[id] == a {
			delete(h.agents, id)
		}
		h.mu.Unlock()
	}
}

// Online reports whether an agent is connected.
func (h *Hub) Online(id string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.agents[id] != nil
}

// Pending is a phone waiting for its agent's data channel.
type Pending struct {
	hub   *Hub
	agent *Agent
	token string
	conn  chan accepted
	once  sync.Once
}

type accepted struct {
	c    *websocket.Conn
	done chan struct{} // closed when the bridge ends, releasing the accept handler
}

// Open reserves a session slot for agent id and asks the agent for a data
// channel. Call Close when the session ends.
func (h *Hub) Open(ctx context.Context, id string) (*Pending, error) {
	token, err := newToken()
	if err != nil {
		return nil, err
	}
	h.mu.Lock()
	a := h.agents[id]
	switch {
	case a == nil:
		h.mu.Unlock()
		return nil, ErrAgentOffline
	case a.sessions >= h.cfg.MaxSessions:
		h.mu.Unlock()
		return nil, ErrTooManySessions
	}
	a.sessions++
	p := &Pending{hub: h, agent: a, token: token, conn: make(chan accepted, 1)}
	h.pending[token] = p
	h.mu.Unlock()

	nctx, cancel := context.WithTimeout(ctx, h.cfg.AcceptTimeout)
	defer cancel()
	if err := a.notify(nctx, token); err != nil {
		p.Close()
		return nil, ErrAgentOffline
	}
	return p, nil
}

// Agent returns the agent this session belongs to.
func (p *Pending) Agent() *Agent { return p.agent }

// Wait returns the agent's data channel and a func to call when the bridge
// ends.
func (p *Pending) Wait(ctx context.Context) (*websocket.Conn, func(), error) {
	ctx, cancel := context.WithTimeout(ctx, p.hub.cfg.AcceptTimeout)
	defer cancel()
	select {
	case a := <-p.conn:
		return a.c, func() { close(a.done) }, nil
	case <-ctx.Done():
		return nil, nil, ErrNoAnswer
	}
}

// Close releases the session slot. Safe to call more than once.
func (p *Pending) Close() {
	p.once.Do(func() {
		h := p.hub
		h.mu.Lock()
		delete(h.pending, p.token)
		p.agent.sessions--
		h.mu.Unlock()
	})
}

// Accept hands the agent's data channel to the waiting phone. It returns a
// channel closed when the bridge ends (the accept handler must stay alive
// until then), or false for an unknown or expired token.
func (h *Hub) Accept(token string, c *websocket.Conn) (<-chan struct{}, bool) {
	h.mu.Lock()
	p := h.pending[token]
	delete(h.pending, token)
	h.mu.Unlock()
	if p == nil {
		return nil, false
	}
	done := make(chan struct{})
	select {
	case p.conn <- accepted{c: c, done: done}:
		return done, true
	default:
		return nil, false
	}
}

func newToken() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
