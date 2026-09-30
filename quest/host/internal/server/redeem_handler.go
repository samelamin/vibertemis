// redeem_handler.go: HTTP handler for POST /pairing/redeem.
//
// This is the ONLY un-HMAC endpoint on the companion. The
// body is bounded by bridge.MaxBodyBytes (16 KiB) and the
// per-IP DOS limiter is consulted FIRST so a noisy client
// cannot drive unbounded signature verifications.
//
// The client cert is NOT supplied in the request body —
// it is bound to the grant at issue_grant time. The
// companion derives the public key from the stored grant
// and verifies the RSA signature against that grant-bound
// cert. The body shape is:
//
//	{schema:1, grant, client_nonce, signature}
package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/bridge"
)

// DeviceHeader is the wire header that selects a per-device
// HMAC credential for /capabilities, /status and
// /start_pcvr. Absent header => legacy global token
// (preserves full backwards compatibility).
const DeviceHeader = "X-Vq-Device"

// RedeemHandler is the per-server redeem endpoint wiring.
type RedeemHandler struct {
	Service *bridge.Service
	Persist bridge.Persist
	limiter *state.RateLimiterFactory
}

// RegisterRedeem attaches the redeem endpoint to an
// existing http.ServeMux. Kept separate from the existing
// server constructor so a legacy-only build can omit it.
func RegisterRedeem(mux *http.ServeMux, h *RedeemHandler) {
	if h == nil || h.Service == nil {
		return
	}
	h.limiter = state.NewRateLimiterFactory(20, time.Minute, 1024, time.Now)
	mux.HandleFunc("/pairing/redeem", h.handleRedeem)
}

func (h *RedeemHandler) handleRedeem(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if h.limiter == nil || !h.limiter.Allow(host) {
		writeJSONErr(w, http.StatusTooManyRequests, "Too many pairing attempts. Retry in a minute.")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, bridge.MaxBodyBytes)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeJSONErr(w, http.StatusBadRequest, "body too large or unreadable: "+err.Error())
		return
	}
	// The companion gets its live Authorizer from the
	// service (the pipe runner wires it on accept, clears
	// on disconnect). If no pipe is active, fail closed.
	resp, err := h.Service.Redeem(bridge.RedeemInput{
		Body:       body,
		Authorizer: h.Service.CurrentAuthorizer(),
		Persist:    h.Persist,
	})
	if err != nil {
		status := http.StatusOK
		switch {
		case errors.Is(err, bridge.ErrSchema), errors.Is(err, bridge.ErrBadField), errors.Is(err, bridge.ErrBadNonce):
			status = http.StatusBadRequest
		case errors.Is(err, bridge.ErrBadSignature), errors.Is(err, bridge.ErrUnauthorized):
			status = http.StatusUnauthorized
		case errors.Is(err, bridge.ErrLimitsExceeded):
			status = http.StatusTooManyRequests
		case errors.Is(err, bridge.ErrHostUnreachable), errors.Is(err, bridge.ErrHostDenied):
			status = http.StatusServiceUnavailable
		case errors.Is(err, bridge.ErrPersistFailed):
			status = http.StatusServiceUnavailable
		case errors.Is(err, bridge.ErrConcurrentRedemption):
			status = http.StatusConflict
		case errors.Is(err, bridge.ErrGrantExpired), errors.Is(err, bridge.ErrGrantRevoked), errors.Is(err, bridge.ErrGrantReplay):
			status = http.StatusGone
		default:
			status = http.StatusInternalServerError
		}
		writeJSONErr(w, status, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func writeJSONErr(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg, "detail": fmt.Sprintf("status=%d", status)})
}
