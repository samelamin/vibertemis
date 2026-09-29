// Package server implements the HTTPS control channel.
//
// Phase 1 contract:
//
//   - /capabilities and /status are HMAC-authenticated (no
//     unauthenticated reads of session_digest or process state).
//   - /start_pcvr is HMAC-authenticated and additionally gated
//     by role+mode (exactly "headset" + "pcvr") AND a non-empty
//     signed request_id.
//   - All side effects run inside an in-progress/result
//     transaction keyed by request_id. The inflight is one
//     global pointer protected by a single mutex. Concurrent
//     SAME requests reuse the same inflight; concurrent
//     DIFFERENT requests are suppressed with STARTING while
//     the inflight lease is held. A same-id different-payload
//     request is rejected as a conflict (no second launch).
//     Once the dispatch completes (or fails) the lease is
//     cleared so retries with a NEW request_id can proceed.
//   - Rate limit is PER-IP and layered:
//     IPLimiter    : DOS guard, consulted BEFORE auth (so
//     invalid HMAC requests still consume a
//     token, bounding CPU spent verifying).
//     ActionLimiter: per-IP budget for authenticated
//     /start_pcvr requests; caps the number
//     of legitimate start dispatches.
//     /capabilities and /status consult ONLY IPLimiter and
//     authenticated HMAC; they do not consume ActionLimiter.
//     Invalid HMAC does NOT consume ActionLimiter.
//   - No offline session.json mutation in Phase 1. Matching
//     requested codec or Auto → read-only, launch allowed even
//     when the ALVR Dashboard is open. Mismatch →
//     RECONNECT_REQUIRED with an explicit "configure the codec
//     in the ALVR Dashboard" message, zero file writes, zero
//     launches.
//
// Auth on every endpoint:
//
//	HMAC-SHA256 over METHOD\nPATH\nTIMESTAMP\nNONCE\nsha256-hex(body)
//	Timestamp within ±maxSkew of host clock.
//	Nonce unique within the cache (state.NonceLRU).
//	Per-IP rate limit (IPLimiter on every endpoint; ActionLimiter
//	additionally on /start_pcvr).
//
// negotiated_codec stays empty until a confirmed stream; this
// is the correct Phase 1 contract (Codex adjudicator).
package server

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"github.com/vibertemis/quest-codec-control/host/internal/security"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"github.com/vibertemis/quest-codec-control/host/internal/steamvr"
)

// StartPcvrError is the wire-level error code returned in
// /start_pcvr responses.
type StartPcvrError string

const (
	ErrAuth            StartPcvrError = "AUTH_FAILED"
	ErrRateLimit       StartPcvrError = "RATE_LIMITED"
	ErrNonceReplay     StartPcvrError = "NONCE_REPLAY"
	ErrNonceCacheFull  StartPcvrError = "NONCE_CACHE_FULL"
	ErrBadRequest      StartPcvrError = "BAD_REQUEST"
	ErrSteamProbe      StartPcvrError = "STEAMVR_PROBE"
	ErrDashboardProbe  StartPcvrError = "DASHBOARD_PROBE"
	ErrSteamLaunch     StartPcvrError = "STEAMVR_LAUNCH"
	ErrAlvrCodec       StartPcvrError = "ALVR_CODEC"
	ErrAlvrConflict    StartPcvrError = "ALVR_CONFLICT"
	ErrAlvrSchema      StartPcvrError = "ALVR_SCHEMA"
	ErrAlvrVersion     StartPcvrError = "ALVR_VERSION"
	ErrAlvrMissing     StartPcvrError = "ALVR_MISSING"
	ErrAlvrUnreadable  StartPcvrError = "ALVR_UNREADABLE"
	ErrAlreadyStarting StartPcvrError = "ALREADY_STARTING"
	ErrIdConflict      StartPcvrError = "REQUEST_ID_CONFLICT"
	ErrInternal        StartPcvrError = "INTERNAL"
)

