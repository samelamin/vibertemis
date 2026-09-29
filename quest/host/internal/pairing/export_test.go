package pairing

import (
	"bytes"
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestExportFileIsPrivateUTF8AndOmitsPrivateKey(t *testing.T) {
	dir := t.TempDir()
	state, err := Generate()
	if err != nil {
		t.Fatal(err)
	}
	for _, host := range []string{"192.168.1.2:28540", "100.64.0.2:28540"} {
		path, err := SaveExport(dir, state, host)
		if err != nil {
			t.Fatal(err)
		}
		if err := verifyFileIsPrivate(path); err != nil {
			t.Fatal(err)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if bytes.HasPrefix(data, []byte{0xef, 0xbb, 0xbf}) || bytes.Contains(data, []byte{0}) {
			t.Fatal("not plain UTF-8")
		}
		if strings.Contains(string(data), "PRIVATE KEY") || strings.Contains(string(data), "key_pem") {
			t.Fatal("export contains private key")
		}
		var export PairingExport
		if err := json.Unmarshal(data, &export); err != nil {
			t.Fatal(err)
		}
		if export.HostAddress != host || export.Token != state.Token {
			t.Fatal("export contents differ")
		}
	}
}
