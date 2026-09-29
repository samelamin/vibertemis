// Package security implements the HMAC-SHA256 request signing
// used over the HTTPS control channel. The signing scheme is:
//
//	method || "\n" || path || "\n" || timestamp || "\n" ||
//	  nonce || "\n" || sha256-hex(body)
//
// All five inputs are bytes from the wire. METHOD is uppercased
// before signing.
package security

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Errors.
var (
	ErrMissingHeader = errors.New("missing required header")
	ErrBadTimestamp  = errors.New("bad timestamp header")
	ErrTimestampSkew = errors.New("timestamp outside allowed skew")
	ErrBadNonce      = errors.New("bad nonce")
	ErrBadSignature  = errors.New("bad signature")
)

// NewNonce returns a 32-character hex nonce.
func NewNonce() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("rand: %w", err)
	}
	return hex.EncodeToString(b[:]), nil
}

// Sign returns the canonical signature for the request.
func Sign(method, path, token string, ts time.Time, nonce string, body []byte) string {
	sum := sha256.Sum256(body)
	mac := hmac.New(sha256.New, []byte(token))
	canonical := strings.ToUpper(method) + "\n" +
		path + "\n" +
		strconv.FormatInt(ts.Unix(), 10) + "\n" +
		nonce + "\n" +
		hex.EncodeToString(sum[:])
	mac.Write([]byte(canonical))
	return hex.EncodeToString(mac.Sum(nil))
}

// Verify parses the four required headers, checks the clock
// skew, and recomputes the signature. On success it returns
// the parsed timestamp; the caller is responsible for the
// replay / nonce check via state.NonceLRU.
func Verify(method, path, token string, headers map[string]string,
	body []byte, maxSkew time.Duration, now func() time.Time) (time.Time, string, error) {
	if now == nil {
		now = time.Now
	}
	sig, ok := headers["X-Vq-Sig"]
	if !ok || sig == "" {
		return time.Time{}, "", ErrMissingHeader
	}
	tsStr, ok := headers["X-Vq-Ts"]
	if !ok || tsStr == "" {
		return time.Time{}, "", ErrMissingHeader
	}
	nonce, ok := headers["X-Vq-Nonce"]
	if !ok || nonce == "" {
		return time.Time{}, "", ErrMissingHeader
	}
	tsSec, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return time.Time{}, "", ErrBadTimestamp
	}
	ts := time.Unix(tsSec, 0)
	delta := now().Sub(ts)
	if delta < 0 {
		delta = -delta
	}
	if delta > maxSkew {
		return time.Time{}, "", ErrTimestampSkew
	}
	if len(nonce) < 16 || len(nonce) > 128 {
		return time.Time{}, "", ErrBadNonce
	}
	expected := Sign(method, path, token, ts, nonce, body)
	gotBytes, err := hex.DecodeString(sig)
	if err != nil {
		return time.Time{}, "", ErrBadSignature
	}
	expBytes, err := hex.DecodeString(expected)
	if err != nil {
		return time.Time{}, "", ErrBadSignature
	}
	if !hmac.Equal(gotBytes, expBytes) {
		return time.Time{}, "", ErrBadSignature
	}
	return ts, nonce, nil
}

// TokenEqual compares two tokens in constant time.
func TokenEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}
