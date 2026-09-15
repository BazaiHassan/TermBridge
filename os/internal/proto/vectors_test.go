package proto

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// The vectors are shared with the Kotlin codec (mobile/core-proto); both
// suites must pass for the two implementations to be byte-compatible.
var vectorsPath = filepath.Join("..", "..", "..", "docs", "vectors", "frames.json")

type vectorFile struct {
	MaxPayload int `json:"max_payload"`
	Raw        []struct {
		Name       string `json:"name"`
		Op         uint8  `json:"op"`
		Sid        uint8  `json:"sid"`
		PayloadHex string `json:"payload_hex"`
		WireHex    string `json:"wire_hex"`
	} `json:"raw"`
	Typed   []typedVector `json:"typed"`
	Invalid []struct {
		Name    string `json:"name"`
		WireHex string `json:"wire_hex"`
		Error   string `json:"error"`
	} `json:"invalid"`
	Generated []struct {
		Name       string `json:"name"`
		Op         uint8  `json:"op"`
		Sid        uint8  `json:"sid"`
		PayloadLen int    `json:"payload_len"`
		Fill       byte   `json:"fill"`
		Error      string `json:"error"`
	} `json:"generated"`
}

type typedVector struct {
	Name     string          `json:"name"`
	Kind     string          `json:"kind"`
	Sid      uint8           `json:"sid"`
	WireHex  string          `json:"wire_hex"`
	JSON     bool            `json:"json"`
	Cols     uint16          `json:"cols"`
	Rows     uint16          `json:"rows"`
	NewSid   uint8           `json:"new_sid"`
	Code     json.RawMessage `json:"code"` // int for session_exit, string for error
	Ts       string          `json:"ts"`
	V        int             `json:"v"`
	Client   string          `json:"client"`
	Agent    string          `json:"agent"`
	OS       string          `json:"os"`
	Hostname string          `json:"hostname"`
	Shell    string          `json:"shell"`
	Cwd      string          `json:"cwd"`
	Msg      string          `json:"msg"`
}

