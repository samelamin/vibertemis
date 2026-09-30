package server

import (
	"context"
	"encoding/json"
	"github.com/vibertemis/quest-codec-control/host/internal/enrollment"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"io"
	"net"
	"net/http"
	"time"
)

const enrollmentMaxBody = 16384

// RegisterEnrollment exposes no administrative operation on the LAN listener.
func (s *Server) RegisterEnrollment(service *enrollment.Service) {
	mux := s.hs.Handler.(*http.ServeMux)
	limiter := state.NewRateLimiterFactory(100, time.Minute, 1024, time.Now)
	mux.HandleFunc("/pairing/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if !limiter.Allow(host) {
			writeJSONErr(w, 429, "Too many pairing requests. Retry shortly.")
			return
		}
		if r.Method != "POST" {
			writeJSONErr(w, 405, "POST required")
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
			writeJSONErr(w, 400, err.Error())
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(result)
	})
}
func decodeEnrollment(r io.Reader, v any) error {
	d := json.NewDecoder(r)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return err
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
		case "/pairing/admin/pending":
		case "/pairing/admin/close":
			service.Close()
		case "/pairing/admin/forget":
			err = service.ForgetAll()
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
			writeJSONErr(w, 400, err.Error())
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

func writeJSONErr(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
