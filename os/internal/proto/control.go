package proto

import (
	"encoding/json"
	"fmt"
)

// Hello is the first frame on every connection (app → agent).
type Hello struct {
	V      int    `json:"v"`
	Client string `json:"client"`
}

// HelloAck answers HELLO (agent → app).
type HelloAck struct {
	V        int    `json:"v"`
	Agent    string `json:"agent"`
	OS       string `json:"os"`
	Hostname string `json:"hostname"`
	Addrs    *Addrs `json:"addrs,omitempty"` // PROTOCOL.md §9.4
}

// SessionOpen asks the agent for a new shell. Empty Shell and Cwd select the
// agent defaults.
type SessionOpen struct {
	Shell string `json:"shell"`
	Cwd   string `json:"cwd"`
	Cols  uint16 `json:"cols"`
	Rows  uint16 `json:"rows"`
}

// ErrorMsg is the payload of an ERROR frame. Msg never carries terminal
// content.
type ErrorMsg struct {
	Code string `json:"code"`
	Msg  string `json:"msg"`
}

// HandshakePayload is the JSON carried, encrypted, in the first Noise
// handshake message (PROTOCOL.md §1.2). Empty for an ordinary connection.
type HandshakePayload struct {
	Pair *PairRequest `json:"pair,omitempty"`
}

// PairRequest redeems a pairing code from the QR (PROTOCOL.md §8).
type PairRequest struct {
	Code string `json:"code"`
	Name string `json:"name"`
}

// Error codes (PROTOCOL.md §3.2).
const (
	CodeHelloRequired      = "hello_required"
	CodeUnsupportedVersion = "unsupported_version"
	CodeBadFrame           = "bad_frame"
	CodeUnsupportedOpcode  = "unsupported_opcode"
	CodeSessionOpenFailed  = "session_open_failed"
	CodeTooManySessions    = "too_many_sessions"
	CodeUnknownSession     = "unknown_session"
)

// JSONFrame builds a control frame whose payload is v encoded as JSON.
func JSONFrame(op Opcode, sid uint8, v any) (Frame, error) {
	p, err := json.Marshal(v)
	if err != nil {
		return Frame{}, fmt.Errorf("encode %s: %w", op, err)
	}
	if len(p) > MaxPayload {
		return Frame{}, fmt.Errorf("encode %s: %w", op, ErrFrameTooLarge)
	}
	return Frame{Op: op, Session: sid, Payload: p}, nil
}

// ParseJSON decodes the JSON payload of f into v, ignoring unknown fields.
func ParseJSON(f Frame, v any) error {
	if err := json.Unmarshal(f.Payload, v); err != nil {
		return fmt.Errorf("%w: %s: %v", ErrBadPayload, f.Op, err)
	}
	return nil
}