func loadVectors(t *testing.T) vectorFile {
	t.Helper()
	b, err := os.ReadFile(vectorsPath)
	if err != nil {
		t.Fatalf("read vectors: %v", err)
	}
	var vf vectorFile
	if err := json.Unmarshal(b, &vf); err != nil {
		t.Fatalf("parse vectors: %v", err)
	}
	return vf
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func TestVectorsMaxPayload(t *testing.T) {
	if vf := loadVectors(t); vf.MaxPayload != MaxPayload {
		t.Fatalf("vectors max_payload = %d, codec MaxPayload = %d", vf.MaxPayload, MaxPayload)
	}
}

func TestVectorsRaw(t *testing.T) {
	for _, v := range loadVectors(t).Raw {
		t.Run(v.Name, func(t *testing.T) {
			wire, payload := mustHex(t, v.WireHex), mustHex(t, v.PayloadHex)
			f, err := Decode(wire)
			if err != nil {
				t.Fatal(err)
			}
			if uint8(f.Op) != v.Op || f.Session != v.Sid || !bytes.Equal(f.Payload, payload) {
				t.Fatalf("decoded %+v", f)
			}
			enc, err := Encode(Frame{Op: Opcode(v.Op), Session: v.Sid, Payload: payload})
			if err != nil || !bytes.Equal(enc, wire) {
				t.Fatalf("encoded %x, %v; want %x", enc, err, wire)
			}
		})
	}
}

func TestVectorsTyped(t *testing.T) {
	for _, v := range loadVectors(t).Typed {
		t.Run(v.Name, func(t *testing.T) {
			wire := mustHex(t, v.WireHex)
			built := buildTyped(t, v)
			enc, err := Encode(built)
			if err != nil {
				t.Fatal(err)
			}
			if !v.JSON && !bytes.Equal(enc, wire) {
				t.Fatalf("encoded %x, want %x", enc, wire)
			}
			for _, b := range [][]byte{wire, enc} { // JSON kinds: compare by value
				f, err := Decode(b)
				if err != nil {
					t.Fatal(err)
				}
				checkTyped(t, v, f)
			}
		})
	}
}

func TestVectorsInvalid(t *testing.T) {
	for _, v := range loadVectors(t).Invalid {
		t.Run(v.Name, func(t *testing.T) {
			f, err := Decode(mustHex(t, v.WireHex))
			switch v.Error {
			case "short_frame":
				if !errors.Is(err, ErrShortFrame) {
					t.Fatalf("err = %v, want short frame", err)
				}
			case "bad_payload":
				if err != nil {
					t.Fatalf("decode: %v", err)
				}
				if perr := parsePayload(f); !errors.Is(perr, ErrBadPayload) {
					t.Fatalf("parse err = %v, want bad payload", perr)
				}
			default:
				t.Fatalf("unknown expected error %q", v.Error)
			}
		})
	}
}

func TestVectorsGenerated(t *testing.T) {
	for _, v := range loadVectors(t).Generated {
		t.Run(v.Name, func(t *testing.T) {
			payload := bytes.Repeat([]byte{v.Fill}, v.PayloadLen)
			wire := append([]byte{v.Op, v.Sid}, payload...)
			_, encErr := Encode(Frame{Op: Opcode(v.Op), Session: v.Sid, Payload: payload})
			_, decErr := Decode(wire)
			if v.Error == "frame_too_large" {
				if !errors.Is(encErr, ErrFrameTooLarge) || !errors.Is(decErr, ErrFrameTooLarge) {
					t.Fatalf("encode %v, decode %v; want frame too large", encErr, decErr)
				}
				return
			}
			if encErr != nil || decErr != nil {
				t.Fatalf("encode %v, decode %v", encErr, decErr)
			}
		})
	}
}

func buildTyped(t *testing.T, v typedVector) Frame {
	t.Helper()
	var (
		f   Frame
		err error
	)
	switch v.Kind {
	case "resize":
		f = Resize(v.Sid, v.Cols, v.Rows)
	case "session_opened":
		f = SessionOpened(v.NewSid)
	case "session_close":
		f = SessionClose(v.Sid)
	case "session_attach":
		f = SessionAttach(v.Sid, v.Cols, v.Rows)
	case "session_attached":
		f = SessionAttached(v.Sid)
	case "session_exit":
		var code int32
		if err := json.Unmarshal(v.Code, &code); err != nil {
			t.Fatal(err)
		}
		f = SessionExit(v.Sid, code)
	case "ping", "pong":
		ts, err := strconv.ParseUint(v.Ts, 10, 64)
		if err != nil {
			t.Fatal(err)
		}
		f = map[string]func(uint64) Frame{"ping": Ping, "pong": Pong}[v.Kind](ts)
	case "hello":
		f, err = JSONFrame(OpHello, v.Sid, Hello{V: v.V, Client: v.Client})
	case "hello_ack":
		f, err = JSONFrame(OpHelloAck, v.Sid, HelloAck{V: v.V, Agent: v.Agent, OS: v.OS, Hostname: v.Hostname})
	case "session_open":
		f, err = JSONFrame(OpSessionOpen, v.Sid, SessionOpen{Shell: v.Shell, Cwd: v.Cwd, Cols: v.Cols, Rows: v.Rows})
	case "error":
		f, err = JSONFrame(OpError, v.Sid, ErrorMsg{Code: errorCode(t, v), Msg: v.Msg})
	default:
		t.Fatalf("unknown kind %q", v.Kind)
	}
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func checkTyped(t *testing.T, v typedVector, f Frame) {
	t.Helper()
	if f.Session != v.Sid {
		t.Fatalf("session = %d, want %d", f.Session, v.Sid)
	}
	built := buildTyped(t, v)
	if f.Op != built.Op {
		t.Fatalf("op = %s, want %s", f.Op, built.Op)
	}
	if err := parsePayload(f); err != nil {
		t.Fatal(err)
	}
	var got, want any
	switch f.Op {
	case OpHello:
		got, want = decodeInto[Hello](t, f), decodeInto[Hello](t, built)
	case OpHelloAck:
		got, want = decodeInto[HelloAck](t, f), decodeInto[HelloAck](t, built)
	case OpSessionOpen:
		got, want = decodeInto[SessionOpen](t, f), decodeInto[SessionOpen](t, built)
	case OpError:
		got, want = decodeInto[ErrorMsg](t, f), decodeInto[ErrorMsg](t, built)
	default:
		got, want = hex.EncodeToString(f.Payload), hex.EncodeToString(built.Payload)
	}
	if got != want {
		t.Fatalf("payload = %+v, want %+v", got, want)
	}
}

func decodeInto[T any](t *testing.T, f Frame) T {
	t.Helper()
	var v T
	if err := ParseJSON(f, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func errorCode(t *testing.T, v typedVector) string {
	t.Helper()
	var s string
	if err := json.Unmarshal(v.Code, &s); err != nil {
		t.Fatal(err)
	}
	return s
}

// parsePayload applies the typed decoder for f's opcode.
func parsePayload(f Frame) error {
	if err := f.Validate(); err != nil {
		return err
	}
	var err error
	switch f.Op {
	case OpResize:
		_, _, err = ParseResize(f)
	case OpSessionAttach:
		_, _, err = ParseSessionAttach(f)
	case OpSessionOpened:
		_, err = ParseSessionOpened(f)
	case OpSessionExit:
		_, err = ParseSessionExit(f)
	case OpPing, OpPong:
		_, err = ParseTimestamp(f)
	case OpHello:
		err = ParseJSON(f, new(Hello))
	case OpHelloAck:
		err = ParseJSON(f, new(HelloAck))
	case OpSessionOpen:
		err = ParseJSON(f, new(SessionOpen))
	case OpError:
		err = ParseJSON(f, new(ErrorMsg))
	}
	return err
}
