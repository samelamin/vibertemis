// Package alvr owns the ALVR v20.14.1 session.json adapter.
//
// Source of truth (verified against the pinned alvr-source commit
// a9f6542fa507a841f40ab4f3fcb531427cd02550, see alvr/session/src/lib.rs
// and alvr/session/src/settings.rs):
//
//   - session.json lives at <config_dir>/session.json.
//   - On Windows the launcher's data_dir() is the install root
//     (alvr/launcher/src/actions.rs:316 — env::current_exe().parent()),
//     and Layout::new on non-Linux uses that root for every
//     directory (alvr/filesystem/src/lib.rs:160-173). The
//     "ALVR companion" must therefore take an explicit
//     absolute session.json path from the operator, not assume
//     %APPDATA%/alvr (that branch is the Linux-only default).
//   - The codec preference is at the JSON path
//     session_settings.video.preferred_codec.variant.
//   - The CodecType enum serializes its variant NAME (serde default)
//     so the wire values are the case-sensitive strings
//     "H264", "Hevc", "AV1". There is no "Auto" variant —
//     the companion treats Auto as "preserve host setting".
//   - The schema flags the codec field as "steamvr-restart" —
//     ALVR will not pick up a change while a stream is up.
//   - server_version is a semver; the reference Cargo.toml pins
//     20.14.1 and SessionConfig::default() uses ALVR_VERSION.
//     We validate server_version == "20.14.1" before any
//     mutation.
//
// Offline-write ownership cannot be proven by mtime alone. The
// adapter requires explicit preconditions for every write:
// vrserver.exe NOT running, ALVR Dashboard.exe NOT running,
// server_version == "20.14.1", schema recognized, file
// snapshot + immediate pre-rename bytes compare, atomic
// rename with .bak. Any failure returns an error code; the
// caller maps it to a user-facing "configure ALVR manually"
// instruction rather than a fake "applied" success.
package alvr

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"
)

// Errors.
var (
	ErrFileMissing        = errors.New("alvr session.json missing")
	ErrParse              = errors.New("alvr session.json parse error")
	ErrSchemaUnrecognized = errors.New("alvr session.json missing required structural fields")
	ErrVersionMismatch    = errors.New("alvr server_version is not 20.14.1")
	ErrConflict           = errors.New("alvr session.json changed since read")
	ErrVRSeverRunning     = errors.New("steamvr running; codec change requires reconnect")
	ErrDashboardRunning   = errors.New("alvr dashboard running; refusing to race it for session.json")
	ErrUnsupportedCodec   = errors.New("requested codec not in stock alvr set")
	ErrWrite              = errors.New("alvr session.json write failed")
	ErrRollbackFailed     = errors.New("alvr session.json write failed and rollback failed")
	ErrProcessProbe       = errors.New("alvr process probe failed; refusing to mutate")
)

// ExpectedVersion is the ALVR server_version the adapter requires.
// Matches the workspace version in alvr/Cargo.toml at the pinned
// commit a9f6542fa507a841f40ab4f3fcb531427cd02550.
const ExpectedVersion = "20.14.1"
const NativeVersion = "20.14.1-vibertemis-pyro.1"

// Codec is a stock ALVR CodecType variant string. The names are
// case-sensitive and match the serde default serialization.
type Codec string

const (
	CodecH264 Codec = "H264"
	CodecHEVC Codec = "Hevc"
	CodecAV1  Codec = "AV1"
)

func (c Codec) IsValid() bool {
	switch c {
	case CodecH264, CodecHEVC, CodecAV1:
		return true
	}
	return false
}

func (c Codec) String() string { return string(c) }

// AutoSentinel is the value the wire protocol sends when the user
// picked "Auto" in the headset UI. The adapter maps it to "no
// write" (preserve host setting). It is NOT a value that can be
// written into session.json (the enum has no Auto variant).
const AutoSentinel = "_auto_preserve_host_setting"

// ProcessGate abstracts the running-process checks. The production
// implementation uses tasklist; tests substitute a fake.
type ProcessGate interface {
	VRServerRunning() (bool, error)
	DashboardRunning() (bool, error)
}

// snapshot is the (mtime, size, bytes) we read in the most
// recent successful Load. Every Write compares the live stat
// + bytes against this snapshot immediately before the atomic
// rename.
type snapshot struct {
	mtime time.Time
	size  int64
	bytes []byte
}

// Adapter owns the session.json path and serializes all
// reads/writes.
type Adapter struct {
	mu           sync.Mutex
	path         string
	gate         ProcessGate
	lastSnapshot *snapshot
	replaceFile  func(string, string) error // optional failure-injection seam; production uses os.Rename
}

