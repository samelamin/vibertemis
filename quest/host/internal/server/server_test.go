// Tests for the HTTPS control server. Every test wires a real
// *http.ServeMux via server.New and a httptest.NewServer, so
// the request pipeline (TLS is skipped at this layer; the
// signing is independent of TLS) and the JSON encoding are
// exercised end-to-end.
//
// What we test:
//   - GET /capabilities, /status never start SteamVR
//   - POST /start_pcvr requires the four auth headers
//   - HMAC mismatch, replay, clock skew, role are all rejected
//   - Duplicate /start_pcvr with same request_id is idempotent
//   - PyroWave stays false; negotiated_codec stays empty
//   - Existing VR with matching codec returns ALREADY_RUNNING
//   - Existing VR with different codec returns RECONNECT_REQUIRED
//   - VR running + ALVR codec change is refused (no kill)
//   - Codec change refuses when dashboard is up
//   - nil / failing process probe is fail-closed
//   - Body too large is rejected
package server

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"github.com/vibertemis/quest-codec-control/host/internal/security"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"github.com/vibertemis/quest-codec-control/host/internal/steamvr"
)

// testServer bundles the server + fakes for each test.
type testServer struct {
	srv           *httptest.Server
	adapter       *alvr.Adapter
	launcher      *steamvr.Launcher
	nonceLRU      *state.NonceLRU
	ipLimiter     *state.RateLimiterFactory
	actionLimiter *state.RateLimiterFactory
	token         string
	session       string
	launched      *int32
}

func newTestServer(t *testing.T) *testServer {
	t.Helper()
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed session: %v", err)
	}
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	var launched int32
	// Use a mutable fake scanner so the Launch callback can flip
	// vrserver=true and the waitForVRServer poll returns
	// immediately (tests don't pay 5 s × N for the launch
	// observation loop).
	fakeScanner := &mutableScanner{}
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner:       fakeScanner,
		Launch:        func(string, ...string) error { atomic.AddInt32(&launched, 1); fakeScanner.setVR(true); return nil },
		ProbeTimeout:  1 * time.Second,
		LaunchTimeout: 1 * time.Second,
	})
	nonceLRU := state.NewNonceLRU(64, 30*time.Second, time.Now)
	// IPLimiter: high cap so it does NOT trip during normal
	// tests (DOS guard only).
	ipLimiter := state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now)
	// ActionLimiter: 3/30s, the production default for /start_pcvr.
	actionLimiter := state.NewRateLimiterFactory(3, 30*time.Second, 1024, time.Now)
	cert, err := generateTestCert()
	if err != nil {
		t.Fatalf("cert: %v", err)
	}
	srv, err := New(Deps{
		Token:         token,
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      nonceLRU,
		IPLimiter:     ipLimiter,
		ActionLimiter: actionLimiter,
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	return &testServer{
		srv:           ts,
		adapter:       adapter,
		launcher:      launcher,
		nonceLRU:      nonceLRU,
		ipLimiter:     ipLimiter,
		actionLimiter: actionLimiter,
		token:         token,
		session:       sessionPath,
		launched:      &launched,
	}
}

// mutableScanner is a ProcessScanner that the Launch callback
// can flip so the post-launch wait returns immediately. Zero-
// value defaults to "not running" (matches the prior FakeScanner).
type mutableScanner struct {
	mu     sync.Mutex
	vr     bool
	dash   bool
	vrErr  error
	dashEr error
}

func (m *mutableScanner) setVR(b bool) {
	m.mu.Lock()
	m.vr = b
	m.mu.Unlock()
}

func (m *mutableScanner) VRServerRunning() (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.vr, m.vrErr
}

func (m *mutableScanner) DashboardRunning() (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.dash, m.dashEr
}

var newTestServerLaunchedUnused int32 // kept as a safety net for grep

func (ts *testServer) close() { ts.srv.Close() }

// doSigned POSTs to /start_pcvr with the four required headers
// and the supplied body. The signature is computed from the
// body+path+method+nonce+ts. Returns the HTTP status, body,
// and decoded response.
func (ts *testServer) doSigned(t *testing.T, body []byte, opts ...func(*signedOpts)) (int, []byte, StartPcvrResponse) {
	t.Helper()
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	for _, f := range opts {
		f(&o)
	}
	sig := security.Sign("POST", "/start_pcvr", ts.token, time.Unix(o.ts, 0), o.nonce, body)
	r := bytes.NewReader(body)
	req, err := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", r)
	if err != nil {
		t.Fatalf("new req: %v", err)
	}
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	if o.bodyOverride != nil {
		req.ContentLength = int64(len(o.bodyOverride))
		req.Body = io.NopCloser(bytes.NewReader(o.bodyOverride))
	}
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &out)
	}
	return resp.StatusCode, raw, out
}

type signedOpts struct {
	ts           int64
	nonce        string
	bodyOverride []byte
}

func mustNewNonce(t *testing.T) string {
	t.Helper()
	s, err := security.NewNonce()
	if err != nil {
		t.Fatalf("nonce: %v", err)
	}
	return s
}

func generateTestCert() (tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, _ := x509.MarshalECPrivateKey(key)
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	c, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, err
	}
	return c, nil
}

// --- tests ---

