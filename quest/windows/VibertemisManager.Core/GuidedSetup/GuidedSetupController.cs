// Guided setup readiness model + controller.
//
// The controller is the single source of truth for "is this PC
// ready to host Vibertemis VR?" The manager UI consults it via
// Readiness.IsPcReady; the companion launch path refuses to
// start until readiness reports ready.
//
// State:
//   * Checking     - we are inspecting prerequisites
//   * ActionNeeded - one or more prerequisites need attention
//   * PcReady      - all static prerequisites are satisfied
//
// Connected is reported ONLY when an external proof of a fresh
// authenticated live stream is available via IConnectivitySignal.
// Process running is NOT connected - a stale process is not
// proof of today's headset. The default signal returns false.
//
// Bridge matching is semantic: the controller compares the
// registered record's UserSid against the active console user's
// SID, the CompanionPath against the canonical manager-known
// companion path, and the SunshinePath against the resolved
// SunshineService binary path. A stale registration with a
// different SID, path, or session is reported as Stale so the
// runner can re-register.
using System;
using System.Collections.Generic;
using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.Prerequisites;
using VibertemisManager.Core.Settings;

namespace VibertemisManager.Core.GuidedSetup;

public enum GuidedSetupState
{
    Checking,
    ActionNeeded,
    PcReady,
}

public enum PrerequisiteStatus
{
    Satisfied,
    Missing,
    TooOld,
    Corrupt,
    Stale,
    Unknown,
}

public sealed record PrerequisiteSnapshot(
    string Name,
    PrerequisiteStatus Status,
    string Detail);

public sealed record ReadinessReport(
    GuidedSetupState State,
    IReadOnlyList<PrerequisiteSnapshot> Prerequisites,
    bool Connected,
    string Headline,
    string? NextAction,
    VcRuntimeStatus? Runtime,
    BridgeSnapshotResult Bridge)
{
    public bool IsPcReady => State == GuidedSetupState.PcReady;
    public bool IsReadyToLaunchCompanion => IsPcReady;
}

// Connectivity signal. There is currently no real proof wired
// in this phase; the default returns false so the UI never
// claims a green Ready indicator based on a process being up.
public interface IConnectivitySignal
{
    bool HasGenuineLiveStream { get; }
}

public sealed class NoConnectivitySignal : IConnectivitySignal
{
    public bool HasGenuineLiveStream => false;
}

// The bridge manager exposes both raw Read and a semantic
// Snapshot. The controller only consumes Snapshot so semantic
// identity / path / user-SID mismatches surface as Stale.
public enum BridgeSnapshotOutcome
{
    Present,
    Absent,
    OtherUserActive,
    StalePath,
    AccessDenied,
    Corrupt,
}

public sealed record BridgeSnapshotResult(
    BridgeSnapshotOutcome Outcome,
    string Detail,
    BridgeReadResult RawRead)
{
    public bool IsCurrentUserPresent => Outcome == BridgeSnapshotOutcome.Present;
}

public sealed class GuidedSetupController
{
    private readonly IVcRuntimeDetector _runtimeDetector;
    private readonly IBridgeManager _bridgeManager;
    private readonly ISettingsStore _settingsStore;
    private readonly IConnectivitySignal _connectivity;
    private readonly Func<string?> _currentUserSid;
    private readonly Func<int> _activeConsoleSession;
    private readonly Func<bool> _isWindows;
    private readonly object _gate = new();
    private ReadinessReport _last;

    public GuidedSetupController(
        IVcRuntimeDetector runtimeDetector,
        IBridgeManager bridgeManager,
        ISettingsStore settingsStore,
        IConnectivitySignal connectivity,
        Func<string?>? currentUserSid = null,
        Func<int>? activeConsoleSession = null,
        Func<bool>? isWindows = null)
    {
        _runtimeDetector = runtimeDetector;
        _bridgeManager = bridgeManager;
        _settingsStore = settingsStore;
        _connectivity = connectivity;
        _currentUserSid = currentUserSid ?? (() => null);
        _activeConsoleSession = activeConsoleSession ?? (() => 0);
        _isWindows = isWindows ?? (() => OperatingSystem.IsWindows());
        _last = EmptyReport();
    }

