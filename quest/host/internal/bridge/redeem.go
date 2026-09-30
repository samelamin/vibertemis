// redeem.go: HTTPS POST /pairing/redeem endpoint logic.
//
// The Quest signs an enrollment canonical with its
// PRIVATE key (for the cert bound at issue_grant time);
// the companion verifies the signature against the
// grant's stored cert PEM. The companion pins against the
// grant's stored SHA + nonce; the HTTPS body does NOT
// carry the client cert SHA — it is derived from the
// stored grant.
//
// The companion cert pin (companionCertSHA) is the
// companion's own HTTPS cert fingerprint, returned in the
// response so the Quest can pin its HMAC channel against
// the companion cert (NOT the client cert).
//
// Transaction shape (per adjudicated contract):
//
//  1. Parse body, validate shapes OUTSIDE the lock.
//  2. Singleflight collapse on grant key (under s.mu).
//  3. Lock pass under s.mu: verify nonce equality against
//     the stored grant and capture bindable fields.
//     Unlock.
//  4. RSA signature verify OUTSIDE the lock, against the
//     grant-bound client cert PEM.
//  5. Fresh host-authority round trip OUTSIDE the lock.
//     Authorized=false with err=nil is an explicit deny
//     and fails closed.
//  6. ATOMIC state transaction: persistMu.Lock →
//     s.mu.Lock. Revalidate grant + epoch + device cap
//     (replacement of an existing same-client record is
//     allowed at cap). Build a candidate snapshot WITHOUT
//     mutating s.devices. Call SaveDevices WHILE STILL
//     HOLDING s.mu (bounded local I/O, never network).
//     On save failure: return WITHOUT any memory mutation;
//     the prior credential stays intact and the grant is
//     unconsumed. On save success: install the in-memory
//     record, mark the grant redeemed, bump the epoch,
//     release both mutexes.
package bridge

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"strings"
	"time"
)

// RedeemRequest is the wire shape posted by the Quest.
type RedeemRequest struct {
	Schema      int    `json:"schema"`
	Grant       string `json:"grant"`
	ClientNonce string `json:"client_nonce"`
	Signature   string `json:"signature"`
}

// RedeemResponse is the wire shape returned to the Quest.
// Certpin is the COMPANION HTTPS cert SHA-256 (used to
// pin the HMAC channel), NOT the client cert.
type RedeemResponse struct {
	Schema      int    `json:"schema"`
	DeviceID    string `json:"device_id"`
	Token       string `json:"token"`
	CertPin     string `json:"certpin"`
	CertPEM     string `json:"cert_pem"`
	HostCertSHA string `json:"host_cert_sha256"`
	ClientUUID  string `json:"client_uuid"`
}

// Persist is the durability contract.
type Persist interface {
	SaveDevices(records []PersistedDevice) error
}

// EnrollmentCanonical returns the exact byte sequence the
// Quest signs, per the contract:
//
//	VIBERTEMIS-VR-ENROLL-1\nclient_nonce\ngrant\nhost_cert_sha256\ncompanion_cert_sha256\nclient_uuid
//
// with actual LF separators and no trailing LF.
func EnrollmentCanonical(clientNonce, grant, hostSHA, companionSHA, clientUUID string) []byte {
	var b strings.Builder
	b.Grow(len("VIBERTEMIS-VR-ENROLL-1") + len(clientNonce) + len(grant) +
		len(hostSHA) + len(companionSHA) + len(clientUUID) + 5)
	b.WriteString("VIBERTEMIS-VR-ENROLL-1")
	b.WriteByte('\n')
	b.WriteString(clientNonce)
	b.WriteByte('\n')
	b.WriteString(grant)
	b.WriteByte('\n')
	b.WriteString(hostSHA)
	b.WriteByte('\n')
	b.WriteString(companionSHA)
	b.WriteByte('\n')
	b.WriteString(clientUUID)
	return []byte(b.String())
}