// StartPcvrState is the wire-level result state.
type StartPcvrState string

const (
	StateStarted           StartPcvrState = "STARTED"
	StateAlreadyRunning    StartPcvrState = "ALREADY_RUNNING"
	StateReconnectRequired StartPcvrState = "RECONNECT_REQUIRED"
	StateStarting          StartPcvrState = "STARTING"
	StateDenied            StartPcvrState = "DENIED"
)

// StartPcvrResponse is the response payload.
type StartPcvrResponse struct {
	State           StartPcvrState `json:"state"`
	NegotiatedCodec string         `json:"negotiated_codec"`
	AppliedCodec    string         `json:"applied_codec,omitempty"`
	Error           StartPcvrError `json:"error,omitempty"`
	Message         string         `json:"message,omitempty"`
}

// StartPcvrRequest is the request payload.
type StartPcvrRequest struct {
	NativeProtocol string `json:"native_protocol,omitempty"`
	Role           string `json:"role"`
	Mode           string `json:"mode"`
	RequestedCodec string `json:"requested_codec"`
	RequestId      string `json:"request_id,omitempty"`
}

// CapabilitiesResponse is the /capabilities payload.
type CapabilitiesResponse struct {
	Version         string   `json:"version"`
	Sequence        int      `json:"sequence"`
	NativeProtocol  string   `json:"native_protocol"`
	Codecs          []string `json:"codecs"`
	PyroWave        bool     `json:"pyrowave"`
	PyroWaveReason  string   `json:"pyrowave_reason"`
	NegotiatedCodec string   `json:"negotiated_codec"`
}

// StatusResponse is the /status payload.
type StatusResponse struct {
	VRServer      bool   `json:"vrserver"`
	Dashboard     bool   `json:"dashboard"`
	CurrentCodec  string `json:"current_codec"`
	CodecVerified bool   `json:"codec_verified"`
	SessionDigest string `json:"session_digest"`
	Version       string `json:"version"`
}

// Deps is the server's external dependencies.
//
// IPLimiter and ActionLimiter are both PER-IP. IPLimiter is
// consulted on every endpoint before HMAC (DOS guard);
// ActionLimiter is consulted only AFTER HMAC verifies on
// /start_pcvr so that invalid HMAC requests do not consume
// the legitimate start budget. If ActionLimiter is nil a
// default factory (burst=3, window=30s, cap=1024) is
// constructed at New().
type Deps struct {
	Token         string
	Cert          tls.Certificate
	Adapter       *alvr.Adapter
	Launcher      *steamvr.Launcher
	NonceLRU      *state.NonceLRU
	IPLimiter     *state.RateLimiterFactory
	ActionLimiter *state.RateLimiterFactory
	SteamPath     string
	MaxBody       int64
	MaxSkew       time.Duration
	Now           func() time.Time
}

// Server is the HTTPS control server.
type Server struct {
	deps Deps

	hs *http.Server

	// Atomic launch transaction state, guarded by txMu.
	//
	// The inflight is ONE global pointer (Codex #16 / Agy
	// #1): while it is non-nil and the lease has not
	// expired, all distinct request_ids receive STARTING
	// (no second dispatch), same-id-same-payload receive
	// the in-progress marker, and same-id-different-payload
	// receive REQUEST_ID_CONFLICT (no second launch). The
	// lease is independent of the request's lifetime so the
	// global startup suppression persists across handler
	// returns and across cold-start windows. The lease is
	// bounded by startupLease so a forgotten inflight cannot
	// keep the gate permanently locked.
	txMu     sync.Mutex
	inflight *tx
	results  map[string]txResult
}

// tx is an in-flight /start_pcvr transaction. The single
// inflight pointer is keyed by request_id only; the payload
// digest is stored separately so a same-id-different-payload
// request is detected as a conflict rather than colliding.
type tx struct {
	requestID     string
	payloadDigest string
	startedAt     time.Time
	leaseUntil    time.Time
	dispatched    bool
}

