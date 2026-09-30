# Vibertemis XR Preview (Quest3) — Setup

A single arm64 APK shipping two streaming modes from one launcher.

- **Screen gaming** — flat streaming on phone; on headsets, optional
  synthetic 3D is available. Pairs with a Sunshine or Apollo host on the
  PC. Moonlight itself is never used as a server.
- **Real PCVR** — custom ALVR 20.14.1-vibertemis-pyro.1 with HMD and Touch controller
  tracking on Quest3. The PC host runs the ALVR streamer; this APK is
  the headset client. Hardware behavior on Quest3 is UNTESTED.

APK `vibertemis-quest-preview-0.1.0.6.apk`, package
`com.vibertemis.quest.preview.debug`, version `0.1.0-quest-preview.6`,
`arm64-v8a` only, `minSdk=26`, `targetSdk=34`. No second ALVR headset
app — the runtime is bundled inside this APK.

## Install on Quest3

1. Put the headset in developer mode per Meta's official guide:
   <https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/>.
2. Connect over USB and accept the "Allow USB debugging?" prompt on
   the headset.
3. `adb install -r vibertemis-quest-preview-0.1.0.6.apk`.
4. Launch under **Unknown Sources**.

## Screen mode

Pairs with Sunshine (or Apollo, an alternative Sunshine-based host) on
the PC. The headset discovers Sunshine over mDNS and adds it to the PC
list. The headset displays a PIN; enter that PIN in the Sunshine (or
Apollo) web UI on the PC to pair.

| Profile | Resolution | FPS | Bitrate | Codec |
|---------|-----------|-----|---------|-------|
| Home (LAN) | 2560×1440 | 72 | 40 Mbps | auto |
| Travel (WAN) | 1920×1080 | 60 | 17 Mbps | auto |
| High quality LAN (opt-in) | 3840×2160 | 60 | 80 Mbps | auto |
| Custom | user | user | user | user |

The bitrate slider accepts values up to 200 Mbps (manual). 80 Mbps is
the starting baseline for the HQ preset, not a measured optimum; higher
bitrates are available manually but do not guarantee a stutter-free
stream on any non-dedicated access point.

Tapping **Apply** opens a confirmation dialog that lists the exact
replacement. Cancel leaves the current settings untouched. After
Apply, the cached resolution / fps / bitrate / codec widgets refresh
in place so no stale value lingers. Custom preserves the user's
current values and writes nothing. Opening the preferences screen
never applies a preset; upstream may still seed or migrate its own
defaults. `auto` codec uses the existing Moonlight / Apollo / Sunshine
negotiation — this preview claims no priority order.

Synthetic-depth is exposed upstream and defaults to `off` here, so the
screen path stays flat unless the user opts in to the depth model. The
synthetic test patterns (`flat`, `ramp`, `blob`, `eyetest`,
`shifttest`) are hidden in this preview build regardless of debug
status; an invalid value falls back to `off`. Touch controllers behave
as a mouse pointer in screen mode; the standard gamepad buttons
upstream already supports work for games.

The hub status line at the top of the hub reads the current streaming
settings through the upstream `PreferenceConfiguration.readPreferences`
reader on every `onResume` — there is no separate "last profile" lie.

## PCVR mode (Quest3 only)

Wireless SteamVR, headset tracking and Touch controllers use the bundled
custom ALVR client. **Install the matching Windows host from this release.**
Stock ALVR 20.14.1 and other releases cannot connect to this custom protocol.
Target hardware: Windows 11, RTX 4090, Quest 3. Actual headset/GPU testing
is still required; this is an experimental test build.

### Windows host setup

1. Run `VibertemisVR-HostManager-Setup-0.1.0.6.exe` in your normal Windows
   account. The per-user wizard installs the manager and matching VR runtime.
   Keep Vibeshine installed for flat streaming. No PowerShell is needed.
2. Open **VibertemisVR Host Manager**. Use its prerequisite buttons if Steam,
   SteamVR or the Microsoft Visual C++ x64 runtime is missing.