// VerifySignature returns nil if signatureBase64 is a valid
// RSA-PKCS1v15 SHA-256 signature over `canonical` using
// the public key in clientCertPEM.
func VerifySignature(clientCertPEM string, canonical []byte, signatureBase64 string) error {
	block, _ := pem.Decode([]byte(clientCertPEM))
	if block == nil || block.Type != "CERTIFICATE" {
		return fmt.Errorf("%w: client cert pem", ErrBadSignature)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return fmt.Errorf("%w: parse client cert: %v", ErrBadSignature, err)
	}
	pub, ok := cert.PublicKey.(*rsa.PublicKey)
	if !ok {
		return fmt.Errorf("%w: only RSA client certs are supported", ErrBadSignature)
	}
	sig, err := base64.StdEncoding.DecodeString(signatureBase64)
	if err != nil {
		return fmt.Errorf("%w: base64: %v", ErrBadSignature, err)
	}
	hashed := sha256.Sum256(canonical)
	if err := rsa.VerifyPKCS1v15(pub, crypto.SHA256, hashed[:], sig); err != nil {
		return fmt.Errorf("%w: rsa verify: %v", ErrBadSignature, err)
	}
	_ = ok
	return nil
}

// RedeemInput is the in-process bundle the HTTP handler
// passes to Service.Redeem. No mTLS: the client cert is
// derived from the grant.
type RedeemInput struct {
	Body       []byte
	Authorizer Authorizer
	Persist    Persist
	Now        func() time.Time
}

