# ADR 0007 — Reaching the computer from other networks

- Status: accepted
- Date: 2026-09-15

## Context

The owner wants production use: the phone connects from any network, ideally directly with no server in
between. We measured the computer's network:

- no global IPv6,
- no UPnP or NAT-PMP on the router,
- a home NAT whose WAN address is unknown and may itself be behind carrier-grade NAT.

When both ends sit behind NAT, a phone can only reach the computer in one of three ways: through an
address that is publicly reachable (IPv6, or a port forwarded on the router), through NAT hole punching,
or through a middle point.

## Decision

**Direct first, relay as fallback** (PROTOCOL.md §9). The owner chose this.

1. **Relay (`os/relay`).** The relay is **blind**: it copies Noise ciphertext 1:1 and holds no key. The
   agent authenticates to it with its Ed25519 identity, signing a fresh nonce. The relay allows 5 sessions
   and 10 Mbit/s per agent and logs metadata only. It ships as one static binary with a hardened systemd
   unit and a Caddy config for automatic TLS. Every owner runs their own relay; there is no shared public
   one.
2. **Direct over the internet.** The relay reports the agent's public IP and probes whether the agent's
   port can be reached from outside, which it can if the owner set up a port forward. When the probe
   succeeds, the agent advertises `wan` addresses in the QR code and in `HELLO_ACK`, and the phone tries
   them before the relay.
3. **Addresses follow the computer.** Every `HELLO_ACK` carries the agent's current LAN and WAN addresses.
   The phone stores them, so a new DHCP lease or a new public IP doesn't break the next connection.
4. **The peer filter moves to a flag.** ADR 0004 guardrail #4 rejected every peer outside the LAN. It
   protected the *unencrypted* phase-1 build. Now Noise authenticates every peer and silently drops
   unknown keys, so the agent accepts connections from any address by default. That's what makes the
   port-forward path possible. `--lan-only` restores the old behavior and also disables the relay.
   Binding stays on private and loopback addresses only; a router port forward reaches the agent through
   its LAN address.
5. **Relay packages live outside `internal/`.** They are at `relay/hub` and `relay/ws` instead of the
   architecture's `relay/internal/…`. That way the agent's end-to-end test can run the real relay, which
   architecture §6 requires for proving the relay never sees plaintext.

## Not done (yet)

- **NAT hole punching over UDP.** It would need a UDP transport on both sides, and it usually fails
  against the symmetric carrier-grade NAT that mobile networks use.
- **Serverless discovery through the BitTorrent DHT (BEP 44).** The relay already fills that role and is
  more reliable on filtered networks. We may add it later for owners without a VPS.

## Consequences

- With neither a port forward nor a relay, connections work on the LAN only. The phone says so and
  suggests `termbridge relay <url>`.
- The relay needs a VPS and a domain, which is the owner's cost. It never gains the ability to read
  sessions.
