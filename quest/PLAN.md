# Plan — final implementation

Two streaming modes share one APK. Screen mode pairs with Sunshine or
Apollo on the host; PCVR mode pairs with ALVR v20.14.1 on the PC host.
Quest3 runs the bundled headset client; Moonlight is never used as a
server. Phone is screen-only. The OpenXR loader is the Maven
`openxr_loader_for_android` artifact; only the ALVR `.so` is packed
from the upstream APK.

## Source pins

- Moonlight: `ae173a4966bb68af749d45ed87c8b9737a0ce570`.
- ALVR: `a9f6542fa507a841f40ab4f3fcb531427cd02550`.
- Additional pins and submodules live in `quest/pins/pins.txt`;
  exact upstream READMEs ship in the source archive.

## Preview 2 identity

- `versionCode = 2`
- `versionName = "0.1.0-quest-preview.2"`
- Package and signer unchanged: `com.vibertemis.quest.preview.debug`
  with the AGP debug keystore.

## Test surface

The unit suite covers prefs, routing, mic, capability gating, the
per-instance `SettingsController` observer wiring, the top status
preference refresh path, the hub launch / request / permission
guards, PackageManager launcher-category resolution, the inflated
SettingsFragment depth-source list, and Robolectric XML inflation.
The build host runs 99 tests with 0 failures, 0 errors, 0 skipped,
plus Robolectric with `GraphicsMode.NATIVE` to render 11 PNG
screenshots (hub, Setup, and Streaming settings) into
`app/build/reports/quest-ui/`. The build host verified: clean fetch,
`apply-overlays.sh` run twice, full build, tests pass, screenshots
written at exact viewport dimensions, full-bitmap distinct-color
scan (replaces the prior sparse 8x8 sample grid that missed rendered
text on near-black backgrounds). No hardware test was performed; the
hardware gap is the user's checklist in `TESTING.md`.

## Review and lifecycle

Initial Claude ideas review held; findings were checked against
source. Codex interim review caught string and capability-check
defects; those were fixed before ship. Final Agy review covered
manifest launcher categories, the permission-result launch-guard
race, the depth debug conditional, and the ALVR setup copy — i.e.
the runtime code; Agy did not review the screenshot harness.
Claude final review recorded no confirmed P0/P1 after the launch
fix. Agy Gemini 3.8 Flash High final review supplied runtime
sign-off — 0 P0/P1 outstanding. Codex independently confirmed
99 tests / 0 failures / 0 errors / 0 skipped, the same APK signer
(AGP debug keystore), `zipalign` succeeded, and the bundled ALVR
`.so` hash matches the pinned value in `quest/pins/pins.txt`. Final
source-package build is running; Codex final package validation
is held pending until the commit message records completion. The
build host verifies the unit suite and the APK assembles cleanly.

The focus-aware implementation matches the official ALVR Cargo
config. Native libraries and the headset process pin are unchanged
from upstream.

## Refusals (review items not adopted)

- Per-tile encoder quality claims about ALVR's fixed-foveated
  encoding: rejected. FFE downscales the periphery before encoding;
  it is not per-tile.
- `noHistory` is not every focus loss: rejected. Stock ALVR manifest
  does not set it, and we do not set it either.
- "Every ALVR connection must fail without mic" claim: rejected. We
  say the preview build asks for mic permission before launching
  PCVR because unmodified ALVR retries can fail on hosts with host-
  side mic enabled; not a universal ALVR failure mode.
- 80 Mbps is not a measured optimum for the HQ preset: stated
  explicitly in user copy. No claim of a stutter-free stream.
- Convenience-overload fail-closed contract: retained as an
  intentional safety property. The UI must not call the pure
  transaction path; the guard fails closed on a null allowed
  snapshot.
