# Native codec integration — experimental Quest preview 3

Matching custom ALVR protocol: 20.14.1-vibertemis-pyro.1. Stock ALVR hosts
cannot pair with this native client. Install the matching Windows package.

`sources.json` pins ALVR, PyroWave, Granite and the research reference.
Granite is pinned to the separately validated source checkout rather than
following PyroWave's moving checkout helper. Its volk/Vulkan headers retain
the submodule revisions recorded by that commit. `fetch.py` applies only the
selective ALVR patch and refuses to overwrite unrelated work.

The planar encoder, conversion shader and Android Vulkan bridge adapt
Galaxy XR ALVR Research under the MIT license in `pyroclient/LICENSE`.
Upstream ALVR, PyroWave and Granite retain their own licenses; include their
source/license trees with release packages. No Galaxy eye-tracking, alternate
wavelets or separate UDP protocol is imported. Existing ALVR transport,
Quest tracking/Touch input and hardware AV1/HEVC paths remain in use.

Windows: run `build-windows.ps1` with MSVC/Windows SDK, Python 3, CMake and
Rust 1.97.1. The PyroWave DLL sits beside the OpenVR driver and is loaded only
when probing/using that codec. This script does not register or start SteamVR.
The `quest/native-codec-ci` branch builds draft artifacts, never releases.

Android: `build-android.sh` builds the pinned libraries; `install-android.py`
installs their verified set after the base fetch/overlay. `check-android.py`
detects stale or mixed libraries. Windows CI run 36550716420 passed. The integrated APK enables the toggle
with verified custom libraries. Source review and both platform builds pass;
real Quest/RTX hardware testing remains outstanding.

Buffer lifetime: latest pending frame, three explicitly leased output buffers;
renderer owns its buffer until next dequeue. GPU waits are bounded to one
second. GPU faults quarantine native resources until the isolated process exits
rather than freeing memory still in use. Hardware testing remains necessary.

Review: Agy Gemini 3.1 Pro (High) approved the revised decoder bridge and
Windows encoder on 2026-09-29 after Codex resolved the findings against actual
C API declarations. Packetizer third argument is packet boundary, not array
capacity. PyroWave image-view wrapper expects a PLANE index and maps R8 to
COLOR internally. Prior incompatible suggested changes were rejected. We
retained the previous buffer until a replacement exists and use an explicit
GENERAL ownership acquire followed by the conversion layout transition.
This source review is not Windows CI or Quest hardware sign-off.