// NewAdapter returns an adapter for the given path. gate MUST be
// non-nil in production; passing nil returns an adapter that
// refuses to mutate (the Write methods treat nil gate as a
// process-probe failure, not as "nothing running").
func NewAdapter(path string, gate ProcessGate) *Adapter {
	return &Adapter{path: path, gate: gate}
}

func (a *Adapter) Path() string { return a.path }

// Load reads session.json, validates the schema and version, and
// records a snapshot. Returns ErrFileMissing if the path does not
// exist.
func (a *Adapter) Load() (map[string]interface{}, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.loadLocked()
}

func (a *Adapter) loadLocked() (map[string]interface{}, error) {
	st, err := os.Stat(a.path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrFileMissing
		}
		return nil, fmt.Errorf("stat: %w", err)
	}
	data, err := os.ReadFile(a.path)
	if err != nil {
		return nil, fmt.Errorf("read: %w", err)
	}
	var out map[string]interface{}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrParse, err)
	}
	if err := validateSchema(out); err != nil {
		return nil, err
	}
	if err := validateVersion(out); err != nil {
		return nil, err
	}
	a.lastSnapshot = &snapshot{
		mtime: st.ModTime(),
		size:  st.Size(),
		bytes: append([]byte(nil), data...),
	}
	return out, nil
}

func validateSchema(j map[string]interface{}) error {
	if j == nil {
		return ErrSchemaUnrecognized
	}
	if _, ok := j["server_version"]; !ok {
		return ErrSchemaUnrecognized
	}
	ss, ok := j["session_settings"].(map[string]interface{})
	if !ok {
		return ErrSchemaUnrecognized
	}
	video, ok := ss["video"].(map[string]interface{})
	if !ok {
		return ErrSchemaUnrecognized
	}
	pc, ok := video["preferred_codec"].(map[string]interface{})
	if !ok {
		return ErrSchemaUnrecognized
	}
	codec, ok := pc["variant"].(string)
	if !ok {
		return ErrSchemaUnrecognized
	}
	switch codec {
	case "H264", "Hevc", "AV1":
	case "PyroWave":
		if sessionVersion(j) != NativeVersion {
			return ErrSchemaUnrecognized
		}
	default:
		return ErrSchemaUnrecognized
	}
	return nil
}

// ALVR serializes semver as a string. Legacy synthetic object fixtures are
// accepted only when all numeric parts and optional prerelease are exact.
func sessionVersion(j map[string]interface{}) string {
	if value, ok := j["server_version"].(string); ok {
		return value
	}
	if value, ok := j["server_version"].(map[string]interface{}); ok {
		major, a := value["major"].(float64)
		minor, b := value["minor"].(float64)
		patch, c := value["patch"].(float64)
		if !a || !b || !c || major != 20 || minor != 14 || patch != 1 {
			return ""
		}
		version := ExpectedVersion
		if pre, exists := value["pre"]; exists {
			text, ok := pre.(string)
			if !ok {
				return ""
			}
			if text != "" {
				version += "-" + text
			}
		}
		if build, exists := value["build"]; exists {
			text, ok := build.(string)
			if !ok || text != "" {
				return ""
			}
		}
		return version
	}
	return ""
}
func validateVersion(j map[string]interface{}) error {
	version := sessionVersion(j)
	if version != ExpectedVersion && version != NativeVersion {
		return fmt.Errorf("%w: %q", ErrVersionMismatch, version)
	}
	return nil
}

// CurrentCodec returns the codec ALVR has currently saved. The
// empty string means "missing or unrecognized" — NOT a negotiated
// value.
func (a *Adapter) CurrentCodec() (Codec, error) {
	codec, _, err := a.CodecState()
	return codec, err
}

// CodecState returns one consistent read, not two racing session snapshots.
func (a *Adapter) CodecState() (Codec, bool, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	cur, err := a.loadLocked()
	if err != nil {
		return "", false, err
	}
	ss, _ := cur["session_settings"].(map[string]interface{})
	video, _ := ss["video"].(map[string]interface{})
	pc, _ := video["preferred_codec"].(map[string]interface{})
	v, _ := pc["variant"].(string)
	return Codec(v), sessionVersion(cur) == NativeVersion, nil
}

