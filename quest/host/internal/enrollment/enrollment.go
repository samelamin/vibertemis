// Package enrollment pairs a headset with the companion after local human
// approval. No GameStream host, pairing database or private key is consulted.
package enrollment

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

const Domain = "VIBERTEMIS-STANDALONE-1"

var ErrClosed = errors.New("Pairing is closed. On the PC, choose Pair headset in VR Host Manager.")
var ErrExpired = errors.New("Pairing expired or was cancelled. Choose Pair headset on the PC and retry.")
var ErrInvalid = errors.New("Pairing identity did not match. Cancel and retry on both devices.")

type Record struct {
	ID      string `json:"device_id"`
	Token   string `json:"token"`
	KeySHA  string `json:"key_sha256"`
	CertSHA string `json:"certpin"`
	Created int64  `json:"created_unix"`
}
type Persist interface{ Save([]Record) error }
type BeginRequest struct {
	Schema int    `json:"schema"`
	Key    string `json:"client_key"`
	Nonce  string `json:"client_nonce"`
}
type Challenge struct {
	Schema      int    `json:"schema"`
	ID          string `json:"session_id"`
	ServerNonce string `json:"server_nonce"`
	CertPEM     string `json:"cert_pem"`
	Expires     int64  `json:"expires_unix"`
}
type Proof struct {
	Schema    int    `json:"schema"`
	ID        string `json:"session_id"`
	Signature string `json:"signature"`
}
type Result struct {
	Schema         int    `json:"schema"`
	State          string `json:"state"`
	DeviceID       string `json:"device_id,omitempty"`
	EncryptedToken string `json:"encrypted_token,omitempty"`
}
type Pending struct {
	Open    bool   `json:"open"`
	ID      string `json:"session_id,omitempty"`
	Code    string `json:"code,omitempty"`
	State   string `json:"state"`
	Expires int64  `json:"expires_unix"`
	Devices int    `json:"devices"`
}
type attempt struct {
	challenge                 Challenge
	key                       *rsa.PublicKey
	keySHA, clientNonce, code string
	result                    Result
}
type Service struct {
	mu               sync.Mutex
	certPEM, certSHA string
	now              func() time.Time
	persist          Persist
	records          map[string]Record
	deadline         time.Time
	begins           int
	current          *attempt
}

