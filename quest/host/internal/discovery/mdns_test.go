package discovery

import (
	"strings"
	"testing"
)

func TestRecordsContainOnlyPublicIdentity(t *testing.T) {
	pin := strings.Repeat("a", 64)
	records, err := Records(pin, "test-protocol")
	if err != nil {
		t.Fatalf("records: %v %v", records, err)
	}
	// The records API now emits 5 entries: certpin, protocol,
	// version, sequence, name. The native protocol/pin stay
	// unchanged; this preview release advertises 0.1.0.9.
	if len(records) != 5 {
		t.Fatalf("records count = %d, want 5 (records=%v)", len(records), records)
	}
	for _, value := range records {
		if strings.Contains(value, "token") || strings.Contains(value, "PRIVATE") {
			t.Fatal("secret advertised")
		}
	}
	want := map[string]string{
		"certpin":  pin,
		"protocol": "test-protocol",
		"version":  "0.1.0.9",
		"sequence": "9",
	}
	for _, value := range records {
		key, rest, ok := strings.Cut(value, "=")
		if !ok {
			t.Fatalf("record %q missing =", value)
		}
		if w, present := want[key]; present {
			if rest != w {
				t.Fatalf("%s mismatch: got %q, want %q", key, rest, w)
			}
			delete(want, key)
		} else if key != "name" {
			t.Fatalf("unexpected record key %q in %v", key, records)
		} else {
			if rest == "" {
				t.Fatalf("name= must be non-empty when advertised (records=%v)", records)
			}
			if len(rest) > hostNameMaxLen {
				t.Fatalf("name= exceeds %d bytes: %q", hostNameMaxLen, rest)
			}
			if strings.ContainsAny(rest, "\n\r\t\x00") {
				t.Fatalf("name= contains control chars: %q", rest)
			}
		}
	}
	for key := range want {
		t.Fatalf("missing record key %q (records=%v)", key, records)
	}
}

func TestRecordsSanitizeHostName(t *testing.T) {
	pin := strings.Repeat("a", 64)
	records, err := Records(pin, "p")
	if err != nil {
		t.Fatal(err)
	}
	var name string
	for _, v := range records {
		if strings.HasPrefix(v, "name=") {
			name = strings.TrimPrefix(v, "name=")
		}
	}
	if name == "" {
		// hostname() can legitimately return "" in some test
		// sandboxes; the absence of name= is documented and
		// accepted on the client side.
		t.Skip("hostname() returned empty in this sandbox")
	}
	for _, r := range name {
		if r < 0x20 || r > 0x7e {
			t.Fatalf("non-printable byte in name %q", name)
		}
	}
	if len(name) > hostNameMaxLen {
		t.Fatalf("name length %d exceeds cap %d", len(name), hostNameMaxLen)
	}
}

func TestInvalidEndpointsNeverStartAdvertisement(t *testing.T) {
	for _, address := range []string{"127.0.0.1:28540", "0.0.0.0:0", "https://example.com", "[::1]:28540"} {
		if s, err := Start(address, strings.Repeat("a", 64), "p"); err == nil {
			s.Shutdown()
			t.Fatalf("accepted %s", address)
		}
	}
	if _, err := Records("invalid", "p"); err == nil {
		t.Fatal("bad pin accepted")
	}
}
