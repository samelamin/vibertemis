# Steam Deck setup and validation

This guide is for the development Flatpak with application ID
`com.artemisdesktop.ArtemisDesktopDev`. It is a sideloaded test build, not the
Flathub Moonlight application and not a claim of Steam Deck Verified status.

> **Hardware status:** CI can inspect the package and exercise software-only
> contracts, but it has no Steam Deck GPU, display, controller, or Gamescope
> session. Every item in the [hardware acceptance matrix](#hardware-acceptance-matrix)
> starts **Not run** until somebody records results from a real LCD or OLED Deck.

## Install or update the bundle

Switch to Desktop Mode, open Konsole, and download the current verified build
from the rolling prerelease:

```bash
curl -fL \
  https://github.com/samelamin/artemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak \
  -o "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak install --user --or-update "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
flatpak run com.artemisdesktop.ArtemisDesktopDev
```

Review the application ID and requested permissions before accepting the
install. If Flatpak asks for a runtime source, enable Flathub in Discover or add
the Flathub remote first. A single-file bundle is not connected to an update
repository, so install each newer artifact with the same `--or-update` command;
`flatpak update` alone cannot discover a newer Artemis bundle. Download the
rolling file again and repeat the same `flatpak install --user --or-update`
command. Flatpak documents these constraints in its
[single-file bundle guide][flatpak-bundles] and the
[`flatpak install` reference][flatpak-install].

The rolling publisher first uploads the direct Flatpak, re-downloads it, and
compares its digest. It only then publishes the
[`artemis-steam-deck.flatpak.sha256` sidecar][deck-flatpak-sha] and the
[`artemis-steam-deck-bundle.tar.gz` atomic archive][deck-flatpak-archive]. A
later successful branch build replaces all three assets. Because two separate
downloads can straddle that replacement, retry both files if a direct Flatpak
and sidecar mismatch while publication is in progress. For an atomic
Flatpak-plus-checksum snapshot, download and verify the archive instead:

```bash
curl -fL \
  https://github.com/samelamin/artemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz \
  -o "$HOME/Downloads/artemis-steam-deck-bundle.tar.gz"
mkdir -p "$HOME/Downloads/artemis-steam-deck-bundle"
tar -xzf "$HOME/Downloads/artemis-steam-deck-bundle.tar.gz" \
  -C "$HOME/Downloads/artemis-steam-deck-bundle"
cd "$HOME/Downloads/artemis-steam-deck-bundle"
sha256sum -c artemis-steam-deck.flatpak.sha256
flatpak install --user --or-update artemis-steam-deck.flatpak
```

The development ID is intentionally distinct from other Artemis or Moonlight
installations. Do not uninstall another app to install this bundle.

## Add Artemis to Steam

1. In Desktop Mode, launch Steam and choose **Games > Add a Non-Steam Game to
   My Library** ([Valve's instructions][valve-non-steam]).
2. Select **Artemis** and add it. Restart Steam once if the newly installed
   desktop entry is not listed yet.
3. Return to Gaming Mode and launch Artemis from **Library > Non-Steam**.
4. Open the entry's controller settings and start with a standard gamepad
   template. Confirm D-pad, sticks, `A`/`B`, and the Steam on-screen keyboard
   before pairing or changing settings.

Use the application launcher or `flatpak run` command above in Desktop Mode.
Gaming Mode is the primary Gamescope and OLED HDR test path; Desktop Mode remains
important for installation, logs, and an SDR launch/input check.

Updating the bundle does not change its application ID, so the Steam shortcut
normally does not need to be added again.

## Display and stream matching

Valve specifies a 1280x800 panel capped at 60 Hz for Steam Deck LCD and a
1280x800 HDR panel up to 90 Hz for Steam Deck OLED ([specifications][deck-specs],
[support note][deck-refresh]). Use these as starting points, not evidence that a
particular stream path has passed:

| Use case | SteamOS display mode | Artemis stream request | Host target |
| --- | --- | --- | --- |
| LCD handheld | 1280x800 at 60 Hz | 1280x800 at 60 FPS | 1280x800 at 60 Hz/FPS |
| OLED handheld, SDR | 1280x800 at 90 Hz | 1280x800 at 90 FPS | 1280x800 at 90 Hz/FPS |
| OLED handheld, HDR | 1280x800 at 90 Hz, HDR enabled | 1280x800 at 90 FPS, HDR enabled | Matching HDR-capable display at 90 Hz/FPS |
| Docked | Select a stable external mode in SteamOS | Exact external resolution and rate | Exact client request |
| 59.94 Hz content/display | 59.94 Hz where SteamOS exposes it | Custom rate `59.94` | Exact 59.94 Hz/FPS, not rounded 59 or 60 |

For a docked display, select its resolution and refresh rate in SteamOS before
starting Artemis, then request the same values in Artemis and on the host. If the
link is unstable, Valve recommends trying a lower mode and checking the dock,
cable, input, and display capabilities ([docking guide][deck-docking]). Do not
assume that a nominally capable cable supports every HDR/high-refresh mode.

Apollo can create a virtual display from the client's requested resolution and
rate. If automatic matching rounds or selects the wrong mode, configure the
client-specific value in Apollo's PIN page, for example
`1280x800x59.94`, reconnect, and verify the resulting host mode. Apollo documents
this exact fractional override in [Display Mode Override][apollo-display-mode].
Use a host frame limiter only after the client, stream, virtual display, and game
targets agree; Apollo's [stutter guidance][apollo-stutter] explains why a mismatch
can look like network jitter.

## Vibepollo/Apollo refresh metadata acceptance

> **Current status:** The `maxFPS` and `clientRefreshRateX100` protocol mapping is
> unit-tested. A stream to a real Vibepollo host has not yet been verified, so do
> not describe this as a verified Vibepollo stream path until the checklist below
> is completed and its evidence is recorded.

Capture synchronized client and host logs for each test case. For the fractional
case, set a custom rate of `59.94` Hz. The unit-tested wire mapping is
`maxFPS=59940` and `clientRefreshRateX100=5994`; the whole-FPS boundary clamping
that keeps those fields consistent is also unit-tested.

Vibepollo logs the RTSP request payload and these attributes only at verbose
level. Record the existing host log level, temporarily set
`min_log_level = verbose` in the Vibepollo configuration, and restart or
reconfigure Vibepollo as required by that installation before testing. Capture
the payload lines for `x-nv-video[0].maxFPS` and
`x-nv-video[0].clientRefreshRateX100`. After the tests, restore the previous log
level and restart or reconfigure Vibepollo again. Verbose logs are noisy and may
contain sensitive host, client, application, or network details, so review and
redact them before sharing.

- [ ] Confirm the Artemis client log contains `Stream refresh metadata` with the
  expected `maxFPS` and `clientRefreshRateX100` values.
- [ ] Confirm the Vibepollo/Apollo host log reports the same two fields from the
  incoming stream request.
- [ ] Confirm Vibepollo does not emit its `clientRefreshRateX100`/`maxFPS`
  mismatch warning.
- [ ] Start the stream and sustain a moving scene for 10 minutes; confirm the
  effective rate observed by the host and client remains at the requested 59.94
  Hz/FPS, allowing only normal measurement tolerance rather than rounding the
  target to 59 or 60.
- [ ] Repeat with an integer rate such as 60 FPS; confirm both sides report
  `maxFPS=60` and `clientRefreshRateX100=6000`, and the stream starts and remains
  usable.
- [ ] Arrange an invalid persisted custom rate with an integer fallback such as
  60 FPS; confirm both sides receive the matching fallback values `maxFPS=60`
  and `clientRefreshRateX100=6000`, with no mismatch warning, and the stream
  starts normally.

The settings UI rejects invalid custom rates, so reproduce the final case by
temporarily editing the stopped Flatpak's persisted configuration. First stop
the development application and locate its `Artemis.conf`; the organization
subdirectory can vary, so use `find` rather than assuming its exact path:

```bash
flatpak kill com.artemisdesktop.ArtemisDesktopDev 2>/dev/null || true
find "$HOME/.var/app/com.artemisdesktop.ArtemisDesktopDev/config" \
  -type f -name 'Artemis.conf' -print
```

Copy the single path printed by `find` into `ARTEMIS_CONF`, verify it, and make a
backup. If more than one path is printed, identify the file updated by the
current development application instead of guessing.

```bash
ARTEMIS_CONF='/absolute/path/printed/by/find/Artemis.conf'
test -f "$ARTEMIS_CONF"
cp -- "$ARTEMIS_CONF" "$ARTEMIS_CONF.before-vibepollo-test"
kwrite "$ARTEMIS_CONF"
```

In the existing `[General]` section, change or add these keys, save the file,
then relaunch Artemis and perform the invalid-fallback checklist item:

```ini
[General]
fractionalrefreshrate=true
customrefreshrate=9
fps=60
```

```bash
flatpak run com.artemisdesktop.ArtemisDesktopDev
```

After capturing the result, stop Artemis before restoring the exact backup. The
backup is intentionally retained rather than deleted.

```bash
flatpak kill com.artemisdesktop.ArtemisDesktopDev 2>/dev/null || true
cp -- "$ARTEMIS_CONF.before-vibepollo-test" "$ARTEMIS_CONF"
```

Record the Artemis commit, Vibepollo/Apollo version, requested and effective
rates, relevant log excerpts, and stream duration. This client change does not
add Vibepollo runtime bitrate/ABR control, host virtual-display recovery, WGC,
HDR peak control, WebRTC, or server UI features to Artemis.

## Codec selection and safe fallback

Start with **Video codec: Automatic (Recommended)** and **Video decoder: Auto**.
The application and host should negotiate a mutually supported path.

| Choice | When to use it | Fallback |
| --- | --- | --- |
| H.264 | Compatibility baseline and first troubleshooting codec; SDR only | Lower resolution/FPS and bitrate |
| HEVC (H.265) | Test when Auto works; HEVC Main10 is required for the HEVC HDR path | Disable HDR, return to Auto, then H.264 |
| AV1 (Experimental) | Test only with Apollo/Sunshine and a host encoder that advertises AV1 | Return to Auto, then HEVC or H.264 |

Moonlight documents that AV1 requires Sunshine and a supported host GPU
([Moonlight PC features][moonlight-qt]). The Artemis package probe only confirms
that its FFmpeg decoders publish VAAPI and Vulkan configurations. It does **not**
create a device, decode a frame, prove hardware acceleration on Steam Deck, or
prove that the host can encode the codec. Confirm the selected codec in the
statistics overlay and confirm lines such as `Using Vulkan video decoding` or a
selected VAAPI path in the Artemis log before recording a hardware result.

If a stream fails to start, shows corruption, or falls back to slow software
decoding, retest in this order: SDR, Auto codec/decoder, H.264, 1280x720 at 30
FPS, and a lower bitrate. Change one item at a time.

## OLED HDR prerequisites and calibration boundary

Only mark OLED HDR as passed when the complete path is HDR-capable:

- Steam Deck OLED, or an HDR-capable external display;
- Gaming Mode/Gamescope with HDR enabled for the Artemis shortcut;
- an HDR-capable host display or EDID/virtual display;
- host HEVC Main10 or AV1 Main10 encoding support;
- a usable 10-bit client decode and Vulkan presentation path; and
- matching host/client resolution to avoid scaling artifacts.

Moonlight's [HDR requirements][moonlight-setup] describe the host display/EDID,
Main10, and resolution-matching requirements. Moonlight's Linux/Steam Deck HDR
support is implemented through the Vulkan renderer ([release notes][moonlight-releases]).
Those upstream capabilities do not prove this Artemis bundle works on a Deck.

Artemis transports and presents the HDR stream; it does not calibrate the host,
game, Deck panel, TV, dock, or cable. Use SteamOS/display and in-game calibration
controls with a known HDR test scene. Record clipping, raised blacks, banding,
washed-out color, or unexpected SDR tone mapping as failures rather than
publishing a universal brightness value. Treat Desktop Mode HDR as exploratory,
not a passed requirement; use Desktop Mode SDR as the supported comparison.

## Frame pacing and latency

- **V-Sync + Frame pacing:** best first choice for consistent motion. Early
  frames may wait in the queue, trading some latency for smoother cadence.
- **V-Sync on, Frame pacing off:** keeps tear-free presentation but removes the
  extra pacing delay; compare when queue delay or periodic stutter is high.
- **V-Sync off:** lowest presentation latency, but visible tearing may occur;
  compositor behavior such as Gamescope can affect the result. Artemis disables
  the frame-pacing control when V-Sync is off.

Test one setting at a time in a repeatable moving scene. At 60, 90, and 59.94
FPS, one frame interval is about 16.67 ms, 11.11 ms, and 16.68 ms respectively.
A stable frame queue delay below one interval is a useful target, not a guarantee.
The statistics do not include every compositor, display, or input delay; Moonlight
documents that boundary in its [statistics FAQ][moonlight-faq].

## Network and bitrate

Follow Moonlight's supported baseline: wire the host PC to the router; use
Ethernet for a docked Deck when practical, otherwise use a strong 5 GHz Wi-Fi 5
or 5/6 GHz Wi-Fi 6/6E connection ([setup guide][moonlight-setup]). Avoid 2.4 GHz
Wi-Fi and powerline links for acceptance testing.

Start with Artemis's default bitrate for the selected resolution and FPS. Raise
it only after the overlay remains stable; lower it first when network drops,
jitter, or latency rises. There is no single correct bitrate for every AP,
distance, display mode, or scene. For Internet streaming, Moonlight recommends a
bitrate at least 1 Mbps below the measured host upload capacity
([FAQ][moonlight-faq]). Its [troubleshooting guide][moonlight-troubleshooting]
also recommends lowering bitrate to distinguish bandwidth trouble.

## Read the statistics overlay

Enable it in Settings or while streaming with
`Select + L1 + R1 + X` (keyboard: `Ctrl + Alt + Shift + S`). The quick menu is
`Select + L1 + R1 + Y`.

| Field | What to look for |
| --- | --- |
| Video stream | Requested resolution/rate and negotiated H.264, HEVC, or AV1 variant |
| Incoming / decoding / rendering FPS | Similar values during moving content; a static Sunshine scene may legitimately send fewer frames |
| Host processing latency | Host capture/encode contribution; investigate the host if it spikes while network values stay stable |
| Network drops | Very close to 0%; sustained loss points to link capacity or reliability |
| Jitter drops | Late/irregular arrival; check Wi-Fi contention and mismatched pacing before blaming decode |
| Network latency and variance | Stable is more useful than one isolated low sample |
| Decoding time | Compare with the frame interval; large or unstable values warrant codec/decoder fallback testing |
| Frame queue delay | Waiting after decode, commonly from pacing/V-Sync; normally below one frame interval |
| Rendering time | Includes monitor V-Sync wait, so V-Sync can add roughly one refresh interval |

Moonlight defines these fields and caveats in the [statistics FAQ][moonlight-faq].

## Troubleshooting and useful logs

Before reporting a problem, update SteamOS, restart the Deck, reproduce once in
Gaming Mode and once in Desktop Mode SDR, and capture:

- Deck model, SteamOS version, mode (Gaming/Desktop), handheld/docked, and
  external display/dock/cable details;
- Artemis bundle filename and `flatpak info com.artemisdesktop.ArtemisDesktopDev`;
- host software/version, GPU/driver, physical or Apollo virtual display mode;
- requested resolution, exact refresh/FPS, bitrate, codec/decoder, renderer,
  V-Sync/frame pacing, and HDR state;
- a statistics-overlay screenshot during motion; and
- the Artemis log from the same reproduction.

On Linux, Artemis writes diagnostic output to standard error. Capture it from a
Desktop Mode terminal without adding sandbox permissions:

```bash
mkdir -p "$HOME/artemis-logs"
set -o pipefail
flatpak run com.artemisdesktop.ArtemisDesktopDev 2>&1 \
  | tee "$HOME/artemis-logs/artemis-$(date +%Y%m%d-%H%M%S).log"
```

Reproduce the issue, then quit Artemis. `pipefail` preserves a non-zero Artemis
exit status instead of hiding it behind a successful `tee`. Attach the newest
file from `~/artemis-logs/`. Review logs before sharing because they can contain
hostnames, IP addresses, application names, and other local details.

## What CI verifies—and what it cannot

When the Flatpak CI job is green, it verifies these software contracts:

- the tracked manifest parses, required sources are pinned, and the application
  ID/desktop/AppStream metadata agree;
- the bundle contains the Artemis executable and required shared libraries;
- FFmpeg's H.264, HEVC, and AV1 decoders expose the required VAAPI/Vulkan
  configuration entries;
- the application survives a bounded headless startup check; and
- fractional input parsing, including `59.94`, is covered by automated tests.

CI does **not** verify a real VAAPI/Vulkan device, decoded hardware frames,
Gamescope launch or mode switching, LCD/OLED output, HDR metadata or calibration,
latency, gamepad navigation, suspend/resume, or network-loss recovery. Only the
matrix below may support those claims.

## Hardware acceptance matrix

Record the bundle commit, SteamOS version, host details, selected settings,
overlay screenshot, and log for each row. `Not run` is the honest default; use
`Pass`, `Fail (<issue>)`, or `Blocked (<reason>)` only after a real-Deck run.

### Display, mode, and codec coverage

| ID | Deck / mode | Display | Codec / range | Rate | Expected evidence | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | LCD / Gaming | Handheld 1280x800 | H.264 SDR | 60 | Launch, correct overlay mode, stable 10-minute moving stream | Not run |
| D2 | LCD / Desktop | Handheld 1280x800 | HEVC SDR | 60 | Launch, input, codec and decode path logged | Not run |
| D3 | LCD / Gaming | Docked, matched native mode | AV1 SDR | 59.94 | Exact client/host rate; clean fallback if AV1 is unavailable | Not run |
| D4 | LCD / Desktop | Docked, matched native mode | H.264 SDR | 60 | Window/fullscreen, external input/audio, clean exit | Not run |
| D5 | OLED / Gaming | Handheld 1280x800 | H.264 SDR | 60 | SDR baseline and overlay cadence | Not run |
| D6 | OLED / Gaming | Handheld 1280x800 | HEVC Main10 HDR | 90 | HDR mode, no clipping/washed color, decode/presentation path logged | Not run |
| D7 | OLED / Gaming | Handheld 1280x800 | AV1 SDR, then AV1 Main10 HDR if advertised | 90 | Negotiated codec/range and safe fallback recorded separately | Not run |
| D8 | OLED / Gaming | Docked HDR display, matched mode | HEVC Main10 HDR | 59.94 | End-to-end HDR and exact host/client rate | Not run |
| D9 | OLED / Desktop | Handheld 1280x800 | HEVC SDR | 90 | SDR launch/input comparison; no Desktop HDR claim | Not run |
| D10 | OLED / Desktop | Docked, matched native mode | AV1 SDR | 60 or display maximum | Codec/decode path, cadence, window/fullscreen | Not run |

Where the display exposes all three rates, repeat a moving-scene cadence check at
60, 90 (OLED or capable external display only), and 59.94 Hz. Never mark an
unsupported panel/display rate as failed; record it as blocked with its mode list.

### Interaction and recovery coverage

| ID | Applies to | Procedure | Pass condition | Status |
| --- | --- | --- | --- | --- |
| R1 | LCD + OLED, Gaming + Desktop | Navigate PC/app/settings UI using only Deck controls; open/close quick menu and stats overlay | Focus remains visible; controls and both overlays respond | Not run |
| R2 | LCD + OLED, handheld + docked | Suspend for 30 seconds during a stream, then resume | No Deck lockup; session resumes or terminates clearly and a new stream starts | Not run |
| R3 | LCD + OLED, Wi-Fi | Disable Wi-Fi for 10 seconds during a stream, then restore it | No application/Deck crash; failure is clear and reconnect succeeds | Not run |
| R4 | LCD + OLED, Gaming + Desktop | Quit and relaunch after R2/R3 | Controller, display mode, pairing, and settings remain usable | Not run |
| R5 | OLED Gaming HDR, handheld + docked | Repeat R2/R3 while HDR is active | Recovery or clean termination without stuck HDR/blank display | Not run |

## Known limitations

- This development bundle has no automatic bundle-update channel.
- AV1 presence in the package is not evidence of Steam Deck hardware decode or
  host encode support.
- HDR is experimental and requires an end-to-end compatible path; LCD handheld
  is SDR, and docked HDR depends on the external display, dock, and cable.
- Apollo/Windows/display drivers can round or reject fractional modes. Verify the
  actual host mode instead of trusting the requested value.
- Gaming Mode and Desktop Mode use different presentation environments; a pass
  in one does not imply a pass in the other.
- Suspend/resume and transient network recovery may terminate a session. A clear
  failure followed by a successful reconnect is acceptable; a lockup is not.

## Primary references

- [Flatpak single-file bundles][flatpak-bundles]
- [`flatpak install` command reference][flatpak-install]
- [Valve: Add Non-Steam Games][valve-non-steam]
- [Valve: Steam Deck specifications][deck-specs]
- [Valve: Steam Deck basic use and display limits][deck-refresh]
- [Valve: Docking the Steam Deck][deck-docking]
- [Moonlight setup, network, and HDR requirements][moonlight-setup]
- [Moonlight statistics and bitrate FAQ][moonlight-faq]
- [Moonlight troubleshooting][moonlight-troubleshooting]
- [Moonlight PC release notes][moonlight-releases]
- [Official Moonlight Flathub manifest][moonlight-flatpak]
- [Apollo Display Mode Override][apollo-display-mode]
- [Apollo Stuttering Clinic][apollo-stutter]

[flatpak-bundles]: https://docs.flatpak.org/en/latest/single-file-bundles.html
[flatpak-install]: https://docs.flatpak.org/en/latest/flatpak-command-reference.html#flatpak-install
[deck-flatpak-sha]: https://github.com/samelamin/artemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak.sha256
[deck-flatpak-archive]: https://github.com/samelamin/artemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz
[valve-non-steam]: https://help.steampowered.com/en/faqs/view/4B8B-9697-2338-40EC
[deck-specs]: https://www.steamdeck.com/en/tech
[deck-refresh]: https://help.steampowered.com/en/faqs/view/69E3-14AF-9764-4C28
[deck-docking]: https://help.steampowered.com/en/faqs/view/4C18-08B5-DEC9-3AF4
[moonlight-setup]: https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide
[moonlight-faq]: https://github.com/moonlight-stream/moonlight-docs/wiki/Frequently-Asked-Questions
[moonlight-troubleshooting]: https://github.com/moonlight-stream/moonlight-docs/wiki/Troubleshooting
[moonlight-qt]: https://github.com/moonlight-stream/moonlight-qt
[moonlight-releases]: https://github.com/moonlight-stream/moonlight-qt/releases
[moonlight-flatpak]: https://github.com/flathub/com.moonlight_stream.Moonlight
[apollo-display-mode]: https://github.com/ClassicOldSong/Apollo/wiki/Display-Mode-Override
[apollo-stutter]: https://github.com/ClassicOldSong/Apollo/wiki/Stuttering-Clinic
