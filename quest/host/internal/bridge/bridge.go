// Package bridge is the host-side GameStream companion for
// the Vibertemis Quest 3 client.
//
// Authority model:
//
//   - Vibeshine owns the canonical "currently paired" records.
//     The FRESH AUTHORITY for any individual
//     (client_uuid, client_cert_sha256) answer is obtained
//     by sending an outbound `authorize` request over the
//     authenticated pipe. The companion never answers
//     `authorize` from its local cache alone.
//
//   - Inbound RPC ops accepted on the pipe are exactly two:
//     `issue_grant` and `revoke`. Both come from the
//     authenticated Vibeshine side. There is NO inbound
//     `authorize`; the companion does not trust inbound
//     authorize requests as authority.
//
//   - The redeem path (HTTPS POST /pairing/redeem) is the
//     only un-HMAC endpoint on the companion. It asks the
//     host Authorizer for a fresh yes/no BEFORE consuming
//     the grant. The Authorizer call happens OUTSIDE the
//     service mutex.
//
//   - Persistence is serialized through a single persistMu
//     so concurrent redeem + revoke writes do not race on
//     disk. Each transaction: build candidate snapshot
//     under s.mu, unlock, persist, re-lock for post-persist
//     recheck + publish. On persist failure we never modify
//     in-memory state — the old credential stays intact
//     and the grant is not consumed.
//
//   - Idempotent re-issue uses a host-bound key
//     (client_uuid|client_nonce|host_cert_sha256). On a hit
//     the existing grant is returned with its ORIGINAL
//     expiry and ALL of its immutable bindings
//     (certSHA, hostSHA, companionSHA, clientUUID,
//     clientNonce, certPEM). Any binding drift is rejected
//     as a conflict. The 60 s TTL is bound at creation and
//     never extended.
//
// Persistence is per-device and separate from the legacy
// global pairing state. Revoking one device never forces
// the user to re-pair any other device. Legacy pairing
// keeps working unchanged.
package bridge

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Errors.
var (
	ErrUnsupported          = errors.New("bridge: unsupported on this OS")
	ErrSchema               = errors.New("bridge: bad schema")
	ErrBadField             = errors.New("bridge: bad field")
	ErrUnknownOp            = errors.New("bridge: unknown op")
	ErrUnauthorized         = errors.New("bridge: unauthorized")
	ErrNotFound             = errors.New("bridge: not found")
	ErrGrantExpired         = errors.New("bridge: grant expired")
	ErrGrantRevoked         = errors.New("bridge: grant revoked")
	ErrGrantReplay          = errors.New("bridge: grant already redeemed")
	ErrGrantConflict        = errors.New("bridge: grant binding mismatch")
	ErrBadSignature         = errors.New("bridge: bad signature")
	ErrBadNonce             = errors.New("bridge: bad nonce")
	ErrBadFingerprint       = errors.New("bridge: bad fingerprint")
	ErrUnknownClient        = errors.New("bridge: unknown client_uuid")
	ErrLimitsExceeded       = errors.New("bridge: limits exceeded")
	ErrConcurrentRedemption = errors.New("bridge: another redemption in flight")
	ErrHostUnreachable      = errors.New("bridge: host authorizer unreachable")
	ErrHostDenied           = errors.New("bridge: host denied authorization")
	ErrPersistFailed        = errors.New("bridge: persist failed")
	ErrPipeClosed           = errors.New("bridge: pipe closed")
)

// Schema version shared with Vibeshine and the Android client.
const SchemaVersion = 1

// grantTTL is the fixed lifetime of a freshly-minted grant.
const grantTTL = 60 * time.Second

// pendingLimit caps the total number of outstanding grants.
const pendingLimit = 128

// deviceLimit caps the number of distinct per-device
// credentials.
const deviceLimit = 32

// MaxFrameBytes bounds a single IPC frame including the
// 4-byte length prefix.
const MaxFrameBytes = 65536

// MaxBodyBytes bounds the redeem HTTP request body (16 KiB).
const MaxBodyBytes = 16 * 1024

// AuthorizeTimeout bounds a single host-authority round trip.
const AuthorizeTimeout = 5 * time.Second