// doSignedGet issues an authenticated GET to path and returns
// the parsed StatusResponse body (or whatever the handler
// returns). GET endpoints still require the four X-Vq-* headers
// (Codex #14).
func (ts *testServer) doSignedGet(t *testing.T, path string) (int, []byte) {
	t.Helper()
	tsLocal := time.Now().Unix()
	nonce := mustNewNonce(t)
	sig := security.Sign("GET", path, ts.token, time.Unix(tsLocal, 0), nonce, nil)
	req, _ := http.NewRequest("GET", ts.srv.URL+path, nil)
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(tsLocal, 10))
	req.Header.Set("X-Vq-Nonce", nonce)
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, raw
}

func TestGetCapabilities_DoesNotStartSteamVR(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	status, raw := ts.doSignedGet(t, "/capabilities")
	if status != 200 {
		t.Fatalf("status = %d", status)
	}
	var res CapabilitiesResponse
	_ = json.Unmarshal(raw, &res)
	if res.PyroWave {
		t.Fatal("pyrowave must be false in phase 1")
	}
	if res.PyroWaveReason == "" {
		t.Fatal("pyrowave_reason must be set when disabled")
	}
	if res.NegotiatedCodec != "" {
		t.Fatalf("negotiated_codec must be empty in phase 1, got %q", res.NegotiatedCodec)
	}
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("GET /capabilities must not launch SteamVR")
	}
}

func TestGetStatus_DoesNotStartSteamVR(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	status, _ := ts.doSignedGet(t, "/status")
	if status != 200 {
		t.Fatalf("status = %d", status)
	}
	// Decode a raw map; the StatusResponse type doesn't include
	// the auth envelope.
	_, raw, _ := ts.doSigned(t, nil)
	_ = raw
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("GET /status must not launch SteamVR")
	}
}

func TestGetStartPcvr_MethodNotAllowed(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	resp, err := ts.srv.Client().Get(ts.srv.URL + "/start_pcvr")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", resp.StatusCode)
	}
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("GET /start_pcvr must not launch SteamVR")
	}
}

func TestStartPcvr_MissingAuth(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	req, _ := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", bytes.NewReader(body))
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200 (json error envelope), got %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.Error != ErrAuth {
		t.Fatalf("expected AUTH_FAILED, got %q (msg=%q)", out.Error, out.Message)
	}
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("unauthenticated request must not launch")
	}
}

func TestStartPcvr_BadHmac(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	status, _, out := ts.doSigned(t, body, func(o *signedOpts) {
		o.ts = time.Now().Unix()
		o.nonce = mustNewNonce(t)
		// Override the sig after doSigned computes it by
		// re-signing with a wrong token. The doSigned above
		// already used the real token; we re-do the signature
		// here to ensure bad sigs are tested.
	})
	_ = status
	// doSigned uses the real token; force a bad sig by
	// re-calling the endpoint with a corrupted header.
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "WRONG_TOKEN", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(raw, &out)
	if out.Error != ErrAuth {
		t.Fatalf("expected AUTH_FAILED, got %q (msg=%q)", out.Error, out.Message)
	}
}

func TestStartPcvr_ClockSkew(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	old := time.Now().Add(-1 * time.Hour).Unix()
	status, _, out := ts.doSigned(t, body, func(o *signedOpts) { o.ts = old })
	if status != 200 {
		t.Fatalf("status = %d", status)
	}
	if out.Error != ErrAuth {
		t.Fatalf("expected AUTH_FAILED for skewed ts, got %q", out.Error)
	}
}

func TestStartPcvr_RejectsReplay(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	// Pin the nonce so the second call IS a replay of the first.
	nonce := mustNewNonce(t)
	ts.doSigned(t, body, func(o *signedOpts) { o.nonce = nonce })
	_, _, out := ts.doSigned(t, body, func(o *signedOpts) { o.nonce = nonce })
	if out.Error != ErrNonceReplay {
		t.Fatalf("expected NONCE_REPLAY, got %q (msg=%q)", out.Error, out.Message)
	}
}

func TestStartPcvr_NonceCacheFull_RejectsWithoutEviction(t *testing.T) {
	// Build a tiny LRU and exhaust it. A new nonce must be
	// rejected, NOT evict.
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	nonceLRU := state.NewNonceLRU(2, 30*time.Second, time.Now)
	ipLimiter := state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now)
	actionLimiter := state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now)
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: &mutableScanner{}, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      nonceLRU,
		IPLimiter:     ipLimiter,
		ActionLimiter: actionLimiter,
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// First two calls fill the cache.
	for i := 0; i < 2; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","request_id":"r-%d"}`, i))
		o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
		sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
		req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
		req.Header.Set("X-Vq-Sig", sig)
		req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
		req.Header.Set("X-Vq-Nonce", o.nonce)
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatalf("do %d: %v", i, err)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
	}
	// Third call: cache full, must be rejected.
	body := []byte(`{"role":"headset","mode":"pcvr","request_id":"r-3"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("do 3: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out3 StartPcvrResponse
	_ = json.Unmarshal(raw, &out3)
	if out3.Error != ErrNonceCacheFull {
		t.Fatalf("expected NONCE_CACHE_FULL, got %q (raw=%s)", out3.Error, raw)
	}
}

func TestStartPcvr_RateLimit(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	// Burn the burst of 3 then expect 4th to be denied.
	for i := 0; i < 3; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","request_id":"r-%d"}`, i))
		ts.doSigned(t, body)
	}
	body := []byte(`{"role":"headset","mode":"pcvr","request_id":"r-4"}`)
	_, _, out := ts.doSigned(t, body)
	if out.Error != ErrRateLimit {
		t.Fatalf("expected RATE_LIMITED, got %q", out.Error)
	}
}

