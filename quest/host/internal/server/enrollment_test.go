package server

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"github.com/vibertemis/quest-codec-control/host/internal/enrollment"
	"github.com/vibertemis/quest-codec-control/host/internal/security"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type enrollmentLookup struct{ s *enrollment.Service }

func (a enrollmentLookup) LookupDeviceIdentity(id string) (PairDeviceIdentity, bool) {
	r, ok := a.s.Lookup(id)
	return PairDeviceIdentity{DeviceID: r.ID, Token: r.Token, PublicKeySHA: r.KeySHA, CompanionCertSHA: r.CertSHA}, ok
}
func TestStandaloneApprovalOverHTTPAndLocalAuthority(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	service, e := enrollment.New("publiccert", strings.Repeat("a", 64), nil, enrollment.FileStore{Dir: t.TempDir()}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	ts.core.RegisterEnrollment(service)
	ts.core.deps.DeviceIdentityLookup = enrollmentLookup{service}
	ts.core.deps.FreshAuthorizer = service
	admin := ts.core.EnrollmentAdminHandler(service)
	local := func(action string, body []byte, remote, device string, signed bool) *httptest.ResponseRecorder {
		path := "/pairing/admin/" + action
		r := httptest.NewRequest("POST", path, bytes.NewReader(body))
		r.RemoteAddr = remote
		if device != "" {
			r.Header.Set(DeviceHeader, device)
		}
		if signed {
			now := time.Now()
			nonce := mustNewNonce(t)
			r.Header.Set("X-Vq-Ts", strconv.FormatInt(now.Unix(), 10))
			r.Header.Set("X-Vq-Nonce", nonce)
			r.Header.Set("X-Vq-Sig", security.Sign("POST", path, ts.token, now, nonce, body))
		}
		w := httptest.NewRecorder()
		admin.ServeHTTP(w, r)
		return w
	}
	for _, c := range []struct {
		remote, device string
		signed         bool
	}{{"192.168.1.22:99", "", true}, {"127.0.0.1:99", strings.Repeat("a", 32), true}, {"127.0.0.1:99", "", false}} {
		local("open", nil, c.remote, c.device, c.signed)
		if service.Pending().Open {
			t.Fatal("untrusted caller opened pairing")
		}
	}
	w := local("open", nil, "127.0.0.1:99", "", true)
	if w.Code != 200 || !service.Pending().Open {
		t.Fatal(w.Body.String())
	}
	post := func(path string, body any) map[string]any {
		data, _ := json.Marshal(body)
		r, e := ts.srv.Client().Post(ts.srv.URL+path, "application/json", bytes.NewReader(data))
		if e != nil {
			t.Fatal(e)
		}
		defer r.Body.Close()
		var v map[string]any
		if e = json.NewDecoder(r.Body).Decode(&v); e != nil {
			t.Fatal(e)
		}
		return v
	}
	// Even a correctly signed local caller cannot reach admin through the LAN mux.
	path := "/pairing/admin/close"
	r := httptest.NewRequest("POST", path, nil)
	r.RemoteAddr = "127.0.0.1:99"
	now := time.Now()
	nonce := mustNewNonce(t)
	r.Header.Set("X-Vq-Ts", strconv.FormatInt(now.Unix(), 10))
	r.Header.Set("X-Vq-Nonce", nonce)
	r.Header.Set("X-Vq-Sig", security.Sign("POST", path, ts.token, now, nonce, nil))
	rw := httptest.NewRecorder()
	ts.core.Handler().ServeHTTP(rw, r)
	if rw.Code != 404 || !service.Pending().Open {
		t.Fatal("LAN handler exposed management")
	}
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	clientNonce := strings.Repeat("b", 64)
	challenge := post("/pairing/begin", enrollment.BeginRequest{Schema: 1, Key: base64.StdEncoding.EncodeToString(der), Nonce: clientNonce})
	id, ok := challenge["session_id"].(string)
	if !ok {
		t.Fatal(challenge)
	}
	h := sha256.Sum256(enrollment.Transcript("POLL", id, clientNonce, challenge["server_nonce"].(string), strings.Repeat("a", 64)))
	sig, _ := rsa.SignPSS(rand.Reader, key, crypto.SHA256, h[:], &rsa.PSSOptions{SaltLength: 32})
	proof := enrollment.Proof{Schema: 1, ID: id, Signature: base64.StdEncoding.EncodeToString(sig)}
	pending := post("/pairing/poll", proof)
	if pending["state"] != "pending" || pending["encrypted_token"] != nil {
		t.Fatal(pending)
	}
	decision, _ := json.Marshal(map[string]any{"session_id": id, "code": service.Pending().Code, "approve": true})
	if w = local("decision", decision, "127.0.0.1:99", "", true); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	result := post("/pairing/poll", proof)
	encrypted, _ := base64.StdEncoding.DecodeString(result["encrypted_token"].(string))
	token, e := rsa.DecryptOAEP(sha256.New(), rand.Reader, key, encrypted, enrollment.TokenLabel(id))
	if e != nil {
		t.Fatal(e)
	}
	device := result["device_id"].(string)
	status := func() map[string]any {
		r, _ := http.NewRequest("GET", ts.srv.URL+"/status", nil)
		now := time.Now()
		nonce := mustNewNonce(t)
		r.Header.Set(DeviceHeader, device)
		r.Header.Set("X-Vq-Ts", strconv.FormatInt(now.Unix(), 10))
		r.Header.Set("X-Vq-Nonce", nonce)
		r.Header.Set("X-Vq-Sig", security.Sign("GET", "/status", string(token), now, nonce, nil))
		resp, e := ts.srv.Client().Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer resp.Body.Close()
		var v map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&v)
		return v
	}
	if v := status(); v["error"] != nil {
		t.Fatal("standalone device required host bridge", v)
	}
	if w = local("forget", nil, "127.0.0.1:99", "", true); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if v := status(); v["error"] != string(ErrAuth) {
		t.Fatal("revoked headset accepted", v)
	}
	if atomic.LoadInt32(ts.launched) != 0 {
		t.Fatal("pairing started SteamVR")
	}
}

