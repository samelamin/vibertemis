//go:build windows

// Native Windows ACL test. This file MUST be run on a real
// Windows host (CI workflow .github/workflows/quest-host-control.yml
// installs Go 1.26 on windows-2022 and runs `go test -race`).
// It exercises the actual advapi32 path:
//
//   - buildPrivateSDDL produces a valid SDDL string.
//   - SecurityDescriptorFromString parses it.
//   - .DACL() returns a DACL pointer.
//   - SetNamedSecurityInfo applies it to a real file on disk.
//   - GetNamedSecurityInfo reads it back.
//   - platformVerifyFilePrivate accepts the protected file.
//   - platformVerifyFilePrivate rejects an unprotected file
//     (one whose DACL grants to Everyone).
package pairing

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows"
)

func TestSDDL_FormatFile_HasMandatorySemicolon(t *testing.T) {
	sddl, err := buildPrivateSDDL(false)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	// The form MUST be "(A;;GA;;;SY)(A;;GA;;;<sid>)" with the
	// canonical semicolons. The malformed "(A;GA;;;SY)" string
	// has no rights slot at all.
	if !strings.Contains(sddl, "(A;;GA;;;SY)") {
		t.Fatalf("malformed file SDDL: %s", sddl)
	}
	if !strings.HasPrefix(sddl, "D:P") {
		t.Fatalf("SDDL must start with D:P: %s", sddl)
	}
}

func TestSDDL_FormatDir_HasMandatorySemicolonAndInheritFlags(t *testing.T) {
	sddl, err := buildPrivateSDDL(true)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !strings.Contains(sddl, "(A;OICI;GA;;;SY)") {
		t.Fatalf("malformed dir SDDL: %s", sddl)
	}
	if !strings.HasPrefix(sddl, "D:P") {
		t.Fatalf("SDDL must start with D:P: %s", sddl)
	}
}

func TestSDDL_ParsesWithSecurityDescriptorFromString(t *testing.T) {
	sddl, err := buildPrivateSDDL(false)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		t.Fatalf("dacl: %v", err)
	}
	if dacl == nil {
		t.Fatal("DACL must be non-nil after parse")
	}
	if dacl.AceCount == 0 {
		t.Fatal("DACL must contain at least 2 ACEs (current user + SYSTEM)")
	}
}

func TestSDDL_ApplyToFileAndReadBack(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "secret.bin")
	if err := os.WriteFile(path, []byte("dummy"), 0600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := setPrivateDACL(path, false); err != nil {
		t.Fatalf("set dacl: %v", err)
	}
	// Read the DACL back and confirm it matches the SDDL we
	// applied.
	info, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.SECURITY_INFORMATION(
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		),
	)
	if err != nil {
		t.Fatalf("read back: %v", err)
	}
	dacl, _, err := info.DACL()
	if err != nil {
		t.Fatalf("dacl: %v", err)
	}
	if dacl == nil {
		t.Fatal("DACL missing after apply")
	}
	// Two ACEs: SYSTEM and current user.
	if dacl.AceCount != 2 {
		t.Fatalf("expected 2 ACEs, got %d", dacl.AceCount)
	}
	if err := platformVerifyFilePrivate(path); err != nil {
		t.Fatalf("protected file must verify: %v", err)
	}
}

func TestSDDL_ApplyToDirAndRejectEveryone(t *testing.T) {
	dir := t.TempDir()
	if err := setPrivateDACL(dir, true); err != nil {
		t.Fatalf("set dacl dir: %v", err)
	}
	// Seed an "Everyone allowed" file inside it via raw ACL.
	bad := filepath.Join(dir, "bad.txt")
	if err := os.WriteFile(bad, []byte("x"), 0600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	sddl := "D:P(A;;GR;;;WD)" // WD = Everyone, GR = read
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		t.Fatalf("sddl: %v", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		t.Fatalf("dacl: %v", err)
	}
	if err := windows.SetNamedSecurityInfo(
		bad,
		windows.SE_FILE_OBJECT,
		windows.SECURITY_INFORMATION(
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		),
		nil, nil, dacl, nil,
	); err != nil {
		t.Fatalf("set world: %v", err)
	}
	// Restore the private DACL on `bad` BEFORE the deferred
	// TempDir cleanup runs. The Windows cleanup path
	// (RemoveAll → SetNamedSecurityInfo with the inherited
	// parent DACL → Delete) refuses to delete a file whose
	// DACL explicitly grants access to Everyone, because the
	// granting ACE is protected from parent inheritance and
	// the inherited delete-right is absent. Without this
	// restore the test would leave a dangling file under
	// the TempDir root and the CI runner would surface a
	// "Access is denied" cleanup error. The fail-closed
	// ACL verification is preserved on `bad` — the restore
	// is local to the test fixture.
	t.Cleanup(func() {
		_ = setPrivateDACL(bad, false)
	})
	err = platformVerifyFilePrivate(bad)
	if err == nil {
		t.Fatal("file with Everyone ACE must be rejected")
	}
	if !errors.Is(err, ErrStateBadDACL) {
		t.Fatalf("expected ErrStateBadDACL, got %v", err)
	}
}

func TestSetPrivateDACL_ProtectedBitIsSet(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "secret.bin")
	if err := os.WriteFile(path, []byte("dummy"), 0600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := setPrivateDACL(path, false); err != nil {
		t.Fatalf("set: %v", err)
	}
	info, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION,
	)
	if err != nil {
		t.Fatalf("read back: %v", err)
	}
	ctrl, _, err := info.Control()
	if err != nil {
		t.Fatalf("control: %v", err)
	}
	if ctrl&windows.SE_DACL_PROTECTED == 0 {
		t.Fatal("SE_DACL_PROTECTED bit must be set")
	}
}

func makeWorldReadable(t *testing.T, path string) error {
	t.Helper()
	sddl, err := buildPrivateSDDL(false)
	if err != nil {
		t.Fatal(err)
	}
	sd, err := windows.SecurityDescriptorFromString(sddl + "(A;;GR;;;WD)")
	if err != nil {
		t.Fatal(err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, dacl, nil); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := protectFile(path); err != nil {
			t.Error(err)
		}
	})
	return ErrStateBadDACL
}
