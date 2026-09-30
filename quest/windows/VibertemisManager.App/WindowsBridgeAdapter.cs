// Windows-side IBridgeManager adapter + bridge registration
// helper.
//
// Adapter exposes the testable Core IBridgeManager contract
// (CanonicalCompanionPath, ResolveSunshinePath, Read) for the
// GuidedSetupController. The production write path goes through
// the existing elevated network helper via IUacHelper - the
// manager itself never writes HKLM directly. The helper verifies
// the manager PID against the kernel and writes HKLM.
#if WINDOWS
using System;
using System.IO;
using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Platform.Windows;

namespace VibertemisManager.App;

public sealed class WindowsBridgeAdapter : IBridgeManager
{
    private readonly WindowsBridgeKeyReader _reader = new();
    private readonly WindowsBridgeScmProbe _scm = new();
    private readonly string _canonicalManager;
    private readonly string _canonicalCompanion;

    public WindowsBridgeAdapter(string programsRoot)
    {
        _canonicalManager = BridgePaths.ResolveManagerPathFromHelper(
            Path.Combine(programsRoot, "manager"));
        _canonicalCompanion = BridgePaths.ResolveCompanionPathFromProgramsRoot(programsRoot);
    }

    public string CanonicalManagerPath => _canonicalManager;
    public string CanonicalCompanionPath => _canonicalCompanion;

    public string? ResolveSunshinePath()
        => _scm.ResolveSunshine(BridgePaths.SunshineServiceName,
            BridgePaths.SunshineExecutableBasename).InstalledExePath;

    public BridgeReadResult Read() => _reader.Read(BridgePaths.Hive, BridgePaths.SubKey);
}

public static class WindowsBridgeRegistration
{
    // UAC-launches the existing network helper with
    // --register-vr-bridge <managerPid>. The helper verifies the
    // PID against the kernel (canonical image path + primary
    // token session + string SID) and writes HKLM. The manager
    // never touches HKLM directly.
    public static UacLaunchResult Register(int managerPid, IUacHelper uac)
    {
        var helperPath = System.IO.Path.GetFullPath(System.IO.Path.Combine(
            AppContext.BaseDirectory, "VibertemisNetworkHelper.exe"));
        if (!System.IO.File.Exists(helperPath))
            return new UacLaunchResult(false, 0,
                "Network helper missing at " + helperPath);
        var args = "--register-vr-bridge " + managerPid.ToString();
        return uac.Launch(helperPath, args);
    }

    // Maps the helper exit codes documented in
    // GUIDED_WINDOWS_SETUP_PLAN.md to BridgeWriteOutcome so the
    // runner can classify the action.
    public static BridgeWriteOutcome MapExit(int exitCode) => exitCode switch
    {
        0 => BridgeWriteOutcome.Written,
        7 => BridgeWriteOutcome.WrongImage,
        8 => BridgeWriteOutcome.WrongSession,
        9 => BridgeWriteOutcome.AccessDenied,
        10 => BridgeWriteOutcome.AccessDenied,
        11 => BridgeWriteOutcome.SunshineUnresolved,
        12 => BridgeWriteOutcome.CompanionMissing,
        13 => BridgeWriteOutcome.AmbiguousUser,
        _ => BridgeWriteOutcome.Other,
    };
}
#endif