# Steam Deck Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a reproducible Artemis Flatpak for Steam Deck with working codec dependencies, correct Gamescope/driver integration, safe Vulkan/FFmpeg compatibility, tested fractional refresh parsing, and an installable CI artifact.

**Architecture:** A tracked Flatpak manifest becomes the single Linux/Deck package definition and the workflow only builds and verifies it. Small C++ probes/tests validate build-time codec and refresh-rate contracts, while a documented manual matrix owns claims that require real Steam Deck hardware. Moonlight fixes are applied individually only after compatibility review against the 6.1-based fork.

**Tech Stack:** Qt 6/qmake, C++17, QTest, QML, Flatpak/flatpak-builder, FFmpeg/libavcodec/libavutil, libplacebo/Vulkan, SDL, GitHub Actions.

---

### Task 1: Add manifest contract validation

**Files:**
- Create: `packaging/flatpak/validate-manifest.py`
- Create: `packaging/flatpak/tests/test_validate_manifest.py`

- [ ] **Step 1: Write failing Python unit tests**

Cover the required application ID, runtime, pinned source commits/hashes, FFmpeg H.264/HEVC/AV1 VAAPI+Vulkan flags, Gamescope socket, device access, LIBVA resets, absence of custom `add-extensions`, and local Artemis source path.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v`

Expected: FAIL because the validator and tracked manifest do not exist.

- [ ] **Step 3: Implement the smallest JSON validator**

The validator loads the manifest, walks modules recursively, rejects unpinned network sources, and reports each missing contract as a separate error. It must use only the Python standard library so it runs locally and in Actions.

- [ ] **Step 4: Re-run tests**

Expected: validator tests PASS while the real-manifest validation still fails until Task 2.

- [ ] **Step 5: Commit**

```bash
git add packaging/flatpak/validate-manifest.py packaging/flatpak/tests
git commit -m "test: define Steam Deck Flatpak contract"
```

### Task 2: Create the tracked Steam Deck Flatpak manifest

**Files:**
- Create: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json`
- Create: `packaging/flatpak/VkLayer_FROG_gamescope_wsi.x86_64.json`
- Create: `packaging/flatpak/libplacebo-disable-internally-synchronized-queues.patch`
- Create: `packaging/flatpak/artemis-codec-probe.cpp`
- Create: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.desktop`
- Create: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml`
- Modify: `app/main.cpp`

- [ ] **Step 1: Run the real-manifest validator and confirm failure**

Run: `python3 packaging/flatpak/validate-manifest.py packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json`

Expected: FAIL because the manifest does not exist.

- [ ] **Step 2: Add the manifest with exact pinned dependencies**

Base it on the current official Moonlight Flathub manifest, retaining its pinned libplacebo, SDL3, SDL2-compat, SDL2_ttf, dav1d, FFmpeg, and Gamescope WSI inputs. Add `libplacebo-disable-internally-synchronized-queues.patch` explicitly to the libplacebo module so Gamescope's WSI layer cannot receive flagged queues and abort. Use KDE runtime 6.10, `--device=all`, `--filesystem=xdg-run/gamescope-0`, `--filesystem=host-os:ro`, and clear/unset inherited LIBVA variables. Do not define custom GL/VAAPI extensions or writable `/app` driver directories.

The Artemis module uses `type: dir`, `path: ../..`, qmake on `artemis.pro`, and installs `/app/bin/artemis`. Its Flatpak ID, desktop filename, icon filename, AppStream ID, and launchable are all `com.artemisdesktop.ArtemisDesktopDev` to preserve the existing sandbox identity. Flatpak-specific desktop/AppStream files live beside the manifest; native Linux metadata remains unchanged.

- [ ] **Step 3: Add an API-linked codec probe**

`artemis-codec-probe.cpp` calls `avcodec_find_decoder()` for `AV_CODEC_ID_H264`, `AV_CODEC_ID_HEVC`, and `AV_CODEC_ID_AV1`; for each codec it iterates `avcodec_get_hw_config()` and requires both `AV_HWDEVICE_TYPE_VAAPI` and `AV_HWDEVICE_TYPE_VULKAN`. It returns non-zero with a codec/device-specific diagnostic on failure.

