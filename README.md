# Vibertemis

An AI-enhanced Artemis/Moonlight client built for Steam Deck and Vibepollo-compatible streaming.

Sam Elamin maintains Vibertemis, a Steam Deck-focused fork of [upstream Artemis](https://github.com/wjbeckett/artemis), originally created by William Beckett.

Vibertemis connects to GameStream-compatible [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine) hosts and implements the precise refresh-request fields that Vibepollo checks. The public name is new, but established Artemis IDs, settings keys, executable names, URL handling, and rolling-download filenames stay in place so existing users keep their settings, pairings, shortcuts, and scripts.

[![Steam Deck branch build](https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml/badge.svg?branch=codex%2Fsteam-deck)](https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml?query=branch%3Acodex%2Fsteam-deck)
[![Downloads](https://img.shields.io/github/downloads/samelamin/vibertemis/total)](https://github.com/samelamin/vibertemis/releases)

## Why Vibertemis?

These are reports from upstream users, not claims that upstream Artemis fails for everyone. The fork status column is limited to work covered by source contracts, package checks, unit tests, or documented local verification.

| Area | Reported upstream pain points | Verified fork status |
| --- | --- | --- |
| Steam Deck installation | [#27: Flatpak not working](https://github.com/wjbeckett/artemis/issues/27), [#53: which Steam Deck build?](https://github.com/wjbeckett/artemis/issues/53), [#58: Ubuntu install issues](https://github.com/wjbeckett/artemis/issues/58) | Pinned rolling Flatpak plus explicit install/update instructions, dependency checks, and an offscreen startup check. |
| Build reliability | [#48: build issues](https://github.com/wjbeckett/artemis/issues/48) | Fork-owned workflows, pinned inputs, CI contracts, and versioned/rolling artifacts. |
| Handheld streaming | Steam Deck-focused maintenance | Gamescope/Vulkan integration, exact fractional refresh and Vibepollo `clientRefreshRateX100` metadata, duration-bounded audio queueing, and controller-first settings/dialog navigation. |
| Apple Silicon and project maintenance | [#63: macOS](https://github.com/wjbeckett/artemis/issues/63) | Native arm64 build and DMG verification are wired into the fork, with the current local-package and CI limits disclosed below. |
| Maintenance activity | [#49: maintenance question](https://github.com/wjbeckett/artemis/issues/49) | Active fork tracker, rolling beta channel, and a documented AI-assisted workflow with human release ownership. |

## AI-enhanced development

Sam directs priorities, reviews the work, owns technical and product decisions, and is responsible for every Vibertemis release. Codex assists with design, implementation, regression tests, packaging, documentation, and review. Independent agent reviewers reviewed this launch work.

Claude may be used as an additional second opinion when it is authenticated and available. It did **not** review this exact launch because the local Claude OAuth session had expired; Vibertemis does not claim otherwise.

The name follows the convention used by [Nonary/Vibepollo](https://github.com/Nonary/Vibepollo#readme): an explicit nod to AI-assisted maintenance alongside the Artemis lineage. The inherited Artemis and Moonlight codebase was not all AI-generated. Its authorship, copyright notices, GPL history, and contributor credit remain intact.

## Steam Deck port

The `codex/steam-deck` branch includes:

- a tracked Flatpak manifest whose network sources are pinned to exact commits, using the KDE 6.10 runtime;
- compatible pinned Flatpak builds of FFmpeg, libplacebo, SDL3, SDL2-compat, SDL2_ttf, and dav1d;
- Gamescope access and Vulkan WSI metadata, plus compatibility across the pinned FFmpeg/libplacebo Vulkan queue APIs;
- exact fractional-rate handling for Apollo's milli-Hz `maxFPS` convention and Vibepollo's Moonlight `clientRefreshRateX100` field;
- selected Moonlight reliability work: deterministic SDL/X11 pairing, duration-bounded SDL audio queueing, D-pad combo-box changes, and controller-navigable dialog buttons; and
- CI contracts for pinned inputs, manifest/AppStream metadata, codec capabilities, linked dependencies, refresh-rate behavior, startup, fork-owned URLs, and rolling publication.

The refresh wire format is unit-tested for 23.976, 29.97, 59.94, 119.88, integer fallbacks, invalid values, overflow, and whole-FPS boundary consistency. Physical Steam Deck behavior and live streams still need beta evidence; see the [Steam Deck setup and validation guide](docs/STEAM_DECK.md).

### Install or update the rolling Flatpak

In Steam Deck Desktop Mode, open Konsole and run:

```bash
curl -fL https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak \
  -o "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak install --user --or-update "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
flatpak run com.artemisdesktop.ArtemisDesktopDev
```

The app appears publicly as **Vibertemis**, but the Flatpak ID remains `com.artemisdesktop.ArtemisDesktopDev` and its internal command/executable remains `artemis`. The `artemis-steam-deck.*` asset names are also deliberate compatibility names. Keeping them protects existing installs, Steam shortcuts, direct-download scripts, and automation; it is not incomplete rebranding.

This is a single-file bundle, not a Flatpak repository. Install each newer download with `--or-update`; `flatpak update` alone cannot discover a replacement. The rolling prerelease replaces these stable URLs after a later branch build succeeds:

- [direct Flatpak](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak)
- [SHA-256 sidecar](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak.sha256)
- [atomic Flatpak-plus-checksum archive](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz)

CI uploads the Flatpak first, re-downloads it, and compares its digest before publishing the checksum companions. Two separately downloaded files can straddle a rolling replacement. Retry both after a mismatch, or use the atomic archive:

```bash
curl -fL https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz \
  -o "$HOME/Downloads/artemis-steam-deck-bundle.tar.gz"
mkdir -p "$HOME/Downloads/artemis-steam-deck-bundle"
tar -xzf "$HOME/Downloads/artemis-steam-deck-bundle.tar.gz" \
  -C "$HOME/Downloads/artemis-steam-deck-bundle"
cd "$HOME/Downloads/artemis-steam-deck-bundle"
sha256sum -c artemis-steam-deck.flatpak.sha256
flatpak install --user --or-update artemis-steam-deck.flatpak
```

The rolling tag is a development prerelease. Review the application ID and Flatpak permissions before installing it.

## macOS development build

Current macOS 26 builds use Qt 6.11.1, which contains the upstream fix for the Apple Clang `__yield` failure in Qt 6.8.3. Other CI platforms remain on Qt 6.8.3, and application code must not depend on Qt 6.11-only APIs.

The package script builds the current machine architecture by default, verifies the application and mounted DMG, and derives the artifact architecture from `lipo`. CI package names use `vibertemis-macos-arm64-VERSION` for a native Apple Silicon build and say `universal` only when both `arm64` and `x86_64` slices are present. Development DMGs are unsigned and unnotarized unless credentials are supplied, so Gatekeeper may require **Open** from Finder's context menu or approval in **System Settings > Privacy & Security**.

Exact dependencies, package commands, verification levels, and the current local toolchain caveat are in the [macOS development build guide](docs/DEVELOPMENT.md#macos-development-builds).

## Features

Building on Artemis and Moonlight, Vibertemis includes hardware-accelerated H.264, HEVC, and AV1 streaming paths, HDR and surround audio, multitouch, up to 16 controllers, force feedback and motion controls, host keyboard shortcuts, and desktop/game mouse modes. Artemis enhancements retained in Vibertemis include:

- clipboard synchronization and server commands;
- OTP pairing;
- an in-stream Quick Menu;
- fractional refresh rates and resolution scaling;
- Apollo virtual-display control;
- UUID-based application launching with legacy ID fallback;
- Apollo permission viewing; and
- `art://` artwork compatibility.

### Keyboard and gamepad shortcuts

Open the Quick Menu with `Ctrl + Alt + Shift + \` or `Select + L1 + R1 + Y`.

| Action | Keyboard | Gamepad |
| --- | --- | --- |
| Quit stream | `Ctrl + Alt + Shift + Q` | `Start + Select + L1 + R1` |
| Quit stream and Vibertemis | `Ctrl + Alt + Shift + E` | — |
| Performance statistics | `Ctrl + Alt + Shift + S` | `Select + L1 + R1 + X` |
| Toggle fullscreen | `Ctrl + Alt + Shift + X` | — |
| Toggle mouse capture | `Ctrl + Alt + Shift + M` | Long-press `Start` for mouse emulation |
| Toggle input capture | `Ctrl + Alt + Shift + Z` | — |
| Paste clipboard text | `Ctrl + Alt + Shift + V` | — |

## Beta testers wanted

The beta needs real-device results more than broad “works for me” reports. The highest-value scenarios are:

- Steam Deck LCD and OLED in both Desktop and Gaming Mode;
- Vibepollo, Apollo, and Sunshine hosts;
- 23.976, 29.97, 59.94, 90, and 119.88 Hz/FPS requests;
- H.264, HEVC, and AV1, with both SDR and HDR where the full path supports it;
- audio latency, stereo/surround selection, and long-session queue behavior;
- controller navigation through settings, dialogs, and the in-stream Quick Menu;
- suspend/resume, network interruption, reconnect, and host restart; and
- macOS arm64 launch, pairing, and a real host stream.

Follow the [Steam Deck setup and validation guide](docs/STEAM_DECK.md), then report results in the [Vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues). Include the exact build/commit, client hardware and mode, host software/version, requested resolution/rate/codec/HDR/audio settings, relevant client and host logs, and repeatable reproduction steps. Review and redact logs before posting them.

## Known validation gaps

- Vibertemis does not have or claim Steam Deck Verified status.
- CI codec probes confirm packaged decoder capabilities; they do not prove physical Steam Deck hardware AV1 decoding.
- HDR requires a real end-to-end HDR host, encoder, network, decoder, Gamescope/display, and content test.
- Real streaming against Vibepollo, Apollo, and Sunshine still needs beta testers; unit and package checks do not establish live-host compatibility by themselves.
- Current macOS development packages are unsigned and unnotarized.
- The earlier Artemis-named macOS package path produced and verified a native arm64 app and DMG. Re-running the renamed Vibertemis package locally is currently blocked because this host's Command Line Tools installation cannot find the standard C++ `type_traits` header, and renamed-package CI was still pending when this README was written. The build badge at the top of this README is authoritative for current CI status and supersedes that point-in-time note.

## Roadmap

The first headline feature planned after the beta is per-host settings profiles, informed by upstream requests [#54](https://github.com/wjbeckett/artemis/issues/54) and [#67](https://github.com/wjbeckett/artemis/issues/67). It is not implemented yet.

Other candidates—not commitments or implemented features—are clipboard file sync and drag-and-drop transfer ([#51](https://github.com/wjbeckett/artemis/issues/51), [#52](https://github.com/wjbeckett/artemis/issues/52)), Windows frame pacing ([#50](https://github.com/wjbeckett/artemis/issues/50)), and Steam Link builds ([#59](https://github.com/wjbeckett/artemis/issues/59)).

## Downloads and source builds

Fork-owned development downloads are on [GitHub Releases](https://github.com/samelamin/vibertemis/releases). The branch workflow also retains versioned Actions artifacts for traceability.

Clone with submodules:

```bash
git clone https://github.com/samelamin/vibertemis.git
cd vibertemis
git submodule update --init --recursive
```

The common CI framework is Qt 6.8.3; current macOS 26 builds use Qt 6.11.1 as documented above. Other requirements include FFmpeg, OpenSSL, Opus, SDL2, and SDL2_ttf. The legacy project and executable names remain part of the compatibility contract. A basic Unix release build is:

```bash
qmake6 artemis.pro CONFIG+=release
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.logicalcpu)"
```

Platform-specific setup is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Flatpak packaging and validation live under [`packaging/flatpak`](packaging/flatpak).

## Contributing and support

Report bugs, hardware results, and focused feature proposals in the [Vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues). Contributions should use a focused branch, include regression coverage where practical, and update documentation when behavior or packaging changes.

## Provenance and credits

Vibertemis keeps the existing copyright notices and GPLv3 license intact. Fork maintenance and AI assistance do not replace the authorship of the software it builds upon.

- William Beckett created and maintained upstream Artemis Qt.
- The [Moonlight Team](https://github.com/moonlight-stream) created [Moonlight Qt](https://github.com/moonlight-stream/moonlight-qt) and the core streaming implementation on which Artemis is based.
- [ClassicOldSong](https://github.com/ClassicOldSong) created [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) and [Apollo](https://github.com/ClassicOldSong/Apollo), which inspired and serve many Artemis enhanced-client features.
- [LizardByte](https://github.com/LizardByte) maintains [Sunshine](https://github.com/LizardByte/Sunshine), another compatible open-source host.
- The `clientRefreshRateX100` compatibility improvement was informed by a focused comparison with [Nonary/Vibepollo](https://github.com/Nonary/Vibepollo); host-only features were deliberately not copied into this client.
- All original and subsequent contributors remain credited through the repository history and source headers.

Vibertemis is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
