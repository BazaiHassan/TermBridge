package proto

// Relay control channel (PROTOCOL.md §9.2), shared by the relay and the agent.
const (
	RelaySubprotocol = "termbridge.relay.v1"
	RelayAuthPrefix  = "TermBridge relay auth v1\n"
)

// RelayMsg is one JSON message on the agent's relay control channel.
type RelayMsg struct {
	Type       string `json:"type"`
	Nonce      string `json:"nonce,omitempty"`
	AgentID    string `json:"agent_id,omitempty"`
	Sig        string `json:"sig,omitempty"`
	Version    string `json:"version,omitempty"`
	ObservedIP string `json:"observed_ip,omitempty"`
	Token      string `json:"token,omitempty"`
	Port       int    `json:"port,omitempty"`
	Reachable  bool   `json:"reachable,omitempty"`
}

// Addrs are the agent's current addresses, sent in HELLO_ACK so the phone
// can go direct next time (PROTOCOL.md §9.4).
type Addrs struct {
	LAN []string `json:"lan,omitempty"`
	WAN []string `json:"wan,omitempty"`
}
