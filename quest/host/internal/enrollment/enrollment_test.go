package enrollment

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

type memoryStore struct {
	records []Record
	fail    bool
}

func (m *memoryStore) Save(r []Record) error {
	if m.fail {
		return errors.New("disk unavailable")
	}
	m.records = append([]Record{}, r...)
	return nil
}

type fixture struct {
	s     *Service
	store *memoryStore
	key   *rsa.PrivateKey
	now   *time.Time
	req   BeginRequest
}

func setup(t *testing.T) fixture {
	t.Helper()
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	now := time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	store := &memoryStore{}
	s, e := New("public cert", strings.Repeat("a", 64), nil, store, func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	return fixture{s, store, key, &now, BeginRequest{1, base64.StdEncoding.EncodeToString(der), strings.Repeat("b", 64)}}
}
func (f fixture) proof(t *testing.T, c Challenge, action string) Proof {
	t.Helper()
	h := sha256.Sum256(Transcript(action, c.ID, f.req.Nonce, c.ServerNonce, f.s.certSHA))
	sig, e := rsa.SignPSS(rand.Reader, f.key, crypto.SHA256, h[:], &rsa.PSSOptions{SaltLength: 32})
	if e != nil {
		t.Fatal(e)
	}
	return Proof{1, c.ID, base64.StdEncoding.EncodeToString(sig)}
}
func TestApprovalPersistenceDeliveryAndRevocation(t *testing.T) {
	f := setup(t)
	if _, e := f.s.Begin(f.req); !errors.Is(e, ErrClosed) {
		t.Fatal(e)
	}
	f.s.Open()
	c, e := f.s.Begin(f.req)
	if e != nil {
		t.Fatal(e)
	}
	proof := f.proof(t, c, "POLL")
	pending, e := f.s.Poll(proof)
	if e != nil || pending.State != "pending" || pending.EncryptedToken != "" || len(f.store.records) != 0 {
		t.Fatal(pending, e)
	}
	if e = f.s.Decide(c.ID, "wrong", true); e == nil {
		t.Fatal("approved mismatched code")
	}
	f.store.fail = true
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); e == nil {
		t.Fatal("approved failed persistence")
	}
	if !errors.Is(e, ErrStorageFailed) {
		t.Fatalf("storage wrap: got %v, want ErrStorageFailed", e)
	}
	if len(f.s.records) != 0 {
		t.Fatal("published before persistence")
	}
	// Storage failure clears the unapproved attempt so a fresh
	// Begin is required to retry. Decide on the dropped attempt
	// returns ErrExpired.
	if err := f.s.Decide(c.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("decide after storage failure = %v, want ErrExpired", err)
	}
	f.store.fail = false
	c, e = f.s.Begin(f.req)
	if e != nil {
		t.Fatal(e)
	}
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); e != nil {
		t.Fatal(e)
	}
	// Refresh the proof for the new attempt; the OLD proof was
	// signed for the OLD session_id which has been cleared.
	proof = f.proof(t, c, "POLL")
	approved, e := f.s.Poll(proof)
	if e != nil || approved.State != "approved" {
		t.Fatal(approved, e)
	}
	f.s.Close()
	if _, e := f.s.Begin(f.req); !errors.Is(e, ErrClosed) {
		t.Fatal("closed approval window accepts new enrollment")
	}
	again, _ := f.s.Poll(proof)
	if again != approved {
		t.Fatal("lost response cannot be retried")
	}
	encrypted, _ := base64.StdEncoding.DecodeString(approved.EncryptedToken)
	token, e := rsa.DecryptOAEP(sha256.New(), rand.Reader, f.key, encrypted, TokenLabel(c.ID))
	if e != nil {
		t.Fatal(e)
	}
	record, ok := f.s.Lookup(approved.DeviceID)
	if !ok || record.Token != string(token) {
		t.Fatal("credential mismatch")
	}
	if _, e = rsa.DecryptOAEP(sha256.New(), rand.Reader, f.key, encrypted, TokenLabel("different")); e == nil {
		t.Fatal("wrong label decrypted")
	}
	restored, e := New(f.s.certPEM, f.s.certSHA, f.store.records, f.store, time.Now)
	if e != nil {
		t.Fatal(e)
	}
	if _, _, _, e = restored.AuthorizeAndSelectHMAC(record.ID, record.KeySHA); e != nil {
		t.Fatal(e)
	}
	if _, e = restored.Begin(f.req); !errors.Is(e, ErrClosed) {
		t.Fatal("restart left enrollment open")
	}
	if e = restored.ForgetAll(); e != nil {
		t.Fatal(e)
	}
	if _, _, _, e = restored.AuthorizeAndSelectHMAC(record.ID, record.KeySHA); e == nil {
		t.Fatal("revoked device authorized")
	}
}
func TestRejectExpireCancelAndProofIsolation(t *testing.T) {
	f := setup(t)
	f.s.Open()
	c, _ := f.s.Begin(f.req)
	p := f.proof(t, c, "POLL")
	wrong := p
	wrong.Signature = strings.Repeat("A", 344)
	if _, e := f.s.Poll(wrong); e == nil {
		t.Fatal("wrong signature accepted")
	}
	if e := f.s.Cancel(p); e == nil {
		t.Fatal("poll proof accepted as cancellation")
	}
	p.Schema = 2
	if _, e := f.s.Poll(p); e == nil {
		t.Fatal("unknown schema accepted")
	}
	if e := f.s.Decide(c.ID, f.s.Pending().Code, false); e != nil {
		t.Fatal(e)
	}
	denied, e := f.s.Poll(f.proof(t, c, "POLL"))
	if e != nil || denied.State != "denied" || denied.EncryptedToken != "" {
		t.Fatal(denied, e)
	}
	// Legacy Open does NOT clear the active pending attempt
	// (same-slot policy); explicitly Close before re-opening so
	// a fresh Begin succeeds.
	f.s.Close()
	f.s.Open()
	c, _ = f.s.Begin(f.req)
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); e != nil {
		t.Fatal(e)
	}
	if e = f.s.Cancel(f.proof(t, c, "CANCEL")); e != nil {
		t.Fatal(e)
	}
	if len(f.s.records) != 0 {
		t.Fatal("cancellation racing approval did not revoke")
	}
	f.s.Close()
	f.s.Open()
	c, _ = f.s.Begin(f.req)
	// Advance past the 180 s ChallengeTTL (independent of the
	// 2-minute legacy window). After expiry the legacy window
	// is also closed; the next Poll must surface ErrExpired
	// AND Pending.Open must be false.
	*f.now = f.now.Add(ChallengeTTL + time.Second)
	if _, e = f.s.Poll(f.proof(t, c, "POLL")); !errors.Is(e, ErrExpired) {
		t.Fatal(e)
	}
	if f.s.Pending().Open {
		t.Fatal("window did not expire")
	}
}
func TestStoreAndTranscriptBoundaries(t *testing.T) {
	f := setup(t)
	f.s.Open()
	c, _ := f.s.Begin(f.req)
	code := f.s.Pending().Code
	for i := 0; i < 5; i++ {
		fields := []string{f.s.certSHA, digest(mustKey(t, f.key)), f.req.Nonce, c.ServerNonce, c.ID}
		fields[i] = strings.Repeat("f", 64)
		if Code(fields[0], fields[1], fields[2], fields[3], fields[4]) == code {
			t.Fatal("identity field not bound")
		}
	}
	if _, e := f.s.Begin(f.req); e == nil {
		t.Fatal("concurrent request displaced approval")
	}
	_ = f.s.Decide(c.ID, code, true)
	store := FileStore{Dir: t.TempDir()}
	if e := store.Save(f.store.records); e != nil {
		t.Fatal(e)
	}
	records, e := store.Load()
	if e != nil || len(records) != 1 {
		t.Fatal(e)
	}
	if e = store.Save([]Record{}); e != nil {
		t.Fatal("atomic replacement failed", e)
	}
	records, e = store.Load()
	if e != nil || len(records) != 0 {
		t.Fatal(e)
	}
	_, e = New("cert", strings.Repeat("c", 64), f.store.records, store, time.Now)
	if e == nil {
		t.Fatal("changed server cert restored devices")
	}
	duplicate := append(f.store.records, f.store.records...)
	if _, e = New("cert", f.s.certSHA, duplicate, store, time.Now); e == nil {
		t.Fatal("duplicate device restored")
	}
}
func mustKey(t *testing.T, key *rsa.PrivateKey) []byte {
	t.Helper()
	der, e := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	return der
}

