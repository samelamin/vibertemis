//go:build windows

package bridge

import (
	"fmt"
	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"io"
	"os"
	"testing"
	"time"
)

func TestWindowsPipeIdentificationAndExclusiveListener(t *testing.T) {
	tok, e := windows.OpenCurrentProcessToken()
	if e != nil {
		t.Fatal(e)
	}
	defer tok.Close()
	sid, session, e := tokenIdentity(tok)
	if e != nil {
		t.Fatal(e)
	}
	exe, e := os.Executable()
	if e != nil {
		t.Fatal(e)
	}
	exe, e = canonicalPath(exe)
	if e != nil {
		t.Fatal(e)
	}
	name := fmt.Sprintf(`\\.\pipe\Vibertemis-test-%d-%d`, os.Getpid(), time.Now().UnixNano())
	l, e := winio.ListenPipe(name, &winio.PipeConfig{SecurityDescriptor: fmt.Sprintf("D:P(A;;GA;;;SY)(A;;GA;;;%s)", sid), InputBufferSize: 4096, OutputBufferSize: 4096})
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	if duplicate, e := winio.ListenPipe(name, nil); e == nil {
		duplicate.Close()
		t.Fatal("second listener acquired same name")
	}
	done := make(chan error, 1)
	go func() {
		c, e := l.Accept()
		if e != nil {
			done <- e
			return
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		var b [1]byte
		if _, e = io.ReadFull(c, b[:]); e != nil {
			done <- e
			return
		}
		fd, ok := c.(interface{ Fd() uintptr })
		if !ok {
			done <- fmt.Errorf("go-winio connection has no Fd")
			return
		}
		peerSID, peerSession, err := pipeIdentity(windows.Handle(fd.Fd()))
		if err != nil || peerSID != sid || peerSession != session {
			done <- fmt.Errorf("identification mismatch: %s/%d: %v", peerSID, peerSession, err)
			return
		}
		err = identifyHost(c, registration{sid: sid, sunshine: exe, session: session})
		if session != windows.WTSGetActiveConsoleSessionId() {
			if err == nil {
				done <- fmt.Errorf("inactive session accepted")
				return
			}
		} else if err != nil {
			done <- err
			return
		}
		if err := identifyHost(c, registration{sid: sid, sunshine: exe, session: session + 1}); err == nil {
			done <- fmt.Errorf("wrong session accepted")
			return
		}
		if sid != "S-1-5-18" && session == windows.WTSGetActiveConsoleSessionId() {
			if identifyHost(c, registration{sid: sid, sunshine: exe + ".other", session: session}) == nil {
				done <- fmt.Errorf("wrong executable accepted")
				return
			}
			if identifyHost(c, registration{sid: "S-1-5-19", sunshine: exe, session: session}) == nil {
				done <- fmt.Errorf("wrong user accepted")
				return
			}
		}
		done <- nil
	}()
	path, e := windows.UTF16PtrFromString(name)
	if e != nil {
		t.Fatal(e)
	}
	var h windows.Handle
	deadline := time.Now().Add(3 * time.Second)
	for {
		h, e = windows.CreateFile(path, windows.GENERIC_READ|windows.GENERIC_WRITE, 0, nil, windows.OPEN_EXISTING, windows.SECURITY_SQOS_PRESENT|windows.SECURITY_IDENTIFICATION, 0)
		if e == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal(e)
		}
		time.Sleep(10 * time.Millisecond)
	}
	defer windows.CloseHandle(h)
	var n uint32
	if e = windows.WriteFile(h, []byte{1}, &n, nil); e != nil {
		t.Fatal(e)
	}
	select {
	case e = <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("identity check stalled")
	}
}
