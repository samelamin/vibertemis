//go:build !windows

// Non-Windows stub. The companion's VR-bridge IPC is
// Windows-only (a Windows helper registers the user SID +
// Sunshine path in HKLM and starts Vibeshine / Sunshine;
// that helper is a separate agent). On other OSes the
// bridge IPC returns ErrUnsupported; the rest of the
// companion (HTTPS, redeem, in-process Service) still
// works so legacy pairing keeps functioning.
package bridge

import "context"

// newPlatformRunner returns the no-op runner.
func newPlatformRunner() PipeRunner { return &otherRunner{} }

type otherRunner struct{}

func (otherRunner) Run(_ context.Context, _ PipeConfig) error {
	return ErrUnsupported
}
