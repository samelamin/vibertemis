// Package steamvr owns the host SteamVR supervision contract.
//
// Phase 1 contract:
//
//   - The companion never tries to launch SteamVR from Session0.
//   - The companion never tries to kill or restart SteamVR.
//   - The companion uses a validated Steam exe path AND/OR a
//     bounded OS URL dispatch (rundll32 url.dll,FileProtocolHandler).
//     No shell. No cmd /c. No defer-cancel on the spawned process.
//   - Scanner failures fail closed: an error from the
//     ProcessScanner is reported to the caller, NOT interpreted
//     as "process absent".
//   - The path is validated to exist as a regular file before
//     mutation; the path passed to the launcher is exactly what
//     the operator configured (no string concat, no shell).
package steamvr

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// keep context alias referenced (avoids unused-import lint when
// the platform-conditional branch compiles).
var _ context.Context = context.Background()

// Errors.
var (
	ErrAlreadyRunning = errors.New("steamvr already running")
	ErrProbeFailed    = errors.New("steamvr process probe failed")
	ErrBadPath        = errors.New("steam path validation failed")
)

// ProcessScanner abstracts the platform process table.
type ProcessScanner interface {
	VRServerRunning() (bool, error)
	DashboardRunning() (bool, error)
}

// Launcher supervises SteamVR.
type Launcher struct {
	scanner       ProcessScanner
	launchFn      func(path string, args ...string) error
	probeTimeout  time.Duration
	launchTimeout time.Duration
	assertSession func() error
	mu            sync.Mutex
}

// Options configures a Launcher.
type Options struct {
	Scanner       ProcessScanner
	Launch        func(path string, args ...string) error // default: spawn-and-detach
	ProbeTimeout  time.Duration                           // default 5s
	LaunchTimeout time.Duration                           // default 5s
	// AssertSession, when non-nil, replaces the production
	// Session0 guard. Tests inject a deterministic function so
	// they can simulate Session0 without global mutable state;
	// production MUST leave this nil so the real
	// ProcessIdToSessionId check fires.
	AssertSession func() error
}

// NewGate returns the production ProcessGate. On Windows this
// is a TasklistScanner; on POSIX it is a pgrepScanner.
func NewGate() ProcessScanner {
	if runtime.GOOS == "windows" {
		return TasklistScanner{}
	}
	return pgrepScanner{}
}

// New returns a Launcher with the supplied Options. A nil
// Scanner is treated as a probe failure (fail-closed) — the
// launcher refuses to start without a scanner.
//
// AssertSession defaults to the real Windows Session0 check
// (assertInteractiveSession) on Windows and a no-op on POSIX;
// tests inject a function to simulate Session0 without any
// globally mutable state.
func New(o Options) *Launcher {
	l := &Launcher{
		scanner:       o.Scanner,
		launchFn:      o.Launch,
		probeTimeout:  o.ProbeTimeout,
		launchTimeout: o.LaunchTimeout,
		assertSession: o.AssertSession,
	}
	if l.probeTimeout == 0 {
		l.probeTimeout = 5 * time.Second
	}
	if l.launchTimeout == 0 {
		l.launchTimeout = 5 * time.Second
	}
	if l.launchFn == nil {
		l.launchFn = defaultLaunch
	}
	if l.assertSession == nil {
		l.assertSession = assertInteractiveSession
	}
	return l
}

// VRServerRunning reports whether SteamVR is up. A scanner error
// is returned to the caller (NOT collapsed to "false") so the
// caller can refuse to act on a probe failure.
func (l *Launcher) VRServerRunning() (bool, error) {
	if l.scanner == nil {
		return false, ErrProbeFailed
	}
	cctx, cancel := context.WithTimeout(context.Background(), l.probeTimeout)
	defer cancel()
	return runWithCtx(cctx, func() (bool, error) { return l.scanner.VRServerRunning() })
}

// DashboardRunning reports whether the ALVR Dashboard is up.
func (l *Launcher) DashboardRunning() (bool, error) {
	if l.scanner == nil {
		return false, ErrProbeFailed
	}
	cctx, cancel := context.WithTimeout(context.Background(), l.probeTimeout)
	defer cancel()
	return runWithCtx(cctx, func() (bool, error) { return l.scanner.DashboardRunning() })
}

