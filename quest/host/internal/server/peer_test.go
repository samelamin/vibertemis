package server

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"github.com/vibertemis/quest-codec-control/host/internal/security"
)

func TestOnlyAuthenticatedHeadsetCanRegisterNativePeer(t *testing.T) {
	for _, scenario := range []string{"valid", "wrong token", "phone", "public", "public spoof header"} {
		t.Run(scenario, func(t *testing.T) {
			ts := newTestServer(t)
			defer ts.srv.Close()
			data, _ := os.ReadFile(ts.session)
			var doc map[string]interface{}
			json.Unmarshal(data, &doc)
			doc["server_version"] = alvr.NativeVersion
			data, _ = json.Marshal(doc)
			if err := os.WriteFile(ts.session, data, 0600); err != nil {
				t.Fatal(err)
			}
			role, token, remote := "headset", ts.token, "192.168.1.50:3456"
			switch scenario {
			case "wrong token":
				token = "other"
			case "phone":
				role = "phone"
			case "public", "public spoof header":
				remote = "8.8.8.8:3456"
			}
			body, _ := json.Marshal(StartPcvrRequest{Role: role, Mode: "pcvr", RequestId: "peer-registration", RequestedCodec: "Auto", NativeProtocol: alvr.NativeVersion, ClientHostname: "quest.client"})
			req := httptest.NewRequest("POST", "/start_pcvr", bytes.NewReader(body))
			req.RemoteAddr = remote
			now := time.Now()
			nonce := mustNewNonce(t)
			req.Header.Set("X-Vq-Sig", security.Sign("POST", "/start_pcvr", token, now, nonce, body))
			req.Header.Set("X-Vq-Ts", strconv.FormatInt(now.Unix(), 10))
			req.Header.Set("X-Vq-Nonce", nonce)
			req.Header.Set("X-Forwarded-For", "192.168.1.50")
			response := httptest.NewRecorder()
			ts.srv.Config.Handler.ServeHTTP(response, req)
			after, _ := os.ReadFile(ts.session)
			if scenario != "valid" {
				if !bytes.Equal(data, after) || *ts.launched != 0 {
					t.Fatalf("unauthorized registration/launch: %s", response.Body.String())
				}
				return
			}
			var result StartPcvrResponse
			json.Unmarshal(response.Body.Bytes(), &result)
			if result.State != StateStarted || *ts.launched != 1 {
				t.Fatalf("did not start: %s", response.Body.String())
			}
			json.Unmarshal(after, &doc)
			peer := doc["client_connections"].(map[string]interface{})["quest.client"].(map[string]interface{})
			if peer["trusted"] != true {
				t.Fatal("headset was not registered")
			}
		})
	}
}