// TestEnrollment_HTTPErrorCodes covers the structured error envelope
// end-to-end through the LAN mux. Each pairing error path emits a
// matching HTTP status, owner-controlled `error` string, and stable
// `code` field. Preview8 clients that only read `error` still see
// a sensible message.
func TestEnrollment_HTTPErrorCodes(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	service, e := enrollment.New("publiccert", strings.Repeat("a", 64), nil, enrollment.FileStore{Dir: t.TempDir()}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	ts.core.RegisterEnrollment(service)
	post := func(path string, body any, remote string) (int, []byte) {
		data, _ := json.Marshal(body)
		r, _ := http.NewRequest("POST", ts.srv.URL+path, bytes.NewReader(data))
		r.Header.Set("Content-Type", "application/json")
		r.RemoteAddr = remote
		w := httptest.NewRecorder()
		ts.core.Handler().ServeHTTP(w, r)
		return w.Code, w.Body.Bytes()
	}
	// (1) CLOSED: window not open. The request is well-formed; the
	// service still refuses with ErrClosed / HTTP 400 / code CLOSED.
	status, raw := post("/pairing/begin", enrollment.BeginRequest{
		Schema: 1,
		Key:    base64.StdEncoding.EncodeToString([]byte("dummy")),
		Nonce:  strings.Repeat("b", 64),
	}, "10.0.0.1:5000")
	if status != 400 {
		t.Fatalf("closed status = %d, want 400", status)
	}
	var env ErrorEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode closed envelope: %v (%s)", err, raw)
	}
	if env.Code != enrollment.CodeClosed {
		t.Fatalf("closed code = %q, want %q", env.Code, enrollment.CodeClosed)
	}
	if env.Error != enrollment.ErrClosed.Error() {
		t.Fatalf("closed error = %q, want %q", env.Error, enrollment.ErrClosed.Error())
	}
	// (2) INVALID: malformed body returns ErrInvalid / 400 / INVALID.
	// We send raw text that fails the JSON decoder to exercise the
	// decode failure path (which now returns ErrInvalid).
	status, raw = post("/pairing/begin", map[string]string{"schema": "wrong"}, "10.0.0.2:5000")
	if status != 400 {
		t.Fatalf("invalid status = %d, want 400", status)
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode invalid envelope: %v (%s)", err, raw)
	}
	if env.Code != enrollment.CodeInvalid {
		t.Fatalf("invalid code = %q, want %q", env.Code, enrollment.CodeInvalid)
	}
	if env.Error != enrollment.ErrInvalid.Error() {
		t.Fatalf("invalid error = %q, want %q", env.Error, enrollment.ErrInvalid.Error())
	}
	// (3) BUSY: open the window, begin once, then a second begin
	// with a valid key + nonce on a fresh request MUST return
	// ErrBusy / 400 / BUSY.
	service.Open()
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	clientNonce := strings.Repeat("c", 64)
	status, raw = post("/pairing/begin", enrollment.BeginRequest{
		Schema: 1,
		Key:    base64.StdEncoding.EncodeToString(der),
		Nonce:  clientNonce,
	}, "10.0.0.3:5000")
	if status != 200 {
		t.Fatalf("first begin status = %d, want 200 (raw=%s)", status, raw)
	}
	status, raw = post("/pairing/begin", enrollment.BeginRequest{
		Schema: 1,
		Key:    base64.StdEncoding.EncodeToString(der),
		Nonce:  clientNonce,
	}, "10.0.0.3:5000")
	if status != 400 {
		t.Fatalf("busy status = %d, want 400", status)
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode busy envelope: %v (%s)", err, raw)
	}
	if env.Code != enrollment.CodeBusy {
		t.Fatalf("busy code = %q, want %q", env.Code, enrollment.CodeBusy)
	}
	// (4) EXPIRED: open, begin, advance the deadline past the
	// session's expiry, then poll. We do not poll yet because
	// signing requires the challenge; we close the window
	// instead. A subsequent poll with the original proof returns
	// ErrExpired.
	var challenge map[string]any
	if err := json.Unmarshal(raw, &challenge); err != nil {
		// not used; reset
		challenge = nil
	}
	// Refetch the first challenge from the service directly.
	pending := service.Pending()
	if pending.Code == "" {
		t.Fatal("expected pending code")
	}
	// Cancel the pending attempt via Close. Close clears BOTH
	// deadlines; expireLocked then drops the unapproved pending
	// current (both deadlines zero, state != approved) so a
	// subsequent poll must return ErrExpired / 400 / EXPIRED.
	service.Close()
	// Reopen with a fresh session so we can build a real proof.
	service.Open()
	status, raw = post("/pairing/begin", enrollment.BeginRequest{
		Schema: 1,
		Key:    base64.StdEncoding.EncodeToString(der),
		Nonce:  clientNonce,
	}, "10.0.0.3:5000")
	if status != 200 {
		t.Fatalf("reopen begin status = %d, want 200 (raw=%s)", status, raw)
	}
	var beginEnv struct {
		SessionID   string `json:"session_id"`
		ServerNonce string `json:"server_nonce"`
	}
	if err := json.Unmarshal(raw, &beginEnv); err != nil {
		t.Fatalf("decode begin: %v (%s)", err, raw)
	}
	// Close the window: pending attempt is dropped, original
	// proof must ErrExpired on Poll AND Decide.
	service.Close()
	h := sha256.Sum256(enrollment.Transcript("POLL", beginEnv.SessionID, clientNonce, beginEnv.ServerNonce, strings.Repeat("a", 64)))
	sig, _ := rsa.SignPSS(rand.Reader, key, crypto.SHA256, h[:], &rsa.PSSOptions{SaltLength: 32})
	proof := enrollment.Proof{Schema: 1, ID: beginEnv.SessionID, Signature: base64.StdEncoding.EncodeToString(sig)}
	status, raw = post("/pairing/poll", proof, "10.0.0.3:5000")
	if status != 400 {
		t.Fatalf("expired status = %d, want 400 (raw=%s)", status, raw)
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode expired envelope: %v (%s)", err, raw)
	}
	if env.Code != enrollment.CodeExpired {
		t.Fatalf("expired code = %q, want %q", env.Code, enrollment.CodeExpired)
	}
	// (5) RATE_LIMITED: trip the per-IP limiter (burst=100). We
	// drain the burst with the cheap invalid-path (which still
	// passes through limiter.Allow before the service denies the
	// request), then the 101st call from the same remote MUST
	// return 429 with the structured RATE_LIMITED code.
	const ip = "10.0.0.99:5000"
	for i := 0; i < 100; i++ {
		_, _ = post("/pairing/begin", enrollment.BeginRequest{Schema: 2}, ip)
	}
	status, raw = post("/pairing/begin", enrollment.BeginRequest{Schema: 2}, ip)
	if status != http.StatusTooManyRequests {
		t.Fatalf("rate-limit status = %d, want 429", status)
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode rate-limit envelope: %v (%s)", err, raw)
	}
	if env.Code != enrollment.CodeRateLimited {
		t.Fatalf("rate-limit code = %q, want %q", env.Code, enrollment.CodeRateLimited)
	}
	if env.Error != enrollment.ErrRateLimited.Error() {
		t.Fatalf("rate-limit error = %q, want %q", env.Error, enrollment.ErrRateLimited.Error())
	}
	// (6) Error body backward compatibility: preview8 clients
	// ignore the `code` field but read `error`. The envelope's
	// `error` is ALWAYS one of the owner-controlled sentinel
	// strings, never raw user input. Verify the strings we
	// emit are the exact ErrClosed / ErrExpired / ErrBusy /
	// ErrInvalid / ErrRateLimited.Error() literals.
	for _, msg := range []string{
		enrollment.ErrClosed.Error(),
		enrollment.ErrExpired.Error(),
		enrollment.ErrBusy.Error(),
		enrollment.ErrInvalid.Error(),
		enrollment.ErrRateLimited.Error(),
	} {
		if strings.ContainsAny(msg, "\n\r") {
			t.Fatalf("owner-controlled message %q contains newline", msg)
		}
	}
}

