# Quest preview 10 — experimental test build

Matching Quest APK and Windows VR Host Manager: **0.1.0.10**.
Keep Vibeshine installed and unchanged.

## What changed

- Larger Quest controls, clearer connection progress, and inline Retry/Cancel actions.
- **Set up PC** discovers and pairs an unpaired headset. A paired headset shows **Connect**.
- Connecting starts SteamVR when the authenticated PC reports it stopped. If SteamVR is already running, **Restart VR** asks for confirmation first.
- Manual VR now requests microphone permission when needed and retains restart confirmation.
- Shorter Windows labels, **Approve / Reject**, and collapsible Advanced settings and Activity log.
- Both apps automatically check for updates. One **Update** action handles downloading and verification, then starts installation. Android or Windows may still require system confirmation.
- Update failures remain visible, with retry paths. A Windows startup race that could overwrite update results is fixed.
- The Quest APK includes the previously missing C++ runtime. Packaging checks every native library dependency and records the runtime's origin and checksum.

## Install or upgrade

1. Close VR. Install `VibertemisVR-HostManager-Setup-0.1.0.10.exe` on Windows.
2. Install `vibertemis-quest-preview-0.1.0.10.apk` on Quest **without uninstalling the existing app**. Preview 9's broken updater requires this one manual APK install. The package and signing certificate are unchanged.
3. On Windows, use **Set up VR** if setup is incomplete. Keep **Advanced > Start with Windows** enabled for startup after Windows sign-in.
4. If the Quest is already paired, select **Connect**. Otherwise select **Set up PC**, choose the PC, compare the code on both devices, and choose **Approve** on Windows. Then select **Connect** on Quest.
5. Grant microphone permission if asked. If prompted to restart an existing SteamVR session, save any game first; **Cancel** leaves it running.

No pairing file is required for normal setup. Flat-screen streaming continues through Vibeshine.

## End-to-end test

Use the same home network first:

1. Start a SteamVR game. Check headset tracking, both controllers, picture, sound, and microphone.
2. Disconnect and reconnect. Check that the desktop remains usable and that Retry/Cancel recover from an interrupted connection.
3. Sign out of Windows and sign back in. Confirm the manager starts in the tray and a paired Quest can connect without repeating setup.
4. Open each app and confirm it checks for updates automatically. Preview 10 should report itself current once no newer release exists. The next published release is needed to test a real device upgrade from this version.
5. Try flat-screen streaming separately. Report which mode failed, the exact message, and whether SteamVR was already running.

## Limits

This is an experimental build for owner testing. Quest 3 / RTX 4090 streaming quality, latency, controllers, and real-device installation still need hardware validation; a flawless stream is not claimed.

Native tracked VR over a public IP and forwarded router ports is **not implemented**. This build targets local-network testing. Existing flat-screen remote streaming is separate.

The release includes the pinned Android runtime's provenance and license notices. Native protocol and existing trust keys are unchanged.
