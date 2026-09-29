// Tests for the pairing state on POSIX. The Windows
// counterpart uses SDDL/DACL; this test exercises the chmod
// fallback.
package pairing

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPairingGenerateAndLoad_RoundTrip(t *testing.T) {
	dir := t.TempDir()
	st, err := Generate()
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	if st.Generation != 1 {
		t.Fatalf("generation = %d", st.Generation)
	}
	if len(st.CertPin) != 64 {
		t.Fatalf("certpin length = %d, want 64", len(st.CertPin))
	}
	if len(st.Token) != 64 {
		t.Fatalf("token length = %d, want 64", len(st.Token))
	}
	if err := Save(dir, st); err != nil {
		t.Fatalf("save: %v", err)
	}
	loaded, err := Load(dir)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if loaded.CertPEM != st.CertPEM || loaded.Token != st.Token {
		t.Fatal("round-trip mismatch")
	}
}

func TestPairingLoad_RefusesWorldReadable(t *testing.T) {
	dir := t.TempDir()
	st, err := Generate()
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	if err := Save(dir, st); err != nil {
		t.Fatalf("save: %v", err)
	}
	// Loosen the file to 0644 (group+other readable). Load
	// must refuse.
	path := filepath.Join(dir, "state.json")
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatalf("chmod: %v", err)
	}
	_, err = Load(dir)
	if err != ErrStateBadPerm {
		t.Fatalf("expected ErrStateBadPerm, got %v", err)
	}
}

func TestPairingExportOmitsPrivateKey(t *testing.T) {
	st, err := Generate()
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	exp := st.Export("127.0.0.1:28540")
	if exp.CertPEM == "" || exp.CertPin == "" || exp.Token == "" {
		t.Fatal("export must include cert, pin, token")
	}
	if exp.CertPEM == st.KeyPEM {
		t.Fatal("export must not include the private key")
	}
	// The private key is in KeyPEM, not CertPEM, by construction.
	// Sanity: KeyPEM contains "EC PRIVATE KEY".
	if !contains(st.KeyPEM, "EC PRIVATE KEY") {
		t.Fatal("test fixture bad: key PEM missing EC PRIVATE KEY")
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
