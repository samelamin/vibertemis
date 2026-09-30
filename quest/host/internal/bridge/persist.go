// persist.go: atomic write of the per-device credentials
// file. The on-disk file is named devices.json and lives
// in the same per-user state directory as the legacy
// pairing state file. The legacy state.json is untouched;
// only devices.json is new.
//
// On Windows the directory inherits the same private DACL
// we already apply via pairing.ProtectDir. On POSIX the
// mode 0700/0600 enforcement from pairing.ProtectFile
// applies.
//
// The temp file name is unique per call (mkstemp-like) so
// concurrent saveers cannot stomp each other. The temp
// file is fsynced before rename and the parent directory
// is fsynced on platforms that support it so a power loss
// after the rename still leaves the new contents visible.
// The existing valid store is preserved on any failure.
package bridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"

	"github.com/vibertemis/quest-codec-control/host/internal/pairing"
)

// FilePersist writes devices.json atomically.
type FilePersist struct {
	Dir string
}

// MaxDevicesFileSize caps the on-disk file we are willing
// to load.
const MaxDevicesFileSize = 256 * 1024

// devicesWrapper is the on-disk envelope.
type devicesWrapper struct {
	Schema  int               `json:"schema"`
	Devices []PersistedDevice `json:"devices"`
}

// SaveDevices marshals records to disk atomically. Errors
// are surfaced; the caller MUST fail closed.
func (p *FilePersist) SaveDevices(records []PersistedDevice) error {
	if p.Dir == "" {
		return errors.New("bridge: empty state dir")
	}
	if err := pairing.EnsureDir(p.Dir); err != nil {
		return fmt.Errorf("bridge: ensure state dir: %w", err)
	}
	body, err := json.MarshalIndent(devicesWrapper{
		Schema: SchemaVersion, Devices: records,
	}, "", "  ")
	if err != nil {
		return fmt.Errorf("bridge: marshal devices: %w", err)
	}
	if len(records) > deviceLimit || len(body) > MaxDevicesFileSize {
		return ErrLimitsExceeded
	}
	final := filepath.Join(p.Dir, "devices.json")
	// mkstemp-like unique temp filename. We use CreateTemp
	// so the temp file is created with 0600 mode on POSIX
	// and is guaranteed unique.
	tmp, err := os.CreateTemp(p.Dir, "devices.json.*.tmp")
	if err != nil {
		return fmt.Errorf("bridge: create tmp: %w", err)
	}
	tmpName := tmp.Name()
	defer func() {
		// Best-effort cleanup if we abandoned the temp.
		_ = os.Remove(tmpName)
	}()
	if _, err := tmp.Write(body); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("bridge: write tmp: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("bridge: fsync tmp: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("bridge: close tmp: %w", err)
	}
	if err := pairing.ProtectFile(tmpName); err != nil {
		return fmt.Errorf("bridge: protect tmp: %w", err)
	}
	if err := replaceFile(tmpName, final); err != nil {
		return fmt.Errorf("bridge: rename: %w", err)
	}

	return nil
}

// LoadDevicesFromFile reads devices.json. Returns nil (no
// error) when the file is absent.
func LoadDevicesFromFile(dir string) ([]PersistedDevice, error) {
	if dir == "" {
		return nil, nil
	}
	path := filepath.Join(dir, "devices.json")
	info, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("bridge: stat devices: %w", err)
	}
	if info.Size() > MaxDevicesFileSize {
		return nil, fmt.Errorf("bridge: devices.json too large (%d)", info.Size())
	}
	if err := pairing.ProtectFile(path); err != nil {
		// Privilege drift: refuse to load.
		return nil, fmt.Errorf("bridge: protect devices: %w", err)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, MaxDevicesFileSize+1))
	if len(data) > MaxDevicesFileSize {
		return nil, ErrLimitsExceeded
	}
	if err != nil {
		return nil, fmt.Errorf("bridge: read devices: %w", err)
	}
	var wrap devicesWrapper
	if err := json.Unmarshal(data, &wrap); err != nil {
		return nil, fmt.Errorf("bridge: parse devices: %w", err)
	}
	if wrap.Schema != SchemaVersion {
		return nil, fmt.Errorf("bridge: bad devices.json schema %d", wrap.Schema)
	}
	if len(wrap.Devices) > deviceLimit {
		return nil, ErrLimitsExceeded
	}
	seenUUID, seenID := map[string]bool{}, map[string]bool{}
	for _, r := range wrap.Devices {
		fingerprint, certErr := CompanionCertSHA(r.ClientCertPEM)
		if !hex32.MatchString(r.DeviceID) || !hex64.MatchString(r.Token) || !hex64.MatchString(r.HostCertSHA) || !hex64.MatchString(r.CompanionCertSHA) || !clientUUIDRe.MatchString(r.ClientUUID) || certErr != nil || fingerprint != r.ClientCertSHA || seenUUID[r.ClientUUID] || seenID[r.DeviceID] {
			return nil, ErrBadField
		}
		seenUUID[r.ClientUUID], seenID[r.DeviceID] = true, true
	}
	return wrap.Devices, nil
}
