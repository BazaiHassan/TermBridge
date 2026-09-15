package discovery

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"strings"
	"testing"
)

func TestTXTCarriesURLSafeAgentID(t *testing.T) {
	pub := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{3}, 32)).Public().(ed25519.PublicKey)
	txt := TXT(pub)
	if txt[0] != "v=1" || !strings.HasPrefix(txt[1], "id=") {
		t.Fatalf("txt = %v", txt)
	}
	id := strings.TrimPrefix(txt[1], "id=")
	if strings.ContainsAny(id, "+/=") {
		t.Fatalf("id %q is not base64url without padding", id)
	}
	decoded, err := base64.RawURLEncoding.DecodeString(id)
	if err != nil || !bytes.Equal(decoded, pub) {
		t.Fatalf("id does not round-trip: %v", err)
	}
}
