# Quest Preview 11 Release Notes — 0.1.0.11

Owner-test release. These notes describe what changed in 0.1.0.11 and what still
needs to be verified by the owner on real hardware. Nothing below should be read
as a claim that physical testing has already been performed.

## Version

- 0.1.0.11 (Quest preview 11)
- Quest keeps the same Android package ID and Android signing certificate as the
  previous Quest release, so the upgrade is applied in place.
- Vibeshine is unchanged in this release.

## What changed

- Flat-screen pointer: removed the 500 ms post-click freeze, so pointer motion no
  longer stalls after a click.
- Hands refresh every frame, including the Meta hand-tracking aim path and system
  gestures.
- Source / tracking loss now synthesises a mouse-up so the UI does not get stuck
  holding a pressed pointer.
- PCVR rejects stale or invalid poses instead of applying them to the scene.
- Reconnect preserves the current configuration, which avoids having to restart
  SteamVR by hand each time. If you change settings, an approved SteamVR restart
  may still be required.
- Settings: codec and bitrate rows are clickable with larger touch targets.
- Windows and Quest both check for updates automatically, and the "Update" action
  covers the app-side steps. The two platforms do not share an identical
  implementation.
- The Android/Windows OS consent dialog may still appear during an update.
  This is expected and cannot be pre-suppressed.
- Quest uses a private `PackageInstaller` flow instead of a generic APK viewer,
  so updates install in place without a third-party installer screen.

## Install notes

- The Windows installer upgrades the existing installation, so 0.1.0.11 installs
  over the previous version without uninstalling. Existing pairing and local data
  are retained.
- Quest installs over the previous version because the package ID and Android
  signing certificate are unchanged; existing pairing and local data are retained.
- If the old Quest updater fails to stage this build, install the manual APK for
  this upgrade only. The full update flow cannot be verified on the headset
  until a subsequent published version is available.

## Owner test steps

1. Flat-screen pointer: click, drag, and open settings with Quest controllers and
   bare hands. Confirm fine aim, drag, and that settings rows respond.
2. System menu: trigger a tracking loss, then confirm the menu recovers cleanly
   and no pointer stays stuck.
3. PCVR: connect, then reconnect, and confirm the session restores without a
   manual SteamVR restart.
4. Startup and update UI: launch on both platforms, confirm each checks for
   updates automatically, and walk the "Update" action through the app-side
   steps.

## Status

- Automated checks are in progress. No pass counts, and no Windows CI result,
  are claimed here; the final report is separate.
- Hardware stream quality, latency, and the Quest install path remain unverified
  until owner acceptance.
- Public IP native VR is still not implemented. LAN testing remains the focus,
  as in the preview 10 notes. No new WAN claims are made.
- README historical notes are unchanged.
