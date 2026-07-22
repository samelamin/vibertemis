# Vibepollo client compatibility design

## Purpose

Adopt the bounded, client-side protocol improvement found while comparing
Artemis with Nonary/Vibepollo, without copying host-only features or shipping an
untested adaptive bitrate controller.

The comparison is against Vibepollo commit
`6966c29befa98ceaa02e0b1cf9fe4a049eb0fb84` (release 1.18.1).

## Findings from the host comparison

Vibepollo's RTSP parser consumes all of these client fields:

- `x-nv-video[0].maxFPS`;
- `x-nv-video[0].clientRefreshRateX100`; and
- `x-ml-video.configuredBitrateKbps`.

It normalizes Artemis/Apollo's milli-Hz `maxFPS` convention, then validates that
`clientRefreshRateX100` rounds to the same effective frame rate. An inconsistent
x100 field is ignored to prevent incorrect capture pacing.

The pinned `moonlight-common-c` already knows how to emit
`clientRefreshRateX100` and `configuredBitrateKbps`. Artemis currently assigns
its custom fractional rate to `STREAM_CONFIGURATION.fps`, but never assigns
`STREAM_CONFIGURATION.clientRefreshRateX100`, so the library sends zero. The
configured bitrate field is already emitted for Sunshine-family hosts and does
not need a new Artemis feature.

Vibepollo 1.18 also exposes authenticated runtime bitrate and ABR-capability
endpoints. Calling those endpoints is not adaptive bitrate. A safe client
implementation additionally needs network feedback, a controller, hysteresis,
rate limits, recovery, capability and authentication behavior, UI policy, and
live-host testing. Runtime bitrate is therefore a documented follow-up, not
part of this compatibility change.

Vibepollo's virtual display recovery, WGC service mode, frame generation,
per-client HDR peak brightness, Playnite, WebRTC, and server UI work are
host-side features and are not portable into a Moonlight client.

## Refresh-rate conversion

Add one pure conversion beside the existing `RefreshRateParser` protocol
helpers. It accepts the resolved protocol FPS and fallback FPS and returns the
Moonlight x100 display refresh rate.

Rules:

- A positive `protocolFps` above 4000 uses Artemis/Apollo milli-Hz units. Begin
  with positive half-up rounding: `floor(protocolFps / 10.0 + 0.5)`.
- Vibepollo validates the two fields after independently rounding both to a
  whole FPS. Near a half-FPS boundary, nearest x100 rounding can cross that
  boundary when milli-Hz rounding does not. Clamp the candidate x100 value to
  the nearest value whose positive half-up whole-FPS result equals
  `floor(protocolFps / 1000.0 + 0.5)`. For whole FPS `N`, the accepted positive
  x100 interval is `[N*100 - 50, N*100 + 49]`.
- A positive integer `protocolFps` at or below 4000 becomes
  `protocolFps * 100`.
- A missing or invalid protocol value first uses the validated positive
  fallback FPS, then applies the integer rule.
- If neither value is positive or the result cannot fit in `int`, return zero,
  which preserves Moonlight's “not specified” behavior.

Examples:

| Requested rate | `maxFPS` | `clientRefreshRateX100` |
|---|---:|---:|
| 23.976 Hz | 23976 | 2398 |
| 29.97 Hz | 29970 | 2997 |
| 59.94 Hz | 59940 | 5994 |
| 119.88 Hz | 119880 | 11988 |
| 60 Hz integer/fallback | 60 | 6000 |

The half-up rule is explicit so values ending in an exact x100 half-unit do not
depend on a language or library's tie-to-even default. The consistency clamp
changes the nearest result by at most one x100 unit and only when needed to stop
Vibepollo from discarding the field. For example, 59.496 Hz initially rounds to
5950, but `maxFPS=59496` rounds to 59 FPS while x100 5950 rounds to 60 FPS, so
the transmitted x100 value is clamped to 5949. At 59.500 Hz both fields round to
60 FPS and x100 5950 is retained.

After `m_StreamConfig.fps` is resolved, Artemis assigns
`m_StreamConfig.clientRefreshRateX100` from this helper before starting the
Moonlight connection. Both RTSP fields therefore derive from the same validated
source.

## Compatibility boundaries

The change uses the existing NVIDIA/Moonlight field and is not gated on a
Vibepollo brand string. Apollo, Sunshine, and compatible GameStream hosts may
use it for pacing; older hosts can ignore it.

Artemis keeps its existing Apollo milli-Hz `maxFPS` behavior. No host detection,
new endpoint, settings UI, automatic bitrate setting, or server configuration
is added.

## Testing and acceptance

Extend the existing refresh-rate unit test with:

- the table above;
- disabled and invalid custom rates using the concrete integer fallback;
- zero/invalid protocol and fallback values returning zero;
- the exact positive half-unit boundary and values immediately around it;
- the 59.495/59.496 inconsistency case clamping to 5949 and the 59.500 case
  retaining 5950;
- upper validated rates without overflow; and
- consistency between formatted NVHTTP mode, effective FPS, `maxFPS`, and x100.

Unit tests prove representation and rounding, not live host interpretation. A
manual acceptance checklist will use a real Vibepollo/Apollo host to confirm:

1. the requested refresh rate appears in client logs;
2. the host reports the expected `maxFPS` and `clientRefreshRateX100`;
3. Vibepollo does not log a mismatch warning;
4. a stream starts and maintains the requested effective rate; and
5. integer and invalid-fallback rates still start normally.

Until that checklist is completed, README wording is “protocol unit-tested” or
“Vibepollo compatibility improvement,” never “Vibepollo stream verified.”

## Failure handling

Invalid persisted custom rates use the existing integer streaming preference as
the fallback and therefore send a matching integer `maxFPS` and x100 rate. If
both resolved and fallback rates are invalid, Artemis sends x100 zero and leaves
the host's existing pacing behavior unchanged.
