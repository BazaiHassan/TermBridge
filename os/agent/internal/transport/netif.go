package transport

import (
	"fmt"
	"net"
	"net/netip"
	"slices"
)

// DefaultPort is the agent's LAN port.
const DefaultPort = 7423

// IsLAN reports whether a is local by definition: loopback, private
// (RFC 1918, RFC 4193) or link-local. Carrier-grade NAT space (100.64/10) is
// deliberately excluded: on a phone hotspot it is shared with strangers.
func IsLAN(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsLoopback() || a.IsPrivate() || a.IsLinkLocalUnicast()
}

// LANListenAddrs returns host:port for every loopback and private unicast
// address on interfaces that are up. It never returns a wildcard address
// (architecture §8.5). Link-local addresses are skipped because binding them
// requires a zone.
func LANListenAddrs(port int) ([]string, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list interfaces: %w", err)
	}
	var out []string
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			prefix, err := netip.ParsePrefix(a.String())
			if err != nil {
				continue
			}
			ip := prefix.Addr()
			if ip.IsLoopback() || ip.IsPrivate() {
				out = append(out, netip.AddrPortFrom(ip, uint16(port)).String())
			}
		}
	}
	slices.Sort(out)
	out = slices.Compact(out)
	if len(out) == 0 {
		return nil, fmt.Errorf("no loopback or private LAN address found")
	}
	return out, nil
}

// virtualPrefixes name interfaces a phone on the same Wi-Fi cannot reach:
// container bridges, VM networks and VPN tunnels.
var virtualPrefixes = []string{
	"docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "podman", "cni", "flannel", "lxc", "lxd",
	"tun", "tap", "wg", "zt", "tailscale", "nekoray", "utun",
}

// PhoneAddrs returns the addresses to advertise in the pairing QR code:
// private IPv4 addresses on physical interfaces that are up.
func PhoneAddrs(port int) ([]string, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list interfaces: %w", err)
	}
	var out []string
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 || isVirtual(ifc.Name) {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			prefix, err := netip.ParsePrefix(a.String())
			if err != nil {
				continue
			}
			if ip := prefix.Addr(); ip.Is4() && ip.IsPrivate() {
				out = append(out, netip.AddrPortFrom(ip, uint16(port)).String())
			}
		}
	}
	slices.Sort(out)
	return slices.Compact(out), nil
}

func isVirtual(name string) bool {
	for _, p := range virtualPrefixes {
		if len(name) >= len(p) && name[:len(p)] == p {
			return true
		}
	}
	return false
}
