//go:build desktop

// Command termbridge-desktop is the TermBridge agent with a window: start it,
// scan the QR code with the phone, done. It runs the same agent core as the
// `termbridge` CLI. Build with `make desktop`.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"

	"termbridge/agent/internal/agent"
	"termbridge/agent/internal/store"
)

// version is overridden at build time: -ldflags "-X main.version=…".
var version = "0.1.0-dev"

const appID = "io.termbridge.desktop"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "--install-launcher" {
		if err := installLauncher(); err != nil {
			fmt.Fprintln(os.Stderr, "termbridge-desktop:", err)
			os.Exit(1)
		}
		return
	}
	a := app.NewWithID(appID)
	a.Settings().SetTheme(newTheme())
	a.SetIcon(iconIdle)
	if err := run(a); err != nil {
		w := a.NewWindow("TermBridge")
		w.SetContent(container.NewPadded(widget.NewLabel("TermBridge could not start:\n\n" + err.Error())))
		w.ShowAndRun()
	}
}

func run(a fyne.App) error {
	if os.Geteuid() == 0 { // -1 on Windows
		return errors.New("refusing to run as root: every paired phone would get a root shell")
	}
	dir, err := store.DefaultDir()
	if err != nil {
		return err
	}
	st, err := store.Open(dir)
	if err != nil {
		return err
	}
	u := newUI(a)
	ag, err := agent.New(agent.Config{
		Store:   st,
		Version: version,
		Logger:  slog.New(slog.NewTextHandler(os.Stderr, nil)),
		Events:  u,
	})
	if err != nil {
		return err
	}
	u.bind(ag)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		if err := ag.Run(ctx); err != nil && ctx.Err() == nil {
			fyne.Do(func() { u.fatal(err) })
		}
	}()
	u.w.ShowAndRun()
	cancel() // hang up every session before the process exits
	<-done
	return nil
}

// installLauncher adds TermBridge to the desktop's application menu (Linux,
// XDG) pointing at this executable.
func installLauncher() error {
	if runtime.GOOS != "linux" {
		return errors.New("--install-launcher is only needed on Linux")
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	exe, _ = filepath.EvalSymlinks(exe)
	data := os.Getenv("XDG_DATA_HOME")
	if data == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return err
		}
		data = filepath.Join(home, ".local", "share")
	}
	iconPath := filepath.Join(data, "icons", "hicolor", "256x256", "apps", appID+".png")
	entryPath := filepath.Join(data, "applications", appID+".desktop")
	for _, d := range []string{filepath.Dir(iconPath), filepath.Dir(entryPath)} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	if err := os.WriteFile(iconPath, iconIdle.Content(), 0o644); err != nil {
		return err
	}
	entry := fmt.Sprintf(`[Desktop Entry]
Type=Application
Name=TermBridge
Comment=Reach this computer's terminal from your phone
Exec=%q
Icon=%s
Terminal=false
Categories=Utility;Network;RemoteAccess;
Keywords=terminal;shell;remote;phone;qr;
`, exe, appID)
	if err := os.WriteFile(entryPath, []byte(entry), 0o644); err != nil {
		return err
	}
	fmt.Printf("Installed %s\nTermBridge now appears in your applications menu.\n", entryPath)
	return nil
}
