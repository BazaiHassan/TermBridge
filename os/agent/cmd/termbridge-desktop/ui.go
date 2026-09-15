//go:build desktop

package main

import (
	"encoding/json"
	"fmt"
	"image/color"
	"slices"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/driver/desktop"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
	qrcode "github.com/skip2/go-qrcode"

	"termbridge/agent/internal/agent"
	"termbridge/agent/internal/pairing"
	"termbridge/agent/internal/store"
	"termbridge/agent/internal/transport"
)

// ui is the desktop window. It implements agent.Events; those callbacks come
// from agent goroutines and hop onto the UI thread with fyne.Do.
type ui struct {
	a  fyne.App
	w  fyne.Window
	ag *agent.Agent

	mu    sync.Mutex
	peers map[string]int  // phone → open shells (attached or detached)
	away  map[string]bool // phones whose shells run detached

	// Containers whose children change size or visibility; Fyne only lays
	// out again when the parent is refreshed.
	header   *fyne.Container
	pairArea *fyne.Container

	status   *canvas.Text
	statusBg *canvas.Rectangle
	qr       *canvas.Image
	timer    *canvas.Text
	pairCard *fyne.Container
	pairBtn  *widget.Button
	notice   *canvas.Text
	list     *widget.List
	empty    *canvas.Text
	footer   *canvas.Text

	listening, relay string // footer parts

	devices []store.Device
	window  *pairing.Window // open pairing window, nil when not pairing
	code    string          // pairing JSON, for "Copy code"
	stop    chan struct{}   // stops the countdown ticker
}

var _ agent.Events = (*ui)(nil)

func newUI(a fyne.App) *ui {
	u := &ui{a: a, w: a.NewWindow("TermBridge"), peers: make(map[string]int), away: make(map[string]bool)}
	u.w.SetIcon(iconIdle)
	u.w.Resize(fyne.NewSize(440, 720))
	return u
}

func (u *ui) bind(ag *agent.Agent) {
	u.ag = ag
	u.w.SetContent(u.layout())
	u.reloadDevices()
	u.setupTray()
	u.w.SetCloseIntercept(u.confirmQuit)
	if len(u.devices) == 0 {
		u.startPairing() // first launch: straight to the QR code
	}
}

func text(s string, c color.Color, size float32, bold bool) *canvas.Text {
	t := canvas.NewText(s, c)
	t.TextSize = size
	t.TextStyle = fyne.TextStyle{Monospace: true, Bold: bold}
	return t
}

