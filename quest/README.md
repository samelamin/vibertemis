# Vibertemis XR Preview (Quest3) — Setup

A single arm64 APK shipping two streaming modes from one launcher.

- **Screen gaming** — flat streaming on phone; on headsets, optional
  synthetic 3D is available. Pairs with a Sunshine or Apollo host on the
  PC. Moonlight itself is never used as a server.
- **Real PCVR** — custom ALVR 20.14.1-vibertemis-pyro.1 with HMD and Touch controller
  tracking on Quest3. The PC host runs the ALVR streamer; this APK is
  the headset client. Hardware behavior on Quest3 is UNTESTED.

APK `vibertemis-quest-preview-0.1.0.3.apk`, package
`com.vibertemis.quest.preview.debug`, version `0.1.0-quest-preview.3`,
`arm64-v8a` only, `minSdk=26`, `targetSdk=34`. No second ALVR headset
app — the runtime is bundled inside this APK.

## Install on Quest3

1. Put the headset in developer mode per Meta's official guide:
   <https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/>.
2. Connect over USB and accept the "Allow USB debugging?" prompt on
   the headset.
3. `adb install -r vibertemis-quest-preview-0.1.0.3.apk`.
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

1. Install Steam and SteamVR. Extract this release's Windows host ZIP to a
   permanent folder, for example `C:\VibertemisVR`. Keep its DLLs together.
2. Run `ALVR Dashboard.exe`, finish the setup wizard and register the driver.
   Allow ALVR through Windows Firewall on your trusted network. Keep the
   dashboard available for trusting the headset and changing display settings.
3. The ZIP includes `vibertemis-host-companion.exe`. Use the actual absolute
   path to the dashboard's `session.json` below.
4. In PowerShell, replace the example IP with your PC's reachable LAN IP:

```powershell
cd C:\VibertemisVR
.\vibertemis-host-companion.exe -alvr-session "C:\VibertemisVR\session.json" -advertise "192.168.1.10:28540" -export-pairing
```

The command prints the path to a private UTF-8 `pairing-export.json` file.
Copy it to the headset's Downloads folder over USB, then open **PCVR settings**
and import it. The file contains a pairing token; keep it private and remove
the transferred copy after import. Do not redirect `-show-export` from older
PowerShell: its UTF-16 output will not import.

5. Start the companion in your normal signed-in Windows session:

```powershell
.\vibertemis-host-companion.exe -alvr-session "C:\VibertemisVR\session.json" -listen "192.168.1.10:28540" -advertise "192.168.1.10:28540"
```

Keep this window open. Allow this executable through the firewall on the
trusted network if prompted. The companion uses TCP 28540; ALVR's wizard
manages streaming rules. It must run as your interactive user, not a service.

6. On Quest, choose **SteamVR**, grant microphone permission, then confirm
   **Connect**. This explicitly permits a SteamVR restart if the selected
   codec/display configuration requires it. Trust the discovered headset in
   the ALVR dashboard on first connection. Launch a VR game from SteamVR.

Only an explicit headset PCVR connection asks the companion to start SteamVR.
Screen gaming, phones, settings and status checks do not start it. Without
pairing, the manual route requires starting SteamVR on the PC yourself.
Codec/display changes can require a restart; save your current game first.
The restart permission expires after two minutes, including connection setup.

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

For travel, configure a UDP-capable VPN and add the headset's reachable VPN
IP manually in ALVR. Pair the companion using the PC's reachable VPN address
if it differs from the LAN address. No automatic Internet discovery or port
opening is implemented. Home upload, round-trip delay, jitter and packet
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
Go 1.26: `go build ./cmd/vibertemis-host-companion`.

APK output:
`build/quest/upstream/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk`.
The corresponding-source archive includes patched Android/ALVR trees,
PyroWave, Granite with submodules, recipes and original licenses.
See `TESTING.md` for the real-device acceptance checklist. Automated tests
and successful compilation do not establish streaming quality or latency.