    public ReadinessReport Current
    {
        get { lock (_gate) return _last; }
    }

    public ReadinessReport Detect()
    {
        if (!_isWindows())
        {
            var snap = new BridgeSnapshotResult(BridgeSnapshotOutcome.Absent,
                "non-Windows", new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "non-Windows"));
            return LockAndReturn(new[]
            {
                new PrerequisiteSnapshot("Host platform",
                    PrerequisiteStatus.Unknown,
                    "Guided setup is Windows-only."),
            }, null, snap, false, "Unsupported platform.", null);
        }

        var snapshots = new List<PrerequisiteSnapshot>();
        var runtime = _runtimeDetector.Detect();
        var vc = VcRuntimeRequirements.Classify(runtime, VcRuntimeRequirements.MinimumX64Runtime);
        snapshots.Add(new PrerequisiteSnapshot(
            "Visual C++ x64 runtime",
            MapVc(vc),
            runtime.Detail));

        var expectedCompanion = _bridgeManager.CanonicalCompanionPath;
        var expectedSunshine = SafeSunshinePath();
        var bridgeSnap = SnapshotBridge(expectedCompanion, expectedSunshine);
        snapshots.Add(MapBridge(bridgeSnap));

        var state = AllSatisfied(snapshots)
            ? GuidedSetupState.PcReady
            : GuidedSetupState.ActionNeeded;
        var headline = state == GuidedSetupState.PcReady
            ? "PC is ready to host Vibertemis VR."
            : "One or more prerequisites need attention.";
        var next = state == GuidedSetupState.PcReady ? null : PickNextAction(snapshots);
        return LockAndReturn(snapshots, runtime, bridgeSnap, _connectivity.HasGenuineLiveStream,
            headline, next);
    }

    // True when all static prerequisites are satisfied. The
    // companion may launch; a Connected verdict requires an
    // external live-stream proof which is wired separately.
    private bool PrerequisitesSatisfied(IReadOnlyList<PrerequisiteSnapshot> snaps)
        => AllSatisfied(snaps);

    private string? SafeSunshinePath()
    {
        try { return _bridgeManager.ResolveSunshinePath(); }
        catch { return null; }
    }

    private BridgeSnapshotResult SnapshotBridge(string expectedCompanion, string? expectedSunshine)
    {
        var read = _bridgeManager.Read();
        if (_activeConsoleSession() < 0 || string.IsNullOrEmpty(expectedSunshine))
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.StalePath,
                "Open Setup VR in your active Windows account after installing Vibeshine.", read);
        if (read.Outcome == BridgeReadOutcome.AccessDenied)
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.AccessDenied, read.Detail, read);
        if (read.Outcome == BridgeReadOutcome.Corrupt)
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.Corrupt, read.Detail, read);
        if (read.Outcome != BridgeReadOutcome.Present)
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.Absent, "Bridge not registered.", read);

        var activeSid = _currentUserSid();
        if (string.IsNullOrEmpty(activeSid))
        {
            // Caller did not provide a SID; we cannot prove the
            // current user owns this bridge. Report Stale so the
            // runner re-registers under the current session.
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.StalePath,
                "Bridge exists but the current user SID is unknown.", read);
        }
        if (!string.Equals(read.UserSid, activeSid, StringComparison.Ordinal))
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.OtherUserActive,
                "Bridge SID " + read.UserSid + " does not match the current user " + activeSid, read);
        if (!CanonicalPathsEqual(read.CompanionPath, expectedCompanion))
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.StalePath,
                "Bridge CompanionPath " + read.CompanionPath + " does not match expected " + expectedCompanion, read);
        if (!CanonicalSunshineEqual(read.SunshinePath, expectedSunshine))
            return new BridgeSnapshotResult(BridgeSnapshotOutcome.StalePath,
                "Bridge SunshinePath " + (read.SunshinePath ?? "<empty>") +
                " does not match expected " + (expectedSunshine ?? "<empty>"), read);
        return new BridgeSnapshotResult(BridgeSnapshotOutcome.Present, "ok", read);
    }

    private ReadinessReport LockAndReturn(
        IReadOnlyList<PrerequisiteSnapshot> snaps, VcRuntimeStatus? runtime,
        BridgeSnapshotResult bridge, bool connected, string headline, string? next)
    {
        var state = AllSatisfied(snaps) ? GuidedSetupState.PcReady : GuidedSetupState.ActionNeeded;
        var report = new ReadinessReport(state, snaps, connected, headline, next, runtime, bridge);
        lock (_gate) _last = report;
        return report;
    }

    private static ReadinessReport EmptyReport()
        => new(GuidedSetupState.Checking, Array.Empty<PrerequisiteSnapshot>(), false,
            "Checking prerequisites...", null, null,
            new BridgeSnapshotResult(BridgeSnapshotOutcome.Absent, "initial",
                new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "initial")));

    private static bool AllSatisfied(IReadOnlyList<PrerequisiteSnapshot> snaps)
    {
        foreach (var s in snaps)
            if (s.Status != PrerequisiteStatus.Satisfied) return false;
        return true;
    }

    private static PrerequisiteStatus MapVc(VcSatisfaction v) => v switch
    {
        VcSatisfaction.Satisfied => PrerequisiteStatus.Satisfied,
        VcSatisfaction.Missing => PrerequisiteStatus.Missing,
        VcSatisfaction.TooOld => PrerequisiteStatus.TooOld,
        VcSatisfaction.Corrupt => PrerequisiteStatus.Corrupt,
        _ => PrerequisiteStatus.Unknown,
    };

    private static PrerequisiteSnapshot MapBridge(BridgeSnapshotResult snap) => snap.Outcome switch
    {
        BridgeSnapshotOutcome.Present => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Satisfied, "Companion path registered for the current user."),
        BridgeSnapshotOutcome.Absent => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Missing, "Bridge is not registered."),
        BridgeSnapshotOutcome.StalePath => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Stale, "Registered bridge paths no longer match this install: " + snap.Detail),
        BridgeSnapshotOutcome.OtherUserActive => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Stale, "Bridge is registered to a different user: " + snap.Detail),
        BridgeSnapshotOutcome.Corrupt => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Corrupt, "Bridge key is incomplete: " + snap.Detail),
        BridgeSnapshotOutcome.AccessDenied => new PrerequisiteSnapshot("VR bridge registration",
            PrerequisiteStatus.Unknown, "Bridge key could not be read: " + snap.Detail),
        _ => new PrerequisiteSnapshot("VR bridge registration", PrerequisiteStatus.Unknown, snap.Detail),
    };

    private static string? PickNextAction(IReadOnlyList<PrerequisiteSnapshot> snaps)
    {
        foreach (var s in snaps)
        {
            if (s.Status == PrerequisiteStatus.Missing)
                return s.Name + ": not installed.";
            if (s.Status == PrerequisiteStatus.TooOld)
                return s.Name + ": installed but too old; update the runtime.";
            if (s.Status == PrerequisiteStatus.Stale)
                return s.Name + ": registration is out of date.";
            if (s.Status == PrerequisiteStatus.Corrupt)
                return s.Name + ": registry is corrupt; rerun the redistributable.";
        }
        return null;
    }

    private static bool CanonicalPathsEqual(string? a, string? b)
    {
        if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
        try
        {
            return string.Equals(
                System.IO.Path.GetFullPath(a).Replace('\\', '/').TrimEnd('/'),
                System.IO.Path.GetFullPath(b).Replace('\\', '/').TrimEnd('/'),
                StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static bool CanonicalSunshineEqual(string? a, string? b)
    {
        if (string.IsNullOrEmpty(a) && string.IsNullOrEmpty(b)) return true;
        if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
        return CanonicalPathsEqual(a, b);
    }
}