func (u *ui) layout() fyne.CanvasObject {
	word := widget.NewRichText(
		&widget.TextSegment{Text: "termbridge", Style: widget.RichTextStyle{Inline: true, ColorName: theme.ColorNameForeground, SizeName: theme.SizeNameHeadingText, TextStyle: fyne.TextStyle{Monospace: true, Bold: true}}},
		&widget.TextSegment{Text: "_", Style: widget.RichTextStyle{Inline: true, ColorName: theme.ColorNamePrimary, SizeName: theme.SizeNameHeadingText, TextStyle: fyne.TextStyle{Monospace: true, Bold: true}}},
	)
	u.status = text("○ waiting", colMuted, 12, true)
	u.statusBg = canvas.NewRectangle(colSurfaceHigh)
	u.statusBg.CornerRadius = 12
	pill := container.NewStack(u.statusBg, container.New(layout.NewCustomPaddedLayout(4, 4, 10, 10), u.status))
	u.header = container.NewBorder(nil, nil, nil, container.NewCenter(pill), word)
	identity := text(u.ag.Hostname()+"  ·  "+u.ag.Fingerprint(), colMuted, 12, false)

	// Pairing card: the QR on white (scanners want contrast), countdown, actions.
	u.qr = canvas.NewImageFromImage(nil)
	u.qr.FillMode = canvas.ImageFillContain
	u.qr.ScaleMode = canvas.ImageScalePixels
	u.qr.SetMinSize(fyne.NewSize(290, 290))
	paper := canvas.NewRectangle(color.White)
	paper.CornerRadius = 18
	qrBox := container.NewCenter(container.NewStack(paper, container.New(layout.NewCustomPaddedLayout(10, 10, 10, 10), u.qr)))
	scan := text("Scan with the TermBridge app", colText, 15, true)
	scan.Alignment = fyne.TextAlignCenter
	u.timer = text("", colMuted, 12, false)
	u.timer.Alignment = fyne.TextAlignCenter
	copyBtn := widget.NewButtonWithIcon("Copy code", theme.ContentCopyIcon(), func() {
		u.a.Clipboard().SetContent(u.code)
	})
	doneBtn := widget.NewButton("Done", u.stopPairing)
	u.pairCard = container.NewVBox(
		qrBox, scan, u.timer,
		container.NewCenter(container.NewHBox(copyBtn, doneBtn)),
	)
	u.pairBtn = widget.NewButtonWithIcon("Pair a phone", theme.ContentAddIcon(), u.startPairing)
	u.pairBtn.Importance = widget.HighImportance
	u.notice = text("", colMint, 13, true)
	u.notice.Alignment = fyne.TextAlignCenter
	u.notice.Hide()
	u.pairArea = container.NewVBox(u.pairCard, container.NewCenter(u.pairBtn), u.notice)
	u.pairCard.Hide()

	// Paired phones.
	u.list = widget.NewList(
		func() int { return len(u.devices) },
		func() fyne.CanvasObject {
			name := text("", colText, 14, true)
			sub := text("", colMuted, 11, false)
			revoke := widget.NewButtonWithIcon("Revoke", theme.DeleteIcon(), nil)
			revoke.Importance = widget.LowImportance
			return container.NewBorder(nil, nil, nil, container.NewCenter(revoke), container.NewVBox(name, sub))
		},
		func(id widget.ListItemID, o fyne.CanvasObject) {
			if id >= len(u.devices) {
				return
			}
			d := u.devices[id]
			row := o.(*fyne.Container)
			info := row.Objects[0].(*fyne.Container)
			info.Objects[0].(*canvas.Text).Text = d.Name
			info.Objects[1].(*canvas.Text).Text = "paired " + d.PairedAt.Local().Format("Jan 2") + "  ·  last seen " + ago(d.LastSeen)
			info.Refresh()
			row.Objects[1].(*fyne.Container).Objects[0].(*widget.Button).OnTapped = func() { u.confirmRevoke(d) }
		},
	)
	u.empty = text("No phones paired yet", colMuted, 12, false)
	u.empty.Alignment = fyne.TextAlignCenter
	devicesTitle := text("PAIRED PHONES", colMuted, 11, true)
	u.footer = text("starting…", colMuted, 11, false)

	top := container.NewVBox(u.header, identity, widget.NewSeparator(), u.pairArea, widget.NewSeparator(), devicesTitle)
	body := container.NewBorder(top, u.footer, nil, nil, container.NewStack(u.list, container.NewCenter(u.empty)))
	return container.New(layout.NewCustomPaddedLayout(18, 14, 20, 20), body)
}

// ---- Pairing ------------------------------------------------------------------------

func (u *ui) startPairing() {
	payload, win, err := u.ag.StartPairing()
	if err != nil {
		dialog.ShowError(err, u.w)
		return
	}
	data, _ := json.Marshal(payload)
	q, err := qrcode.New(string(data), qrcode.Medium)
	if err != nil {
		dialog.ShowError(err, u.w)
		return
	}
	q.ForegroundColor = colInk
	u.qr.Image = q.Image(580)
	u.qr.Refresh()
	u.code, u.window = string(data), win
	u.notice.Hide()
	u.pairBtn.Hide()
	u.pairCard.Show()
	u.relayout()
	u.tick(win)
	u.startCountdown(win)
}

