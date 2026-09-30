package enrollment

import (
	"encoding/json"
	"errors"
	"github.com/vibertemis/quest-codec-control/host/internal/pairing"
	"io"
	"os"
	"path/filepath"
)

type FileStore struct{ Dir string }
type envelope struct {
	Schema  int      `json:"schema"`
	Devices []Record `json:"devices"`
}

const storeLimit = 32768

func (f FileStore) Save(records []Record) error {
	if len(records) > 32 {
		return ErrInvalid
	}
	if err := pairing.EnsureDir(f.Dir); err != nil {
		return err
	}
	data, err := json.Marshal(envelope{1, records})
	if err != nil {
		return err
	}
	if len(data) > storeLimit {
		return ErrInvalid
	}
	tmp, err := os.CreateTemp(f.Dir, ".standalone-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err = pairing.ProtectFile(tmp.Name()); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return replaceFile(tmp.Name(), filepath.Join(f.Dir, "standalone-devices.json"))
}
func (f FileStore) Load() ([]Record, error) {
	path := filepath.Join(f.Dir, "standalone-devices.json")
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		return nil, nil
	} else if err != nil {
		return nil, err
	}
	if err := pairing.ProtectFile(path); err != nil {
		return nil, err
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, storeLimit+1))
	if err != nil {
		return nil, err
	}
	if len(data) > storeLimit {
		return nil, ErrInvalid
	}
	var e envelope
	if err = json.Unmarshal(data, &e); err != nil {
		return nil, err
	}
	if e.Schema != 1 || len(e.Devices) > 32 {
		return nil, ErrInvalid
	}
	return e.Devices, nil
}
