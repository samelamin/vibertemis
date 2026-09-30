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
