# TermBridge Protocol — v1

Status: **normative**. The Go codec (`os/internal/proto`) and the Kotlin codec
(`mobile/core-proto`) must stay byte-compatible with this document.

To change the protocol: edit this file first, then both codecs, then
`docs/vectors/frames.json`. Both test suites replay those vectors, so a change
in only one language fails CI.

---

## 1. Layers

```
[ WebSocket binary messages ]           one message = one Noise message
            ↑
[ Noise_IK_25519_ChaChaPoly_BLAKE2s ]   end to end, on the LAN too (§1.2)
            ↑
[ TermBridge frames (§2) ]
```

- The first two WebSocket messages are the Noise handshake (§1.2). After that,
  each **binary** message carries exactly one Noise transport message, which
  decrypts to exactly one frame. Text messages are a protocol error.
- The WebSocket itself is plain `ws://`. Confidentiality and authentication come
  from Noise, so a relay or anyone on the network only sees ciphertext.
- The client must offer the WebSocket subprotocol **`termbridge.v1`**. The agent
  rejects handshakes that don't negotiate it.
- Don't negotiate `permessage-deflate`. It adds latency, and ciphertext doesn't
  compress anyway.

### 1.1 Endpoints

| URL                                   | Used by                          | Phase |
|---------------------------------------|----------------------------------|-------|
| `ws://<lan-ip>:7423/v1`               | app → agent, direct on the LAN   | 1     |
| `wss://<relay>/agent`                 | agent → relay                    | 5     |
| `wss://<relay>/client?agent=<id>`     | app → relay                      | 5     |

### 1.2 Secure channel

Noise pattern **IK**, prologue `TermBridge/1` (ASCII, no terminator).

| Key | Definition |
|---|---|
| Agent static | X25519 key derived from the agent's Ed25519 identity. Private key: `clamp(SHA-512(seed)[0..32])`. The phone computes the public key from the agent ID with the Edwards→Montgomery map `u = (1 + y) / (1 − y) mod 2²⁵⁵ − 19`, as libsodium's `crypto_sign_ed25519_pk_to_curve25519` does. Low-order keys are rejected. |
| Phone static | X25519 key generated on the phone. This is the `pubkey` stored in the agent's `devices.json`. |

```
 phone                                     agent
  │ -> e, es, s, ss   + payload ──────────►│  msg1: phone key + payload, encrypted to the agent's key
  │                                        │  authorize: is the key paired, or does the payload redeem a code?
  │◄─────────────────────── <- e, ee, se   │  msg2 (empty payload), sent only when authorized
  │══ transport messages: one frame each ══│
```

- **msg1 payload** is either empty or JSON `{"pair":{"code":"<base64>","name":"Pixel 8"}}`,
  which redeems a pairing code (§8).
- **Rejection:** the agent closes TCP without a close frame and without msg2. An
  unknown device and a wrong agent key look the same to the phone; both mean
  "pair again".
- The handshake must complete within 10 s.
- **Transport:** ChaChaPoly with a separate nonce counter in each direction. Any
  decryption failure closes the connection. v1 never rekeys.
- Test vectors: [`vectors/noise.json`](vectors/noise.json) (fixed keys, exact bytes).

---

## 2. Frame

```
+--------+-----------+---------------------+
| opcode | sessionID | payload             |
| u8     | u8        | 0 … 65 517 bytes    |
+--------+-----------+---------------------+
```

- All multi-byte integers are **big-endian**.
- `sessionID = 0` is a connection-level control frame. Values `1…255` refer to a
  session.
- **Maximum payload: 65 517 bytes** (65 535 − 16 AEAD tag − 2 header bytes). With
  this limit, every frame fits in a single Noise transport message, which Noise
  caps at 65 535 bytes. This refines the architecture's "64 KiB" figure; see
  [ADR 0003](adr/0003-max-payload.md).
- A frame shorter than 2 bytes or longer than the limit is **malformed**. The
  receiver closes the connection.

---

## 3. Opcodes

