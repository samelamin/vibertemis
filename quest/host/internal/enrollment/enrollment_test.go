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
	if len(f.s.records) != 0 {
		t.Fatal("published before persistence")
	}
	f.store.fail = false
	if e = f.s.Decide(c.ID, f.s.Pending().Code, true); e != nil {
		t.Fatal(e)
	}
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
	f.s.Open()
	c, _ = f.s.Begin(f.req)
	*f.now = f.now.Add(121 * time.Second)
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
