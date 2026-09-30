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

// ErrorCode is the structured pairing error code surfaced to the
// headset. Stable wire values; never reuse a name with a different
// meaning. The headset pairs each code to a bounded user-facing
// message via a whitelist so the local UI never echoes arbitrary
// remote text.
type ErrorCode string

const (
	CodeClosed      ErrorCode = "CLOSED"
	CodeExpired     ErrorCode = "EXPIRED"
	CodeBusy        ErrorCode = "BUSY"
	CodeRateLimited ErrorCode = "RATE_LIMITED"
	CodeInvalid     ErrorCode = "INVALID"
	CodeCapacity    ErrorCode = "CAPACITY"
	CodeStorage     ErrorCode = "STORAGE_FAILED"
	CodeUnknown     ErrorCode = "UNKNOWN"
)

// Sentinel errors returned to the LAN pairing endpoints. Strings are
// the canonical owner-controlled messages; the wire envelope pairs
// each with a matching ErrorCode.
var (
	ErrClosed        = errors.New("Pairing is closed. On the PC, choose Pair headset in VR Host Manager.")
	ErrExpired       = errors.New("Pairing expired or was cancelled. Choose Pair headset on the PC and retry.")
	ErrBusy          = errors.New("Another headset is awaiting approval. Check the request on the PC first.")
	ErrRateLimited   = errors.New("Too many pairing requests. Close and reopen Pair headset on the PC.")
	ErrInvalid       = errors.New("Pairing identity did not match. Cancel and retry on both devices.")
	ErrCapacity      = errors.New("Headset limit reached. Forget paired headsets in the PC manager before retrying.")
	ErrStorageFailed = errors.New("Could not save pairing on this PC. Retry, or reinstall the VR Manager if the error persists.")
)

// CodeOf maps a sentinel error (or any error chained to one via
// errors.Is) to its stable wire code. Returns CodeUnknown for nil or
// unrecognized errors; callers MUST treat CodeUnknown as opaque and
// surface only the bounded fallback message.
func CodeOf(err error) ErrorCode {
	switch {
	case err == nil:
		return ""
	case errors.Is(err, ErrClosed):
		return CodeClosed
	case errors.Is(err, ErrExpired):
		return CodeExpired
	case errors.Is(err, ErrBusy):
		return CodeBusy
	case errors.Is(err, ErrRateLimited):
		return CodeRateLimited
	case errors.Is(err, ErrInvalid):
		return CodeInvalid
	case errors.Is(err, ErrCapacity):
		return CodeCapacity
	case errors.Is(err, ErrStorageFailed):
		return CodeStorage
	default:
		return CodeUnknown
	}
}

// ReceiveLease is the bounded lease window for the receiving mode.
// The Windows manager refreshes the lease by calling
// RenewReceiveLease on a serial timer; the 10 s lease + ~2 s
// refresh overlap tolerates a single timer-tick miss without
// dropping the window. When the manager stops (process exit,
// explicit Turn off, Suppress1h) the lease expires naturally and
// any pending attempt is invalidated.
//
// The constant lives on the Service so tests can drive an injected
// clock through the same code path.
const ReceiveLease = 10 * time.Second

// ChallengeTTL is the bounded lifetime of a single pairing
// challenge, independent of the lease window. A challenge
// issued under the receiving lease is valid for ChallengeTTL from
// the moment of Begin; the headset can complete Poll / Cancel
// against the same logical session across multiple lease
// renewals, headset dozes, or TLS reconnects.
const ChallengeTTL = 180 * time.Second

// LegacyOpenWindow is the legacy 2-minute one-shot open window
// used by the standalone-pairing explicit "Pair headset" dialog.
// The challenge lifetime in legacy mode equals LegacyOpenWindow so
// the existing preview8 client contract is unchanged.
const LegacyOpenWindow = 2 * time.Minute

// BeginsWindow is the rolling-window cap for the 6-begins-per-window
// DoS budget. The window is INDEPENDENT of the lease renewal clock:
// a malicious peer cannot burn a fresh budget every 2 seconds
// because the counter slides forward by BeginsWindow, not by the
// lease clock.
const BeginsWindow = 2 * time.Minute

