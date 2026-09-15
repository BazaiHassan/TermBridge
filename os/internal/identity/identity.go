// Package identity holds the agent's long-term key. It is an Ed25519 key whose
// public half is the agent ID carried in the pairing QR code; the X25519 Noise
// static key is derived from it the way libsodium does, so a phone that only
// knows the agent ID can compute the Noise key to handshake with (ADR 0005).
package identity

import (
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"

	"filippo.io/edwards25519"
	"golang.org/x/crypto/curve25519"
)

var errLowOrder = errors.New("identity: Ed25519 public key has low order")

// AgentID encodes an Ed25519 public key as carried in the QR code.
func AgentID(pub ed25519.PublicKey) string {
	return base64.StdEncoding.EncodeToString(pub)
}

// ParseAgentID decodes an agent ID and checks it is a usable public key.
func ParseAgentID(id string) (ed25519.PublicKey, error) {
	b, err := base64.StdEncoding.DecodeString(id)
	if err != nil || len(b) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("identity: agent ID must be %d base64 bytes", ed25519.PublicKeySize)
	}
	if _, err := NoisePublic(b); err != nil {
		return nil, err
	}
	return b, nil
}

// NoiseKey derives the X25519 static key pair from an Ed25519 private key
// (libsodium crypto_sign_ed25519_sk_to_curve25519).
func NoiseKey(priv ed25519.PrivateKey) (private, public []byte, err error) {
	h := sha512.Sum512(priv.Seed())
	sk := make([]byte, curve25519.ScalarSize)
	copy(sk, h[:curve25519.ScalarSize])
	sk[0] &= 248
	sk[31] &= 127
	sk[31] |= 64
	pk, err := curve25519.X25519(sk, curve25519.Basepoint)
	if err != nil {
		return nil, nil, fmt.Errorf("identity: derive X25519 key: %w", err)
	}
	return sk, pk, nil
}

// NoisePublic converts an Ed25519 public key to the matching X25519 public key
// (libsodium crypto_sign_ed25519_pk_to_curve25519). Low-order keys are
// rejected: they would make every Diffie-Hellman result predictable.
func NoisePublic(pub ed25519.PublicKey) ([]byte, error) {
	p, err := new(edwards25519.Point).SetBytes(pub)
	if err != nil {
		return nil, fmt.Errorf("identity: invalid Ed25519 public key: %w", err)
	}
	if new(edwards25519.Point).MultByCofactor(p).Equal(edwards25519.NewIdentityPoint()) == 1 {
		return nil, errLowOrder
	}
	return p.BytesMontgomery(), nil
}

// Fingerprint renders a key as four short groups for humans to compare,
// e.g. "1a2b 3c4d 5e6f 7a8b".
func Fingerprint(key []byte) string {
	sum := sha256.Sum256(key)
	h := hex.EncodeToString(sum[:8])
	return h[0:4] + " " + h[4:8] + " " + h[8:12] + " " + h[12:16]
}
