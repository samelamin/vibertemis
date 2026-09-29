//go:build windows

// Windows DACL enforcement for the pairing state file.
//
// We build an SDDL string with the current user's SID and
// LocalSystem, parse it with SecurityDescriptorFromString,
// extract the DACL with .DACL(), and apply it with
// SetNamedSecurityInfo. No raw ACE marshalling.
//
// On ACL setup failure the caller MUST fail closed: the
// state file is not loadable, the pairing export is not
// issued.
package pairing

import (
	"errors"
	"fmt"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

func protectFile(path string) error { return setPrivateDACL(path, false) }
func protectDir(path string) error  { return setPrivateDACL(path, true) }

// setPrivateDACL replaces the DACL on path with one that grants
// the current user and LocalSystem full control.
//
// SetNamedSecurityInfo flags: PROTECTED_DACL_SECURITY_INFORMATION
// + DACL_SECURITY_INFORMATION. We deliberately do NOT pass
// OWNER_SECURITY_INFORMATION with a nil owner SID (the API
// rejects that combination) and we do NOT pass
// UNPROTECTED_DACL_SECURITY_INFORMATION.
//
// SECURITY_INFORMATION is defined as uint32 by x/sys/windows;
// the public constants are untyped ints, so the explicit
// cast keeps the OR expression as that type. Without the
// cast the inferred type is `int`, which the Go compiler
// refuses to pass to SetNamedSecurityInfo (cross-platform
// type mismatch, surfacing only on GOOS=windows builds).
func setPrivateDACL(path string, isDir bool) error {
	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("stat: %w", err)
	}
	sddl, err := buildPrivateSDDL(isDir)
	if err != nil {
		return err
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		return fmt.Errorf("sd from sddl: %w", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return fmt.Errorf("dacl: %w", err)
	}
	info := windows.SECURITY_INFORMATION(
		windows.DACL_SECURITY_INFORMATION | windows.PROTECTED_DACL_SECURITY_INFORMATION,
	)
	if err := windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		info,
		nil, nil, dacl, nil,
	); err != nil {
		return fmt.Errorf("setnamedsecurityinfo: %w", err)
	}
	return nil
}

// buildPrivateSDDL returns an SDDL string that grants GA
// (GENERIC_ALL) to LocalSystem (SY) and the current user SID.
// D:P makes the DACL protected from inheritance of parent ACEs.
// CI/OI on directory ACEs propagate the explicit ACEs to
// children.
//
// SDDL ACE format is (AceType;AceFlags;Rights;ObjectGuid;InheritObjectGuid;Trustee).
// The AceFlags <-> Rights separator is a SEMICOLON. The previous
// "(A;%sGA;;;SY)" form concatenated the flags string into the
// flags slot AND collapsed the flags/rights separator; the
// result was either "(A;GA;;;SY)" (a flag named "GA", no rights)
// or "(A;CIOIGA;;;SY)" (illegally merged), both rejected by
// advapi32.
func buildPrivateSDDL(isDir bool) (string, error) {
	sid, err := currentUserSIDString()
	if err != nil {
		return "", err
	}
	if isDir {
		// (A;OICI;GA;;;<sid>) — Object+Container Inherit ACE.
		return fmt.Sprintf("D:P(A;OICI;GA;;;SY)(A;OICI;GA;;;%s)", sid), nil
	}
	// (A;;GA;;;<sid>) — file ACE, no inheritance flags.
	return fmt.Sprintf("D:P(A;;GA;;;SY)(A;;GA;;;%s)", sid), nil
}

func currentUserSIDString() (string, error) {
	sid, err := currentUserSID()
	if err != nil {
		return "", err
	}
	return sid.String(), nil
}

func currentUserSID() (*windows.SID, error) {
	tok, err := windows.OpenCurrentProcessToken()
	if err != nil {
		return nil, fmt.Errorf("opentoken: %w", err)
	}
	defer tok.Close()
	u, err := tok.GetTokenUser()
	if err != nil {
		return nil, fmt.Errorf("getuser: %w", err)
	}
	return u.User.Sid, nil
}

// platformVerifyFilePrivate refuses a file whose DACL grants
// access to anyone other than the current user and LocalSystem.
func platformVerifyFilePrivate(path string) error {
	info, err := windows.GetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION,
	)
	if err != nil {
		return fmt.Errorf("getnamedsecurityinfo: %w", err)
	}
	dacl, _, err := info.DACL()
	if err != nil {
		return fmt.Errorf("dacl: %w", err)
	}
	if dacl == nil {
		return ErrStateBadDACL
	}
	want, err := wantSIDs()
	if err != nil {
		return err
	}
	for i := uint32(0); i < uint32(dacl.AceCount); i++ {
		var acePtr *windows.ACCESS_ALLOWED_ACE
		if err := windows.GetAce(dacl, i, &acePtr); err != nil {
			return fmt.Errorf("getace %d: %w", i, err)
		}
		// Only flag ALLOWED ACEs; deny ACEs would be unexpected
		// but are not flagged here.
		if acePtr.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE {
			continue
		}
		sidPtr := unsafe.Pointer(uintptr(unsafe.Pointer(acePtr)) +
			unsafe.Offsetof(acePtr.SidStart))
		trustee := (*windows.SID)(sidPtr)
		if matchesAnySID(trustee, want) {
			continue
		}
		return fmt.Errorf("%w: ace %d grants to non-owner SID", ErrStateBadDACL, i)
	}
	return nil
}

func wantSIDs() ([]*windows.SID, error) {
	cu, err := currentUserSID()
	if err != nil {
		return nil, err
	}
	sy, err := windows.StringToSid("S-1-5-18")
	if err != nil {
		return nil, fmt.Errorf("local system sid: %w", err)
	}
	return []*windows.SID{cu, sy}, nil
}

func matchesAnySID(s *windows.SID, list []*windows.SID) bool {
	for _, x := range list {
		if windows.EqualSid(s, x) {
			return true
		}
	}
	return false
}

// Compile-time guards so a missing dependency or broken build
// is loud at link time, not at runtime.
var _ = errors.New
var _ unsafe.Pointer
