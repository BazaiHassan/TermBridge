# ADR 0008: Sessions that survive the screen, the network and the background

- Status: accepted
- Date: 2026-09-15

## Context

A phone's link to the computer drops all the time: Wi-Fi hands over to mobile data, the train
enters a tunnel, the user switches apps and Android freezes the process. Until phase 6 a drop
killed the shell, and leaving the terminal screen closed the connection.

## Decision

1. **The agent keeps shells alive** for 15 minutes after a disconnect, holding up to 256 KiB of
   output, and lets the same device key re-attach with `SESSION_ATTACH` (PROTOCOL.md §4.8).
   Any other device gets `unknown_session`.
2. **On the phone, sessions belong to an app-wide `TerminalSessions`**, not to a screen. A
   `MachineSession` owns the connection, the remote shell and the emulator. The terminal
   screen only observes it.
3. **Reconnect policy**: back off 1 s, 2 s, 4 s … up to 30 s. Retry at once when the default
   network changes or the computer appears on mDNS. A network change also probes the live
   link (PING, 4 s timeout) instead of waiting ~45 s for missed keepalives. Stop retrying only
   when retrying cannot help: the computer rejected this phone (revoked or reset), the protocol
   doesn't match, or no shell could start.
4. **Foreground service** (`SessionService`, type `specialUse`) runs while any session is
   connecting, live or reconnecting. Its ongoing notification names the computer, opens the
   terminal on tap, and has *End session*. No other foreground service type fits an
   interactive remote shell: `dataSync` has a daily time limit on Android 15, and
   `connectedDevice` is for paired peripherals.
5. **Ending is explicit.** *End session* (menu or notification) sends `SESSION_CLOSE`, and the
   shell ends on the computer. Leaving the screen or losing the network does not end it.

## Consequences

- Closing the app no longer means disconnecting. The notification makes this visible, and the
  agent's LIVE indicator still shows the session.
- `START_NOT_STICKY`: if Android kills the process anyway, nothing restarts. The shell waits
  on the computer for 15 minutes but the phone has forgotten its ID, so it expires.
- **Known gap:** output that the agent had already queued on a connection that silently died
  is lost, not replayed, because the replay buffer only starts at the detach. A full-screen
  program redraws on the next keystroke or resize. Acknowledged byte offsets would close this
  gap; that is a protocol change for later.