| Code   | Name             | Direction   | sessionID   | Payload                               |
|--------|------------------|-------------|-------------|---------------------------------------|
| `0x01` | `DATA`           | both        | session     | raw terminal bytes                    |
| `0x02` | `RESIZE`         | app → agent | session     | `u16 cols, u16 rows` (4 bytes)        |
| `0x10` | `SESSION_OPEN`   | app → agent | 0           | JSON `SessionOpen`                    |
| `0x11` | `SESSION_OPENED` | agent → app | 0           | `u8` new sessionID (1 byte)           |
| `0x12` | `SESSION_CLOSE`  | both        | session     | empty                                 |
| `0x13` | `SESSION_EXIT`   | agent → app | session     | `i32` exit code (4 bytes)             |
| `0x20` | `PING`           | both        | 0           | `u64` timestamp, ms (8 bytes)         |
| `0x21` | `PONG`           | both        | 0           | the 8 PING bytes, echoed verbatim     |
| `0x30` | `HELLO`          | app → agent | 0           | JSON `Hello`                          |
| `0x31` | `HELLO_ACK`      | agent → app | 0           | JSON `HelloAck`                       |
| `0x40` | `ERROR`          | agent → app | 0 / session | JSON `Error`                          |

- Fixed-size payloads (`RESIZE`, `SESSION_OPENED`, `SESSION_CLOSE`,
  `SESSION_EXIT`, `PING`, `PONG`) must have **exactly** the listed length.
  Otherwise the frame is a `bad_frame`.
- **Unknown opcodes** are ignored by the receiver, which keeps the protocol
  forward-compatible. The agent may answer `ERROR unsupported_opcode`.
- The `PING` timestamp is opaque to the peer. The sender can use any clock
  (the app uses a monotonic clock) because it only compares the timestamp with
  its own clock when the `PONG` returns.

### 3.1 JSON payloads

JSON payloads are UTF-8 with no BOM and are encoded compactly. Receivers
**ignore unknown fields**. Tests compare JSON payloads by value, not byte for
byte.

```jsonc
// Hello — app → agent, first frame on every connection
{"v": 1, "client": "android/0.1.0"}

// HelloAck — agent → app
{"v": 1, "agent": "termbridge-agent/0.1.0", "os": "linux", "hostname": "amir-thinkpad"}

// SessionOpen — empty shell/cwd = agent default ($SHELL -l, home directory)
{"shell": "", "cwd": "", "cols": 80, "rows": 24}

// Error — msg is for humans and never contains terminal content
{"code": "session_open_failed", "msg": "exec /bin/zsh: no such file or directory"}
```

### 3.2 Error codes

| Code                  | Meaning                                                   | Connection |
|-----------------------|-----------------------------------------------------------|------------|
| `hello_required`      | first frame was not `HELLO`                               | closed     |
| `unsupported_version` | `HELLO.v` is not a version the agent speaks               | closed     |
| `bad_frame`           | fixed-size payload has the wrong length, or JSON is invalid | kept     |
| `unsupported_opcode`  | opcode unknown to this agent                              | kept       |
| `session_open_failed` | the PTY or shell could not be started                     | kept       |
| `too_many_sessions`   | per-agent session limit reached                           | kept       |

---

## 4. Connection lifecycle

```
 app                                      agent
  │── WebSocket upgrade (termbridge.v1) ──►│
  │── HELLO {"v":1,…} ────────────────────►│
  │◄──────────────────── HELLO_ACK {…}     │
  │── SESSION_OPEN {"cols":80,"rows":24} ─►│  spawn login shell in a PTY
  │◄──────────────────── SESSION_OPENED(3) │
  │── DATA(3) "ls\r" ─────────────────────►│
  │◄──────────────────── DATA(3) "…"       │
  │── RESIZE(3) 120×40 ───────────────────►│  TIOCSWINSZ → kernel sends SIGWINCH
  │── PING t ─────────────────────────────►│
  │◄──────────────────── PONG t            │
  │◄──────────────────── SESSION_EXIT(3) 0 │  shell exited
```

