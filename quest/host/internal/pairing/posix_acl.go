//go:build !windows

// POSIX ACL fallback for the pairing state. The kernel honors
// 0600/0700 mode bits, so chmod is effective here. On Windows
// the windows_acl.go counterpart uses a real SDDL DACL; this
// file is the Linux/macOS/test build.
package pairing

import "os"

func protectFile(path string) error { return os.Chmod(path, 0600) }
func protectDir(path string) error  { return os.Chmod(path, 0700) }

func platformVerifyFilePrivate(path string) error {
	st, err := os.Stat(path)
	if err != nil {
		return err
	}
	if st.Mode().Perm()&0o077 != 0 {
		return ErrStateBadPerm
	}
	return nil
}
