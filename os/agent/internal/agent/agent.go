// Package agent wires identity, pairing, transport and sessions into a
// running TermBridge agent. The CLI and the desktop app are thin front ends
// over it.
package agent

import (
	"cmp"
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/flynn/noise"

	"termbridge/agent/internal/discovery"
	"termbridge/agent/internal/link"
	"termbridge/agent/internal/pairing"
	"termbridge/agent/internal/session"
	"termbridge/agent/internal/store"
	"termbridge/agent/internal/transport"
	"termbridge/internal/identity"
	"termbridge/internal/proto"
)

// revocationPoll is how often devices.json is checked for changes made by
// another process (`termbridge revoke`); revocation must be immediate.
const revocationPoll = time.Second

// Events observes the agent; every method must return quickly.
type Events interface {
	link.Events
	Listening(addrs []string)
	Paired(d store.Device)
	RelayChanged(s transport.RelayStatus)
}

// Config configures an Agent.
type Config struct {
	Store       *store.Store
	Listen      []string // explicit listen addresses; empty: every loopback and private address
	Port        int      // default transport.DefaultPort
	Shell       string
	MaxSessions int
	Version     string
	Logger      *slog.Logger
	Events      Events // optional
	// Relay is the relay base URL (PROTOCOL.md §9); empty disables it.
	Relay string
	// LANOnly accepts peers from the local network only and disables the
	// relay. Otherwise any peer may try; only paired keys get past Noise.
	LANOnly bool
}

// Agent is a configured, not yet running agent.
type Agent struct {
	cfg      Config
	log      *slog.Logger
	events   Events
	identity ed25519.PrivateKey
	edPub    ed25519.PublicKey
	static   noise.DHKey
	sessions *session.Manager
	hostname string

	mu     sync.Mutex
	window *pairing.Window
	conns  map[*liveConn]struct{}
	wan    []string // public addresses the relay verified as reachable
}

type liveConn struct {
	key    []byte
	cancel context.CancelFunc
}

// New loads (or creates) the identity and prepares the agent.
func New(cfg Config) (*Agent, error) {
	if cfg.Store == nil {
		return nil, errors.New("agent: store is required")
	}
	cfg.Port = cmp.Or(cfg.Port, transport.DefaultPort)
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	priv, err := cfg.Store.Identity()
	if err != nil {
		return nil, err
	}
	sk, pk, err := identity.NoiseKey(priv)
	if err != nil {
		return nil, err
	}
	hostname, _ := os.Hostname()
	a := &Agent{
		cfg:      cfg,
		log:      cfg.Logger,
		events:   cfg.Events,
		identity: priv,
		edPub:    priv.Public().(ed25519.PublicKey),
		static:   noise.DHKey{Private: sk, Public: pk},
		hostname: cmp.Or(hostname, "computer"),
		conns:    make(map[*liveConn]struct{}),
		sessions: session.NewManager(session.Config{Shell: cfg.Shell, MaxSessions: cfg.MaxSessions, Logger: cfg.Logger}),
	}
	if a.events == nil {
		a.events = nopEvents{}
	}
	return a, nil
}

// AgentID is the base64 Ed25519 public key shown in the QR code.
func (a *Agent) AgentID() string { return identity.AgentID(a.edPub) }

// Fingerprint is a short, human-comparable form of the agent's key.
func (a *Agent) Fingerprint() string { return identity.Fingerprint(a.edPub) }

// Hostname is the name the phone shows for this machine.
func (a *Agent) Hostname() string { return a.hostname }

// Devices lists paired phones.
func (a *Agent) Devices() ([]store.Device, error) { return a.cfg.Store.Devices() }

