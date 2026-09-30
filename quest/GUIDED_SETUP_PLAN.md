# Vibertemis Quest guided setup — phase 1

Owner asked for an in-app guided setup experience on Quest and on Windows.
Agy Gemini 3.1 Pro (High) approved the plan on 2026-09-30. This document
captures the consultation summary and the bounded phase 1 scope.

Phase 2 (pairing import, Visual C++ runtime helper, signed Windows update
worker, etc.) is intentionally out of scope here and is NOT claimed as
finished.

## Consultation summary (Agy Gemini 3.1 Pro High)

Approved scope, with no P0/P1 blockers:

1. **Shared signed update state** across Quest hub and the updates screen.
   Exact manifest / signature bytes and the verified downloaded APK are
   stored in an application-scoped repository; both surfaces consume the
   same verified result so a check in one surface is visible in the other.
2. **Throttle, idle gates, coalesced requests.** Six-hour throttle on
   successful metadata checks. Shorter retry after failure (15 minutes).
   Explicit user "Refresh now" button bypasses the throttle. Concurrent
   trigger calls collapse onto a single in-flight check; no UI blocks,
   no network duplication.
3. **SteamVR actual executable validation.** Confirm `vrserver.exe` and
   `vrstartup.exe` exist in the configured root before reporting the
   runtime as installed. When the openvr vrpath file is missing,
   fall back to Steam's `libraryfolders.vdf` + `appmanifest_250820.acf`
   so a Steam library that contains SteamVR but has not yet been opened
   is recognised as installed (not "missing").
4. **Status differentiation.** Distinguish installed-uninitialised
   (SteamVR present, vrpath not yet written), missing (no install),
   stale/broken (recorded path but executables gone, or recorded path
   points outside any active Steam library).
5. **VDF parsing must be safe.** No regex assumptions about escapes,
   nested libraries, or quoted paths. Use a real VDF reader for the
   libraryfolder scan and never shell-parse `libraryfolders.vdf`.

Pairing, Visual C++ runtime helper, the Windows update worker, and any
direct install-flow changes are deferred to phase 2.

## Phase 1 scope (this implementation)

### Android (`quest/overlay/.../update` + `MainHubActivity`)

- New `UpdateRepository` (pure Java, testable) holds:
  - the exact signed manifest bytes and signature bytes from the
    last successful check,
  - the verified cached `update.apk` path,
  - throttle state (last-success timestamp, last-failure timestamp),
  - a single in-flight coalescing handle.
- `MainHubActivity` launches a metadata-only check on
  `onCreate`/`onResume`. The check runs on the shared background
  executor; the UI shows an "Update available" badge and button when
  the shared state advertises a newer manifest. The user can launch
  `UpdatesActivity` from the badge or the existing button; that
  activity consumes the same shared state.
- `UpdatesActivity` continues to verify the cached APK and download /
  install. It no longer requires a manual Check click before showing
  the verified metadata — it shows whatever the shared repository
  currently reports. Errors and offline states never block gaming.
- All APK hash, signature, package-name / version-code / downgrade
  checks, and the live VR-idle gate (`ensureIdle`) are preserved.
- `onDestroy` on any Activity must NOT cancel the shared in-flight
  check (it is owned by the application context). An Activity that
  registers an observer may unregister it without leaking.
- A new pure-Java test class exercises the repository state machine
  with Robolectric-free, harness-friendly inputs.

### Windows (`MainForm`, `VibertemisManager.Core/Update`)

- New `UpdateRepository` (Core, testable) holds the same shared
  state shape: signed manifest bytes, signature bytes, verified
  installer path, throttle timestamps, and a coalescing handle.
- `MainForm` starts a background metadata-only check at launch and
  on each 1 s status-timer tick when the host is idle. The check
  honours the same six-hour successful / fifteen-minute failure
  throttle, exposes an explicit Refresh button to bypass, and
  coalesces concurrent triggers.
- The existing three-stage UX is split:
  - The "Check for updates" button now performs the metadata check
    only and updates the status line / surfaces a Download button
    once the verified metadata is available.
  - The Download button performs the download + verification
    boundary; a separate "Install update" button performs the
    install handoff once the verified installer is on disk.
- Exact signed bytes, the worker handoff validation, the busy
  checks, and the no-automatic-closure / no-reboot rule are
  preserved. Cached installer is retained if the network fails.