func (u *ui) stopPairing() {
	u.ag.StopPairing()
	u.window = nil
	u.stopCountdown()
	u.pairCard.Hide()
	u.pairBtn.Show()
	u.relayout()
}

// relayout lays the whole window out again after the pairing panel changed
// size; refreshing only the panel would let it overlap its neighbours.
func (u *ui) relayout() { u.w.Content().Refresh() }

func (u *ui) startCountdown(win *pairing.Window) {
	u.stopCountdown()
	stop := make(chan struct{})
	u.stop = stop
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				fyne.Do(func() { u.tick(win) })
			}
		}
	}()
}

func (u *ui) stopCountdown() {
	if u.stop != nil {
		close(u.stop)
		u.stop = nil
	}
}

// tick updates the countdown and swaps in a fresh code when one expires, so
// the QR on screen is always scannable.
func (u *ui) tick(win *pairing.Window) {
	if u.window != win {
		return
	}
	left := time.Until(win.Expires()).Round(time.Second)
	if left <= 0 || !win.Active() {
		u.startPairing()
		return
	}
	u.timer.Text = fmt.Sprintf("New code in %d:%02d  ·  each code works once", int(left.Minutes()), int(left.Seconds())%60)
	u.timer.Refresh()
}

// ---- Devices --------------------------------------------------------------------------

func (u *ui) reloadDevices() {
	devices, err := u.ag.Devices()
	if err != nil {
		dialog.ShowError(err, u.w)
		return
	}
	slices.SortFunc(devices, func(a, b store.Device) int { return b.LastSeen.Compare(a.LastSeen) })
	u.devices = devices
	u.list.Refresh()
	if len(devices) == 0 {
		u.empty.Show()
	} else {
		u.empty.Hide()
	}
}

func (u *ui) confirmRevoke(d store.Device) {
	dialog.ShowConfirm("Revoke "+d.Name+"?",
		"The phone is disconnected now and can no longer connect.\nIt can pair again later with a new QR code.",
		func(ok bool) {
			if !ok {
				return
			}
			if _, err := u.ag.Revoke(d.Name); err != nil {
				dialog.ShowError(err, u.w)
			}
			u.reloadDevices()
		}, u.w)
}

// ---- Status, tray, quit ---------------------------------------------------------------

func (u *ui) refreshStatus() {
	u.mu.Lock()
	shells, names := 0, make([]string, 0, len(u.peers))
	for p, n := range u.peers {
		shells += n
		names = append(names, p)
	}
	u.mu.Unlock()
	slices.Sort(names)
	switch {
	case shells > 0:
		u.status.Text, u.status.Color = fmt.Sprintf("● LIVE · %d shell%s", shells, plural(shells)), colInk
		u.statusBg.FillColor = colCoral
	case len(names) > 0:
		u.status.Text, u.status.Color = "● "+strings.Join(names, ", "), colInk
		u.statusBg.FillColor = colAmber
	default:
		u.status.Text, u.status.Color = "○ waiting", colMuted
		u.statusBg.FillColor = colSurfaceHigh
	}
	u.status.Refresh()
	u.statusBg.Refresh()
	u.header.Refresh() // the pill's width follows its text
	if desk, ok := u.a.(desktop.App); ok {
		if shells > 0 {
			desk.SetSystemTrayIcon(iconLive)
		} else {
			desk.SetSystemTrayIcon(iconIdle)
		}
	}
}

func (u *ui) setupTray() {
	desk, ok := u.a.(desktop.App)
	if !ok {
		return
	}
	show := func() {
		u.w.Show()
		u.w.RequestFocus()
	}
	desk.SetSystemTrayMenu(fyne.NewMenu("TermBridge",
		fyne.NewMenuItem("Open TermBridge", show),
		fyne.NewMenuItem("Pair a phone", func() {
			show()
			u.startPairing()
		}),
	))
	desk.SetSystemTrayIcon(iconIdle)
}

