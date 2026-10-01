// VrRestartWorker coordinator tests.
//
// Every OS specific concern (Toolhelp32, module enumeration, final
// path resolution, CreateProcess, named mutex, stdin/stdout) is behind
// an injected port, so the whole handshake is exercised here with a
// deterministic clock on any platform: no real Windows process, no
// real SteamVR runtime, and no wall clock waiting.
//
// The layout mirrors the real install: the recorded SteamVR root is a
// raw alias (a junction or short name in production) while every real
// file lives under the canonical runtime, and the bundled driver lives
// in a completely separate manager install root.
//
// Covered:
//   - preflight rejections publish a result and never signal READY
//   - the expected driver module is the installed one, in the install
//     root, never a same-named file inside the SteamVR folder
//   - every expected file is compared in canonical form, and the
//     canonical startup is what gets launched
//   - bounded, strict commit line handling and a bounded read that can
//     never block the caller forever
//   - a process table or module list that cannot be read is refused,
//     never treated as empty
//   - the launch happens only after every retained handle exited
//   - monotonic exit and startup budgets, unaffected by wall clock jumps
//   - success needs the same canonical vrserver AND our driver loaded
//   - never a duplicate vrserver, never a foreign runtime launch, and a
//     foreign vrserver is refused even behind a matching one
//   - coalescing, handle/mutex cleanup, bounded result JSON
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.Steam;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class VrRestartWorkerTests
{
    // The recorded runtime root is a raw alias; a real process image is
    // always the resolved path below the canonical runtime.
    private const string SteamVrRoot = @"C:\vr\alias-steamvr";
    private const string SteamVrCanonical = @"C:\vr\SteamVR";
    private const string InstallRoot = @"C:\VibertemisVR";
    private const int ParentPid = 4242;

    private static readonly string Win64 = VrRestartPaths.Combine(SteamVrCanonical, "bin", "win64");
    private static readonly string AliasWin64 = VrRestartPaths.Combine(SteamVrRoot, "bin", "win64");
    private static readonly string VrserverImage = VrRestartPaths.Combine(Win64, "vrserver.exe");
    private static readonly string VrstartupImage = VrRestartPaths.Combine(Win64, "vrstartup.exe");
    private static readonly string AliasVrstartupImage = VrRestartPaths.Combine(AliasWin64, "vrstartup.exe");
    private static readonly string VrmonitorImage = VrRestartPaths.Combine(Win64, "vrmonitor.exe");
    private static readonly string VrcompositorImage = VrRestartPaths.Combine(Win64, "vrcompositor.exe");
    private static readonly string ForeignVrserverImage = @"C:\other\SteamVR\bin\win64\vrserver.exe";

    // The bundled driver is installed by us, under the install root.
    private static readonly string InstalledDriver =
        VrRestartPaths.Combine(InstallRoot, VrRestartWorker.DriverModuleRelativePath);
    // The same file name inside the SteamVR folder is never ours.
    private static readonly string SteamVrFolderDriver =
        VrRestartPaths.Combine(Win64, VrRestartWorker.DriverModuleName);

    private readonly FakeEnv _env = new();
    private readonly FakeProtocol _protocol = new();
    private readonly TestClock _clock = new();
    private readonly VrRestartWorker _worker;

    public VrRestartWorkerTests() => _worker = new VrRestartWorker(_env, _protocol, _clock);

    // ---------------------------------------------------------------- preflight

    [Theory]
    [InlineData(0)]
    [InlineData(-7)]
    public void InvalidParentPid_PublishesFailureAndNeverSignalsReady(int pid)
    {
        var result = _worker.Run(pid);
        Assert.Equal(VrRestartOutcome.InvalidRequest, result.Outcome);
        Assert.False(result.Succeeded);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
        Assert.Equal(0, _env.MutexAcquireAttempts);
        Assert.Single(_env.Results);
    }

    [Fact]
    public void NoRegisteredRuntime_PublishesFailureWithoutReadyOrLaunch()
    {
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.SteamVrNotInstalled, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
        Assert.Equal(VrRestartMessages.NotInstalled, Message());
    }

    [Fact]
    public void RequestingPidIsNotTheParent_PublishesFailureWithoutReadyOrLaunch()
    {
        _env.Runtimes.Add(SteamVrRoot);
        // The adapter refused the pin: parentPid is not our real parent.
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ParentUnverified, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ParentImageOutsideTheVerifiedRuntime_PublishesFailureWithoutReadyOrLaunch()
    {
        RegisteredRuntime();
        AddParent(ForeignVrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ParentUnverified, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ParentImageThatIsOnlyTheRawAlias_PublishesFailureWithoutReadyOrLaunch()
    {
        RegisteredRuntime();
        // A raw alias is not a resolved path, so it is never accepted
        // against the canonical expectation.
        AddParent(VrRestartPaths.Combine(AliasWin64, "vrserver.exe"), InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ParentUnverified, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void CanonicalParentImageBehindARegisteredRootAlias_IsAccepted()
    {
        HappyPreflight();
        // The whole rest of the handshake runs on the resolved path even
        // though the recorded root is an alias.
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        Assert.Equal(VrstartupImage, Assert.Single(_env.Launches).Exe);
    }

    [Fact]
    public void InstalledDriverModuleInTheInstallRoot_IsTheOnlyAcceptedModule()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        Assert.True(_protocol.ReadyWritten);
    }

    [Fact]
    public void MissingDriverModule_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.Parent!.Modules.Clear();
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ModuleMismatch, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ForeignDriverModule_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.Parent!.Modules.Clear();
        _env.Parent.Modules.Add(@"C:\staging\bin\win64\driver_alvr_server.dll");
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ModuleMismatch, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void DriverModuleInsideTheSteamVrFolder_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        // A driver_alvr_server.dll dropped into the SteamVR win64 folder
        // is not the installed driver, no matter what it is called.
        _env.Parent!.Modules.Clear();
        _env.Parent.Modules.Add(SteamVrFolderDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ModuleMismatch, result.Outcome);
        Assert.Equal(VrRestartMessages.ModuleMismatch, Message());
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void UnreadableParentModuleList_PublishesModuleMismatchWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.ModuleEnumerationThrows = true;
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.ModuleMismatch, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void MissingIntegrityHash_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.FailedIntegrity.Add(InstalledPayload.NativePaths[2]);   // driver_alvr_server.dll
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.IntegrityFailed, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
        // Verification stops at the first mismatch, and it stops before READY.
        Assert.Equal(InstalledPayload.NativePaths[2], _env.VerifiedFiles.Last());
    }

    [Fact]
    public void AbsentIntegrityManifest_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.IntegrityAvailable = false;   // the embedded manifest is missing or invalid
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.IntegrityFailed, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
        Assert.Single(_env.Results);
    }

    [Fact]
    public void UnresolvableInstalledDriver_PublishesIntegrityFailedWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.MissingFiles.Add(InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.IntegrityFailed, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void MissingRuntimeBinary_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.MissingFiles.Add(VrstartupImage);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.RuntimeMissing, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void UnresolvableRuntimeBinary_PublishesRuntimeMissingWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.MissingFiles.Add(VrmonitorImage);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.RuntimeMissing, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ForeignRuntimeMonitor_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.AddProcess(5001, "vrmonitor.exe", @"C:\other\vr\bin\win64\vrmonitor.exe");
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void InaccessibleVrserver_PublishesFailureWithoutReadyOrLaunch()
    {
        HappyPreflight();
        _env.AddProcess(5002, "vrserver.exe", null);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void UnreadableProcessTable_RefusesBeforeReadyAndLaunchesNothing()
    {
        HappyPreflight();
        _env.InspectionThrowOnListCall = 1;   // the first process table read fails
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void UnreadableProcessTableAfterTheCommit_LaunchesNothing()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.InspectionThrowOnListCall = 2;   // the pre-launch scan fails
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.True(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);          // never launch from an untrustworthy table
        Assert.Single(_env.Results);
    }

    // ---------------------------------------------------------------- commit

    [Fact]
    public void NoCommit_PublishesFailureAndLaunchesNothing()
    {
        HappyPreflight();
        _protocol.CommitLine = null;   // EOF, timeout, or a refused line
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.NotCommitted, result.Outcome);
        Assert.True(_protocol.ReadyWritten);
        Assert.Equal(TimeSpan.FromSeconds(10), _protocol.RequestedTimeout);
        Assert.Empty(_env.Launches);
    }

    [Theory]
    [InlineData("VIBERTEMIS_VR_RESTART_COMMIT ")]
    [InlineData("vibertemis_vr_restart_commit")]
    [InlineData("VIBERTEMIS_VR_RESTART_COMMIT2")]
    [InlineData("OK")]
    public void AnythingButTheExactCommitLine_PublishesFailureAndLaunchesNothing(string line)
    {
        HappyPreflight();
        _protocol.CommitLine = line;
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.NotCommitted, result.Outcome);
        Assert.Empty(_env.Launches);
    }

    // ---------------------------------------------------------------- waiting

    [Fact]
    public void OriginalStillAliveAtTheDeadline_PublishesDidNotCloseAndLaunchesNothing()
    {
        HappyPreflight();   // parent never goes away
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.DidNotExit, result.Outcome);
        Assert.Equal("SteamVR did not close. Close SteamVR, then connect again.", Message());
        Assert.Empty(_env.Launches);
        Assert.Equal(45.0, _clock.Elapsed.TotalSeconds, 1);
        // The retained handle is released, and nothing was terminated:
        // disposal is the only operation the worker performs on it.
        Assert.Equal(1, _env.Parent!.DisposeCount);
    }

    [Fact]
    public void WaitsForEveryRetainedHandleInsideOneSharedBudget()
    {
        HappyPreflight();
        var compositor = _env.AddProcess(5005, "vrcompositor.exe", VrcompositorImage);
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.ExitProbe = () => _clock.Elapsed >= TimeSpan.FromSeconds(20);
            compositor.ExitProbe = () => _clock.Elapsed >= TimeSpan.FromSeconds(44);
        };
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        Assert.Single(_env.Launches);
        Assert.Equal(44.0, _clock.Elapsed.TotalSeconds, 1);
        Assert.Equal(1, _env.Parent!.DisposeCount);
        Assert.Equal(1, compositor.DisposeCount);
    }

    [Fact]
    public void RetainedHandleStillAliveAfterTheBudget_PublishesDidNotCloseAndLaunchesNothing()
    {
        HappyPreflight();
        var compositor = _env.AddProcess(5005, "vrcompositor.exe", VrcompositorImage);
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            compositor.ExitProbe = () => _clock.Elapsed >= TimeSpan.FromSeconds(46);
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.DidNotExit, result.Outcome);
        Assert.Equal("SteamVR did not close. Close SteamVR, then connect again.", Message());
        Assert.Empty(_env.Launches);
        Assert.Equal(45.0, _clock.Elapsed.TotalSeconds, 1);
        Assert.Equal(1, compositor.DisposeCount);
    }

    [Theory]
    [InlineData(3600.0)]
    [InlineData(-3600.0)]
    public void ExitWaitIsBoundedByTheMonotonicClockNotTheWallClock(double wallJumpSeconds)
    {
        HappyPreflight();
        // A wall clock jump in either direction can neither end the wait
        // early nor keep it running past the budget.
        _clock.WallJump = TimeSpan.FromSeconds(wallJumpSeconds);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.DidNotExit, result.Outcome);
        Assert.Equal(45.0, _clock.Elapsed.TotalSeconds, 1);
    }

    [Fact]
    public void StartupWaitIsBoundedByTheMonotonicClockNotTheWallClock()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _clock.WallJump = TimeSpan.FromHours(1);   // the launch succeeds, no vrserver appears
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.StartupTimeout, result.Outcome);
        Assert.Equal(30.0, _clock.Elapsed.TotalSeconds, 1);
    }

    // ---------------------------------------------------------------- launching

    [Fact]
    public void LaunchesOnlyAfterEveryRetainedHandleExited()
    {
        HappyPreflight();
        var monitor = _env.AddProcess(5003, "vrmonitor.exe", VrmonitorImage);
        _protocol.OnReadCommit = () => { _env.Parent!.Gone = true; monitor.Gone = true; };
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.True(result.Succeeded);
        var launch = Assert.Single(_env.Launches);
        Assert.True(launch.AllPinnedExited, "vrstartup was started while a VR process was still running");
    }

    [Fact]
    public void Success_LaunchesVrstartupOnceFromTheTrustedRuntime()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        Assert.Equal(VrRestartMessages.Restarted, Message());
        var launch = Assert.Single(_env.Launches);
        // The canonical startup is what is launched: the raw alias is
        // never the file that is started, and never compared with it.
        Assert.Equal(VrstartupImage, launch.Exe);
        Assert.NotEqual(AliasVrstartupImage, launch.Exe);
        Assert.Equal(AliasWin64, launch.WorkingDirectory);
        Assert.Empty(launch.Arguments);
        Assert.Equal(1, launch.MutexHeld);   // held for the whole attempt
    }

    [Fact]
    public void AlreadyRestarted_ReportsSuccessWithoutADuplicateLaunch()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            _env.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        };
        var result = _worker.Run(ParentPid);
        Assert.True(result.Succeeded);
        Assert.Equal("SteamVR was already restarted.", Message());
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ExistingReplacementWithoutOurDriver_IsNeitherDuplicatedNorCalledARestart()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            // A safe mode vrserver: same canonical binary, no driver.
            _env.AddProcess(9001, "vrserver.exe", VrserverImage);
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.StartupTimeout, result.Outcome);
        Assert.Equal(VrRestartMessages.AddonNotLoaded, Message());
        Assert.Empty(_env.Launches);            // never a second vrserver
        Assert.Equal(30.0, _clock.Elapsed.TotalSeconds, 1);
    }

    [Fact]
    public void RestartedWithoutOurDriver_ReportsTheAddonIsBlocked()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.StartupTimeout, result.Outcome);
        Assert.Equal(VrRestartMessages.AddonNotLoaded, Message());
        Assert.Single(_env.Launches);
        Assert.Equal(30.0, _clock.Elapsed.TotalSeconds, 1);
    }

    [Fact]
    public void DriverThatOnlyAppearsLater_StillCountsAsASuccessfulRestart()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        var loaded = false;
        _env.AfterLaunch = e =>
        {
            var replacement = e.AddProcess(9001, "vrserver.exe", VrserverImage);
            e.OnListCall = call => { if (call >= 5 && !loaded) { loaded = true; replacement.Modules.Add(InstalledDriver); } };
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        Assert.Equal(VrRestartMessages.Restarted, Message());
        Assert.Single(_env.Launches);
    }

    [Fact]
    public void ForeignVrserverAfterExit_PublishesFailureWithoutLaunch()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            _env.AddProcess(9001, "vrserver.exe", ForeignVrserverImage);
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void InaccessibleVrserverAfterExit_PublishesFailureWithoutLaunch()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            _env.AddProcess(9001, "vrserver.exe", null);
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void ForeignVrserverBehindAMatchingOne_IsStillRefused()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () =>
        {
            _env.Parent!.Gone = true;
            // A correctly restarted runtime comes up first, then a foreign
            // vrserver behind it. The whole table is classified, so the
            // leading match cannot hide the conflict.
            _env.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
            _env.AddProcess(9002, "vrserver.exe", ForeignVrserverImage);
        };
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.UnexpectedRuntime, result.Outcome);
        Assert.False(result.Succeeded);
        Assert.Equal(VrRestartMessages.UnexpectedRuntime, Message());
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void LaunchFailure_PublishesAFailure()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.LaunchSucceeds = false;
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.LaunchFailed, result.Outcome);
        Assert.Single(_env.Launches);
        Assert.Single(_env.Results);
        Assert.Equal(VrRestartMessages.LaunchFailed, Message());
    }

    [Fact]
    public void StartupTimeout_PublishesAFailure()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        // The launch "succeeds" but no vrserver ever appears.
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.StartupTimeout, result.Outcome);
        Assert.Single(_env.Launches);
        Assert.Equal(30.0, _clock.Elapsed.TotalSeconds, 1);
        Assert.Equal(VrRestartMessages.StartupTimeout, Message());
        Assert.Single(_env.Results);
    }

    // ---------------------------------------------------------------- lifecycle

    [Fact]
    public void CoalescedWorker_DoesNothingAndPublishesNothing()
    {
        HappyPreflight();
        _env.MutexAvailable = false;   // another worker holds the lease
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Coalesced, result.Outcome);
        Assert.False(_protocol.ReadyWritten);
        Assert.Empty(_env.Launches);
        Assert.Empty(_env.Results);
        Assert.Equal(1, _env.MutexAcquireAttempts);
    }

    [Fact]
    public void MutexIsReleasedOnEveryOutcome()
    {
        HappyPreflight();
        _protocol.CommitLine = null;   // refusal outcome
        var failure = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.NotCommitted, failure.Outcome);
        Assert.Equal(0, _env.MutexLeaseHeld);

        // A fresh worker instance per process, one attempt each.
        _protocol.CommitLine = VrRestartProtocol.CommitLine;
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var success = new VrRestartWorker(_env, _protocol, _clock).Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, success.Outcome);
        Assert.Equal(0, _env.MutexLeaseHeld);
        Assert.Equal(2, _env.MutexAcquireAttempts);
    }

    [Fact]
    public void EveryRetainedHandleIsReleasedExactlyOnce()
    {
        HappyPreflight();
        var compositor = _env.AddProcess(5005, "vrcompositor.exe", VrcompositorImage);
        _protocol.OnReadCommit = () => { _env.Parent!.Gone = true; compositor.Gone = true; };
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        _worker.Run(ParentPid);
        Assert.Equal(1, _env.Parent!.DisposeCount);
        Assert.Equal(1, compositor.DisposeCount);
        Assert.Equal(0, _env.MutexLeaseHeld);
    }

    [Fact]
    public void UnexpectedFailure_PublishesExactlyOneResult()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.ThrowOnListCall = 2;
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.WorkerError, result.Outcome);
        Assert.Single(_env.Results);
        Assert.Equal(1, _protocol.ReadyWrites);
    }

    [Fact]
    public void RecordedOutcomeIsNeverOverwritten()
    {
        HappyPreflight();
        var compositor = _env.AddProcess(5005, "vrcompositor.exe", VrcompositorImage);
        compositor.ThrowOnDispose = true;   // the release itself fails
        _protocol.OnReadCommit = () => { _env.Parent!.Gone = true; compositor.Gone = true; };
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        var result = _worker.Run(ParentPid);
        Assert.Equal(VrRestartOutcome.Succeeded, result.Outcome);
        var published = Assert.Single(_env.Results);
        Assert.Contains("\"succeeded\":true", published.Content);
        Assert.Contains(VrRestartMessages.Restarted, published.Content);
    }

    // ---------------------------------------------------------------- result file

    [Fact]
    public void ResultIsWrittenIntoTheManagerStateDirectoryAsBoundedJson()
    {
        HappyPreflight();
        _protocol.OnReadCommit = () => _env.Parent!.Gone = true;
        _env.AfterLaunch = e => e.AddProcess(9001, "vrserver.exe", VrserverImage, InstalledDriver);
        _worker.Run(ParentPid);
        var published = Assert.Single(_env.Results);
        Assert.Equal(@"C:\state\VibertemisVRHostManager\vr-restart-result.json", published.Path);
        Assert.True(published.Content.Length <= 320);
        using var doc = JsonDocument.Parse(published.Content);
        Assert.Equal(1, doc.RootElement.GetProperty("schema").GetInt32());
        Assert.True(doc.RootElement.GetProperty("succeeded").GetBoolean());
        Assert.EndsWith("Z", doc.RootElement.GetProperty("timestampUtc").GetString());
        // No path, PID, or payload detail is ever published.
        Assert.DoesNotContain(SteamVrRoot, published.Content);
        Assert.DoesNotContain(InstallRoot, published.Content);
        Assert.DoesNotContain("4242", published.Content);
    }

    [Fact]
    public void FailureResultIsAlsoBoundedAndMachineReadable()
    {
        HappyPreflight();
        var result = _worker.Run(ParentPid);
        Assert.False(result.Succeeded);
        var published = Assert.Single(_env.Results);
        using var doc = JsonDocument.Parse(published.Content);
        Assert.False(doc.RootElement.GetProperty("succeeded").GetBoolean());
        Assert.Equal(VrRestartMessages.DidNotExit, doc.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public void ResultMessageIsSanitisedAndBounded()
    {
        var long_ = new string('x', 1000) + "\n\r\t" + new string('y', 1000);
        var sanitized = VrRestartResultWriter.Sanitize(long_);
        Assert.True(sanitized.Length <= VrRestartResultWriter.MaxMessageChars);
        Assert.DoesNotContain("\n", sanitized);
        Assert.DoesNotContain("\r", sanitized);
        Assert.DoesNotContain("\t", sanitized);
        Assert.Equal("", VrRestartResultWriter.Sanitize(null));
    }

    // ---------------------------------------------------------------- line reader

    [Fact]
    public void CommitLineIsReadExactly()
    {
        Assert.Equal(VrRestartLineStatus.Line, VrRestartLineReader.TryReadLine(
            Line(VrRestartProtocol.CommitLine + "\r\n"), VrRestartProtocol.MaxLineBytes, out var line));
        Assert.True(VrRestartProtocol.IsCommitLine(line));
    }

    [Fact]
    public void CommitLineWithOnlyLineFeedIsReadExactly()
    {
        Assert.Equal(VrRestartLineStatus.Line, VrRestartLineReader.TryReadLine(
            Line(VrRestartProtocol.CommitLine + "\n"), VrRestartProtocol.MaxLineBytes, out var line));
        Assert.True(VrRestartProtocol.IsCommitLine(line));
    }

    [Fact]
    public void JunkBeforeTheCommitLineIsRefused()
    {
        Assert.Equal(VrRestartLineStatus.Line, VrRestartLineReader.TryReadLine(
            Line("NOTICE hello\nVIBERTEMIS_VR_RESTART_COMMIT\n"), VrRestartProtocol.MaxLineBytes, out var line));
        Assert.False(VrRestartProtocol.IsCommitLine(line));
    }

    [Fact]
    public void EndOfStreamIsRefused()
    {
        using var pipe = new MemoryStream();
        Assert.Equal(VrRestartLineStatus.EndOfStream,
            VrRestartLineReader.TryReadLine(pipe, VrRestartProtocol.MaxLineBytes, out var line));
        Assert.Equal("", line);
    }

    [Fact]
    public void TruncatedCommitIsRefused()
    {
        using var pipe = Line("VIBERTEMIS_VR_RESTART_COMM");
        Assert.Equal(VrRestartLineStatus.Failed,
            VrRestartLineReader.TryReadLine(pipe, VrRestartProtocol.MaxLineBytes, out _));
    }

    [Fact]
    public void OversizeCommitIsRefused()
    {
        using var pipe = Line(new string('z', 4096) + "\n");
        Assert.Equal(VrRestartLineStatus.Oversize,
            VrRestartLineReader.TryReadLine(pipe, VrRestartProtocol.MaxLineBytes, out var line));
        Assert.Equal("", line);
    }

    [Fact]
    public void EveryByteCountsTowardTheLimitIncludingTheLineFeed()
    {
        var exact = new string('z', VrRestartProtocol.MaxLineBytes - 1) + "\n";
        Assert.Equal(VrRestartLineStatus.Line,
            VrRestartLineReader.TryReadLine(Line(exact), VrRestartProtocol.MaxLineBytes, out _));
        // One byte over the budget, with the terminator still last, so the
        // line is refused for its length rather than read and then split.
        var over = new string('z', VrRestartProtocol.MaxLineBytes) + "\n";
        Assert.Equal(VrRestartLineStatus.Oversize,
            VrRestartLineReader.TryReadLine(Line(over), VrRestartProtocol.MaxLineBytes, out _));
    }

    [Fact]
    public void CarriageReturnCountsTowardTheLimitToo()
    {
        var exact = new string('z', VrRestartProtocol.MaxLineBytes - 2) + "\r\n";
        Assert.Equal(VrRestartLineStatus.Line,
            VrRestartLineReader.TryReadLine(Line(exact), VrRestartProtocol.MaxLineBytes, out _));
        var over = new string('z', VrRestartProtocol.MaxLineBytes - 1) + "\r\n";
        Assert.Equal(VrRestartLineStatus.Oversize,
            VrRestartLineReader.TryReadLine(Line(over), VrRestartProtocol.MaxLineBytes, out _));
    }

    [Theory]
    [InlineData("VIBERTEMIS_VR_RESTART_COMMIT\r\r\n")]
    [InlineData("VIBERTEMIS_VR_RESTART_COMMIT\rX\n")]
    [InlineData("VIBERTEMIS_VR_RESTART\r_COMMIT\n")]
    public void EmbeddedCarriageReturnIsRefused(string raw)
    {
        Assert.Equal(VrRestartLineStatus.Malformed,
            VrRestartLineReader.TryReadLine(Line(raw), VrRestartProtocol.MaxLineBytes, out var line));
        Assert.Equal("", line);
    }

    [Fact]
    public void TrailingCarriageReturnWithoutLineFeedIsRefused()
    {
        Assert.Equal(VrRestartLineStatus.Failed, VrRestartLineReader.TryReadLine(
            Line("VIBERTEMIS_VR_RESTART_COMMIT\r"), VrRestartProtocol.MaxLineBytes, out _));
    }

    [Fact]
    public void NonAsciiIsRefused()
    {
        var bytes = Encoding.ASCII.GetBytes(VrRestartProtocol.CommitLine).Concat(new byte[] { 0xe9, (byte)'\n' });
        Assert.Equal(VrRestartLineStatus.Malformed,
            VrRestartLineReader.TryReadLine(Line(bytes.ToArray()), VrRestartProtocol.MaxLineBytes, out _));
    }

    [Fact]
    public void ControlBytesAreRefused()
    {
        Assert.Equal(VrRestartLineStatus.Malformed, VrRestartLineReader.TryReadLine(
            Line(VrRestartProtocol.CommitLine + "\t\n"), VrRestartProtocol.MaxLineBytes, out _));
    }

    [Fact]
    public void ReadFailureIsRefused()
    {
        using var pipe = new TimingOutStream();
        Assert.Equal(VrRestartLineStatus.Failed,
            VrRestartLineReader.TryReadLine(pipe, VrRestartProtocol.MaxLineBytes, out _));
    }

    // ---------------------------------------------------------------- bounded read

    [Fact]
    public void BoundedReadReturnsTheExactCommitLine()
    {
        using var pipe = new MemoryStream(Encoding.ASCII.GetBytes(VrRestartProtocol.CommitLine + "\n"));
        Assert.Equal(VrRestartLineStatus.Line, VrRestartBoundedRead.TryReadLineWithin(
            pipe, VrRestartProtocol.MaxLineBytes, TimeSpan.FromSeconds(5), out var line));
        Assert.True(VrRestartProtocol.IsCommitLine(line));
    }

    [Fact]
    public void BoundedReadWaitsForASlowButCompletingStream()
    {
        var stream = new GatedStream(Encoding.ASCII.GetBytes(VrRestartProtocol.CommitLine + "\n"));
        var status = default(VrRestartLineStatus);
        var line = "";
        var reader = Task.Run(() =>
            status = VrRestartBoundedRead.TryReadLineWithin(
                stream, VrRestartProtocol.MaxLineBytes, TimeSpan.FromSeconds(10), out line));
        try
        {
            Assert.False(reader.Wait(TimeSpan.FromMilliseconds(200)), "the read must still be waiting");
            stream.Release();
            Assert.True(reader.Wait(TimeSpan.FromSeconds(20)));
            Assert.Equal(VrRestartLineStatus.Line, status);
            Assert.True(VrRestartProtocol.IsCommitLine(line));
        }
        finally { stream.Release(); }
    }

    [Fact]
    public void BoundedReadTimesOutOnABlockingStreamInsteadOfWaitingForever()
    {
        var stream = new GatedStream(Array.Empty<byte>());
        var started = Stopwatch.StartNew();
        try
        {
            var status = VrRestartBoundedRead.TryReadLineWithin(
                stream, VrRestartProtocol.MaxLineBytes, TimeSpan.FromMilliseconds(200), out var line);
            started.Stop();
            Assert.Equal(VrRestartLineStatus.TimedOut, status);
            Assert.Equal("", line);
            Assert.True(started.Elapsed < TimeSpan.FromSeconds(20), "the bounded read must return on its own deadline");
            // The input is never disposed on the timeout path: disposing a
            // stream with a pending synchronous read blocks forever.
            Assert.False(stream.Disposed);
        }
        finally { stream.Release(); }   // let the abandoned reader thread finish
    }

    // ---------------------------------------------------------------- arguments

    [Theory]
    [InlineData(new[] { "--restart-vr", "5" }, true, 5)]
    [InlineData(new[] { "--restart-vr", "1" }, true, 1)]
    [InlineData(new[] { "--restart-vr" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "5", "extra" }, false, 0)]
    [InlineData(new[] { "--other", "--restart-vr", "5" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "5", "--other" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "0" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "-5" }, false, 0)]
    [InlineData(new[] { "--restart-vr", " 5" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "5 " }, false, 0)]
    [InlineData(new[] { "--restart-vr", "+5" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "5.0" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "0x5" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "five" }, false, 0)]
    [InlineData(new[] { "--restart-vr", "2147483648" }, false, 0)]
    [InlineData(new[] { "--restart-vr-prefix", "5" }, false, 0)]
    [InlineData(new[] { "--RESTART-VR", "5" }, false, 0)]
    [InlineData(new string[0], false, 0)]
    public void OnlyTheExactTwoArgumentFormIsAccepted(string[] args, bool accepted, int pid)
    {
        Assert.Equal(accepted, VrRestartArguments.TryReadParentPid(args, out var parsed));
        Assert.Equal(pid, parsed);
    }

    // ---------------------------------------------------------------- helpers

    private string Message() =>
        JsonDocument.Parse(_env.Results.Last().Content).RootElement.GetProperty("message").GetString()!;

    private void HappyPreflight()
    {
        RegisteredRuntime();
        AddParent(VrserverImage, InstalledDriver);
    }

    private void RegisteredRuntime()
    {
        _env.Runtimes.Add(SteamVrRoot);
        _protocol.CommitLine = VrRestartProtocol.CommitLine;
    }

    private void AddParent(string? imageFinalPath, params string[] modules) =>
        _env.Parent = _env.AddProcess(ParentPid, "vrserver.exe", imageFinalPath, modules);

    private static Stream Line(string text) => new MemoryStream(Encoding.ASCII.GetBytes(text));

    private static Stream Line(byte[] bytes) => new MemoryStream(bytes);

    private sealed class FakeProcess : IVrRestartProcess
    {
        public int ProcessId { get; }
        public string ImageName { get; }
        public string? ImageFinalPath { get; }
        public List<string> Modules { get; } = new();
        public bool Gone { get; set; }
        public bool ThrowOnDispose { get; set; }
        public Func<bool>? ExitProbe { get; set; }
        public int DisposeCount { get; private set; }
        public DateTime StartTimeUtc { get; } = new DateTime(2026, 1, 2, 2, 0, 0, DateTimeKind.Utc);

        public FakeProcess(int processId, string imageName, string? imageFinalPath, IEnumerable<string> modules)
        {
            ProcessId = processId;
            ImageName = imageName;
            ImageFinalPath = imageFinalPath;
            Modules.AddRange(modules);
        }

        public bool HasExited => ExitProbe?.Invoke() ?? Gone;
        public void Dispose()
        {
            DisposeCount++;
            if (ThrowOnDispose) throw new InvalidOperationException("handle release failed");
        }
    }

    public sealed record LaunchRecord(string Exe, string WorkingDirectory, string[] Arguments, bool AllPinnedExited, int MutexHeld);

    private sealed class FakeEnv : IVrRestartEnvironment
    {
        public string InstallRoot { get; set; } = VrRestartWorkerTests.InstallRoot;
        public string ManagerStateDir { get; set; } = @"C:\state\VibertemisVRHostManager";
        public List<string> Runtimes { get; } = new();
        // Files that exist, in canonical form.
        public HashSet<string> Files { get; } = new(StringComparer.OrdinalIgnoreCase)
        {
            VrserverImage, VrstartupImage, VrmonitorImage, VrcompositorImage, InstalledDriver,
        };
        public HashSet<string> MissingFiles { get; } = new(StringComparer.OrdinalIgnoreCase);
        public HashSet<string> FailedIntegrity { get; } = new(StringComparer.OrdinalIgnoreCase);
        public List<FakeProcess> Processes { get; } = new();
        public List<string> VerifiedFiles { get; } = new();
        public List<string> ResolvedPaths { get; } = new();
        public List<LaunchRecord> Launches { get; } = new();
        public List<(string Path, string Content)> Results { get; } = new();
        public FakeProcess? Parent { get; set; }
        public bool LaunchSucceeds { get; set; } = true;
        public bool IntegrityAvailable { get; set; } = true;
        public bool MutexAvailable { get; set; } = true;
        public bool ModuleEnumerationThrows { get; set; }
        public int MutexAcquireAttempts { get; private set; }
        public int MutexLeaseHeld { get; private set; }
        public int ThrowOnListCall { get; set; } = int.MaxValue;
        public int InspectionThrowOnListCall { get; set; } = int.MaxValue;
        public Action<int>? OnListCall { get; set; }
        public Action<FakeEnv>? AfterLaunch { get; set; }
        private int _listCalls;

        public FakeProcess AddProcess(int processId, string imageName, string? imageFinalPath, params string[] modules)
        {
            var process = new FakeProcess(processId, imageName, imageFinalPath, modules);
            Processes.Add(process);
            return process;
        }

        public IReadOnlyList<string> LocateRegisteredSteamVrRuntimes() => Runtimes;
        public bool FileExists(string path) => Exists(path);
        public bool VerifyInstalledFile(string relativePath)
        {
            VerifiedFiles.Add(relativePath);
            return IntegrityAvailable && !FailedIntegrity.Contains(relativePath);
        }

        public string? ResolveFilePath(string path)
        {
            ResolvedPaths.Add(path);
            return Exists(path) ? Canonical(path) : null;
        }

        // The recorded runtime root is a raw alias, so every real file
        // below it resolves to the canonical runtime, exactly like a file
        // handle based final path lookup.
        private static string Canonical(string path) =>
            path.StartsWith(SteamVrRoot + "\\", StringComparison.OrdinalIgnoreCase)
                ? SteamVrCanonical + path[SteamVrRoot.Length..]
                : path;

        private bool Exists(string path)
        {
            var canonical = Canonical(path);
            return Files.Contains(canonical) && !MissingFiles.Contains(canonical);
        }

        public VrRestartParentPin? TryPinRequestingVrserver(int parentPid, IReadOnlyList<string> runtimeRoots)
        {
            if (Parent is null || Parent.HasExited) return null;
            return new VrRestartParentPin(SteamVrRoot, Parent);
        }

        public IReadOnlyList<string> GetModuleImagePaths(IVrRestartProcess process)
        {
            if (ModuleEnumerationThrows) throw new VrRestartInspectionException("module list unavailable");
            if (process is not FakeProcess fake) throw new VrRestartInspectionException("unknown process");
            return fake.Modules;
        }

        public IReadOnlyList<VrRestartProcessEntry> ListProcesses()
        {
            var call = ++_listCalls;
            if (call == ThrowOnListCall) throw new InvalidOperationException("process table unavailable");
            if (call == InspectionThrowOnListCall) throw new VrRestartInspectionException("process table unreadable");
            OnListCall?.Invoke(call);
            return Processes.Where(p => !p.HasExited)
                .Select(p => new VrRestartProcessEntry(p.ProcessId, p.ImageName))
                .ToArray();
        }

        public IVrRestartProcess? TryPinProcess(int processId)
        {
            foreach (var process in Processes)
            {
                if (process.ProcessId != processId || process.HasExited) continue;
                return process;
            }
            return null;
        }

        public bool TryLaunchVrStartup(string exePath, string workingDirectory)
        {
            var allGone = Processes.All(p => p.HasExited);
            Launches.Add(new LaunchRecord(exePath, workingDirectory, Array.Empty<string>(), allGone, MutexLeaseHeld));
            if (!LaunchSucceeds) return false;
            AfterLaunch?.Invoke(this);
            return true;
        }

        public IVrRestartMutex? TryAcquireMutex(string name)
        {
            MutexAcquireAttempts++;
            if (!MutexAvailable) return null;
            MutexLeaseHeld++;
            return new Lease(this);
        }

        public void WriteResultAtomically(string path, string content) => Results.Add((path, content));

        private sealed class Lease : IVrRestartMutex
        {
            private readonly FakeEnv _owner;
            public Lease(FakeEnv owner) => _owner = owner;
            public void Dispose() => _owner.MutexLeaseHeld--;
        }
    }

    private sealed class FakeProtocol : IVrRestartProtocol
    {
        public string? CommitLine { get; set; }
        public bool ReadyWritten { get; private set; }
        public int ReadyWrites { get; set; }
        public TimeSpan? RequestedTimeout { get; private set; }
        public Action? OnReadCommit { get; set; }

        public void WriteReady()
        {
            ReadyWritten = true;
            ReadyWrites++;
        }

        public bool TryReadCommit(TimeSpan timeout)
        {
            RequestedTimeout = timeout;
            OnReadCommit?.Invoke();
            return VrRestartProtocol.IsCommitLine(CommitLine);
        }
    }

    private sealed class TestClock : IVrRestartClock
    {
        public DateTime Start { get; } = new DateTime(2026, 1, 2, 3, 4, 5, DateTimeKind.Utc);
        public DateTime UtcNow { get; private set; } = new DateTime(2026, 1, 2, 3, 4, 5, DateTimeKind.Utc);
        public TimeSpan Elapsed { get; private set; }
        public int Delays { get; private set; }

        // Applied to the wall clock only, on every wait, to prove that no
        // deadline is ever read from it.
        public TimeSpan WallJump { get; set; }

        public void Delay(TimeSpan duration)
        {
            Delays++;
            Elapsed += duration;
            UtcNow += duration + WallJump;
        }
    }

    private sealed class TimingOutStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => throw new IOException("The pipe has been ended.");
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    // A pipe that blocks on every read until the test releases it.
    private sealed class GatedStream : Stream
    {
        private readonly ManualResetEventSlim _gate = new(false);
        private readonly byte[] _payload;
        private int _index;

        public GatedStream(byte[] payload) => _payload = payload;

        public bool Disposed { get; private set; }

        public void Release() => _gate.Set();

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }

        public override int Read(byte[] buffer, int offset, int count)
        {
            _gate.Wait();
            if (_index >= _payload.Length) return 0;
            buffer[offset] = _payload[_index++];
            return 1;
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }
}
