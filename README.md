# TermBridge

Your computer's shell on your phone. Start the app on the computer, scan the QR code with the
phone, and you're in. No port forwarding, no SSH keys to manage, no screen frames: only terminal
bytes travel, end-to-end encrypted, so typing feels local (target: < 50 ms echo on a LAN).

> **Status: phases 0, 1 and 4 are implemented, and phase 5 partly.** That covers the protocol, the
> terminal, pairing, end-to-end encryption, and connecting from other networks through a
> self-hosted blind relay or a port forward. mDNS discovery (the rest of phase 5) comes next.
> None of it has been tested on a real phone yet.

```
termbridge/
├── docs/      PROTOCOL.md (source of truth) · adr/ · vectors/ (golden frames + Noise bytes)
├── os/        Go 1.24+: agent CLI, desktop app, and later the relay
└── mobile/    Android app: Kotlin, Jetpack Compose, Hilt, custom VT emulator
```

The full design, written in Persian, is in [`docs/TermBridge-Architecture.md`](docs/TermBridge-Architecture.md).
Decisions that refine it are recorded in [`docs/adr/`](docs/adr).

**Downloads:** see [Releases](https://github.com/BazaiHassan/TermBridge/releases). Each release has
the `termbridge` CLI (Linux, macOS), the desktop app (Linux, macOS), and Android APKs, one per
CPU type. `SHA256SUMS.txt` lists the checksums. Maintainers publish a release by pushing a tag,
e.g. `git tag v0.1.0-alpha.1 && git push origin v0.1.0-alpha.1`. That runs
[`.github/workflows/release.yml`](.github/workflows/release.yml).

---

## Quick start

### 1. On the computer, choose one of the two front ends

**Desktop app.** It opens a window with a QR code:

```bash
cd os
make desktop                          # X11 + Wayland build; needs GL/X11 headers, see below
./bin/termbridge-desktop
make install-desktop                  # optional: install to ~/.local/bin and add to the app menu
```

Build dependencies. The GUI toolkit compiles both X11 and Wayland support, so it needs both sets of headers:

- **Fedora:** `sudo dnf install gcc libX11-devel libXcursor-devel libXrandr-devel libXinerama-devel libXi-devel libXxf86vm-devel mesa-libGL-devel wayland-devel libxkbcommon-devel`
- **Ubuntu/Debian:** `sudo apt install gcc libgl1-mesa-dev xorg-dev libxkbcommon-dev libwayland-dev`

If you only need a Wayland build and don't have the X11 headers, run `make desktop DESKTOP_TAGS="desktop wayland"`.

**CLI.** For servers or headless machines:

```bash
cd os
make build
./bin/termbridge pair                 # prints the QR code in the terminal, pairs, keeps running
./bin/termbridge run                  # every later start
```

### 2. On the phone

Install the app, tap **Scan QR code**, and point the camera at the code. Before you tap **Pair**,
check that the fingerprint shown on the phone matches the one on the computer. The phone then
opens a shell.

```bash
cd mobile
./gradlew :app:installDebug                       # phone connected over adb
./gradlew :app:assembleRelease                    # signed APKs, one per CPU type (see Signing)
```

### CLI reference

| Command | What it does |
|---|---|
| `termbridge pair` | Shows a QR code with a one-time code that expires after 120 s, pairs a phone, then keeps serving |
| `termbridge run` | Serves phones that are already paired. Unknown phones are dropped without a reply |
| `termbridge status` | Shows the fingerprint, whether the agent is running, and paired phones with when each was last seen |
| `termbridge revoke <name>` | Unpairs a phone. A running agent disconnects it within 1 s |
| `termbridge reset` | Deletes the identity and every pairing |
| `termbridge relay [url\|off]` | Shows, sets or turns off the relay used when the phone isn't on the same network |
| `termbridge autostart [on\|off]` | Starts serving at login: systemd user unit (or XDG autostart) on Linux, LaunchAgent on macOS, Run key on Windows. Flags after `on` go to `run`; no argument shows the state |

Flags for `run` and `pair`: `--port` (default 7423), `--listen`, `--shell`, `--max-sessions`,
`--lan-only` (local network only, no relay), `-v`.

### 3. From anywhere (optional)

On the same Wi-Fi everything works out of the box. For other networks, such as mobile data or
another house, the phone tries these in order and the first to answer wins (PROTOCOL.md §9):

1. **Direct over the internet.** If you forward TCP port 7423 on your router to this computer, the
   agent checks through the relay that the port is really reachable and tells the phone. The phone
   then connects straight to your public IP, with nothing in between.
2. **Your own relay.** It's for when no direct path exists, for example behind carrier-grade NAT.
   The relay only ever sees end-to-end encrypted bytes and holds no key. Tests prove it.

To set up a relay on a VPS (any small Linux server with a domain name pointing at it):

```bash
# on the VPS: binary from Releases (termbridge-relay-…-linux-amd64), or `make relay` in os/
sudo install -m755 termbridge-relay-*-linux-amd64 /usr/local/bin/termbridge-relay
sudo install -m644 termbridge-relay.service /etc/systemd/system/
sudo systemctl enable --now termbridge-relay
# Caddy provides TLS: put the Caddyfile (with your domain) in /etc/caddy/ and reload Caddy

# on the computer
termbridge relay wss://relay.example.com
termbridge pair          # pair again, so the phone learns the relay
```

After that the phone reaches the computer from any network. The computer keeps an outgoing
connection to the relay, so no port has to be opened at home.

### "Can't reach …" on the phone

The phone tries every address in the QR code and says which ones failed. The usual causes:

1. **A VPN on the phone** (v2rayNG, Nekobox, …) sends local traffic into the tunnel. Turn on
   *Bypass LAN*, or pause the VPN.
2. **Different networks.** Guest Wi-Fi, or "client isolation" on the router, stops devices from
   seeing each other.
3. **Firewall on the computer.** Fedora Workstation allows ports 1025–65535 by default. Elsewhere,
   run `sudo firewall-cmd --add-port=7423/tcp` or `sudo ufw allow 7423/tcp`.

### Tests

```bash
cd os && make test        # 93 tests: PTYs, Noise, pairing, revocation, end-to-end encrypted shell
cd mobile && ./gradlew test   # 61 tests: codec, Noise interop, emulator, connection racing
```

Two sets of golden vectors lock the Go and Kotlin implementations together:
[`frames.json`](docs/vectors/frames.json) for framing, and [`noise.json`](docs/vectors/noise.json)
for the exact bytes of the Noise handshake and transport, in both roles.

---

## What works

| Area | Implemented |
|---|---|
| Security | Noise_IK_25519_ChaChaPoly_BLAKE2s end to end. The agent's key comes from the QR code, so there's no MITM window. One-time 120 s pairing code inside the encrypted handshake. Allowlist in `devices.json`. Instant revocation. The phone's key is sealed by the Android Keystore (StrongBox when available) ([ADR 0005](docs/adr/0005-keys-and-pairing.md)) |
| Agent | PTY sessions (ConPTY on Windows: PowerShell 7, Windows PowerShell or cmd), output coalescing (first byte sent immediately, then a 5 ms window), 4 MiB backpressure, LAN-only bind, live status line, CLI and desktop front ends over one core |
| Desktop app | QR window that renews its code automatically, paired phones with Revoke, LIVE indicator (tray icon where the desktop has a tray), app-menu launcher ([ADR 0006](docs/adr/0006-desktop-app.md)) |
| Phone | CameraX + ML Kit scanner (on-device, no Google services needed), fingerprint check, tries every LAN address and keeps the first to answer, custom VT emulator (truecolor, alt screen, 10 000-line scrollback), sticky Ctrl/Alt key row, pinch zoom. Drops (Wi-Fi ↔ mobile data, tunnels) reconnect on their own and re-attach the running shell, replaying what you missed. Several shells per computer as tabs over one connection. An ongoing notification keeps them open while you use other apps ([ADR 0008](docs/adr/0008-background-sessions.md)) |

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 0 | Monorepo, protocol, codecs | ✅ |
| 1 | LAN proof of concept | ✅ |
| 2 | Full emulator: wide chars, mouse, reflow, selection, recorded `vim`/`less` replays | wide chars (CJK, emoji), combining marks, mouse (tap = click, drag = wheel; X10 and SGR), long-press selection with copy, reflow on width change and replays of real `vim` and `less` sessions ✅ |
| 3 | Windows ConPTY | ✅ agent (Windows 10 1809+, tested in CI); Windows desktop app with phase 7 |
| 4 | Pairing, Noise, Keystore, QR, desktop app | ✅ code complete; needs a run on a real phone |
| 5 | Blind relay, direct internet path, addresses that follow the computer, mDNS discovery | ✅ |
| 6 | Shells survive disconnects (15 min, replay), auto-reconnect with re-attach, foreground service, multiple sessions | ✅ |
| 7 | Installers, settings | start at login and settings ✅ (desktop: relay, shell; phone: text size, Night/Day theme, keep screen on); signed installers next |

---

## Security

TermBridge opens a full shell on your computer, so treat it with the same care as SSH.

- Every byte is encrypted and authenticated end to end. The WebSocket underneath is plain `ws://`
  because Noise does the protection. A relay (phase 5) will only ever see ciphertext.
- Only paired phones can connect. Pairing requires the QR code on your screen, whose code works
  once within 120 s.
- If a phone is lost, run `termbridge revoke <name>` or use **Revoke** in the desktop app.

**Never run the agent with `sudo` or as root/Administrator.** Every paired phone would get a root
shell. The agent refuses to run as root unless you pass `--allow-root`. Don't.

### Out of scope

TermBridge doesn't protect against:

- a computer or phone that is already compromised,
- physical access to an unlocked device, including someone photographing the QR code while it's
  on screen,
- traffic analysis: packet sizes and timing reveal typing rhythm.

---

## Signing

Release builds are signed when credentials are available. Otherwise they come out unsigned.

| Source | Used for |
|---|---|
| `mobile/keystore.properties` (gitignored): `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | local builds |
| `TERMBRIDGE_KEYSTORE`, `TERMBRIDGE_KEYSTORE_PASSWORD`, `TERMBRIDGE_KEY_ALIAS`, `TERMBRIDGE_KEY_PASSWORD` | CI (take precedence) |

The release key lives **outside the repository**, at `~/.android/keystores/termbridge-release.jks`.
Back it up offline together with `keystore.properties`. If you lose it, installed apps can never be
updated.

`assembleRelease` produces one APK per CPU type (`app-arm64-v8a-release.apk` for almost every
current phone), because ML Kit's QR model ships a native library for each ABI.

## Building behind regional blocks

Google's download hosts are geo-blocked in some regions. This checkout is set up for that:

| What | Workaround |
|---|---|
| Go toolchain | installed from `goproxy.cn` as the `golang.org/toolchain` module into `~/sdk/go1.27.1`. The Makefile finds it there |
| Go modules | `GOPROXY=https://goproxy.cn,direct` (set by the Makefile) |
| Google Maven | `termbridge.googleMirror=https://maven.myket.ir` in `mobile/gradle.properties`. Delete that line where `maven.google.com` is reachable |

## Third-party

JetBrains Mono: SIL Open Font License 1.1 ([license](docs/third_party/JetBrainsMono-OFL.txt)).
Go: `creack/pty`, `coder/websocket`, `flynn/noise`, `skip2/go-qrcode`, `filippo.io/edwards25519`,
`spf13/cobra`, Fyne. Android: AndroidX, Jetpack Compose, Hilt, OkHttp, BouncyCastle, CameraX,
ML Kit (bundled barcode model), kotlinx.serialization.