Compile and execute the probe during the Flatpak build after FFmpeg is installed. Install it under `/app/libexec/` for CI diagnostics.

- [ ] **Step 4: Make desktop and AppStream metadata internally consistent**

Install the Flatpak-specific metadata under `com.artemisdesktop.ArtemisDesktopDev` while keeping `Exec=artemis`, `Name=Artemis`, and the existing Artemis branding/content. In `app/main.cpp`, read `FLATPAK_ID` at runtime and use it for `setDesktopFileName()` and `SDL_VIDEO_{WAYLAND,X11}_WMCLASS`; outside Flatpak fall back to `com.artemis_desktop.Artemis`. Remove the hard-coded Moonlight ID.

- [ ] **Step 5: Run validator tests and real-manifest validation**

Run:

```bash
python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v
python3 packaging/flatpak/validate-manifest.py packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add packaging/flatpak app/main.cpp
git commit -m "build: add reproducible Steam Deck Flatpak"
```

### Task 3: Fix Vulkan renderer compatibility with the pinned FFmpeg stack

**Files:**
- Modify: `app/streaming/video/ffmpeg-renderers/plvk.cpp`
- Modify: `app/streaming/video/ffmpeg-renderers/plvk.h` if new helper declarations are required

- [ ] **Step 1: Capture the incompatible behavior in a compile contract**

The Flatpak build is the regression test: libavutil 59.34.100+ must compile the code path that populates `AVVulkanDeviceContext::qf[]` and `nb_qf`. Add compile-time guards that fail clearly for an unsupported FFmpeg Vulkan queue API rather than silently leaving queue data empty.

- [ ] **Step 2: Restore libplacebo queue locking**

For supported libavutil versions, delegate `lockQueue()`/`unlockQueue()` to the pinned libplacebo instance callbacks `m_Vulkan->lock_queue(m_Vulkan, queueFamily, index)` and `m_Vulkan->unlock_queue(m_Vulkan, queueFamily, index)`. Retain version guards only where the FFmpeg API no longer accepts callbacks.

- [ ] **Step 3: Populate modern queue-family data**

Use `vkGetPhysicalDeviceQueueFamilyProperties2`, chain `VkQueueFamilyVideoPropertiesKHR`, fill every `qf[]` entry, set video capabilities, and assign `nb_qf`. Preserve the legacy graphics/transfer/compute fields for older libavutil.

- [ ] **Step 4: Build against the exact Flatpak FFmpeg/libplacebo versions**

Run:

```bash
flatpak-builder --force-clean --install-deps-from=flathub --repo=flatpak-repo flatpak-build packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
flatpak build flatpak-build /app/libexec/artemis-codec-probe
```

Expected: the application and codec probe compile and the probe exits 0.

- [ ] **Step 5: Commit**

```bash
git add app/streaming/video/ffmpeg-renderers/plvk.cpp app/streaming/video/ffmpeg-renderers/plvk.h
git commit -m "fix: restore Vulkan Video queue integration"
```

### Task 4: Add tested fractional refresh parsing

**Files:**
- Create: `app/settings/refreshrateparser.h`
- Create: `app/settings/refreshrateparser.cpp`
- Create: `tests/tests.pro`
- Create: `tests/refreshrate/refreshrate.pro`
- Create: `tests/refreshrate/tst_refreshrate.cpp`
- Modify: `app/app.pro`
- Modify: `artemis.pro`
- Modify: `app/main.cpp`
- Modify: `app/gui/SettingsView.qml`
- Modify: `app/streaming/session.cpp`

- [ ] **Step 1: Add the failing QTest cases**

Test `59.94`, `59,94`, surrounding whitespace, `60`, malformed input, mixed separators, values below 10 and above 500, and milli-Hz conversion (`59.94 -> 59940`). Require rounded, overflow-safe conversion. Add an invalid persisted-value test proving `resolveStreamFps(true, invalidCustomRate, fallbackFps)` returns the fallback.

- [ ] **Step 2: Run the test and confirm failure**

Run in a Qt Linux build environment:

