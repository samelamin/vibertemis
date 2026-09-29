package server

import (
	"bytes"
	"encoding/json"
	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"os"
	"sync/atomic"
	"testing"
)

func TestNativeCodecNegotiationNeverWritesSavedPreference(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	original, _ := os.ReadFile(ts.session)
	var doc map[string]interface{}
	if err := json.Unmarshal(original, &doc); err != nil {
		t.Fatal(err)
	}
	doc["server_version"] = alvr.NativeVersion
	original, _ = json.Marshal(doc)
	if err := os.WriteFile(ts.session, original, 0600); err != nil {
		t.Fatal(err)
	}
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"PyroWave","native_protocol":"20.14.1-vibertemis-pyro.1","request_id":"native-cold"}`)
	_, _, response := ts.doSigned(t, body)
	if response.State != StateStarted || response.NegotiatedCodec != "" || response.AppliedCodec != "" {
		t.Fatalf("cold response: %+v", response)
	}
	body = bytes.ReplaceAll(body, []byte("native-cold"), []byte("native-warm"))
	_, _, response = ts.doSigned(t, body)
	if response.State != StateAlreadyRunning || response.AppliedCodec != "" {
		t.Fatalf("warm response: %+v", response)
	}
	if atomic.LoadInt32(ts.launched) != 1 {
		t.Fatal("warm connection launched SteamVR again")
	}
	after, _ := os.ReadFile(ts.session)
	if !bytes.Equal(original, after) {
		t.Fatal("native session was modified by companion")
	}
}

func TestCustomClientCannotStartStockHost(t *testing.T) {
	ts := newTestServer(t)
	defer ts.close()
	body := []byte(`{"role":"headset","mode":"pcvr","requested_codec":"auto","native_protocol":"20.14.1-vibertemis-pyro.1","request_id":"wrong-host"}`)
	_, _, response := ts.doSigned(t, body)
	if response.Error != ErrAlvrVersion || atomic.LoadInt32(ts.launched) != 0 {
		t.Fatalf("wrong protocol launched: %+v", response)
	}
}