3. Choose your PC's reachable LAN address. Click **Prepare VR** and approve
   Windows network access. This verifies/registers the matching SteamVR driver,
   creates native defaults only if missing, and starts the connection service.
   Close SteamVR and ALVR Dashboard first. Switching from another ALVR driver
   asks once; its files/settings and unrelated drivers remain intact.
4. Enable **Keep host ready after Windows sign-in**. Click **Export pairing
   file**. Copy `pairing-export.json` to Quest Downloads over USB, open
   **PCVR settings**, and import it. Keep this credential private; remove the
   transferred copy after import. Headset trust is now registered automatically
   from this authenticated pairing. Vibeshine screen pairing is separate.
5. On Quest, tap **Connect**, grant microphone permission, and confirm the
   possible SteamVR restart. A detected VR headset takes the tracked PCVR
   path automatically. **Flat screen** remains an explicit override. Phones
   take the flat path and do not ask the host to start SteamVR.

After this one-time setup, sign in to Windows and leave the manager in the
tray. It waits for the saved network, retries startup/crashes (2–30 seconds,
then a 60-second cooldown after repeated failures), and rebinds its connection
service when that adapter's address changes. Pairing stays intact. This never
starts or stops SteamVR by itself; the paired Quest Connect request starts VR.
A missing saved adapter waits instead of switching to a different network.

**Keep host ready after Windows sign-in** is the single startup setting.
For a new installation it is recommended before the first explicit Start.
Existing preview4 startup choices are preserved: enable this checkbox once if
it was previously off. Closing the window keeps hosting in the tray. **Stop
companion** pauses hosting and next-launch restoration until you Start again.
**Exit** stops the current companion but preserves next-login restoration.
Disabling the checkbox disables future automatic startup/restoration; use Stop
as well if you want to stop the current session. Startup is after Windows
sign-in, not a service running at the login screen.

Paired LAN discovery recovers changed PC addresses after authenticating the
saved PC identity. Internet use still needs a reachable direct/VPN address.
See [RECOVERY_PLAN.md](RECOVERY_PLAN.md) for the reboot-to-Quest test checklist.

For an existing ZIP setup, pairing in the user profile is reused. Its ALVR
session stays in the old ZIP folder; close VR and use Explorer to copy that
`session.json` into the new installation's `runtime` folder if you want to
keep those native settings. Use **Prepare VR** to register the new runtime.
Do not run the old standalone companion alongside the manager.

Codec/display changes can require a SteamVR restart; save your game before
confirming Connect. Without companion pairing, the manual route requires
starting SteamVR on the PC yourself.

### In-app updates

- Windows: **Check for updates** in the host manager. Confirm download, close
  SteamVR/ALVR Dashboard, then click **Install update**. Download progress and
  cancellation are shown; a busy session keeps the verified download for later.
  The worker waits for the manager to exit, runs setup, checks the installed
  FileVersion and payload integrity, and reopens the manager with the outcome.
  Pairing/settings are preserved. Active VR sessions block updates/uninstall.
  If the old updater failed, run the preview6 installer manually once.
  Logs are under `%LOCALAPPDATA%/VibertemisVRHostManager/logs`; the last shown
  result stays in `updates/last-update.json.seen` under that state directory.
- Quest: **App updates** in the idle hub. Download, then **Install update**.
  Allow installs from Vibertemis if Android asks, and confirm the Android
  installer. Preview4 and later update through this screen. Preview3 needs
  one manual sideload to gain the updater.
- Both clients verify signed release metadata and downloaded bytes. Quest
  additionally requires the existing app signer and a newer package version.
  Checks target Quest previews, independently of desktop releases.

### Travel and desktop restoration