```bash
qmake6 tests/refreshrate/refreshrate.pro
make -j2
./tst_refreshrate
```

Expected: FAIL because `RefreshRateParser` does not exist.

- [ ] **Step 3: Implement the parser**

Implement `RefreshRateParser : QObject` with `Q_INVOKABLE QVariantMap parse(const QString&)`, plus static `parseValue()`, `toMilliHz()`, and `resolveStreamFps()` methods used directly by C++. It normalizes one decimal comma to a decimal point, rejects mixed/multiple separators and non-numeric tails, enforces 10–500 Hz, and returns `valid`, `hz`, and `milliHz` to QML.

- [ ] **Step 4: Route QML through the tested helper**

Register `RefreshRateParser` in `app/main.cpp` as singleton module `RefreshRateParser 1.0`, import it in `SettingsView.qml`, and replace `parseFloat()` with `RefreshRateParser.parse(enteredValue)`. Keep `StreamingPreferences` authoritative and do not extend the duplicate `ArtemisSettings` path.

Extend `tst_refreshrate.cpp` with a `QQmlEngine`/`QQmlComponent` integration case. Before constructing the engine, the test must call the same `qmlRegisterSingletonType<RefreshRateParser>("RefreshRateParser", 1, 0, ...)` registration used by `main.cpp`; then it imports `RefreshRateParser 1.0`, evaluates `RefreshRateParser.parse("59,94")`, and asserts the returned `valid`, `hz`, and `milliHz` properties. This verifies the QML-facing contract without assuming the test binary executes application `main()`.

- [ ] **Step 5: Use checked milli-Hz conversion in the session**

Replace the truncating cast in `session.cpp` with `resolveStreamFps()`. Invalid persisted values fall back to the integer FPS setting and log a warning; the unit test from Step 1 covers this exact branch.

- [ ] **Step 6: Run QTest and build the app**

Expected: all parser tests PASS and the application compiles.

- [ ] **Step 7: Commit**

```bash
git add artemis.pro app/app.pro app/main.cpp app/settings app/gui/SettingsView.qml app/streaming/session.cpp tests
git commit -m "fix: support fractional Steam Deck refresh rates"
```

### Task 5: Replace the generated Flatpak workflow

**Files:**
- Modify: `.github/workflows/dev-build.yml`

- [ ] **Step 1: Add `codex/**` to development-build branch triggers**

This lets the pushed delivery branch produce the requested artifact without merging to `develop`.

- [ ] **Step 2: Remove the manifest heredoc and ad-hoc runtime setup**

Install the runtime/SDK declared by the tracked manifest, run the Python contract tests, then invoke:

```bash
flatpak-builder --force-clean --install-deps-from=flathub --repo=flatpak-repo flatpak-build packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
flatpak build-bundle flatpak-repo "artemis-flatpak-$VERSION.flatpak" com.artemisdesktop.ArtemisDesktopDev
```

- [ ] **Step 3: Add build-result checks**

Run the installed codec probe inside `flatpak-build`, verify `/app/bin/artemis`, validate AppStream metadata, and assert required libraries are present and resolved:

```bash
flatpak build flatpak-build /app/libexec/artemis-codec-probe
flatpak build flatpak-build test -x /app/bin/artemis
flatpak build flatpak-build appstreamcli validate --no-net /app/share/metainfo/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml
flatpak build flatpak-build sh -euxc 'ldd /app/bin/artemis | tee /tmp/artemis-ldd.txt; ! grep -q "not found" /tmp/artemis-ldd.txt; for lib in libavcodec libplacebo libSDL2-2.0 libSDL2_ttf-2.0; do find /app/lib -name "$lib.so*" -print -quit | grep -q .; done'
```

Perform the offscreen smoke test with explicit timeout semantics:

```bash
set +e
timeout 10s flatpak build flatpak-build env QT_QPA_PLATFORM=offscreen SDL_VIDEODRIVER=dummy /app/bin/artemis >artemis-smoke.log 2>&1
status=$?
set -e
if [ "$status" -ne 0 ] && [ "$status" -ne 124 ]; then cat artemis-smoke.log; exit "$status"; fi
```

