// Package ws exposes the relay's WebSocket endpoints (PROTOCOL.md §9).
package ws

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"termbridge/internal/proto"
	"termbridge/relay/hub"
)

// Protocol constants (PROTOCOL.md §9).
const (
	closeAuthFailed  = websocket.StatusCode(4001)
	closeOffline     = websocket.StatusCode(4004)
	closeNoAnswer    = websocket.StatusCode(4008)
	closeTooMany     = websocket.StatusCode(4029)
	pingInterval     = 30 * time.Second
	handshakeTimeout = 10 * time.Second
	probeTimeout     = 3 * time.Second
)

// Server is the relay's HTTP handler.
type Server struct {
	Hub        *hub.Hub
	Logger     *slog.Logger
	TrustProxy bool // take the client IP from X-Forwarded-For (behind Caddy)
}

// Handler routes the relay endpoints.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /agent", s.serveAgent)
	mux.HandleFunc("GET /agent/accept", s.serveAccept)
	mux.HandleFunc("GET /client", s.serveClient)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	return mux
}

// agentKey normalizes an agent ID given as base64 or base64url.
func agentKey(id string) (string, ed25519.PublicKey, error) {
	id = strings.TrimRight(id, "=")
	b, err := base64.RawURLEncoding.DecodeString(strings.NewReplacer("+", "-", "/", "_").Replace(id))
	if err != nil || len(b) != ed25519.PublicKeySize {
		return "", nil, errors.New("invalid agent id")
	}
	return base64.StdEncoding.EncodeToString(b), b, nil
}

func fingerprint(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:4])
}

func (s *Server) clientIP(r *http.Request) string {
	if s.TrustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			return strings.TrimSpace(strings.Split(xff, ",")[0])
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// serveAgent authenticates an agent and keeps its control channel.
func (s *Server) serveAgent(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{proto.RelaySubprotocol}})
	if err != nil {
		return
	}
	if c.Subprotocol() != proto.RelaySubprotocol {
		_ = c.Close(websocket.StatusPolicyViolation, "subprotocol "+proto.RelaySubprotocol+" required")
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	ip := s.clientIP(r)

	id, err := s.authenticate(ctx, c)
	if err != nil {
		s.Logger.Info("agent authentication failed", "ip", ip, "err", err)
		_ = c.Close(closeAuthFailed, "authentication failed")
		return
	}
	log := s.Logger.With("agent", fingerprint(id), "ip", ip)
	var writeMu sync.Mutex
	send := func(ctx context.Context, m proto.RelayMsg) error {
		b, _ := json.Marshal(m)
		writeMu.Lock()
		defer writeMu.Unlock()
		return c.Write(ctx, websocket.MessageText, b)
	}
	_, unregister := s.Hub.Register(id, func(ctx context.Context, token string) error {
		return send(ctx, proto.RelayMsg{Type: "incoming", Token: token})
	}, cancel)
	defer unregister()
	if err := send(ctx, proto.RelayMsg{Type: "ready", ObservedIP: ip}); err != nil {
		return
	}
	log.Info("agent online")
	defer log.Info("agent offline")

	go func() {
		t := time.NewTicker(pingInterval)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				pctx, pcancel := context.WithTimeout(ctx, pingInterval/2)
				err := c.Ping(pctx)
				pcancel()
				if err != nil {
					cancel()
					return
				}
			}
		}
	}()
	for {
		_, b, err := c.Read(ctx)
		if err != nil {
			_ = c.Close(websocket.StatusNormalClosure, "")
			return
		}
		var m proto.RelayMsg
		if json.Unmarshal(b, &m) != nil {
			continue
		}
		if m.Type == "probe" && m.Port > 0 && m.Port < 65536 {
			go func(port int) {
				reachable := probe(ctx, ip, port)
				_ = send(ctx, proto.RelayMsg{Type: "probe_result", Port: port, Reachable: reachable})
			}(m.Port)
		}
	}
}

