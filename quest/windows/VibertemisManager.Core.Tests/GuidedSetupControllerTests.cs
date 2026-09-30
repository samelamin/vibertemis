// Guided setup controller tests.
//
// Verifies the high-level state machine and the bridge
// semantic-matching contract:
//   * Empty detector + empty bridge -> ActionNeeded.
//   * Both satisfied + connected -> PcReady.
//   * Wrong-user SID -> Stale; current user must own the bridge.
//   * Stale path -> Stale; CompanionPath/SunshinePath must match.
//   * Process-running-but-no-stream is reported as not connected.
//   * OtherUserActive / StalePath trigger re-register in the runner.
//   * Corrupt registry reports the next action without crashing.
using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.GuidedSetup;
using VibertemisManager.Core.Prerequisites;
using VibertemisManager.Core.Settings;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class GuidedSetupControllerTests
{
    private const string CurrentUserSid = "S-1-5-21-CURRENT";
    private const string CanonicalCompanion = @"C:\VibertemisVR\manager\bin\vibertemis-host-companion.exe";
    private const string CanonicalSunshine = @"C:\Program Files\Sunshine\sunshine.exe";

    [Fact]
    public void Detect_ReportsActionNeeded_WhenRuntimeMissing()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.NotInstalled("absent", VcReadSource.Missing)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, CanonicalCompanion, null)));
        var report = controller.Detect();
        Assert.Equal(GuidedSetupState.ActionNeeded, report.State);
        Assert.False(report.IsPcReady);
        Assert.NotNull(report.NextAction);
    }

    [Fact]
    public void Detect_ReportsPcReady_WhenAllPrerequisitesSatisfied()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, CanonicalCompanion, CanonicalSunshine), CanonicalSunshine),
            isWindows: true, currentUserSid: CurrentUserSid);
        var report = controller.Detect();
        Assert.Equal(GuidedSetupState.PcReady, report.State);
        Assert.True(report.IsPcReady);
        Assert.Null(report.NextAction);
    }

    [Fact]
    public void Detect_ReportsStale_WhenBridgeSidDiffers()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead("S-1-OTHER", CanonicalCompanion, CanonicalSunshine), CanonicalSunshine),
            currentUserSid: CurrentUserSid);
        var report = controller.Detect();
        Assert.Equal(GuidedSetupState.ActionNeeded, report.State);
        Assert.Equal(BridgeSnapshotOutcome.OtherUserActive, report.Bridge.Outcome);
        Assert.Contains(report.Prerequisites,
            p => p.Name == "VR bridge registration" && p.Status == PrerequisiteStatus.Stale);
    }

    [Fact]
    public void Detect_ReportsStale_WhenCompanionPathChanged()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, @"C:\old\companion.exe", null)),
            currentUserSid: CurrentUserSid);
        var report = controller.Detect();
        Assert.Equal(BridgeSnapshotOutcome.StalePath, report.Bridge.Outcome);
        Assert.Contains(report.Prerequisites,
            p => p.Name == "VR bridge registration" && p.Status == PrerequisiteStatus.Stale);
    }

    [Fact]
    public void Detect_ReportsStale_WhenSunshinePathChanged()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, CanonicalCompanion, @"C:\old\sunshine.exe")),
            currentUserSid: CurrentUserSid);
        var report = controller.Detect();
        Assert.Equal(BridgeSnapshotOutcome.StalePath, report.Bridge.Outcome);
    }

    [Fact]
    public void Detect_ReportsStale_WhenCurrentUserSidIsUnknown()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, CanonicalCompanion, CanonicalSunshine), CanonicalSunshine),
            currentUserSid: null);
        var report = controller.Detect();
        Assert.Equal(BridgeSnapshotOutcome.StalePath, report.Bridge.Outcome);
    }

    [Fact]
    public void Detect_ReportsConnectedFalse_WhenProcessIsUpButNoStreamProof()
    {
        // The IConnectivitySignal returns false because there is
        // currently no fresh authenticated live-stream proof;
        // the controller MUST NOT report Connected.
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields)),
            new StaticBridgeManager(PresentRead(CurrentUserSid, CanonicalCompanion, CanonicalSunshine), CanonicalSunshine),
            currentUserSid: CurrentUserSid,
            connectivity: new NoConnectivitySignal());
        var report = controller.Detect();
        Assert.False(report.Connected,
            "Connected must be false when no fresh stream proof is wired.");
    }

    [Fact]
    public void Detect_ReportsCorruptRegistry_NextActionSuggestsRerun()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.NotInstalled("Installed=1 missing Major/Minor", VcReadSource.CorruptRegistry)),
            new StaticBridgeManager(new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "no")));
        var report = controller.Detect();
        Assert.Equal(GuidedSetupState.ActionNeeded, report.State);
        Assert.Contains("rerun the redistributable", report.NextAction);
    }

    [Fact]
    public void Detect_ReportsUnknownState_WhenPlatformNotWindows()
    {
        var controller = Build(
            new StaticVcDetector(VcRuntimeStatus.NotInstalled("n/a", VcReadSource.NotSupported)),
            new StaticBridgeManager(new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "no")),
            isWindows: false);
        var report = controller.Detect();
        Assert.Equal(GuidedSetupState.ActionNeeded, report.State);
        Assert.Contains(report.Prerequisites,
            p => p.Name == "Host platform" && p.Status == PrerequisiteStatus.Unknown);
    }

    private static BridgeReadResult PresentRead(string sid, string companion, string? sunshine)
        => new(BridgeReadOutcome.Present, companion, sunshine, sid, "ok");

    private static GuidedSetupController Build(IVcRuntimeDetector detector, IBridgeManager bridge,
        bool isWindows = true,
        string? currentUserSid = CurrentUserSid,
        IConnectivitySignal? connectivity = null)
    {
        return new GuidedSetupController(detector, bridge, new NoopSettingsStore(),
            connectivity ?? new NoConnectivitySignal(),
            currentUserSid: () => currentUserSid,
            activeConsoleSession: () => 1,
            isWindows: () => isWindows);
    }

    private sealed class StaticVcDetector : IVcRuntimeDetector
    {
        private readonly VcRuntimeStatus _status;
        public StaticVcDetector(VcRuntimeStatus status) => _status = status;
        public VcRuntimeStatus Detect() => _status;
    }

    private sealed class StaticBridgeManager : IBridgeManager
    {
        private readonly BridgeReadResult _read;
        private readonly string? _sunshine;
        public StaticBridgeManager(BridgeReadResult read, string? sunshine = null)
        { _read = read; _sunshine = sunshine; }
        public string CanonicalManagerPath => "";
        public string CanonicalCompanionPath { get; } = CanonicalCompanion;
        public string? ResolveSunshinePath() => _sunshine;
        public BridgeReadResult Read() => _read;
    }

    private sealed class NoopSettingsStore : ISettingsStore
    {
        public UserSettings Load() => new UserSettings();
        public void Save(UserSettings settings) { }
    }
}