// txResult is a completed /start_pcvr response cached by
// requestID for txTTL after completion. The payload digest
// is stored alongside the response so a same-id-different-
// payload reuse is detected as REQUEST_ID_CONFLICT even
// after the original completed (a host / client can NOT
// silently change the payload and get a cached "yes" for a
// different body).
type txResult struct {
	resp          StartPcvrResponse
	completed     time.Time
	payloadDigest string
}

const (
	// txTTL caps how long a completed tx result is honoured.
	txTTL = 30 * time.Second
	// txCap caps the result cache size; on overflow the
	// oldest expired entry is evicted (or rejected if none
	// expired).
	txCap = 1024
	// startupLease bounds how long a single inflight can
	// keep the global dispatch gate locked. The inflight is
	// cleared once now > leaseUntil even if no completeTx is
	// called, so a failed/dispatch handler cannot leave the
	// gate closed.
	startupLease = 60 * time.Second
	// startupProbe caps how long the handler blocks waiting
	// for vrserver after dispatch. Real-time bounded via a
	// context so a frozen injected clock cannot hang the
	// handler. If the probe does not observe vrserver within
	// this budget the handler returns immediately with
	// STARTING; the lease keeps the gate closed and the
	// client polls /status.
	startupProbe = 500 * time.Millisecond
)

// New returns a Server. ActionLimiter is auto-created with
// burst=3, window=30s, cap=1024 if nil; IPLimiter MUST be
// supplied in production (DOS guard).
func New(deps Deps) (*Server, error) {
	if deps.Token == "" {
		return nil, errors.New("server: empty token")
	}
	if deps.Adapter == nil {
		return nil, errors.New("server: nil adapter")
	}
	if deps.Launcher == nil {
		return nil, errors.New("server: nil launcher")
	}
	if deps.NonceLRU == nil {
		return nil, errors.New("server: nil nonce lru")
	}
	if deps.IPLimiter == nil {
		return nil, errors.New("server: nil ip limiter")
	}
	if deps.ActionLimiter == nil {
		if deps.Now == nil {
			deps.Now = time.Now
		}
		deps.ActionLimiter = state.NewRateLimiterFactory(3, 30*time.Second, 1024, deps.Now)
	}
	if deps.MaxBody == 0 {
		deps.MaxBody = 8 * 1024
	}
	if deps.MaxSkew == 0 {
		deps.MaxSkew = 30 * time.Second
	}
	if deps.Now == nil {
		deps.Now = time.Now
	}
	s := &Server{
		deps:    deps,
		results: make(map[string]txResult, txCap),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/capabilities", s.handleCapabilities)
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/start_pcvr", s.handleStartPcvr)
	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{deps.Cert},
		MinVersion:   tls.VersionTLS12,
	}
	s.hs = &http.Server{
		Handler:           mux,
		TLSConfig:         tlsCfg,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	return s, nil
}

// ListenAndServeTLS binds to addr and serves until ctx is done.
func (s *Server) ListenAndServe(ctx context.Context, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	errCh := make(chan error, 1)
	go func() { errCh <- s.hs.ServeTLS(ln, "", "") }()
	select {
	case <-ctx.Done():
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = s.hs.Shutdown(shutCtx)
		return <-errCh
	case err := <-errCh:
		return err
	}
}

// Handler returns the underlying http.Handler for tests.
func (s *Server) Handler() http.Handler { return s.hs.Handler }

// authenticate verifies the four headers, the clock skew, and
// the nonce. On success it returns the parsed timestamp. On
// failure it writes the canonical error envelope and returns
// false.
//
// Layering:
//
//   - IPLimiter (DOS guard) is consulted for EVERY request
//     including unauth/invalid-HMAC, so a noisy client
//     cannot force unlimited HMAC verification CPU.
//   - ActionLimiter is NOT consulted here. The caller (start
//     handler) consults it AFTER authenticate succeeds so
//     invalid HMAC does NOT consume a legitimate start
//     budget.
func (s *Server) authenticate(w http.ResponseWriter, r *http.Request, body []byte, method, path string, maxBody int64) (time.Time, string, bool) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if host == "" {
		host = "unknown"
	}
	if !s.deps.IPLimiter.Allow(host) {
		writeErr(w, http.StatusTooManyRequests, ErrRateLimit, "rate limit exceeded")
		return time.Time{}, "", false
	}
	ts, nonce, err := security.Verify(method, path, s.deps.Token, headerMap(r), body, s.deps.MaxSkew, s.deps.Now)
	if err != nil {
		writeErr(w, http.StatusOK, ErrAuth, err.Error())
		return time.Time{}, "", false
	}
	replay, err := s.deps.NonceLRU.CheckAndAdd(nonce, ts)
	if err != nil {
		writeErr(w, http.StatusOK, ErrNonceCacheFull, err.Error())
		return time.Time{}, "", false
	}
	if replay {
		writeErr(w, http.StatusOK, ErrNonceReplay, "nonce already seen")
		return time.Time{}, "", false
	}
	return ts, nonce, true
}

