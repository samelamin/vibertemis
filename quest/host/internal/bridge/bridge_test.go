package bridge

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// testRSAKey is an RSA key the tests use to sign the
// enrollment canonical. The companion cert PEM and the
// companion cert SHA are produced separately.
func testRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return k
}

// testCertPEM returns an RSA X.509 cert PEM signed by the
// supplied key. The cert's Subject matches the test
// fixtures; NotBefore / NotAfter are generous so the cert
// never expires during the test run.
func testCertPEM(t *testing.T, key *rsa.PrivateKey) string {
	t.Helper()
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "vibertemis-test-client"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
}

// testCompanionPEM returns the companion HTTPS cert PEM
// (RSA). Tests use this as the cert the Service holds
// internally.
func testCompanionPEM(t *testing.T) (string, string) {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "vibertemis-test-companion"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &k.PublicKey, k)
	if err != nil {
		t.Fatalf("create companion cert: %v", err)
	}
	pemText := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	pin, err := CompanionCertSHA(pemText)
	if err != nil {
		t.Fatalf("companion sha: %v", err)
	}
	return pemText, pin
}

// randHex returns N random bytes hex-encoded via
// crypto/rand.
func randHex(t *testing.T, n int) string {
	t.Helper()
	var b [64]byte
	if _, err := rand.Read(b[:n]); err != nil {
		t.Fatalf("rand: %v", err)
	}
	return hex.EncodeToString(b[:n])
}

// signEnrollment signs the canonical with the supplied
// key. Returns base64.
func signEnrollment(t *testing.T, key *rsa.PrivateKey, canonical []byte) string {
	t.Helper()
	hashed := sha256.Sum256(canonical)
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hashed[:])
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return base64.StdEncoding.EncodeToString(sig)
}

// fakeAuthorizer is a controllable Authorizer for tests.
// When deny is true, it returns authorized=false with
// err=nil (the contract's explicit-denial path).
type fakeAuthorizer struct {
	deny     atomic.Bool
	calls    atomic.Int32
	delay    time.Duration
	lastUUID string
	lastSHA  string
}

func (f *fakeAuthorizer) Authorize(req AuthorizeRequest) (AuthorizeResponse, error) {
	f.calls.Add(1)
	f.lastUUID = req.ClientUUID
	f.lastSHA = req.ClientCertSHA
	if f.delay > 0 {
		time.Sleep(f.delay)
	}
	if f.deny.Load() {
		return AuthorizeResponse{Authorized: false}, nil
	}
	return AuthorizeResponse{Authorized: true}, nil
}

// inMemoryPersist is a test Persist implementation.
// blocksBeforeSave is an optional sleep so we can
// observe mid-persist state if needed.
type inMemoryPersist struct {
	mu               sync.Mutex
	snapshot         []PersistedDevice
	failNext         atomic.Bool
	failErr          error
	calls            atomic.Int32
	blocksBeforeSave time.Duration
}

func (p *inMemoryPersist) SaveDevices(records []PersistedDevice) error {
	p.calls.Add(1)
	if p.blocksBeforeSave > 0 {
		time.Sleep(p.blocksBeforeSave)
	}
	if p.failNext.Load() {
		return p.failErr
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	// Deep copy so callers can't mutate our copy.
	out := make([]PersistedDevice, len(records))
	copy(out, records)
	p.snapshot = out
	return nil
}

func (p *inMemoryPersist) Snapshot() []PersistedDevice {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]PersistedDevice, len(p.snapshot))
	copy(out, p.snapshot)
	return out
}