// A committed test-only fixture lets Android verify Go PSS and decrypt Go OAEP.
func TestWriteInteropFixture(t *testing.T) {
	path := os.Getenv("VIBERTEMIS_TEST_VECTOR_OUTPUT")
	if path == "" {
		return
	}
	f := setup(t)
	f.s.Open()
	c, _ := f.s.Begin(f.req)
	p := f.proof(t, c, "POLL")
	code := f.s.Pending().Code
	if e := f.s.Decide(c.ID, code, true); e != nil {
		t.Fatal(e)
	}
	r, _ := f.s.Poll(p)
	private, e := x509.MarshalPKCS8PrivateKey(f.key)
	if e != nil {
		t.Fatal(e)
	}
	data, e := json.MarshalIndent(map[string]any{"test_only": true, "private_key": base64.StdEncoding.EncodeToString(private), "client_key": f.req.Key, "client_nonce": f.req.Nonce, "key_sha256": digest(mustKey(t, f.key)), "pin": f.s.certSHA, "challenge": c, "proof": p, "result": r, "token": f.store.records[0].Token, "code": code}, "", "  ")
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(path, data, 0600); e != nil {
		t.Fatal(e)
	}
}

// CodeOf maps each sentinel to its wire code, and any other error
// (including the wrapped storage error) to UNKNOWN. The mapping
// feeds both the LAN error envelope and the headset's bounded UI
// message whitelist, so a regression on any branch surfaces here.
func TestCodeOfSentinelMapping(t *testing.T) {
	cases := []struct {
		err  error
		want ErrorCode
	}{
		{ErrClosed, CodeClosed},
		{ErrExpired, CodeExpired},
		{ErrBusy, CodeBusy},
		{ErrRateLimited, CodeRateLimited},
		{ErrInvalid, CodeInvalid},
		{ErrCapacity, CodeCapacity},
		{ErrStorageFailed, CodeStorage},
		{errors.New("disk unavailable"), CodeUnknown},
		{fmt.Errorf("%w: %v", ErrStorageFailed, errors.New("disk unavailable")), CodeStorage},
		{nil, ""},
	}
	for _, c := range cases {
		if got := CodeOf(c.err); got != c.want {
			t.Fatalf("CodeOf(%v) = %q, want %q", c.err, got, c.want)
		}
	}
}

