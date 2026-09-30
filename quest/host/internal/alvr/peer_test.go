package alvr

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func nativePeerSession(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "session.json")
	if err := NewFakeSession(path, CodecAV1); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	var doc map[string]interface{}
	json.Unmarshal(data, &doc)
	doc["server_version"] = NativeVersion
	doc["unrelated"] = "keep me"
	data, _ = json.Marshal(doc)
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestPeerTransportScope(t *testing.T) {
	for _, peer := range []string{"192.168.1.9:1234", "10.0.0.3:1", "172.16.9.2:2", "100.64.0.2:9", "[fd00::2]:2", "[::ffff:192.168.1.9]:3"} {
		if _, err := PeerAddress(peer); err != nil {
			t.Errorf("rejected %s: %v", peer, err)
		}
	}
	for _, peer := range []string{"8.8.8.8:2", "127.0.0.1:1", "169.254.1.1:3", "224.0.0.1:1", "0.0.0.0:1", "100.128.0.1:9", "[::1]:9", "[fe80::1%eth0]:9", "hostname:2"} {
		if _, err := PeerAddress(peer); !errors.Is(err, ErrPeerAddress) {
			t.Errorf("accepted %s", peer)
		}
	}
}

func TestPeerRegistrationAndReadOnlyReconnect(t *testing.T) {
	path := nativePeerSession(t)
	a := NewAdapter(path, FakeGate{})
	if err := a.EnsurePeer("quest.client", "192.168.1.9:4567"); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(path)
	var doc map[string]interface{}
	json.Unmarshal(before, &doc)
	if doc["unrelated"] != "keep me" {
		t.Fatal("lost unrelated preference")
	}
	peer := doc["client_connections"].(map[string]interface{})["quest.client"].(map[string]interface{})
	if peer["trusted"] != true || peer["connection_state"] != "Disconnected" {
		t.Fatal(peer)
	}
	a.gate = FakeGate{VRServer: true, Dashboard: true}
	if err := a.EnsurePeer("quest.client", "192.168.1.9:5000"); err != nil {
		t.Fatal(err)
	}
	if err := a.EnsurePeer("quest.client", "192.168.1.10:5000"); !errors.Is(err, ErrPeerBusy) {
		t.Fatal(err)
	}
	if err := a.EnsurePeer("other.client", "192.168.1.10:5000"); !errors.Is(err, ErrPeerBusy) {
		t.Fatal(err)
	}
	after, _ := os.ReadFile(path)
	if !bytes.Equal(before, after) {
		t.Fatal("live runtime was modified")
	}
	a.gate = FakeGate{}
	if err := a.EnsurePeer("quest.client", "192.168.1.10:5000"); err != nil {
		t.Fatal(err)
	}
	doc, _ = a.Load()
	peer = doc["client_connections"].(map[string]interface{})["quest.client"].(map[string]interface{})
	ips := peer["manual_ips"].([]interface{})
	if len(ips) != 1 || ips[0] != "192.168.1.10" {
		t.Fatal(ips)
	}
}

func TestPeerRegistrationFailuresPreserveFile(t *testing.T) {
	for _, scenario := range []string{"public", "hostname", "no gate", "probe", "dashboard", "replace", "version"} {
		t.Run(scenario, func(t *testing.T) {
			path := nativePeerSession(t)
			a := NewAdapter(path, FakeGate{})
			name, peer := "quest.client", "192.168.1.3:99"
			switch scenario {
			case "public":
				peer = "1.1.1.1:99"
			case "hostname":
				name = "../other"
			case "no gate":
				a.gate = nil
			case "probe":
				a.gate = FakeGate{VRServerE: errors.New("denied")}
			case "dashboard":
				a.gate = FakeGate{Dashboard: true}
			case "replace":
				a.replaceFile = func(string, string) error { return errors.New("locked") }
			case "version":
				NewFakeSession(path, CodecAV1)
			}
			before, _ := os.ReadFile(path)
			if err := a.EnsurePeer(name, peer); err == nil {
				t.Fatal("expected refusal")
			}
			after, _ := os.ReadFile(path)
			if !bytes.Equal(before, after) {
				t.Fatal("failed operation changed session")
			}
		})
	}
}