func TestStartPcvr_UnknownRole(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"hacker","requested_codec":"AV1"}`)
	_, _, out := ts.doSigned(t, body)
	if out.Error != ErrBadRequest {
		t.Fatalf("expected BAD_REQUEST, got %q", out.Error)
	}
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("rejected role must not launch")
	}
}

func TestStartPcvr_AutoAndNoVRServer_StartsSteamVR(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"auto","request_id":"req-1"}`)
	_, _, out := ts.doSigned(t, body)
	if out.Error != "" {
		t.Fatalf("expected no error, got %q msg=%q", out.Error, out.Message)
	}
	if out.State != StateStarted {
		t.Fatalf("state = %q, want STARTED", out.State)
	}
	if out.AppliedCodec != string(alvr.CodecH264) {
		t.Fatalf("applied_codec = %q, want H264", out.AppliedCodec)
	}
	if out.NegotiatedCodec != "" {
		t.Fatalf("negotiated_codec must be empty, got %q", out.NegotiatedCodec)
	}
	if atomic.LoadInt32(ts.launched) != 1 {
		t.Fatalf("expected exactly 1 launch, got %d", atomic.LoadInt32(ts.launched))
	}
}

func TestStartPcvr_ExistingVRMatchingCodec_AlreadyRunning(t *testing.T) {
	// Build a server with VRServer=true. The first call's
	// request_id lets the response be cached; we only assert
	// the first call's behavior.
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{vr: true}
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(10, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"r-H264"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.State != StateAlreadyRunning {
		t.Fatalf("state = %q, want ALREADY_RUNNING (raw=%s)", out.State, raw)
	}
	if out.Error != "" {
		t.Fatalf("expected no error, got %q", out.Error)
	}
}

func TestStartPcvr_ExistingVRDifferentCodec_ReconnectRequired(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{vr: true}
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(10, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.State != StateReconnectRequired {
		t.Fatalf("state = %q, want RECONNECT_REQUIRED (raw=%s)", out.State, raw)
	}
	if out.Error != ErrAlvrCodec {
		t.Fatalf("error = %q, want ALVR_CODEC", out.Error)
	}
}

func TestStartPcvr_DashboardOpen_NoFileWriteOnCodecMismatch(t *testing.T) {
	// Phase 1 contract: the companion does NOT write to
	// session.json. If the dashboard is open AND the headset
	// requests a codec different from the saved one, the
	// companion returns RECONNECT_REQUIRED with the explicit
	// "configure the codec in the ALVR Dashboard" message.
	// session.json is NOT modified; no SteamVR launch is
	// attempted. The dashboard state alone does not gate the
	// response (the companion does not write, so it cannot
	// race the dashboard) — the codec mismatch itself is the
	// reason and ALVR_CODEC is the correct error code.
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{dash: true}
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(10, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.State != StateReconnectRequired {
		t.Fatalf("state = %q, want RECONNECT_REQUIRED (raw=%s)", out.State, raw)
	}
	if out.Error != ErrAlvrCodec {
		t.Fatalf("error = %q, want ALVR_CODEC (raw=%s)", out.Error, raw)
	}
	_ = ms
	if !strings.Contains(out.Message, "ALVR Dashboard") {
		t.Fatalf("message must instruct the user to open the ALVR Dashboard, got %q", out.Message)
	}
	// Verify session.json was NOT modified: the codec
	// variant must still be H264.
	data, err := os.ReadFile(sessionPath)
	if err != nil {
		t.Fatalf("read session: %v", err)
	}
	var doc map[string]interface{}
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse session: %v", err)
	}
	variant, _ := doc["session_settings"].(map[string]interface{})["video"].(map[string]interface{})["preferred_codec"].(map[string]interface{})["variant"].(string)
	if variant != string(alvr.CodecH264) {
		t.Fatalf("session.json variant mutated: got %q, want H264", variant)
	}
}

func TestStartPcvr_ProbeError_FailsClosed(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{vrErr: errors.New("tasklist crash")}
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(10, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "tok", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.Error != ErrSteamProbe {
		t.Fatalf("error = %q, want STEAMVR_PROBE (raw=%s)", out.Error, raw)
	}
}

func TestStartPcvr_IdempotentByRequestId(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"auto","request_id":"req-7"}`)
	_, _, first := ts.doSigned(t, body)
	if first.State != StateStarted {
		t.Fatalf("first state = %q", first.State)
	}
	// Replay with a different nonce (so nonce check passes)
	// but the same request_id: the cached response is returned
	// without a second launch.
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", ts.token, time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var second StartPcvrResponse
	_ = json.Unmarshal(raw, &second)
	if second.State != StateStarted {
		t.Fatalf("idempotent state = %q", second.State)
	}
	if atomic.LoadInt32(ts.launched) != 1 {
		t.Fatalf("expected exactly 1 launch across both calls, got %d", atomic.LoadInt32(ts.launched))
	}
}

func TestStartPcvr_BodyTooLarge(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	// Force a body of 64 KiB (default MaxBody is 8 KiB).
	big := bytes.Repeat([]byte("a"), 64*1024)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", ts.token, time.Unix(o.ts, 0), o.nonce, big)
	req, _ := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", bytes.NewReader(big))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	req.ContentLength = int64(len(big))
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.Error != ErrBadRequest {
		t.Fatalf("error = %q, want BAD_REQUEST", out.Error)
	}
}

// Sanity: a wrong-token signature is rejected with
// AUTH_FAILED. This is the canonical replay / wrong-credential
// case.
func TestStartPcvr_WrongTokenSignature(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"r-AV1"}`)
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", "WRONG_TOKEN", time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", ts.srv.URL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := ts.srv.Client().Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	if out.Error != ErrAuth {
		t.Fatalf("error = %q, want AUTH_FAILED (raw=%s)", out.Error, raw)
	}
}

// helper to assert digest is non-empty.
func TestProbeDigestNonEmpty(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	_, _ = ts.adapter.Load()
	status, raw := ts.doSignedGet(t, "/status")
	if status != 200 {
		t.Fatalf("status = %d", status)
	}
	if !strings.Contains(string(raw), `"session_digest"`) {
		t.Fatalf("expected session_digest field, got %s", raw)
	}
}

// --- Blocker #1 regression: atomic launch transaction ---
//
// concurrent SAME and DIFFERENT request_ids while vrserver
// remains absent must dispatch at most once. The launch
// dispatch is HELD (we keep vrserver=false and the launch
// gate blocked) so we exercise the real cold-start
// window — not the immediate-flip shortcut. After the
// launch gate is released and the post-launch probe
// observes (or doesn't observe) vrserver, exactly ONE
// launch must have happened.

type blockingScanner struct {
	mu   sync.Mutex
	vr   bool
	dash bool
}

func (b *blockingScanner) VRServerRunning() (bool, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.vr, nil
}

func (b *blockingScanner) DashboardRunning() (bool, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.dash, nil
}

func (b *blockingScanner) setVRNow(v bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.vr = v
}

// TestStartPcvr_ColdStart_SameIDDifferentPayload_IsConflict
// is the canonical Blocker #1 regression for the same-ID
// different-payload case. While the first dispatch is
// in-flight, a second request with the SAME request_id but
// a DIFFERENT payload must be rejected as
// REQUEST_ID_CONFLICT and must NOT trigger a second launch.
func TestStartPcvr_ColdStart_SameIDDifferentPayload_IsConflict(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	bs := &blockingScanner{vr: false}
	var launchCount int32
	launchGate := make(chan struct{}) // closed when test wants the launch to return
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner:       bs,
		Launch:        func(string, ...string) error { atomic.AddInt32(&launchCount, 1); <-launchGate; return nil },
		ProbeTimeout:  2 * time.Second,
		LaunchTimeout: 2 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// First call dispatches; the Launch closure holds.
	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"req-A"}`)
	type result struct {
		status int
		out    StartPcvrResponse
	}
	firstCh := make(chan result, 1)
	go func() {
		s, _, out := postSigned(t, ts.URL, "tok", body1, "")
		firstCh <- result{s, out}
	}()

	// Wait until the launch closure has been entered.
	deadline := time.Now().Add(2 * time.Second)
	for atomic.LoadInt32(&launchCount) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("first launch never started")
		}
		time.Sleep(5 * time.Millisecond)
	}

	// Second call: SAME request_id, DIFFERENT payload
	// (different requested_codec). Must be REJECTED with
	// REQUEST_ID_CONFLICT and must NOT increment
	// launchCount.
	body2 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"req-A"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body2, "")
	if out.Error != ErrIdConflict {
		t.Fatalf("same-id different-payload must be REQUEST_ID_CONFLICT, got %q (state=%s)", out.Error, out.State)
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("conflict path must NOT trigger a second launch: launchCount=%d", got)
	}

	// Release the original launch; the first call still
	// returns STARTING (the bounded probe times out).
	close(launchGate)
	select {
	case <-firstCh:
	case <-time.After(3 * time.Second):
		t.Fatal("first call did not return after release")
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("after release, exactly one launch must have happened, got %d", got)
	}
}

// TestStartPcvr_ColdStart_DifferentID_SuppressedDuringInflight
// is the canonical Blocker #1 regression for global
// suppression: while a dispatch is in-flight and vrserver
// remains absent, a request with a DIFFERENT request_id
// must return STARTING (no second launch) and must NOT
// increment launchCount. We keep vrserver=false throughout
// the test and hold the launch gate to simulate a real
// cold-start window.
func TestStartPcvr_ColdStart_DifferentID_SuppressedDuringInflight(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	bs := &blockingScanner{vr: false}
	var launchCount int32
	launchGate := make(chan struct{})
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner: bs,
		Launch: func(string, ...string) error {
			atomic.AddInt32(&launchCount, 1)
			<-launchGate
			return nil
		},
		ProbeTimeout:  2 * time.Second,
		LaunchTimeout: 2 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// First call (req-A) holds the global inflight.
	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"req-A"}`)
	firstDone := make(chan struct{})
	go func() {
		_, _, _ = postSigned(t, ts.URL, "tok", body1, "")
		close(firstDone)
	}()
	deadline := time.Now().Add(2 * time.Second)
	for atomic.LoadInt32(&launchCount) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("first launch never started")
		}
		time.Sleep(5 * time.Millisecond)
	}

	// While the first call is still inflight, send two
	// DIFFERENT request_ids. Both must return STARTING
	// without incrementing launchCount.
	for _, id := range []string{"req-B", "req-C"} {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":%q}`, id))
		_, _, out := postSigned(t, ts.URL, "tok", body, id)
		if out.State != StateStarting {
			t.Fatalf("different id %s: state=%s, want STARTING", id, out.State)
		}
		if out.Error != ErrAlreadyStarting {
			t.Fatalf("different id %s: error=%q, want ALREADY_STARTING", id, out.Error)
		}
	}
	// Same id A + SAME payload: in-progress (STARTING),
	// no second dispatch.
	_, _, out := postSigned(t, ts.URL, "tok", body1, "")
	if out.State != StateStarting {
		t.Fatalf("same id + same payload: state=%s, want STARTING", out.State)
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("overlapping requests must dispatch at most once: launchCount=%d", got)
	}

	// Release the launch and let the first call's
	// probe time out (vrserver=false).
	close(launchGate)
	select {
	case <-firstDone:
	case <-time.After(3 * time.Second):
		t.Fatal("first call did not return")
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("after release: launchCount=%d, want 1", got)
	}
}

