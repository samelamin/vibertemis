// Tests for the HMAC-SHA256 request signing used by the control
// channel. We cover the four required headers, the clock skew
// window, the constant-time signature compare, and the
// canonical-input exactness (METHOD is uppercased, the path is
// passed through verbatim).
package security

import (
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestSign_Deterministic(t *testing.T) {
	ts := time.Unix(1_700_000_000, 0)
	s1 := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", []byte(`{"role":"headset"}`))
	s2 := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", []byte(`{"role":"headset"}`))
	if s1 != s2 {
		t.Fatal("Sign must be deterministic")
	}
}

func TestVerify_AcceptsValid(t *testing.T) {
	ts := time.Now()
	body := []byte(`{"role":"headset","requested_codec":"AV1"}`)
	sig := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", body)
	headers := map[string]string{
		"X-Vq-Sig":   sig,
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "nonce-aaaaaaaaaa",
	}
	parsed, nonce, err := Verify("POST", "/start_pcvr", "token", headers, body, 30*time.Second, time.Now)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if parsed.Unix() != ts.Unix() {
		t.Fatalf("ts = %v", parsed)
	}
	if nonce != "nonce-aaaaaaaaaa" {
		t.Fatalf("nonce = %q", nonce)
	}
}

func TestVerify_RejectsBadSignature(t *testing.T) {
	ts := time.Now()
	body := []byte(`{}`)
	sig := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", body)
	headers := map[string]string{
		"X-Vq-Sig":   flipHexChar(sig),
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "nonce-aaaaaaaaaa",
	}
	_, _, err := Verify("POST", "/start_pcvr", "token", headers, body, 30*time.Second, time.Now)
	if err != ErrBadSignature {
		t.Fatalf("expected ErrBadSignature, got %v", err)
	}
}

func TestVerify_RejectsClockSkew(t *testing.T) {
	now := time.Now()
	body := []byte(`{}`)
	ts := now.Add(-1 * time.Minute)
	sig := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", body)
	headers := map[string]string{
		"X-Vq-Sig":   sig,
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "nonce-aaaaaaaaaa",
	}
	_, _, err := Verify("POST", "/start_pcvr", "token", headers, body, 30*time.Second, func() time.Time { return now })
	if err != ErrTimestampSkew {
		t.Fatalf("expected ErrTimestampSkew, got %v", err)
	}
}

func TestVerify_MethodCaseInsensitive(t *testing.T) {
	ts := time.Now()
	body := []byte(`{}`)
	sig := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", body)
	headers := map[string]string{
		"X-Vq-Sig":   sig,
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "nonce-aaaaaaaaaa",
	}
	if _, _, err := Verify("post", "/start_pcvr", "token", headers, body, 30*time.Second, time.Now); err != nil {
		t.Fatalf("method case should be normalised: %v", err)
	}
}

func TestVerify_PathIsVerbatim(t *testing.T) {
	ts := time.Now()
	body := []byte(`{}`)
	sig := Sign("POST", "/start_pcvr", "token", ts, "nonce-aaaaaaaaaa", body)
	headers := map[string]string{
		"X-Vq-Sig":   sig,
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "nonce-aaaaaaaaaa",
	}
	if _, _, err := Verify("POST", "/Start_PCVR", "token", headers, body, 30*time.Second, time.Now); err != ErrBadSignature {
		t.Fatalf("expected path to be verbatim, got %v", err)
	}
}

func TestVerify_RejectsShortNonce(t *testing.T) {
	ts := time.Now()
	body := []byte(`{}`)
	sig := Sign("POST", "/start_pcvr", "token", ts, "short", body)
	headers := map[string]string{
		"X-Vq-Sig":   sig,
		"X-Vq-Ts":    strconv.FormatInt(ts.Unix(), 10),
		"X-Vq-Nonce": "short",
	}
	_, _, err := Verify("POST", "/start_pcvr", "token", headers, body, 30*time.Second, time.Now)
	if err != ErrBadNonce {
		t.Fatalf("expected ErrBadNonce, got %v", err)
	}
}

func TestTokenEqual_ConstantTime(t *testing.T) {
	if !TokenEqual("abc", "abc") {
		t.Fatal("equal tokens must compare equal")
	}
	if TokenEqual("abc", "abd") {
		t.Fatal("different tokens must compare not equal")
	}
	if TokenEqual("abc", "abcd") {
		t.Fatal("different-length tokens must compare not equal")
	}
}

// flipHexChar flips the first character of a hex string. Used
// to corrupt a signature for the bad-signature test.
func flipHexChar(s string) string {
	if s == "" {
		return s
	}
	first := s[0]
	if first == '0' {
		return "1" + s[1:]
	}
	return "0" + s[1:]
}

// guard against a future Sign change that would silently break
// cross-language interop.
func TestSign_NoTrailingNewline(t *testing.T) {
	s := Sign("POST", "/start_pcvr", "t", time.Unix(1, 0), "nonce-aaaaaaaaaa", []byte("body"))
	if strings.Contains(s, "\n") {
		t.Fatal("hex signature must not contain newlines")
	}
}