1. **HELLO comes first**, within 10 s of the upgrade. If any other frame comes
   first, the agent sends `ERROR hello_required` and closes. If `v ≠ 1`, it sends
   `ERROR unsupported_version` and closes.
2. **SESSION_OPEN is answered in order.** Each request gets exactly one reply:
   `SESSION_OPENED` or `ERROR` (sessionID 0). Replies come in request order, so
   the app matches them first in, first out.
3. **The agent assigns session IDs**: the lowest free ID in `1…255`. An ID is not
   reused until `SESSION_EXIT` has been sent for it.
4. **Frames of one session arrive in order.** `SESSION_EXIT` is always the last
   frame of a session and follows its final `DATA`.
5. **`SESSION_CLOSE` from the app:** the agent hangs up the shell (SIGHUP to its
   process group, SIGKILL after 3 s) and replies with `SESSION_EXIT`.
   **`SESSION_CLOSE` from the agent:** the agent tore the session down without an
   exit status, for example on shutdown or revocation.
6. **Exit code:** the process exit status. If the process was killed by signal
   *N*, the exit code is `128 + N`.
7. Frames for an **unknown sessionID** are dropped silently. This covers a `DATA`
   frame racing a `SESSION_EXIT`.
8. **Phases 1–5:** sessions end when their connection ends. Surviving a reconnect
   (phase 6) requires a protocol revision that adds re-attach.

---

## 5. Flow rules

| Rule | Detail |
|---|---|
| Keepalive | The app sends `PING` every 15 s. Three unanswered PINGs in a row mean the connection is dead, and the app reconnects. The agent drops a connection that has sent nothing for 50 s. |
| Output coalescing | After ≥ 5 ms without output, the agent sends the next PTY chunk **immediately**, so keystroke echo never waits. Output that follows within the 5 ms window is merged and flushed when the window ends or the buffer reaches 32 KiB. |
| Input | **Never coalesced.** Each keystroke is sent as its own `DATA` frame immediately. A paste may be one `DATA` frame, split at the payload limit. |
| Backpressure | The agent caps each connection's send queue at **4 MiB** of buffer memory. When the queue is full, session pumps block. They stop reading the PTY, which blocks the writing process. Nothing is lost and memory stays bounded. |
| Priority | Connection-level control frames (`PONG`, `HELLO_ACK`, connection `ERROR`) skip the data queue, so keepalive survives bulk output. Session-scoped frames (`SESSION_OPENED`, `DATA`, `SESSION_EXIT`) share one FIFO queue to keep rule 4.4. |

---

## 6. Versioning

- The version appears in two places: `HELLO.v` and the subprotocol name
  `termbridge.v1`.
- **Additive changes don't bump the version:** new opcodes, which old peers
  ignore, and new JSON fields, which old peers also ignore.
- **Incompatible changes** bump both, to `v: 2` and `termbridge.v2`.

## 7. Test vectors

[`vectors/frames.json`](vectors/frames.json) holds canonical frames: raw frames,
typed messages, and malformed inputs. Unit tests in Go and in Kotlin replay every
vector in both directions, encoding and decoding.
[`vectors/noise.json`](vectors/noise.json) pins the handshake and transport
bytes for fixed keys. The Go test generates it, and the Kotlin implementation
must reproduce it exactly.

## 8. Pairing

1. `termbridge pair`, or the desktop app, opens a **pairing window**: a random
   6-byte code that is valid for 120 s, works once, and is burned after 5 wrong
   codes.
2. It shows a **QR code** that encodes compact JSON:
   ```json
   {"v":1,"agent_id":"<base64 Ed25519 public key>","name":"amir-thinkpad",
    "code":"<base64, 6 bytes>","lan":["192.168.1.20:7423"]}
   ```
   `lan` lists the private IPv4 addresses of physical interfaces only, never
   container or VPN interfaces. Phase 5 adds `relay`. Receivers ignore unknown
   fields.
3. The phone derives the agent's static key from `agent_id` (§1.2). It then
   connects to every `lan` address, starting each attempt 250 ms after the
   previous one. The first handshake to finish wins, and the phone closes the
   rest.
