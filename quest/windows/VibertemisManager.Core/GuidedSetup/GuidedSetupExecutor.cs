// Guided setup executor - the detect -> act -> recheck engine.
//
// Lives in Core so portable tests exercise the full state
// machine without referencing App-level types. The App's
// MainForm.GuidedSetup partial is a thin adapter that
// instantiates this executor with the real
// VcRedistInstaller / IUacHelper dependencies; tests inject
// in-memory delegates.
//
// State machine:
//   1. Detect via the controller (current-user SID + canonical
//      companion + canonical sunshine all checked).
//   2. If already PrcReady, return AlreadyReady (we do NOT
//      depend on Connected - the controller's Connected
//      signal reports false when no fresh authenticated live-
//      stream proof exists; that is the truthful default and
//      does not block PC readiness).
//   3. If runtime missing/too-old: invoke the runtime install
//      delegate. The delegate MUST reuse its launcher across
//      retries so a slow install is observed, never duplicated.
//   4. Recheck after VC install.
//   5. If bridge snapshot is Absent / StalePath /
//      OtherUserActive / Corrupt: invoke the bridge register
//      delegate (which the App wires to UacHelper + helper).
//   6. Final recheck.
//
// Returns a GuidedSetupOutcome the App uses to drive the
// existing PrepareVr() flow.
using System;
using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.Core.GuidedSetup;

public enum GuidedSetupOutcome
{
    AlreadyReady,
    Succeeded,
    StillRunning,
    PackageCorrupt,
    Denied,
    RestartRequired,
    HostMissing,
    BridgeAmbiguous,
}

public enum VcInstallOutcome
{
    Success,
    AlreadyInstalled,
    StillRunning,
    RestartRequired,
    Denied,
    PackageCorrupt,
    Other,
}

public sealed record VcInstallResult(VcInstallOutcome Outcome, string Detail, VcRuntimeStatus VerifiedRuntime);

public enum BridgeRegisterOutcome
{
    Written,
    WrongImage,
    WrongSession,
    Denied,
    SunshineUnresolved,
    CompanionMissing,
    AmbiguousUser,
    Other,
}

public sealed record BridgeRegisterResult(BridgeRegisterOutcome Outcome, string Detail);

// Delegates the executor calls. The App wires these to
// VcRedistInstaller + WindowsBridgeRegistration; tests wire
// in-memory implementations.
public interface IVcInstallAction
{
    VcInstallResult EnsureInstalled();
}

public interface IBridgeRegisterAction
{
    BridgeRegisterResult Register();
}

public sealed class GuidedSetupExecutor
{
    private readonly GuidedSetupController _controller;
    private readonly IVcInstallAction _vcInstall;
    private readonly IBridgeRegisterAction _bridgeRegister;
    private readonly Action<string> _log;

    public GuidedSetupExecutor(
        GuidedSetupController controller,
        IVcInstallAction vcInstall,
        IBridgeRegisterAction bridgeRegister,
        Action<string> log)
    {
        _controller = controller;
        _vcInstall = vcInstall;
        _bridgeRegister = bridgeRegister;
        _log = log;
    }

    public GuidedSetupOutcome RunOnce()
    {
        var initial = _controller.Detect();
        _log(initial.Headline);

        // AlreadyReady fast path depends on the static
        // prerequisites, NOT on Connected. Process-running is
        // not Connected; we have no fresh stream proof wired
        // in this phase, but that is independent of whether the
        // PC itself is ready to host.
        if (initial.IsPcReady) return GuidedSetupOutcome.AlreadyReady;

        if (NeedsVcInstall(initial))
        {
            var installResult = _vcInstall.EnsureInstalled();
            _log(installResult.Detail);
            switch (installResult.Outcome)
            {
                case VcInstallOutcome.Success:
                case VcInstallOutcome.AlreadyInstalled:
                    break;
                case VcInstallOutcome.RestartRequired:
                    return GuidedSetupOutcome.RestartRequired;
                case VcInstallOutcome.Denied:
                    return GuidedSetupOutcome.Denied;
                case VcInstallOutcome.StillRunning:
                    return GuidedSetupOutcome.StillRunning;
                default:
                    return GuidedSetupOutcome.PackageCorrupt;
            }
        }

        var midReport = _controller.Detect();
        _log(midReport.Headline);

        if (NeedsBridgeAction(midReport))
        {
            var regResult = _bridgeRegister.Register();
            _log(regResult.Detail);
            switch (regResult.Outcome)
            {
                case BridgeRegisterOutcome.Written:
                    break;
                case BridgeRegisterOutcome.Denied:
                    return GuidedSetupOutcome.Denied;
                case BridgeRegisterOutcome.AmbiguousUser:
                    return GuidedSetupOutcome.BridgeAmbiguous;
                case BridgeRegisterOutcome.SunshineUnresolved:
                    return GuidedSetupOutcome.HostMissing;
                default:
                    return GuidedSetupOutcome.PackageCorrupt;
            }
        }

        var finalReport = _controller.Detect();
        _log(finalReport.Headline);
        return finalReport.IsPcReady
            ? GuidedSetupOutcome.Succeeded
            : GuidedSetupOutcome.PackageCorrupt;
    }

    private static bool NeedsVcInstall(ReadinessReport report)
    {
        if (report.Runtime is null) return true;
        return VcRuntimeRequirements.Classify(report.Runtime,
            VcRuntimeRequirements.MinimumX64Runtime) != VcSatisfaction.Satisfied;
    }

    private static bool NeedsBridgeAction(ReadinessReport report)
        => report.Bridge.Outcome is
            BridgeSnapshotOutcome.Absent
            or BridgeSnapshotOutcome.StalePath
            or BridgeSnapshotOutcome.OtherUserActive
            or BridgeSnapshotOutcome.Corrupt;
}