// Decide surfaces ErrCapacity and ErrStorageFailed (wrapped) with
// their matching codes so the headset UI shows actionable reasons
// instead of "Pairing request rejected".
func TestDecideCapacityAndStorageCodes(t *testing.T) {
	f := setup(t)
	// Pre-fill records up to the 32-entry cap.
	for i := 0; i < 32; i++ {
		f.s.records[fmt.Sprintf("%032x", i)] = Record{
			ID:      fmt.Sprintf("%032x", i),
			Token:   strings.Repeat("a", 64),
			KeySHA:  strings.Repeat("b", 64),
			CertSHA: f.s.certSHA,
			Created: f.now.Unix(),
		}
	}
	f.s.Open()
	// Begin must refuse BEFORE bothering the owner. The current
	// attempt is never allocated; the owner-side dialog does not
	// even get a code to display.
	c, e := f.s.Begin(f.req)
	if !errors.Is(e, ErrCapacity) {
		t.Fatalf("begin-at-capacity: got %v, want ErrCapacity", e)
	}
	if got := CodeOf(e); got != CodeCapacity {
		t.Fatalf("begin-at-capacity code: got %q, want %q", got, CodeCapacity)
	}
	// Records are unchanged after the rejected Begin.
	if len(f.s.records) != 32 {
		t.Fatalf("records mutated by capacity-rejected Begin: %d", len(f.s.records))
	}
	// Drain to 31 records to exercise the Decide recheck path.
	delete(f.s.records, fmt.Sprintf("%032x", 0))
	f.s.Open()
	c, e = f.s.Begin(f.req)
	if e != nil {
		t.Fatal(e)
	}
	// Re-add the dropped record so Decide sees 32 and re-checks.
	f.s.records[fmt.Sprintf("%032x", 0)] = Record{
		ID:      fmt.Sprintf("%032x", 0),
		Token:   strings.Repeat("a", 64),
		KeySHA:  strings.Repeat("b", 64),
		CertSHA: f.s.certSHA,
		Created: f.now.Unix(),
	}
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); !errors.Is(e, ErrCapacity) {
		t.Fatalf("decide-at-capacity recheck: got %v, want ErrCapacity", e)
	}
	if got := CodeOf(e); got != CodeCapacity {
		t.Fatalf("decide-at-capacity code: got %q, want %q", got, CodeCapacity)
	}

	// Drain the records and verify the storage wrap. ForgetAll
	// drops current AND records so we can re-issue a fresh Begin
	// for the storage branch.
	f.s.records = map[string]Record{}
	if err := f.s.ForgetAll(); err != nil {
		t.Fatal(err)
	}
	f.s.Open()
	c, e = f.s.Begin(f.req)
	if e != nil {
		t.Fatal(e)
	}
	f.store.fail = true
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); e == nil {
		t.Fatal("expected storage error")
	}
	if !errors.Is(e, ErrStorageFailed) {
		t.Fatalf("storage: got %v, want ErrStorageFailed", e)
	}
	if got := CodeOf(e); got != CodeStorage {
		t.Fatalf("storage code: got %q, want %q", got, CodeStorage)
	}
	// The error text must not echo arbitrary remote text. The
	// sentinel prefix is owner-controlled.
	if !strings.Contains(e.Error(), ErrStorageFailed.Error()) {
		t.Fatalf("storage error leaked non-owner text: %v", e)
	}
	// Storage failure must clear the failed unapproved attempt
	// so the manager can retry without Close+Open. Decide on the
	// dropped attempt returns ErrExpired; a fresh Begin succeeds.
	if err := f.s.Decide(c.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("decide after storage failure = %v, want ErrExpired", err)
	}
	f.store.fail = false
	if _, err := f.s.Begin(f.req); err != nil {
		t.Fatalf("retry Begin after storage failure = %v, want nil", err)
	}
}

