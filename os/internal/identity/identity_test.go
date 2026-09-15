package identity

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"regexp"
	"testing"
)

func TestNoiseKeyMatchesPublicConversion(t *testing.T) {
	for range 64 {
		pub, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		_, fromPrivate, err := NoiseKey(priv)
		if err != nil {
			t.Fatal(err)
		}
		fromPublic, err := NoisePublic(pub)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(fromPrivate, fromPublic) {
			t.Fatalf("X25519 from private %x != from public %x", fromPrivate, fromPublic)
		}
	}
}

func TestAgentIDRoundTrip(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	got, err := ParseAgentID(AgentID(pub))
	if err != nil || !bytes.Equal(got, pub) {
		t.Fatalf("round trip: %x, %v", got, err)
	}
	for _, bad := range []string{"", "not base64!", "AAAA"} {
		if _, err := ParseAgentID(bad); err == nil {
			t.Errorf("ParseAgentID(%q) accepted", bad)
		}
	}
}

func TestRejectsLowOrderKey(t *testing.T) {
	identity := make([]byte, 32)
	identity[0] = 1 // the neutral element (0, 1)
	if _, err := NoisePublic(identity); err == nil {
		t.Fatal("low-order key accepted")
	}
}

func TestFingerprintFormat(t *testing.T) {
	if fp := Fingerprint([]byte("key")); !regexp.MustCompile(`^[0-9a-f]{4}( [0-9a-f]{4}){3}$`).MatchString(fp) {
		t.Fatalf("fingerprint %q", fp)
	}
}
