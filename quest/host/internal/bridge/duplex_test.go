package bridge

import (
	"context"
	"encoding/json"
	"net"
	"sync"
	"testing"
	"time"
)

func TestDuplexAuthorizationAndInboundGrant(t *testing.T) {
	cert, _ := testCompanionPEM(t)
	svc := New(cert, 28540)
	server, client := net.Pipe()
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- serveDuplex(ctx, server, svc) }()
	deadline := time.Now().Add(time.Second)
	for svc.CurrentAuthorizer() == nil && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	auth := svc.CurrentAuthorizer()
	if auth == nil {
		t.Fatal("authorizer not wired")
	}
	result := make(chan error, 1)
	go func() {
		r, e := auth.Authorize(AuthorizeRequest{ClientUUID: "00000000-0000-0000-0000-000000000001", ClientCertSHA: randHex(t, 32)})
		if e == nil && !r.Authorized {
			e = ErrHostDenied
		}
		result <- e
	}()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	body, e := DecodeFrame(client)
	if e != nil {
		t.Fatal(e)
	}
	var req Request
	if e = json.Unmarshal(body, &req); e != nil {
		t.Fatal(e)
	}
	if req.Op != "authorize" {
		t.Fatal(req.Op)
	}
	// An unrelated inbound request must not hide the outstanding response.
	body, _ = json.Marshal(Request{V: 1, ID: "bad-op", Op: "not-supported"})
	if e = EncodeFrame(client, body); e != nil {
		t.Fatal(e)
	}
	body, _ = json.Marshal(Response{V: 1, ID: req.ID, OK: true, Payload: json.RawMessage(`{"authorized":true}`)})
	if e = EncodeFrame(client, body); e != nil {
		t.Fatal(e)
	}
	if e = <-result; e != nil {
		t.Fatal(e)
	}
	body, e = DecodeFrame(client)
	if e != nil {
		t.Fatal(e)
	}
	var reply Response
	if e = json.Unmarshal(body, &reply); e != nil {
		t.Fatal(e)
	}
	if reply.ID != "bad-op" || reply.OK {
		t.Fatal("bad request not rejected")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("disconnect hung")
	}
	if svc.CurrentAuthorizer() != nil {
		t.Fatal("disconnected authorizer retained")
	}
}

func TestDuplexWriteDeadlineIncludesWriterQueue(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := &duplex{conn: a, ctx: ctx, cancel: cancel, gate: make(chan struct{}, 1)}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); _ = d.write([]byte(`{}`), time.Now().Add(150*time.Millisecond)) }()
	time.Sleep(10 * time.Millisecond)
	start := time.Now()
	if e := d.write([]byte(`{}`), start.Add(30*time.Millisecond)); e == nil {
		t.Fatal("stalled queue succeeded")
	}
	if time.Since(start) > 120*time.Millisecond {
		t.Fatal("queue ignored deadline")
	}
	wg.Wait()
}
