# Testing

## Automated tests (build host)

`./gradlew testNonRootDebugUnitTest` runs the unit suite. The build
host has passed 153 tests, 0 failures for preview7. Tests cover prefs, routing, mic,
capability gating, per-instance SettingsController wiring, top status
preference observer refresh, hub launch / request / permission guards,
PackageManager launcher-category resolution, the inflated
SettingsFragment depth-source list, and Robolectric XML inflation.
They do not load native code; `ShadowMoonBridge` skips JNI init only.
All tests must pass before the build is considered green.

The signed preview APK was statically verified for package, libs, and
entrypoint. Headset and host hardware were not available.

## Robolectric rendering screenshots

The build host also runs Robolectric with `GraphicsMode.NATIVE` to
produce PNG screenshots of the hub, Setup, and Streaming settings
screens. PNGs land at
`build/quest/upstream/app/build/reports/quest-ui/` with the following
filenames (all written at the exact requested pixel viewport, all
non-blank per the test's full-bitmap distinct-color scan):

- `hub_phone_360x640.png`
- `hub_phone_fontscale_1_6_360x640.png`
- `hub_phone_landscape_640x360.png`
- `hub_headset_1000x700.png`
- `hub_headset_fontscale_1_6_1000x700.png`
- `setup_phone_360x640.png`
- `setup_phone_fontscale_1_6_360x640.png`
- `setup_headset_1000x700.png`
- `settings_top_1000x700.png`
- `settings_presets_1000x700.png`
- `settings_vr_1000x700.png`

The hub layouts come from `vibertemis_hub.xml` (inflated via
`setContentView` in `MainHubActivity.onCreate`); the Streaming
settings screens come from `app/src/main/res/xml/preferences.xml`
(inflated via `addPreferencesFromResource` in the upstream
`StreamSettings.SettingsFragment`); the Setup screen is built
programmatically from `LinearLayout`/`TextView`/`Button` views in
`SetupActivity.onCreate` and is not driven from a layout XML — the
tests snapshot the actual rendered `View` tree either way.

These render the actual Android views through the framework's normal
inflation path with the Skia-backed NATIVE graphics mode. They are
not a substitute for headset hardware testing — they prove the
views inflate, lay out, and draw without crashing, and that the
visible text fits at fontScale 1.6 and in landscape; they cannot
prove runtime behaviour on the device.

The test asserts the bitmap's exact dimensions, that the distinct-
color count exceeds 16, and that more than 500 pixels differ from
the top-left background corner; PNG compression must also succeed
(`writePng` returns / file is non-zero size). The non-blank check
scans every pixel of the rendered bitmap — a sparse 8x8 sample grid
was found to miss rendered text on near-black backgrounds; the full
scan catches actual rendered rows.

## Hardware checklist (user must execute)

Sideload the APK, run it flat, opt into synthetic 3D, then exercise
SteamVR with head and two controllers tracked, haptics, recenter, audio
out, mic round-trip, rapid exit and relaunch, mode switching, and
sleep / wake. Capture LAN quality metrics from the ALVR dashboard and,
over VPN, record RTT, jitter, and loss for the Travel path. Do not
record measured performance numbers from the build host — there is no
real headset there.

## Preview 3 acceptance cases

- Upgrade preview 2 without uninstalling; confirm the signing identity and
  Screen pairing/settings survive. Screen mode must not start SteamVR.
- Import the Windows companion pairing file. Confirm an incorrect pin or
  token fails closed; Forget removes pairing and returns to manual setup.
- From Quest, explicitly connect and approve restart. SteamVR should start
  once. Cancel, phone launch, opening settings and stale background dialogs
  must not start it. After two minutes, a required restart needs fresh consent.
- Verify both controllers, buttons, poses, haptics and recenter in a VR game.
- Compare Standard AV1, HEVC and Home PyroWave using the actual decoded-codec
  label after returning to settings. Never infer success from the toggle.
- Test unavailable PyroWave capability and encoder/decoder initialization
  failure: fallback should negotiate Standard, with no false PyroWave label.
- Switch Travel on, connect through VPN, and confirm Standard plus its own
  bitrate ceiling. Switch Home back on; prior PyroWave selection is retained.
- Record resolution, refresh, actual bitrate, encode/decode times, network
  latency, frame drops and visual artefacts at 30/100/200 Mbps as applicable.
  Compare like-for-like settings. Do not assume 200 Mbps is always better.
- Exercise sleep/wake, network loss, host exit, repeated codec switches and
  rapid disconnect/reconnect. Check for stuck resources or duplicate launch.

Pure Rust tests cover native codec-selection/fallback and decoder config
bounds. Go tests cover authenticated startup, replay/transaction ownership,
read-only custom-host configuration and protected pairing export. Windows
runtime CI is required in addition to Linux race tests. Device testing remains
outstanding until the owner supplies results.

## Preview 7 guided-setup acceptance

1. Upgrade Vibeshine, Windows VR Host Manager, and Quest without uninstalling.
   Confirm existing screen pairing and saved stream settings remain intact.
2. With SteamVR closed, choose **Setup VR** on Windows. Missing VC++ should
   offer the bundled Microsoft runtime automatically; an already adequate
   runtime must not prompt again. Finish missing Steam/SteamVR setup if shown.
3. Choose **Setup VR** on Quest and select the already-paired PC. Enrollment
   must succeed without a pairing file. Cancellation or a rejected attempt
   must retain the previous working VR pairing. Pairing alone must not start VR.
4. Connect for VR. Verify SteamVR sees the headset and both controllers. Close
   VR, then connect a phone or use **Flat screen**; SteamVR must stay closed.
5. Remove the Quest's screen pairing in Vibeshine, then attempt a new VR
   request: inherited VR credentials must fail closed. Pair again and repeat
   Setup VR to recover. Existing sessions need separate disconnect testing.
6. Enable startup, reboot, sign in, and connect without opening the manager.
   Test Wi-Fi loss/recovery, headset sleep/wake, and PC address changes.
7. Open each app and verify automatic update status. A cached newer release
   should offer Download/Install directly. Keep a verified download through an
   offline launch; cancellation or active VR must not discard it.
8. For travel, connect both devices to a reachable VPN route and select its PC
   adapter under Advanced. Repeat setup/connect from another network. Forwarded
   GameStream ports alone do not provide tracked VR. Record latency and loss.
9. Compare Home PyroWave and Standard AV1/HEVC, plus Travel at 30 Mbps; check
   actual decoded codec, tracking, controllers, audio, frame pacing, and desktop
   restoration. Do not infer stream quality from a successful setup test.