// issueGrant is a tiny test helper that runs the inbound
// issue_grant handler end-to-end. It returns the grant
// string.
func issueGrant(t *testing.T, svc *Service, clientUUID, clientPEM, hostSHA, compSHA, nonce string) string {
	t.Helper()
	payload, _ := json.Marshal(IssueGrantRequest{
		ClientUUID:    clientUUID,
		ClientCertPEM: clientPEM,
		ClientCertSHA: FingerprintFromPEMOrFatal(t, clientPEM),
		HostCertSHA:   hostSHA,
		ClientNonce:   nonce,
	})
	resp, err := svc.HandleInbound(&Request{
		V: SchemaVersion, ID: "test-1", Op: "issue_grant", Payload: payload,
	})
	if err != nil {
		t.Fatalf("issue_grant: %v", err)
	}
	var ig IssueGrantResponse
	if err := json.Unmarshal(resp.Payload, &ig); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if ig.CompanionCertSHA != compSHA {
		t.Fatalf("companion cert sha mismatch: got %q want %q", ig.CompanionCertSHA, compSHA)
	}
	return ig.Grant
}

// FingerprintFromPEMOrFatal is a fatal-error wrapper for
// FingerprintFromPEM.
func FingerprintFromPEMOrFatal(t *testing.T, pemText string) string {
	t.Helper()
	f, err := FingerprintFromPEM(pemText)
	if err != nil {
		t.Fatalf("fingerprint: %v", err)
	}
	return f
}

// buildRedeemBody signs the canonical for the supplied
// grant + nonce using the supplied client key.
func buildRedeemBody(t *testing.T, clientKey *rsa.PrivateKey, grant, nonce, hostSHA, compSHA, clientUUID string) []byte {
	t.Helper()
	canonical := EnrollmentCanonical(nonce, grant, hostSHA, compSHA, clientUUID)
	sig := signEnrollment(t, clientKey, canonical)
	body, err := json.Marshal(RedeemRequest{
		Schema:      SchemaVersion,
		Grant:       grant,
		ClientNonce: nonce,
		Signature:   sig,
	})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return body
}

// ----- Tests -----

func TestIssueGrantIdempotentAllBindingsMatch(t *testing.T) {
	compPEM, _ := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "aca1edcf-2edb-50b6-9b8c-f9de11894893"

	grant1, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("first issue: %v", err)
	}
	if svc.Pending() != 1 {
		t.Fatalf("pending = %d, want 1", svc.Pending())
	}

	// Same key + nonce: idempotent, same grant, ORIGINAL expiry.
	grant2, exp2, err := issueGrantAndExtractFull(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("second issue: %v", err)
	}
	if grant2 != grant1 {
		t.Fatalf("idempotent reissue returned different grant: %q vs %q", grant1, grant2)
	}
	// Pending should still be 1.
	if svc.Pending() != 1 {
		t.Fatalf("pending after reissue = %d, want 1", svc.Pending())
	}
	_ = exp2
}

func TestIssueGrantIdempotentBindingDriftRejected(t *testing.T) {
	compPEM, _ := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "27dde0ed-e860-5d8e-8dea-7f5472131177"

	_, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("first issue: %v", err)
	}
	// Same UUID + nonce but DIFFERENT hostSHA — must
	// mint a fresh grant (no cross-host collision).
	grant2, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, randHex(t, 32), nonce)
	if err != nil {
		t.Fatalf("second issue: %v", err)
	}
	// Two distinct grants now exist; pending cap test.
	if svc.Pending() != 2 {
		t.Fatalf("pending = %d, want 2", svc.Pending())
	}
	_ = grant2
}

func TestIssueGrantTTLNotExtendedOnIdempotentReissue(t *testing.T) {
	compPEM, _ := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	// Force the clock so the expiry is deterministic.
	frozen := time.Unix(1_700_000_000, 0)
	svc.SetClock(func() time.Time { return frozen })

	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "5af06812-82c8-57e3-bb6b-9dee1854a1ff"

	_, exp1, err := issueGrantAndExtractFull(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("first issue: %v", err)
	}
	// Advance the clock by 30 seconds (half of TTL).
	svc.SetClock(func() time.Time { return frozen.Add(30 * time.Second) })
	_, exp2, err := issueGrantAndExtractFull(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("second issue: %v", err)
	}
	if exp1 != exp2 {
		t.Fatalf("TTL was extended: exp1=%d exp2=%d", exp1, exp2)
	}
}

