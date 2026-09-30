# Windows guided prerequisites — implementation contract

Quest-preview branch `quest/windows-guided-prerequisites`. Per-user
Windows manager and bounded elevated helper; no MSIX migration,
no signing-key change, no restart-manager termination of VR.

This document records the implementation delivered on this branch,
the exact hook methods opencode will call after merge, and the
test/build coverage the CI gate must satisfy before merge.

## Scope of this branch

Files added or modified (Windows only):

-   `quest/windows/VibertemisManager.Core/Prerequisites/`
    -   `VcRuntimeDetector.cs` — Major/Minor/Bld/Rbld + Installed
        DWORD detection on both registry views; testable through
        the `IVcRegistryKey` adapter (no platform gate in Core).
    -   `VcRedistInstaller.cs` — bounded Win32 launcher with
        `Begin` / `TryGetResult` / `IsInFlight` state machine;
        WinVerifyTrust + leaf-Subject-O=Microsoft Corporation;
        never kills the installer on timeout.
-   `quest/windows/VibertemisManager.Core/Platform/Windows/`
    -   `WindowsVcRuntimeDetector.cs` — production binding;
        reads `Registry64` view first, falls back to
        `WOW6432Node\...x64`.
    -   `WindowsVcRedistInspector.cs` — production WinVerifyTrust
        binding with the correct native ABI (32-byte
        WINTRUST_FILE_INFO, 88-byte WINTRUST_DATA), `WTD_REVOKE_NONE
        = 0` and `WTD_CACHE_ONLY_URL_RETRIEVAL = 0x1000`. Always
        issues the `WTD_STATEACTION_CLOSE` follow-up call.
    -   `WindowsVcRedistLauncher.cs` — `ShellExecute=true +
        Verb=runas` UAC-elevated launch; `WaitForExit` with
        explicit timeout; never kills the redistributable; tracks
        the actual `Process` until natural exit.
-   `quest/windows/VibertemisManager.Core/Bridge/`
    -   `BridgePaths.cs`, `BridgeRegistrationController.cs` —
        testable controller that verifies the manager PID against
        the kernel (image path + TokenSessionId + string SID),
        reads Sunshine via SCM, and rolls back partial HKLM writes.
-   `quest/windows/VibertemisManager.Core/Platform/Windows/WindowsBridge.cs`
    -   Production Windows bindings for the bridge: kernel32 +
        advapi32 caller verification, SCM SunshineService lookup
        via `QueryServiceConfigW` + `CommandLineToArgvW`, ACL
        write under `HKLM\SOFTWARE\Vibertemis\VRBridge`.
-   `quest/windows/VibertemisManager.Core/GuidedSetup/GuidedSetupController.cs`
    -   Resumable detect→act→recheck state machine with three
        top-level states (Checking, ActionNeeded, PcReady) and
        per-prerequisite status snapshots. Connected is reported
        only via the existing `IConnectivitySignal`.
-   `quest/windows/VibertemisManager.Core/Integrity/InstalledPayload.cs`
    -   Drops the obsolete `runtime/bin/win64/vcruntime140_1.dll`
        entry; adds `manager/prerequisites/vc_redist.x64.exe` to
        the manifest so the integrity verifier requires the
        bundled package to match the manifest before any
        install attempt.
-   `quest/windows/VibertemisManager.App/MainForm.GuidedSetup.cs`
    -   New partial that exposes `InitializeGuidedSetup()` and
        `RunGuidedSetupAsync()` to opencode's later integration
        hook. Existing `MainForm.cs` / `MainForm.VrSetup.cs` /
        `SteamVrLocator.cs` are NOT modified.
-   `quest/windows/VibertemisManager.App/WindowsBridgeAdapter.cs`
    -   `IBridgeManager` adapter that wraps the platform binding
        for the guided-setup controller.
-   `quest/installer/network-helper/Program.cs`
    -   Adds the bounded `--register-vr-bridge <managerPID>` mode.
        The helper derives both CompanionPath and SunshinePath;
        neither is caller-controlled.
-   `quest/installer/installer.iss`
    -   Removes the obsolete `vcruntime140_1.dll` install entry;
        installs the entire `manager/prerequisites/` subtree.
-   `quest/installer/build-installer.ps1`
    -   Downloads the official Microsoft vc_redist.x64.exe from
        `https://aka.ms/vc14/vc_redist.x64.exe` (canonical, no
        mirror); computes its SHA-256 + size into the integrity
        manifest; stages it under `manager/prerequisites/`.

