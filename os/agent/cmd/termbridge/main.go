// Command termbridge is the TermBridge agent: it gives paired phones a shell
// on this machine. See the repository README and docs/PROTOCOL.md.
package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	qrcode "github.com/skip2/go-qrcode"
	"github.com/spf13/cobra"

	"termbridge/agent/internal/agent"
	"termbridge/agent/internal/pairing"
	"termbridge/agent/internal/session"
	"termbridge/agent/internal/statusline"
	"termbridge/agent/internal/store"
	"termbridge/agent/internal/transport"
	"termbridge/internal/identity"
	"termbridge/internal/proto"
)

// version is overridden at build time: -ldflags "-X main.version=…".
var version = "0.1.0-dev"

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := newRootCmd().ExecuteContext(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "termbridge:", err)
		os.Exit(1)
	}
}

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "termbridge",
		Short:         "Reach this machine's terminal from your phone",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.AddCommand(
		newServeCmd("pair", "Show a QR code, pair a phone, then keep serving", true),
		newServeCmd("run", "Serve paired phones", false),
		newStatusCmd(), newRevokeCmd(), newResetCmd(), newVersionCmd(),
	)
	return root
}

type serveFlags struct {
	port        int
	listen      []string
	shell       string
	maxSessions int
	allowRoot   bool
	verbose     bool
	qrPNG       string
}

func newServeCmd(use, short string, pair bool) *cobra.Command {
	var f serveFlags
	cmd := &cobra.Command{
		Use:   use,
		Short: short,
		Args:  cobra.NoArgs,
		RunE:  func(cmd *cobra.Command, _ []string) error { return serve(cmd.Context(), f, pair) },
	}
	fl := cmd.Flags()
	fl.IntVar(&f.port, "port", transport.DefaultPort, "LAN port")
	fl.StringSliceVar(&f.listen, "listen", nil, "explicit listen addresses (host:port); default: every loopback and private address")
	fl.StringVar(&f.shell, "shell", "", "shell to start (default: $SHELL, else /bin/bash; powershell.exe on Windows)")
	fl.IntVar(&f.maxSessions, "max-sessions", session.DefaultMaxSessions, "maximum concurrent shell sessions")
	fl.BoolVar(&f.allowRoot, "allow-root", false, "allow running as root (dangerous: every paired phone gets a root shell)")
	fl.BoolVarP(&f.verbose, "verbose", "v", false, "debug logging")
	if pair {
		fl.StringVar(&f.qrPNG, "qr-png", "", "also write the QR code as a PNG image to this path (e.g. to show it on another screen)")
	}
	return cmd
}

// cliEvents shows agent events on the status line and reports pairings.
type cliEvents struct {
	*statusline.Line
	paired chan store.Device
}

func (e *cliEvents) Paired(d store.Device) {
	select {
	case e.paired <- d:
	default:
	}
}

func serve(ctx context.Context, f serveFlags, pair bool) error {
	if os.Geteuid() == 0 && !f.allowRoot { // Geteuid is -1 on Windows
		return errors.New("refusing to run as root: every paired phone would get a root shell (override with --allow-root)")
	}
	st, err := openStore()
	if err != nil {
		return err
	}
	status := statusline.New(os.Stderr)
	defer status.Close()
	level := slog.LevelInfo
	if f.verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(status, &slog.HandlerOptions{Level: level}))
	events := &cliEvents{Line: status, paired: make(chan store.Device, 1)}
	a, err := agent.New(agent.Config{
		Store: st, Listen: f.listen, Port: f.port, Shell: f.shell, MaxSessions: f.maxSessions,
		Version: version, Logger: log, Events: events,
	})
	if err != nil {
		return err
	}
	if devices, _ := a.Devices(); len(devices) == 0 && !pair {
		log.Warn("no phone is paired yet; run `termbridge pair` to add one")
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- a.Run(ctx) }()
	if !pair {
		return <-done
	}

	payload, window, err := a.StartPairing()
	if err != nil {
		cancel()
		<-done
		return err
	}
	if err := printPairing(status, a, payload, f.qrPNG); err != nil {
		cancel()
		<-done
		return err
	}
	expired := time.NewTimer(time.Until(window.Expires()))
	defer expired.Stop()
	select {
	case d := <-events.paired:
		a.StopPairing()
		fmt.Fprintf(status, "\n  \x1b[1;32m✓ Paired %s.\x1b[0m The agent keeps running; press Ctrl+C to stop.\n    Next time just run: termbridge run\n\n", d.Name)
		return <-done
	case <-expired.C:
		cancel()
		<-done
		return errors.New("pairing code expired; run `termbridge pair` again")
	case err := <-done:
		return err
	}
}

