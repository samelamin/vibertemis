package server

import (
	"bytes"
	"fmt"
	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"github.com/vibertemis/quest-codec-control/host/internal/steamvr"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestObservationPreservesReceiptAndDoesNotFinishPreflight(t *testing.T) {
	now := time.Now()
	s := &Server{deps: Deps{Now: func() time.Time { return now }}, results: make(map[string]txResult)}
	first := s.lookupOrBeginTx("connect", "av1")
	s.resolveDispatchedTx()
	if got := s.lookupOrBeginTx("other", "hevc"); got.kind != txStarting {
		t.Fatal("observation unlocked preflight")
	}
	now = now.Add(2 * startupLease)
	if got := s.lookupOrBeginTx("other", "hevc"); got.kind != txStarting {
		t.Fatal("slow preflight lost ownership")
	}
	s.markDispatched(first.owner)
	s.resolveDispatchedTx()
	if got := s.lookupOrBeginTx("connect", "av1"); got.kind != txCompleted || got.cached.State != StateStarted {
		t.Fatal("observation lost completed receipt")
	}
	if got := s.lookupOrBeginTx("connect", "hevc"); got.kind != txConflict {
		t.Fatal("observation lost payload identity")
	}
	second := s.lookupOrBeginTx("other", "hevc")
	if second.kind != txFresh {
		t.Fatal("observed startup did not unlock next request")
	}
	s.completeTx(first.owner, StartPcvrResponse{State: StateDenied}, true)
	if s.inflight != second.owner || s.results["connect"].resp.State != StateStarted {
		t.Fatal("old handler corrupted new ownership or receipt")
	}
}

func TestExpiredOwnerCannotMutateReusedRequestID(t *testing.T) {
	now := time.Now()
	s := &Server{deps: Deps{Now: func() time.Time { return now }}, results: make(map[string]txResult)}
	first := s.lookupOrBeginTx("connect", "av1")
	s.markDispatched(first.owner)
	now = now.Add(startupLease + time.Second)
	second := s.lookupOrBeginTx("connect", "av1")
	if second.kind != txFresh || second.owner == first.owner {
		t.Fatal("expired startup not replaced")
	}
	s.completeTx(first.owner, StartPcvrResponse{State: StateStarted}, true)
	s.markDispatched(first.owner)
	if s.inflight != second.owner || second.owner.dispatched || len(s.results) != 0 {
		t.Fatal("stale owner changed replacement")
	}
	s.completeTx(second.owner, StartPcvrResponse{State: StateReconnectRequired}, true)
	if got := s.lookupOrBeginTx("connect", "av1"); got.kind != txCompleted || got.cached.State != StateReconnectRequired {
		t.Fatal("new owner could not complete")
	}
}

func TestColdStartPollingPreservesRequestIdentity(t *testing.T) {
	for _, slowLaunch := range []bool{false, true} {
		t.Run(fmt.Sprint("uncertain-launch-", slowLaunch), func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "session.json")
			if err := alvr.NewFakeSession(path, alvr.CodecH264); err != nil {
				t.Fatal(err)
			}
			scanner := &mutableScanner{}
			var launches atomic.Int32
			release := make(chan struct{})
			defer close(release)
			launcher := steamvr.New(steamvr.Options{Scanner: scanner, AssertSession: func() error { return nil }, LaunchTimeout: 10 * time.Millisecond, Launch: func(string, ...string) error {
				launches.Add(1)
				if slowLaunch {
					<-release
				}
				return nil
			}})
			host, err := New(Deps{Token: "test", Adapter: alvr.NewAdapter(path, alvr.FakeGate{}), Launcher: launcher, NonceLRU: state.NewNonceLRU(64, 30*time.Second, time.Now), IPLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now)})
			if err != nil {
				t.Fatal(err)
			}
			httpServer := httptest.NewServer(host.Handler())
			defer httpServer.Close()
			body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"cold"}`)
			_, _, first := postSigned(t, httpServer.URL, "test", body, "first")
			if first.State != StateStarting {
				t.Fatalf("expected pending startup, got %+v", first)
			}
			scanner.setVR(true)
			getSigned(t, httpServer.URL, "/status", "test")
			_, _, retry := postSigned(t, httpServer.URL, "test", body, "retry")
			if retry.State != StateStarted {
				t.Fatalf("missing completed receipt: %+v", retry)
			}
			changed := bytes.Replace(body, []byte("H264"), []byte("AV1"), 1)
			_, _, conflict := postSigned(t, httpServer.URL, "test", changed, "changed")
			if conflict.Error != ErrIdConflict || launches.Load() != 1 {
				t.Fatalf("identity/launch violation: %+v, launches=%d", conflict, launches.Load())
			}
		})
	}
}