Files NOT modified on this branch (other writers own them):

-   `quest/windows/VibertemisManager.App/MainForm.cs`
-   `quest/windows/VibertemisManager.App/MainForm.VrSetup.cs`
-   `quest/windows/VibertemisManager.Core/Steam/SteamVrLocator.cs`
-   `quest/windows/VibertemisManager.Core/Update/*`
-   Android, Go, ALVR companion, recovery controller, networking

## Version pinning and detection semantics

The bundled native payload was built with VS 2022 17.14 / MSVC
14.44.35207 (compiler 19.44.35229) per CI run `36550716420`
(2026-09-29 toolchain path `VC/Tools/MSVC/14.44.35207`). The
detector reports the installed runtime's actual version from
the Microsoft-documented `Major` / `Minor` / `Bld` / `Rbld`
DWORDs under `HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\
Runtimes\x64` (and the `WOW6432Node` mirror on 32-bit views).

Minimum toolchain version is `VibertemisManager.Core.Prerequisites.
VcRuntimeRequirements.MinimumX64Runtime = (14, 44, 35207, 0)`.
Version parsing:

-   optional leading lowercase `v` prefix is trimmed;
-   1–4 strictly-numeric dot-separated components are accepted;
-   missing trailing components default to 0 for comparison;
-   anything else (dashes, commas, alphabetic suffixes) is
    rejected as malformed (NOT silently coerced).

## WinVerifyTrust layout (Microsoft ABI)

Both struct sizes are pinned by `WinVerifyTrustLayoutTests` and
must remain `32` and `88` bytes respectively on x64:

```
WINTRUST_FILE_INFO (x64):
    DWORD cbStruct;            // 4
    [4 bytes padding]
    LPCWSTR pcwszFilePath;     // 8 (pointer)
    HANDLE  hFile;             // 8
    GUID   *pgKnownSubject;    // 8
    total                      // 32

WINTRUST_DATA (x64):
    DWORD cbStruct;            // 4
    [4 bytes padding]
    LPVOID pPolicyCallbackData;// 8
    LPVOID pSIPClientData;     // 8
    DWORD  dwUIChoice;         // 4
    DWORD  fdwRevocationChecks;// 4
    DWORD  dwUnionChoice;      // 4
    [4 bytes padding]
    union { ... };             // 8 (pointer)
    DWORD  dwStateAction;      // 4
    [4 bytes padding]
    HANDLE hWVTStateData;      // 8
    LPCWSTR pwszURLReference;  // 8
    DWORD  dwProvFlags;        // 4
    DWORD  dwUIContext;        // 4
    WINTRUST_SIGNATURE_SETTINGS *pSignatureSettings; // 8
    total                      // 88
```

`dwUIChoice` is `WTD_UI_NONE = 2`. `fdwRevocationChecks` is
`WTD_REVOKE_NONE = 0` (NOT `1`, which is `WTD_REVOKE_WHOLECHAIN`).
`dwProvFlags` is `WTD_CACHE_ONLY_URL_RETRIEVAL = 0x1000`, which
per Microsoft docs is required to prevent the offline setup
path from attempting AIA / OCSP network retrieval.
`dwStateAction` runs `WTD_STATEACTION_VERIFY = 1` then
`WTD_STATEACTION_CLOSE = 2` unconditionally in `finally`.

## Publisher check (leaf Subject, not Issuer, not CompanyName)

WinVerifyTrust already verifies the chain. The Core verifier then
re-checks the **leaf** signing certificate's parsed Subject DN
`O=` (Organization) component for an exact string match against
`"Microsoft Corporation"`:

-   `VcRedistVerifier.LeafSubjectMatchesMicrosoft(subjectDn)`
-   Issuer (the issuing CA — e.g. "Microsoft Code Signing PCA")
    is ignored.
-   PE `FileVersionInfo.CompanyName` (a forgeable PE string) is
    ignored.
-   Substring match on the full Subject is rejected; only the
    exact O= value is matched.
-   A valid foreign signer whose Issuer chain happens to mention
    "Microsoft" is rejected because its leaf O is not
    "Microsoft Corporation".

## Process tracking (never kill, never drop)

`WindowsVcRedistLauncher.Begin(package)` returns:

