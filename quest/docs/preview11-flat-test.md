# Quest 3 flat-screen tracking test — preview 11

Manual test release only: `vibertemis-quest-preview-0.1.0.11.apk`.

The Windows preview 11 installer and the combined PCVR end-to-end flow are **not
ready**. Do not treat preview 11 as a full release. This build is deliberately
**not offered by the in-app updater** — install and update it by hand.

## Install

- Install the APK over your existing Vibertemis app. Do **not** uninstall first:
  the package and signer are unchanged, so your Vibeshine pairing is retained.
- Vibeshine is unchanged in preview 11. Your existing Vibeshine already supports
  the flat-screen test, so you do not need to update Windows VR Host Manager.
- In Vibertemis, choose **Flat Screen**, then connect to your existing paired
  Vibeshine PC.

## What to test

Run the same checks with Quest controllers and again with bare hands:

- Cursor motion right after a click.
- Dragging while holding trigger / pinch.
- The settings cog and buttons.
- Tracking loss and source handoff — no stuck clicks.

## Changes in this preview

Tracking fixes targeted by this test:

- Removed the 500 ms post-click pointer freeze.
- Hands are sampled every frame.
- Held input is released when tracking is lost.

An in-app update fix is included, but it can only be verified once a later
published version exists.

## Status

- 427 Android tests pass; APK native dependencies and signing verified.
- No physical Quest 3 validation has been done yet — that is what this test is
  for. Please report what you observe.
- The PCVR full release follows the Windows checks.
