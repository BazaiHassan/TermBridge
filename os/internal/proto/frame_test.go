package proto

import (
	"bytes"
	"errors"
	"testing"
)

func TestEncodeDecodeRoundTrip(t *testing.T) {
	in := Data(3, []byte("ls -la\r"))
	wire, err := Encode(in)
	if err != nil {
		t.Fatal(err)
	}
	if want := append([]byte{0x01, 0x03}, "ls -la\r"...); !bytes.Equal(wire, want) {
		t.Fatalf("wire = %x, want %x", wire, want)
	}
	out, err := Decode(wire)
	if err != nil {
		t.Fatal(err)
	}
	if out.Op != OpData || out.Session != 3 || !bytes.Equal(out.Payload, in.Payload) {
		t.Fatalf("decoded %+v", out)
	}
}

func TestDecodeRejectsMalformed(t *testing.T) {
	if _, err := Decode(nil); !errors.Is(err, ErrShortFrame) {
		t.Errorf("nil: err = %v", err)
	}
	if _, err := Decode([]byte{0x01}); !errors.Is(err, ErrShortFrame) {
		t.Errorf("1 byte: err = %v", err)
	}
	if _, err := Decode(make([]byte, MaxFrame+1)); !errors.Is(err, ErrFrameTooLarge) {
		t.Errorf("oversize: err = %v", err)
	}
	if _, err := Decode(make([]byte, MaxFrame)); err != nil {
		t.Errorf("max size: err = %v", err)
	}
}

func TestEncodeRejectsOversizePayload(t *testing.T) {
	_, err := Encode(Data(1, make([]byte, MaxPayload+1)))
	if !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("err = %v", err)
	}
}

func TestMaxFrameFitsNoiseMessage(t *testing.T) {
	const noiseMax, tag = 65535, 16
	if MaxFrame+tag != noiseMax {
		t.Fatalf("MaxFrame+tag = %d, want %d", MaxFrame+tag, noiseMax)
	}
}

func TestTypedHelpersRoundTrip(t *testing.T) {
	cols, rows, err := ParseResize(Resize(1, 132, 43))
	if err != nil || cols != 132 || rows != 43 {
		t.Errorf("resize = %d×%d, %v", cols, rows, err)
	}
	code, err := ParseSessionExit(SessionExit(1, -1))
	if err != nil || code != -1 {
		t.Errorf("exit = %d, %v", code, err)
	}
	id, err := ParseSessionOpened(SessionOpened(9))
	if err != nil || id != 9 {
		t.Errorf("opened = %d, %v", id, err)
	}
	ts, err := ParseTimestamp(Ping(1<<63 + 5))
	if err != nil || ts != 1<<63+5 {
		t.Errorf("ping = %d, %v", ts, err)
	}
}

func TestValidateFixedPayloads(t *testing.T) {
	bad := []Frame{
		{Op: OpResize, Payload: []byte{0, 80, 0}},
		{Op: OpSessionClose, Session: 1, Payload: []byte{1}},
		{Op: OpPong, Payload: make([]byte, 9)},
	}
	for _, f := range bad {
		if err := f.Validate(); !errors.Is(err, ErrBadPayload) {
			t.Errorf("%s: err = %v", f.Op, err)
		}
	}
	if err := Data(1, nil).Validate(); err != nil {
		t.Errorf("empty DATA: %v", err)
	}
}

func TestOpcodeString(t *testing.T) {
	if OpHelloAck.String() != "HELLO_ACK" || Opcode(0x7F).String() != "OP_0x7F" {
		t.Fatal("unexpected opcode names")
	}
	if Opcode(0x7F).Known() || !OpError.Known() {
		t.Fatal("Known() wrong")
	}
}

func TestBufferPoolClasses(t *testing.T) {
	for _, n := range []int{1, 256, 257, 4096, MaxFrame} {
		b := GetBuffer(n)
		if cap(*b) < n || len(*b) != 0 {
			t.Fatalf("GetBuffer(%d): len %d cap %d", n, len(*b), cap(*b))
		}
		PutBuffer(b)
	}
}

func BenchmarkAppendEncodeData(b *testing.B) {
	payload := bytes.Repeat([]byte("x"), 1024)
	b.ReportAllocs()
	for range b.N {
		buf := GetBuffer(HeaderSize + len(payload))
		*buf, _ = AppendEncode(*buf, Data(1, payload))
		PutBuffer(buf)
	}
}