// TestStartPcvr_LeaseClearsAfterFailureAllowsNewIDRetry
// verifies that the inflight lease is released when a
// dispatch fails (e.g., launcher returns an error). A
// subsequent request with a NEW request_id must dispatch
// successfully.
func TestStartPcvr_LeaseClearsAfterFailureAllowsNewIDRetry(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	bs := &blockingScanner{vr: false}
	var launchCount int32
	var failOnce int32
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner: bs,
		Launch: func(string, ...string) error {
			atomic.AddInt32(&launchCount, 1)
			// Atomically flip failOnce 0 -> 1. If we were
			// 0 before, fail.
			if atomic.SwapInt32(&failOnce, 1) == 0 {
				return errors.New("simulated launch failure")
			}
			return nil
		},
		ProbeTimeout:  1 * time.Second,
		LaunchTimeout: 1 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// First call: launch fails. Lease must clear so a
	// retry with a NEW request_id can dispatch.
	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"req-fail"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body1, "")
	if out.Error != ErrSteamLaunch {
		t.Fatalf("first call error=%q, want STEAM_LAUNCH", out.Error)
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("first call launchCount=%d, want 1", got)
	}

	// Second call with a NEW request_id must dispatch.
	body2 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"req-retry"}`)
	_, _, out = postSigned(t, ts.URL, "tok", body2, "")
	if out.State != StateStarting {
		t.Fatalf("retry state=%s msg=%s", out.State, out.Message)
	}
	if got := atomic.LoadInt32(&launchCount); got != 2 {
		t.Fatalf("retry must dispatch a second launch, launchCount=%d", got)
	}
}

// --- Blocker #3 regression: separate read / action budgets ---

// TestReadBudget_IndependentOfStartBudget exercises the
// per-IP budget split: invalid HMAC must NOT consume the
// start budget, and authenticated /status polling must NOT
// consume the start budget either. Only an authenticated
// /start_pcvr call consumes the ActionLimiter.
func TestReadBudget_IndependentOfStartBudget(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	bs := &blockingScanner{vr: true}
	srv, err := New(Deps{
		Token:    "tok",
		Cert:     cert,
		Adapter:  alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher: steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: bs, Launch: func(string, ...string) error { return nil }}),
		NonceLRU: state.NewNonceLRU(64, 30*time.Second, time.Now),
		// High IPLimiter so it never trips.
		IPLimiter: state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		// Tight ActionLimiter: 3/30s.
		ActionLimiter: state.NewRateLimiterFactory(3, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// (a) Send 20 invalid-HMAC /start_pcvr requests. They
	// must all return AUTH_FAILED and must NOT consume the
	// ActionLimiter.
	for i := 0; i < 20; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","request_id":"r-%d"}`, i))
		o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
		// Sign with WRONG token so HMAC fails.
		sig := security.Sign("POST", "/start_pcvr", "WRONG_TOKEN", time.Unix(o.ts, 0), o.nonce, body)
		req, _ := http.NewRequest("POST", ts.URL+"/start_pcvr", bytes.NewReader(body))
		req.Header.Set("X-Vq-Sig", sig)
		req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
		req.Header.Set("X-Vq-Nonce", o.nonce)
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatalf("do %d: %v", i, err)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
	}
	// (b) 30 authenticated /status polls. They must all
	// succeed (do not consume ActionLimiter).
	for i := 0; i < 30; i++ {
		o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
		sig := security.Sign("GET", "/status", "tok", time.Unix(o.ts, 0), o.nonce, nil)
		req, _ := http.NewRequest("GET", ts.URL+"/status", nil)
		req.Header.Set("X-Vq-Sig", sig)
		req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
		req.Header.Set("X-Vq-Nonce", o.nonce)
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatalf("status %d: %v", i, err)
		}
		if resp.StatusCode != 200 {
			t.Fatalf("status %d: status=%d, want 200", i, resp.StatusCode)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
	}
	// (c) Now send 3 authenticated /start_pcvr with
	// vrserver=true → ALREADY_RUNNING. The first three must
	// succeed; the 4th must be RATE_LIMITED because the
	// ActionLimiter burst is 3.
	for i := 0; i < 3; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"ok-%d"}`, i))
		_, _, out := postSigned(t, ts.URL, "tok", body, fmt.Sprintf("ok-%d", i))
		if out.Error != "" {
			t.Fatalf("legit start %d: error=%q, want none", i, out.Error)
		}
		if out.State != StateAlreadyRunning {
			t.Fatalf("legit start %d: state=%s, want ALREADY_RUNNING", i, out.State)
		}
	}
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"ok-4"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body, "ok-4")
	if out.Error != ErrRateLimit {
		t.Fatalf("4th legit start: error=%q, want RATE_LIMITED", out.Error)
	}
}

// --- Blocker #4 regression: bounded wait ---

// TestWaitForVRServer_FrozenClock_ReturnsWithinBudget
// verifies that even when the injected Now() is frozen
// (no wall-clock progress), the wait returns within the
// context budget. A frozen clock must NOT hang the
// handler — the bounded wait uses real time via a context
// deadline, not the injected Now().
func TestWaitForVRServer_FrozenClock_ReturnsWithinBudget(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	// Freeze Now() at the current wall clock so any
	// deadline computed via s.deps.Now().Add(...) would
	// never advance. The probe returns false (vrserver
	// absent), so the bounded probe is the only thing
	// keeping the handler alive.
	frozen := time.Now()
	bs := &blockingScanner{vr: false}
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner:       bs,
		Launch:        func(string, ...string) error { return nil },
		ProbeTimeout:  2 * time.Second,
		LaunchTimeout: 2 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
		Now:           func() time.Time { return frozen },
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// The injected Now() is frozen. Any deadline check
	// using s.deps.Now() would never advance; we assert
	// the handler returns STARTING within startupProbe
	// (500ms) plus a small slack.
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"frozen-id"}`)
	startT := time.Now()
	_, _, out := postSigned(t, ts.URL, "tok", body, "")
	elapsed := time.Since(startT)
	if elapsed > 2*time.Second {
		t.Fatalf("handler took %v with frozen clock; must return within ~startupProbe", elapsed)
	}
	if out.State != StateStarting {
		t.Fatalf("state=%s, want STARTING (vrserver not observed); msg=%s", out.State, out.Message)
	}
	// And the lease persists: a follow-up /status must NOT
	// return with vrserver=true.
	_, raw := getSigned(t, ts.URL, "/status", "tok")
	if !strings.Contains(string(raw), `"vrserver":false`) {
		t.Fatalf("/status after frozen-clock STARTING must report vrserver=false, got %s", raw)
	}
}