// StartPairing opens a fresh pairing window, replacing any open one, and
// returns the QR payload for it.
func (a *Agent) StartPairing() (pairing.Payload, *pairing.Window, error) {
	lan, err := transport.PhoneAddrs(a.cfg.Port)
	if err != nil {
		return pairing.Payload{}, nil, err
	}
	if len(lan) == 0 {
		return pairing.Payload{}, nil, errors.New("no Wi-Fi or Ethernet address found; connect this computer to the phone's network")
	}
	w, err := pairing.NewWindow()
	if err != nil {
		return pairing.Payload{}, nil, err
	}
	a.mu.Lock()
	a.window = w
	a.mu.Unlock()
	relay := a.cfg.Relay
	if a.cfg.LANOnly {
		relay = ""
	}
	return pairing.Payload{V: 1, AgentID: a.AgentID(), Name: a.hostname, Code: w.Code(), LAN: lan, WAN: a.wanAddrs(), Relay: relay}, w, nil
}

// StopPairing closes the pairing window.
func (a *Agent) StopPairing() {
	a.mu.Lock()
	a.window = nil
	a.mu.Unlock()
}

// Revoke unpairs a device and disconnects it at once.
func (a *Agent) Revoke(name string) (store.Device, error) {
	d, err := a.cfg.Store.RemoveDevice(name)
	if err == nil {
		a.dropRevoked()
	}
	return d, err
}

// Run serves until ctx ends; every session is hung up before it returns.
func (a *Agent) Run(ctx context.Context) error {
	addrs := a.cfg.Listen
	if len(addrs) == 0 {
		var err error
		if addrs, err = transport.LANListenAddrs(a.cfg.Port); err != nil {
			return err
		}
	}
	allow := func(netip.Addr) bool { return true } // Noise authenticates every peer (ADR 0007)
	if a.cfg.LANOnly {
		allow = transport.IsLAN
	}
	srv, err := transport.Listen(transport.ServerConfig{
		Addrs:     addrs,
		Static:    a.static,
		Authorize: a.authorize,
		Handler:   a.handle,
		Logger:    a.log,
		AllowPeer: allow,
	})
	if err != nil {
		return err
	}
	bound := make([]string, 0, len(addrs))
	for _, ad := range srv.Addrs() {
		bound = append(bound, ad.String())
		a.log.Debug("listening", "url", "ws://"+ad.String()+transport.Path)
	}
	a.log.Info("agent ready", "addresses", len(bound), "fingerprint", a.Fingerprint())
	a.events.Listening(bound)
	go a.watchRevocations(ctx)
	if err := discovery.Advertise(ctx, a.hostname, a.cfg.Port, a.edPub); err != nil {
		a.log.Warn("LAN discovery unavailable", "err", err)
	}
	var relay sync.WaitGroup
	if a.cfg.Relay != "" && !a.cfg.LANOnly {
		relay.Add(1)
		go func() {
			defer relay.Done()
			transport.RunRelay(ctx, transport.RelayConfig{
				URL:       a.cfg.Relay,
				Identity:  a.identity,
				Static:    a.static,
				Authorize: a.authorize,
				Handler:   a.handle,
				ProbePort: a.cfg.Port,
				Version:   a.cfg.Version,
				Logger:    a.log,
				OnStatus:  a.relayChanged,
			})
		}()
	}
	err = srv.Serve(ctx)
	relay.Wait()
	return err
}

// relayChanged records the public address once the relay confirms it is
// reachable directly (a port forward on the router), so phones can skip the
// relay next time.
func (a *Agent) relayChanged(s transport.RelayStatus) {
	a.mu.Lock()
	if s.Connected {
		a.wan = nil
		if s.Reachable && s.ObservedIP != "" {
			a.wan = []string{net.JoinHostPort(s.ObservedIP, strconv.Itoa(a.cfg.Port))}
		}
	}
	a.mu.Unlock()
	a.events.RelayChanged(s)
}

func (a *Agent) wanAddrs() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return slices.Clone(a.wan)
}

// addrs are this agent's current addresses for HELLO_ACK (PROTOCOL.md §9.4).
func (a *Agent) addrs() *proto.Addrs {
	lan, _ := transport.PhoneAddrs(a.cfg.Port)
	return &proto.Addrs{LAN: lan, WAN: a.wanAddrs()}
}