func (s *Server) authenticate(ctx context.Context, c *websocket.Conn) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, handshakeTimeout)
	defer cancel()
	nonce := make([]byte, 32)
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	b, _ := json.Marshal(proto.RelayMsg{Type: "challenge", Nonce: base64.StdEncoding.EncodeToString(nonce)})
	if err := c.Write(ctx, websocket.MessageText, b); err != nil {
		return "", err
	}
	_, b, err := c.Read(ctx)
	if err != nil {
		return "", err
	}
	var hello proto.RelayMsg
	if err := json.Unmarshal(b, &hello); err != nil || hello.Type != "hello" {
		return "", errors.New("expected hello")
	}
	id, pub, err := agentKey(hello.AgentID)
	if err != nil {
		return "", err
	}
	sig, err := base64.StdEncoding.DecodeString(hello.Sig)
	if err != nil || !ed25519.Verify(pub, append([]byte(proto.RelayAuthPrefix), nonce...), sig) {
		return "", errors.New("bad signature")
	}
	return id, nil
}

// probe reports whether ip:port accepts TCP connections from the internet.
func probe(ctx context.Context, ip string, port int) bool {
	d := net.Dialer{Timeout: probeTimeout}
	c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
	if err != nil {
		return false
	}
	_ = c.Close()
	return true
}

// serveAccept receives an agent's data channel and keeps it open until the
// bridge ends.
func (s *Server) serveAccept(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{proto.Subprotocol}})
	if err != nil {
		return
	}
	c.SetReadLimit(proto.MaxMessage)
	done, ok := s.Hub.Accept(r.URL.Query().Get("token"), c)
	if !ok {
		_ = c.Close(websocket.StatusPolicyViolation, "unknown or expired token")
		return
	}
	<-done
}

// serveClient bridges a phone to its agent.
func (s *Server) serveClient(w http.ResponseWriter, r *http.Request) {
	id, _, err := agentKey(r.URL.Query().Get("agent"))
	if err != nil {
		http.Error(w, "invalid agent id", http.StatusBadRequest)
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{proto.Subprotocol}})
	if err != nil {
		return
	}
	if c.Subprotocol() != proto.Subprotocol {
		_ = c.Close(websocket.StatusPolicyViolation, "subprotocol "+proto.Subprotocol+" required")
		return
	}
	c.SetReadLimit(proto.MaxMessage)
	ctx := r.Context()
	log := s.Logger.With("agent", fingerprint(id), "ip", s.clientIP(r))

	p, err := s.Hub.Open(ctx, id)
	switch {
	case errors.Is(err, hub.ErrTooManySessions):
		_ = c.Close(closeTooMany, "too many sessions")
		return
	case err != nil:
		_ = c.Close(closeOffline, "agent offline")
		return
	}
	defer p.Close()
	agentConn, release, err := p.Wait(ctx)
	if err != nil {
		_ = c.Close(closeNoAnswer, "agent did not answer")
		return
	}
	defer release()

	start := time.Now()
	up, down := bridge(ctx, c, agentConn, p.Agent().Limiter())
	log.Info("session ended", "bytes_up", up, "bytes_down", down, "duration", time.Since(start).Round(time.Second))
}

// bridge copies messages 1:1 in both directions until either side ends,
// preserving message boundaries. It returns the bytes relayed each way.
func bridge(ctx context.Context, phone, agent *websocket.Conn, lim *hub.Limiter) (up, down int64) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var upN, downN atomic.Int64
	var wg sync.WaitGroup
	wg.Add(2)
	pump := func(dst, src *websocket.Conn, n *atomic.Int64) {
		defer wg.Done()
		defer cancel()
		for {
			typ, rd, err := src.Reader(ctx)
			if err != nil {
				return
			}
			wr, err := dst.Writer(ctx, typ)
			if err != nil {
				return
			}
			if _, err := io.Copy(wr, &limitedReader{ctx: ctx, r: rd, lim: lim, n: n}); err != nil {
				return
			}
			if err := wr.Close(); err != nil {
				return
			}
		}
	}
	go pump(agent, phone, &upN)
	go pump(phone, agent, &downN)
	wg.Wait()
	_ = phone.Close(websocket.StatusNormalClosure, "")
	_ = agent.Close(websocket.StatusNormalClosure, "")
	return upN.Load(), downN.Load()
}

type limitedReader struct {
	ctx context.Context
	r   io.Reader
	lim *hub.Limiter
	n   *atomic.Int64
}

func (l *limitedReader) Read(p []byte) (int, error) {
	if len(p) > 16<<10 {
		p = p[:16<<10]
	}
	n, err := l.r.Read(p)
	if n > 0 {
		l.n.Add(int64(n))
		if werr := l.lim.Wait(l.ctx, n); werr != nil {
			return n, fmt.Errorf("rate limit: %w", werr)
		}
	}
	return n, err
}