4. msg1 carries `{"pair":{…}}`. The agent redeems the code, stores the phone's
   static key in `devices.json`, and completes the handshake.
5. Later connections send an empty payload, and the agent accepts only paired
   keys. After `termbridge revoke <name>`, a running agent drops that phone's
   connections within 1 s.
6. **Optional QR fields:** `relay` (the relay's URL, e.g.
   `wss://relay.example.com`) and `wan` (public `host:port` addresses the agent
   has verified as reachable).

## 9. Relay

The relay connects a phone to an agent when no direct path exists, for example
when the laptop sits behind NAT. It is **blind**: it only copies Noise
ciphertext (§1.2) and holds no key. Each deployment is operated by its owner;
there is no shared public relay.

### 9.1 Endpoints

| URL | Subprotocol | Used by |
|---|---|---|
| `wss://<relay>/agent` | `termbridge.relay.v1` | agent: control channel (JSON text messages) |
| `wss://<relay>/agent/accept?token=<t>` | `termbridge.v1` | agent: one data channel per phone session |
| `wss://<relay>/client?agent=<id>` | `termbridge.v1` | phone: one connection per session |

`<id>` is the agent ID encoded as base64url without padding (`+/` → `-_`).

### 9.2 Agent control channel

```
relay → agent  {"type":"challenge","nonce":"<base64, 32 bytes>"}
agent → relay  {"type":"hello","agent_id":"<base64 Ed25519 public key>","sig":"<base64>","version":"…"}
               sig = Ed25519(identity, "TermBridge relay auth v1\n" ‖ nonce)
relay → agent  {"type":"ready","observed_ip":"91.109.105.133"}
relay → agent  {"type":"incoming","token":"<base64url, 16 bytes>"}     one per phone session
agent → relay  {"type":"probe","port":7423}                            is observed_ip:port reachable?
relay → agent  {"type":"probe_result","port":7423,"reachable":true}
```

- An invalid signature closes the connection with code 4001. A second control
  channel for the same agent replaces the first.
- The relay sends a WebSocket ping every 30 s. Either side treats a failed ping
  as a dead connection. The agent reconnects with exponential backoff, from
  1 s up to 60 s.

### 9.3 Sessions

1. The phone opens `/client?agent=<id>`. If the agent is not connected, the relay
   closes with **4004** ("agent offline").
2. The relay sends `incoming` with a fresh token. The agent opens
   `/agent/accept?token=<t>` within 10 s, otherwise the relay closes the phone's
   connection with **4008**.
3. The relay then **copies every binary message 1:1 in both directions**, keeping
   message boundaries. The phone runs the ordinary Noise handshake (§1.2) and the
   frame protocol through it, exactly as on the LAN.
4. **Limits per agent:** 5 concurrent sessions (the 6th is closed with **4029**) and
   10 Mbit/s of relayed traffic, both directions combined.
5. **Logging:** connection metadata only (agent fingerprint, client IP, byte
   counts, duration). Never message content.

### 9.4 Address hints and dial order

- `HELLO_ACK` gains an optional field `"addrs":{"lan":[…],"wan":[…]}` with the
  agent's current addresses. The phone stores them, so the next connection can
  go direct even after the agent's IP changed.
- **Dial order on the phone:**
  1. LAN addresses, one every 250 ms.
  2. `wan` addresses.
  3. The relay, 750 ms after the first attempt, or immediately once every direct
     attempt has failed.

  The first handshake to complete wins, and the phone closes the others.

### 9.5 LAN discovery (mDNS)

The agent advertises itself with DNS-SD on its physical LAN interfaces:

| Field | Value |
|---|---|
| Service | `_termbridge._tcp.local` |
| Instance | the computer's host name |
| Port | the agent's LAN port (7423) |
| TXT | `v=1`, `id=<agent ID, base64url without padding>` |

The phone browses for the service and matches `id` against its paired machines. A match adds that
address to the dial list, even when dialing has already started, and the phone stores it for next
time. So a laptop that gets a new IP from DHCP is still found without scanning a new QR code. The
record carries no secret: the Noise handshake authenticates the agent as always.
