// start_pcvr handler. Split out for clarity; see server.go
// for the package doc.
package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
)

func (s *Server) handleStartPcvr1(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	body, err := readBoundedBody(w, r, s.deps.MaxBody)
	if err != nil {
		writeStartPcvr(w, StartPcvrResponse{
			State: StateDenied, Error: ErrBadRequest, Message: err.Error(),
		})
		return
	}
	// Auth (HMAC + nonce). NOTE: this consumes only the
	// IPLimiter (DOS guard) — the ActionLimiter is checked
	// AFTER auth so invalid HMAC cannot consume the
	// legitimate start budget (Codex #3).
	if _, _, ok := s.authenticate(w, r, body, r.Method, r.URL.Path, s.deps.MaxBody); !ok {
		return
	}
	// ActionLimiter is consulted here, post-auth.
	if !s.consumeActionBudget(w, r) {
		return
	}
	// Parse body.
	var req StartPcvrRequest
	if len(body) > 0 {
		if err := json.Unmarshal(body, &req); err != nil {
			writeStartPcvr(w, StartPcvrResponse{
				State: StateDenied, Error: ErrBadRequest, Message: "bad json: " + err.Error(),
			})
			return
		}
	}
	// Strict role+mode validation (Codex #13).
	if !validRequestClass(req.Role, req.Mode) {
		writeStartPcvr(w, StartPcvrResponse{
			State: StateDenied, Error: ErrBadRequest,
			Message: fmt.Sprintf("role=%q mode=%q rejected; require exactly role=headset mode=pcvr", req.Role, req.Mode),
		})
		return
	}
	// Required request_id (Codex #16).
	if strings.TrimSpace(req.RequestId) == "" {
		writeStartPcvr(w, StartPcvrResponse{
			State: StateDenied, Error: ErrBadRequest,
			Message: "request_id required",
		})
		return
	}
	payloadDigest := payloadHex(body)

	// Atomic launch transaction. The lookupOrBeginTx call
	// inspects (and possibly installs) the global inflight
	// pointer under txMu and returns one of:
	//   txCompleted   : cached result for THIS (requestID,
	//                   payload) is still valid → write it.
	//   txInProgress  : same requestID + same payload is
	//                   already running → return STARTING so
	//                   the caller polls /status / retries.
	//   txStarting    : a different requestID is currently
	//                   inflight and the lease is held → return
	//                   STARTING without dispatching a second
	//                   SteamVR launch.
	//   txConflict    : same requestID is currently inflight
	//                   OR a completed cached result exists
	//                   for a different payload → return
	//                   REQUEST_ID_CONFLICT. DO NOT launch.
	//   txCacheFull   : result cache is at capacity AND
	//                   nothing has expired → return
	//                   INTERNAL with a "cache full" message.
	//                   The lease is still held so no second
	//                   dispatch happens.
	//   txFresh       : no inflight, no lease → install one,
	//                   caller proceeds to dispatch.
	tx := s.lookupOrBeginTx(req.RequestId, payloadDigest)
	switch tx.kind {
	case txCompleted:
		writeStartPcvr(w, tx.cached)
		return
	case txInProgress:
		writeStartPcvr(w, StartPcvrResponse{
			State: StateStarting, Error: ErrAlreadyStarting,
			Message: "another request with the same request_id and payload is in progress",
		})
		return
	case txStarting:
		writeStartPcvr(w, StartPcvrResponse{
			State: StateStarting, Error: ErrAlreadyStarting,
			Message: "a startup is already in progress; poll /status or retry with a new request_id",
		})
		return
	case txConflict:
		writeStartPcvr(w, StartPcvrResponse{
			State: StateDenied, Error: ErrIdConflict,
			Message: fmt.Sprintf("request_id %q is in use with a different payload; another launch with the same id is rejected", req.RequestId),
		})
		return
	case txCacheFull:
		writeStartPcvr(w, StartPcvrResponse{
			State: StateDenied, Error: ErrInternal,
			Message: "result cache is full and nothing has expired; retry after the existing entries age out",
		})
		return
	}
	// tx.kind == txFresh → we own the global inflight
	// pointer. We MUST finalise it via completeTx (success),
	// failTx (failure that clears the lease), or a deferred
	// lease expiry.
	owner := true
	defer func() {
		if owner {
			s.completeTx(tx.owner, StartPcvrResponse{}, false)
		}
	}()
	finalise := func(resp StartPcvrResponse) {
		// All complete/fail paths store a response for
		// ttl-keyed lookup so same-id-same-payload
		// replays return the deterministic cached
		// outcome without re-running.
		if owner {
			s.completeTx(tx.owner, resp, true)
			owner = false
		}
		writeStartPcvr(w, resp)
	}
	// failStart clears the lease so a same-ID retry (or a
	// new-ID retry) can re-attempt dispatch. We do NOT cache
	// the failure — the next attempt starts fresh.
	failStart := func(resp StartPcvrResponse) {
		if owner {
			s.completeTx(tx.owner, resp, false)
			owner = false
		}
		writeStartPcvr(w, resp)
	}

	// Probe vrserver (fail-closed on error).
	runningVR, err := s.deps.Launcher.VRServerRunning()
	if err != nil {
		failStart(StartPcvrResponse{State: StateDenied, Error: ErrSteamProbe, Message: err.Error()})
		return
	}
	if runningVR {
		// Already up. Phase 1: no file write, no kill.
		// Match or Auto → ALREADY_RUNNING. Mismatch →
		// RECONNECT_REQUIRED with explicit dashboard
		// instruction.
		cur, curErr := s.deps.Adapter.CurrentCodec()
		if curErr != nil {
			finalise(StartPcvrResponse{
				State:   StateReconnectRequired,
				Error:   ErrAlvrUnreadable,
				Message: "SteamVR is running but the ALVR session.json is unreadable (" + curErr.Error() + "); cannot confirm codec — open the ALVR Dashboard, verify Codec is one of H264/Hevc/AV1, then retry",
			})
			return
		}
		if desired := normalizeCodec(req.RequestedCodec); desired == "" || string(desired) == alvr.AutoSentinel || cur == desired {
			finalise(StartPcvrResponse{
				State: StateAlreadyRunning, AppliedCodec: string(cur),
			})
			return
		}
		finalise(StartPcvrResponse{
			State: StateReconnectRequired, Error: ErrAlvrCodec,
			AppliedCodec: string(cur),
			Message: fmt.Sprintf("SteamVR is running with codec=%s but the headset requested codec=%s. Do not kill SteamVR. Open the ALVR Dashboard, set Codec to %s, save, then stop and reconnect ALVR from the headset.",
				cur, req.RequestedCodec, req.RequestedCodec),
		})
		return
	}

	// SteamVR is NOT running. Cold-start path.
	// Phase 1 contract: validate the ALVR session.json BEFORE
	// launch (Codex #15). The headset has not asked for a
	// codec change yet; Auto / matching / cold-start all flow
	// through the same read-only check.
	cur, curErr := s.deps.Adapter.CurrentCodec()
	if curErr != nil {
		// Missing / malformed / wrong version: refuse to launch.
		failStart(StartPcvrResponse{
			State:   StateReconnectRequired,
			Error:   mapCurErr(curErr),
			Message: "ALVR is not installed, the session.json is unreadable, or its server_version is not 20.14.1 (" + curErr.Error() + "); install / repair ALVR v20.14.1 then retry",
		})
		return
	}

	// Now check the requested codec against the ALVR setting.
	desired := normalizeCodec(req.RequestedCodec)
	if desired != "" && string(desired) != alvr.AutoSentinel && cur != desired {
		// Phase 1: refuse to auto-edit the host config. Tell
		// the user to use the ALVR Dashboard.
		failStart(StartPcvrResponse{
			State: StateReconnectRequired, Error: ErrAlvrCodec,
			AppliedCodec: string(cur),
			Message: fmt.Sprintf("ALVR is currently configured for codec=%s but the headset requested %s. The companion does not edit session.json in this build. Open the ALVR Dashboard, set Codec to %s, save, then retry.",
				cur, desired, desired),
		})
		return
	}

	// Launch SteamVR. We use the launcher, which picks the
	// direct path (with -applaunch 250820) when supplied or
	// the rundll32 URL dispatch otherwise. (Phase 1:
	// dashboard-open + matching-codec + cold-start IS allowed
	// by spec — the dashboard is not racing us for the file.)
	if err := s.deps.Launcher.Start(s.deps.SteamPath); err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			// Dispatch may complete after its observation deadline. Retain the
			// lease so a retry cannot launch a second copy in that interval.
			s.markDispatched(tx.owner)
			owner = false
			writeStartPcvr(w, StartPcvrResponse{State: StateStarting, Message: "Launch confirmation timed out; checking whether SteamVR starts."})
			return
		}
		failStart(StartPcvrResponse{
			State: StateDenied, Error: ErrSteamLaunch,
			AppliedCodec: string(cur),
			Message:      err.Error(),
		})
		return
	}

	s.markDispatched(tx.owner)

	// Bounded real-time probe for vrserver. The probe uses
	// a real timer (startupProbe budget) via context, so a
	// frozen injected clock cannot hang the handler. Any
	// probe error is treated as "not observed" (the inflight
	// lease keeps the gate closed so the client polls
	// /status). We never kill or restart the launcher.
	probeCtx, cancel := context.WithTimeout(context.Background(), startupProbe)
	defer cancel()
	observed := s.waitForVRServer(probeCtx)
	if observed {
		// vrserver is up. The startup is complete; cache
		// the final response and clear the lease so a NEW
		// request_id can dispatch again.
		finalise(StartPcvrResponse{State: StateStarted, AppliedCodec: string(cur)})
		return
	}
	// vrserver not yet observed. Return STARTING immediately.
	// The inflight lease persists so concurrent or
	// subsequent requests are suppressed until either the
	// lease expires or a fresh dispatch observes vrserver
	// via /status + a subsequent start attempt. We do NOT
	// call completeTx here — that would drop the lease
	// and allow a second dispatch.
	owner = false
	writeStartPcvr(w, StartPcvrResponse{
		State: StateStarting, AppliedCodec: string(cur),
		Message: "launch dispatched; vrserver not observed within probe budget; poll /status or retry with the same request_id",
	})
}

