# Research and implementation notes

## PyroWave

[Upstream PyroWave](https://github.com/Themaister/pyrowave) is an MIT-licensed
Vulkan intra-frame GPU codec aimed at high-bandwidth streaming. Its bandwidth
and performance claims are upstream results, not measurements of this build.
Preview 3 selectively integrates its host encoder and Android decoder into
ALVR 20.14.1. The custom protocol is `20.14.1-vibertemis-pyro.1`.
Exact sources are recorded in `native/sources.json`.

The [Galaxy XR research fork](https://github.com/Terminal-ennui/galaxy-xr-alvr-pyrowave-444)
provided MIT-licensed reference interop code. Its ALVR 20.13 assumptions,
Galaxy presets, experimental wavelets and eye-tracking settings were not
imported. This port uses upstream CDF 9/7 with FP32 math. Reference hardware
results do not establish Quest compatibility or performance.

## Foveated streaming

[Valve's external-vendor integration notes](https://github.com/ValveSoftware/openvr/wiki/Steam-Link-Integration-for-External-Vendors)
describe its Steam Link path. This build uses ALVR fixed-foveated encoding:
peripheral detail is reduced before encoding. It does not implement Valve's
gaze-driven transport. Quest 3 has no eye-tracking hardware.

## Quality and transport

The custom handshake carries requested codec, standard fallback, a bitrate
ceiling and expiring restart consent. Runtime capability checks own codec
selection. Home permits 200 Mbps; Travel selects standard codecs and starts
at 30 Mbps. Both use adaptive bitrate below their configured ceiling.
ALVR retains its tracking, controller, audio and transport paths.

Screen streaming continues to use Sunshine or
[Apollo](https://github.com/ClassicOldSong/Apollo). PyroWave applies only to
PCVR in this build, not the Moonlight Screen protocol.

Remote PCVR requires working network reachability. See
[ALVR separate-network setup](https://github.com/alvr-org/ALVR/wiki/Headset-and-ALVR-streamer-on-separate-networks).
A 50 Mbps download measurement does not establish upload, jitter, packet loss
or motion-to-photon latency. No seamless Internet broker is implemented.

## Validation boundary

Source review, Android tests and native compilation cannot establish
headset stability, latency, colour accuracy or controller behaviour. Record
those on Quest 3 / Windows 11 / RTX 4090 using TESTING.md. Microphone access
is required by the launcher; forwarding remains dashboard-controlled.
