package server

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/enrollment"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
)

const enrollmentMaxBody = 16384

// ErrorEnvelope is the wire-level pairing error. Older clients
// (preview 8) read `error` as a string; newer clients read `code`
// to drive the structured mapping. Both fields are emitted on every
// error response so the same payload serves both client generations.
// `error` text is ALWAYS one of the owner-controlled sentinel
// messages from the enrollment package; never arbitrary remote text.
type ErrorEnvelope struct {
	Error string               `json:"error"`
	Code  enrollment.ErrorCode `json:"code,omitempty"`
}

// emitEnrollmentError writes the structured pairing error envelope.
// Status code is selected from the structured code; message text is
// the matching owner-controlled sentinel message. Unknown errors
// fall back to a generic neutral message and 400; the schema1 happy
// path is unaffected.
func emitEnrollmentError(w http.ResponseWriter, err error) {
	code := enrollment.CodeOf(err)
	status, message := enrollmentErrorStatus(code)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(ErrorEnvelope{Error: message, Code: code})
}

func enrollmentErrorStatus(code enrollment.ErrorCode) (int, string) {
	switch code {
	case enrollment.CodeClosed:
		return http.StatusBadRequest, enrollment.ErrClosed.Error()
	case enrollment.CodeExpired:
		return http.StatusBadRequest, enrollment.ErrExpired.Error()
	case enrollment.CodeBusy:
		return http.StatusBadRequest, enrollment.ErrBusy.Error()
	case enrollment.CodeRateLimited:
		return http.StatusTooManyRequests, enrollment.ErrRateLimited.Error()
	case enrollment.CodeInvalid:
		return http.StatusBadRequest, enrollment.ErrInvalid.Error()
	case enrollment.CodeCapacity:
		return http.StatusBadRequest, enrollment.ErrCapacity.Error()
	case enrollment.CodeStorage:
		return http.StatusBadRequest, enrollment.ErrStorageFailed.Error()
	default:
		return http.StatusBadRequest, "Pairing request rejected. Retry Pair headset on the PC."
	}
}

// allowRemoteAddr rejects source addresses that are NOT
// private/LAN/VPN. Public / WAN / multicast / unspecified /
// link-local-multicast are refused with 403 BEFORE the per-IP
// limiter is consulted so a remote attacker cannot burn the
// limiter's burst budget. The existing legacy code did not gate
// on source class because the legacy "Pair headset" dialog was a
// local-only user gesture; the seamless standalone flow opens the
// door to remote Begin probes, so the source gate is the FIRST
// check.
func allowRemoteAddr(remote string) bool {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	if ip.IsLoopback() {
		// Loopback callers (the manager's HTTP client) bypass the
		// public-source gate because they reach the LAN listener
		// via 127.0.0.1 in test/CI setups. Admin endpoints sit
		// on the dedicated loopback listener and use their own
		// gate.
		return true
	}
	if ip.IsMulticast() {
		// Multicast (any scope, IPv4 or IPv6) cannot be a
		// pairing endpoint. Refuse up front so a forged mDNS
		// payload that resolves to a multicast address cannot
		// fan out Begin requests.
		return false
	}
	// RFC 1918 / RFC 4193 / link-local unicast (169.254/16,
	// fe80::/10) / CGNAT 100.64/10 (Tailscale). Public /
	// unspecified are refused.
	if ip.IsPrivate() {
		return true
	}
	if ip.IsLinkLocalUnicast() {
		return true
	}
	if ip4 := ip.To4(); ip4 != nil {
		if ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
			return true // CGNAT / Tailscale 100.64/10
		}
	}
	return false
}

// beginPerIPCap is the per-IP Begin admission budget. The HTTP
// layer consults this BEFORE the enrollment.Service so a noisy
// LAN peer cannot burn the global six-valid/two-minute budget
// (which is reserved for actual VALID admission decisions). The
// cap is per-IP so a fleet of legitimate headsets does not
// starve each other; 6/min matches the global per-window cap
// and keeps the burst bounded.
const beginPerIPCap = 6

// beginPerIPWindow is the rolling window for the per-IP Begin
// admission limiter.
const beginPerIPWindow = time.Minute

