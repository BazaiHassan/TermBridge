//go:build desktop

package main

import (
	"errors"
	"net/url"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"termbridge/agent/internal/pty"
)

// showSettings edits config.json: the relay and the shell. The agent reads
// both when it starts, so changes apply at the next start.
func (u *ui) showSettings() {
	cfg, err := u.st.Config()
	if err != nil {
		dialog.ShowError(err, u.w)
		return
	}
	relay := widget.NewEntry()
	relay.SetPlaceHolder("wss://relay.example.com")
	relay.SetText(cfg.Relay)
	relay.Validator = validRelay
	def, _ := pty.DefaultShell()
	shell := widget.NewEntry()
	shell.SetPlaceHolder(def)
	shell.SetText(cfg.Shell)
	items := []*widget.FormItem{
		{Text: "Relay", Widget: relay, HintText: "For phones on other networks. Empty: direct and LAN only"},
		{Text: "Shell", Widget: shell, HintText: "Empty: " + def},
	}
	d := dialog.NewForm("Settings", "Save", "Cancel", items, func(ok bool) {
		if !ok {
			return
		}
		cfg.Relay = strings.TrimRight(strings.TrimSpace(relay.Text), "/")
		cfg.Shell = strings.TrimSpace(shell.Text)
		if err := u.st.SaveConfig(cfg); err != nil {
			dialog.ShowError(err, u.w)
			return
		}
		dialog.ShowInformation("Saved", "The new settings apply the next time TermBridge starts.", u.w)
	}, u.w)
	d.Resize(fyne.NewSize(460, 300))
	d.Show()
}

// validRelay accepts an empty field (no relay) or a ws:// or wss:// URL.
func validRelay(s string) error {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "wss" && u.Scheme != "ws") || u.Host == "" {
		return errors.New("use wss://host (ws:// only for testing)")
	}
	return nil
}