// Begin produces ErrBusy / ErrRateLimited when the corresponding
// preconditions hold, so the headset UI can show actionable
// reasons instead of "Pairing request rejected".
func TestBeginBusyAndRateLimitedCodes(t *testing.T) {
	f := setup(t)
	if _, e := f.s.Begin(f.req); !errors.Is(e, ErrClosed) {
		t.Fatalf("closed window: got %v, want ErrClosed", e)
	}

	// Busy: a second concurrent Begin while the first is pending.
	f.s.Open()
	if _, e := f.s.Begin(f.req); e != nil {
		t.Fatal(e)
	}
	if _, e := f.s.Begin(f.req); !errors.Is(e, ErrBusy) {
		t.Fatalf("busy: got %v, want ErrBusy", e)
	}
	if got := CodeOf(ErrBusy); got != CodeBusy {
		t.Fatalf("busy code: got %q, want %q", got, CodeBusy)
	}

	// Malformed requests must NOT consume the global rolling
	// begins budget. Seven invalid-schema begins in a single open
	// window return ErrInvalid every time (no seventh ErrRateLimited
	// trip) because the schema check runs BEFORE the budget is
	// charged. The per-IP Begin limiter on the HTTP layer is the
	// malformed-traffic DoS guard; this service-side check is
	// only the wire-format gate.
	f2 := setup(t)
	f2.s.Open()
	invalid := BeginRequest{Schema: 2, Key: f2.req.Key, Nonce: f2.req.Nonce}
	for i := 0; i < 7; i++ {
		_, last := f2.s.Begin(invalid)
		if !errors.Is(last, ErrInvalid) {
			t.Fatalf("iteration %d: got %v, want ErrInvalid", i, last)
		}
	}
	// The rolling counter MUST remain empty because none of the
	// malformed attempts reached the budget-charge line.
	if len(f2.s.beginsRolling) != 0 {
		t.Fatalf("malformed attempts burned the global budget: %d entries",
			len(f2.s.beginsRolling))
	}
	// After the seven invalid attempts, a VALID Begin still
	// succeeds because the budget is intact.
	c0, err := f2.s.Begin(f2.req)
	if err != nil {
		t.Fatalf("valid Begin after malformed spam = %v, want nil", err)
	}
	if len(f2.s.beginsRolling) != 1 {
		t.Fatalf("rolling begins after one valid Begin = %d, want 1",
			len(f2.s.beginsRolling))
	}
	// Same for invalid DER, wrong key size, wrong exponent,
	// oversized key string. None of them touch the rolling
	// counter beyond the single VALID begin above.
	if _, err := f2.s.Begin(BeginRequest{Schema: 1, Key: base64.StdEncoding.EncodeToString([]byte("not der")), Nonce: f2.req.Nonce}); !errors.Is(err, ErrBusy) {
		// ErrBusy because the prior VALID attempt is still in
		// flight; that is the expected outcome and proves the
		// budget-charge line did NOT run for the malformed body.
		t.Fatalf("bad DER (with in-flight attempt): got %v, want ErrBusy", err)
	}
	if len(f2.s.beginsRolling) != 1 {
		t.Fatalf("bad DER burned budget: %d entries", len(f2.s.beginsRolling))
	}
	// Cancel the in-flight attempt so the next Begin can actually
	// reach the validation guard (and not ErrBusy first).
	if err := f2.s.Cancel(f2.proof(t, c0, "CANCEL")); err != nil {
		t.Fatalf("cancel in-flight: %v", err)
	}
	// Build a 1025-byte key string to exercise the bounded key
	// check; the rolling counter MUST NOT advance.
	pre := len(f2.s.beginsRolling)
	if _, err := f2.s.Begin(BeginRequest{Schema: 1, Key: strings.Repeat("A", 1025), Nonce: f2.req.Nonce}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("oversized key: got %v, want ErrInvalid", err)
	}
	if len(f2.s.beginsRolling) != pre {
		t.Fatalf("oversized key burned budget: %d entries (was %d)",
			len(f2.s.beginsRolling), pre)
	}
}

