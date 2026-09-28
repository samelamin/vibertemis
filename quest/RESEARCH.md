# Research notes

Items not integrated. The screen path is stock Moonlight XR and PCVR
is stock ALVR v20.14.1.

## PyroWave

<https://github.com/Themaister/pyrowave> — MIT, Vulkan-based,
intra-only GPU encoder designed for very high bandwidth (~200+ Mbps).
Stock Moonlight and stock ALVR do not negotiate it, and no Quest
decoder is shipped. Integration requires paired host and client
codec support, a Quest decoder, and matched quality and latency
benchmarks.

## Valve PyroWave option (Quest)

<https://steamcommunity.com/groups/homestream/discussions/0/564794422009744473?ctp=2>.
Only the PyroWave option is flat-only — flat-screen desktop streaming
at roughly 5–10× the prior Link traffic. Steam Link itself already
supports Quest PCVR; this Pyro option is not a PCVR path.

## GalaxyXR PyroWave fork

<https://github.com/Terminal-ennui/galaxy-xr-alvr-pyrowave-444> —
ALVR 20.13 (not 20.14.1). Reports a streaming experiment at
300–400 Mbps. Not Quest-validated.

## Eye-tracked foveation on Quest3

Quest3 in the public SDK profile exposes no eye-tracking sensor to
third-party apps. Valve Steam Frame
(<https://store.steampowered.com/hardware/steamframe>) uses gaze;
Quest3 does not. ALVR's fixed-foveated encoding reduces peripheral
detail in the encoded stream — distinct from local-client foveated
rendering.