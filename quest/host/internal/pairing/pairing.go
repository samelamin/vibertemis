// Package pairing owns the persistent companion state: the
// server TLS certificate, the paired token, and the per-user
// state file.
//
// The state directory is per-user. On Windows we use
// %LOCALAPPDATA%\vibertemis\companion\ and apply a per-user DACL
// via x/sys/windows.SecurityDescriptorFromString. On POSIX we
// rely on the kernel 0700/0600 mode bits.
//
// The previous Codex P1 review noted: "Go's os.Chmod does NOT
// enforce DACLs on Windows" — the current implementation uses
// a real SDDL-based DACL with SetNamedSecurityInfo on Windows
// and falls through to chmod on POSIX. Any ACL failure is
// fail-closed: we refuse to load or save the state file.
package pairing

import (
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
	"math/big"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

// Errors.
var (
	ErrStateMissing      = errors.New("state file missing")
	ErrStateBadDACL      = errors.New("state file DACL weaker than owner-only")
	ErrStateBadPerm      = errors.New("state file permissions weaker than owner-only")
	ErrGenerationMissing = errors.New("state file missing generation")
)

// State is the on-disk schema.
type State struct {
	CertPEM    string `json:"cert_pem"`
	KeyPEM     string `json:"key_pem"`
	Token      string `json:"token"`
	CertPin    string `json:"certpin"`
	Generation int64  `json:"generation"`
}

// PairingExport is the user-initiated export. The private key is
// never included.
type PairingExport struct {
	HostAddress string `json:"host_address"`
	CertPEM     string `json:"cert_pem"`
	CertPin     string `json:"certpin"`
	Token       string `json:"token"`
	Generated   int64  `json:"generated_unix"`
}

// CertFingerprint returns the lowercase-hex SHA-256 of the cert
// DER (the standard client-side "pin").
func CertFingerprint(certPEM string) (string, error) {
	block, _ := pem.Decode([]byte(certPEM))
	if block == nil {
		return "", errors.New("pem decode: no block")
	}
	if block.Type != "CERTIFICATE" {
		return "", fmt.Errorf("pem decode: not a CERTIFICATE block (%q)", block.Type)
	}
	sum := sha256.Sum256(block.Bytes)
	return hex.EncodeToString(sum[:]), nil
}

// DefaultStateDir returns the per-user state directory.
func DefaultStateDir() (string, error) {
	if runtime.GOOS == "windows" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			base = os.Getenv("USERPROFILE")
			if base == "" {
				return "", errors.New("neither LOCALAPPDATA nor USERPROFILE set")
			}
			base = filepath.Join(base, "AppData", "Local")
		}
		return filepath.Join(base, "vibertemis", "companion"), nil
	}
	base := os.Getenv("XDG_DATA_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, ".local", "share")
	}
	return filepath.Join(base, "vibertemis", "companion"), nil
}

// EnsureDir creates the state directory if missing and applies
// the platform-private permission set. Always create the dir
// BEFORE any secrets are written.
func EnsureDir(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if err := protectDir(dir); err != nil {
		return fmt.Errorf("protect dir: %w", err)
	}
	return nil
}

// Load reads the state file. The loaded file MUST pass the
// platform-private ACL/permission check or Load returns
// ErrStateBadDACL / ErrStateBadPerm. The state must be
// internally consistent (token and pin parse, key matches
// cert, lengths sensible) or Load returns a descriptive error.
func Load(dir string) (*State, error) {
	path := filepath.Join(dir, "state.json")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrStateMissing
		}
		return nil, fmt.Errorf("read state: %w", err)
	}
	if err := verifyFileIsPrivate(path); err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, fmt.Errorf("parse state: %w", err)
	}
	if s.Generation == 0 {
		return nil, ErrGenerationMissing
	}
	if err := s.validate(); err != nil {
		return nil, err
	}
	return &s, nil
}

