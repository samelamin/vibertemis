//go:build windows

package main

import (
	"encoding/json"
	"os/exec"
)

// spawnAndDetach starts the command and releases the process
// handle so the OS owns the child.
func spawnAndDetach(path string, args ...string) error {
	cmd := exec.Command(path, args...)
	if err := cmd.Start(); err != nil {
		return err
	}
	if cmd.Process != nil {
		_ = cmd.Process.Release()
	}
	return nil
}

func jsonMarshalIndent(v interface{}) ([]byte, error) { return json.MarshalIndent(v, "", "  ") }