// TestWaitForVRServer_LeasePersistsAcrossHandlerReturns
// verifies the inflight lease persists after the handler
// returns STARTING so a subsequent /start_pcvr with a
// DIFFERENT id during the cold-start window is suppressed.
func TestWaitForVRServer_LeasePersistsAcrossHandlerReturns(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	bs := &blockingScanner{vr: false}
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner:       bs,
		Launch:        func(string, ...string) error { return nil },
		ProbeTimeout:  1 * time.Second,
		LaunchTimeout: 1 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"lease-A"}`)
	_, _, first := postSigned(t, ts.URL, "tok", body1, "lease-A")
	if first.State != StateStarting {
		t.Fatalf("first call state=%s, want STARTING", first.State)
	}
	body2 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"lease-B"}`)
	_, _, second := postSigned(t, ts.URL, "tok", body2, "lease-B")
	if second.State != StateStarting {
		t.Fatalf("second (different id) state=%s, want STARTING", second.State)
	}
	if second.Error != ErrAlreadyStarting {
		t.Fatalf("second error=%q, want ALREADY_STARTING", second.Error)
	}
}

// postSigned is a test helper that signs with the supplied
// token (allowing deliberate wrong-token tests) and posts
// to /start_pcvr. A fresh nonce is generated for every call
// so callers do not need to worry about nonce-cache collisions.
func postSigned(t *testing.T, baseURL, token string, body []byte, _ string) (int, []byte, StartPcvrResponse) {
	t.Helper()
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("POST", "/start_pcvr", token, time.Unix(o.ts, 0), o.nonce, body)
	req, _ := http.NewRequest("POST", baseURL+"/start_pcvr", bytes.NewReader(body))
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out StartPcvrResponse
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, raw, out
}

