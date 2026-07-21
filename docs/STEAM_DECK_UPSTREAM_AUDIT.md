# Steam Deck Moonlight Upstream Audit

**Audit date:** 2026-07-21

**Artemis branch:** `codex/steam-deck`

**Upstream reference:** `research/moonlight-master`

**Common history:** `1bf86f52d368935e60780acc2d9d3c5265f6bce8`

## Method and acceptance criteria

Artemis carries substantial fork-specific work on its Moonlight 6.1-era base, so the candidates below were reviewed as individual behavior ports rather than cherry-picked as a batch. Each patch was inspected from its fetched commit and compared with the current Artemis source.

A candidate was accepted only when it:

1. fixes a failure or reliability problem observable in the supported Steam Deck Flatpak path;
2. does not require later Moonlight SDL, renderer, or platform refactors;
3. preserves Artemis-specific streaming, branding, fractional-refresh, and pinned FFmpeg/libplacebo work;
4. has a narrow automated regression contract where practical; and
5. avoids unrelated behavior changes.

The supported Deck path for this release is the Flatpak under Gamescope/Wayland, with XWayland as a compatibility fallback. Direct-display EGLFS/KMSDRM is not the supported Steam Deck launch path.

## Accepted ports

| Upstream | Result | Dependencies and Steam Deck relevance | Artemis commit and verification |
| --- | --- | --- | --- |
| `402ac593` — select the X11 SDL driver for an xcb Qt platform | Accepted, ported by intent | Uses only the existing Qt platform name, `WMUtils::isRunningWayland()`, and `SDL_VIDEODRIVER`. It makes the SDL/Qt backend pairing deterministic when a Deck Flatpak falls back to xcb/XWayland. The XWayland warning remains conditional, while plain X11 also selects SDL's X11 driver. The KMSDRM selection was moved into the same mutually exclusive platform chain. | `1e82d12a` (`fix: align SDL driver with X11 platform`). A source-contract test requires the xcb outer branch, nested XWayland warning check, and X11 driver assignment. |
| `4cf498b0` — constrain SDL audio latency by queued duration | Accepted | Uses APIs already present in Artemis: Opus sample rate/frame size, SDL queued-audio byte count, and Limelight's pending-duration backpressure. The old ten-frame threshold could allow 100 ms when the host negotiates 10 ms packets; the port consistently caps SDL's queue at 50 ms. This is material to handheld latency and audio/video synchronization. The macOS-specific requested buffer-size branch remains untouched. | `f8a46ef1` (`fix: constrain SDL audio queue by duration`). The regression contract requires calculation of frame duration and duration-based queue gating. |
| `02004bac` — change combo-box options with Left/Right | Accepted | Qt Quick Controls `ComboBox.decrementCurrentIndex()` and `incrementCurrentIndex()` are available on the current QML base and require no later navigation refactor. This lets the Deck D-pad change focused settings without opening the popup. Existing popup navigation-mode handling is preserved. | `15633c07` (`fix: navigate combo options with left and right`). The regression contract requires both key handlers and their direction-specific actions. |
| `d040bd24` — focus and navigate dialog buttons | Accepted | Uses the existing `DialogButtonBox`, button delegate, and Qt focus chain. Initial focus moves to the final standard button (the conservative/default choice), Return/Enter activates the focused button, and Left/Right traverses buttons. This directly improves Deck controller use for errors, confirmations, and help dialogs. | `65c87bc4` (`fix: navigate dialog buttons with gamepad`). The regression contract requires initial button focus, activation handlers, and bidirectional focus-chain traversal. |

## Already present or superseded

| Upstream | Result | Evidence |
| --- | --- | --- |
| `2a1749e2` — disable libplacebo internally synchronized queues for Gamescope | Already present | `packaging/flatpak/libplacebo-disable-internally-synchronized-queues.patch` contains the same functional hunk (`internallySynchronizedQueues = false`) with additional rationale, and the pinned libplacebo module in `com.artemisdesktop.ArtemisDesktopDev.json` applies it. This was landed as part of `5ecafd2b`; importing the AppImage workflow change would duplicate behavior and modify an unsupported packaging path. |
| `2a63ad53` — stop polling gamepads while the GUI is unfocused/hidden | Present and subsequently refined | The upstream commit is already in Artemis ancestry. Follow-up `b6a33692` replaced repeated SDL subsystem enable/disable with `notifyWindowFocus()` and timer-state gating. Current `main.qml` reports `visible && active` on both visibility and activity changes, while `SdlGamepadKeyNavigation::updateTimerState()` starts polling only when enabled and focused. Reapplying the older patch would regress that refinement. |

## Rejected candidates

| Upstream | Decision | Reason |
| --- | --- | --- |
| `53a7680a` — probe AV1 before preferring it to H.264 for SDR | Rejected for this Deck scope | The added probe is not reachable on Steam Deck's Linux/x86 build. In that configuration the existing preprocessor branch includes `\|\| !enableHdr`, so SDR AV1 is already deprioritized before the new `else if`; for HDR, that `else if` is false. The patch changes Windows and non-x86 Unix edge cases, but it provides no Deck-facing behavior and would unnecessarily touch Artemis's heavily customized session and fractional-FPS code. |
| `d17575d4` — make the DRM-master hook lock recursive | Rejected | This addresses re-entrant driver calls while running the Vulkan renderer directly through KMSDRM on AMDGPU. The supported Deck Flatpak presents through Gamescope/Wayland and its WSI layer, not Artemis's direct-display DRM-master handoff. No failure in the supported path is reproduced, so changing global hook locking would expand scope without Deck evidence. |
| `94d47e95` — serialize Vulkan setup with DRM master | Rejected | This is also KMSDRM-only and depends on the DRM-master hook/locker integration. Artemis does not define the upstream `HAVE_DRM_MASTER_HOOKS` contract, and its `plvk.cpp` has diverged significantly for the manifest's pinned FFmpeg/libplacebo versions, including queue-family population, libplacebo queue locking, and FFmpeg's Vulkan proc loader. Importing the patch would either be inert or risk those verified compatibility changes without benefiting Gamescope/Wayland. |
| `efa67fec` — disable VBlank virtualization with dynamic refresh | Rejected | Despite the general title, the patch is entirely under `Q_OS_WIN32` and dynamically calls the Windows DXGI `DXGIDisableVBlankVirtualization()` API. It has no Linux or Steam Deck code path. Fractional refresh on Deck is handled by Artemis's tested refresh parser and session milli-Hz conversion instead. |

## Automated verification

The accepted ports were developed with red/green checks in `packaging/flatpak/tests/test_upstream_ports.py`. These are deliberately narrow source contracts because the affected platform/UI behavior cannot be exercised on this macOS host. They run with the existing manifest contract suite:

```sh
python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v
python3 packaging/flatpak/validate-manifest.py \
  packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
git diff --check
```

At audit time the Python suite contains 24 passing tests, including four upstream-port contracts, and the tracked manifest satisfies its validator.

## Remaining validation limits

Neither `qmake6`/`qmake` nor `flatpak-builder`/`flatpak` is installed on the audit host. Consequently, the accepted C++ and QML ports still require the full Linux Flatpak build and offscreen smoke checks in CI. Controller behavior, audio latency, and XWayland fallback also remain hardware/manual acceptance items; this audit does not claim they were exercised on a physical Steam Deck.