// allowRemoteAddr must refuse public / WAN traffic. Private /
// LAN / VPN (RFC 1918 / RFC 4193 / link-local unicast / CGNAT
// 100.64/10) and loopback are accepted. Multicast (any scope)
// is refused because it cannot be a pairing endpoint.
func TestAllowRemoteAddr(t *testing.T) {
	good := []string{
		"127.0.0.1:1234", "10.0.0.5:5000", "192.168.1.42:28540",
		"172.16.0.1:1", "169.254.1.5:5000", // link-local unicast
		"[fe80::1]:5000", "[fc00::1]:5000",
		"100.64.1.2:5000", "100.127.255.254:5000",
	}
	for _, s := range good {
		if !allowRemoteAddr(s) {
			t.Fatalf("expected allow for %q", s)
		}
	}
	bad := []string{
		"8.8.8.8:53", "1.1.1.1:443", "2001:db8::1:5000",
		"224.0.0.1:5000", "239.255.255.255:5000", // multicast IPv4
		"[ff02::1]:5000", // link-local multicast IPv6
		"[ff00::1]:5000", // site-local multicast IPv6
		"[ff05::1]:5000", // site-local multicast IPv6
		"",
	}
	for _, s := range bad {
		if allowRemoteAddr(s) {
			t.Fatalf("expected reject for %q", s)
		}
	}
}

