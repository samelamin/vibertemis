package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"sync"
	"time"
)

// One reader dispatches replies while a separate bounded worker handles host
// requests. All writers share a deadline-aware gate, including response writes.
type duplex struct {
	conn    net.Conn
	ctx     context.Context
	cancel  context.CancelFunc
	gate    chan struct{}
	mu      sync.Mutex
	pending map[string]chan Response
}

func (d *duplex) write(body []byte, deadline time.Time) error {
	timer := time.NewTimer(time.Until(deadline))
	defer timer.Stop()
	select {
	case d.gate <- struct{}{}:
	case <-timer.C:
		return ErrHostUnreachable
	case <-d.ctx.Done():
		return ErrPipeClosed
	}
	defer func() { <-d.gate }()
	if time.Now().After(deadline) {
		return ErrHostUnreachable
	}
	if err := d.conn.SetWriteDeadline(deadline); err != nil {
		return err
	}
	if err := EncodeFrame(d.conn, body); err != nil {
		d.cancel()
		d.conn.Close() // A partial frame makes this connection unusable.
		return err
	}
	return nil
}

func (d *duplex) Authorize(req AuthorizeRequest) (AuthorizeResponse, error) {
	deadline := time.Now().Add(AuthorizeTimeout)
	id, err := randomHex(16)
	if err != nil {
		return AuthorizeResponse{}, err
	}
	payload, err := json.Marshal(req)
	if err != nil {
		return AuthorizeResponse{}, err
	}
	body, err := json.Marshal(Request{V: 1, ID: id, Op: "authorize", Payload: payload})
	if err != nil {
		return AuthorizeResponse{}, err
	}
	reply := make(chan Response, 1)
	d.mu.Lock()
	if len(d.pending) >= pendingRequests {
		d.mu.Unlock()
		return AuthorizeResponse{}, ErrLimitsExceeded
	}
	d.pending[id] = reply
	d.mu.Unlock()
	defer func() { d.mu.Lock(); delete(d.pending, id); d.mu.Unlock() }()
	if err := d.write(body, deadline); err != nil {
		return AuthorizeResponse{}, err
	}
	timer := time.NewTimer(time.Until(deadline))
	defer timer.Stop()
	select {
	case r := <-reply:
		if !r.OK {
			return AuthorizeResponse{}, ErrHostDenied
		}
		var result AuthorizeResponse
		if err := json.Unmarshal(r.Payload, &result); err != nil {
			return result, err
		}
		return result, nil
	case <-d.ctx.Done():
		return AuthorizeResponse{}, ErrPipeClosed
	case <-timer.C:
		return AuthorizeResponse{}, ErrHostUnreachable
	}
}

func serveDuplex(ctx context.Context, conn net.Conn, svc *Service) error {
	ctx, cancel := context.WithCancel(ctx)
	d := &duplex{conn: conn, ctx: ctx, cancel: cancel, gate: make(chan struct{}, 1), pending: make(map[string]chan Response)}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer func() { cancel(); conn.Close(); stop() }()
	svc.SetAuthorizer(d)
	defer svc.clearAuthorizer(d)
	requests := make(chan Request, 32)
	workerDone := make(chan struct{})
	go func() {
		defer close(workerDone)
		for {
			select {
			case <-ctx.Done():
				return
			case req := <-requests:
				response, err := svc.HandleInbound(&req)
				if err != nil {
					response = ErrorResponse(&req, err)
				}
				body, err := json.Marshal(response)
				if err != nil || d.write(body, time.Now().Add(AuthorizeTimeout)) != nil {
					cancel()
					return
				}
			}
		}
	}()
	defer func() { cancel(); <-workerDone }()
	for {
		body, err := DecodeFrame(conn)
		if err != nil {
			return err
		}
		var envelope struct {
			V       int             `json:"v"`
			ID      string          `json:"id"`
			Op      string          `json:"op"`
			OK      *bool           `json:"ok"`
			Payload json.RawMessage `json:"payload"`
			Error   string          `json:"error"`
		}
		if err := json.Unmarshal(body, &envelope); err != nil {
			return err
		}
		if envelope.V != 1 || len(envelope.ID) == 0 || len(envelope.ID) > 128 {
			return ErrSchema
		}
		if envelope.Op != "" && envelope.OK == nil {
			req := Request{V: 1, ID: envelope.ID, Op: envelope.Op, Payload: envelope.Payload}
			select {
			case requests <- req:
			default:
				return ErrLimitsExceeded
			}
		} else if envelope.Op == "" && envelope.OK != nil {
			d.mu.Lock()
			ch := d.pending[envelope.ID]
			d.mu.Unlock()
			if ch != nil {
				select {
				case ch <- Response{V: 1, ID: envelope.ID, OK: *envelope.OK, Payload: envelope.Payload, Error: envelope.Error}:
				default:
				}
			}
		} else {
			return errors.New("ambiguous bridge envelope")
		}
	}
}

func (s *Service) clearAuthorizer(a Authorizer) {
	s.authorizerMu.Lock()
	defer s.authorizerMu.Unlock()
	if s.authorizer == a {
		s.authorizer = nil
	}
}
