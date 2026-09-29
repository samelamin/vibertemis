package discovery

import (
	"strings"
	"testing"
)

func TestRecordsContainOnlyPublicIdentity(t *testing.T) {
	pin := strings.Repeat("a", 64)
	records, err := Records(pin, "test-protocol")
	if err != nil || len(records) != 4 {
		t.Fatalf("records: %v %v", records, err)
	}
	for _, value := range records {
		if strings.Contains(value, "token") || strings.Contains(value, "PRIVATE") {
			t.Fatal("secret advertised")
		}
	}
	if records[0] != "certpin="+pin {
		t.Fatal("missing paired identity")
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