// waitForVRServer polls the launcher at 200ms intervals
// until ctx is done. A probe error is treated as
// "not running" (returns false). The deadline is enforced by
// ctx so a frozen injected clock cannot hang this loop.
func (s *Server) waitForVRServer(ctx context.Context) bool {
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return false
		case <-tick.C:
			ok, err := s.deps.Launcher.VRServerRunning()
			if err == nil && ok {
				return true
			}
		}
	}
}

// mapCurErr maps an Adapter error to a wire-level code.
func mapCurErr(err error) StartPcvrError {
	switch {
	case errors.Is(err, alvr.ErrFileMissing):
		return ErrAlvrMissing
	case errors.Is(err, alvr.ErrVersionMismatch):
		return ErrAlvrVersion
	case errors.Is(err, alvr.ErrSchemaUnrecognized):
		return ErrAlvrSchema
	case errors.Is(err, alvr.ErrParse):
		return ErrAlvrSchema
	}
	return ErrAlvrUnreadable
}

// payloadHex returns sha256-hex of the raw POST bytes. Stored
// alongside the request_id in the in-flight tx and in the
// result cache so two payloads sharing a request_id do NOT
// collide (same-id-different-payload is REQUEST_ID_CONFLICT).
func payloadHex(body []byte) string {
	h := sha256.Sum256(body)
	return hex.EncodeToString(h[:])
}

