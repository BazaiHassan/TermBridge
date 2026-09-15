package transport

import (
	"net/netip"
	"strings"
	"testing"
)

func TestIsLAN(t *testing.T) {
	cases := map[string]bool{
		"127.0.0.1":          true,
		"192.168.1.20":       true,
		"10.0.0.7":           true,
		"172.16.4.4":         true,
		"fd12:3456::1":       true,
		"fe80::1":            true,
		"::1":                true,
		"::ffff:192.168.1.2": true,
		"8.8.8.8":            false,
		"100.64.0.1":         false, // CGNAT
		"2001:4860::8888":    false,
	}
	for s, want := range cases {
		if got := IsLAN(netip.MustParseAddr(s)); got != want {
			t.Errorf("IsLAN(%s) = %v, want %v", s, got, want)
		}
	}
}

func TestLANListenAddrsNeverWildcard(t *testing.T) {
	addrs, err := LANListenAddrs(DefaultPort)
	if err != nil {
		t.Fatal(err)
	}
	sawLoopback := false
	for _, a := range addrs {
		ap := netip.MustParseAddrPort(a)
		if ap.Addr().IsUnspecified() {
			t.Fatalf("wildcard address %s", a)
		}
		if ap.Port() != DefaultPort {
			t.Fatalf("port in %s", a)
		}
		if strings.HasPrefix(a, "127.") {
			sawLoopback = true
		}
	}
	if !sawLoopback {
		t.Fatalf("loopback missing from %v", addrs)
	}
}
