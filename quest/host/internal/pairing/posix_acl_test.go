//go:build !windows

package pairing

import (
	"os"
	"testing"
)

func makeWorldReadable(t *testing.T, path string) error {
	t.Helper()
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	return ErrStateBadPerm
}