func TestRedeemWrongNonceRejected(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	wrongNonce := randHex(t, 32)
	clientUUID := "e6c186f9-dfb8-5c8e-a579-744d4322931b"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	auth := &fakeAuthorizer{}
	body := buildRedeemBody(t, clientKey, grant, wrongNonce, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: auth,
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrBadNonce) {
		t.Fatalf("expected ErrBadNonce, got %v", err)
	}
}

func TestRedeemBadSignatureRejected(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	otherKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "d44a3d5d-53c6-555c-966e-62b241d998e1"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	// Sign with the WRONG key.
	body := buildRedeemBody(t, otherKey, grant, nonce, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrBadSignature) {
		t.Fatalf("expected ErrBadSignature, got %v", err)
	}
}

func TestRedeemHostDenyFailsClosed(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "93b68ee8-f16a-511c-8062-b524ca779538"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	auth := &fakeAuthorizer{}
	auth.deny.Store(true)
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: auth,
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrHostDenied) {
		t.Fatalf("expected ErrHostDenied, got %v", err)
	}
	// The grant MUST NOT be consumed.
	if svc.Pending() != 1 {
		t.Fatalf("pending = %d after host deny, want 1 (grant must survive)", svc.Pending())
	}
}

func TestRedeemHostUnreachableFailsClosed(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "fa5a0932-ecfd-56a7-a6ac-6b1dc653515e"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	// nil Authorizer: fail closed.
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: nil,
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrHostUnreachable) {
		t.Fatalf("expected ErrHostUnreachable, got %v", err)
	}
	if svc.Pending() != 1 {
		t.Fatalf("pending = %d after host unreachable, want 1", svc.Pending())
	}
}

func TestRedeemConcurrentSameGrantSecondRejected(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "8d00bbe6-81dd-5386-b34c-b1fa7dc5cb64"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	auth := &fakeAuthorizer{}
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	persist := &inMemoryPersist{blocksBeforeSave: 100 * time.Millisecond}

	type result struct {
		resp *RedeemResponse
		err  error
	}
	results := make(chan result, 2)
	start := make(chan struct{})
	for i := 0; i < 2; i++ {
		go func() {
			<-start
			r, e := svc.Redeem(RedeemInput{
				Body:       body,
				Authorizer: auth,
				Persist:    persist,
			})
			results <- result{r, e}
		}()
	}
	close(start)
	var winner, loser result
	for i := 0; i < 2; i++ {
		r := <-results
		if r.err == nil {
			winner = r
		} else {
			loser = r
		}
	}
	if winner.resp == nil {
		t.Fatalf("expected one winner, both errors: %v / %v", winner.err, loser.err)
	}
	if !errors.Is(loser.err, ErrConcurrentRedemption) {
		t.Fatalf("expected loser ErrConcurrentRedemption, got %v", loser.err)
	}
}

func TestRedeemPersistFailureLeavesMemoryUntouched(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "c71e1ba6-f8c6-5fbe-8df7-fef644a60491"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	persist := &inMemoryPersist{}
	persist.failNext.Store(true)
	persist.failErr = errors.New("disk full")

	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: &fakeAuthorizer{},
		Persist:    persist,
	})
	if !errors.Is(err, ErrPersistFailed) {
		t.Fatalf("expected ErrPersistFailed, got %v", err)
	}

	// Critical: prior in-memory state is UNTOUCHED.
	if svc.Devices() != 0 {
		t.Fatalf("Devices() = %d after persist failure, want 0 (no in-memory exposure)", svc.Devices())
	}
	// Grant is NOT consumed.
	if svc.Pending() != 1 {
		t.Fatalf("Pending() = %d after persist failure, want 1 (grant must survive)", svc.Pending())
	}
}

