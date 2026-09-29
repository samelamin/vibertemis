// validRequestClass + normalizeCodec live here so both server.go
// and start_pcvr.go can use them.
package server

import (
	"strings"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
)

// validRequestClass enforces the Phase 1 contract:
//   - role MUST be exactly "headset"
//   - mode  MUST be exactly "pcvr"
//
// The paired token is authorization (it authenticates the
// caller as a paired client); it is NOT hardware attestation.
// A signed request with role="viewer", role="", mode="screen",
// or mode="" is rejected before any state read or write.
func validRequestClass(role, mode string) bool {
	return role == "headset" && mode == "pcvr"
}

// normalizeCodec converts "auto" / "Auto" / AutoSentinel to
// the AutoSentinel marker; everything else is passed through
// as a stock codec value.
func normalizeCodec(s string) alvr.Codec {
	if s == "" || strings.EqualFold(s, "auto") || s == alvr.AutoSentinel {
		return alvr.Codec(alvr.AutoSentinel)
	}
	return alvr.Codec(s)
}
