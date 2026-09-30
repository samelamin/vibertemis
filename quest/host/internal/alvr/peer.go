package alvr

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"regexp"
)

var (
	ErrPeerAddress = errors.New("VR requires a local network or encrypted VPN connection")
	ErrPeerBusy    = errors.New("close SteamVR and ALVR Dashboard once, then reconnect to register this headset address")
	peerHostname   = regexp.MustCompile(`\A[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}\z`)
)

// PeerAddress accepts a transport peer, never an address supplied in JSON or
// proxy headers. Public GameStream forwarding is not a secure ALVR transport.
func PeerAddress(remote string) (string, error) {
	peer, err := netip.ParseAddrPort(remote)
	if err != nil {
		return "", ErrPeerAddress
	}
	ip := peer.Addr().Unmap()
	if ip.Zone() != "" || ip.IsLoopback() || (!ip.IsPrivate() && !netip.MustParsePrefix("100.64.0.0/10").Contains(ip)) {
		return "", ErrPeerAddress
	}
	return ip.String(), nil
}

// EnsurePeer is called only inside the authenticated start transaction. It
// never edits a live runtime's session. An already configured peer can reconnect
// read-only; new peers/address changes require an idle runtime.
func (a *Adapter) EnsurePeer(hostname, remote string) error {
	if !peerHostname.MatchString(hostname) {
		return fmt.Errorf("invalid headset hostname")
	}
	ip, err := PeerAddress(remote)
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	doc, err := a.loadLocked()
	if err != nil {
		return err
	}
	if sessionVersion(doc) != NativeVersion {
		return ErrVersionMismatch
	}
	clients, ok := doc["client_connections"].(map[string]interface{})
	if !ok {
		return ErrSchemaUnrecognized
	}
	if peer, ok := clients[hostname].(map[string]interface{}); ok && peer["trusted"] == true {
		if ips, ok := peer["manual_ips"].([]interface{}); ok {
			for _, value := range ips {
				if value == ip {
					return nil
				}
			}
		}
	}
	if err := a.peerIdle(); err != nil {
		return err
	}
	// Keep unrelated peers/preferences. This trust is granted by the existing
	// companion pairing, not by unauthenticated native discovery.
	peer, ok := clients[hostname].(map[string]interface{})
	if !ok {
		peer = map[string]interface{}{"display_name": "Vibertemis headset"}
	}
	peer["trusted"] = true
	peer["manual_ips"] = []string{ip}
	peer["current_ip"] = nil
	peer["connection_state"] = "Disconnected"
	clients[hostname] = peer
	data, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(parentDir(a.path), ".peer-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := a.peerIdle(); err != nil {
		return err
	}
	if err := a.confirmSnapshotMatchesLocked(); err != nil {
		return err
	}
	replace := a.replaceFile
	if replace == nil {
		replace = os.Rename
	}
	if err := replace(f.Name(), a.path); err != nil {
		return fmt.Errorf("%w: %v", ErrWrite, err)
	}
	_, err = a.loadLocked()
	return err
}

func (a *Adapter) peerIdle() error {
	if a.gate == nil {
		return ErrProcessProbe
	}
	vr, err := a.gate.VRServerRunning()
	if err != nil {
		return fmt.Errorf("%w: %v", ErrProcessProbe, err)
	}
	dash, err := a.gate.DashboardRunning()
	if err != nil {
		return fmt.Errorf("%w: %v", ErrProcessProbe, err)
	}
	if vr || dash {
		return ErrPeerBusy
	}
	return nil
}
