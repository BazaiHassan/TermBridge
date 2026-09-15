// Package discovery advertises the agent on the local network with mDNS /
// DNS-SD (architecture §3.2, PROTOCOL.md §9.5), so a phone still finds the
// computer after its IP address changes.
package discovery

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"fmt"

	"github.com/grandcat/zeroconf"

	"termbridge/agent/internal/transport"
)

// Service is the DNS-SD service type.
const Service = "_termbridge._tcp"

// TXT is the record that lets a phone match the advertisement to a paired
// machine. It carries no secret: Noise authenticates the agent anyway.
func TXT(agentID ed25519.PublicKey) []string {
	return []string{"v=1", "id=" + base64.RawURLEncoding.EncodeToString(agentID)}
}

// Advertise announces the agent on every physical LAN interface until ctx
// ends. Container bridges and VPN tunnels are skipped: phones can't reach them.
func Advertise(ctx context.Context, instance string, port int, agentID ed25519.PublicKey) error {
	ifaces := transport.PhysicalInterfaces()
	if len(ifaces) == 0 {
		return fmt.Errorf("mdns: no physical network interface")
	}
	srv, err := zeroconf.Register(instance, Service, "local.", port, TXT(agentID), ifaces)
	if err != nil {
		return fmt.Errorf("mdns: %w", err)
	}
	go func() {
		<-ctx.Done()
		srv.Shutdown()
	}()
	return nil
}