// BeginsCap is the maximum number of VALID begin attempts in
// BeginsWindow. Malformed / invalid-schema requests are rejected
// BEFORE this counter is charged so a noisy LAN peer cannot burn
// the global budget with garbage; the per-IP Begin limiter on
// the HTTP layer is the dedicated malformed-traffic DoS guard.
const BeginsCap = 6

// RecordsCap is the maximum number of paired devices the service
// will retain. Begin rejects a new attempt with ErrCapacity when
// the cap is hit; the owner must Forget paired headsets before
// retrying. The cap is also rechecked inside Decide as a defence
// in depth so a concurrent Decide cannot squeeze in a 33rd record.
const RecordsCap = 32

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
	// TTLSeconds is the explicit per-challenge TTL in seconds. The
	// headset uses this for clock-skew-safe local deadline
	// computation: localDeadline = observed_unix + TTLSeconds +
	// maxSkew. Optional in the wire envelope so preview8 clients
	// (which read `expires_unix`) keep their existing behaviour.
	TTLSeconds int64 `json:"ttl_seconds,omitempty"`
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
	// Open is true if EITHER the legacy one-shot window OR the
	// receiving lease is active AND no Suppress1h window is in
	// effect. Headset-friendly view of "is pairing available now".
	Open bool `json:"open"`
	// Receiving indicates the receiving-mode lease is the source
	// of the open state. The manager uses this to render the
	// tray icon without confusing the legacy one-shot dialog.
	Receiving bool `json:"receiving"`
	// Suppressed indicates the Suppress1h window is currently
	// blocking NEW pairing requests. Existing pending attempts
	// have been cancelled; paired credentials are NOT touched.
	Suppressed bool `json:"suppressed"`
	// SuppressUntilUnix is the wall-clock instant (Unix seconds)
	// until which Suppress1h is in effect. Zero when not
	// suppressed.
	SuppressUntilUnix int64  `json:"suppress_until_unix,omitempty"`
	ID                string `json:"session_id,omitempty"`
	Code              string `json:"code,omitempty"`
	State             string `json:"state"`
	// Expires is the canonical challenge deadline (Unix seconds)
	// for the current pending attempt, or the legacy window
	// deadline if no attempt is in flight.
	Expires int64 `json:"expires_unix"`
	// LeaseExpiresUnix is the receiving lease deadline (Unix
	// seconds). Set ONLY when Receiving is true. The manager uses
	// this to drive its serial timer; the headset uses it as a
	// hint that the lease will be refreshed.
	LeaseExpiresUnix int64 `json:"lease_expires_unix,omitempty"`
	// TTLSeconds mirrors Challenge.TTLSeconds for the active
	// attempt so the headset can compute a clock-skew-safe
	// local deadline without re-parsing the challenge.
	TTLSeconds int64 `json:"ttl_seconds,omitempty"`
	Devices    int   `json:"devices"`
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

	// legacyDeadline is the legacy 2-minute one-shot Open window.
	// Independent of the receiving lease; survives across a
	// Close/Open cycle. Cleared by Close() or ForgetAll(); set
	// only by Open(). Challenge lifetime in legacy mode equals
	// LegacyOpenWindow.
	legacyDeadline time.Time

	// leaseDeadline is the receiving-mode lease deadline. Set /
	// refreshed by RenewReceiveLease; cleared by Close(),
	// ForgetAll(), SetSuppressed() and by expireLocked when past.
	// An expired lease drops only UNAPPROVED pending attempts;
	// approved results are preserved until their own challenge
	// expiry so the headset can poll them across the dialog
	// close.
	leaseDeadline time.Time

	// beginsRolling is the rolling-window counter of VALID Begin
	// attempts. Entries older than BeginsWindow are pruned on
	// every Begin. The counter is NOT reset by lease renewal; a
	// malicious peer cannot burn a fresh budget every 2 seconds.
	// Malformed / invalid-schema attempts are rejected BEFORE
	// they reach this counter (the per-IP Begin limiter on the
	// HTTP layer is the malformed-traffic DoS guard), so the
	// global six-valid/two-minute budget is reserved for actual
	// admission decisions.
	beginsRolling []time.Time

	current         *attempt
	suppressedUntil time.Time
}

