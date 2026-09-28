# Vibertemis XR Preview (Quest3) — Setup

A single arm64 APK shipping two streaming modes from one launcher.

- **Screen gaming** — flat streaming on phone; on headsets, optional
  synthetic 3D is available. Pairs with a Sunshine or NVIDIA
  GameStream host. Moonlight itself is never used as a server.
- **Real PCVR** — actual ALVR v20.14.1 with HMD and Touch controller
  tracking on Quest3. The PC host runs the ALVR streamer; this APK is
  the headset client. Hardware behavior on Quest3 is UNTESTED.

APK `vibertemis-quest-preview-0.1.0.1.apk`, package
`com.vibertemis.quest.preview.debug`, version `0.1.0-quest-preview.1`,
`arm64-v8a` only, `minSdk=26`, `targetSdk=34`. No second ALVR headset
app — the runtime is bundled inside this APK.

## Install on Quest3

1. Put the headset in developer mode per Meta's official guide:
   <https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/>.
2. Connect over USB and accept the "Allow USB debugging?" prompt on
   the headset.
3. `adb install -r vibertemis-quest-preview-0.1.0.1.apk`.
4. Launch under **Unknown Sources**.

## Screen mode

Pairs with Sunshine or GameStream on the host.

| Profile | Resolution | FPS | Bitrate | Codec |
|---------|-----------|-----|---------|-------|
| Home (LAN) | 2560×1440 | 72 | 40 Mbps | auto |
| Travel (WAN, screen only) | 1920×1080 | 60 | 17 Mbps | auto |
| Custom | user | user | user | user |

Tapping **Apply** writes values into the existing upstream preference
keys; Custom preserves the user's current values and writes nothing
new. Opening the preferences screen never applies a preset; upstream
may still seed or migrate its own defaults. `auto` codec uses the
existing Moonlight / Apollo / Sunshine negotiation — this preview
claims no priority order. Synthetic-depth is exposed upstream and
defaults to `off` here, so the screen path stays flat unless the user
opts in. Touch controllers behave as a mouse pointer in screen mode;
the standard gamepad buttons upstream already supports work for games.

## PCVR mode (Quest3 only)

Wireless SteamVR through ALVR v20.14.1. The headset client is this
APK; the PC host runs the matching ALVR streamer.

### ALVR PC streamer (REQUIRED, exact 20.14.1)

- **Windows** — `alvr_streamer_windows.zip`
  <https://github.com/alvr-org/ALVR/releases/download/v20.14.1/alvr_streamer_windows.zip>
  SHA-256 `6fbb85432822e9e3162d29b2919cd843bd9805262aef0c4797ffec7c57654a83`.
  Launch `ALVR Dashboard.exe` at the archive top level.
- **Linux** — `alvr_streamer_linux.tar.gz`
  <https://github.com/alvr-org/ALVR/releases/download/v20.14.1/alvr_streamer_linux.tar.gz>
  SHA-256 `be82b4a7a3cb3607dd5c307cf2b0a1b524d1ecbba586d657a458117a8a63bc09`.
  Launch the extracted `alvr_streamer_linux/bin/alvr_dashboard`.

Verify with `sha256sum` after download. Install Steam and SteamVR on
the PC; let the ALVR wizard register its driver. On the headset, tap
the SteamVR entry — the headset app prompts for microphone access on
first use. On the PC dashboard, trust the discovered headset, then
launch SteamVR. Requirements: <https://github.com/alvr-org/ALVR/wiki/Requirements>.

### PCVR settings live on the dashboard

Codec, refresh, resolution, adaptive bitrate, and fixed-foveated
encoding are dashboard-owned. Verified ALVR v20.14.1 defaults (see
`alvr/session/src/settings.rs`): 72 Hz, H.264, 30 Mbps constant,
fixed-foveated encoding on, adaptive bitrate available but not
default-enabled. Quest3 has no eye-tracking hardware, so no
gaze-foveation promise. ALVR's fixed-foveated encoding reduces
peripheral detail in the encoded bitstream — distinct from
local-client foveated rendering. The Travel preset is
screen-only. A 50 Mbps downlink test alone does not predict a working
session; home upload, RTT, jitter, and loss dominate. PCVR WAN is
experimental: run a UDP-passing VPN and enter the peer's VPN-side IP
manually in the ALVR dashboard. No headset-side host IP field, no
automatic port opening, no seamless Internet VR.
Guide: <https://github.com/alvr-org/ALVR/wiki/Headset-and-ALVR-streamer-on-separate-networks>.
## Rebuilding from source

Prerequisites: `git`, `rsync`, `curl`, `unzip`; JDK 17+ (tested 21);
SDK `platforms/android-34`, `build-tools/34.0.0`, `platform-tools`,
NDK `27.0.12077973`. Set `ANDROID_HOME` and `JAVA_HOME`; optional
`GRADLE_USER_HOME` is respected.

```bash
bash quest/build/fetch.sh          # fetch upstream + extract native lib
bash quest/build/apply-overlays.sh # apply patch and overlay sources
ANDROID_HOME=/path/to/Android/Sdk \
JAVA_HOME=/path/to/jdk-21 \
bash quest/build/build.sh          # tests + assembleNonRootDebug
```
Output APK:
`build/quest/upstream/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk`.

The source archive exposes `android/` (full patched Moonlight XR
source plus submodules and the prebuilt ALVR `.so`), `alvr-source/`
(exact MIT source for ALVR at the pinned SHA), and
`vibertemis-quest/` (recipes, overlays, docs). GPLv3 and MIT
originals included; no `.git` required. From `android/`, run
`./gradlew testNonRootDebugUnitTest assembleNonRootDebug` with
`ANDROID_HOME` and `JAVA_HOME` set. Real sideload, ALVR session,
SteamVR, controller tracking, haptics, recenter, mic round-trip,
host disconnect / reconnect, and LAN / VPN quality metrics are the
user's responsibility — see `TESTING.md` and `RESEARCH.md`.