// confirmQuit: closing the window stops the agent (not every desktop has a
// tray to hide into), so warn when shells would be cut off.
func (u *ui) confirmQuit() {
	u.mu.Lock()
	shells := 0
	for _, n := range u.peers {
		shells += n
	}
	u.mu.Unlock()
	if shells == 0 {
		u.a.Quit()
		return
	}
	dialog.ShowConfirm("Quit TermBridge?",
		fmt.Sprintf("%d open shell%s will be closed and phones can't connect until you start it again.", shells, plural(shells)),
		func(ok bool) {
			if ok {
				u.a.Quit()
			}
		}, u.w)
}

func (u *ui) fatal(err error) {
	u.stopPairing()
	u.pairBtn.Disable()
	u.footer.Text = "not running"
	u.footer.Refresh()
	dialog.ShowError(fmt.Errorf("TermBridge can't accept phones: %w\n\nIf `termbridge run` is already running in a terminal, stop it and start this app again", err), u.w)
}

// ---- agent.Events (agent goroutines) --------------------------------------------------

func (u *ui) PeerConnected(peer string) {
	u.mu.Lock()
	if _, ok := u.peers[peer]; !ok {
		u.peers[peer] = 0
	}
	delete(u.away, peer)
	u.mu.Unlock()
	fyne.Do(func() {
		u.refreshStatus()
		u.reloadDevices()
	})
}

func (u *ui) PeerDisconnected(peer string) {
	u.mu.Lock()
	if u.peers[peer] == 0 {
		delete(u.peers, peer)
	} else {
		u.away[peer] = true // its shells keep running until the resume window ends
	}
	u.mu.Unlock()
	fyne.Do(u.refreshStatus)
}

func (u *ui) SessionOpened(peer string, _ uint8) {
	u.mu.Lock()
	if _, ok := u.peers[peer]; ok {
		u.peers[peer]++
	}
	u.mu.Unlock()
	fyne.Do(u.refreshStatus)
}

func (u *ui) SessionClosed(peer string, _ uint8) {
	u.mu.Lock()
	if u.peers[peer] > 0 {
		u.peers[peer]--
	}
	if u.peers[peer] == 0 && u.away[peer] {
		delete(u.peers, peer)
		delete(u.away, peer)
	}
	u.mu.Unlock()
	fyne.Do(u.refreshStatus)
}

func (u *ui) Listening(addrs []string) {
	fyne.Do(func() {
		u.listening = "Listening on " + strings.Join(phoneAddrs(addrs), ", ")
		u.refreshFooter()
	})
}

func (u *ui) RelayChanged(s transport.RelayStatus) {
	fyne.Do(func() {
		switch {
		case s.Connected && s.Reachable:
			u.relay = "relay connected · reachable directly at " + s.ObservedIP
		case s.Connected:
			u.relay = "relay connected"
		default:
			u.relay = "relay unreachable, retrying"
		}
		u.refreshFooter()
	})
}

func (u *ui) refreshFooter() {
	u.footer.Text = u.listening
	if u.relay != "" {
		u.footer.Text += "  ·  " + u.relay
	}
	u.footer.Refresh()
}

func (u *ui) Paired(d store.Device) {
	fyne.Do(func() {
		u.stopPairing()
		u.notice.Text = "✓ Paired " + d.Name + " — you're all set"
		u.notice.Refresh()
		u.notice.Show()
		u.relayout()
		u.reloadDevices()
	})
}

// phoneAddrs drops loopback and container addresses from the footer.
func phoneAddrs(addrs []string) []string {
	out := slices.DeleteFunc(slices.Clone(addrs), func(a string) bool {
		return strings.HasPrefix(a, "127.") || strings.HasPrefix(a, "[::1]") || strings.HasPrefix(a, "172.")
	})
	if len(out) == 0 {
		return addrs
	}
	return out
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
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
		return t.Local().Format("Jan 2")
	}
}