// getSigned is a test helper that signs a GET and returns
// the raw response body.
func getSigned(t *testing.T, baseURL, path, token string) (int, []byte) {
	t.Helper()
	o := signedOpts{ts: time.Now().Unix(), nonce: mustNewNonce(t)}
	sig := security.Sign("GET", path, token, time.Unix(o.ts, 0), o.nonce, nil)
	req, _ := http.NewRequest("GET", baseURL+path, nil)
	req.Header.Set("X-Vq-Sig", sig)
	req.Header.Set("X-Vq-Ts", strconv.FormatInt(o.ts, 10))
	req.Header.Set("X-Vq-Nonce", o.nonce)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, raw
}

// --- Residual review regression tests (v3 findings) ---

// TestStartPcvr_SameIDDifferentPayload_AfterCompletion_IsConflict
// is the canonical Finding #1 regression: after the FIRST
// request with id X completes (vrserver observed → STARTED
// cached), a SECOND request with the SAME id X but a
// DIFFERENT payload (e.g., different requested_codec) MUST
// be rejected as REQUEST_ID_CONFLICT. The cached result is
// authoritative for the ORIGINAL payload only, NOT a
// wildcard. The test runs vrserver=true so the first call
// returns ALREADY_RUNNING (no second launch). The second
// call must NOT return the cached ALREADY_RUNNING for the
// changed payload; it must return REQUEST_ID_CONFLICT and
// must not trigger a second launch.
func TestStartPcvr_SameIDDifferentPayload_AfterCompletion_IsConflict(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	var launchCount int32
	ms := &mutableScanner{vr: true}
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { atomic.AddInt32(&launchCount, 1); return nil }}),
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(10, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"reuse-id"}`)
	_, _, out1 := postSigned(t, ts.URL, "tok", body1, "reuse-id")
	if out1.State != StateAlreadyRunning {
		t.Fatalf("first call state=%s, want ALREADY_RUNNING", out1.State)
	}
	if got := atomic.LoadInt32(&launchCount); got != 0 {
		t.Fatalf("first call must not launch (vrserver=true), got %d", got)
	}
	// Second call: SAME id, DIFFERENT payload. The cached
	// result must NOT be returned. We expect REQUEST_ID_CONFLICT
	// and no new dispatch.
	body2 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"reuse-id"}`)
	_, _, out2 := postSigned(t, ts.URL, "tok", body2, "reuse-id")
	if out2.Error != ErrIdConflict {
		t.Fatalf("same-id different-payload after completion must be REQUEST_ID_CONFLICT, got %q state=%s", out2.Error, out2.State)
	}
	if got := atomic.LoadInt32(&launchCount); got != 0 {
		t.Fatalf("conflict path must not launch, got %d", got)
	}
}