func New(certPEM, certSHA string, records []Record, persist Persist, now func() time.Time) (*Service, error) {
	if now == nil {
		now = time.Now
	}
	s := &Service{certPEM: certPEM, certSHA: certSHA, persist: persist, now: now, records: map[string]Record{}}
	if !IsHex(certSHA, 64) || persist == nil || len(records) > RecordsCap {
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

// expireLocked drops expired deadlines and current state per
// the agreed semantics:
//
//  1. Zero any expired legacyDeadline / leaseDeadline so they
//     are not re-used by a late renewal. A late renew therefore
//     sees a zero deadline and starts a fresh lease.
//  2. Drop current if EITHER:
//     (a) its own challenge.Expires has elapsed, OR
//     (b) it is NOT approved AND BOTH legacyDeadline and
//     leaseDeadline are zero (the manager has neither the
//     legacy window open nor a live lease).
//
// APPROVED results survive (b) and only fall when (a) fires —
// the headset can still poll an approved result after Close /
// Suppress / lease expiry, until the per-challenge TTL elapses.
// PENDING / DENIED / unknown results fall as soon as BOTH
// deadlines are zero (i.e. on Close or Suppress without a live
// lease); they do NOT survive the dialog close.
//
// expireLocked runs FIRST in RenewReceiveLease and Open so an
// already-expired lease and its unapproved pending attempt are
// dropped before a new deadline is installed; a late renewal
// cannot revive an expired lease's pending attempt.
func (s *Service) expireLocked() {
	now := s.now()
	if !s.legacyDeadline.IsZero() && !now.Before(s.legacyDeadline) {
		s.legacyDeadline = time.Time{}
	}
	if !s.leaseDeadline.IsZero() && !now.Before(s.leaseDeadline) {
		s.leaseDeadline = time.Time{}
	}
	if s.current != nil {
		if now.Unix() >= s.current.challenge.Expires {
			s.current = nil
		} else if s.current.result.State != "approved" && s.legacyDeadline.IsZero() && s.leaseDeadline.IsZero() {
			s.current = nil
		}
	}
}

// pendingSnapshot returns the canonical view of the service state
// for the manager and the admin HTTP layer. expireLocked is called
// first so the snapshot never includes stale deadlines.
//
// An APPROVED current attempt is preserved across Close, Suppress
// and lease expiry until its own challenge TTL elapses; the
// headset polls the approved result through the dialog close.
// PendingSnapshot therefore reports State="approved" for an
// approved current even when Open=false, so the manager and the
// HTTP layer can surface the approved credential to the polling
// headset. PENDING / DENIED results fall with the lease or the
// dialog, so they are reported as "closed" in that case.
func (s *Service) pendingSnapshot() Pending {
	s.expireLocked()
	now := s.now()
	suppressed := !s.suppressedUntil.IsZero() && now.Before(s.suppressedUntil)
	leaseLive := !s.leaseDeadline.IsZero()
	legacyLive := !s.legacyDeadline.IsZero()
	// Open reflects "is the pairing endpoint currently accepting
	// new Begin requests?". Receiving-mode lease and legacy
	// window both count as "open" for the headset's perspective.
	// Suppression forces the endpoint closed.
	open := !suppressed && (leaseLive || legacyLive)
	receiving := leaseLive && !suppressed
	p := Pending{
		Open:              open,
		Receiving:         receiving,
		Suppressed:        suppressed,
		SuppressUntilUnix: unixIfSet(s.suppressedUntil),
		State:             "waiting",
		Devices:           len(s.records),
	}
	if a := s.current; a != nil {
		p.ID = a.challenge.ID
		p.Code = a.code
		p.State = a.result.State
		p.Expires = a.challenge.Expires
		p.TTLSeconds = int64(ChallengeTTL / time.Second)
	} else if open && legacyLive {
		p.Expires = s.legacyDeadline.Unix()
	}
	if leaseLive {
		p.LeaseExpiresUnix = s.leaseDeadline.Unix()
	}
	// Close the snapshot only when there is no deliverable
	// approved result still in flight. A "closed" status must
	// not overwrite an approved state, or the headset cannot
	// poll its credential after the dialog closes.
	if !open && (s.current == nil || s.current.result.State != "approved") {
		p.State = "closed"
	}
	return p
}

func unixIfSet(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.Unix()
}

// OpenWithLease is a legacy alias kept for tests and the public
// API; it delegates to RenewReceiveLease. The first renewal of a
// fresh service behaves identically to a subsequent renewal; the
// manager can call this every ~2 seconds without conditional
// branching.
func (s *Service) OpenWithLease() Pending { return s.RenewReceiveLease() }

// RenewReceiveLease refreshes the receiving-mode lease. It MUST
// be called by the manager on its serial timer; the implementation
// is the same path used by OpenWithLease so callers do not have
// to conditional on first-vs-renew.
//
// Lifecycle (server-enforced):
//
//  1. expireLocked FIRST. An already-expired lease and its
//     unapproved pending attempt are dropped before the new
//     deadline is installed. A late renewal cannot revive an
//     expired lease.
//  2. Suppression check. If supersedesUntil is in the future,
//     the lease is NOT refreshed and the pending state is
//     returned unchanged. A manager that mistakenly calls
//     RenewReceiveLease during Suppress1h cannot clear the
//     suppression by renewing the lease.
//  3. A DENIED current attempt is dropped before refreshing the
//     lease so a fresh Begin from the next headset is not
//     blocked by a stale 180-second slot lock. PENDING and
//     APPROVED attempts are NEVER replaced by a renewal.
//  4. leaseDeadline = now + ReceiveLease. The legacy deadline
//     is untouched; legacy and receiving are independent.
//  5. An approved pending attempt is preserved unchanged (the
//     challenge expiry is independent of the lease).
//  6. The rolling begins counter is NOT reset.
func (s *Service) RenewReceiveLease() Pending {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked()
	now := s.now()
	if !s.suppressedUntil.IsZero() && now.Before(s.suppressedUntil) {
		return s.pendingSnapshot()
	}
	if s.current != nil && s.current.result.State == "denied" {
		s.current = nil
	}
	s.leaseDeadline = now.Add(ReceiveLease)
	return s.pendingSnapshot()
}

// Open is the legacy one-shot 2-minute window. It MUST NOT
// replace an ACTIVE PENDING attempt (same-slot policy: the user's
// dialog keeps the same code across a re-open). It MAY clear a
// DENIED completed attempt so a fresh attempt can begin without
// the user having to explicitly Close+Open. An APPROVED result
// is preserved through its own challenge TTL regardless of Open.
func (s *Service) Open() Pending {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked()
	if s.current != nil && s.current.result.State == "denied" {
		s.current = nil
	}
	s.legacyDeadline = s.now().Add(LegacyOpenWindow)
	return s.pendingSnapshot()
}

// Close clears BOTH deadlines; expireLocked then drops any
// unapproved current (the manager no longer has either window
// open) while preserving an APPROVED result until its own
// challenge TTL elapses. Paired credentials (records) are NEVER
// revoked by Close(); only ForgetAll() removes them.
func (s *Service) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.legacyDeadline = time.Time{}
	s.leaseDeadline = time.Time{}
	s.expireLocked()
}

// SuppressedUntil returns the wall-clock instant until which the
// service has been suppressed by a "Suppress1h" command, or the
// zero time.Time if no suppression is in place.
func (s *Service) SuppressedUntil() time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.suppressedUntil
}