// OpenWithLease arms a 10-second lease that is renewable by the
// Windows manager's serial timer. A single lease that is never
// refreshed must expire and cancel any pending attempt; the
// approved credential stays valid until ForgetAll().
//
// The challenge issued under the lease has its OWN 180-second
// deadline, independent of the lease. The lease is the manager's
// authorization window; the challenge is the headset's logical
// session. PENDING attempts fall as soon as the lease expires
// (the manager no longer authorises a session to be in flight).
func TestOpenWithLeaseExpiresAndCancelsPending(t *testing.T) {
	f := setup(t)
	p := f.s.OpenWithLease()
	if !p.Open {
		t.Fatal("expected window open")
	}
	if !p.Receiving {
		t.Fatal("expected receiving mode set")
	}
	// Lease is 10 s, NOT the legacy 2-minute one-shot window.
	wantLease := f.now.Add(ReceiveLease).Unix()
	if p.LeaseExpiresUnix != wantLease {
		t.Fatalf("lease expiry = %d, want %d", p.LeaseExpiresUnix, wantLease)
	}
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	// Challenge deadline is now+180s, independent of the lease.
	wantChallenge := f.now.Add(ChallengeTTL).Unix()
	if c.Expires != wantChallenge {
		t.Fatalf("challenge expires = %d, want %d (independent of lease)", c.Expires, wantChallenge)
	}
	if c.TTLSeconds != int64(ChallengeTTL/time.Second) {
		t.Fatalf("challenge TTLSeconds = %d, want %d", c.TTLSeconds, int64(ChallengeTTL/time.Second))
	}
	// Advance past the lease deadline; the pending attempt is
	// cancelled (both deadlines are zero, state != approved) and
	// a fresh Begin is allowed (ErrClosed because the lease is
	// gone, NOT ErrBusy).
	*f.now = f.now.Add(ReceiveLease + time.Second)
	if _, err := f.s.Begin(f.req); !errors.Is(err, ErrClosed) {
		t.Fatalf("post-lease Begin = %v, want ErrClosed", err)
	}
	// Original proof now POLLs as ErrExpired: the pending attempt
	// was dropped by expireLocked when the lease elapsed; the
	// challenge.Expires has not passed yet but BOTH deadlines are
	// zero so the unapproved current falls.
	if _, err := f.s.Poll(f.proof(t, c, "POLL")); !errors.Is(err, ErrExpired) {
		t.Fatalf("post-lease poll = %v, want ErrExpired", err)
	}
	// Decide on the dropped attempt is also ErrExpired.
	if err := f.s.Decide(c.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("post-lease Decide = %v, want ErrExpired", err)
	}
	// New lease, new begin, real approval, record persists.
	f.s.OpenWithLease()
	c2, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.s.Decide(c2.ID, f.s.Pending().Code, true); err != nil {
		t.Fatal(err)
	}
	if _, ok := f.s.Lookup(f.store.records[0].ID); !ok {
		t.Fatal("approved credential missing after lease cycle")
	}
}

// A lease refresh preserves the in-flight pending attempt so the
// headset does not have to re-Begin just because the manager
// renewed the lease on its serial timer. The headset's session_id
// stays the same. The rolling begin counter is NOT reset by a
// lease renewal — that is the residual DoS surface even on the
// LAN. Lease renewal is independent of the rolling budget.
func TestLeaseRefreshPreservesPending(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	if len(f.s.beginsRolling) != 1 {
		t.Fatalf("rolling begins after first Begin = %d, want 1", len(f.s.beginsRolling))
	}
	// Refresh the lease; the in-flight attempt keeps its id, the
	// rolling begin counter is NOT reset (rolling-window semantic).
	f.s.OpenWithLease()
	if f.s.current == nil || f.s.current.challenge.ID != c.ID {
		t.Fatalf("pending attempt replaced by lease refresh (current=%v)", f.s.current)
	}
	if len(f.s.beginsRolling) != 1 {
		t.Fatalf("rolling begins after refresh = %d, want 1 (rolling window preserves entries)", len(f.s.beginsRolling))
	}
	// The original proof still POLLs as pending (logical session
	// survives the lease refresh).
	res, err := f.s.Poll(f.proof(t, c, "POLL"))
	if err != nil || res.State != "pending" {
		t.Fatalf("post-refresh poll = (%v, %v), want (pending, nil)", res, err)
	}
}

// A second Begin with the same key + nonce must NOT displace the
// pending attempt; the original session keeps the slot. The retry
// gets ErrBusy which maps to the BUSY code.
func TestIdenticalRetryDoesNotReplacePending(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	// Second Begin with the EXACT same key + nonce.
	_, err = f.s.Begin(f.req)
	if !errors.Is(err, ErrBusy) {
		t.Fatalf("identical retry = %v, want ErrBusy", err)
	}
	if got := CodeOf(err); got != CodeBusy {
		t.Fatalf("BUSY retry code = %q, want %q", got, CodeBusy)
	}
	// Original attempt is intact.
	if f.s.current == nil || f.s.current.challenge.ID != c.ID {
		t.Fatalf("pending replaced by identical retry")
	}
}

