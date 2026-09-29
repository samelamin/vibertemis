// Package discovery advertises endpoint hints only. TLS pinning and HMAC
// authentication remain mandatory; no credentials enter multicast records.
package discovery

import (
	"fmt"
	"github.com/libp2p/zeroconf/v2"
	"net"
	"net/netip"
	"regexp"
)

const Service = "_vibertemis-vr._tcp"

func Records(pin, protocol string) ([]string, error) {
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(pin) {
		return nil, fmt.Errorf("invalid discovery identity")
	}
	return []string{"certpin=" + pin, "protocol=" + protocol, "version=0.1.0.4", "sequence=4"}, nil
}
func Start(address, pin, protocol string) (*zeroconf.Server, error) {
	endpoint, err := netip.ParseAddrPort(address)
	if err != nil || !endpoint.Addr().Is4() || endpoint.Addr().IsLoopback() || endpoint.Port() == 0 {
		return nil, fmt.Errorf("discovery needs a reachable local IPv4 endpoint")
	}
	txt, err := Records(pin, protocol)
	if err != nil {
		return nil, err
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			prefix, err := netip.ParsePrefix(address.String())
			if err != nil || prefix.Addr() != endpoint.Addr() {
				continue
			}
			return zeroconf.RegisterProxy("Vibertemis "+pin[:12], Service, "local.", int(endpoint.Port()),
				"vibertemis-"+pin[:12]+".local.", []string{endpoint.Addr().String()}, txt, []net.Interface{iface})
		}
	}
	return nil, fmt.Errorf("advertised address is not assigned to a local adapter")
}