// SetSuppressed marks the service as suppressed until the given
// wall-clock instant. While suppressed, Begin returns ErrClosed,
// RenewReceiveLease is a no-op, and the legacy deadline is closed
// too. expireLocked then drops any UNAPPROVED current (both
// deadlines are zero); an APPROVED current is preserved through
// its own challenge TTL so the headset can still poll the
// approved result while suppressed. Paired credentials are
// NEVER revoked by SetSuppressed.
//
// Passing the zero time.Time clears the suppression immediately.
func (s *Service) SetSuppressed(until time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.suppressedUntil = until
	s.legacyDeadline = time.Time{}
	s.leaseDeadline = time.Time{}
	s.expireLocked()
}

// IsSuppressed is the cheap path used by the manager's lease timer
// to skip RenewReceiveLease while a Suppress1h window is active.
func (s *Service) IsSuppressed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.suppressedUntil.IsZero() {
		return false
	}
	return s.now().Before(s.suppressedUntil)
}

func (s *Service) Pending() Pending {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pendingSnapshot()
}

func (s *Service) Begin(req BeginRequest) (Challenge, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireLocked()
	// Suppression is server-enforced: a Suppress1h command blocks
	// NEW Begin requests without consulting the manager.
	if !s.suppressedUntil.IsZero() && s.now().Before(s.suppressedUntil) {
		return Challenge{}, ErrClosed
	}
	// Receiving-mode lease OR legacy window must be open.
	if s.legacyDeadline.IsZero() && s.leaseDeadline.IsZero() {
		return Challenge{}, ErrClosed
	}
	if s.current != nil {
		// A DENIED attempt does NOT hold the slot for the
		// full 180-second ChallengeTTL. A fresh Begin from the
		// next headset can discard the denied attempt and
		// accept the new request without Close/Open or a TTL
		// wait. PENDING and APPROVED attempts are NEVER
		// replaced: they must be Cancelled by their owner or
		// must expire via the challenge deadline.
		if s.current.result.State == "denied" {
			s.current = nil
		} else {
			return Challenge{}, ErrBusy
		}
	}
	// Capacity: refuse a new attempt before bothering them with
	// the owner-side dialog. Decide re-checks the same cap as
	// defence in depth.
	if len(s.records) >= RecordsCap {
		return Challenge{}, ErrCapacity
	}
	// Validation FIRST. Malformed / invalid-schema requests are
	// rejected before the global rolling begins budget is charged
	// so a noisy LAN peer cannot burn the six-valid/two-minute
	// admission budget with garbage. The per-IP Begin limiter on
	// the HTTP layer is the dedicated malformed-traffic DoS
	// guard; this service-side check is the wire-format gate that
	// runs after the per-IP admission passes.
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
	// Rolling VALID-begin budget. We slide forward by BeginsWindow
	// so a malicious peer cannot burn a fresh budget every 2
	// seconds. Lease renewal does NOT reset this counter; that is
	// the residual bounded DoS surface even on the LAN.
	now := s.now()
	cutoff := now.Add(-BeginsWindow)
	pruned := s.beginsRolling[:0]
	for _, t := range s.beginsRolling {
		if t.After(cutoff) {
			pruned = append(pruned, t)
		}
	}
	s.beginsRolling = pruned
	if len(s.beginsRolling) >= BeginsCap {
		return Challenge{}, ErrRateLimited
	}
	s.beginsRolling = append(s.beginsRolling, now)
	id, err := randomHex(32)
	if err != nil {
		return Challenge{}, err
	}
	nonce, err := randomHex(32)
	if err != nil {
		return Challenge{}, err
	}
	// Challenge expiry is INDEPENDENT of the lease: it is computed
	// from now + ChallengeTTL. The headset uses this for its
	// local monotonic deadline; the lease can refresh or expire
	// underneath without affecting the logical session.
	challengeExpires := now.Add(ChallengeTTL)
	c := Challenge{
		Schema:      1,
		ID:          id,
		ServerNonce: nonce,
		CertPEM:     s.certPEM,
		Expires:     challengeExpires.Unix(),
		TTLSeconds:  int64(ChallengeTTL / time.Second),
	}
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
			// Cancellation raced approval and the revocation
			// failed to persist. Wrap with ErrStorageFailed so
			// the manager sees the actionable storage code
			// instead of an opaque OS error.
			return fmt.Errorf("%w: %v", ErrStorageFailed, err)
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
	// Capacity recheck as defence in depth: a concurrent Begin
	// from another headset between Begin() and Decide() must not
	// be able to publish a 33rd record.
	if len(s.records) >= RecordsCap {
		return ErrCapacity
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
		// Storage failure: never partially persist. The record
		// is NOT added to s.records, the encrypted token is
		// NOT handed back, and the pending attempt is cleared
		// so a fresh Begin can be issued without Close/Open.
		// The manager receives the actionable storage code.
		s.current = nil
		return fmt.Errorf("%w: %v", ErrStorageFailed, err)
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
		// Storage failures during revocation are surfaced as
		// ErrStorageFailed so the caller (Cancel) can hand the
		// actionable envelope to the manager without leaking
		// the raw OS error string.
		return fmt.Errorf("%w: %v", ErrStorageFailed, err)
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
	s.legacyDeadline = time.Time{}
	s.leaseDeadline = time.Time{}
	s.beginsRolling = nil
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
