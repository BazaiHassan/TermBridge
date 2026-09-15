package transport

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"os"
	"path/filepath"
	"testing"

	"github.com/flynn/noise"

	"termbridge/internal/identity"
	"termbridge/internal/proto"
)

var updateVectors = flag.Bool("update-vectors", false, "rewrite docs/vectors/noise.json")

var noiseVectorsPath = filepath.Join("..", "..", "..", "..", "docs", "vectors", "noise.json")

func fixedKey(t *testing.T, label string) noise.DHKey {
	t.Helper()
	seed := sha256.Sum256([]byte(label))
	k, err := noise.DH25519.GenerateKeypair(bytes.NewReader(seed[:]))
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func TestIsVirtual(t *testing.T) {
	for name, want := range map[string]bool{"wlp2s0": false, "enp3s0": false, "eth0": false, "docker0": true, "br-cd70b795": true, "nekoray-tun": true, "tun0": true, "wg0": true} {
		if got := isVirtual(name); got != want {
			t.Errorf("isVirtual(%s) = %v", name, got)
		}
	}
}

type noiseVector struct {
	Protocol              string `json:"protocol"`
	PrologueHex           string `json:"prologue_hex"`
	AgentEd25519SeedHex   string `json:"agent_ed25519_seed_hex"`
	AgentID               string `json:"agent_id"`
	AgentStaticPublicHex  string `json:"agent_x25519_public_hex"`
	AgentStaticPrivHex    string `json:"agent_x25519_private_hex"`
	DeviceStaticPrivHex   string `json:"device_static_private_hex"`
	DeviceStaticPublicHex string `json:"device_static_public_hex"`
	InitiatorEphemeralHex string `json:"initiator_ephemeral_private_hex"`
	ResponderEphemeralHex string `json:"responder_ephemeral_private_hex"`
	Msg1PayloadHex        string `json:"msg1_payload_hex"`
	Msg1Hex               string `json:"msg1_hex"`
	Msg2PayloadHex        string `json:"msg2_payload_hex"`
	Msg2Hex               string `json:"msg2_hex"`
	Transport             []struct {
		Direction     string `json:"direction"`
		PlaintextHex  string `json:"plaintext_hex"`
		CiphertextHex string `json:"ciphertext_hex"`
	} `json:"transport"`
}

// TestNoiseVectors pins the exact handshake and transport bytes that the
// Kotlin implementation (mobile/core-crypto) must reproduce. Regenerate with
// go test ./agent/internal/transport -run NoiseVectors -update-vectors.
func TestNoiseVectors(t *testing.T) {
	seed := sha256.Sum256([]byte("termbridge test agent"))
	agentEd := ed25519.NewKeyFromSeed(seed[:])
	agentPriv, agentPub, err := identity.NoiseKey(agentEd)
	if err != nil {
		t.Fatal(err)
	}
	agentStatic := noise.DHKey{Private: agentPriv, Public: agentPub}
	device := fixedKey(t, "termbridge test device")
	ie, re := fixedKey(t, "initiator ephemeral"), fixedKey(t, "responder ephemeral")
	payload1, _ := json.Marshal(proto.HandshakePayload{Pair: &proto.PairRequest{Code: "AAECAwQF", Name: "Pixel 8"}})

	// flynn/noise ignores Config.EphemeralKeypair when writing `e` and always
	// draws it from Random, so pin the ephemeral key through Random.
	cfg := func(initiator bool, static, eph noise.DHKey, peer []byte) *noise.HandshakeState {
		hs, err := noise.NewHandshakeState(noise.Config{
			CipherSuite: CipherSuite, Pattern: noise.HandshakeIK, Initiator: initiator,
			Prologue: []byte(Prologue), StaticKeypair: static, PeerStatic: peer,
			Random: bytes.NewReader(eph.Private),
		})
		if err != nil {
			t.Fatal(err)
		}
		return hs
	}
	agentStaticFromID, err := identity.NoisePublic(agentEd.Public().(ed25519.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	initiator := cfg(true, device, ie, agentStaticFromID)
	responder := cfg(false, agentStatic, re, nil)

	msg1, _, _, err := initiator.WriteMessage(nil, payload1)
	if err != nil {
		t.Fatal(err)
	}
	got1, _, _, err := responder.ReadMessage(nil, msg1)
	if err != nil || !bytes.Equal(got1, payload1) || !bytes.Equal(responder.PeerStatic(), device.Public) {
		t.Fatalf("responder msg1: %v", err)
	}
	msg2, rRecv, rSend, err := responder.WriteMessage(nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, iSend, iRecv, err := initiator.ReadMessage(nil, msg2)
	if err != nil {
		t.Fatal(err)
	}

	v := noiseVector{
		Protocol:              "Noise_IK_25519_ChaChaPoly_BLAKE2s",
		PrologueHex:           hex.EncodeToString([]byte(Prologue)),
		AgentEd25519SeedHex:   hex.EncodeToString(seed[:]),
		AgentID:               base64.StdEncoding.EncodeToString(agentEd.Public().(ed25519.PublicKey)),
		AgentStaticPublicHex:  hex.EncodeToString(agentPub),
		AgentStaticPrivHex:    hex.EncodeToString(agentPriv),
		DeviceStaticPrivHex:   hex.EncodeToString(device.Private),
		DeviceStaticPublicHex: hex.EncodeToString(device.Public),
		InitiatorEphemeralHex: hex.EncodeToString(ie.Private),
		ResponderEphemeralHex: hex.EncodeToString(re.Private),
		Msg1PayloadHex:        hex.EncodeToString(payload1),
		Msg1Hex:               hex.EncodeToString(msg1),
		Msg2Hex:               hex.EncodeToString(msg2),
	}
	hello, _ := proto.JSONFrame(proto.OpHello, 0, proto.Hello{V: 1, Client: "android/0.1.0"})
	helloWire, _ := proto.Encode(hello)
	ackWire, _ := proto.Encode(proto.Pong(42))
	dataWire, _ := proto.Encode(proto.Data(1, []byte("ls\r")))
	for _, m := range []struct {
		dir   string
		plain []byte
		send  *noise.CipherState
		recv  *noise.CipherState
	}{
		{"initiator_to_responder", helloWire, iSend, rRecv},
		{"responder_to_initiator", ackWire, rSend, iRecv},
		{"initiator_to_responder", dataWire, iSend, rRecv},
	} {
		ct, err := m.send.Encrypt(nil, nil, m.plain)
		if err != nil {
			t.Fatal(err)
		}
		pt, err := m.recv.Decrypt(nil, nil, ct)
		if err != nil || !bytes.Equal(pt, m.plain) {
			t.Fatalf("transport round trip: %v", err)
		}
		v.Transport = append(v.Transport, struct {
			Direction     string `json:"direction"`
			PlaintextHex  string `json:"plaintext_hex"`
			CiphertextHex string `json:"ciphertext_hex"`
		}{m.dir, hex.EncodeToString(m.plain), hex.EncodeToString(ct)})
	}

	if *updateVectors {
		b, _ := json.MarshalIndent(v, "", "  ")
		if err := os.WriteFile(noiseVectorsPath, append(b, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	b, err := os.ReadFile(noiseVectorsPath)
	if err != nil {
		t.Fatalf("read vectors (generate with -update-vectors): %v", err)
	}
	var want noiseVector
	if err := json.Unmarshal(b, &want); err != nil {
		t.Fatal(err)
	}
	gotJSON, _ := json.Marshal(v)
	wantJSON, _ := json.Marshal(want)
	if !bytes.Equal(gotJSON, wantJSON) {
		t.Fatalf("Noise output changed; if intended, regenerate docs/vectors/noise.json\n got %s\nwant %s", gotJSON, wantJSON)
	}
}

func TestDialRejectedByUnknownKey(t *testing.T) {
	agentEd := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{7}, 32))
	priv, pub, _ := identity.NoiseKey(agentEd)
	srv, err := Listen(ServerConfig{
		Addrs:     []string{"127.0.0.1:0"},
		Static:    noise.DHKey{Private: priv, Public: pub},
		Authorize: func([]byte, []byte) (Peer, bool) { return Peer{}, false },
		Handler:   func(context.Context, Conn, Peer) { t.Error("handler ran for rejected device") },
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = srv.Serve(ctx) }()
	_, err = Dial(ctx, "ws://"+srv.Addrs()[0].String()+Path, pub, fixedKey(t, "stranger"), nil)
	if !errors.Is(err, ErrRejected) {
		t.Fatalf("dial err = %v, want ErrRejected", err)
	}
}
