// pipe.go: cross-platform wire codec + the duplex-named-pipe
// runner. The wire format is the same on every OS:
//
//	uint32 length (LE) || JSON envelope (length bytes)
//
// The JSON envelope is one of two shapes:
//
//	Request :  {v:int, id:string, op:string, payload?:object}
//	Response:  {v:int, id:string, ok:bool, payload?:object, error?:string}
//
// Inbound frames are classified by sniffing the JSON: a
// Response has ok==true or error!=""; a Request has
// op!="". Outbound frames from the companion to Vibeshine
// are Requests (op="authorize"); inbound replies to those
// are Responses with matching id.
package bridge

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

// PipePrefix is the shared named-pipe prefix.
const PipePrefix = `\\.\pipe\VibertemisVRBridge-`

// PipeConfig is what the runner needs from the operator.
type PipeConfig struct {
	Name       string // full pipe name including \\.\pipe\ prefix
	Service    *Service
	Authorizer Authorizer // production: pipe-based; tests: fake
}

// PipeRunner is the abstraction used by the cmd entrypoint.
type PipeRunner interface {
	Run(ctx context.Context, cfg PipeConfig) error
}

// NewRunner returns the platform-appropriate runner.
func NewRunner() PipeRunner { return newPlatformRunner() }

// EncodeFrame writes one length-prefixed frame.
func EncodeFrame(w io.Writer, payload []byte) error {
	if len(payload) == 0 || len(payload) > MaxFrameBytes {
		return fmt.Errorf("bridge: frame too large (%d)", len(payload))
	}
	var lenBuf [4]byte
	binary.LittleEndian.PutUint32(lenBuf[:], uint32(len(payload)))
	frame := append(lenBuf[:], payload...)
	for len(frame) > 0 {
		n, err := w.Write(frame)
		if err != nil {
			return err
		}
		if n <= 0 || n > len(frame) {
			return io.ErrShortWrite
		}
		frame = frame[n:]
	}
	return nil
}

// DecodeFrame reads one length-prefixed frame. Returns
// ErrPipeClosed on a clean close.
func DecodeFrame(r io.Reader) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrClosedPipe) {
			return nil, ErrPipeClosed
		}
		return nil, err
	}
	n := binary.LittleEndian.Uint32(lenBuf[:])
	if n == 0 {
		return nil, fmt.Errorf("bridge: empty frame")
	}
	if n > MaxFrameBytes {
		return nil, fmt.Errorf("bridge: frame too large (%d)", n)
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrClosedPipe) {
			return nil, ErrPipeClosed
		}
		return nil, err
	}
	return body, nil
}

// FrameReader reads length-prefixed JSON envelopes.
type FrameReader struct {
	r *bufio.Reader
}

func NewFrameReader(r io.Reader) *FrameReader {
	return &FrameReader{r: bufio.NewReaderSize(r, MaxFrameBytes+16)}
}

func (f *FrameReader) ReadRaw() ([]byte, error) {
	return DecodeFrame(f.r)
}

func (f *FrameReader) Read() (*Request, error) {
	body, err := DecodeFrame(f.r)
	if err != nil {
		return nil, err
	}
	var req Request
	if err := json.Unmarshal(body, &req); err != nil {
		return nil, fmt.Errorf("%w: parse request: %v", ErrSchema, err)
	}
	return &req, nil
}

// FrameWriter serializes length-prefixed JSON envelopes.
type FrameWriter struct {
	w  io.Writer
	mu sync.Mutex
}

func NewFrameWriter(w io.Writer) *FrameWriter { return &FrameWriter{w: w} }

func (f *FrameWriter) WriteRaw(payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	return EncodeFrame(f.w, payload)
}

// wireSniff is the union of Request + Response shapes.
// Used by the inbound dispatch loop to route frames to the
// service (Request) or to a pending outbound waiter
// (Response).
type wireSniff struct {
	V       int             `json:"v"`
	ID      string          `json:"id"`
	Op      string          `json:"op"`
	OK      bool            `json:"ok"`
	Error   string          `json:"error"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// classifyFrame returns the parsed envelope plus a hint
// about which shape it matches. The reader dispatches on
// the hint.
type frameKind int

const (
	frameUnknown frameKind = iota
	frameRequest
	frameResponse
)

func classifyFrame(body []byte) (sniff wireSniff, kind frameKind, err error) {
	if err := json.Unmarshal(body, &sniff); err != nil {
		return sniff, frameUnknown, fmt.Errorf("%w: parse envelope: %v", ErrSchema, err)
	}
	if sniff.Op != "" {
		return sniff, frameRequest, nil
	}
	if sniff.OK || sniff.Error != "" {
		return sniff, frameResponse, nil
	}
	return sniff, frameUnknown, fmt.Errorf("%w: envelope missing op/ok", ErrSchema)
}

// decodeResponse builds a Response from a classified
// response envelope.
func decodeResponse(sniff wireSniff) *Response {
	return &Response{
		V:       sniff.V,
		ID:      sniff.ID,
		OK:      sniff.OK,
		Payload: sniff.Payload,
		Error:   sniff.Error,
	}
}

// inMemoryPipe is a test fixture: a duplex pipe backed by
// bytes.Buffer. Used by pipe_test.go.
type inMemoryPipe struct {
	r *bytes.Buffer
	w *bytes.Buffer
}

func newInMemoryPipe() *inMemoryPipe {
	return &inMemoryPipe{r: &bytes.Buffer{}, w: &bytes.Buffer{}}
}

func (p *inMemoryPipe) Read(b []byte) (int, error)  { return p.r.Read(b) }
func (p *inMemoryPipe) Write(b []byte) (int, error) { return p.w.Write(b) }
func (p *inMemoryPipe) Close() error                { return nil }

func (p *inMemoryPipe) consumeOutbound() []byte {
	out := p.w.Bytes()
	p.w.Reset()
	return out
}

func (p *inMemoryPipe) injectInbound(payload []byte) {
	p.r.Write(payload)
}