- New xUnit tests cover the state machine transitions and the
  ReleaseClient mock-HTTP boundary.

### SteamVR locator (`VibertemisManager.Core/Steam/SteamVrLocator.cs`)

- The locator now distinguishes:
  - `InstalledAndReady` (vrpath present + executables present),
  - `InstalledUninitialized` (Steam install has appmanifest_250820 but
    no vrpath / no executables yet),
  - `StaleRecorded` (vrpath present but executables missing),
  - `Missing`.
- A new `SafeVdfReader` parses `libraryfolders.vdf` without regex
  assumptions (handles escapes and nested `"path"` strings).
- Existing `SteamVrInstallStatus` shape is preserved so the caller in
  `MainForm.RefreshSteamVrStatus` does not need to change; a new
  `SteamVrDiscoveryStatus` record adds the rich classification.
- New xUnit tests cover stale recorded path, valid alternate
  library, missing files, and malformed VDF.

## Out of phase 1 (do not claim done)

- Pairing import UX (covered by existing PcvrSettingsActivity).
- Visual C++ runtime helper button behaviour.
- Windows update worker changes.
- Any installer / setup-flow change on the Windows side.

## Validation

- Run `dotnet test VibertemisManager.Core.Tests` (expect 161 prior +
  new tests passing).
- Cross-build `VibertemisManager.Core -f net8.0-windows` and
  `VibertemisManager.App -p:EnableWindowsTargeting=true` with
  TreatWarningsAsErrors.
- Run the existing Robolectric Java test suite via the build
  harness documented in `quest/TESTING.md` for the new pure-Java
  UpdateRepository tests.
- Do not bump version numbers or release artifacts in this phase.

## Codex takeover and validation — 2026-09-30

Owner explicitly approved direct Codex implementation for this task after MiniMax quota and tool-permission failures. MiniMax diagnostics found no idle workers; persisted sessions are history. No daily cleanup job was installed.

Integrated implementation: existing pinned GameStream certificate enrolls per-device VR credentials; companion persists transactionally and checks current host authority for all inherited authenticated requests. Windows transport uses go-winio with identification-only token inspection. The host transport uses Boost.Asio, bounded RPCs, registration/session rechecks and an explicit ping before ready. Windows setup verifies the bundled Microsoft runtime, protects machine registration and rechecks prerequisites; Steam discovery verifies binaries; advanced manual pairing remains available. Shared update repositories check in the background and preserve verified downloads.

Agy reviewed the go-winio plan and host Asio implementation. Host review accepted shared-promise caller deadlines and bidirectional authorization. Adjudications: Vibeshine's `sunshinesvc.cpp` launches Sunshine in the active console session using a duplicated SYSTEM token; session-zero refusal is intentional. `util::FailGuard` always executes unless disabled. The project's Windows dependency is current OpenSSL. Agy's claim about go-winio lacking Fd remains subject to the real Windows pipe test, not speculative API changes.

Local validation: Android unit tests and APK assembly pass; Windows manager builds; 257 Core tests pass; companion race tests and inherited-authentication request tests pass. Real Windows API, signature/tamper, installer and update-handoff checks are required in CI. This is a draft validation build, not final sign-off or a claim of hardware end-to-end success.

## Real Windows findings

CI run 36736104446 passed all 257 Core tests and all Windows Go tests, including real go-winio handle/identification and exclusive listener checks. This resolves Agy's speculative Fd blocker. Both real SCM service-path tests passed. The real registry test caught .NET's separate managed writability flag: `OpenSubKey(name, rights)` inherits the base permission-check mode even with native write rights. Fixed by explicitly using `ReadWriteSubTree` alongside the native ACL rights (verified against dotnet/runtime v8.0.0 RegistryKey.cs). Release remains gated on rerunning the Windows tests and installer checks. Agy's broad “perfect/flawless” wording is not adopted; its sign-off covers only the supplied source, with runtime validation authoritative.

CI run 36737153882 passed the real Windows registry rewrite, SCM discovery, companion tests, installer build, and genuine/tampered Microsoft runtime inspection. Installer and update-handoff checks were still running at this checkpoint. Host CI now explicitly executes the actual Asio transport test instead of only compiling it. UI labels use Start/Stop hosting and wrap status text within the left column.
