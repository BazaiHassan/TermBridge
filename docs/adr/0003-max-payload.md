# ADR 0003 — Maximum frame payload is 65 517 bytes, not 65 536

- Status: accepted (protocol clarification; PROTOCOL.md §2 updated first)
- Date: 2026-09-14

## Context

Architecture §4.2 sets the maximum payload at "64 KB". Section 4.1 wraps every frame in a Noise transport
message. The Noise specification caps a transport message at **65 535 bytes, including the 16-byte AEAD
tag**.

A frame that carries a full 65 536-byte payload is 65 538 bytes before encryption and 65 554 bytes after.
That's too large for one Noise message. We would have to either fragment frames inside the Noise layer or
lower the limit.

## Decision

`MaxPayload = 65 535 − 16 (tag) − 2 (header) = 65 517` bytes.

Every frame fits in exactly one Noise message, and every Noise message fits in exactly one WebSocket
message. With this 1:1:1 mapping, the transport doesn't need to reassemble anything.

## Consequences

- The limit stays "under 64 KiB", so the architecture's intent holds.
- Senders split pastes and bulk output at this limit. In practice the agent flushes DATA at 32 KiB anyway
  (see PROTOCOL.md §5).
- Both codecs reject frames above this limit, and the vectors include `payload_too_large` to test it.