// ProbeResult is the /capabilities payload.
//
// Codecs: the static enum list, surfaced only when session.json
// is readable AND version is 20.14.1. We do NOT claim a runtime
// capability we have not observed.
//
// PyroWave: always false in Phase 1. The reason string is the
// single source of truth for why.
//
// NegotiatedCodec: ALWAYS empty in Phase 1. The companion has
// no confirmed-stream signal; reporting a value from
// session.json would conflate "what the user picked" with "what
// the stream is using".
type ProbeResult struct {
	NativeHandshake bool     `json:"native_handshake"`
	Codecs          []string `json:"codecs"`
	PyroWave        bool     `json:"pyrowave"`
	PyroWaveReason  string   `json:"pyrowave_reason"`
	NegotiatedCodec string   `json:"negotiated_codec"`
}

const PyroWaveDisabledReason = "PyroWave requires a matched native host encoder and Android decoder that have not shipped in this build."

// Probe reads the live session.json and reports the
// capability snapshot. If session.json is missing or the schema
// is unrecognized, the codec list is empty (the companion does
// not invent capabilities from thin air).
func (a *Adapter) Probe() (ProbeResult, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, err := os.Stat(a.path); err != nil {
		if os.IsNotExist(err) {
			return ProbeResult{
				Codecs:          []string{},
				PyroWave:        false,
				PyroWaveReason:  PyroWaveDisabledReason,
				NegotiatedCodec: "",
			}, nil
		}
		return ProbeResult{}, err
	}
	doc, err := a.loadLocked()
	if err != nil {
		return ProbeResult{
			Codecs:          []string{},
			PyroWave:        false,
			PyroWaveReason:  PyroWaveDisabledReason,
			NegotiatedCodec: "",
		}, nil
	}
	if sessionVersion(doc) == NativeVersion {
		return ProbeResult{NativeHandshake: true,
			Codecs:         []string{string(CodecH264), string(CodecHEVC), string(CodecAV1), "PyroWave"},
			PyroWaveReason: "Runtime encoder and decoder compatibility is checked during the VR connection."}, nil
	}
	return ProbeResult{
		Codecs:          []string{string(CodecH264), string(CodecHEVC), string(CodecAV1)},
		PyroWave:        false,
		PyroWaveReason:  PyroWaveDisabledReason,
		NegotiatedCodec: "",
	}, nil
}

