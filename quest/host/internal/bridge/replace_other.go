//go:build !windows

package bridge

import (
	"os"
	"path/filepath"
)

func replaceFile(from, to string) error {
	if e := os.Rename(from, to); e != nil {
		return e
	}
	if d, e := os.Open(filepath.Dir(to)); e == nil {
		defer d.Close()
		_ = d.Sync()
	}
	return nil
}