// TestRedeemBlockedPersistNeverExposesReplacement: a
// persist call that BLOCKS (longer than the redeem
// timeout we use) must NOT expose the replacement
// credential in memory while blocked. The transaction
// holds s.mu during SaveDevices; callers therefore see
// no replacement until the save returns success.
func TestRedeemBlockedPersistNeverExposesReplacement(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "c5ed6d72-e0b6-597b-9469-8eb3f8c04739"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)

	persist := &inMemoryPersist{blocksBeforeSave: 250 * time.Millisecond}

	// Run redeem in a goroutine; while it is blocked in
	// SaveDevices we probe the in-memory state.
	type result struct {
		resp *RedeemResponse
		err  error
	}
	done := make(chan result, 1)
	go func() {
		r, e := svc.Redeem(RedeemInput{
			Body:       body,
			Authorizer: &fakeAuthorizer{},
			Persist:    persist,
		})
		done <- result{r, e}
	}()

	// While persistence holds the transaction lock, lookup must not expose
	// the new device. A blocked lookup completes only after the save commits.
	deadline := time.Now().Add(time.Second)
	for persist.calls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if persist.calls.Load() == 0 {
		t.Fatal("persist was never reached")
	}
	observed := make(chan int, 1)
	go func() { observed <- svc.Devices() }()
	select {
	case <-observed:
		t.Fatal("uncommitted state exposed")
	case <-time.After(30 * time.Millisecond):
	}

	r := <-done
	if r.err != nil {
		t.Fatalf("redeem: %v", r.err)
	}
	if svc.Devices() != 1 {
		t.Fatalf("Devices() = %d after success, want 1", svc.Devices())
	}
}

// TestRedeemFailurePreservesOriginalToken: if a device
// already has a credential AND a fresh redemption fails,
// the original token must remain in service (not be
// overwritten by the failed candidate).
func TestRedeemFailurePreservesOriginalToken(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	clientUUID := "25aa6d12-c3a3-5b2d-ba2c-6a7aebb46b90"

	// First redemption: succeeds, device record installed.
	nonce1 := randHex(t, 32)
	grant1, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce1)
	if err != nil {
		t.Fatalf("issue1: %v", err)
	}
	body1 := buildRedeemBody(t, clientKey, grant1, nonce1, hostSHA, compSHA, clientUUID)
	resp1, err := svc.Redeem(RedeemInput{
		Body:       body1,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if err != nil {
		t.Fatalf("redeem1: %v", err)
	}
	originalToken := resp1.Token
	originalDeviceID := resp1.DeviceID

	// Second redemption with the same client: fails at
	// persist. The original credential must survive.
	nonce2 := randHex(t, 32)
	grant2, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce2)
	if err != nil {
		t.Fatalf("issue2: %v", err)
	}
	persist2 := &inMemoryPersist{}
	persist2.failNext.Store(true)
	persist2.failErr = errors.New("write conflict")
	body2 := buildRedeemBody(t, clientKey, grant2, nonce2, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body2,
		Authorizer: &fakeAuthorizer{},
		Persist:    persist2,
	})
	if !errors.Is(err, ErrPersistFailed) {
		t.Fatalf("expected ErrPersistFailed, got %v", err)
	}
	// Original credential still active.
	dev, ok := svc.LookupDeviceIdentity(originalDeviceID)
	if !ok {
		t.Fatalf("original device missing after failed replacement")
	}
	if dev.Token != originalToken {
		t.Fatalf("original token overwritten by failed redemption")
	}
	if svc.Devices() != 1 {
		t.Fatalf("Devices() = %d, want 1", svc.Devices())
	}
}