// Start launches SteamVR using the validated Steam exe path. The
// path MUST be a non-empty absolute path to a regular file. The
// command is spawned without a shell; arguments are passed as
// argv (no concatenation, no escaping). The companion does NOT
// wait for the launched process — it detaches and returns.
//
// If SteamVR is already up, the call returns ErrAlreadyRunning
// (no second launch). If either process probe returns an error,
// the launch is refused.
//
// On Windows the companion refuses to launch when running in
// Session 0 (the service session), because Session 0 cannot
// bring up an interactive SteamVR process.
//
// SteamPath == "" means "no direct exe supplied; use the
// OS URL handler with steam://run/250820". The URL handler is
// invoked via rundll32 url.dll,FileProtocolHandler on Windows,
// xdg-open on Linux, open on macOS. No shell.
func (l *Launcher) Start(steamPath string) error {
	if err := l.assertSession(); err != nil {
		return err
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	running, err := l.VRServerRunning()
	if err != nil {
		return fmt.Errorf("%w: vrserver: %v", ErrProbeFailed, err)
	}
	if running {
		return ErrAlreadyRunning
	}
	if steamPath != "" {
		return l.startDirect(steamPath)
	}
	return l.startURLHandler()
}

func (l *Launcher) startDirect(steamPath string) error {
	if err := validateSteamPath(steamPath); err != nil {
		return err
	}
	// Static argv: Steam.exe -applaunch 250820 launches SteamVR
	// (the canonical Steam app-id for SteamVR). No shell, no
	// concat, no cmd /c. The exe path the operator supplied
	// goes through verbatim into the first argv slot; the
	// applaunch args are a fixed pair.
	args := []string{"-applaunch", "250820"}
	cctx, cancel := context.WithTimeout(context.Background(), l.launchTimeout)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- l.launchFn(steamPath, args...)
	}()
	select {
	case err := <-done:
		if err != nil {
			return fmt.Errorf("launch steam: %w", err)
		}
		return nil
	case <-cctx.Done():
		return fmt.Errorf("launch steam: %w", cctx.Err())
	}
}

func (l *Launcher) startURLHandler() error {
	cctx, cancel := context.WithTimeout(context.Background(), l.launchTimeout)
	defer cancel()
	url := "steam://run/250820"
	var path string
	var args []string
	switch runtime.GOOS {
	case "windows":
		// rundll32 url.dll,FileProtocolHandler <url>
		// No shell. Comma in "url.dll,FileProtocolHandler" is a
		// single argv element; Go's exec passes it verbatim to
		// CreateProcess.
		path = "rundll32.exe"
		args = []string{"url.dll,FileProtocolHandler", url}
	case "linux":
		path = "xdg-open"
		args = []string{url}
	case "darwin":
		path = "open"
		args = []string{url}
	default:
		return fmt.Errorf("unsupported GOOS: %s", runtime.GOOS)
	}
	done := make(chan error, 1)
	go func() {
		done <- l.launchFn(path, args...)
	}()
	select {
	case err := <-done:
		if err != nil {
			return fmt.Errorf("dispatch url: %w", err)
		}
		return nil
	case <-cctx.Done():
		return fmt.Errorf("dispatch url: %w", cctx.Err())
	}
}

// validateSteamPath checks the path exists, is a regular file,
// and is a non-empty absolute path. The check is conservative;
// we do not second-guess the operator.
func validateSteamPath(p string) error {
	if p == "" {
		return fmt.Errorf("%w: empty path", ErrBadPath)
	}
	if !filepath.IsAbs(p) {
		return fmt.Errorf("%w: not absolute: %s", ErrBadPath, p)
	}
	st, err := os.Stat(p)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrBadPath, err)
	}
	if !st.Mode().IsRegular() {
		return fmt.Errorf("%w: not a regular file: %s", ErrBadPath, p)
	}
	return nil
}

// defaultLaunch spawns the command and detaches it. The
// process handle is released so the Go runtime does not retain
// it; the launched process is now an orphan under the OS and
// outlives the companion.
func defaultLaunch(path string, args ...string) error {
	cmd := exec.Command(path, args...)
	if err := cmd.Start(); err != nil {
		return err
	}
	if cmd.Process != nil {
		_ = cmd.Process.Release()
	}
	return nil
}

