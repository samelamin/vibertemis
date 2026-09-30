//go:build windows

package bridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
	"unsafe"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

type WindowsRunner struct{}

func newPlatformRunner() PipeRunner { return &WindowsRunner{} }

type registration struct {
	sid, companion, sunshine string
	session                  uint32
}

func tokenIdentity(t windows.Token) (string, uint32, error) {
	u, err := t.GetTokenUser()
	if err != nil {
		return "", 0, err
	}
	var session, size uint32
	err = windows.GetTokenInformation(t, windows.TokenSessionId, (*byte)(unsafe.Pointer(&session)), 4, &size)
	return u.User.Sid.String(), session, err
}
func canonicalPath(p string) (string, error) {
	if !filepath.IsAbs(p) {
		return "", errors.New("bridge path is not absolute")
	}
	p, err := filepath.EvalSymlinks(p)
	if err != nil {
		return "", err
	}
	return filepath.Clean(p), nil
}
func readRegistration() (r registration, err error) {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Vibertemis\VRBridge`, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err != nil {
		return r, err
	}
	defer k.Close()
	if r.sid, _, err = k.GetStringValue("UserSid"); err != nil {
		return r, err
	}
	if r.companion, _, err = k.GetStringValue("CompanionPath"); err != nil {
		return r, err
	}
	if r.sunshine, _, err = k.GetStringValue("SunshinePath"); err != nil {
		return r, err
	}
	if r.companion, err = canonicalPath(r.companion); err != nil {
		return r, err
	}
	if r.sunshine, err = canonicalPath(r.sunshine); err != nil {
		return r, err
	}
	t, err := windows.OpenCurrentProcessToken()
	if err != nil {
		return r, err
	}
	defer t.Close()
	sid, session, err := tokenIdentity(t)
	if err != nil {
		return r, err
	}
	r.session = windows.WTSGetActiveConsoleSessionId()
	exe, err := os.Executable()
	if err != nil {
		return r, err
	}
	exe, err = canonicalPath(exe)
	if err != nil {
		return r, err
	}
	if r.session == 0xffffffff || session != r.session || sid != r.sid || !strings.EqualFold(exe, r.companion) {
		return r, errors.New("bridge registration does not match active companion")
	}
	return r, nil
}

// conn is exclusively owned here; cancellation cannot close it during identity
// inspection. The prefetched byte establishes the pipe identification context.
func identifyHost(conn net.Conn, r registration) error {
	f, ok := conn.(interface{ Fd() uintptr })
	if !ok {
		return errors.New("pipe does not expose its Windows handle")
	}
	h := windows.Handle(f.Fd())
	defer runtime.KeepAlive(conn)
	var pid uint32
	if err := windows.GetNamedPipeClientProcessId(h, &pid); err != nil {
		return err
	}
	sid, session, err := pipeIdentity(h)
	if err != nil {
		return err
	}
	if session != r.session || windows.WTSGetActiveConsoleSessionId() != r.session {
		return ErrUnauthorized
	}
	if sid == "S-1-5-18" {
		return nil
	}
	if sid != r.sid {
		return ErrUnauthorized
	}
	// Identification impersonation has ended before opening securable objects.
	p, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(p)
	var token windows.Token
	if err := windows.OpenProcessToken(p, windows.TOKEN_QUERY, &token); err != nil {
		return err
	}
	defer token.Close()
	actualSID, actualSession, err := tokenIdentity(token)
	if err != nil || actualSID != sid || actualSession != session {
		return ErrUnauthorized
	}
	buf := make([]uint16, 32768)
	n := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(p, 0, &buf[0], &n); err != nil {
		return err
	}
	actual, err := canonicalPath(windows.UTF16ToString(buf[:n]))
	if err != nil || !strings.EqualFold(actual, r.sunshine) {
		return ErrUnauthorized
	}
	return nil
}
func pipeIdentity(h windows.Handle) (sid string, session uint32, err error) {
	runtime.LockOSThread()
	if err = impersonateNamedPipeClient(h); err != nil {
		runtime.UnlockOSThread()
		return
	}
	defer func() {
		if revertToSelf() != nil {
			os.Exit(1)
		} // Never return an impersonated thread to Go.
		runtime.UnlockOSThread()
	}()
	var token windows.Token
	if err = windows.OpenThreadToken(windows.CurrentThread(), windows.TOKEN_QUERY, true, &token); err != nil {
		return
	}
	defer token.Close()
	return tokenIdentity(token)
}

type prefetchedConn struct {
	net.Conn
	first   byte
	pending bool
}

func (c *prefetchedConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if c.pending {
		p[0] = c.first
		c.pending = false
		return 1, nil
	}
	return c.Conn.Read(p)
}
func (WindowsRunner) Run(ctx context.Context, cfg PipeConfig) error {
	if cfg.Service == nil {
		return errors.New("bridge service missing")
	}
	for ctx.Err() == nil {
		r, err := readRegistration()
		if err == nil {
			_ = runRegistered(ctx, cfg, r)
		}
		// Setup VR can create registration after the companion has started.
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return ctx.Err()
}
func runRegistered(ctx context.Context, cfg PipeConfig, r registration) error {
	name := fmt.Sprintf("%s%d", PipePrefix, r.session)
	l, err := winio.ListenPipe(name, &winio.PipeConfig{
		SecurityDescriptor: fmt.Sprintf("D:P(A;;GA;;;SY)(A;;GA;;;%s)", r.sid),
		InputBufferSize:    MaxFrameBytes, OutputBufferSize: MaxFrameBytes,
	})
	if err != nil {
		return err
	}
	defer l.Close()
	stop := context.AfterFunc(ctx, func() { l.Close() })
	defer stop()
	conn, err := l.Accept()
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := conn.SetReadDeadline(time.Now().Add(AuthorizeTimeout)); err != nil {
		return err
	}
	var first [1]byte
	if _, err := io.ReadFull(conn, first[:]); err != nil {
		return err
	}
	if err := identifyHost(conn, r); err != nil {
		return err
	}
	latest, err := readRegistration()
	if err != nil || latest != r {
		return ErrUnauthorized
	}
	if err := conn.SetReadDeadline(time.Time{}); err != nil {
		return err
	}
	// Close on session switch/registration change, as well as process shutdown.
	sessionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-sessionCtx.Done():
				return
			case <-ticker.C:
				current, err := readRegistration()
				if err != nil || current != r {
					cancel()
					return
				}
			}
		}
	}()
	err = serveDuplex(sessionCtx, &prefetchedConn{Conn: conn, first: first[0], pending: true}, cfg.Service)
	cancel()
	<-done
	return err
}
