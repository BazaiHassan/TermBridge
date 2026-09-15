package transport

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"

	"termbridge/internal/proto"
)

// RelayStatus reports the agent's connection to its relay.
type RelayStatus struct {
	Connected  bool
	ObservedIP string // the agent's public IP as the relay sees it
	Reachable  bool   // the relay reached ObservedIP:ProbePort directly
	Err        error
}

// RelayConfig configures the agent side of the relay (PROTOCOL.md §9).
type RelayConfig struct {
	URL       string             // ws(s):// or http(s):// base URL of the relay
	Identity  ed25519.PrivateKey // proves the agent ID to the relay
	Static    noise.DHKey        // Noise static key, as for the LAN server
	Authorize Authorizer
	Handler   Handler
	ProbePort int // when > 0, ask the relay whether ObservedIP:ProbePort is reachable
	Version   string
	Logger    *slog.Logger
	OnStatus  func(RelayStatus)
}

const (
	relayMaxBackoff = time.Minute
	relayPing       = 30 * time.Second
)

// RunRelay keeps a control channel to the relay open until ctx ends,
// reconnecting with exponential backoff (1 s … 60 s), and serves every
// session the relay hands over exactly like a LAN connection. Sessions
// survive a reconnect of the control channel.
func RunRelay(ctx context.Context, cfg RelayConfig) {
	base := strings.TrimRight(cfg.URL, "/")
	if cfg.OnStatus == nil {
		cfg.OnStatus = func(RelayStatus) {}
	}
	var sessions sync.WaitGroup
	defer sessions.Wait()
	backoff := time.Second
	for ctx.Err() == nil {
		start := time.Now()
		err := relayControl(ctx, cfg, base, &sessions)
		if ctx.Err() != nil {
			return
		}
		cfg.OnStatus(RelayStatus{Err: err})
		if time.Since(start) > relayMaxBackoff {
			backoff = time.Second
		}
		cfg.Logger.Info("relay disconnected", "err", err, "retry_in", backoff)
		t := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
		backoff = min(backoff*2, relayMaxBackoff)
	}
}

// relayControl runs one control channel until it fails.
func relayControl(parent context.Context, cfg RelayConfig, base string, sessions *sync.WaitGroup) error {
	dctx, cancel := context.WithTimeout(parent, HandshakeTimeout)
	c, _, err := websocket.Dial(dctx, base+"/agent", &websocket.DialOptions{Subprotocols: []string{proto.RelaySubprotocol}})
	cancel()
	if err != nil {
		return fmt.Errorf("dial relay: %w", err)
	}
	defer c.CloseNow()
	ctx, stop := context.WithCancel(parent)
	defer stop()

	var writeMu sync.Mutex
	write := func(m proto.RelayMsg) error {
		b, _ := json.Marshal(m)
		writeMu.Lock()
		defer writeMu.Unlock()
		wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		return c.Write(wctx, websocket.MessageText, b)
	}
	read := func() (proto.RelayMsg, error) {
		var m proto.RelayMsg
		_, b, err := c.Read(ctx)
		if err != nil {
			if websocket.CloseStatus(err) == 4001 {
				return m, errors.New("relay rejected this agent's identity")
			}
			return m, err
		}
		return m, json.Unmarshal(b, &m)
	}

	challenge, err := read()
	if err != nil || challenge.Type != "challenge" {
		return fmt.Errorf("relay handshake: expected challenge (%v)", err)
	}
	nonce, err := base64.StdEncoding.DecodeString(challenge.Nonce)
	if err != nil || len(nonce) < 16 {
		return errors.New("relay handshake: bad nonce")
	}
	sig := ed25519.Sign(cfg.Identity, append([]byte(proto.RelayAuthPrefix), nonce...))
	pub := cfg.Identity.Public().(ed25519.PublicKey)
	if err := write(proto.RelayMsg{
		Type:    "hello",
		AgentID: base64.StdEncoding.EncodeToString(pub),
		Sig:     base64.StdEncoding.EncodeToString(sig),
		Version: cfg.Version,
	}); err != nil {
		return err
	}
	ready, err := read()
	if err != nil {
		return err
	}
	if ready.Type != "ready" {
		return fmt.Errorf("relay handshake: expected ready, got %q", ready.Type)
	}
	status := RelayStatus{Connected: true, ObservedIP: ready.ObservedIP}
	cfg.OnStatus(status)
	cfg.Logger.Info("relay connected", "relay", base, "public_ip", ready.ObservedIP)
	if cfg.ProbePort > 0 {
		if err := write(proto.RelayMsg{Type: "probe", Port: cfg.ProbePort}); err != nil {
			return err
		}
	}

	go func() { // our own keepalive: detects a half-open control channel
		t := time.NewTicker(relayPing)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				pctx, cancel := context.WithTimeout(ctx, relayPing/2)
				err := c.Ping(pctx)
				cancel()
				if err != nil {
					stop()
					return
				}
			}
		}
	}()

	for {
		m, err := read()
		if err != nil {
			return err
		}
		switch m.Type {
		case "incoming":
			sessions.Add(1)
			go func(token string) {
				defer sessions.Done()
				acceptRelayed(parent, cfg, base, token) // outlives this control channel
			}(m.Token)
		case "probe_result":
			status.Reachable = m.Reachable
			cfg.OnStatus(status)
		}
	}
}

// acceptRelayed opens the data channel for one phone session and serves it
// through the same Noise responder as the LAN server.
func acceptRelayed(ctx context.Context, cfg RelayConfig, base, token string) {
	dctx, cancel := context.WithTimeout(ctx, HandshakeTimeout)
	c, _, err := websocket.Dial(dctx, base+"/agent/accept?token="+url.QueryEscape(token),
		&websocket.DialOptions{Subprotocols: []string{proto.Subprotocol}})
	cancel()
	if err != nil {
		cfg.Logger.Info("relay session: dial failed", "err", err)
		return
	}
	c.SetReadLimit(proto.MaxMessage)
	mc := &messageConn{c: c, remote: "relay"}
	nc, peer, err := accept(ctx, mc, cfg.Static, cfg.Authorize)
	if err != nil {
		cfg.Logger.Info("relayed handshake rejected", "err", err)
		_ = mc.Drop()
		return
	}
	cfg.Handler(ctx, nc, peer)
}

// RelayClientURL is the URL a phone dials to reach agentID through the relay.
func RelayClientURL(relay string, agentID ed25519.PublicKey) string {
	return strings.TrimRight(relay, "/") + "/client?agent=" + base64.RawURLEncoding.EncodeToString(agentID)
}