Exit 0 (clean early exit) and 124 (still running when intentionally stopped) pass; loader errors, signals, and immediate non-zero exits fail.

- [ ] **Step 4: Upload the `.flatpak` artifact and verification log**

Retain the existing versioned artifact naming and 30-day retention.

- [ ] **Step 5: Validate workflow YAML and inspect the diff**

Run a YAML parser available in the workspace/container and `git diff --check`.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/dev-build.yml
git commit -m "ci: build and verify the tracked Flatpak"
```

### Task 6: Evaluate Moonlight Steam Deck fixes individually

**Files:**
- Potentially modify files under `app/streaming/`, `app/gui/`, and `app/streaming/video/`
- Create: `docs/STEAM_DECK_UPSTREAM_AUDIT.md`

- [ ] **Step 1: Audit candidates against the common v6.1 base**

Review decoder fallback (`53a7680a`), Gamescope/display handling (`2a1749e2`, `402ac593`), AMD Vulkan deadlock/probing (`d17575d4`, `94d47e95`), dynamic refresh/frame pacing (`efa67fec`), audio queue latency (`4cf498b0`), and gamepad focus/navigation (`2a63ad53`, `02004bac`, `d040bd24`). Record dependencies and Deck relevance.

- [ ] **Step 2: Apply one candidate at a time**

Use `git cherry-pick --no-commit` only when the patch is isolated. Resolve by porting intent—not by copying incompatible master APIs. Drop candidates coupled to SDL/rendering refactors or without a reproducible Deck-facing benefit.

- [ ] **Step 3: Verify and commit each accepted fix separately**

Run the narrowest available test plus the application/Flatpak compile. Each accepted fix gets its own commit. The audit records accepted, already-present, and rejected candidates with reasons.

### Task 7: Add Steam Deck operating guidance and acceptance matrix

**Files:**
- Create: `docs/STEAM_DECK.md`
- Modify: `README.md`

- [ ] **Step 1: Document installation and recommended settings**

Cover Gaming/Desktop Mode, 1280×800 handheld output, docked matching, 60/90/59.94 Hz, codec selection/fallback, OLED HDR prerequisites, frame pacing, bitrate, wired host networking, 5/6 GHz client Wi-Fi, and statistics-overlay interpretation.

- [ ] **Step 2: Add the manual hardware matrix**

Clearly label LCD/OLED, modes, dock state, codecs, HDR, refresh rates, controller UI, suspend/resume, and network-loss checks as unverified until run on hardware.

- [ ] **Step 3: Link the guide from README**

- [ ] **Step 4: Commit**

```bash
git add docs/STEAM_DECK.md README.md
git commit -m "docs: add Steam Deck setup and validation guide"
```

### Task 8: Build, inspect, review, and publish

**Files:**
- Modify only files required by review findings

- [ ] **Step 1: Run all local static/unit checks**

Run manifest unit tests, real-manifest validation, QTest in Linux, workflow parsing, and `git diff --check`.

- [ ] **Step 2: Build the Flatpak in Linux**

Use a Linux environment with recursive submodules and run the exact Task 5 `flatpak-builder`, probe, AppStream, `ldd`, shared-library, and bounded-smoke commands. Then run:

```bash
flatpak build-bundle flatpak-repo artemis-steam-deck.flatpak com.artemisdesktop.ArtemisDesktopDev
sha256sum artemis-steam-deck.flatpak | tee artemis-steam-deck.flatpak.sha256
```

- [ ] **Step 3: Request independent code review**

Review correctness, Steam Deck relevance, packaging reproducibility, and overclaiming. Resolve findings one at a time and rerun affected checks.

- [ ] **Step 4: Verify repository state**

Confirm only intended files changed, all commits are present, and no build/cache artifacts are tracked.

- [ ] **Step 5: Push delivery branch**

Push `codex/steam-deck` to `origin`, wait for the GitHub Actions Flatpak job, inspect logs, and retrieve the successful artifact or fix failures until it passes.

- [ ] **Step 6: Report the artifact and remaining hardware-only checks**

Provide the branch, commit, workflow/artifact location, SHA-256, installation command, and the still-unverified real-Deck matrix without claiming hardware success.