// TestRedeemFullCapReplacement: at deviceLimit, a fresh
// redemption for an ALREADY-paired client must succeed
// (replacement). A redemption for a NEW client at cap
// must fail.
func TestRedeemFullCapReplacement(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)

	// Pre-populate s.devices to the cap with a mix of
	// other clients + the target.
	otherRecords := make([]PersistedDevice, 0, deviceLimit)
	for i := 0; i < deviceLimit-1; i++ {
		// Use deviceLimit-1 distinct clients; one slot
		// is for the target client whose record we
		// already have.
		cUUID := fmt.Sprintf("00000000-0000-0000-0000-%012x", i)
		otherRecords = append(otherRecords, PersistedDevice{
			DeviceID:         randHex(t, 16),
			Token:            randHex(t, 32),
			ClientCertPEM:    "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----",
			ClientCertSHA:    randHex(t, 32),
			HostCertSHA:      randHex(t, 32),
			CompanionCertSHA: randHex(t, 32),
			ClientUUID:       cUUID,
		})
	}
	// Add target client record too, so we're at the cap.
	targetClient := "cd742324-89c3-5aea-a5b5-8ad7056a0a12"
	otherRecords = append(otherRecords, PersistedDevice{
		DeviceID:         randHex(t, 16),
		Token:            randHex(t, 32),
		ClientCertPEM:    "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----",
		ClientCertSHA:    randHex(t, 32),
		HostCertSHA:      randHex(t, 32),
		CompanionCertSHA: randHex(t, 32),
		ClientUUID:       targetClient,
	})
	svc.LoadDevices(otherRecords)
	if svc.Devices() != deviceLimit {
		t.Fatalf("Devices() = %d after seed, want %d", svc.Devices(), deviceLimit)
	}

	// (1) Replacement of targetClient at cap: must succeed.
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	grant, err := issueGrantAndExtract(t, svc, targetClient, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, targetClient)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if err != nil {
		t.Fatalf("replacement at cap failed: %v", err)
	}
	// Still at cap (replacement, not insert).
	if svc.Devices() != deviceLimit {
		t.Fatalf("Devices() = %d after replacement, want %d", svc.Devices(), deviceLimit)
	}

	// (2) Insertion of a NEW client at cap: must fail.
	nonce2 := randHex(t, 32)
	grant2, err := issueGrantAndExtract(t, svc, "5dc6a36f-0567-5eb3-b61d-40911ebd68fe", clientPEM, hostSHA, nonce2)
	if err != nil {
		t.Fatalf("issue new: %v", err)
	}
	body2 := buildRedeemBody(t, clientKey, grant2, nonce2, hostSHA, compSHA, "5dc6a36f-0567-5eb3-b61d-40911ebd68fe")
	_, err = svc.Redeem(RedeemInput{
		Body:       body2,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrLimitsExceeded) {
		t.Fatalf("expected ErrLimitsExceeded, got %v", err)
	}
}

// TestRedeemConcurrentRevokeCannotResurrect: a revoke
// that lands while a redeem is in flight must NOT
// resurrect the credential the redeem intended to
// install. The transaction holds persistMu + s.mu
// throughout; revoke waits for persistMu.
func TestRedeemConcurrentRevokeCannotResurrect(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "c73965a1-75d8-5408-abc3-72e6f6dc6cef"

	// Seed a prior credential so the target record
	// already exists.
	seed := PersistedDevice{
		DeviceID:         randHex(t, 16),
		Token:            randHex(t, 32),
		ClientCertPEM:    clientPEM,
		ClientCertSHA:    FingerprintFromPEMOrFatal(t, clientPEM),
		HostCertSHA:      hostSHA,
		CompanionCertSHA: compSHA,
		ClientUUID:       clientUUID,
	}
	svc.LoadDevices([]PersistedDevice{seed})
	if svc.Devices() != 1 {
		t.Fatalf("seed: Devices() = %d", svc.Devices())
	}
	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatal(err)
	}
	persist := &inMemoryPersist{blocksBeforeSave: 100 * time.Millisecond}
	svc.SetInboundPersist(persist)
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	done := make(chan error, 1)
	go func() {
		_, e := svc.Redeem(RedeemInput{Body: body, Authorizer: &fakeAuthorizer{}, Persist: persist})
		done <- e
	}()
	deadline := time.Now().Add(time.Second)
	for persist.calls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if persist.calls.Load() == 0 {
		t.Fatal("persist not reached")
	}
	payload, _ := json.Marshal(RevokeRequest{ClientUUID: clientUUID})
	if _, e := svc.HandleInbound(&Request{V: 1, ID: "revoke", Op: "revoke", Payload: payload}); e != nil {
		t.Fatal(e)
	}
	if e := <-done; e != nil {
		t.Fatal(e)
	}
	if svc.Devices() != 0 || len(persist.Snapshot()) != 0 {
		t.Fatal("revoked credential resurrected")
	}

}