func printPairing(w io.Writer, a *agent.Agent, p pairing.Payload, pngPath string) error {
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	qr, err := pairing.RenderQR(string(data))
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "\n  \x1b[1mScan with the TermBridge app\x1b[0m  (Scan QR code)\n\n%s\n", indent(qr, "  "))
	fmt.Fprintf(w, "  Machine      %s\n  Fingerprint  %s\n  Addresses    %s\n  Expires in   %s — the code works once\n",
		p.Name, a.Fingerprint(), strings.Join(p.LAN, ", "), pairing.Lifetime)
	if pngPath != "" {
		if err := qrcode.WriteFile(string(data), qrcode.Medium, 640, pngPath); err != nil {
			return fmt.Errorf("write QR image: %w", err)
		}
		fmt.Fprintf(w, "  QR image     %s\n", pngPath)
	}
	// Same as the desktop app's "Copy code": for the app's "Paste pairing code".
	fmt.Fprintf(w, "  Paste code   \x1b[2m%s\x1b[0m\n\n", data)
	return nil
}

func indent(s, prefix string) string {
	lines := strings.Split(strings.TrimSuffix(s, "\n"), "\n")
	for i := range lines {
		lines[i] = prefix + lines[i]
	}
	return strings.Join(lines, "\n") + "\n"
}

func newStatusCmd() *cobra.Command {
	var port int
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show this agent's identity, whether it is running, and paired phones",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			st, err := openStore()
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			if !st.HasIdentity() {
				fmt.Fprintf(out, "Not set up yet. Run `termbridge pair` to pair your first phone.\n")
				return nil
			}
			priv, err := st.Identity()
			if err != nil {
				return err
			}
			running := "not running"
			if c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 300*time.Millisecond); err == nil {
				c.Close()
				running = "running on port " + strconv.Itoa(port)
			}
			fmt.Fprintf(out, "Agent        %s\nFingerprint  %s\nConfig       %s\n\n", running, identity.Fingerprint(priv.Public().(ed25519.PublicKey)), st.Dir())
			devices, err := st.Devices()
			if err != nil {
				return err
			}
			if len(devices) == 0 {
				fmt.Fprintln(out, "No paired phones. Run `termbridge pair`.")
				return nil
			}
			tw := tabwriter.NewWriter(out, 0, 4, 2, ' ', 0)
			fmt.Fprintln(tw, "PHONE\tPAIRED\tLAST SEEN\tKEY")
			for _, d := range devices {
				fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", d.Name, d.PairedAt.Local().Format("2006-01-02"), ago(d.LastSeen), identity.Fingerprint(d.Key()))
			}
			return tw.Flush()
		},
	}
	cmd.Flags().IntVar(&port, "port", transport.DefaultPort, "LAN port to probe")
	return cmd
}

func newRevokeCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "revoke <name>",
		Short: "Unpair a phone; a running agent disconnects it within a second",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			st, err := openStore()
			if err != nil {
				return err
			}
			d, err := st.RemoveDevice(args[0])
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Revoked %s. It can no longer connect; a running agent drops it now.\n", d.Name)
			return nil
		},
	}
}

func newResetCmd() *cobra.Command {
	var yes bool
	cmd := &cobra.Command{
		Use:   "reset",
		Short: "Delete this agent's identity and every pairing",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			st, err := openStore()
			if err != nil {
				return err
			}
			devices, _ := st.Devices()
			if !yes {
				fmt.Fprintf(cmd.OutOrStdout(), "This deletes the agent identity and %d paired phone(s); every phone must pair again.\nType 'reset' to confirm: ", len(devices))
				line, _ := bufio.NewReader(cmd.InOrStdin()).ReadString('\n')
				if strings.TrimSpace(line) != "reset" {
					return errors.New("aborted")
				}
			}
			if err := st.Reset(); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "Reset done. Run `termbridge pair` to start over.")
			return nil
		},
	}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "do not ask for confirmation")
	return cmd
}

func newVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the agent version",
		Run: func(cmd *cobra.Command, _ []string) {
			fmt.Fprintf(cmd.OutOrStdout(), "termbridge %s (protocol v%d, %s/%s)\n", version, proto.Version, runtime.GOOS, runtime.GOARCH)
		},
	}
}

func openStore() (*store.Store, error) {
	dir, err := store.DefaultDir()
	if err != nil {
		return nil, err
	}
	return store.Open(dir)
}

func ago(t time.Time) string {
	if t.IsZero() {
		return "never"
	}
	switch d := time.Since(t); {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%d h ago", int(d.Hours()))
	default:
		return t.Local().Format("2006-01-02")
	}
}