// A "Suppress1h" must pause NEW pairing requests, cancel any
// UNAPPROVED pending attempt, and KEEP already-paired credentials.
// An APPROVED pending attempt is preserved through its own
// challenge expiry so the headset can still poll the approved
// result while suppressed. Paired records are NEVER touched by
// suppression; only ForgetAll() revokes them.
func TestSuppress1hPausesAndPreservesCredentials(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	// Approve the pending attempt to seed an authorised credential.
	if err := f.s.Decide(c.ID, f.s.Pending().Code, true); err != nil {
		t.Fatal(err)
	}
	savedID := f.store.records[0].ID
	if _, ok := f.s.Lookup(savedID); !ok {
		t.Fatal("seed credential missing")
	}
	// Suppress. The approved current is preserved (its challenge
	// TTL is 180 s, well into the future), but new Begin requests
	// are blocked.
	until := f.now.Add(1 * time.Hour)
	f.s.SetSuppressed(until)
	if !f.s.IsSuppressed() {
		t.Fatal("IsSuppressed = false after SetSuppressed")
	}
	if got := f.s.SuppressedUntil(); !got.Equal(until) {
		t.Fatalf("SuppressedUntil = %v, want %v", got, until)
	}
	// Pending snapshot reflects suppression.
	ps := f.s.Pending()
	if !ps.Suppressed || ps.Open {
		t.Fatalf("pending = %+v, want suppressed=true, open=false", ps)
	}
	// Begin on a closed window: ErrClosed (NOT ErrBusy).
	if _, err := f.s.Begin(f.req); !errors.Is(err, ErrClosed) {
		t.Fatalf("suppressed Begin = %v, want ErrClosed", err)
	}
	// Approved current SURVIVES suppression; the headset can still
	// poll the approved result via its original session_id.
	if f.s.current == nil {
		t.Fatal("approved current dropped by suppression")
	}
	res, err := f.s.Poll(f.proof(t, c, "POLL"))
	if err != nil || res.State != "approved" {
		t.Fatalf("suppressed approved poll = (%v, %v), want (approved, nil)", res, err)
	}
	// Approved credential is STILL authorised.
	if _, ok := f.s.Lookup(savedID); !ok {
		t.Fatal("approved credential revoked by suppression")
	}
	if _, _, _, err := f.s.AuthorizeAndSelectHMAC(savedID, f.store.records[0].KeySHA); err != nil {
		t.Fatalf("authorize suppressed credential = %v", err)
	}
	// RenewReceiveLease during suppression is a no-op: a manager
	// that mistakenly keeps ticking its serial timer cannot clear
	// the suppression by renewing the lease.
	ps = f.s.RenewReceiveLease()
	if !ps.Suppressed {
		t.Fatal("renew cleared suppression")
	}
	if _, err := f.s.Begin(f.req); !errors.Is(err, ErrClosed) {
		t.Fatalf("post-renew suppressed Begin = %v, want ErrClosed", err)
	}
	// Clear suppression; new Begin is allowed again. The approved
	// current is still in flight (it survives suppression until
	// its own challenge TTL), so we advance the clock past the
	// TTL to clear it before the fresh begin.
	f.s.SetSuppressed(time.Time{})
	if f.s.IsSuppressed() {
		t.Fatal("IsSuppressed = true after clear")
	}
	*f.now = f.now.Add(ChallengeTTL + time.Second)
	f.s.OpenWithLease()
	if _, err := f.s.Begin(f.req); err != nil {
		t.Fatalf("post-clear Begin = %v, want nil", err)
	}
}

// The default posture after construction is closed. A fresh
// companion process must NEVER auto-arm the receiving window.
func TestFreshServiceIsClosed(t *testing.T) {
	f := setup(t)
	if p := f.s.Pending(); p.Open {
		t.Fatal("fresh service started in Open state")
	}
	if _, err := f.s.Begin(f.req); !errors.Is(err, ErrClosed) {
		t.Fatalf("fresh Begin = %v, want ErrClosed", err)
	}
}

// A bounded global begin budget is the residual DoS surface even
// when the per-IP limiter is bypassed (private / LAN admin path).
// A malicious LAN peer can still occupy the single pending slot;
// that bounded residual is documented and not claimed to be
// eliminated. The six-valid/two-minute budget bounds the number
// of VALID admission decisions in any BeginsWindow; lease
// renewal does NOT reset it.
func TestBoundedValidBeginsPerLease(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatalf("first Begin: %v", err)
	}
	// Six valid begins interleaved with signed cancels and lease
	// renewals. Each iteration consumes one rolling-window slot;
	// the 7th attempt is limited.
	for i := 0; i < 6; i++ {
		if i > 0 {
			// Refresh the lease BEFORE the next Begin so the
			// manager's serial timer is exercised and the
			// rolling begins counter is preserved.
			f.s.OpenWithLease()
			c, err = f.s.Begin(f.req)
			if err != nil {
				t.Fatalf("iteration %d Begin: %v", i, err)
			}
		}
		if err := f.s.Cancel(f.proof(t, c, "CANCEL")); err != nil {
			t.Fatalf("iteration %d cancel: %v", i, err)
		}
	}
	// 7th valid Begin (with another renewal between it and the
	// 6th) trips the rolling-window budget: ErrRateLimited.
	f.s.OpenWithLease()
	_, err = f.s.Begin(f.req)
	if !errors.Is(err, ErrRateLimited) {
		t.Fatalf("bounded begins = %v, want ErrRateLimited", err)
	}
	if got := CodeOf(err); got != CodeRateLimited {
		t.Fatalf("bounded begins code: got %q, want %q", got, CodeRateLimited)
	}
}