// txKind classifies the lookupOrBeginTx outcome.
type txKind int

const (
	txFresh      txKind = iota // caller owns the inflight; dispatch
	txInProgress               // same id + same payload is still running
	txStarting                 // different id, inflight lease is held
	txConflict                 // same id + different payload is inflight
	txCompleted                // cached result hit (still within ttl)
	txCacheFull                // result cache is at capacity AND none expired; refuse
)

type txLookup struct {
	kind   txKind
	cached StartPcvrResponse
	owner  *tx
}

// lookupOrBeginTx reserves one result slot and the global launch gate. A
// preflight owns the gate until it finishes; only a dispatched startup can
// expire. This prevents a slow preflight from launching after its lease was
// handed to another request.
func (s *Server) lookupOrBeginTx(requestID, payloadDigest string) txLookup {
	s.txMu.Lock()
	defer s.txMu.Unlock()
	now := s.deps.Now()
	s.pruneExpiredResultsLocked(now)
	if r, ok := s.results[requestID]; ok {
		if r.payloadDigest != payloadDigest {
			return txLookup{kind: txConflict}
		}
		return txLookup{kind: txCompleted, cached: r.resp}
	}
	if t := s.inflight; t != nil {
		if t.dispatched && now.After(t.leaseUntil) {
			s.inflight = nil
		} else if t.requestID == requestID {
			if t.payloadDigest != payloadDigest {
				return txLookup{kind: txConflict}
			}
			return txLookup{kind: txInProgress}
		} else {
			return txLookup{kind: txStarting}
		}
	}
	if len(s.results) >= txCap {
		return txLookup{kind: txCacheFull}
	}
	t := &tx{requestID: requestID, payloadDigest: payloadDigest, startedAt: now, leaseUntil: now.Add(startupLease)}
	s.inflight = t
	return txLookup{kind: txFresh, owner: t}
}