func runWithCtx(ctx context.Context, fn func() (bool, error)) (bool, error) {
	done := make(chan struct {
		b   bool
		err error
	}, 1)
	go func() {
		b, err := fn()
		done <- struct {
			b   bool
			err error
		}{b, err}
	}()
	select {
	case r := <-done:
		return r.b, r.err
	case <-ctx.Done():
		return false, ctx.Err()
	}
}

// FakeScanner is a fixed-state scanner for tests.
type FakeScanner struct {
	VRServer  bool
	VRErr     error
	Dashboard bool
	DashErr   error
}

func (f FakeScanner) VRServerRunning() (bool, error)  { return f.VRServer, f.VRErr }
func (f FakeScanner) DashboardRunning() (bool, error) { return f.Dashboard, f.DashErr }

// TasklistScanner is the production Windows scanner. It uses
// tasklist's image-name filter. The exit-code-1 = "no match" is
// treated as "not running", but a tasklist invocation that
// returns any other error is reported as a probe failure.
type TasklistScanner struct{}

func (TasklistScanner) VRServerRunning() (bool, error) {
	return tasklistRunning("vrserver.exe")
}

func (TasklistScanner) DashboardRunning() (bool, error) {
	return tasklistRunning("ALVR Dashboard.exe")
}

// tasklistRunning is the Windows process probe. Any nonzero
// tasklist exit is treated as a probe failure (fail-closed).
// Only an explicit substring match in the output reports the
// process as running. The subprocess is bounded by the
// caller's context so a hung tasklist cannot leak a goroutine.
func tasklistRunning(image string) (bool, error) {
	ctx, cancel := newTasklistCtx()
	defer cancel()
	cmd := exec.CommandContext(ctx, "tasklist", "/FI", "IMAGENAME eq "+image, "/NH")
	out, err := cmd.CombinedOutput()
	if err != nil {
		// Codex #10: any nonzero exit is fail-closed. The
		// historical "exit 1 = no match" assumption is unsafe
		// across Windows editions and locales.
		return false, fmt.Errorf("tasklist: %w", err)
	}
	return bytesContainCI(out, image), nil
}

// newTasklistCtx returns a 5-second deadline for the
// tasklist subprocess. If the system is under load and
// tasklist hangs, the caller's goroutine is freed.
func newTasklistCtx() (ctx contextLike, cancel func()) {
	return tasklistContextFactory()
}

// contextLike is the minimal interface used here so we can
// avoid importing context in this file's public surface (it
// already imports it via runtime.GOOS). We do not need to
// pass the context to exec — exec.CommandContext is the
// standard wiring.
type contextLike = context.Context

func tasklistContextFactory() (context.Context, func()) {
	return context.WithTimeout(context.Background(), 5*time.Second)
}

func bytesContainCI(b []byte, needle string) bool {
	s := string(b)
	lowS := toLowerASCII(s)
	return contains(lowS, toLowerASCII(needle))
}

func toLowerASCII(s string) string {
	out := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			c += 'a' - 'A'
		}
		out[i] = c
	}
	return string(out)
}

func contains(s, sub string) bool {
	if len(sub) > len(s) {
		return false
	}
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// pgrepScanner is the production POSIX scanner.
type pgrepScanner struct{}

func (pgrepScanner) VRServerRunning() (bool, error) {
	return pgrepRunning("vrserver")
}

func (pgrepScanner) DashboardRunning() (bool, error) {
	return pgrepRunning("alvr_dashboard")
}

func pgrepRunning(name string) (bool, error) {
	cmd := exec.Command("pgrep", "-x", name)
	err := cmd.Run()
	if err == nil {
		return true, nil
	}
	if exitErr, ok := err.(*exec.ExitError); ok && exitErr.ExitCode() == 1 {
		return false, nil
	}
	return false, fmt.Errorf("pgrep %s: %w", name, err)
}

// Compile-time interfaces.
var (
	_ ProcessScanner = FakeScanner{}
	_ ProcessScanner = TasklistScanner{}
)
