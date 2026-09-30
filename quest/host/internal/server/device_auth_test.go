package server

import (
	"encoding/json"
	"errors"
	"github.com/vibertemis/quest-codec-control/host/internal/security"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type deviceAuthority struct {
	identity PairDeviceIdentity
	deny     bool
	calls    atomic.Int32
}

func (a *deviceAuthority) LookupDeviceIdentity(id string) (PairDeviceIdentity, bool) {
	return a.identity, id == a.identity.DeviceID
}
func (a *deviceAuthority) AuthorizeAndSelectHMAC(uuid, sha string) (string, string, string, error) {
	a.calls.Add(1)
	if a.deny || uuid != a.identity.DeviceID || sha != a.identity.PublicKeySHA {
		return "", "", "", errors.New("revoked")
	}
	return a.identity.DeviceID, a.identity.Token, a.identity.CompanionCertSHA, nil
}

func TestDeviceAuthenticationRequiresCurrentApprovalAndPreservesLegacy(t *testing.T) {
	for _, c := range []struct {
		name                            string
		headers                         []string
		legacy, deny, missing, accepted bool
		calls                           int32
	}{
		{name: "current paired device", headers: []string{strings.Repeat("a", 32)}, accepted: true, calls: 1},
		{name: "revoked device", headers: []string{strings.Repeat("a", 32)}, deny: true, calls: 1},
		{name: "unavailable pairing authority", headers: []string{strings.Repeat("a", 32)}, missing: true},
		{name: "unknown device", headers: []string{strings.Repeat("b", 32)}},
		{name: "empty header never falls back", headers: []string{""}, legacy: true},
		{name: "duplicate header never falls back", headers: []string{strings.Repeat("a", 32), strings.Repeat("a", 32)}},
		{name: "device header with legacy token", headers: []string{strings.Repeat("a", 32)}, legacy: true},
		{name: "legacy with no device header", legacy: true, missing: true, accepted: true},
	} {
		t.Run(c.name, func(t *testing.T) {
			ts := newTestServer(t)
			defer ts.close()
			authority := &deviceAuthority{identity: PairDeviceIdentity{DeviceID: strings.Repeat("a", 32), Token: strings.Repeat("b", 64), PublicKeySHA: strings.Repeat("c", 64)}, deny: c.deny}
			ts.core.deps.DeviceIdentityLookup = authority
			if !c.missing {
				ts.core.deps.FreshAuthorizer = authority
			}
			now := time.Now()
			nonce := mustNewNonce(t)
			token := authority.identity.Token
			if c.legacy {
				token = ts.token
			}
			req, _ := http.NewRequest("GET", ts.srv.URL+"/status", nil)
			req.Header.Set("X-Vq-Ts", strconv.FormatInt(now.Unix(), 10))
			req.Header.Set("X-Vq-Nonce", nonce)
			req.Header.Set("X-Vq-Sig", security.Sign("GET", "/status", token, now, nonce, nil))
			for _, header := range c.headers {
				req.Header.Add(DeviceHeader, header)
			}
			resp, err := ts.srv.Client().Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			var result map[string]any
			if err = json.NewDecoder(resp.Body).Decode(&result); err != nil {
				t.Fatal(err)
			}
			denied := result["error"] == string(ErrAuth)
			if denied == c.accepted {
				t.Fatalf("accepted=%v response=%v", c.accepted, result)
			}
			if got := authority.calls.Load(); got != c.calls {
				t.Fatalf("fresh checks=%d want %d", got, c.calls)
			}
			if atomic.LoadInt32(ts.launched) != 0 {
				t.Fatal("status launched SteamVR")
			}
		})
	}
}