Forwarded GameStream ports/public PC IP do not carry the native tracked VR
transport. Preview6 supports an optional Tailscale adapter. Install and connect
Tailscale on the PC and Quest separately, select the PC's **Tailscale VPN** address
in Host Manager, run **Prepare VR**, then export/import that pairing. This is not
an integrated VPN installer. LAN remains available without it. Native trust uses
the authenticated connection's private/VPN source address. If the headset address
changes while SteamVR is already running, close SteamVR once and reconnect.
Remote Quest VPN/background behaviour and latency require hardware testing.

For flat streaming, Vibeshine already has **Restore as soon as the client
disconnects** under its display lifecycle/recovery settings. Enable it on the PC
so the physical desktop returns after the last client disconnects or times out.
This release does not silently change the running Vibeshine configuration.

### Codec and quality controls

**PCVR settings** keeps its controls separate from Screen gaming:

- **PyroWave**: experimental GPU codec for Home. Switch it off to use the
  saved Standard selection: Auto, AV1 or HEVC. Runtime encoder/decoder
  capability checks can fall back to a standard codec.
- **Travel**: temporarily uses Standard and preserves the PyroWave choice
  for your next Home connection.
- **Home limit**: adjustable 5–200 Mbps, starting at 200 Mbps.
- **Travel limit**: adjustable 5–45 Mbps, starting at 30 Mbps to leave room
  on a 50 Mbps connection. Adaptive bitrate may drop below either limit.
- **Last decoded codec**: recorded only after the headset decodes a frame.
  It describes the last stream, not a guarantee for the next connection.

Changes apply on the next PCVR connection. Disconnect before changing an
active stream. Resolution, refresh rate and fixed-foveated encoding remain
in the ALVR dashboard. Begin at 72 Hz, then test higher display settings on
your own network. High bitrate does not guarantee low latency.

ALVR's fixed foveation reduces peripheral image detail before encoding.
This is not Valve's gaze-driven foveated-streaming implementation. Quest 3
has no eye tracking; this build does not offer gaze-driven foveation.

For travel, use the optional Tailscale setup above. The headset's VPN address
is registered automatically from the authenticated connection. No automatic
Internet discovery, VPN installation or router port opening is implemented. Home upload, round-trip delay, jitter and packet
loss matter alongside download speed. See the
[ALVR separate-network guide](https://github.com/alvr-org/ALVR/wiki/Headset-and-ALVR-streamer-on-separate-networks).

Microphone permission is required by the preview launcher. Voice forwarding
is controlled separately in the ALVR dashboard. Screen synthetic 3D and real
stereoscopic PCVR are separate modes.

## Rebuilding from source

Prerequisites: `git`, `rsync`, `curl`, `unzip`; JDK 17+ (tested 21);
SDK `platforms/android-34`, `build-tools/34.0.0`, `platform-tools`,
NDK `27.0.12077973`. Set `ANDROID_HOME` and `JAVA_HOME`; optional
`GRADLE_USER_HOME` is respected.

```bash
bash quest/build/fetch.sh
bash quest/build/apply-overlays.sh
bash quest/native/build-android.sh
python3 quest/native/install-android.py
bash quest/build/build.sh
```

Native prerequisites additionally include Rust 1.97.1, CMake and Ninja.
The native scripts fetch exact ALVR, PyroWave and Granite commits from
`native/sources.json`. Source fingerprints and library hashes reject stale
or mixed binaries. Windows host build: `quest/native/build-windows.ps1`
with MSVC and the Windows SDK. Build the companion from `quest/host` using
Go 1.26.8: `go build ./cmd/vibertemis-host-companion`.

APK output:
`build/quest/upstream/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk`.
The corresponding-source archive includes patched Android/ALVR trees,
PyroWave, Granite with submodules, recipes and original licenses.
See `TESTING.md` for the real-device acceptance checklist. Automated tests
and successful compilation do not establish streaming quality or latency.

For the Windows installer, use Windows with .NET 8, Go 1.26.8 and Inno Setup 6:

```powershell
./quest/installer/build-installer.ps1
```

The script verifies the immutable native payload, embeds its hashes and builds
the manager/helper/installer. See `quest/update/README.md` for the signed update
metadata contract. Release signing private keys are not included in source.
