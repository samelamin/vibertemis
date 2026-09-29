// Test helpers for the ALVR package. Tiny shims that keep the
// test file free of encoding/json and other package names.
package alvr

import "encoding/json"

func jsonUnmarshal(b []byte, v interface{}) error { return json.Unmarshal(b, v) }
func jsonMarshalIndent(v interface{}, p, ind string) ([]byte, error) {
	return json.MarshalIndent(v, p, ind)
}
