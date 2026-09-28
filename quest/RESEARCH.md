# Research notes

Items not integrated. The screen path is stock Moonlight XR and PCVR
is stock ALVR v20.14.1.

## PyroWave

<https://github.com/Themaister/pyrowave> — MIT, Vulkan-based,
intra-only GPU encoder designed for very high bandwidth (~200+ Mbps).
Stock Moonlight and stock ALVR do not negotiate it, and no Quest
decoder is shipped. Integration requires paired host and client
codec support, a Quest decoder, and matched quality and latency
benchmarks. This preview does not ship a PyroWave toggle.

## GalaxyXR PyroWave fork

<https://github.com/Terminal-ennui/galaxy-xr-alvr-pyrowave-444> —
ALVR 20.13 (not 20.14.1). Reports a streaming experiment at
300–400 Mbps on non-Quest hardware. Not Quest-validated; not drop-in
for this preview.

## Eye-tracked foveation on Quest3

Quest 3 has no eye-tracking hardware at all. Valve Steam Frame
(<https://store.steampowered.com/hardware/steamframe>) ships with
gaze tracking; Quest 3 does not. ALVR's fixed-foveated encoding
downscales the periphery of each frame before encoding — distinct
from local-client foveated rendering.

## Apollo

<https://github.com/ClassicOldSong/Apollo> — an alternative Sunshine-
based host. Not a legacy NVIDIA GameStream client.

## ALVR v20.14.1 verified defaults

From `alvr/session/src/settings.rs`:

- Codec: H.264 (HEVC and AV1 available on the dashboard if the host
  GPU supports them).
- Refresh: 72 Hz.
- Bitrate: 30 Mbps constant (adaptive bitrate is an opt-in toggle on
  the dashboard; the v20.14.1 default is constant 30 Mbps).
- Fixed-foveated encoding: enabled.
- Microphone: off on Windows hosts by default. Voice chat is host-
  controlled.

The bundled ALVR runtime in this preview build is unmodified.
Microphone permission is required by this preview's launcher. The
bundled ALVR runtime is unmodified; its recording path can repeatedly
retry when host microphone forwarding is enabled and recording is
denied. The sample-rate query alone does not establish a
recording-permission requirement. Voice forwarding is controlled by
the ALVR dashboard (off by default on Windows, on by default on
Linux).