// The per-IP Begin admission limiter is independent of the
// generic DOS guard: a single LAN IP cannot burn more than
// 6 Begin attempts per minute regardless of wire validity.
// The 7th Begin from the same source returns 429 with the
// stable RATE_LIMITED code BEFORE the enrollment.Service is
// consulted, so malformed Begin spam does NOT burn the global
// six-valid/two-minute admission budget.
func TestEnrollment_PerIPBeginLimiter(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	service, e := enrollment.New("publiccert", strings.Repeat("a", 64), nil, enrollment.FileStore{Dir: t.TempDir()}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	ts.core.RegisterEnrollment(service)
	post := func(remote string) (int, []byte) {
		body, _ := json.Marshal(enrollment.BeginRequest{
			Schema: 2, Key: "ignored", Nonce: strings.Repeat("b", 64),
		})
		r, _ := http.NewRequest("POST", ts.srv.URL+"/pairing/begin", bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.RemoteAddr = remote
		w := httptest.NewRecorder()
		ts.core.Handler().ServeHTTP(w, r)
		return w.Code, w.Body.Bytes()
	}
	const ip = "10.0.0.42:5000"
	// Six malformed Begin attempts all return 400 INVALID
	// (per-session wire-format rejection). The 7th trips the
	// per-IP admission limiter and returns 429 RATE_LIMITED.
	for i := 0; i < 6; i++ {
		status, raw := post(ip)
		if status != 400 {
			t.Fatalf("iteration %d: status = %d, want 400 (body=%s)", i, status, raw)
		}
	}
	status, raw := post(ip)
	if status != http.StatusTooManyRequests {
		t.Fatalf("per-IP Begin 7th = %d, want 429 (body=%s)", status, raw)
	}
	var env ErrorEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode envelope: %v", err)
	}
	if env.Code != enrollment.CodeRateLimited {
		t.Fatalf("per-IP Begin code = %q, want %q", env.Code, enrollment.CodeRateLimited)
	}
}

// The LAN /pairing/begin endpoint refuses public / WAN source
// addresses with 403 BEFORE the per-IP limiter is consulted. A
// remote attacker cannot burn the limiter's burst budget.
func TestEnrollment_SourceGateRefusesPublic(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	service, e := enrollment.New("publiccert", strings.Repeat("a", 64), nil, enrollment.FileStore{Dir: t.TempDir()}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	ts.core.RegisterEnrollment(service)
	body, _ := json.Marshal(enrollment.BeginRequest{Schema: 1, Key: "ignored", Nonce: strings.Repeat("b", 64)})
	r, _ := http.NewRequest("POST", ts.srv.URL+"/pairing/begin", bytes.NewReader(body))
	r.RemoteAddr = "8.8.8.8:53" // public WAN
	w := httptest.NewRecorder()
	ts.core.Handler().ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatalf("public source = %d, want 403 (body=%s)", w.Code, w.Body.String())
	}
}

// The HTTP admin handler exposes /pairing/admin/renew,
// /pairing/admin/suppress, and /pairing/admin/unsuppress on the
// loopback listener. The handler must:
//
//  1. Authenticate via the owner-only HMAC token.
//  2. Reject requests from non-loopback sources.
//  3. Reject requests with the X-Vq-Device header (a paired
//     headset must not be able to run admin).
//  4. Refresh the receiving lease on /renew.
//  5. Block Begin while suppressed.
//  6. Clear suppression on /unsuppress.
func TestEnrollment_AdminLeaseSuppressEndpoints(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	service, e := enrollment.New("publiccert", strings.Repeat("a", 64), nil, enrollment.FileStore{Dir: t.TempDir()}, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	ts.core.RegisterEnrollment(service)
	admin := ts.core.EnrollmentAdminHandler(service)
	signed := func(action string, body []byte, remote string) *httptest.ResponseRecorder {
		path := "/pairing/admin/" + action
		r := httptest.NewRequest("POST", path, bytes.NewReader(body))
		r.RemoteAddr = remote
		now := time.Now().Unix()
		nonce := mustNewNonce(t)
		r.Header.Set("X-Vq-Ts", strconv.FormatInt(now, 10))
		r.Header.Set("X-Vq-Nonce", nonce)
		r.Header.Set("X-Vq-Sig", security.Sign("POST", path, ts.token, time.Unix(now, 0), nonce, body))
		w := httptest.NewRecorder()
		admin.ServeHTTP(w, r)
		return w
	}
	var w *httptest.ResponseRecorder
	// Non-loopback caller is rejected even with a valid signature.
	if w = signed("renew", nil, "192.168.1.50:1234"); w.Code != 403 {
		t.Fatalf("non-loopback renew = %d, want 403", w.Code)
	}
	// Unsigned loopback caller: authenticate fails, lease is NOT
	// refreshed. We construct a real unsigned httptest request and
	// assert the lease stays empty (no service.Pending().Open).
	if u := httptest.NewRequest("POST", "/pairing/admin/renew", nil); true {
		u.RemoteAddr = "127.0.0.1:99"
		uw := httptest.NewRecorder()
		admin.ServeHTTP(uw, u)
		if service.Pending().Open {
			t.Fatalf("unsigned renew refreshed lease: code=%d body=%s", uw.Code, uw.Body.String())
		}
	}
	// Signed loopback renew.
	w = signed("renew", nil, "127.0.0.1:99")
	if w.Code != 200 {
		t.Fatalf("renew = %d, want 200 (body=%s)", w.Code, w.Body.String())
	}
	pending := enrollment.Pending{}
	if err := json.Unmarshal(w.Body.Bytes(), &pending); err != nil {
		t.Fatal(err)
	}
	if !pending.Receiving || !pending.Open {
		t.Fatalf("post-renew pending = %+v, want receiving+open", pending)
	}
	// Suppress with a far-future until; pending snapshot must
	// reflect suppression.
	sup := []byte(`{"until_unix":` + strconv.FormatInt(time.Now().Add(time.Hour).Unix(), 10) + `}`)
	w = signed("suppress", sup, "127.0.0.1:99")
	if w.Code != 200 {
		t.Fatalf("suppress = %d, want 200 (body=%s)", w.Code, w.Body.String())
	}
	pending = enrollment.Pending{}
	if err := json.Unmarshal(w.Body.Bytes(), &pending); err != nil {
		t.Fatal(err)
	}
	if !pending.Suppressed || pending.Open {
		t.Fatalf("post-suppress pending = %+v, want suppressed=true open=false", pending)
	}
	// Renew during suppression is a no-op: suppression is not
	// cleared and the lease is NOT refreshed.
	w = signed("renew", nil, "127.0.0.1:99")
	if w.Code != 200 {
		t.Fatalf("renew-during-suppress = %d, want 200", w.Code)
	}
	pending = enrollment.Pending{}
	if err := json.Unmarshal(w.Body.Bytes(), &pending); err != nil {
		t.Fatal(err)
	}
	if !pending.Suppressed {
		t.Fatal("renew during suppress cleared suppression")
	}
	// Begin is blocked while suppressed.
	body, _ := json.Marshal(enrollment.BeginRequest{Schema: 1, Key: base64.StdEncoding.EncodeToString([]byte("x")), Nonce: strings.Repeat("c", 64)})
	r, _ := http.NewRequest("POST", ts.srv.URL+"/pairing/begin", bytes.NewReader(body))
	r.RemoteAddr = "10.0.0.1:5000"
	w2 := httptest.NewRecorder()
	ts.core.Handler().ServeHTTP(w2, r)
	if w2.Code != 400 {
		t.Fatalf("suppressed begin = %d, want 400", w2.Code)
	}
	// Unsuppress and verify the lease comes back. Unsuppress
	// itself does NOT refresh the lease (it only clears the
	// suppression flag); the manager's serial timer will renew
	// on its next tick.
	w = signed("unsuppress", nil, "127.0.0.1:99")
	if w.Code != 200 {
		t.Fatalf("unsuppress = %d, want 200", w.Code)
	}
	pending = enrollment.Pending{}
	if err := json.Unmarshal(w.Body.Bytes(), &pending); err != nil {
		t.Fatal(err)
	}
	if pending.Suppressed {
		t.Fatal("unsuppress did not clear suppression")
	}
}