// Seven invalid Begins interleaved with lease renewals must NOT
// trip the rolling-window rate limit. Malformed requests are
// rejected BEFORE the budget is charged; lease renewal does not
// change that semantic. The per-IP Begin limiter on the HTTP
// layer is the malformed-traffic DoS guard.
func TestMalformedBeginsAcrossLeaseRenewalsDoNotBurnBudget(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	invalid := BeginRequest{Schema: 2, Key: f.req.Key, Nonce: f.req.Nonce}
	var last error
	for i := 0; i < 7; i++ {
		_, last = f.s.Begin(invalid)
		if !errors.Is(last, ErrInvalid) {
			t.Fatalf("iteration %d: got %v, want ErrInvalid", i, last)
		}
		// Renew the lease every iteration. The rolling budget
		// is NOT charged by the malformed attempts.
		f.s.OpenWithLease()
	}
	if len(f.s.beginsRolling) != 0 {
		t.Fatalf("malformed attempts burned the budget: %d entries", len(f.s.beginsRolling))
	}
	// A VALID Begin after the malformed spam still succeeds.
	if _, err := f.s.Begin(f.req); err != nil {
		t.Fatalf("valid Begin after malformed spam = %v, want nil", err)
	}
}

// Late renewal does NOT revive an expired lease's pending
// attempt. The sequence:
//
//  1. renew → lease live
//  2. begin → pending attempt A
//  3. advance 11 sec → lease expires, expireLocked drops A
//     (both deadlines zero, state != approved)
//  4. renew → lease live again, but A is gone
//
// Original Poll on A must return ErrExpired; original Decide on
// A must return ErrExpired. A fresh Begin succeeds with a NEW
// session_id (the old session_id cannot be revived).
func TestLateRenewalDoesNotReviveExpiredAttempt(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	original := c.ID
	// Advance past the 10 s lease without renewing.
	*f.now = f.now.Add(ReceiveLease + time.Second)
	// Late renewal: lease live again, but original pending is gone.
	f.s.OpenWithLease()
	// Original Poll MUST ErrExpired.
	if _, err := f.s.Poll(f.proof(t, c, "POLL")); !errors.Is(err, ErrExpired) {
		t.Fatalf("original poll = %v, want ErrExpired", err)
	}
	// Original Decide MUST ErrExpired.
	if err := f.s.Decide(c.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("original decide = %v, want ErrExpired", err)
	}
	// A fresh Begin succeeds with a different session_id.
	c2, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatalf("post-late-renewal Begin = %v, want nil", err)
	}
	if c2.ID == original {
		t.Fatal("session_id was recycled; new begin must mint a new id")
	}
}

// Suppress1h cancels a UNAPPROVED pending attempt. The sequence:
//
//  1. open lease, begin attempt A (unapproved)
//  2. suppress → expireLocked drops A (unapproved + both
//     deadlines zero)
//  3. A's Poll/Decide must ErrExpired.
//  4. RenewReceiveLease cannot revive A; Begin stays blocked.
//
// Paired credentials are not the focus of this test (the
// separate TestSuppress1hPausesAndPreservesCredentials covers
// the records-survive-suppression semantic).
func TestSuppressCancelsUnapprovedPending(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	cA, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	// Suppress. Unapproved A is dropped.
	f.s.SetSuppressed(f.now.Add(1 * time.Hour))
	if _, err := f.s.Poll(f.proof(t, cA, "POLL")); !errors.Is(err, ErrExpired) {
		t.Fatalf("unapproved suppressed Poll = %v, want ErrExpired", err)
	}
	if err := f.s.Decide(cA.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("unapproved suppressed Decide = %v, want ErrExpired", err)
	}
	// RenewReceiveLease cannot revive A and cannot lift suppression.
	ps := f.s.RenewReceiveLease()
	if !ps.Suppressed {
		t.Fatal("renew cleared suppression")
	}
	if _, err := f.s.Begin(f.req); !errors.Is(err, ErrClosed) {
		t.Fatalf("post-renew-suppressed Begin = %v, want ErrClosed", err)
	}
}

