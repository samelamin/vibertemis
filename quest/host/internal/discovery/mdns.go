// Package discovery advertises endpoint hints only. TLS pinning and HMAC
// authentication remain mandatory; no credentials enter multicast records.
package discovery

import (
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/libp2p/zeroconf/v2"
	"net"
	"net/netip"
)

const Service = "_vibertemis-vr._tcp"

// hostNameMaxLen bounds the TXT `name` value so a malformed or
// hostile hostname cannot bloat the multicast record. 32 bytes
// matches the renderer-side UI label cap in this preview; the
// underlying mDNS wire format permits longer TXT strings, so this
// limit is a Vibertemis UI choice, not a universal mDNS rule.
const hostNameMaxLen = 32

// hostNamePattern restricts the hostname to a printable ASCII
// label: letters, digits, dot, dash, underscore. Anything else
// is replaced with `_` so a hostile hostname cannot inject
// control bytes into the multicast TXT.
var hostNamePattern = regexp.MustCompile(`[^A-Za-z0-9._-]`)

// sanitizedHostName returns a bounded, printable hostname for
// the TXT `name` field. The value is display-only; the renderer
// must never use it to authorize a connection.
func sanitizedHostName() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		return ""
	}
	host = hostNamePattern.ReplaceAllString(host, "_")
	if len(host) > hostNameMaxLen {
		host = host[:hostNameMaxLen]
	}
	return host
}

// Records returns the TXT entries the companion advertises over
// mDNS. Every entry is an untrusted hint the client uses to
// filter and surface metadata: `certpin`, `protocol`,
// `version`, `sequence`, and the sanitized `name` are all
// display-only and never carry credentials or the pairing
// token. No TXT field is a trust anchor. Initial trust is
// established by the observed TLS certificate on the connect
// flow, the canonical pairing transcript, and human approval on
// the Windows panel; subsequent connects authenticate against
// the saved pin. A malicious mDNS responder may rewrite any
// hint; it must not be able to convince a renderer to bypass
// the handshake.
//
// Adding the `name` entry is a Records API change: callers that
// assumed the legacy 4-tuple must read all entries and key off
// the prefix (`certpin=`, `protocol=`, `version=`, `sequence=`,
// `name=`). The native protocol/pin stay unchanged and the
// version is bumped to `0.1.0.9` for this preview release.
func Records(pin, protocol string) ([]string, error) {
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(pin) {
		return nil, fmt.Errorf("invalid discovery identity")
	}
	records := []string{"certpin=" + pin, "protocol=" + protocol, "version=0.1.0.9", "sequence=9"}
	if name := sanitizedHostName(); name != "" {
		records = append(records, "name="+name)
	}
	return records, nil
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
			display := "Vibertemis " + pin[:12]
			if name := sanitizedHostName(); name != "" {
				display = display + " (" + name + ")"
			}
			return zeroconf.RegisterProxy(display, Service, "local.", int(endpoint.Port()),
				"vibertemis-"+pin[:12]+".local.", strings.Split(endpoint.Addr().String(), ","), txt, []net.Interface{iface})
		}
	}
	return nil, fmt.Errorf("advertised address is not assigned to a local adapter")
}