// pendingRequests caps the number of in-flight outbound
// authorize requests.
const pendingRequests = 32

var hex64 = regexp.MustCompile(`^[0-9a-f]{64}$`)
var hex32 = regexp.MustCompile(`^[0-9a-f]{32}$`)
var clientUUIDRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// Wire envelope. Length prefix is uint32 little-endian.
type Request struct {
	V       int             `json:"v"`
	ID      string          `json:"id"`
	Op      string          `json:"op"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type Response struct {
	V       int             `json:"v"`
	ID      string          `json:"id"`
	OK      bool            `json:"ok"`
	Payload json.RawMessage `json:"payload,omitempty"`
	Error   string          `json:"error,omitempty"`
}

// IssueGrantRequest is the inbound payload from Vibeshine.
type IssueGrantRequest struct {
	ClientUUID    string `json:"client_uuid"`
	ClientCertPEM string `json:"client_cert_pem"`
	ClientCertSHA string `json:"client_cert_sha256"`
	HostCertSHA   string `json:"host_cert_sha256"`
	ClientNonce   string `json:"client_nonce"`
}

// IssueGrantResponse is the companion -> Vibeshine answer.
type IssueGrantResponse struct {
	Schema           int    `json:"schema"`
	Grant            string `json:"grant"`
	ExpiresUnix      int64  `json:"expires_unix"`
	ClientNonce      string `json:"client_nonce"`
	ClientUUID       string `json:"client_uuid"`
	HostCertSHA      string `json:"host_cert_sha256"`
	CompanionCertSHA string `json:"companion_cert_sha256"`
	Port             int    `json:"port"`
}

// RevokeRequest is the inbound revoke payload.
type RevokeRequest struct {
	ClientUUID string `json:"client_uuid"`
}

// AuthorizeRequest is the OUTBOUND query from companion to
// Vibeshine.
type AuthorizeRequest struct {
	ClientUUID    string `json:"client_uuid"`
	ClientCertSHA string `json:"client_cert_sha256"`
}

// AuthorizeResponse mirrors the same shape Vibeshine returns.
type AuthorizeResponse struct {
	Authorized bool `json:"authorized"`
}

// Authorizer is the interface the companion uses to talk to
// Vibeshine for a fresh authority answer.
type Authorizer interface {
	Authorize(req AuthorizeRequest) (AuthorizeResponse, error)
}

// FingerprintDER is the lowercase-hex SHA-256 of a DER blob.
func FingerprintDER(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

// CompanionCertSHA is the lowercase-hex SHA-256 of the
// companion HTTPS cert DER.
func CompanionCertSHA(certPEM string) (string, error) {
	block, _ := pem.Decode([]byte(certPEM))
	if block == nil || block.Type != "CERTIFICATE" {
		return "", fmt.Errorf("companion cert: pem decode")
	}
	return FingerprintDER(block.Bytes), nil
}

// ParseClientCert verifies the client cert PEM is a
// parseable RSA X.509 certificate.
func ParseClientCert(certPEM string) (*x509.Certificate, error) {
	block, _ := pem.Decode([]byte(certPEM))
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, fmt.Errorf("%w: client cert pem", ErrBadField)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("%w: client cert parse: %v", ErrBadField, err)
	}
	if _, isRSA := cert.PublicKey.(*rsa.PublicKey); !isRSA {
		return nil, fmt.Errorf("%w: only RSA client certs are supported", ErrBadField)
	}
	return cert, nil
}

// FingerprintFromPEM is the lowercase-hex SHA-256 of the DER
// in a CERTIFICATE PEM block.
func FingerprintFromPEM(pemText string) (string, error) {
	block, _ := pem.Decode([]byte(pemText))
	if block == nil || block.Type != "CERTIFICATE" {
		return "", fmt.Errorf("pem decode")
	}
	return FingerprintDER(block.Bytes), nil
}

// grantRecord carries EVERYTHING the redeem path needs to
// verify the Quest signature and bind the device record.
type grantRecord struct {
	grant            string
	clientUUID       string
	clientCertPEM    string
	clientCertSHA    string
	hostCertSHA      string
	companionCertSHA string
	clientNonce      string
	expiresUnix      int64
	redeemed         bool
}

// deviceRecord is the per-device credential the redeem
// path installs. companionCertSHA is the COMPANION HTTPS
// cert pin (the HMAC token validates against THIS), NOT the
// client cert. clientCertSHA is the binding identifier only.
type deviceRecord struct {
	deviceID         string
	token            string
	clientCertPEM    string
	clientCertSHA    string
	hostCertSHA      string
	clientUUID       string
	companionCertSHA string
}

// PersistedDevice is the on-disk shape.
type PersistedDevice struct {
	DeviceID         string `json:"device_id"`
	Token            string `json:"token"`
	ClientCertPEM    string `json:"client_cert_pem"`
	ClientCertSHA    string `json:"client_cert_sha256"`
	HostCertSHA      string `json:"host_cert_sha256"`
	ClientUUID       string `json:"client_uuid"`
	CompanionCertSHA string `json:"companion_cert_sha256"`
}

// Service holds the in-process state. Mutex protects
// every field below. persistMu serializes disk writes.
type Service struct {
	mu sync.Mutex

	// persistMu serializes ALL persist operations. A
	// persistMu acquisition blocks any other concurrent
	// persist (revoke, redeem, or startup restore). The
	// two mutexes are always taken in persistMu -> s.mu
	// order at the start of a transaction and never nested
	// in opposite order.
	persistMu sync.Mutex

	companionCertPEM string
	port             int

	// inboundPersist is the durability hook for inbound
	// ops that persist (revoke). nil means revoke is a
	// no-op for durability (in-memory only) — redeem is
	// unaffected and uses Persist from RedeemInput.
	inboundPersist Persist

	pendingGrants map[string]*grantRecord
	pendingKeys   map[string]string
	pendingOrder  []string

	devices map[string]*deviceRecord

	// persistenceEpoch is bumped on every successful save and
	// every revoke.
	persistenceEpoch uint64

	// singleflight guards concurrent redemptions of the same
	// grant.
	singleflight map[string]chan struct{}

	// authorizer is set by the pipe runner while a
	// connection is active and cleared on disconnect.
	authorizerMu sync.RWMutex
	authorizer   Authorizer

	now func() time.Time
}

// New returns a fresh Service bound to a companion cert +
// port.
func New(companionCertPEM string, port int) *Service {
	return &Service{
		companionCertPEM: companionCertPEM,
		port:             port,
		pendingGrants:    make(map[string]*grantRecord),
		pendingKeys:      make(map[string]string),
		devices:          make(map[string]*deviceRecord),
		singleflight:     make(map[string]chan struct{}),
		now:              time.Now,
	}
}

// SetInboundPersist wires the persist hook used by the
// inbound revoke path. May be nil (revoke stays in-memory
// only).
func (s *Service) SetInboundPersist(p Persist) {
	s.mu.Lock()
	s.inboundPersist = p
	s.mu.Unlock()
}

// InboundPersist returns the wired persist hook (or nil).
func (s *Service) InboundPersist() Persist {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.inboundPersist
}

// SetClock is exposed for tests so a fake clock can drive
// expiry / TTL behaviour.
func (s *Service) SetClock(now func() time.Time) { s.now = now }

// SetAuthorizer is called by the pipe runner when a
// Vibeshine connection is accepted and before the first
// request is dispatched. The companion uses this
// authorizer for fresh authority checks (Redeem and
// AuthorizeAndSelectHMAC). When nil, fresh authority is
// unavailable — the companion fails closed.
func (s *Service) SetAuthorizer(a Authorizer) {
	s.authorizerMu.Lock()
	s.authorizer = a
	s.authorizerMu.Unlock()
}

// CurrentAuthorizer returns the live authorizer (nil if no
// pipe connection is active).
func (s *Service) CurrentAuthorizer() Authorizer {
	s.authorizerMu.RLock()
	defer s.authorizerMu.RUnlock()
	return s.authorizer
}

// ClearAuthorizer drops the authorizer (called by the pipe
// runner on disconnect).
func (s *Service) ClearAuthorizer() {
	s.authorizerMu.Lock()
	s.authorizer = nil
	s.authorizerMu.Unlock()
}

// CompanionPort returns the HTTPS port we tell the Quest
// to dial for redeem.
func (s *Service) CompanionPort() int { return s.port }

// CompanionCertPEM returns the verbatim companion HTTPS
// cert.
func (s *Service) CompanionCertPEM() string { return s.companionCertPEM }

// PersistenceEpoch returns the current local epoch.
func (s *Service) PersistenceEpoch() uint64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.persistenceEpoch
}

// HandleInbound dispatches a single inbound RPC frame.
// Only `ping`, `issue_grant` and `revoke` are accepted. Inbound
// `authorize` is rejected with ErrUnknownOp.
func (s *Service) HandleInbound(req *Request) (*Response, error) {
	if req == nil {
		return nil, ErrSchema
	}
	if req.V != SchemaVersion {
		return nil, fmt.Errorf("%w: v=%d", ErrSchema, req.V)
	}
	if req.ID == "" {
		return nil, ErrSchema
	}
	switch req.Op {
	case "ping":
		return &Response{V: SchemaVersion, ID: req.ID, OK: true, Payload: json.RawMessage(`{}`)}, nil
	case "issue_grant":
		return s.opIssueGrant(req)
	case "revoke":
		return s.RevokeAndPersist(req, s.InboundPersist())
	default:
		return nil, fmt.Errorf("%w: %q", ErrUnknownOp, req.Op)
	}
}

// Pending returns the live (un-redeemed, un-expired) count.
func (s *Service) Pending() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneExpiredLocked()
	return len(s.pendingGrants)
}

// Devices returns the live device count.
func (s *Service) Devices() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.devices)
}

// LoadDevices restores per-device credentials on startup.
func (s *Service) LoadDevices(records []PersistedDevice) {
	s.mu.Lock()
	defer s.mu.Unlock()
	seen := make(map[string]bool, len(records))
	for _, r := range records {
		if !hex32.MatchString(r.DeviceID) || !hex64.MatchString(r.Token) {
			continue
		}
		if !hex64.MatchString(r.ClientCertSHA) || !hex64.MatchString(r.HostCertSHA) ||
			!hex64.MatchString(r.CompanionCertSHA) {
			continue
		}
		if !clientUUIDRe.MatchString(r.ClientUUID) {
			continue
		}
		if seen[r.ClientUUID] {
			continue
		}
		seen[r.ClientUUID] = true
		s.devices[r.ClientUUID] = &deviceRecord{
			deviceID:         r.DeviceID,
			token:            r.Token,
			clientCertPEM:    r.ClientCertPEM,
			clientCertSHA:    r.ClientCertSHA,
			hostCertSHA:      r.HostCertSHA,
			clientUUID:       r.ClientUUID,
			companionCertSHA: r.CompanionCertSHA,
		}
	}
}

// LookupDevice selects the HMAC credential for a device.
func (s *Service) LookupDevice(deviceID string) (token, companionCertSHA string, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if d.deviceID == deviceID {
			return d.token, d.companionCertSHA, true
		}
	}
	return "", "", false
}

// LookupDeviceIdentity returns the full per-device
// identity for the device_id, including client_uuid +
// client_cert_sha256 so the start handler can do a fresh
// authorize.
func (s *Service) LookupDeviceIdentity(deviceID string) (DeviceIdentity, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if d.deviceID == deviceID {
			return DeviceIdentity{
				DeviceID:         d.deviceID,
				Token:            d.token,
				ClientUUID:       d.clientUUID,
				ClientCertSHA:    d.clientCertSHA,
				CompanionCertSHA: d.companionCertSHA,
			}, true
		}
	}
	return DeviceIdentity{}, false
}

// DeviceIdentity is the per-device identity the start
// handler needs to do a fresh-authorize round trip.
type DeviceIdentity struct {
	DeviceID         string
	Token            string
	ClientUUID       string
	ClientCertSHA    string
	CompanionCertSHA string
}

// opIssueGrant is the inbound issue_grant handler.
func (s *Service) opIssueGrant(req *Request) (*Response, error) {
	var in IssueGrantRequest
	if err := json.Unmarshal(req.Payload, &in); err != nil {
		return nil, fmt.Errorf("%w: issue_grant payload: %v", ErrSchema, err)
	}
	if err := validateIssueGrant(in); err != nil {
		return nil, err
	}
	clientSHA, err := FingerprintFromPEM(in.ClientCertPEM)
	if err != nil {
		return nil, fmt.Errorf("%w: client_cert_pem: %v", ErrBadField, err)
	}
	if clientSHA != in.ClientCertSHA {
		return nil, fmt.Errorf("%w: client_cert_sha256 does not match cert pem", ErrBadFingerprint)
	}
	companionSHA, err := CompanionCertSHA(s.companionCertPEM)
	if err != nil {
		return nil, fmt.Errorf("companion cert sha: %w", err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneExpiredLocked()

	// Idempotency key includes hostSHA so a cross-host
	// collision is rejected up front.
	key := in.ClientUUID + "|" + in.ClientNonce + "|" + in.HostCertSHA
	if existingGrant, ok := s.pendingKeys[key]; ok {
		if g, ok := s.pendingGrants[existingGrant]; ok {
			// Idempotent re-issue: ALL immutable bindings
			// MUST match exactly. Drift on any of them is
			// a conflict, NOT a refresh. We keep the
			// ORIGINAL expiry — the 60 s TTL is bound at
			// creation and never extended.
			if g.clientUUID != in.ClientUUID ||
				g.clientCertSHA != in.ClientCertSHA ||
				g.hostCertSHA != in.HostCertSHA ||
				g.companionCertSHA != companionSHA ||
				g.clientNonce != in.ClientNonce ||
				g.clientCertPEM != in.ClientCertPEM {
				return nil, fmt.Errorf("%w: idempotent reissue with different bindings", ErrGrantConflict)
			}
			return okResponse(req, IssueGrantResponse{
				Schema:           SchemaVersion,
				Grant:            g.grant,
				ExpiresUnix:      g.expiresUnix,
				ClientNonce:      in.ClientNonce,
				ClientUUID:       in.ClientUUID,
				HostCertSHA:      in.HostCertSHA,
				CompanionCertSHA: companionSHA,
				Port:             s.port,
			})
		}
	}
	if len(s.pendingOrder) >= pendingLimit {
		return nil, fmt.Errorf("%w: pending grants at cap", ErrLimitsExceeded)
	}
	grant, err := randomHex(32)
	if err != nil {
		return nil, err
	}
	s.pendingGrants[grant] = &grantRecord{
		grant:            grant,
		clientUUID:       in.ClientUUID,
		clientCertPEM:    in.ClientCertPEM,
		clientCertSHA:    in.ClientCertSHA,
		hostCertSHA:      in.HostCertSHA,
		companionCertSHA: companionSHA,
		clientNonce:      in.ClientNonce,
		expiresUnix:      s.now().Add(grantTTL).Unix(),
	}
	s.pendingKeys[key] = grant
	s.pendingOrder = append(s.pendingOrder, key)
	return okResponse(req, IssueGrantResponse{
		Schema:           SchemaVersion,
		Grant:            grant,
		ExpiresUnix:      s.pendingGrants[grant].expiresUnix,
		ClientNonce:      in.ClientNonce,
		ClientUUID:       in.ClientUUID,
		HostCertSHA:      in.HostCertSHA,
		CompanionCertSHA: companionSHA,
		Port:             s.port,
	})
}

// RevokeAndPersist is the inbound revoke handler with
// persistence. The same lock order as Redeem applies:
// persistMu first, then s.mu. In-memory revocation
// happens immediately under s.mu and is RETAINED on
// persist failure (fresh host authority protects
// restart). Callers MUST NOT call opRevoke directly —
// only RevokeAndPersist is the supported path.
func (s *Service) RevokeAndPersist(req *Request, persist Persist) (*Response, error) {
	var in RevokeRequest
	if err := json.Unmarshal(req.Payload, &in); err != nil {
		return nil, fmt.Errorf("%w: revoke payload: %v", ErrSchema, err)
	}
	if !clientUUIDRe.MatchString(in.ClientUUID) {
		return nil, fmt.Errorf("%w: client_uuid", ErrBadField)
	}

	s.persistMu.Lock()
	defer s.persistMu.Unlock()

	if persist == nil {
		return nil, ErrPersistFailed
	}

	s.mu.Lock()
	for k, g := range s.pendingGrants {
		if g.clientUUID == in.ClientUUID {
			delete(s.pendingGrants, k)
		}
	}
	for k := range s.pendingKeys {
		if strings.HasPrefix(k, in.ClientUUID+"|") {
			delete(s.pendingKeys, k)
		}
	}
	delete(s.devices, in.ClientUUID)
	s.pendingOrder = s.pendingOrder[:0]
	for k := range s.pendingKeys {
		s.pendingOrder = append(s.pendingOrder, k)
	}
	s.persistenceEpoch++

	// Snapshot is built inline under s.mu — no recursive
	// locking. SaveDevices runs while we still hold s.mu.
	if err := persist.SaveDevices(s.snapshotLocked()); err != nil {
		// Memory revocation stays. Fresh host authority
		// (via the bridge pipe authorizer) catches missed
		// offline revokes on the next authorize round
		// trip. We surface the persist failure to the
		// operator so they can retry.
		s.mu.Unlock()
		return nil, fmt.Errorf("%w: %v", ErrPersistFailed, err)
	}
	s.mu.Unlock()

	return &Response{
		V:  SchemaVersion,
		ID: req.ID,
		OK: true,
	}, nil
}

// AuthorizeAndSelectHMAC is the inherited-start helper. It
// asks the host for fresh authority (outside the service
// mutex via the pipe runner), then selects the per-device
// HMAC credential from the in-memory map. The HMAC pin is
// the COMPANION cert SHA, not the client cert. If the
// host returns authorized=false the call fails closed
// even if err is nil.
func (s *Service) AuthorizeAndSelectHMAC(clientUUID, clientCertSHA string) (deviceID, token, companionCertSHA string, err error) {
	authorizer := s.CurrentAuthorizer()
	if authorizer == nil {
		return "", "", "", ErrHostUnreachable
	}
	if !clientUUIDRe.MatchString(clientUUID) {
		return "", "", "", fmt.Errorf("%w: client_uuid", ErrBadField)
	}
	if !hex64.MatchString(clientCertSHA) {
		return "", "", "", fmt.Errorf("%w: client_cert_sha256", ErrBadField)
	}
	auth, err := authorizer.Authorize(AuthorizeRequest{
		ClientUUID:    clientUUID,
		ClientCertSHA: clientCertSHA,
	})
	if err != nil {
		return "", "", "", fmt.Errorf("%w: %v", ErrHostUnreachable, err)
	}
	if !auth.Authorized {
		// Authorized=false with err=nil is an explicit
		// denial. We fail closed.
		return "", "", "", ErrHostDenied
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if d.clientUUID == clientUUID && d.clientCertSHA == clientCertSHA {
			return d.deviceID, d.token, d.companionCertSHA, nil
		}
	}
	return "", "", "", ErrUnknownClient
}

// snapshotLocked returns the current device list as
// PersistedDevice rows. ASSUMES s.mu is held.
func (s *Service) snapshotLocked() []PersistedDevice {
	out := make([]PersistedDevice, 0, len(s.devices))
	for _, d := range s.devices {
		out = append(out, PersistedDevice{
			DeviceID:         d.deviceID,
			Token:            d.token,
			ClientCertPEM:    d.clientCertPEM,
			ClientCertSHA:    d.clientCertSHA,
			HostCertSHA:      d.hostCertSHA,
			ClientUUID:       d.clientUUID,
			CompanionCertSHA: d.companionCertSHA,
		})
	}
	return out
}

// pruneExpiredLocked drops grants whose expiry has passed.
// Must be called with s.mu held.
func (s *Service) pruneExpiredLocked() {
	now := s.now().Unix()
	for k, g := range s.pendingGrants {
		if g.expiresUnix <= now {
			delete(s.pendingGrants, k)
		}
	}
	s.pendingOrder = s.pendingOrder[:0]
	for k := range s.pendingKeys {
		evicted := true
		for _, g := range s.pendingGrants {
			if g.clientUUID+"|"+g.clientNonce+"|"+g.hostCertSHA == k {
				evicted = g.expiresUnix <= now
				break
			}
		}
		if !evicted {
			s.pendingOrder = append(s.pendingOrder, k)
		} else {
			delete(s.pendingKeys, k)
		}
	}
}

func validateIssueGrant(in IssueGrantRequest) error {
	if !clientUUIDRe.MatchString(in.ClientUUID) {
		return fmt.Errorf("%w: client_uuid", ErrBadField)
	}
	if in.ClientCertPEM == "" {
		return fmt.Errorf("%w: client_cert_pem", ErrBadField)
	}
	if _, err := ParseClientCert(in.ClientCertPEM); err != nil {
		return err
	}
	if !hex64.MatchString(in.ClientCertSHA) {
		return fmt.Errorf("%w: client_cert_sha256", ErrBadField)
	}
	if !hex64.MatchString(in.HostCertSHA) {
		return fmt.Errorf("%w: host_cert_sha256", ErrBadField)
	}
	if !hex64.MatchString(in.ClientNonce) {
		return fmt.Errorf("%w: client_nonce", ErrBadField)
	}
	return nil
}

func okResponse(req *Request, payload interface{}) (*Response, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	return &Response{
		V:       SchemaVersion,
		ID:      req.ID,
		OK:      true,
		Payload: body,
	}, nil
}

// ClassifyError maps an internal error to a stable wire
// code so the IPC peer never sees Go error strings.
func ClassifyError(err error) string {
	switch {
	case err == nil:
		return ""
	case errors.Is(err, ErrSchema):
		return "BAD_REQUEST"
	case errors.Is(err, ErrBadField):
		return "BAD_REQUEST"
	case errors.Is(err, ErrUnknownOp):
		return "UNKNOWN_OP"
	case errors.Is(err, ErrUnauthorized):
		return "UNAUTHORIZED"
	case errors.Is(err, ErrUnknownClient):
		return "UNKNOWN_CLIENT"
	case errors.Is(err, ErrLimitsExceeded):
		return "LIMIT_EXCEEDED"
	case errors.Is(err, ErrBadSignature):
		return "BAD_SIGNATURE"
	case errors.Is(err, ErrBadNonce):
		return "BAD_NONCE"
	case errors.Is(err, ErrGrantExpired):
		return "GRANT_EXPIRED"
	case errors.Is(err, ErrGrantRevoked):
		return "GRANT_REVOKED"
	case errors.Is(err, ErrGrantReplay):
		return "GRANT_REPLAY"
	case errors.Is(err, ErrGrantConflict):
		return "GRANT_CONFLICT"
	case errors.Is(err, ErrConcurrentRedemption):
		return "CONCURRENT_REDEMPTION"
	case errors.Is(err, ErrHostUnreachable):
		return "HOST_UNREACHABLE"
	case errors.Is(err, ErrHostDenied):
		return "HOST_DENIED"
	case errors.Is(err, ErrPersistFailed):
		return "PERSIST_FAILED"
	case errors.Is(err, ErrPipeClosed):
		return "PIPE_CLOSED"
	default:
		return "INTERNAL"
	}
}

// ErrorResponse builds an error envelope.
func ErrorResponse(req *Request, err error) *Response {
	return &Response{
		V:     SchemaVersion,
		ID:    req.ID,
		OK:    false,
		Error: ClassifyError(err),
	}
}

// randomHex returns N bytes hex-encoded via crypto/rand.
// On entropy failure it returns the error so callers can
// fail closed.
func randomHex(n int) (string, error) {
	if n <= 0 || n > 64 {
		return "", fmt.Errorf("bridge: randomHex bad size %d", n)
	}
	var b [64]byte
	if _, err := rand.Read(b[:n]); err != nil {
		return "", fmt.Errorf("bridge: rand.Read: %w", err)
	}
	return hex.EncodeToString(b[:n]), nil
}
