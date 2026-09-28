# Plan — final implementation

Two streaming modes share one APK. Screen mode pairs with Sunshine or
NVIDIA GameStream on the host; PCVR mode pairs with ALVR v20.14.1 on
the PC host. Quest3 runs the bundled headset client; Moonlight is
never used as a server. Phone is screen-only. The OpenXR loader is
the Maven `openxr_loader_for_android` artifact; only the ALVR `.so`
is packed from the upstream APK.

## Source pins

- Moonlight: `ae173a4966bb68af749d45ed87c8b9737a0ce570`.
- ALVR: `a9f6542fa507a841f40ab4f3fcb531427cd02550`.
- Additional pins and submodules live in `quest/pins/pins.txt`;
  exact upstream READMEs ship in the source archive.

## Test surface

The 37 unit tests cover prefs, routing, mic, and guard logic. The
build host just verified: clean fetch, `apply-overlays.sh` run
twice, full build 37/37 tests pass. No hardware test was performed;
the hardware gap is the user's checklist in `TESTING.md`.

## Review and lifecycle

A focused Claude ideas review was held; findings were checked
against source. Agy (Gemini 3.8 Flash High) approved runtime
process isolation without a global file lock. A code review
verified the core and the requested focus-aware plus
script-idempotency plus staging work. The focus-aware
implementation matches official ALVR Cargo config; the code fix is
under test, the scripts are already improved, and Agy's final
sign-off is pending.