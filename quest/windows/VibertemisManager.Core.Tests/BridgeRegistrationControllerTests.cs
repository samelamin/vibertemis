// Bridge registration controller tests.
//
// Verifies the helper-side flow:
//   * Caller PID is VERIFIED against the kernel (not trusted).
//   * Wrong canonical image path -> WrongImage (not written).
//   * Wrong session id -> WrongSession (not written).
//   * Missing Sunshine -> NotInstalled is propagated, SunshinePath
//     is cleared but the companion record is still written so the
//     headset can pair without Vibeshine.
//   * Rollback on partial write failure.
//   * Read returns the existing record when current.
//   * Snapshot() reports OtherUserActive when a different user's
//     companion is registered.
using VibertemisManager.Core.Bridge;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class BridgeRegistrationControllerTests
{
    private const string CanonicalManager = @"C:\ProgramData\VibertemisVR\manager\VibertemisManager.App.exe";
    private const string CanonicalCompanion = @"C:\ProgramData\VibertemisVR\manager\bin\vibertemis-host-companion.exe";
    private const string SunshinePath = @"C:\Program Files\Sunshine\sunshine.exe";

    [Fact]
    public void HandleRegistration_Writes_WhenCallerAndSessionValid()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, CanonicalManager, 1, "S-1-5-21-abc");
        var scm = new FakeScmProbe();
        scm.Sunshine = SunshinePath;
        var reader = new FakeKeyReader();
        var writer = new FakeKeyWriter();
        var controller = new BridgeRegistrationController(probe, scm, reader, writer,
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.Written, result.Outcome);
        Assert.NotNull(result.Record);
        Assert.Equal(CanonicalCompanion, result.Record!.CompanionPath);
        Assert.Equal(SunshinePath, result.Record.SunshinePath);
        Assert.Equal("S-1-5-21-abc", result.Record.UserSid);
        Assert.Equal(1, writer.WriteCalls);
    }

    [Fact]
    public void HandleRegistration_RejectsWrongImagePath()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, @"C:\evil\VibertemisManager.App.exe", 1, "S-1");
        var controller = new BridgeRegistrationController(probe, new FakeScmProbe(), new FakeKeyReader(), new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.WrongImage, result.Outcome);
        Assert.Null(result.Record);
    }

    [Fact]
    public void HandleRegistration_RejectsWrongSession()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, CanonicalManager, 2, "S-1");
        var controller = new BridgeRegistrationController(probe, new FakeScmProbe(), new FakeKeyReader(), new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.WrongSession, result.Outcome);
    }

    [Fact]
    public void HandleRegistration_RejectsAccessDeniedProbe()
    {
        var probe = new FakeProcessProbe();
        // Mark the PID as access denied (Missing status) - the helper
        // refused to verify the caller.
        probe.MissingPids.Add(1234);
        var controller = new BridgeRegistrationController(probe, new FakeScmProbe(), new FakeKeyReader(), new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.WrongImage, result.Outcome);
    }

    [Fact]
    public void HandleRegistration_PreservesRegistration_WhenServiceAbsent()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, CanonicalManager, 1, "S-1");
        var scm = new FakeScmProbe { Sunshine = null }; // Sunshine not installed
        var writer = new FakeKeyWriter();
        var controller = new BridgeRegistrationController(probe, scm, new FakeKeyReader(), writer,
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.SunshineUnresolved, result.Outcome);
        Assert.Null(result.Record);
    }

    [Fact]
    public void HandleRegistration_RestoresExisting_WhenWriteFails()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, CanonicalManager, 1, "S-1");
        var scm = new FakeScmProbe { Sunshine = SunshinePath };
        var writer = new FakeKeyWriter();
        writer.WriteOutcome = BridgeWriteOutcome.AccessDenied;
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Present, CanonicalCompanion, SunshinePath, "S-1", "ok")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, writer,
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.AccessDenied, result.Outcome);
        Assert.Equal(0, writer.RollbackCalls);
        Assert.Equal(2, writer.WriteCalls);
        Assert.Equal(CanonicalCompanion, writer.LastCompanion);
        Assert.Equal(SunshinePath, writer.LastSunshine);
    }

    [Fact]
    public void HandleRegistration_ClearsPartialWrite_WhenNothingWasRegistered()
    {
        var probe = new FakeProcessProbe();
        probe.Map[1234] = new CallerIdentity(1234, CanonicalManager, 1, "S-1");
        var scm = new FakeScmProbe { Sunshine = SunshinePath };
        var writer = new FakeKeyWriter { WriteOutcome = BridgeWriteOutcome.AccessDenied };
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "no")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, writer,
            CanonicalManager, CanonicalCompanion, () => true);
        controller.SetActiveSessionId(1);
        var result = controller.HandleRegistration(1234);
        Assert.Equal(BridgeWriteOutcome.AccessDenied, result.Outcome);
        Assert.Equal(1, writer.RollbackCalls);
    }

    [Fact]
    public void Snapshot_ReportsPresent_WhenCurrent()
    {
        var probe = new FakeProcessProbe();
        var scm = new FakeScmProbe { Sunshine = SunshinePath };
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Present,
                CanonicalCompanion, SunshinePath, "S-1-5-21-abc", "ok")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        var outcome = controller.Snapshot("S-1-5-21-abc", CanonicalCompanion, SunshinePath);
        Assert.Equal(BridgeReadOutcome.Present, outcome);
    }

    [Fact]
    public void Snapshot_ReportsOtherUserActive_WhenSidDiffers()
    {
        var probe = new FakeProcessProbe();
        var scm = new FakeScmProbe { Sunshine = SunshinePath };
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Present,
                CanonicalCompanion, SunshinePath, "S-1-OTHER", "ok")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        var outcome = controller.Snapshot("S-1-5-21-abc", CanonicalCompanion, SunshinePath);
        Assert.Equal(BridgeReadOutcome.OtherUserActive, outcome);
    }

    [Fact]
    public void Snapshot_ReportsOtherUserActive_WhenCompanionPathChanged()
    {
        var probe = new FakeProcessProbe();
        var scm = new FakeScmProbe { Sunshine = SunshinePath };
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Present,
                @"C:\other\companion.exe", SunshinePath, "S-1-5-21-abc", "ok")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        var outcome = controller.Snapshot("S-1-5-21-abc", CanonicalCompanion, SunshinePath);
        Assert.Equal(BridgeReadOutcome.OtherUserActive, outcome);
    }

    [Fact]
    public void Snapshot_ReportsPresent_WhenSunshineClearedOnBothSides()
    {
        var probe = new FakeProcessProbe();
        var scm = new FakeScmProbe { Sunshine = null };
        var reader = new FakeKeyReader
        {
            Returned = new BridgeReadResult(BridgeReadOutcome.Present,
                CanonicalCompanion, "", "S-1-5-21-abc", "ok")
        };
        var controller = new BridgeRegistrationController(probe, scm, reader, new FakeKeyWriter(),
            CanonicalManager, CanonicalCompanion, () => true);
        var outcome = controller.Snapshot("S-1-5-21-abc", CanonicalCompanion, null);
        Assert.Equal(BridgeReadOutcome.Present, outcome);
    }

    private sealed class FakeProcessProbe : IBridgeProcessProbe
    {
        public Dictionary<int, CallerIdentity?> Map { get; } = new();
        public HashSet<int> MissingPids { get; } = new();
        public CallerVerifyResult Resolve(int processId, string expectedCanonicalImagePath)
        {
            if (MissingPids.Contains(processId))
                return new CallerVerifyResult(CallerVerifyStatus.Missing, "missing", null);
            if (!Map.TryGetValue(processId, out var id))
                return new CallerVerifyResult(CallerVerifyStatus.AccessDenied, "denied", null);
            if (id is null)
                return new CallerVerifyResult(CallerVerifyStatus.AccessDenied, "denied", null);
            if (!string.Equals(id.CanonicalImagePath, expectedCanonicalImagePath, System.StringComparison.OrdinalIgnoreCase))
                return new CallerVerifyResult(CallerVerifyStatus.WrongImagePath,
                    "image " + id.CanonicalImagePath + " != " + expectedCanonicalImagePath, null);
            return new CallerVerifyResult(CallerVerifyStatus.Valid, "ok", id);
        }
    }

    private sealed class FakeScmProbe : IBridgeScmProbe
    {
        public string? Sunshine { get; set; }
        public ScmQueryResult ResolveSunshine(string serviceName, string sunshineExeBasename)
        {
            return Sunshine is null
                ? ScmQueryResult.NotInstalled("absent")
                : ScmQueryResult.Resolved(Sunshine, "ok");
        }
    }

    private sealed class FakeKeyReader : IBridgeKeyReader
    {
        public BridgeReadResult Returned { get; set; } =
            new(BridgeReadOutcome.Absent, null, null, null, "absent");
        public BridgeReadResult Read(string hive, string subKey) => Returned;
    }

    private sealed class FakeKeyWriter : IBridgeKeyWriter
    {
        public BridgeWriteOutcome WriteOutcome = BridgeWriteOutcome.Written;
        public int WriteCalls;
        public int RollbackCalls;
        public string? LastCompanion;
        public string? LastSunshine;
        public string? LastSid;
        public BridgeWriteOutcome Write(string hive, string subKey, string companionPath, string? sunshinePath, string userSid)
        {
            WriteCalls++;
            LastCompanion = companionPath;
            LastSunshine = sunshinePath;
            LastSid = userSid;
            return WriteOutcome;
        }
        public void Rollback(string hive, string subKey) { RollbackCalls++; }
    }
}