func New(certPEM, certSHA string, records []Record, persist Persist, now func() time.Time) (*Service, error) {
	if now == nil {
		now = time.Now
	}
	s := &Service{certPEM: certPEM, certSHA: certSHA, persist: persist, now: now, records: map[string]Record{}}
	if !IsHex(certSHA, 64) || persist == nil || len(records) > 32 {
		return nil, ErrInvalid
	}
	for _, r := range records {
		if !IsHex(r.ID, 32) || !IsHex(r.Token, 64) || !IsHex(r.KeySHA, 64) || r.CertSHA != certSHA || r.Created <= 0 {
			return nil, ErrInvalid
		}
		if _, exists := s.records[r.ID]; exists {
			return nil, ErrInvalid
		}
		s.records[r.ID] = r
	}
	return s, nil
}
func IsHex(s string, n int) bool { return len(s) == n && strings.Trim(s, "0123456789abcdef") == "" }
func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
func digest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func Code(pin, keySHA, clientNonce, serverNonce, id string) string {
	raw := strings.ToUpper(digest([]byte(strings.Join([]string{Domain + "-CODE", "1", pin, keySHA, clientNonce, serverNonce, id}, "\n")))[:16])
	return raw[:4] + "-" + raw[4:8] + "-" + raw[8:12] + "-" + raw[12:]
}
func Transcript(action, id, clientNonce, serverNonce, pin string) []byte {
	return []byte(strings.Join([]string{Domain + "-" + action, "1", id, clientNonce, serverNonce, pin}, "\n"))
}
func TokenLabel(id string) []byte { return []byte(Domain + "-TOKEN\n1\n" + id) }
func (s *Service) expireLocked() {
	if !s.now().Before(s.deadline) {
		s.deadline = time.Time{}
		if s.current != nil && s.current.result.State != "approved" {
			s.current = nil
		}
	}
	if s.current != nil && s.now().Unix() >= s.current.challenge.Expires {
		s.current = nil
	}
}
func (s *Service) Open() Pending {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.current = nil
	s.begins = 0
	s.deadline = s.now().Add(2 * time.Minute)
	return s.pendingLocked()
}
func (s *Service) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deadline = time.Time{}
	// The PC dialog can close immediately after approval. Retain only the
	// encrypted approved result until its original deadline for headset delivery.
	// Unapproved requests are cancelled; no new begin is possible while closed.
	s.expireLocked()
}
func (s *Service) pendingLocked() Pending {
	s.expireLocked()
	p := Pending{Open: !s.deadline.IsZero(), State: "waiting", Devices: len(s.records)}
	if p.Open {
		p.Expires = s.deadline.Unix()
	} else {
		p.State = "closed"
	}
	if a := s.current; a != nil {
		p.ID = a.challenge.ID
		p.Code = a.code
		p.State = a.result.State
	}
	return p
}
func (s *Service) Pending() Pending { s.mu.Lock(); defer s.mu.Unlock(); return s.pendingLocked() }
func (s *Service) Begin(req BeginRequest) (Challenge, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked()
	if s.deadline.IsZero() {
		return Challenge{}, ErrClosed
	}
	if s.current != nil {
		return Challenge{}, errors.New("Another headset is awaiting approval. Check the request on the PC first.")
	}
	if s.begins >= 6 {
		return Challenge{}, errors.New("Too many pairing requests. Close and reopen Pair headset on the PC.")
	}
	s.begins++
	if req.Schema != 1 || !IsHex(req.Nonce, 64) || len(req.Key) > 1024 {
		return Challenge{}, ErrInvalid
	}
	der, err := base64.StdEncoding.Strict().DecodeString(req.Key)
	if err != nil {
		return Challenge{}, ErrInvalid
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return Challenge{}, ErrInvalid
	}
	key, ok := parsed.(*rsa.PublicKey)
	if !ok || key.N.BitLen() != 2048 || key.E != 65537 {
		return Challenge{}, ErrInvalid
	}
	id, err := randomHex(32)
	if err != nil {
		return Challenge{}, err
	}
	nonce, err := randomHex(32)
	if err != nil {
		return Challenge{}, err
	}
	c := Challenge{1, id, nonce, s.certPEM, s.deadline.Unix()}
	a := &attempt{challenge: c, key: key, keySHA: digest(der), clientNonce: req.Nonce, result: Result{Schema: 1, State: "pending"}}
	a.code = Code(s.certSHA, a.keySHA, req.Nonce, nonce, id)
	s.current = a
	return c, nil
}
func (s *Service) verifyLocked(req Proof, action string) (*attempt, error) {
	s.expireLocked()
	a := s.current
	if a == nil || req.ID != a.challenge.ID {
		return nil, ErrExpired
	}
	if req.Schema != 1 || len(req.Signature) > 512 {
		return nil, ErrInvalid
	}
	sig, err := base64.StdEncoding.Strict().DecodeString(req.Signature)
	if err != nil {
		return nil, ErrInvalid
	}
	h := sha256.Sum256(Transcript(action, req.ID, a.clientNonce, a.challenge.ServerNonce, s.certSHA))
	if rsa.VerifyPSS(a.key, crypto.SHA256, h[:], sig, &rsa.PSSOptions{SaltLength: 32, Hash: crypto.SHA256}) != nil {
		return nil, ErrInvalid
	}
	return a, nil
}
func (s *Service) Poll(req Proof) (Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.verifyLocked(req, "POLL")
	if err != nil {
		return Result{}, err
	}
	return a.result, nil
}
func (s *Service) Cancel(req Proof) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.verifyLocked(req, "CANCEL")
	if err != nil {
		return err
	}
	// If approval raced cancellation, revoke the unused credential too.
	if a.result.DeviceID != "" {
		if err := s.revokeLocked(a.result.DeviceID); err != nil {
			return err
		}
	}
	s.current = nil
	return nil
}
func (s *Service) Decide(id, code string, approve bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked()
	a := s.current
	if a == nil || id != a.challenge.ID || code != a.code || a.result.State != "pending" {
		return ErrExpired
	}
	if !approve {
		a.result.State = "denied"
		return nil
	}
	if len(s.records) >= 32 {
		return errors.New("Headset limit reached. Forget paired headsets in the PC manager before retrying.")
	}
	id, err := randomHex(16)
	if err != nil {
		return err
	}
	token, err := randomHex(32)
	if err != nil {
		return err
	}
	encrypted, err := rsa.EncryptOAEP(sha256.New(), rand.Reader, a.key, []byte(token), TokenLabel(a.challenge.ID))
	if err != nil {
		return err
	}
	record := Record{id, token, a.keySHA, s.certSHA, s.now().Unix()}
	next := s.recordsLocked("")
	next = append(next, record)
	if err = s.persist.Save(next); err != nil {
		return fmt.Errorf("Could not save pairing on this PC: %w", err)
	}
	s.records[id] = record
	a.result = Result{1, "approved", id, base64.StdEncoding.EncodeToString(encrypted)}
	return nil
}
func (s *Service) recordsLocked(except string) []Record {
	records := make([]Record, 0, len(s.records))
	for id, r := range s.records {
		if id != except {
			records = append(records, r)
		}
	}
	return records
}
func (s *Service) revokeLocked(id string) error {
	if err := s.persist.Save(s.recordsLocked(id)); err != nil {
		return err
	}
	delete(s.records, id)
	return nil
}
func (s *Service) ForgetAll() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.persist.Save([]Record{}); err != nil {
		return err
	}
	s.records = map[string]Record{}
	s.current = nil
	s.deadline = time.Time{}
	return nil
}
func (s *Service) Lookup(id string) (Record, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.records[id]
	return r, ok
}
func (s *Service) AuthorizeAndSelectHMAC(id, keySHA string) (string, string, string, error) {
	r, ok := s.Lookup(id)
	if !ok || r.KeySHA != keySHA {
		return "", "", "", ErrInvalid
	}
	return r.ID, r.Token, r.CertSHA, nil
}
