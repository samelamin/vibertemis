//go:build !windows

package steamvr

// assertInteractiveSession is a no-op on non-Windows hosts.
// The Session0 guard is Windows-specific.
func assertInteractiveSession() error { return nil }

// ErrSession0 is exported for cross-platform callers; on
// non-Windows it is never returned.
var ErrSession0 = errSession0Sentinel{}

type errSession0Sentinel struct{}

func (errSession0Sentinel) Error() string {
	return "Session0 guard is Windows-specific"
}