func (s *Server) pruneExpiredResultsLocked(now time.Time) {
	for id, r := range s.results {
		if now.Sub(r.completed) >= txTTL {
			delete(s.results, id)
		}
	}
}

func (s *Server) markDispatched(owner *tx) {
	s.txMu.Lock()
	defer s.txMu.Unlock()
	if owner != nil && s.inflight == owner {
		owner.dispatched = true
		owner.leaseUntil = s.deps.Now().Add(startupLease)
	}
}

// A process observation can finish a dispatched startup, never a preflight.
// Preserve its receipt so both matching retries and payload conflicts survive.
func (s *Server) resolveDispatchedTx() {
	s.txMu.Lock()
	defer s.txMu.Unlock()
	if t := s.inflight; t != nil && t.dispatched {
		s.finishTxLocked(t, StartPcvrResponse{State: StateStarted}, true)
	}
}

func (s *Server) completeTx(owner *tx, resp StartPcvrResponse, cache bool) {
	s.txMu.Lock()
	defer s.txMu.Unlock()
	s.finishTxLocked(owner, resp, cache)
}

func (s *Server) finishTxLocked(owner *tx, resp StartPcvrResponse, cache bool) {
	// Pointer identity matters: the same request ID may own a later lease.
	if owner == nil || s.inflight != owner {
		return
	}
	if cache {
		// Only the current owner inserts. Its slot was reserved before side effects.
		s.results[owner.requestID] = txResult{resp: resp, completed: s.deps.Now(), payloadDigest: owner.payloadDigest}
	}
	s.inflight = nil
}

// readBoundedBody reads up to max bytes from r.Body. Returns
// ErrBadRequest on overflow.
func readBoundedBody(w http.ResponseWriter, r *http.Request, max int64) ([]byte, error) {
	if max <= 0 {
		return nil, errors.New("readBoundedBody: max <= 0")
	}
	if r.ContentLength > 0 && r.ContentLength > max {
		return nil, fmt.Errorf("body too large: %d > %d", r.ContentLength, max)
	}
	r.Body = http.MaxBytesReader(w, r.Body, max)
	buf := make([]byte, 0, 256)
	tmp := make([]byte, 4096)
	for {
		n, err := r.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}
	}
	return buf, nil
}

// headerMap extracts the X-Vq-* headers from r.
func headerMap(r *http.Request) map[string]string {
	out := make(map[string]string, 4)
	for _, h := range []string{"X-Vq-Sig", "X-Vq-Ts", "X-Vq-Nonce"} {
		if v := r.Header.Get(h); v != "" {
			out[h] = v
		}
	}
	return out
}

func writeStartPcvr(w http.ResponseWriter, resp StartPcvrResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}

// writeErr emits a StartPcvr-shaped error envelope. Used for
// the auth path so the same envelope covers all error modes.
func writeErr(w http.ResponseWriter, status int, code StartPcvrError, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(StartPcvrResponse{
		State:   StateDenied,
		Error:   code,
		Message: msg,
	})
}
