# Artemis Qt — Steam Deck fork

Sam Elamin ported and maintains this Steam Deck-focused fork of [upstream Artemis](https://github.com/wjbeckett/artemis), originally created by William Beckett.

Artemis is an enhanced, cross-platform Moonlight client for NVIDIA GameStream-compatible [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine) hosts. This fork concentrates on a reproducible Steam Deck Flatpak, handheld reliability, precise fractional refresh metadata, and working Apple Silicon development packages.

[![Steam Deck branch build](https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml/badge.svg?branch=codex%2Fsteam-deck)](https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml?query=branch%3Acodex%2Fsteam-deck)
[![Downloads](https://img.shields.io/github/downloads/samelamin/vibertemis/total)](https://github.com/samelamin/vibertemis/releases)

## Steam Deck port

The `codex/steam-deck` branch includes:

- a tracked Flatpak manifest whose network sources are pinned to exact commits, using the KDE 6.10 runtime;
- compatible pinned builds of FFmpeg, libplacebo, SDL3, SDL2-compat, SDL2_ttf, and dav1d in the Flatpak;
- Gamescope access and its Vulkan WSI metadata, plus compatibility across the legacy and current FFmpeg/libplacebo Vulkan queue APIs;
- exact fractional-rate handling, including the Apollo milli-Hz `maxFPS` convention and a Vibepollo-consistent Moonlight `clientRefreshRateX100` field;
- selected current Moonlight reliability ports: deterministic SDL/X11 pairing, duration-bounded SDL audio queuing, D-pad combo-box changes, and controller-navigable dialog buttons; and
- CI contracts for pinned inputs, manifest and AppStream metadata, codec capabilities, linked dependencies, refresh-rate behavior, application startup, fork-owned URLs, and rolling-release publication.

The refresh wire format is protocol unit-tested, including 23.976, 29.97, 59.94, 119.88, integer fallbacks, invalid values, overflow, and whole-FPS boundary consistency. Physical Steam Deck behavior and a live stream to Vibepollo, Apollo, or Sunshine remain manual acceptance work; see the [Steam Deck setup and validation guide](docs/STEAM_DECK.md). This repository does not claim Steam Deck Verified status.

### Install or update the rolling Flatpak

The rolling prerelease exposes a stable download URL. In Steam Deck Desktop Mode, open Konsole and run:

```bash
curl -fL https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak \
  -o "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak install --user --or-update "$HOME/Downloads/artemis-steam-deck.flatpak"
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
flatpak run com.artemisdesktop.ArtemisDesktopDev
```

This is a single-file bundle, not a Flatpak repository: install each newer download with `--or-update`; `flatpak update` alone cannot discover a replacement.

The publisher replaces these rolling assets after every later successful branch build:

- [direct Flatpak](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak)
- [SHA-256 sidecar](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak.sha256)
- [atomic Flatpak-plus-checksum archive](https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz)

CI uploads the Flatpak first, re-downloads it, and compares its digest before publishing the checksum companions. Two separately downloaded rolling files can still straddle a replacement. If direct-file verification reports a mismatch while publication is in progress, retry both downloads; for one atomic snapshot, use the archive:

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

Current macOS 26 builds use Qt 6.11.1 because it contains the upstream fix for the Apple Clang `__yield` failure in Qt 6.8.3. Other CI platforms remain on Qt 6.8.3, and application code must not depend on Qt 6.11-only APIs.

The package script builds the current machine architecture by default and derives the artifact name from `lipo`. The locally exercised package path produced a native `arm64` application and unsigned development DMG, verified the bundle architecture and dynamic libraries, mounted the DMG, and smoke-launched the deployed Cocoa application. It does not prove a real host stream. CI uses names such as `artemis-macos-arm64-VERSION`; it says `universal` only when both `arm64` and `x86_64` slices are present.

Development DMGs are not Developer ID signed or notarized unless credentials are supplied, so Gatekeeper may require **Open** from Finder's context menu or approval in **System Settings > Privacy & Security**. Exact Qt installation, Homebrew dependencies, package commands, verification levels, and local toolchain caveats are in the [macOS development build guide](docs/DEVELOPMENT.md#macos-development-builds).

## Features

Artemis retains Moonlight's hardware-accelerated H.264, HEVC, and AV1 streaming support, HDR and surround audio, multitouch, up to 16 controllers, force feedback and motion controls, host keyboard shortcuts, and desktop/game mouse modes. Artemis adds:

- clipboard synchronization and server commands;
- OTP pairing;
- an in-stream quick menu;
- fractional refresh rates and resolution scaling;
- Apollo virtual-display control;
- UUID-based application launching with legacy ID fallback;
- Apollo permission viewing; and
- Artemis branding and `art://` artwork compatibility.

### Keyboard and gamepad shortcuts

The quick menu is `Ctrl + Alt + Shift + \` on a keyboard or `Select + L1 + R1 + Y` on a controller. Other useful shortcuts are:

| Action | Keyboard | Gamepad |
| --- | --- | --- |
| Quit stream | `Ctrl + Alt + Shift + Q` | `Start + Select + L1 + R1` |
| Quit stream and Artemis | `Ctrl + Alt + Shift + E` | — |
| Performance statistics | `Ctrl + Alt + Shift + S` | `Select + L1 + R1 + X` |
| Toggle fullscreen | `Ctrl + Alt + Shift + X` | — |
| Toggle mouse capture | `Ctrl + Alt + Shift + M` | Long-press `Start` for mouse emulation |
| Toggle input capture | `Ctrl + Alt + Shift + Z` | — |
| Paste clipboard text | `Ctrl + Alt + Shift + V` | — |

## Downloads and source builds

Fork-owned development downloads are on [GitHub Releases](https://github.com/samelamin/vibertemis/releases). The branch workflow also retains versioned Actions artifacts for build traceability.

Clone with submodules:

```bash
git clone https://github.com/samelamin/vibertemis.git
cd artemis
git submodule update --init --recursive
```

The common CI framework is Qt 6.8.3; current macOS 26 builds use Qt 6.11.1 as documented above. Other requirements include FFmpeg, OpenSSL, Opus, SDL2, and SDL2_ttf. A basic Unix release build is:

```bash
qmake6 artemis.pro CONFIG+=release
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.logicalcpu)"
```

Platform-specific setup, including the reproducible macOS DMG flow, is documented in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Flatpak packaging and validation live under [`packaging/flatpak`](packaging/flatpak).

## Contributing and support

Report fork issues in the [samelamin/vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues). Include the commit or bundle name, client platform, host software/version, requested stream settings, logs, and whether the issue reproduces outside a live stream.

Contributions should use a focused branch, include regression coverage where practical, and update documentation when behavior or packaging changes.

## Provenance and credits

This work keeps the project's existing copyright notices and GPLv3 license intact. Fork maintenance and porting credit does not replace the authorship of the software it builds upon.

- William Beckett created and maintained upstream Artemis Qt.
- The [Moonlight Team](https://github.com/moonlight-stream) created [Moonlight Qt](https://github.com/moonlight-stream/moonlight-qt) and the core streaming implementation on which Artemis is based.
- [ClassicOldSong](https://github.com/ClassicOldSong) created [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) and [Apollo](https://github.com/ClassicOldSong/Apollo), which inspired and serve many of Artemis's enhanced client features.
- [LizardByte](https://github.com/LizardByte) maintains [Sunshine](https://github.com/LizardByte/Sunshine), another compatible open-source host.
- The `clientRefreshRateX100` compatibility improvement was informed by a focused comparison with [Nonary/Vibepollo](https://github.com/Nonary/Vibepollo); host-only features were deliberately not copied into this client.
- All original and subsequent contributors remain credited through the repository history and source headers.

Artemis is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
