package alvr

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestExactNativeVersionAllowlist(t *testing.T) {
	for _, version := range []interface{}{ExpectedVersion, NativeVersion, map[string]interface{}{"major": float64(20), "minor": float64(14), "patch": float64(1), "pre": "vibertemis-pyro.1"}} {
		if err := validateVersion(map[string]interface{}{"server_version": version}); err != nil {
			t.Fatal(err)
		}
	}
	for _, version := range []interface{}{"20.14.1-other", "20.14.1-vibertemis-pyro.2", "20.14.1+unknown", map[string]interface{}{"major": float64(20), "minor": float64(14), "patch": float64(1.5)}, map[string]interface{}{"major": float64(20), "minor": float64(14), "patch": float64(1), "pre": "unknown"}} {
		if validateVersion(map[string]interface{}{"server_version": version}) == nil {
			t.Fatalf("accepted %#v", version)
		}
	}
}

func TestFailedLegacyReplaceDoesNotRestoreStaleBackup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.json")
	if err := NewFakeSession(path, CodecH264); err != nil {
		t.Fatal(err)
	}
	ad := NewAdapter(path, FakeGate{})
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path+".bak", []byte("stale backup"), 0600); err != nil {
		t.Fatal(err)
	}
	ad.replaceFile = func(_, _ string) error { return errors.New("simulated file lock") }
	if _, err := ad.EnsureCodec(CodecAV1); !errors.Is(err, ErrWrite) {
		t.Fatalf("expected replace failure, got %v", err)
	}
	actual, _ := os.ReadFile(path)
	backup, _ := os.ReadFile(path + ".bak")
	if !bytes.Equal(actual, original) || !bytes.Equal(backup, original) {
		t.Fatal("original was overwritten or backup stale")
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temporary file left behind")
	}
}

func TestMalformedVersionMetadataDoesNotPanic(t *testing.T) {
	version := map[string]interface{}{"major": float64(20), "minor": float64(14), "patch": float64(1), "build": map[string]interface{}{"invalid": true}}
	if validateVersion(map[string]interface{}{"server_version": version}) == nil {
		t.Fatal("accepted object build metadata")
	}
}