-   `int?` exit code if the redistributable completed within the
    wait; OR
-   `null` if the wait elapsed and the process is still running.

The actual `Process` is held in `_inFlight` (not `using`-disposed
on timeout) so the next call can observe:

-   `IsInFlight` — true iff the previous install is still alive;
-   `TryGetResult(out exitCode, out stillRunning)` — returns
    the cached exit code once the process naturally exits, or
    `stillRunning=true` while it persists.

The wrapper `VcRedistInstaller` consults this state on every
`EnsureInstalled` call:

1.  If `IsInFlight`, observe first; never launch a duplicate.
2.  If the runtime is already satisfactory, return
    `AlreadyInstalled`.
3.  Otherwise, call `Begin`; on `null`, return `StillRunning`
    (the user closes the manager; the next launch re-detects
    the registry and either reports success or asks to retry).
4.  Classify the exit: `0` + registry recheck satisfied →
    `Success`; `3010` / `1641` → `RestartRequired`;
    `1223` (UAC) → `Denied`; other → `Other` with the documented
    detail.

The manager closing (tray close, explicit Exit) NEVER kills the
redistributable. When the wrapper is finalised the kernel
handle is released; the redistributable continues to its natural
exit.

## Bridge registration (HKLM, kernel-verified caller)

`VibertemisNetworkHelper --register-vr-bridge <managerPID>`:

-   `<managerPID>` is the only caller-controlled argument.
-   The helper opens the manager PID with
    `PROCESS_QUERY_INFORMATION`, reads `QueryFullProcessImageNameW`,
    canonicalises it, and asserts it equals
    `<programsroot>/manager/VibertemisManager.App.exe` (the
    canonical installed manager — NOT a renamed helper, NOT a
    copy). Wrong path → exit `7`.
-   Opens the manager's primary token (`TOKEN_QUERY`); reads
    `TokenSessionId` and `TokenUser`. The session must equal the
    active console session from `WTSGetActiveConsoleSessionId`.
    Wrong session → exit `8`.
-   Reads the `SunshineService` SCM entry via
    `QueryServiceConfigW`; parses the binary path with
    `CommandLineToArgvW`; resolves the canonical
    `<sunshineDir>/sunshine.exe`. If absent → SunshinePath is
    written as `""` (manager prompts "install Vibeshine").
-   CompanionPath is fixed: `<programsroot>/manager/bin/vibertemis-host-companion.exe`. Never caller-controlled.
-   Writes under `HKLM\SOFTWARE\Vibertemis\VRBridge`
    (`UserSid`, `CompanionPath`, `SunshinePath`) with an
    admin / SYSTEM DACL plus read for the registering user.
-   On any partial-write failure, rolls back the three values
    so consumers never see a half-populated record.
-   The manager parent PID argument of an elevated UAC broker
    is explicitly NOT used — the helper derives everything from
    the kernel.

Helper exit codes:
`0` written, `7` wrong image, `8` wrong session, `9` access
denied, `10` unreadable SID, `11` sunshine unresolved, `12`
companion missing, `13` ambiguous user, `14` other, `99` not
Windows.

## Required hooks for opencode's later wiring