// TestRevokePersistFailureRetainsMemoryRevocation: a
// revoke that fails to persist must keep the in-memory
// revocation so the device is unreachable via HMAC +
// fresh authority.
func TestRevokePersistFailureRetainsMemoryRevocation(t *testing.T) {
	compPEM, _ := testCompanionPEM(t)
	svc := New(compPEM, 28540)

	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	clientUUID := "6c850129-c409-52e2-b7fe-c5c709044c37"

	// Seed a credential.
	devID := randHex(t, 16)
	svc.LoadDevices([]PersistedDevice{{
		DeviceID:         devID,
		Token:            randHex(t, 32),
		ClientCertPEM:    clientPEM,
		ClientCertSHA:    FingerprintFromPEMOrFatal(t, clientPEM),
		HostCertSHA:      hostSHA,
		CompanionCertSHA: randHex(t, 32),
		ClientUUID:       clientUUID,
	}})
	if svc.Devices() != 1 {
		t.Fatalf("seed")
	}

	// Wire a persist that fails.
	persist := &inMemoryPersist{}
	persist.failNext.Store(true)
	persist.failErr = errors.New("disk full")
	svc.SetInboundPersist(persist)

	revokePayload, _ := json.Marshal(RevokeRequest{ClientUUID: clientUUID})
	_, err := svc.HandleInbound(&Request{
		V: SchemaVersion, ID: "test-revoke", Op: "revoke", Payload: revokePayload,
	})
	if !errors.Is(err, ErrPersistFailed) {
		t.Fatalf("expected ErrPersistFailed, got %v", err)
	}
	// Memory revocation retained: device is gone.
	if svc.Devices() != 0 {
		t.Fatalf("Devices() = %d after revoke (memory should be revoked even though persist failed)", svc.Devices())
	}
}

// TestLookupDeviceReturnsCompanionCertPinNotClientCert:
// the contract is explicit: HMAC token lookup returns the
// companionCertSHA (the companion HTTPS cert pin), NOT
// the client cert fingerprint. The client cert is the
// binding identifier only.
func TestLookupDeviceReturnsCompanionCertPinNotClientCert(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	clientSHA := FingerprintFromPEMOrFatal(t, clientPEM)
	hostSHA := randHex(t, 32)
	devID := randHex(t, 16)
	token := randHex(t, 32)
	clientUUID := "1c4a3907-9b6f-5d79-aede-9cae92608209"

	svc.LoadDevices([]PersistedDevice{{
		DeviceID:         devID,
		Token:            token,
		ClientCertPEM:    clientPEM,
		ClientCertSHA:    clientSHA,
		HostCertSHA:      hostSHA,
		CompanionCertSHA: compSHA,
		ClientUUID:       clientUUID,
	}})

	gotToken, gotCompSHA, ok := svc.LookupDevice(devID)
	if !ok {
		t.Fatalf("LookupDevice failed")
	}
	if gotToken != token {
		t.Fatalf("token mismatch")
	}
	if gotCompSHA != compSHA {
		t.Fatalf("HMAC pin = %q, want companionCertSHA %q (NOT clientCertSHA %q)", gotCompSHA, compSHA, clientSHA)
	}
	if gotCompSHA == clientSHA {
		t.Fatalf("HMAC pin equals clientCertSHA; the contract is that the companion cert pins the HMAC channel")
	}
}

// TestAuthorizeAndSelectHMACDeniesOnAuthorizedFalse: the
// contract says Authorized=false with err=nil is an
// explicit deny and fails closed.
func TestAuthorizeAndSelectHMACDeniesOnAuthorizedFalse(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	clientSHA := FingerprintFromPEMOrFatal(t, clientPEM)
	hostSHA := randHex(t, 32)
	devID := randHex(t, 16)
	clientUUID := "38f24fab-511e-59d7-9b1e-cdb2f8b7c234"

	svc.LoadDevices([]PersistedDevice{{
		DeviceID:         devID,
		Token:            randHex(t, 32),
		ClientCertPEM:    clientPEM,
		ClientCertSHA:    clientSHA,
		HostCertSHA:      hostSHA,
		CompanionCertSHA: compSHA,
		ClientUUID:       clientUUID,
	}})

	auth := &fakeAuthorizer{}
	auth.deny.Store(true)
	svc.SetAuthorizer(auth)

	_, _, _, err := svc.AuthorizeAndSelectHMAC(clientUUID, clientSHA)
	if !errors.Is(err, ErrHostDenied) {
		t.Fatalf("expected ErrHostDenied on authorized=false, got %v", err)
	}
}

