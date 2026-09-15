// Command termbridge-relay connects phones to agents that have no direct
// path to each other (PROTOCOL.md §9). It only copies Noise ciphertext and
// cannot read the traffic it relays. Run it behind Caddy for TLS; see
// relay/deploy/.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"termbridge/relay/hub"
	"termbridge/relay/ws"
)

var version = "0.1.0-dev"

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "address to listen on (put Caddy or another TLS proxy in front)")
	trustProxy := flag.Bool("trust-proxy", false, "take client IPs from X-Forwarded-For (only behind a local reverse proxy)")
	maxSessions := flag.Int("max-sessions", 5, "concurrent sessions per agent")
	rateMbit := flag.Int("rate-mbit", 10, "relayed bandwidth per agent, Mbit/s")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println("termbridge-relay", version)
		return
	}

	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	srv := &ws.Server{
		Hub:        hub.New(hub.Config{MaxSessions: *maxSessions, BytesPerSecond: *rateMbit * 1_000_000 / 8}),
		Logger:     log,
		TrustProxy: *trustProxy,
	}
	hs := &http.Server{Addr: *listen, Handler: srv.Handler(), ReadHeaderTimeout: 10 * time.Second}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = hs.Shutdown(sctx)
	}()
	log.Info("relay listening", "addr", *listen, "version", version)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("relay stopped", "err", err)
		os.Exit(1)
	}
}
