package transport

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"

	"termbridge/internal/proto"
)

// Path is the LAN WebSocket endpoint (PROTOCOL.md §1.1).
const Path = "/v1"

// Handler serves one authenticated connection; it returns when the peer is done.
type Handler func(ctx context.Context, c Conn, peer Peer)

// ServerConfig configures a LAN server.
type ServerConfig struct {
	Addrs     []string    // host:port pairs; never wildcards by default
	Static    noise.DHKey // the agent's Noise static key
	Authorize Authorizer
	Handler   Handler
	Logger    *slog.Logger
	// AllowPeer vets the remote address before the WebSocket upgrade.
	// Nil means IsLAN.
	AllowPeer func(netip.Addr) bool
}

// Server accepts WebSocket connections from LAN peers and secures each with
// a Noise handshake before handing it to the Handler.
type Server struct {
	cfg ServerConfig
	lns []net.Listener

	mu      sync.Mutex
	closing bool
	conns   sync.WaitGroup // hijacked connections, which http.Server does not track
}

// Listen opens every configured address. Serve must be called to accept.
func Listen(cfg ServerConfig) (*Server, error) {
	switch {
	case len(cfg.Addrs) == 0:
		return nil, errors.New("transport: no listen addresses")
	case len(cfg.Static.Private) == 0 || cfg.Authorize == nil || cfg.Handler == nil:
		return nil, errors.New("transport: static key, authorizer and handler are required")
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if cfg.AllowPeer == nil {
		cfg.AllowPeer = IsLAN
	}
	s := &Server{cfg: cfg}
	for _, addr := range cfg.Addrs {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			s.closeListeners()
			return nil, fmt.Errorf("listen %s: %w", addr, err)
		}
		s.lns = append(s.lns, ln)
	}
	return s, nil
}

// Addrs returns the bound addresses (useful with port 0).
func (s *Server) Addrs() []net.Addr {
	out := make([]net.Addr, len(s.lns))
	for i, ln := range s.lns {
		out[i] = ln.Addr()
	}
	return out
}

// Serve accepts connections until ctx ends, then waits for every handler to
// return so no session outlives the server.
func (s *Server) Serve(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET "+Path, s.serveWS)
	hs := &http.Server{
		Handler:           mux,
		BaseContext:       func(net.Listener) context.Context { return ctx },
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          slog.NewLogLogger(s.cfg.Logger.Handler(), slog.LevelDebug),
	}
	errc := make(chan error, len(s.lns))
	for _, ln := range s.lns {
		go func(ln net.Listener) { errc <- hs.Serve(ln) }(ln)
	}

	var err error
	select {
	case <-ctx.Done():
	case err = <-errc:
	}
	s.mu.Lock()
	s.closing = true
	s.mu.Unlock()
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	_ = hs.Shutdown(shutdownCtx)
	s.conns.Wait()
	if errors.Is(err, http.ErrServerClosed) {
		err = nil
	}
	return err
}

func (s *Server) serveWS(w http.ResponseWriter, r *http.Request) {
	log := s.cfg.Logger.With("remote", r.RemoteAddr)
	peer, err := netip.ParseAddrPort(r.RemoteAddr)
	if err != nil || !s.cfg.AllowPeer(peer.Addr().Unmap()) {
		log.Warn("rejected peer outside the local network")
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		http.Error(w, "shutting down", http.StatusServiceUnavailable)
		return
	}
	s.conns.Add(1)
	s.mu.Unlock()
	defer s.conns.Done()

	// Accept keeps coder/websocket's Origin check: a web page open in the
	// laptop's browser cannot reach the agent.
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{proto.Subprotocol}})
	if err != nil {
		log.Info("websocket upgrade failed", "err", err)
		return
	}
	if c.Subprotocol() != proto.Subprotocol {
		_ = c.Close(websocket.StatusPolicyViolation, "subprotocol "+proto.Subprotocol+" required")
		log.Info("rejected client without subprotocol")
		return
	}
	c.SetReadLimit(proto.MaxMessage)
	mc := &messageConn{c: c, remote: r.RemoteAddr}
	nc, device, err := accept(r.Context(), mc, s.cfg.Static, s.cfg.Authorize)
	if err != nil {
		log.Info("handshake rejected", "err", err)
		_ = mc.Drop()
		return
	}
	s.cfg.Handler(r.Context(), nc, device)
}

func (s *Server) closeListeners() {
	for _, ln := range s.lns {
		_ = ln.Close()
	}
}
