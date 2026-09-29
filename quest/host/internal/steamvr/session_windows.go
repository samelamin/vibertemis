//go:build windows

// Windows session gating. The companion must run in the
// user's interactive session (Session ≥ 1); Session 0 is the
// service session and cannot bring up an interactive
// SteamVR process. We refuse to start when invoked from
// Session 0.
package steamvr

import (
	"errors"
	"fmt"

	"golang.org/x/sys/windows"
)

// ErrSession0 is returned when the current process is running
// in Windows Session 0 (the service session).
var ErrSession0 = errors.New("companion is running in Windows Session 0; restart it from the user's interactive session")

// assertInteractiveSession returns ErrSession0 if the current
// process is running in Windows Session 0. It is called by the
// Launcher.Start path before any launch is attempted.
func assertInteractiveSession() error {
	pid := uint32(windows.GetCurrentProcessId())
	var sessionID uint32
	if err := windows.ProcessIdToSessionId(pid, &sessionID); err != nil {
		return fmt.Errorf("ProcessIdToSessionId: %w", err)
	}
	if sessionID == 0 {
		return ErrSession0
	}
	return nil
}