// A denied attempt must NOT hold the slot for the full
// 180-second ChallengeTTL. The owner can issue a fresh Begin
// immediately after the deny decision, and the original attempt
// is unreachable (its Poll/Decide return ErrExpired). PENDING
// and APPROVED attempts are never replaced.
func TestDenialDoesNotHoldSlot(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	cA, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	// Owner denies. The slot is released for immediate reuse.
	if err := f.s.Decide(cA.ID, f.s.Pending().Code, false); err != nil {
		t.Fatal(err)
	}
	// A new Begin succeeds without Close/Open or 180-second wait.
	cB, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatalf("post-deny Begin: %v", err)
	}
	if cB.ID == cA.ID {
		t.Fatal("session_id recycled; denied attempt still held the slot")
	}
	// The denied A is unreachable: Poll/Decide both ErrExpired.
	if _, err := f.s.Poll(f.proof(t, cA, "POLL")); !errors.Is(err, ErrExpired) {
		t.Fatalf("denied A Poll: %v, want ErrExpired", err)
	}
	if err := f.s.Decide(cA.ID, "code", true); !errors.Is(err, ErrExpired) {
		t.Fatalf("denied A Decide: %v, want ErrExpired", err)
	}
	// Pending snapshot reflects the live B attempt.
	if p := f.s.Pending(); p.ID != cB.ID {
		t.Fatalf("pending id = %q, want %q (denied A still surfaced)", p.ID, cB.ID)
	}

	// A PENDING attempt is NEVER replaced by a fresh Begin.
	cC, err := f.s.Begin(f.req)
	if !errors.Is(err, ErrBusy) {
		t.Fatalf("pending replacement Begin: %v, want ErrBusy", err)
	}
	if f.s.current == nil || f.s.current.challenge.ID != cB.ID {
		t.Fatal("pending attempt was replaced by ErrBusy Begin")
	}
	_ = cC
}

// RenewReceiveLease drops a DENIED current so a fresh Begin from
// the next headset succeeds without the 180-second TTL lock.
// PENDING and APPROVED currents are preserved unchanged across the
// renewal.
func TestRenewReceiveLeaseDropsDenied(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	cA, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.s.Decide(cA.ID, f.s.Pending().Code, false); err != nil {
		t.Fatal(err)
	}
	// Tick the lease; the renewal MUST clear the denied current.
	f.s.RenewReceiveLease()
	cB, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatalf("post-deny renew Begin: %v", err)
	}
	if cB.ID == cA.ID {
		t.Fatal("denied attempt survived lease renewal")
	}
}

// PendingSnapshot represents an APPROVED result as
// State="approved" even when Open=false, so the headset can poll
// the approved credential across the dialog close. Close cancels
// the lease but the approved result remains deliverable until the
// 180-second ChallengeTTL elapses.
func TestApprovedStatePersistsAcrossClose(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.s.Decide(c.ID, f.s.Pending().Code, true); err != nil {
		t.Fatal(err)
	}
	// Close drops the lease but the approved result MUST survive.
	f.s.Close()
	p := f.s.Pending()
	if p.State != "approved" {
		t.Fatalf("pending.State = %q, want approved", p.State)
	}
	if p.Open {
		t.Fatalf("pending.Open = true after Close")
	}
	// The original proof still polls the credential.
	res, err := f.s.Poll(f.proof(t, c, "POLL"))
	if err != nil || res.State != "approved" {
		t.Fatalf("post-close poll: (%+v, %v), want (approved, nil)", res, err)
	}
	// Advance past the ChallengeTTL: the approved result MUST
	// finally fall and Open MUST be false.
	*f.now = f.now.Add(ChallengeTTL + time.Second)
	if p := f.s.Pending(); p.State == "approved" {
		t.Fatal("approved result survived past its own ChallengeTTL")
	}
	if _, err := f.s.Poll(f.proof(t, c, "POLL")); !errors.Is(err, ErrExpired) {
		t.Fatalf("post-ttl poll: %v, want ErrExpired", err)
	}
}

// Cancel of an approved attempt wraps a revocation storage
// failure as ErrStorageFailed. The wrapping is stable through
// errors.Is so the manager's envelope emits the actionable
// STORAGE_FAILED code.
func TestCancelStorageFailureWraps(t *testing.T) {
	f := setup(t)
	f.s.OpenWithLease()
	c, err := f.s.Begin(f.req)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.s.Decide(c.ID, f.s.Pending().Code, true); err != nil {
		t.Fatal(err)
	}
	// Approved credential is published.
	if len(f.s.records) != 1 {
		t.Fatalf("records after approve = %d, want 1", len(f.s.records))
	}
	// Flip storage to fail. Cancel must revoke and surface the
	// storage failure wrapped as ErrStorageFailed.
	f.store.fail = true
	err = f.s.Cancel(f.proof(t, c, "CANCEL"))
	if !errors.Is(err, ErrStorageFailed) {
		t.Fatalf("cancel storage wrap: got %v, want ErrStorageFailed", err)
	}
	if got := CodeOf(err); got != CodeStorage {
		t.Fatalf("cancel storage code: got %q, want %q", got, CodeStorage)
	}
	// Records unchanged because the revoke failed to persist.
	if len(f.s.records) != 1 {
		t.Fatalf("records after failed revoke = %d, want 1", len(f.s.records))
	}
}