// RegisterEnrollment exposes no administrative operation on the LAN listener.
// The LAN /pairing/* endpoints are gated by:
//
//  1. Source class: the caller must be private/LAN/VPN. Public
//     WAN traffic is refused with 403 BEFORE the per-IP limiter
//     so a remote attacker cannot burn the burst budget.
//  2. Generic per-IP burst limiter (DOS guard) emitting
//     RATE_LIMITED for all /pairing/* paths. Bounds malformed /
//     replayed / replay-of-rejected traffic.
//  3. Per-IP Begin admission limiter (6/minute) on /pairing/begin.
//     Bounds malformed Begin attempts BEFORE the global
//     six-valid/two-minute admission budget is consulted. Poll
//     and Cancel are NOT affected: their legitimate retry rate
//     is bounded only by the generic DOS guard.
//  4. Enrollment-session policies (Begin / Poll / Cancel) on
//     the enrollment.Service. The rolling six-valid/two-minute
//     budget is reserved for VALID admission decisions.
func (s *Server) RegisterEnrollment(service *enrollment.Service) {
	mux := s.hs.Handler.(*http.ServeMux)
	limiter := state.NewRateLimiterFactory(100, time.Minute, 1024, time.Now)
	beginLimiter := state.NewRateLimiterFactory(beginPerIPCap, beginPerIPWindow, 1024, time.Now)
	mux.HandleFunc("/pairing/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if !allowRemoteAddr(r.RemoteAddr) {
			writeJSONErr(w, 403, "Local manager required")
			return
		}
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if !limiter.Allow(host) {
			// Per-IP burst trips here. The shared error envelope
			// emits the stable RATE_LIMITED code so the headset
			// UI can show the same owner-controlled message as the
			// per-session ErrRateLimited sentinel; we use a
			// dedicated limiter-rejection error so the code path
			// stays distinct (this is an HTTP-layer DOS guard, not
			// an enrollment-session policy).
			writeRateLimitedJSONErr(w)
			return
		}
		if r.Method != "POST" {
			writeJSONErr(w, 405, "POST required")
			return
		}
		// Per-IP Begin admission limiter: refuse malformed Begin
		// attempts before they reach the enrollment.Service so
		// the global rolling-window budget stays intact for
		// VALID decisions. Poll and Cancel bypass this limiter
		// so a legitimate polling cadence is not capped.
		if r.URL.Path == "/pairing/begin" && !beginLimiter.Allow(host) {
			writeRateLimitedJSONErr(w)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, enrollmentMaxBody)
		var result any
		var err error
		switch r.URL.Path {
		case "/pairing/begin":
			var req enrollment.BeginRequest
			if err = decodeEnrollment(r.Body, &req); err == nil {
				result, err = service.Begin(req)
			}
		case "/pairing/poll", "/pairing/cancel":
			var req enrollment.Proof
			if err = decodeEnrollment(r.Body, &req); err == nil {
				if r.URL.Path == "/pairing/poll" {
					result, err = service.Poll(req)
				} else {
					err = service.Cancel(req)
					result = map[string]bool{"ok": err == nil}
				}
			}
		default:
			writeJSONErr(w, 404, "Unknown pairing action")
			return
		}
		if err != nil {
			emitEnrollmentError(w, err)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(result)
	})
}

// writeRateLimitedJSONErr emits a 429 with the structured pairing
// envelope so the headset parses the stable RATE_LIMITED code the
// same way it parses the per-session ErrRateLimited sentinel. The
// message text is the matching owner-controlled ErrRateLimited
// string so preview8 clients (which read `error` as a string) keep
// their existing behaviour while newer clients see the code.
func writeRateLimitedJSONErr(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusTooManyRequests)
	_ = json.NewEncoder(w).Encode(ErrorEnvelope{
		Error: enrollment.ErrRateLimited.Error(),
		Code:  enrollment.CodeRateLimited,
	})
}
func decodeEnrollment(r io.Reader, v any) error {
	d := json.NewDecoder(r)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return enrollment.ErrInvalid
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		return enrollment.ErrInvalid
	}
	return nil
}

// EnrollmentAdminHandler is mounted ONLY on the dedicated loopback listener.
// The owner-only global token authenticates management. Headset tokens cannot.
func (s *Server) EnrollmentAdminHandler(service *enrollment.Service) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(host)
		_, deviceHeader := r.Header[http.CanonicalHeaderKey(DeviceHeader)]
		if err != nil || ip == nil || !ip.IsLoopback() || deviceHeader {
			writeJSONErr(w, 403, "Local manager required")
			return
		}
		if r.Method != "POST" {
			writeJSONErr(w, 405, "POST required")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, enrollmentMaxBody)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			writeJSONErr(w, 400, "Invalid request")
			return
		}
		if _, _, ok := s.authenticate(w, r, body, "POST", r.URL.Path, enrollmentMaxBody); !ok {
			return
		}
		switch r.URL.Path {
		case "/pairing/admin/open":
			service.Open()
		case "/pairing/admin/renew":
			service.RenewReceiveLease()
		case "/pairing/admin/pending":
		case "/pairing/admin/close":
			service.Close()
		case "/pairing/admin/forget":
			err = service.ForgetAll()
		case "/pairing/admin/suppress":
			var sup struct {
				UntilUnix int64 `json:"until_unix"`
			}
			if json.Unmarshal(body, &sup) != nil {
				writeJSONErr(w, 400, "Invalid request")
				return
			}
			service.SetSuppressed(time.Unix(sup.UntilUnix, 0))
		case "/pairing/admin/unsuppress":
			service.SetSuppressed(time.Time{})
		case "/pairing/admin/decision":
			var decision struct {
				ID      string `json:"session_id"`
				Code    string `json:"code"`
				Approve bool   `json:"approve"`
			}
			if json.Unmarshal(body, &decision) != nil {
				err = enrollment.ErrInvalid
			} else {
				err = service.Decide(decision.ID, decision.Code, decision.Approve)
			}
		default:
			writeJSONErr(w, 404, "Unknown manager action")
			return
		}
		if err != nil {
			emitEnrollmentError(w, err)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(service.Pending())
	})
}
func (s *Server) ServeEnrollmentAdmin(ctx context.Context, service *enrollment.Service) error {
	listener, err := net.Listen("tcp", "127.0.0.1:28541")
	if err != nil {
		return err
	}
	hs := &http.Server{Handler: s.EnrollmentAdminHandler(service), TLSConfig: s.hs.TLSConfig.Clone(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 15 * time.Second}
	done := make(chan error, 1)
	go func() { done <- hs.ServeTLS(listener, "", "") }()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		service.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = hs.Shutdown(shutdown)
		return <-done
	}
}

// writeJSONErr remains for non-pairing endpoints (rate limiter, 404,
// 405, 403). Pairing error responses go through emitEnrollmentError.
// Messages are stripped of newlines so a misbehaving caller cannot
// inject multi-line garbage into the JSON wire envelope.
func writeJSONErr(w http.ResponseWriter, status int, message string) {
	if i := strings.IndexAny(message, "\n\r"); i >= 0 {
		message = message[:i]
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