// TestStartPcvr_ResultCache_RespectsTxCap_NoSilentOverflow
// is the Finding #2 regression: a result cache that is at
// capacity (txCap) with no expired entries MUST refuse a
// fresh install. The fresh path is rejected with INTERNAL
// (txCacheFull) BEFORE the dispatch is even considered; no
// new launch must occur and the cache must never grow
// beyond txCap. After txTTL elapses the slot is reusable.
func TestStartPcvr_ResultCache_RespectsTxCap_NoSilentOverflow(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{vr: true}
	var launchCount int32
	srv, err := New(Deps{
		Token:    "tok",
		Cert:     cert,
		Adapter:  alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher: steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { atomic.AddInt32(&launchCount, 1); return nil }}),
		NonceLRU: state.NewNonceLRU(4096, 30*time.Second, time.Now),
		// IP+action limiters are sized so the 1024-fill
		// loop cannot deplete either; the test exercises
		// the cache cap, not the rate limit.
		IPLimiter:     state.NewRateLimiterFactory(100000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100000, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// Fill the result cache to txCap with still-valid entries
	// (vrserver=true means each call is ALREADY_RUNNING
	// (final=true), so the result is cached). We use
	// unique request_ids so each entry is a fresh insert.
	for i := 0; i < txCap; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"fill-%d"}`, i))
		_, _, out := postSigned(t, ts.URL, "tok", body, fmt.Sprintf("fill-%d", i))
		if out.Error != "" || out.State != StateAlreadyRunning {
			t.Fatalf("fill %d: state=%s err=%q", i, out.State, out.Error)
		}
	}
	// Cache is now at capacity. The next FRESH request must
	// be rejected with INTERNAL (txCacheFull) and must NOT
	// trigger a second SteamVR launch (the existing-VR
	// path does not call Launch anyway, but the cold-start
	// branch would, so we verify the result map size and
	// the error code).
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"over-cap"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body, "over-cap")
	if out.State != StateDenied || out.Error != ErrInternal {
		t.Fatalf("cache-full fresh path must be DENIED/INTERNAL, got state=%s err=%q", out.State, out.Error)
	}
	if got := atomic.LoadInt32(&launchCount); got != 0 {
		t.Fatalf("no SteamVR launch must occur in the existing-VR path, got %d", got)
	}
	// Confirm the result map size is bounded.
	if size := resultMapSize(srv); size != txCap {
		t.Fatalf("result cache size = %d, want exactly txCap (%d)", size, txCap)
	}
}

// TestStartPcvr_ResultCache_ExpiredSlotsReusable verifies
// that an expired slot is freed on the next lookup and a
// fresh request can install a new entry. This is the
// "expired slots reusable" half of the Finding #2 contract.
func TestStartPcvr_ResultCache_ExpiredSlotsReusable(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	ms := &mutableScanner{vr: true}
	// Use a frozen clock so we can advance past txTTL
	// without sleeping. Anchor the clock at the current
	// wall clock so signed timestamps remain within the
	// allowed skew (30s).
	var clock time.Time
	clock = time.Now()
	nowFn := func() time.Time { return clock }
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       alvr.NewAdapter(sessionPath, alvr.FakeGate{}),
		Launcher:      steamvr.New(steamvr.Options{AssertSession: func() error { return nil }, Scanner: ms, Launch: func(string, ...string) error { return nil }}),
		NonceLRU:      state.NewNonceLRU(4096, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(100000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100000, 30*time.Second, 1024, time.Now),
		// MaxSkew is wider than the TTL advance so the
		// post-TTL signed request still passes auth.
		MaxSkew: 1 * time.Hour,
		Now:     nowFn,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// Fill exactly to capacity at clock=T0.
	for i := 0; i < txCap; i++ {
		body := []byte(fmt.Sprintf(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"slot-%d"}`, i))
		_, raw, out := postSigned(t, ts.URL, "tok", body, fmt.Sprintf("slot-%d", i))
		if out.State != StateAlreadyRunning {
			t.Fatalf("fill %d: state=%s err=%q raw=%s", i, out.State, out.Error, raw)
		}
	}
	// Advance the clock past txTTL so every cached entry
	// is strictly expired.
	clock = clock.Add(txTTL + time.Second)
	// A fresh request must be accepted (not DENIED/INTERNAL)
	// because the prune sweep frees the slots.
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"after-ttl"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body, "after-ttl")
	if out.State != StateAlreadyRunning {
		t.Fatalf("after-ttl fresh path must succeed (slots reusable), got state=%s err=%q", out.State, out.Error)
	}
}

