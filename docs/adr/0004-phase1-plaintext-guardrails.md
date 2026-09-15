# ADR 0004 — Guardrails for the unencrypted phase-1 build

- Status: **superseded** by [ADR 0005](0005-keys-and-pairing.md). Noise now protects every connection and
  `--insecure-dev` has been removed. Guardrails 2–6 and 8 remain in force.
- Date: 2026-09-14

## Context

Phases 1–3 carry terminal bytes over plain WebSocket. Architecture §13.6 says those builds must not run
outside a development LAN and must print a warning. At the same time, the agent exposes a full login shell.

## Decision

The protocol stays unencrypted until phase 4, but these defenses apply from day one.

| # | Guardrail | Where |
|---|-----------|-------|
| 1 | `termbridge run` refuses to start without `--insecure-dev`, and logs a loud warning when it does start. | `agent/cmd/termbridge` |
| 2 | The agent refuses to run as root unless `--allow-root` is passed (architecture §8.7). | `agent/cmd/termbridge` |
| 3 | The agent binds only to loopback, RFC 1918, and IPv6 ULA addresses — never to `0.0.0.0` or `::`. Carrier-grade NAT addresses (`100.64/10`) are excluded. `--listen` can override this. | `transport/netif.go` |
| 4 | The agent rejects peers whose source address isn't LAN-local, before the WebSocket upgrade. This applies even if `--listen` is set to something wider. | `transport/wsserver.go` |
| 5 | The agent refuses WebSocket upgrades that carry a cross-origin `Origin` header. This stops a web page in the laptop's browser from reaching `ws://127.0.0.1:7423`. | `coder/websocket` default, kept on deliberately |
| 6 | The agent requires the `termbridge.v1` subprotocol, so stray clients fail fast. | `transport/wsserver.go` |
| 7 | Android permits cleartext traffic **only in the debug build** (`src/debug/AndroidManifest.xml`). A release APK can't talk to a phase-1 agent at all. | `mobile/app` |
| 8 | The agent shows a live status line in its terminal while any session is active (architecture §8.8). | `agent/internal/statusline` |
| 9 | The app shows a persistent "Unencrypted dev build" banner. | `feature-devices` |

## Consequences

Anyone who can already send packets on the same LAN can still read or inject keystrokes. This ADR only
limits how *far* that exposure reaches, not whether it exists. Phase 4 removes the exposure itself.