// validate is the on-disk consistency check. Failures are not
// recoverable by re-reading the file (a half-written JSON
// would not unmarshal in the first place); they indicate that
// the file was tampered with or corrupted, and the caller
// should refuse to use the state.
func (s *State) validate() error {
	if len(s.CertPEM) == 0 || len(s.KeyPEM) == 0 || len(s.Token) == 0 || len(s.CertPin) == 0 {
		return errors.New("pairing state: missing required field")
	}
	if len(s.Token) != 64 {
		return fmt.Errorf("pairing state: token length %d, want 64", len(s.Token))
	}
	if len(s.CertPin) != 64 {
		return fmt.Errorf("pairing state: certpin length %d, want 64", len(s.CertPin))
	}
	// Verify the cert+key pair actually matches.
	if _, err := tls.X509KeyPair([]byte(s.CertPEM), []byte(s.KeyPEM)); err != nil {
		return fmt.Errorf("pairing state: cert/key mismatch: %w", err)
	}
	// Cross-check the certpin against the cert PEM.
	pin, err := CertFingerprint(s.CertPEM)
	if err != nil {
		return fmt.Errorf("pairing state: cert fingerprint: %w", err)
	}
	if pin != s.CertPin {
		return errors.New("pairing state: certpin mismatch")
	}
	return nil
}

// Save writes the state file atomically with platform-private
// permissions on the final path. EnsureDir runs FIRST so the
// directory's protected DACL/chmod is in place before any
// secret-bearing file lands on disk; the file itself is written
// to a tmp sibling under the protected dir, given the same
// protection, and renamed into place.
func Save(dir string, s *State) error {
	if err := EnsureDir(dir); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	final := filepath.Join(dir, "state.json")
	tmp := final + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return fmt.Errorf("write tmp: %w", err)
	}
	if err := protectFile(tmp); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("protect tmp: %w", err)
	}
	if err := os.Rename(tmp, final); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename: %w", err)
	}
	if err := protectFile(final); err != nil {
		return fmt.Errorf("protect final: %w", err)
	}
	return nil
}

// Generate provisions a fresh self-signed ECDSA P-256 cert and
// 256-bit token.
func Generate() (*State, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("ecdsa keygen: %w", err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}
	tmpl := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "vibertemis-host-companion"},
		NotBefore:    time.Now().Add(-1 * time.Hour),
		NotAfter:     time.Now().Add(825 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:  []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
		DNSNames:     []string{"localhost"},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, fmt.Errorf("create cert: %w", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("marshal key: %w", err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	pin, err := CertFingerprint(string(certPEM))
	if err != nil {
		return nil, err
	}
	var tokenBuf [32]byte
	if _, err := rand.Read(tokenBuf[:]); err != nil {
		return nil, fmt.Errorf("token rand: %w", err)
	}
	return &State{
		CertPEM:    string(certPEM),
		KeyPEM:     string(keyPEM),
		Token:      hex.EncodeToString(tokenBuf[:]),
		CertPin:    pin,
		Generation: 1,
	}, nil
}

// Export assembles a user-initiated pairing export. The
// private key is omitted.
func (s *State) Export(hostAddress string) PairingExport {
	return PairingExport{
		HostAddress: hostAddress,
		CertPEM:     s.CertPEM,
		CertPin:     s.CertPin,
		Token:       s.Token,
		Generated:   time.Now().Unix(),
	}
}

// verifyFileIsPrivate refuses a file whose permissions have
// been relaxed.
func verifyFileIsPrivate(path string) error {
	return platformVerifyFilePrivate(path)
}

// ProtectFile applies the platform-private permission set to
// the file at path. Exposed for callers that materialize
// auxiliary secret-bearing files (e.g. the on-disk server
// cert+key pair).
func ProtectFile(path string) error { return protectFile(path) }

// ProtectDir applies the platform-private permission set to
// the directory at path.
func ProtectDir(path string) error { return protectDir(path) }

// SaveExport writes UTF-8 JSON directly, avoiding PowerShell's UTF-16 redirection.
// The export contains the shared token, so it stays under the private state directory.
func SaveExport(dir string, state *State, address string) (string, error) {
	if err := EnsureDir(dir); err != nil {
		return "", err
	}
	data, err := json.MarshalIndent(state.Export(address), "", "  ")
	if err != nil {
		return "", err
	}
	file, err := os.CreateTemp(dir, ".pairing-export-*")
	if err != nil {
		return "", err
	}
	temporary := file.Name()
	defer os.Remove(temporary)
	if err := protectFile(temporary); err != nil {
		file.Close()
		return "", err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		return "", err
	}
	if err := file.Close(); err != nil {
		return "", err
	}
	final := filepath.Join(dir, "pairing-export.json")
	if err := os.Rename(temporary, final); err != nil {
		return "", err
	}
	if err := verifyFileIsPrivate(final); err != nil {
		return "", err
	}
	return final, nil
}