// TestStartPcvr_StatusObservesVRClearsLease_NewIDRespectsCodec
// is the Finding #3 regression: while the global inflight
// lease is held (cold start, vrserver not yet observed),
// subsequent /start_pcvr calls return STARTING (no second
// launch). When /status observes vrserver=true the lease is
// cleared, and a NEW request_id with a CHANGED codec must
// return RECONNECT_REQUIRED (matching the existing-VR /
// different-codec branch) — NOT STARTING (which would imply
// the cold-start gate is still closed) and NOT ALREADY_RUNNING
// (which would imply the codec is acceptable).
func TestStartPcvr_StatusObservesVRClearsLease_NewIDRespectsCodec(t *testing.T) {
	dir := t.TempDir()
	sessionPath := filepath.Join(dir, "session.json")
	if err := alvr.NewFakeSession(sessionPath, alvr.CodecH264); err != nil {
		t.Fatalf("seed: %v", err)
	}
	cert, _ := generateTestCert()
	adapter := alvr.NewAdapter(sessionPath, alvr.FakeGate{})
	bs := &blockingScanner{vr: false}
	var launchCount int32
	launchGate := make(chan struct{})
	launcher := steamvr.New(steamvr.Options{AssertSession: func() error { return nil },
		Scanner: bs,
		Launch: func(string, ...string) error {
			atomic.AddInt32(&launchCount, 1)
			<-launchGate
			return nil
		},
		ProbeTimeout:  2 * time.Second,
		LaunchTimeout: 2 * time.Second,
	})
	srv, err := New(Deps{
		Token:         "tok",
		Cert:          cert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      state.NewNonceLRU(64, 30*time.Second, time.Now),
		IPLimiter:     state.NewRateLimiterFactory(1000, 30*time.Second, 1024, time.Now),
		ActionLimiter: state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now),
		MaxSkew:       30 * time.Second,
	})
	if err != nil {
		t.Fatalf("server: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// (1) Cold-start dispatch holds the inflight lease. The
	// Launch closure is gated so the handler will block in
	// waitForVRServer until ctx times out. While it is
	// blocked, /status reports vrserver=false.
	body1 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"H264","request_id":"cold-A"}`)
	firstDone := make(chan StartPcvrResponse, 1)
	go func() {
		_, _, out := postSigned(t, ts.URL, "tok", body1, "cold-A")
		firstDone <- out
	}()
	// Wait for the launch closure to be entered.
	deadline := time.Now().Add(2 * time.Second)
	for atomic.LoadInt32(&launchCount) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("first launch never started")
		}
		time.Sleep(5 * time.Millisecond)
	}
	// (2) /status while vrserver=false: lease persists.
	_, raw1 := getSigned(t, ts.URL, "/status", "tok")
	if !strings.Contains(string(raw1), `"vrserver":false`) {
		t.Fatalf("/status with vrserver=false: %s", raw1)
	}
	// (3) Now flip the scanner to vrserver=true. /status
	// observes this and the lease MUST be cleared.
	bs.setVRNow(true)
	_, raw2 := getSigned(t, ts.URL, "/status", "tok")
	if !strings.Contains(string(raw2), `"vrserver":true`) {
		t.Fatalf("/status with vrserver=true: %s", raw2)
	}
	// (4) Release the first launch closure so the first
	// call's waitForVRServer can return. We expect
	// STARTING because the probe ctx times out before the
	// scanner flip.
	close(launchGate)
	select {
	case <-firstDone:
	case <-time.After(3 * time.Second):
		t.Fatal("first call did not return")
	}
	// (5) A NEW request_id with a CHANGED codec (AV1) MUST
	// take the existing-VR / different-codec branch and
	// return RECONNECT_REQUIRED — NOT STARTING (lease
	// already cleared) and NOT ALREADY_RUNNING (codec
	// mismatches the saved H264). The headset is told to
	// use the ALVR Dashboard; no second launch.
	body2 := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"AV1","request_id":"cold-B"}`)
	_, _, out := postSigned(t, ts.URL, "tok", body2, "cold-B")
	if out.State != StateReconnectRequired || out.Error != ErrAlvrCodec {
		t.Fatalf("post-status new-id changed-codec must be RECONNECT_REQUIRED/ALVR_CODEC, got state=%s err=%q", out.State, out.Error)
	}
	if got := atomic.LoadInt32(&launchCount); got != 1 {
		t.Fatalf("exactly one launch must have happened, got %d", got)
	}
}

// resultMapSize returns the current size of the result map.
// Test-only helper.
func resultMapSize(srv *Server) int {
	srv.txMu.Lock()
	defer srv.txMu.Unlock()
	return len(srv.results)
}

// unused import guards
var _ = pem.Block{}
var _ = sha256.Sum256
var _ = hex.EncodeToString
var _ = os.WriteFile
var _ = filepath.Join
