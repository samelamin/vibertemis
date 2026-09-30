// VR Bridge registration controller.
//
// The Quest headset pulls the PC's runtime install by asking the
// bridge where vibertemis-host-companion.exe and (optionally)
// Sunshine are. To avoid having to (1) hand that information
// across the elevation boundary on every connect, the manager
// writes three values to a system-wide, ACL-protected key:
//
//   HKLM\SOFTWARE\Vibertemis\VRBridge
//     UserSid        REG_SZ    - SID of the manager's console user
//     CompanionPath  REG_SZ    - canonical <programsroot>/manager/bin/vibertemis-host-companion.exe
//     SunshinePath   REG_SZ    - canonical SunshineService.exe ImagePath-derived sunshine.exe
//
// Only an admin / SYSTEM write is allowed. The ACL grants read
// only to the user that registered + Administrators + SYSTEM +
// Local Service, never to arbitrary users. A second user trying
// to register while a different user's companion is active is
// rejected with AmbiguousUser so the manager can tell the user
// to run explicit registration from their own account.
//
// The helper exe (network helper, already elevated by UAC) takes
// ONE new bounded arg:
//
//     --register-vr-bridge <managerPID>
//
// The helper verifies the manager PID against the kernel:
//   - it opens a process handle with PROCESS_QUERY_INFORMATION,
//   - reads the manager's actual MainModule FileName,
//   - resolves that file to a canonical absolute path,
//   - asserts the path equals
//       <helper-dir>/VibertemisManager.App.exe
//     (i.e. the running manager IS the canonical installed
//      manager, NOT a renamed helper or copy),
//   - reads the manager's primary token,
//   - reads the session ID from the token,
//   - reads the active console session id via WTSGetActiveConsoleSessionId,
//   - asserts the two sessions match,
//   - reads the token SID (string SID) for the UserSid value.
//
// The PID arg itself is NOT trusted for the image path or SID -
// the helper derives both from the kernel. This rejects the
// trivial attack where a non-manager process passes a victim's
// PID; that process must own a handle on the manager PID with the
// exact image path AND a primary token in the active console.
//
// SunshinePath is derived from the SunshineService SCM ImagePath
// via Windows CommandLineToArgvW parsing (so a quoted path with
// spaces is handled correctly), then resolved to the installed
// sunshine.exe. If Sunshine is not installed, the helper clears
// the SunshinePath value and the manager asks the user to
// install Vibeshine; the bridge never guesses a non-Sunshine
// path.
//
// CompanionPath is a FIXED constant path; the helper refuses to
// accept any caller-controlled path for it.
namespace VibertemisManager.Core.Bridge;

public sealed record BridgeRegistrationRecord(
    string CompanionPath,
    string? SunshinePath,
    string UserSid,
    string ManagerCanonicalPath,
    int ManagerProcessId);

public enum BridgeVerificationOutcome
{
    Valid,
    WrongImage,
    WrongSession,
    UnreadableSid,
    MissingImage,
    AccessDenied,
    Unknown,
}

public sealed record BridgeVerificationResult(
    BridgeVerificationOutcome Outcome,
    string Detail,
    BridgeRegistrationRecord? Record);

public enum BridgeReadOutcome
{
    Present,
    Absent,
    AccessDenied,
    OtherUserActive,
    Corrupt,
}

public sealed record BridgeReadResult(
    BridgeReadOutcome Outcome,
    string? CompanionPath,
    string? SunshinePath,
    string? UserSid,
    string Detail)
{
    public bool IsCurrentUser => Outcome == BridgeReadOutcome.Present;
}

public enum BridgeWriteOutcome
{
    Written,
    WrongImage,
    WrongSession,
    UnreadableSid,
    AccessDenied,
    SunshineUnresolved,
    CompanionMissing,
    AmbiguousUser,
    Other,
}

public static class BridgeWriteOutcomeExtensions
{
    public static string UserMessage(this BridgeWriteOutcome outcome) => outcome switch
    {
        BridgeWriteOutcome.Written => "Bridge registered.",
        BridgeWriteOutcome.WrongImage => "Caller is not the canonical installed manager.",
        BridgeWriteOutcome.WrongSession => "Caller session is not the active console session.",
        BridgeWriteOutcome.UnreadableSid => "Caller token SID could not be read.",
        BridgeWriteOutcome.AccessDenied => "UAC cancelled, or the helper could not be elevated.",
        BridgeWriteOutcome.SunshineUnresolved => "SunshineService is not installed (install Vibeshine).",
        BridgeWriteOutcome.CompanionMissing => "Bundled companion binary is missing on disk.",
        BridgeWriteOutcome.AmbiguousUser => "A different user's bridge is already registered.",
        _ => "Bridge registration failed.",
    };
}

public sealed record BridgeWriteResult(
    BridgeWriteOutcome Outcome,
    string Detail,
    BridgeRegistrationRecord? Record);

// Testable, platform-agnostic contract. Production binding in
// Platform/Windows uses Microsoft.Win32 + WTSAPI + kernel32.
// The production write path runs inside the elevated network
// helper via the manager's existing IUacHelper - the manager
// itself never writes HKLM directly.
public interface IBridgeManager
{
    string CanonicalManagerPath { get; }
    string CanonicalCompanionPath { get; }

    // Resolves the local SunshineService SCM entry to a canonical
    // sunshine.exe absolute path, or null when Sunshine is not
    // installed. The manager presents "install Vibeshine" in that
    // case; the bridge never returns a guessed path.
    string? ResolveSunshinePath();

    // Reads the raw registered record. The controller does the
    // semantic comparison against currentUserSid / canonical
    // companion / canonical sunshine / active console session.
    BridgeReadResult Read();
}

public static class BridgePaths
{
    public const string Hive = "HKLM";
    public const string SubKey = @"SOFTWARE\Vibertemis\VRBridge";
    public const string ValueUserSid = "UserSid";
    public const string ValueCompanionPath = "CompanionPath";
    public const string ValueSunshinePath = "SunshinePath";
    public const string CanonicalManagerBasename = "VibertemisManager.App.exe";
    public const string CanonicalCompanionBasename = "vibertemis-host-companion.exe";
    public const string CanonicalCompanionRelative = @"manager\bin\vibertemis-host-companion.exe";
    public const string SunshineServiceName = "SunshineService";
    public const string SunshineExecutableBasename = "sunshine.exe";

    public static string ResolveManagerPathFromHelper(string helperDirectory)
        => System.IO.Path.GetFullPath(System.IO.Path.Combine(helperDirectory, CanonicalManagerBasename));

    public static string ResolveCompanionPathFromProgramsRoot(string programsRoot)
        => System.IO.Path.GetFullPath(System.IO.Path.Combine(programsRoot, CanonicalCompanionRelative));
}

public sealed record BridgeRegistrationRequest(
    int ClaimedManagerPid,
    string CanonicalCompanionPath,
    string? SunshinePath,
    string CanonicalManagerPath)
{
    public static BridgeRegistrationRequest For(int requestedPid,
        string canonicalManagerPath,
        string canonicalCompanionPath,
        string? sunshinePath)
        => new(requestedPid, canonicalCompanionPath, sunshinePath, canonicalManagerPath);
}