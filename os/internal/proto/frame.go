package proto

import (
	"encoding/binary"
	"errors"
	"fmt"
)

const (
	// HeaderSize is the opcode byte plus the sessionID byte.
	HeaderSize = 2
	// MaxPayload keeps header + payload + the 16-byte AEAD tag inside one
	// 65535-byte Noise transport message (ADR 0003).
	MaxPayload = 65535 - 16 - HeaderSize
	// MaxFrame is the size of the largest valid wire frame.
	MaxFrame = HeaderSize + MaxPayload
	// MaxMessage is the largest Noise transport message: MaxFrame + AEAD tag.
	MaxMessage = 65535
	// ControlSession is the sessionID of connection-level frames.
	ControlSession uint8 = 0
)

// Decoding errors; match with errors.Is.
var (
	ErrShortFrame    = errors.New("proto: frame shorter than header")
	ErrFrameTooLarge = errors.New("proto: payload exceeds MaxPayload")
	ErrBadPayload    = errors.New("proto: malformed payload")
)

// Frame is one protocol frame. After Decode, Payload aliases the decoded
// buffer; copy it before that buffer is reused.
type Frame struct {
	Op      Opcode
	Session uint8
	Payload []byte
}

// Decode parses one wire frame without copying.
func Decode(b []byte) (Frame, error) {
	if len(b) < HeaderSize {
		return Frame{}, ErrShortFrame
	}
	if len(b) > MaxFrame {
		return Frame{}, fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, len(b)-HeaderSize)
	}
	return Frame{Op: Opcode(b[0]), Session: b[1], Payload: b[HeaderSize:]}, nil
}

// AppendEncode appends the wire encoding of f to dst.
func AppendEncode(dst []byte, f Frame) ([]byte, error) {
	if len(f.Payload) > MaxPayload {
		return dst, fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, len(f.Payload))
	}
	dst = append(dst, byte(f.Op), f.Session)
	return append(dst, f.Payload...), nil
}

// Encode returns the wire encoding of f in a new slice.
func Encode(f Frame) ([]byte, error) {
	return AppendEncode(make([]byte, 0, HeaderSize+len(f.Payload)), f)
}

// fixedPayloadLen reports the payload length the spec mandates for op.
func fixedPayloadLen(op Opcode) (int, bool) {
	switch op {
	case OpResize, OpSessionExit, OpSessionAttach:
		return 4, true
	case OpSessionOpened:
		return 1, true
	case OpSessionClose, OpAttached:
		return 0, true
	case OpPing, OpPong:
		return 8, true
	}
	return 0, false
}

// Validate checks that fixed-size opcodes carry exactly the mandated payload.
func (f Frame) Validate() error {
	if n, ok := fixedPayloadLen(f.Op); ok && len(f.Payload) != n {
		return fmt.Errorf("%w: %s payload is %d bytes, want %d", ErrBadPayload, f.Op, len(f.Payload), n)
	}
	return nil
}

// Data builds a DATA frame. p is not copied.
func Data(sid uint8, p []byte) Frame {
	return Frame{Op: OpData, Session: sid, Payload: p}
}

// Resize builds a RESIZE frame.
func Resize(sid uint8, cols, rows uint16) Frame {
	p := make([]byte, 4)
	binary.BigEndian.PutUint16(p, cols)
	binary.BigEndian.PutUint16(p[2:], rows)
	return Frame{Op: OpResize, Session: sid, Payload: p}
}

// ParseResize decodes a RESIZE payload.
func ParseResize(f Frame) (cols, rows uint16, err error) {
	if err := f.Validate(); err != nil {
		return 0, 0, err
	}
	return binary.BigEndian.Uint16(f.Payload), binary.BigEndian.Uint16(f.Payload[2:]), nil
}

// SessionAttach builds a SESSION_ATTACH frame: re-attach sid at this size.
func SessionAttach(sid uint8, cols, rows uint16) Frame {
	f := Resize(sid, cols, rows)
	f.Op = OpSessionAttach
	return f
}

// ParseSessionAttach decodes the size carried by SESSION_ATTACH.
func ParseSessionAttach(f Frame) (cols, rows uint16, err error) { return ParseResize(f) }

// SessionAttached confirms a re-attach; replayed output follows as DATA.
func SessionAttached(sid uint8) Frame {
	return Frame{Op: OpAttached, Session: sid}
}

// SessionOpened builds the reply to SESSION_OPEN carrying the new session ID.
func SessionOpened(newID uint8) Frame {
	return Frame{Op: OpSessionOpened, Session: ControlSession, Payload: []byte{newID}}
}

// ParseSessionOpened decodes the new session ID; 0 is reserved and rejected.
func ParseSessionOpened(f Frame) (uint8, error) {
	if err := f.Validate(); err != nil {
		return 0, err
	}
	if f.Payload[0] == ControlSession {
		return 0, fmt.Errorf("%w: session ID 0 is reserved", ErrBadPayload)
	}
	return f.Payload[0], nil
}

// SessionClose builds a SESSION_CLOSE frame.
func SessionClose(sid uint8) Frame {
	return Frame{Op: OpSessionClose, Session: sid}
}

// SessionExit builds a SESSION_EXIT frame.
func SessionExit(sid uint8, code int32) Frame {
	p := make([]byte, 4)
	binary.BigEndian.PutUint32(p, uint32(code))
	return Frame{Op: OpSessionExit, Session: sid, Payload: p}
}

// ParseSessionExit decodes the exit code of a SESSION_EXIT frame.
func ParseSessionExit(f Frame) (int32, error) {
	if err := f.Validate(); err != nil {
		return 0, err
	}
	return int32(binary.BigEndian.Uint32(f.Payload)), nil
}

// Ping builds a PING frame carrying a millisecond timestamp.
func Ping(ts uint64) Frame { return timestampFrame(OpPing, ts) }

// Pong builds a PONG frame echoing a PING timestamp.
func Pong(ts uint64) Frame { return timestampFrame(OpPong, ts) }

func timestampFrame(op Opcode, ts uint64) Frame {
	p := make([]byte, 8)
	binary.BigEndian.PutUint64(p, ts)
	return Frame{Op: op, Session: ControlSession, Payload: p}
}

// ParseTimestamp decodes the timestamp of a PING or PONG frame.
func ParseTimestamp(f Frame) (uint64, error) {
	if err := f.Validate(); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(f.Payload), nil
}