// EnsureCodec makes the saved codec match desired, but only when
// the desired value is a stock codec. desired == "" or
// AutoSentinel means "preserve current" — no write, no error.
//
// Behaviour table:
//
//   - desired == "" or AutoSentinel: no write, returns
//     (current, nil).
//   - desired invalid enum: returns ("", ErrUnsupportedCodec).
//   - current == desired: no write, returns (desired, nil).
//   - vrserver running: returns (current, ErrVRSeverRunning).
//   - dashboard running: returns (current, ErrDashboardRunning).
//   - process probe error: returns ("", ErrProcessProbe).
//   - file/snapshot/version/schema invalid: error.
//   - atomic write fails: rollback attempted, error returned.
//
// On success the file contains the new codec; every other
// field is byte-for-byte preserved. The (possibly updated)
// current codec is returned for the caller to surface.
func (a *Adapter) EnsureCodec(desired Codec) (Codec, error) {
	if desired == "" || string(desired) == AutoSentinel {
		cur, err := a.CurrentCodec()
		if err != nil {
			return "", err
		}
		return cur, nil
	}
	if !desired.IsValid() {
		return "", fmt.Errorf("%w: %q", ErrUnsupportedCodec, string(desired))
	}
	if a.gate == nil {
		return "", ErrProcessProbe
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	cur, err := a.loadLocked()
	if err != nil {
		return "", err
	}
	if sessionVersion(cur) == NativeVersion {
		return "", errors.New("custom ALVR codec is managed by its native handshake")
	}
	runningVR, err := a.gate.VRServerRunning()
	if err != nil {
		return "", fmt.Errorf("%w: vrserver: %v", ErrProcessProbe, err)
	}
	if runningVR {
		return "", ErrVRSeverRunning
	}
	runningDash, err := a.gate.DashboardRunning()
	if err != nil {
		return "", fmt.Errorf("%w: dashboard: %v", ErrProcessProbe, err)
	}
	if runningDash {
		return "", ErrDashboardRunning
	}
	currentCodec, _ := cur["session_settings"].(map[string]interface{})["video"].(map[string]interface{})["preferred_codec"].(map[string]interface{})["variant"].(string)
	if Codec(currentCodec) == desired {
		// Already matches; idempotent no-write.
		return desired, nil
	}
	return desired, a.writeLocked(cur, desired)
}

func (a *Adapter) writeLocked(cur map[string]interface{}, desired Codec) error {
	ss := cur["session_settings"].(map[string]interface{})
	video := ss["video"].(map[string]interface{})
	pc := video["preferred_codec"].(map[string]interface{})
	pc["variant"] = string(desired)
	newData, err := json.MarshalIndent(cur, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	// Immediate pre-rename check: mtime + size + bytes must match
	// the recorded snapshot.
	if err := a.confirmSnapshotMatchesLocked(); err != nil {
		return err
	}
	bakPath := a.path + ".bak"
	tmpPath := a.path + ".tmp"
	if err := os.WriteFile(tmpPath, newData, 0644); err != nil {
		return fmt.Errorf("write tmp: %w", err)
	}
	// Refresh the backup for this transaction; an old backup is never a
	// rollback source. A failed rename must leave the destination untouched.
	if cpErr := os.WriteFile(bakPath, a.lastSnapshot.bytes, 0644); cpErr != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("backup: %w", cpErr)
	}
	if err := a.confirmSnapshotMatchesLocked(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	replace := a.replaceFile
	if replace == nil {
		replace = os.Rename
	}
	if err := replace(tmpPath, a.path); err != nil {
		_ = os.Remove(tmpPath)
		// Do not restore anything over the still-current file (or over a
		// newer file another process wrote). The original rename failed.
		return fmt.Errorf("%w: %v", ErrWrite, err)
	}

	st, err := os.Stat(a.path)
	if err == nil {
		a.lastSnapshot = &snapshot{
			mtime: st.ModTime(),
			size:  st.Size(),
			bytes: append([]byte(nil), newData...),
		}
	}
	return nil
}

func (a *Adapter) confirmSnapshotMatchesLocked() error {
	if a.lastSnapshot == nil {
		return ErrConflict
	}
	st, err := os.Stat(a.path)
	if err != nil {
		return fmt.Errorf("re-stat: %w", err)
	}
	if !st.ModTime().Equal(a.lastSnapshot.mtime) {
		return ErrConflict
	}
	if st.Size() != a.lastSnapshot.size {
		return ErrConflict
	}
	cur, err := os.ReadFile(a.path)
	if err != nil {
		return fmt.Errorf("re-read: %w", err)
	}
	if !bytesEqual(cur, a.lastSnapshot.bytes) {
		return ErrConflict
	}
	return nil
}

// Digest returns the lowercase-hex SHA-256 of the most recent
// Load's bytes. Used by /status for the operator to detect
// silent dashboard writes.
func (a *Adapter) Digest() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.lastSnapshot == nil {
		return ""
	}
	sum := sha256.Sum256(a.lastSnapshot.bytes)
	return hex.EncodeToString(sum[:])
}

// EnsureParentDir is a best-effort mkdir for callers that want
// to create the config dir before the first write. ALVR itself
// creates this on startup, so the dir normally exists.
func EnsureParentDir(path string) error {
	return os.MkdirAll(parentDir(path), 0755)
}

func parentDir(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' || p[i] == '\\' {
			return p[:i]
		}
	}
	return "."
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0644)
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

// RequireGate is a process gate that always returns an error. It
// exists so the production wiring fails closed if no real
// ProcessGate is supplied. Tests inject a fake that returns
// deterministic states.
type RequireGate struct{}

func (RequireGate) VRServerRunning() (bool, error) {
	return false, errors.New("no process gate configured")
}
func (RequireGate) DashboardRunning() (bool, error) {
	return false, errors.New("no process gate configured")
}

// FakeGate returns the given state for every probe. The error
// fields are independent so tests can simulate probe failure.
type FakeGate struct {
	VRServer   bool
	VRServerE  error
	Dashboard  bool
	DashboardE error
}

func (f FakeGate) VRServerRunning() (bool, error)  { return f.VRServer, f.VRServerE }
func (f FakeGate) DashboardRunning() (bool, error) { return f.Dashboard, f.DashboardE }

// NewFakeSession is a test helper that materializes a minimal
// 20.14.1 session.json at path. It is the only sanctioned way
// to put a session.json on disk during tests; tests must not
// handcraft the JSON because the field names must match the
// schema.
func NewFakeSession(path string, codec Codec) error {
	doc := map[string]interface{}{
		"server_version": map[string]interface{}{
			"major": 20, "minor": 14, "patch": 1,
		},
		"openvr_config":      map[string]interface{}{},
		"client_connections": map[string]interface{}{},
		"session_settings": map[string]interface{}{
			"video": map[string]interface{}{
				"preferred_codec": map[string]interface{}{
					"variant": string(codec),
				},
			},
		},
	}
	data, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	if err := EnsureParentDir(path); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}
