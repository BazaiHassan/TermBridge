# ADR 0005 — Keys, pairing and where secrets live

- Status: accepted (supersedes [ADR 0004](0004-phase1-plaintext-guardrails.md))
- Date: 2026-09-14

## Context

Phase 4 adds end-to-end encryption and QR pairing (architecture §3.1, §8). The architecture fixes several
parts:

- the QR code's JSON (`agent_id` = the Ed25519 public key),
- Noise_IK_25519_ChaChaPoly_BLAKE2s,
- a one-time 120 s pairing code,
- an allowlist in `devices.json`,
- phone keys in the Android Keystore.

Several details are left open. Noise needs an X25519 static key, not an Ed25519 one. The Android Keystore
can't hold X25519 keys below API 31. And no maintained Noise library exists for the JVM.

## Decisions

1. **One agent secret; the X25519 key is derived from it.** `identity.key` holds the Ed25519 seed. The Noise
   static key is derived from it the way libsodium does (PROTOCOL.md §1.2). The phone gets the matching
   X25519 public key from `agent_id` alone, so the QR format stays exactly as the architecture specifies.
   The relay (phase 5) can authenticate the agent with the same Ed25519 key.
2. **The pairing code travels inside Noise msg1.** It is encrypted to the agent's static key, which the phone
   already trusts from the QR code. Nobody watching the network sees the code, and the agent learns the
   phone's static key in the same message. An unknown key without a valid code is dropped without a reply.
3. **Revocation takes effect immediately, even from another process.** A running agent polls the modification
   time of `devices.json` every second and cancels the connections of any key that is no longer listed.
4. **Phone key storage.** The phone's X25519 private key is generated in software (BouncyCastle). It is
   stored encrypted with AES-256-GCM under a non-exportable key in the Android Keystore, backed by StrongBox
   when the device has it. Paired machines are stored the same way. `androidx.security:security-crypto`
   (EncryptedSharedPreferences) was deprecated in 2025, so a ~60-line wrapper replaces it.
5. **Noise in Kotlin** is a small implementation on BouncyCastle primitives (X25519, ChaCha20-Poly1305,
   BLAKE2s). It covers only what TermBridge uses: pattern IK in both roles, with no PSK and no rekey. The Go
   test generates `docs/vectors/noise.json` from `flynn/noise`, and the Kotlin test must reproduce it byte
   for byte.
6. **No plaintext mode.** `--insecure-dev` is gone. Because Noise protects every byte, release builds may use
   `ws://`: cleartext is permitted at the WebSocket level, and a release APK now works.

## Consequences

- Losing `identity.key` or running `termbridge reset` changes the agent ID, and every phone must pair again.
- Traffic analysis, meaning the size and timing of messages, is still possible. It is listed as out of scope
  in the README.
- The code is 48 bits, lives 120 s, works once, and is burned after 5 misses. Each guess costs a full Noise
  handshake, so guessing it online isn't feasible.