func (a *Agent) authorize(key, payload []byte) (transport.Peer, bool) {
	if d, ok, err := a.cfg.Store.FindDevice(key); err != nil {
		a.log.Error("read devices", "err", err)
		return transport.Peer{}, false
	} else if ok {
		return transport.Peer{Name: d.Name}, true
	}
	var hp proto.HandshakePayload
	if len(payload) > 0 && json.Unmarshal(payload, &hp) == nil && hp.Pair != nil {
		a.mu.Lock()
		w := a.window
		a.mu.Unlock()
		if w != nil && w.Consume(hp.Pair.Code) {
			d, err := a.cfg.Store.AddDevice(key, cleanName(hp.Pair.Name), time.Now())
			if err != nil {
				a.log.Error("save paired device", "err", err)
				return transport.Peer{}, false
			}
			a.log.Info("device paired", "name", d.Name, "key", identity.Fingerprint(key))
			a.events.Paired(d)
			return transport.Peer{Name: d.Name}, true
		}
	}
	a.log.Warn("rejected unpaired device", "key", identity.Fingerprint(key))
	return transport.Peer{}, false
}

func (a *Agent) handle(ctx context.Context, c transport.Conn, peer transport.Peer) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	lc := &liveConn{key: peer.Key, cancel: cancel}
	a.mu.Lock()
	a.conns[lc] = struct{}{}
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		delete(a.conns, lc)
		a.mu.Unlock()
	}()
	if err := a.cfg.Store.TouchDevice(peer.Key, time.Now()); err != nil {
		a.log.Warn("record last seen", "err", err)
	}
	opts := link.Options{
		Sessions: a.sessions,
		Ack: proto.HelloAck{
			V:        proto.Version,
			Agent:    "termbridge-agent/" + cmp.Or(a.cfg.Version, "dev"),
			OS:       runtime.GOOS,
			Hostname: a.hostname,
			Addrs:    a.addrs(),
		},
		PeerName: peer.Name,
		Logger:   a.log,
		Events:   a.events,
	}
	if err := link.Serve(ctx, c, opts); err != nil {
		a.log.Info("connection ended", "peer", peer.Name, "err", err)
	}
}

func (a *Agent) watchRevocations(ctx context.Context) {
	ticker := time.NewTicker(revocationPoll)
	defer ticker.Stop()
	last := a.cfg.Store.DevicesModTime()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if mt := a.cfg.Store.DevicesModTime(); !mt.Equal(last) {
				last = mt
				a.dropRevoked()
			}
		}
	}
}

// dropRevoked disconnects every connection whose device is no longer paired.
func (a *Agent) dropRevoked() {
	a.mu.Lock()
	live := make([]*liveConn, 0, len(a.conns))
	for lc := range a.conns {
		live = append(live, lc)
	}
	a.mu.Unlock()
	for _, lc := range live {
		if _, ok, err := a.cfg.Store.FindDevice(lc.key); err == nil && !ok {
			a.log.Warn("device revoked; disconnecting", "key", identity.Fingerprint(lc.key))
			lc.cancel()
		}
	}
}

// cleanName keeps a device name printable and short; names come from the
// phone and end up in logs and terminals.
func cleanName(s string) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || r == unicode.ReplacementChar {
			return -1
		}
		return r
	}, strings.TrimSpace(s))
	if r := []rune(s); len(r) > 40 {
		s = string(r[:40])
	}
	return cmp.Or(strings.TrimSpace(s), "phone")
}

type nopEvents struct{}

func (nopEvents) PeerConnected(string)               {}
func (nopEvents) PeerDisconnected(string)            {}
func (nopEvents) SessionOpened(string, uint8)        {}
func (nopEvents) SessionClosed(string, uint8)        {}
func (nopEvents) Listening([]string)                 {}
func (nopEvents) Paired(store.Device)                {}
func (nopEvents) RelayChanged(transport.RelayStatus) {}