// Redeem orchestrates one POST /pairing/redeem.
func (s *Service) Redeem(in RedeemInput) (*RedeemResponse, error) {
	now := in.Now
	if now == nil {
		now = s.now
	}
	if len(in.Body) > MaxBodyBytes {
		return nil, fmt.Errorf("%w: body too large", ErrSchema)
	}
	var req RedeemRequest
	if err := json.Unmarshal(in.Body, &req); err != nil {
		return nil, fmt.Errorf("%w: parse redeem: %v", ErrSchema, err)
	}
	if req.Schema != SchemaVersion {
		return nil, fmt.Errorf("%w: schema=%d", ErrSchema, req.Schema)
	}
	if !hex64.MatchString(req.Grant) {
		return nil, fmt.Errorf("%w: grant", ErrBadField)
	}
	if !hex64.MatchString(req.ClientNonce) {
		return nil, fmt.Errorf("%w: client_nonce", ErrBadField)
	}
	if req.Signature == "" {
		return nil, fmt.Errorf("%w: signature", ErrBadField)
	}

	// Singleflight collapse: one redemption per grant at a
	// time.
	s.mu.Lock()
	if _, busy := s.singleflight[req.Grant]; busy {
		s.mu.Unlock()
		return nil, ErrConcurrentRedemption
	}
	wait := make(chan struct{})
	s.singleflight[req.Grant] = wait
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.singleflight, req.Grant)
		close(wait)
		s.mu.Unlock()
	}()

	// First read under the lock to capture bindable fields
	// and verify client_nonce. The signature verify and the
	// host-authority round trip happen OUTSIDE the lock.
	s.mu.Lock()
	g, ok := s.pendingGrants[req.Grant]
	if !ok {
		s.mu.Unlock()
		return nil, ErrGrantExpired
	}
	if g.redeemed {
		s.mu.Unlock()
		return nil, ErrGrantReplay
	}
	if g.expiresUnix <= now().Unix() {
		delete(s.pendingGrants, req.Grant)
		s.mu.Unlock()
		return nil, ErrGrantExpired
	}
	if g.clientNonce != req.ClientNonce {
		s.mu.Unlock()
		return nil, ErrBadNonce
	}
	clientUUID := g.clientUUID
	clientPEM := g.clientCertPEM
	clientSHA := g.clientCertSHA
	hostSHA := g.hostCertSHA
	compSHA := g.companionCertSHA
	preEpoch := s.persistenceEpoch
	s.mu.Unlock()

	// Signature verify OUTSIDE the lock, using the
	// grant-bound client cert. The Quest proves possession
	// of the private key for THIS cert.
	canonical := EnrollmentCanonical(req.ClientNonce, req.Grant, hostSHA, compSHA, clientUUID)
	if err := VerifySignature(clientPEM, canonical, req.Signature); err != nil {
		return nil, err
	}

	// Fresh host authority OUTSIDE the lock.
	if in.Authorizer == nil {
		return nil, ErrHostUnreachable
	}
	auth, err := in.Authorizer.Authorize(AuthorizeRequest{
		ClientUUID:    clientUUID,
		ClientCertSHA: clientSHA,
	})
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrHostUnreachable, err)
	}
	if !auth.Authorized {
		// Authorized=false with err=nil is an explicit
		// denial — fail closed. The grant is NOT consumed.
		return nil, ErrHostDenied
	}

	// === ATOMIC state transaction ===
	// Lock order: persistMu then s.mu. Never the reverse.
	s.persistMu.Lock()
	defer s.persistMu.Unlock()

	if in.Persist == nil {
		return nil, ErrPersistFailed
	}

	// Re-lock under s.mu; revalidate everything.
	s.mu.Lock()

	g2, ok := s.pendingGrants[req.Grant]
	if !ok {
		s.mu.Unlock()
		return nil, ErrGrantExpired
	}
	if g2.redeemed {
		s.mu.Unlock()
		return nil, ErrGrantReplay
	}
	if g2.expiresUnix <= now().Unix() {
		delete(s.pendingGrants, req.Grant)
		s.mu.Unlock()
		return nil, ErrGrantExpired
	}
	if g2.clientNonce != req.ClientNonce {
		s.mu.Unlock()
		return nil, ErrBadNonce
	}
	if g2.clientUUID != clientUUID ||
		g2.clientCertSHA != clientSHA ||
		g2.clientCertPEM != clientPEM ||
		g2.hostCertSHA != hostSHA ||
		g2.companionCertSHA != compSHA {
		s.mu.Unlock()
		return nil, ErrGrantConflict
	}
	if s.persistenceEpoch != preEpoch {
		// A revoke or another save landed during the host
		// round trip. Abort; the client retries with a
		// fresh grant.
		s.mu.Unlock()
		return nil, ErrGrantRevoked
	}

	// Device cap. Replacement of an existing same-client
	// record is allowed at the cap (we never silently
	// evict an unrelated paired device).
	_, isReplacement := s.devices[clientUUID]
	if !isReplacement && len(s.devices) >= deviceLimit {
		s.mu.Unlock()
		return nil, fmt.Errorf("%w: device cap reached; revoke a device first", ErrLimitsExceeded)
	}

	// Allocate the fresh credential INSIDE the lock.
	deviceID, err := randomHex(16)
	if err != nil {
		s.mu.Unlock()
		return nil, err
	}
	token, err := randomHex(32)
	if err != nil {
		s.mu.Unlock()
		return nil, err
	}

	// Build the candidate snapshot WITHOUT mutating
	// s.devices. If the entry for clientUUID already exists
	// we DROP it from the candidate; the new record takes
	// its slot. No silent eviction of unrelated entries.
	candidateSnapshot := make([]PersistedDevice, 0, len(s.devices)+1)
	for cUUID, d := range s.devices {
		if cUUID == clientUUID {
			continue
		}
		candidateSnapshot = append(candidateSnapshot, PersistedDevice{
			DeviceID:         d.deviceID,
			Token:            d.token,
			ClientCertPEM:    d.clientCertPEM,
			ClientCertSHA:    d.clientCertSHA,
			HostCertSHA:      d.hostCertSHA,
			ClientUUID:       d.clientUUID,
			CompanionCertSHA: d.companionCertSHA,
		})
	}
	candidateSnapshot = append(candidateSnapshot, PersistedDevice{
		DeviceID:         deviceID,
		Token:            token,
		ClientCertPEM:    clientPEM,
		ClientCertSHA:    clientSHA,
		HostCertSHA:      hostSHA,
		ClientUUID:       clientUUID,
		CompanionCertSHA: compSHA,
	})

	// SaveDevices is bounded local I/O. We hold s.mu for
	// the duration so the candidate snapshot is the ONLY
	// state the I/O sees. On failure we return WITHOUT any
	// in-memory mutation — the prior credential stays
	// intact and the grant is unconsumed.
	if err := in.Persist.SaveDevices(candidateSnapshot); err != nil {
		s.mu.Unlock()
		return nil, fmt.Errorf("%w: %v", ErrPersistFailed, err)
	}

	// Success. Commit in-memory state under the same lock
	// before unlocking.
	s.devices[clientUUID] = &deviceRecord{
		deviceID:         deviceID,
		token:            token,
		clientCertPEM:    clientPEM,
		clientCertSHA:    clientSHA,
		hostCertSHA:      hostSHA,
		clientUUID:       clientUUID,
		companionCertSHA: compSHA,
	}
	g2.redeemed = true
	s.persistenceEpoch = preEpoch + 1
	s.mu.Unlock()

	return &RedeemResponse{
		Schema:      SchemaVersion,
		DeviceID:    deviceID,
		Token:       token,
		CertPin:     compSHA, // COMPANION cert pin, NOT client cert
		CertPEM:     s.companionCertPEM,
		HostCertSHA: hostSHA,
		ClientUUID:  clientUUID,
	}, nil
}

// errCompare keeps the errors import alive when tests want
// to assert typed errors.
func errCompare(a, b error) bool { return errors.Is(a, b) }

var _ = errCompare
