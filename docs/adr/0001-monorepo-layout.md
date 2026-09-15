# ADR 0001 — Monorepo layout: `os/` + `mobile/` + `docs/`

- Status: accepted
- Date: 2026-09-14

## Context

The architecture (§2) calls for three modules in one monorepo: `agent`, `relay`, and `app`. The
project owner asked for the repository to be split into two top-level trees — one for the
operating-system side and one for the mobile app. The protocol specification must stay shared.

## Decision

```
termbridge/
├── docs/     PROTOCOL.md (source of truth), ADRs, golden test vectors
├── os/       one Go module: agent (phase 1+) and relay (phase 5)
└── mobile/   one Gradle build: the Android app, split into modules
```

### `os/` — one Go module (`module termbridge`)

```
os/
├── internal/proto/        frame codec, shared by the agent, the relay, and test clients
├── agent/cmd/termbridge/  CLI
├── agent/internal/…       pty, session, link, transport, statusline, …
└── relay/…                phase 5
```

The architecture puts `proto/` under `agent/internal/`, but it also says the package is "shared with relay".
Go's `internal/` visibility rule would block the relay from importing it at that path. So the package moves
up to `os/internal/proto`, where everything under `os/` can import it. One module also means one `go.mod`,
one dependency set, and one `go test ./...`.

We added one package that the architecture doesn't list: `agent/internal/link`. It handles a single
connected peer: the HELLO handshake, the per-connection send queue, and routing frames to sessions. It
sits between `transport` (which moves bytes) and `session` (which owns the PTYs). Keeping it separate lets
the transport change to Noise in phase 4 without touching session code.

### `mobile/` — Gradle build

The module names follow architecture §7.2 exactly: `core-proto`, `core-crypto`, `core-transport`,
`core-terminal`, `feature-pairing`, `feature-terminal`, `feature-devices`, `app`. We create each module in
the phase that first needs it. We don't commit empty modules.

We added two modules:

| Module        | Why |
|---------------|-----|
| `core-ui`     | Design system: theme, typography, shared components. Both feature modules and `app` use it. Without it, every feature would carry its own copy of the theme. |
| `build-logic` | Gradle convention plugins (`termbridge.android.feature`, …), so each module's build file stays about 10 lines and SDK and JVM levels live in one place. |

`core-proto` and `core-terminal` are **pure Kotlin/JVM** modules, with no Android dependency. Their unit
tests run in milliseconds on the JVM. They also make the protocol and emulator easy to reuse in a future
desktop or iOS (KMP) client.

## Consequences

- Neither the agent nor the relay can drift from the protocol. Both import the same `os/internal/proto`,
  and the Kotlin codec is checked against the same `docs/vectors/frames.json`.
- Anyone who wants to import `os/` as a library from outside this repository is blocked by `internal/`.
  That's intended.