After merge, opencode (per the prompt's "integration hook will be
added later by opencode after merge") wires:

1.  **MainForm constructor** — after `BuildLayout()` and before
    `_statusTimer.Start()`, call `_svc.InitializeGuidedSetup()`.
    The partial already exposes this via
    `MainForm.GuidedSetup.cs`.

2.  **Prepare VR button click** — replace the existing
    `_btnSetupNetwork.Click += async (_, _) => await PrepareVr();`
    with a guarded call that consults
    `_svc.RunGuidedSetupAsync()` first; only call `PrepareVr()` on
    success. The partial exposes `RunGuidedSetupAsync()` so the
    existing button wiring is the only line that changes.

3.  **Existing PrepareVr() path** — keep the existing
    `RequireIdle`, `VerifyPayload`, `_recovery.SuspendAndStop`,
    `VrSetup.Prepare`, `SetupNetworkAccess`, etc. The guided
    setup gates the call; it does NOT replace it.

4.  **Companion start guard** — add a `&& IsReadyToLaunchCompanion`
    check to `_btnCompanionToggle.Enabled` (the partial exposes
    `internal bool IsReadyToLaunchCompanion`).

5.  **Bridge re-registration** — when the manager detects an
    adapter change / user switch / first launch, consult
    `WindowsBridgeAdapter.Snapshot(...)`; if it returns
    `OtherUserActive`, surface "different user owns the bridge";
    if `Absent`, launch UAC via `_svc.UacHelper.Launch(helper,
    "--register-vr-bridge " + Environment.ProcessId)`.

6.  **Cleanup of the obsolete `vcruntime140_1.dll`** — at first
    `Prepare VR` after upgrade, if
    `<programsroot>/runtime/bin/win64/vcruntime140_1.dll` exists
    AND the new bundled package is verified present, move that
    one file to `<programsroot>/runtime/bin/win64/.removed-vcruntime140_1-<timestamp>`.
    Do NOT delete any other file. Do NOT delete the file from
    another app's directory. Open the later PR with the existing
    `IoMover` pattern.

7.  **Manager self-startup opt-out** — the partial subscribes to
    `_settings.AutoStartWithWindows` and only re-registers the
    bridge after the user has explicitly opted in to
    `KeepHostReadyAfterSignIn`, never on every login. The
    existing `ApplyStartupPreference` flow already records the
    opt-in; the partial does NOT call `_svc.AutoStart` directly.

## Test coverage

Linux `dotnet test`:

```
Passed!  - Failed:     0, Passed:   219, Skipped:     0,
Total:   219, Duration: 3 s - VibertemisManager.Core.Tests.dll (net8.0)
```

(Baseline was 161 tests; this branch adds 58 tests across the
new modules.)

Tests cover:

-   `VcRuntimeDetectorTests` (16 tests): missing key,
    `Installed=0`, corrupt registry (Installed=1 but no
    Major/Minor), happy path, stringified DWORDs, multi-view
    priority, fallback to WOW node, version parser edge cases,
    semantic CompareTo, Classify().
-   `VcRedistInstallerTests` (13 tests): mandatory SHA / size,
    mismatch detection, wrong publisher, foreign signer with
    Microsoft in Issuer chain, accepted Microsoft publisher,
    old FileVersion, AlreadyInstalled skip, success on exit 0,
    RestartRequired on 3010 / 1641, UAC 1223 denial,
    in-progress state machine (timeout → still running →
    completion → re-detect → Success, NO duplicate launch),
    fail-closed on installer 0 / runtime missing, non-Windows
    rejection.
-   `WinVerifyTrustLayoutTests` (5 tests): VcVersion struct
    size, publisher parser (exact O=, not Issuer, not
    CompanyName, case-sensitive), and on Windows TFM the
    reflective struct size and field order checks.
-   `WindowsVcRedistInspectorSmoke` (3 tests, WINDOWS-only):
    bundled-package passes; tampered-PE fails; downloaded
    end-to-end Microsoft package passes.
-   `BridgeRegistrationControllerTests` (10 tests): write on
    valid caller, reject wrong image, reject wrong session,
    reject access-denied probe, write with empty Sunshine when
    not installed, rollback on partial failure, no rollback
    when nothing was registered, snapshot current, snapshot
    other-user-active on SID mismatch, snapshot
    other-user-active on companion path change, snapshot
    present on Sunshine cleared on both sides.
-   `GuidedSetupControllerTests` (5 tests): ActionNeeded when
    runtime missing, PcReady when all satisfied,
    OtherUserActive reports Unknown, CorruptRegistry next
    action, non-Windows platform.
-   (Existing 161 baseline tests still pass.)

## Build pipeline / CI

Required before merge:

```
dotnet test quest/windows/VibertemisManager.Core.Tests/
    VibertemisManager.Core.Tests.csproj
dotnet build quest/windows/VibertemisManager.App/
    VibertemisManager.App.csproj -p:EnableWindowsTargeting=true
dotnet build quest/installer/network-helper/
    VibertemisNetworkHelper.csproj
```

All three succeed on this branch (`Passed: 219` and 0 build
warnings/errors). Windows CI run `windows-cross-build` MUST pass
on `net8.0-windows` TFM for both Core and App; the WINDOWS-only
smoke test must run on the Windows runner with the bundled
package produced by `quest/installer/build-installer.ps1`.

## No commits / pushes

This branch is left dirty with the working-tree changes; no
`git commit`, `git push`, release, or external-system calls
were performed. The `git status` shows the full diff waiting
for opencode to land it after merge per the original prompt.