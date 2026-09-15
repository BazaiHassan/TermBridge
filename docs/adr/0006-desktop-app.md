# ADR 0006 — Desktop app for the agent

- Status: accepted
- Date: 2026-09-15

## Context

The project owner asked for a desktop app: launch it, a QR code appears, the phone scans it, done.
Architecture §1 lists a desktop *client* as a non-goal. That means connecting *from* a desktop to
other machines, which is a different thing. This app is a front end for the *agent* side.

## Decision

- **One agent core, two front ends.** `agent/internal/agent` holds pairing, authorization, revocation
  and serving. The `termbridge` CLI and `termbridge-desktop` are both thin layers over it. Events flow
  through `agent.Events`.
- **Toolkit: Fyne v2.8.** It is pure Go plus OpenGL through cgo, runs on Linux, Windows and macOS, and
  has a system tray API. Wails needs Node and WebKitGTK. Gio has no tray support and would need every
  widget built by hand.
- **Separate binary behind a build tag.** Every desktop file carries `//go:build desktop`. `go build
  ./...`, `go test ./...` and the CLI (8 MB) therefore need no cgo or GL headers. The desktop binary is
  about 25 MB, above the 15 MB target that architecture §9 sets for the *agent*. That's accepted for an
  optional GUI.
- **Closing the window quits the agent.** It doesn't hide to the tray. Stock GNOME has no tray without
  an extension, and a hidden window you can't bring back would leave the computer silently reachable,
  which conflicts with architecture §8.8. If shells are open, the app asks for confirmation first.
- **The QR code renews itself.** Pairing is open while the QR is on screen and closes when a phone
  pairs or the user taps "Done". Each code lives 120 s and works once. A fresh code replaces an expired
  one automatically.
- **Design is checked in tests.** `ui_snapshot_test.go` renders the window with Fyne's software painter
  in the pairing, paired and live states. Setting `TERMBRIDGE_SNAPSHOT_DIR` writes those renders out as
  PNGs for review.

## Consequences

- Building the desktop app requires a C compiler and the GL/X11 headers, or `DESKTOP_TAGS="desktop
  wayland"` for a Wayland-only build.
- The CLI and the desktop app can't run at the same time: both listen on port 7423. The desktop app
  says so when it can't bind the port.
