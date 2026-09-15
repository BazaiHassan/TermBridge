// Package proto implements TermBridge v1 framing as specified in
// docs/PROTOCOL.md. The agent, the relay and test clients share it; the Kotlin
// codec in mobile/core-proto mirrors it byte for byte, and both are checked
// against docs/vectors/frames.json.
package proto

import "fmt"

// Version is the protocol version exchanged in HELLO and HELLO_ACK.
const Version = 1

// Subprotocol is the WebSocket subprotocol a client must negotiate.
const Subprotocol = "termbridge.v1"

// Opcode identifies the type of a frame.
type Opcode uint8

// Opcodes defined by protocol v1 (PROTOCOL.md §3).
const (
	OpData          Opcode = 0x01
	OpResize        Opcode = 0x02
	OpSessionOpen   Opcode = 0x10
	OpSessionOpened Opcode = 0x11
	OpSessionClose  Opcode = 0x12
	OpSessionExit   Opcode = 0x13
	OpPing          Opcode = 0x20
	OpPong          Opcode = 0x21
	OpHello         Opcode = 0x30
	OpHelloAck      Opcode = 0x31
	OpError         Opcode = 0x40
)

var opNames = map[Opcode]string{
	OpData:          "DATA",
	OpResize:        "RESIZE",
	OpSessionOpen:   "SESSION_OPEN",
	OpSessionOpened: "SESSION_OPENED",
	OpSessionClose:  "SESSION_CLOSE",
	OpSessionExit:   "SESSION_EXIT",
	OpPing:          "PING",
	OpPong:          "PONG",
	OpHello:         "HELLO",
	OpHelloAck:      "HELLO_ACK",
	OpError:         "ERROR",
}

func (o Opcode) String() string {
	if n, ok := opNames[o]; ok {
		return n
	}
	return fmt.Sprintf("OP_0x%02X", uint8(o))
}

// Known reports whether o is defined by protocol v1.
func (o Opcode) Known() bool {
	_, ok := opNames[o]
	return ok
}