// consumeActionBudget consults ActionLimiter for an
// authenticated /start_pcvr caller. It is called ONLY after
// authenticate succeeds so invalid HMAC does not consume the
// legitimate start budget.
func (s *Server) consumeActionBudget(w http.ResponseWriter, r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if host == "" {
		host = "unknown"
	}
	if !s.deps.ActionLimiter.Allow(host) {
		writeErr(w, http.StatusTooManyRequests, ErrRateLimit, "action rate limit exceeded")
		return false
	}
	return true
}

func (s *Server) handleCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if _, _, ok := s.authenticate(w, r, nil, r.Method, r.URL.Path, 0); !ok {
		return
	}
	res, err := s.deps.Adapter.Probe()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, ErrInternal, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(CapabilitiesResponse{
		Version: "0.1.0.4", Sequence: 4, NativeProtocol: alvr.NativeVersion,
		Codecs:          res.Codecs,
		PyroWave:        res.PyroWave,
		PyroWaveReason:  res.PyroWaveReason,
		NegotiatedCodec: res.NegotiatedCodec,
	})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if _, _, ok := s.authenticate(w, r, nil, r.Method, r.URL.Path, 0); !ok {
		return
	}
	vr, err := s.deps.Launcher.VRServerRunning()
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, ErrSteamProbe, "vrserver probe: "+err.Error())
		return
	}
	dash, err := s.deps.Launcher.DashboardRunning()
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, ErrDashboardProbe, "dashboard probe: "+err.Error())
		return
	}
	// Lease transition: when /status observes vrserver=true
	// (and a held inflight lease is in place) the original
	// dispatch has effectively completed. The inflight
	// pointer is cleared so subsequent /start_pcvr calls go
	// through the "existing VR" branch and run normal
	// codec-match validation, NOT a second cold-start. The
	// observed-vrserver event is the canonical phase
	// transition in this design; before this transition
	// every /start_pcvr inside the lease must return
	// STARTING (no second launch) or REQUEST_ID_CONFLICT
	// (no second launch). After this transition the
	// matching-codec → ALREADY_RUNNING path and the
	// mismatched-codec → RECONNECT_REQUIRED path both
	// apply with no lease interference.
	if vr {
		s.resolveDispatchedTx()
	}
	cur, codecErr := s.deps.Adapter.CurrentCodec()
	verified := codecErr == nil
	resp := StatusResponse{
		VRServer:      vr,
		Dashboard:     dash,
		CurrentCodec:  string(cur),
		CodecVerified: verified,
		SessionDigest: s.deps.Adapter.Digest(),
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (s *Server) handleStartPcvr(w http.ResponseWriter, r *http.Request) {
	s.handleStartPcvr1(w, r)
}
