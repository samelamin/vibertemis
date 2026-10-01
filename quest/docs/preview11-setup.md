# Quest Preview 11 Setup — 0.1.0.11

Install the Windows installer and the Quest APK that carry the same version
number (0.1.0.11). Both install over the previous version; do not uninstall
first. Uninstalling would discard the local data and pairing described below.

The Windows app is Vibertemis VR Host Manager. The headset app is Vibertemis.
Vibeshine is the separate external host used for flat-screen Moonlight pairing;
it is unchanged in this release and is still required for flat-screen use.

## Windows

1. Run the 0.1.0.11 installer. It upgrades the existing installation in place,
   and existing pairing and local data are retained.
2. Leave "Start with Windows" enabled, so Vibertemis VR Host Manager starts
   after sign-in.
3. Run "Set up VR" only if the runtime is incomplete. It only installs and
   registers the VR runtime; if the headset is already paired and streaming
   works, skip it.
4. Vibertemis VR Host Manager waits for a connection request from Vibertemis on
   the headset. When the request arrives, compare the code shown in the Windows
   window with the code shown on the headset, then press Approve on Windows
   and Connect on the headset.

Selecting the PC belongs to "Set up PC" on the headset. There is no pairing
file to copy or place by hand; pairing is performed by the two devices
directly.

## Quest

1. Install the 0.1.0.11 APK over the existing installation. The package ID and
   the Android signing certificate are unchanged, so the upgrade is applied in
   place and local data is retained.
2. If the old Quest updater fails to stage this build, install the manual APK
   for this upgrade only. The updated in-app install flow cannot be verified on
   the headset until a subsequent published version is available to test with.
3. Open Vibertemis. If the headset is already paired with this PC, press
   Connect. Otherwise run "Set up PC", choose the PC, compare the code shown
   on the headset with the code shown in the Windows window, then press
   Approve on Windows and Connect on the headset.

If the headset asks for microphone permission, allow it.

Flat-screen streaming uses the existing Moonlight pairing with Vibeshine. VR
Host Manager pairing is separate and is not required for flat-screen
streaming. Vibeshine is unchanged.

## After connecting

- Only when Vibertemis requests a SteamVR restart, and only when that restart is
  necessary, save any running VR game on the PC first and then choose Restart
  VR. Choosing Cancel leaves the running game alone.
