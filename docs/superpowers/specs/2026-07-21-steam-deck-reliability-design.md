# Steam Deck Reliability Design

**Date:** 2026-07-21
**Branch:** `codex/steam-deck`
**Scope:** Steam Deck-specific Linux packaging, fractional refresh rates, streaming reliability, and release validation.

## Objective

Produce a reproducible Artemis Flatpak that installs and launches on Steam Deck, exposes the hardware video paths required for H.264, HEVC, and AV1, supports Gamescope and OLED HDR prerequisites, handles fractional refresh rates correctly, and is accompanied by honest build-time and hardware acceptance checks.

The implementation addresses the Steam Deck-relevant Artemis reports: #21, #23, #27, #48, #53, #58, #62, and #65. It also evaluates a small set of post-6.1 Moonlight fixes where they directly affect Steam Deck streaming and can be isolated from Moonlight's later architectural changes.

## Constraints

- Artemis has substantial fork-only development on top of Moonlight Qt 6.1.0. A wholesale rebase onto Moonlight master is out of scope.
- CI does not have a Steam Deck GPU or Gamescope session. It cannot prove real AV1 decode, HDR output, display mode switching, latency, or suspend/resume behavior.
- The Steam Deck release artifact is Flatpak. The disabled AppImage pipeline is not promoted to a second supported packaging stack without a concrete Deck use case.
- Artemis IDs, branding, executable names, Apollo integrations, and fork-specific behavior must remain intact.
- Dependencies and source revisions in the Flatpak manifest must be pinned and reproducible.

## Design

### 1. Tracked Flatpak packaging

Move the manifest out of the `dev-build.yml` heredoc into `packaging/flatpak/`. Adapt the current official Moonlight Flathub manifest to Artemis rather than carrying bespoke GPU extension directories.

The manifest will:

- use a currently supported KDE runtime after verifying Artemis compiles against it;
- preserve `com.artemisdesktop.ArtemisDesktopDev` as the Flatpak application ID for this release so existing Deck installations retain their sandboxed settings and pairings; install desktop/AppStream metadata under the same ID and document that the older `com.artemis_desktop.Artemis` metadata name is not the Flatpak identity;
- include pinned libplacebo, SDL, SDL_ttf, dav1d, FFmpeg, and Gamescope WSI inputs;
- compile FFmpeg from source with H.264, HEVC, and AV1 decoders plus VAAPI and Vulkan hardware acceleration;
- expose Gamescope's runtime socket and the devices needed by the renderer;
- clear inherited `LIBVA_DRIVER_NAME` and `LIBVA_DRIVERS_PATH` so Flatpak runtime driver extensions resolve normally;
- remove the current inconsistent `add-extensions` block and `/app/lib/gl-*` directory creation;
- avoid writable `/app` assumptions and hard-coded SteamOS host library layouts;
- use the actual `artemis.pro` project file.

The official manifest currently pairs Moonlight 6.1.0 with SDL3 and SDL2-compat, so SDL2 API usage alone does not disqualify that stack. Artemis will first test the official pinned SDL stack. It will retain native SDL2 only if an Artemis-specific incompatibility is demonstrated by the build or smoke tests.

The pinned FFmpeg/libplacebo versions make Artemis's Vulkan renderer compatibility part of packaging, not an optional later backport. For libavutil 59.34.100 and newer, `PlVkRenderer::populateQueues()` must enumerate Vulkan queue-family properties and populate `AVVulkanDeviceContext::qf[]` and `nb_qf`. Queue lock/unlock callbacks must delegate to libplacebo for the supported libavutil range instead of remaining unconditional no-ops. This compatibility work must compile against the exact manifest versions before the AV1/HDR-capable bundle is considered build-complete.

### 2. Build workflow and validation

The GitHub workflow will consume the tracked manifest and upload an installable Flatpak bundle. Local reproduction instructions will use the same file.

Automated checks will make deliberately narrow claims:

- manifest parses and all required sources are pinned;
- Flatpak build completes from a clean checkout;
- the bundle contains the Artemis executable and required shared libraries;
- a small API-linked probe calls libavcodec directly and confirms H.264, HEVC, and AV1 decoders expose the expected VAAPI/Vulkan `AVCodecHWConfig` device types;
- the application starts far enough in a headless/offscreen environment to catch loader and immediate startup failures;
- desktop metadata and application IDs are internally consistent.

These checks do not create a hardware device or claim that hardware decode or HDR works on a Deck. Device creation, selected renderer/decoder, decoded hardware frames, HDR presentation, and Gamescope behavior remain manual Deck checks.

### 3. Fractional refresh rates

`StreamingPreferences` is the authoritative store used by `SettingsView.qml` and streaming sessions. The parallel `ArtemisSettings` values are not used for this UI path and will not be expanded as part of this fix.

The current QML validator is locale-aware while `parseFloat()` is not. Parsing will move to a small C++ helper with an explicit, testable input contract. Both decimal point and decimal comma inputs will be normalized, whitespace will be handled, malformed and out-of-range values will be rejected, and conversion to Apollo's milli-Hz representation will be range-checked and rounded rather than silently truncated.

A small Qt test target will cover valid fractional inputs, locale variants, junk input, boundary values, and milli-Hz conversion. The main build must no longer delete test sources as a packaging workaround.

### 4. Selective Moonlight fixes

Potential upstream fixes are evaluated one at a time against the 6.1-based Artemis tree. A fix lands only when:

1. it addresses a Deck-observable failure or material reliability issue;
2. its dependency chain does not require the broader SDL/rendering refactors from Moonlight master;
3. it builds independently on the Artemis base;
4. an automated regression check exists where practical; and
5. it does not alter non-Deck behavior without a clear compatibility reason.

Candidate areas are decoder selection/fallback, Gamescope/display changes, frame pacing, audio queue latency, and gamepad navigation. Candidates that cannot meet the criteria are documented and omitted rather than force-ported.

### 5. Steam Deck guidance

Add a Steam Deck guide covering:

- installing and updating the Flatpak bundle;
- Gaming Mode and Desktop Mode launch behavior;
- 1280×800 handheld resolution and docked display matching;
- 60 Hz LCD and 90 Hz OLED configuration, including 59.94 Hz;
- H.264, HEVC, and AV1 selection and fallback;
- OLED HDR prerequisites and calibration boundaries;
- frame pacing choices and their latency trade-offs;
- bitrate and network recommendations based on Moonlight's current documentation;
- statistics-overlay interpretation and troubleshooting;
- known limitations and how to collect useful logs.

### 6. Hardware acceptance

Final Deck validation is a documented manual matrix:

- LCD and OLED models;
- Gaming Mode and Desktop Mode;
- handheld and docked displays;
- H.264, HEVC, and AV1;
- SDR and OLED HDR;
- 60 Hz, 90 Hz, and 59.94 Hz;
- controller navigation and the streaming overlay;
- suspend/resume;
- temporary network loss and recovery.

The release notes will distinguish completed CI checks from hardware results still awaiting a real Deck.

## Delivery sequence

1. Land the tracked Flatpak manifest and build workflow.
2. Add the parsing helper, test target, and fractional refresh fix.
3. Evaluate and land compatible upstream reliability fixes as separate commits.
4. Add the Steam Deck guide and acceptance matrix.
5. Build and inspect the release bundle in Linux CI/container tooling.
6. Run an independent code review, resolve findings, commit, and push `codex/steam-deck` to the user's fork.

## Non-goals

- Rebasing Artemis onto current Moonlight master.
- Claiming Deck hardware behavior based solely on CI.
- Maintaining the disabled AppImage dependency pipeline in this change.
- Broad Linux distribution support unrelated to Steam Deck.
- Refactoring all duplicate Artemis settings infrastructure.
