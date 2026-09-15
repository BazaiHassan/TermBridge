# ADR 0002 — Custom VT emulator (architecture §7.3, option 1)

- Status: accepted
- Date: 2026-09-14

## Context

The architecture offers three options for the terminal emulator: write our own VT parser, use Termux's
`terminal-emulator` (GPLv3), or run xterm.js in a WebView. It recommends option 1.

## Decision

We write our own emulator in `mobile/core-terminal`, a pure-Kotlin JVM module.

- **Parser.** A table-free state machine based on the DEC ANSI parser model by Paul Williams: ground,
  escape, CSI entry/param/intermediate/ignore, OSC string, and DCS/SOS/PM/APC passthrough. It decodes
  UTF-8 as a stream, so a multi-byte character split across two frames still decodes correctly.
- **Screen model.** Each row is an `IntArray` of code points plus a `LongArray` of packed styles:
  foreground 26 bits, background 26 bits, flags 12 bits. A cell costs 12 bytes, and printing a character
  doesn't allocate any objects.
- **Scrollback.** A ring buffer of 10 000 lines. Once the ring is full, the line that falls off the top of
  the ring is reused as the new bottom row of the screen. After warm-up, scrolling allocates nothing.
- **Dirty tracking.** Each row carries a dirty bit. The renderer (`feature-terminal/TerminalView`) re-records
  only the dirty rows into per-row `android.graphics.Picture` caches and replays the clean rows unchanged.
  Frames are paced by `postOnAnimation`, which is driven by Choreographer. Output that arrives in bursts is
  rendered at most once per vsync.
- **Threading.** The emulator isn't thread-safe. The network thread feeds it and the UI thread renders it,
  and both take the emulator's lock. The critical sections are short: a feed touches at most one frame's
  bytes, and a render reads only the dirty rows.

### Coverage in this pass

C0 controls, the CSI cursor/erase/insert/delete/scroll family, REP (modern ncurses uses it to draw
borders), SGR (16 colors, 256 colors, truecolor in both `;` and `:` forms, and attributes), DECSTBM scroll
regions, the DECSET modes 1/7/25/47/1047/1049/2004, the G0 DEC line-drawing charset, OSC 0/2 window
titles, DSR 5/6 and DA1/DA2 replies, and save/restore cursor.

### Left for phase 2

- Double-width (CJK/emoji) cells, based on a wcwidth table
- Insert mode (IRM)
- G1 charset and SO/SI shifts
- Mouse reporting
- Reflowing wrapped lines on resize
- Replay tests against recorded `vttest`, `vim`, and `htop` sessions
- Text selection

## Consequences

- No license constraint on the app. This also sets aside the Termux option, whose GPLv3 license would
  cover the whole app.
- We own the correctness burden. The mitigation is the architecture's plan (§11) to replay recorded
  sessions against reference screens.