// TestAuthorizeAndSelectHMACNoAuthorizerFailsClosed: when
// no pipe is active (Authorizer is nil), the call fails
// closed.
func TestAuthorizeAndSelectHMACNoAuthorizerFailsClosed(t *testing.T) {
	compPEM, _ := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	_, _, _, err := svc.AuthorizeAndSelectHMAC("u", randHex(t, 32))
	if !errors.Is(err, ErrHostUnreachable) {
		t.Fatalf("expected ErrHostUnreachable, got %v", err)
	}
}

// TestRedeemReplayRejected: a second redemption attempt
// for the same grant (after success) is rejected as
// replay.
func TestRedeemReplayRejected(t *testing.T) {
	compPEM, compSHA := testCompanionPEM(t)
	svc := New(compPEM, 28540)
	clientKey := testRSAKey(t)
	clientPEM := testCertPEM(t, clientKey)
	hostSHA := randHex(t, 32)
	nonce := randHex(t, 32)
	clientUUID := "d2a8ab03-4ac8-58a2-af44-c69965962600"

	grant, err := issueGrantAndExtract(t, svc, clientUUID, clientPEM, hostSHA, nonce)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	body := buildRedeemBody(t, clientKey, grant, nonce, hostSHA, compSHA, clientUUID)
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if err != nil {
		t.Fatalf("first redeem: %v", err)
	}
	// Second attempt with the same body: replay.
	_, err = svc.Redeem(RedeemInput{
		Body:       body,
		Authorizer: &fakeAuthorizer{},
		Persist:    &inMemoryPersist{},
	})
	if !errors.Is(err, ErrGrantReplay) {
		t.Fatalf("expected ErrGrantReplay, got %v", err)
	}
}

// issueGrantAndExtract is a tiny helper that runs
// issue_grant and returns the grant string.
func issueGrantAndExtract(t *testing.T, svc *Service, clientUUID, clientPEM, hostSHA, nonce string) (string, error) {
	t.Helper()
	payload, _ := json.Marshal(IssueGrantRequest{
		ClientUUID:    clientUUID,
		ClientCertPEM: clientPEM,
		ClientCertSHA: FingerprintFromPEMOrFatal(t, clientPEM),
		HostCertSHA:   hostSHA,
		ClientNonce:   nonce,
	})
	resp, err := svc.HandleInbound(&Request{
		V: SchemaVersion, ID: "test", Op: "issue_grant", Payload: payload,
	})
	if err != nil {
		return "", err
	}
	var ig IssueGrantResponse
	if err := json.Unmarshal(resp.Payload, &ig); err != nil {
		return "", err
	}
	return ig.Grant, nil
}

// issueGrantAndExtractFull also returns the expires_unix
// for TTL tests.
func issueGrantAndExtractFull(t *testing.T, svc *Service, clientUUID, clientPEM, hostSHA, nonce string) (string, int64, error) {
	t.Helper()
	payload, _ := json.Marshal(IssueGrantRequest{
		ClientUUID:    clientUUID,
		ClientCertPEM: clientPEM,
		ClientCertSHA: FingerprintFromPEMOrFatal(t, clientPEM),
		HostCertSHA:   hostSHA,
		ClientNonce:   nonce,
	})
	resp, err := svc.HandleInbound(&Request{
		V: SchemaVersion, ID: "test", Op: "issue_grant", Payload: payload,
	})
	if err != nil {
		return "", 0, err
	}
	var ig IssueGrantResponse
	if err := json.Unmarshal(resp.Payload, &ig); err != nil {
		return "", 0, err
	}
	return ig.Grant, ig.ExpiresUnix, nil
}
