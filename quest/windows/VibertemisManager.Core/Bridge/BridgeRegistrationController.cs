// VR Bridge registration controller.
//
// Composes the testable Core abstractions behind
// IBridgeManager with a concrete IBridgeProcessProbe + IBridgeScmProbe
// + IBridgeKeyWriter so the controller's behaviour is exercised on
// POSIX tests without touching the Windows registry / SCM.
//
// The controller is the high-level helper-side flow:
//   1. Receive --register-vr-bridge <managerPID>.
//   2. Verify the claimed PID resolves to the canonical
//      VibertemisManager.App.exe (not the helper, not a rename,
//      not a copy) with an active primary token in the active
//      console session.
//   3. Resolve Sunshine via the SCM (or null when not installed).
//   4. If Sunshine is absent, ask the manager to surface
//      "install Vibeshine"; we never invent a Sunshine path.
//   5. Write the canonical record under the admin ACL.
//   6. Roll back a partial write if any one step fails after the
//      registry has been touched.
//
// The data the helper writes is exactly:
//   CompanionPath  - canonical Companion exe path (caller fixed).
//   SunshinePath   - SCM-derived sunshine.exe absolute path or "".
//   UserSid        - manager primary token string SID.
//
// The manager itself never trusts the helper's claim about its
// OWN path - the manager calls Read() first and only launches
// the helper when the existing record is missing or out of date.
using System;
using System.Collections.Generic;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Bridge;

public sealed record CallerIdentity(
    int ProcessId,
    string CanonicalImagePath,
    int SessionId,
    string UserSid);

public enum CallerVerifyStatus
{
    Valid,
    WrongImagePath,
    WrongSession,
    AccessDenied,
    Unknown,
    Missing,
    UnreadableSid,
}

public sealed record CallerVerifyResult(CallerVerifyStatus Status, string Detail, CallerIdentity? Identity);

public interface IBridgeProcessProbe
{
    // Read the canonical image path + primary token session + string SID
    // of a given process. Returns null + status when the PID does not
    // exist or cannot be inspected.
    CallerVerifyResult Resolve(int processId, string expectedCanonicalImagePath);
}

public enum ScmQueryStatus
{
    Resolved,
    NotInstalled,
    AccessDenied,
    Unknown,
}

public sealed record ScmQueryResult(ScmQueryStatus Status, string? InstalledExePath, string Detail)
{
    public static ScmQueryResult Resolved(string path, string detail) => new(ScmQueryStatus.Resolved, path, detail);
    public static ScmQueryResult NotInstalled(string detail) => new(ScmQueryStatus.NotInstalled, null, detail);
    public static ScmQueryResult AccessDenied(string detail) => new(ScmQueryStatus.AccessDenied, null, detail);
    public static ScmQueryResult Unknown(string detail) => new(ScmQueryStatus.Unknown, null, detail);
}

public interface IBridgeScmProbe
{
    ScmQueryResult ResolveSunshine(string serviceName, string sunshineExeBasename);
}

public interface IBridgeKeyReader
{
    BridgeReadResult Read(string hive, string subKey);
}

public interface IBridgeKeyWriter
{
    // Writes the three values under the admin ACL. Returns the
    // granted permissions on success. On failure, returns the
    // reason; the controller rolls back any partial write.
    BridgeWriteOutcome Write(string hive, string subKey,
        string companionPath, string? sunshinePath, string userSid);
    // Rolls back a partial write by deleting the values that
    // were written in the failed transaction.
    void Rollback(string hive, string subKey);
}

public sealed class BridgeRegistrationController
{
    private readonly IBridgeProcessProbe _probe;
    private readonly IBridgeScmProbe _scm;
    private readonly IBridgeKeyReader _reader;
    private readonly IBridgeKeyWriter _writer;
    private readonly string _canonicalManagerPath;
    private readonly string _canonicalCompanionPath;
    private readonly Func<bool> _isWindows;
    private int _activeSessionId;

    public BridgeRegistrationController(
        IBridgeProcessProbe probe,
        IBridgeScmProbe scm,
        IBridgeKeyReader reader,
        IBridgeKeyWriter writer,
        string canonicalManagerPath,
        string canonicalCompanionPath,
        Func<bool>? isWindows = null)
    {
        _probe = probe;
        _scm = scm;
        _reader = reader;
        _writer = writer;
        _canonicalManagerPath = canonicalManagerPath;
        _canonicalCompanionPath = canonicalCompanionPath;
        _isWindows = isWindows ?? (() => OperatingSystem.IsWindows());
    }

    public string CanonicalManagerPath => _canonicalManagerPath;
    public string CanonicalCompanionPath => _canonicalCompanionPath;

    public void SetActiveSessionId(int sessionId)
    {
        _activeSessionId = sessionId;
    }

    // Step 1: the manager itself calls this when it wants to
    // know whether the registered bridge is current for the
    // active console user. This is the "do we need to re-register
    // at all?" check that prevents us from launching UAC on
    // every sign-in.
    public BridgeReadOutcome Snapshot(string currentUserSid, string expectedCompanionPath, string? expectedSunshinePath)
    {
        if (!_isWindows()) return BridgeReadOutcome.Absent;
        var existing = _reader.Read(BridgePaths.Hive, BridgePaths.SubKey);
        if (existing.Outcome == BridgeReadOutcome.Absent) return BridgeReadOutcome.Absent;
        if (existing.Outcome != BridgeReadOutcome.Present) return existing.Outcome;
        if (!string.Equals(existing.UserSid, currentUserSid, StringComparison.Ordinal))
            return BridgeReadOutcome.OtherUserActive;
        if (!CanonicalPathsEqual(existing.CompanionPath, expectedCompanionPath))
            return BridgeReadOutcome.OtherUserActive;
        if (!CanonicalSunshineEqual(existing.SunshinePath, expectedSunshinePath))
            return BridgeReadOutcome.OtherUserActive;
        return BridgeReadOutcome.Present;
    }

    // Step 2: helper-side entry. The caller PID arg is
    // VERIFIED against the kernel, never trusted directly.
    public BridgeWriteResult HandleRegistration(int claimedManagerPid)
    {
        var verify = _probe.Resolve(claimedManagerPid, _canonicalManagerPath);
        if (verify.Status != CallerVerifyStatus.Valid || verify.Identity is null)
            return MapVerify(verify);

        var identity = verify.Identity;
        if (_activeSessionId < 0 || identity.SessionId != _activeSessionId)
            return new BridgeWriteResult(BridgeWriteOutcome.WrongSession,
                "Manager session " + identity.SessionId + " is not the active console session.",
                null);

        var scm = _scm.ResolveSunshine(BridgePaths.SunshineServiceName, BridgePaths.SunshineExecutableBasename);
        if (scm.Status == ScmQueryStatus.AccessDenied)
            return new BridgeWriteResult(BridgeWriteOutcome.AccessDenied,
                "Cannot query SunshineService: " + scm.Detail, null);
        if (scm.Status == ScmQueryStatus.Unknown)
            return new BridgeWriteResult(BridgeWriteOutcome.Other,
                "Could not resolve Sunshine: " + scm.Detail, null);
        if (scm.Status == ScmQueryStatus.NotInstalled || scm.InstalledExePath is null)
        {
            // Preserve an existing registration when its host is unavailable.
            return new BridgeWriteResult(BridgeWriteOutcome.SunshineUnresolved, "Install the VR-enabled Vibeshine host, then retry Setup VR.", null);
        }
        return WriteWithSunshine(identity, scm.InstalledExePath);
    }

    private BridgeWriteResult WriteWithSunshine(CallerIdentity identity, string? sunshinePath)
    {
        // Roll back any partial write on failure. We never leave a
        // half-populated record: protocol consumers currently read three
        // fixed values and a complete-but-stale mismatch is fail-closed
        // by the manager, but a partial write would silently corrupt
        // the headset view.
        var existing = _reader.Read(BridgePaths.Hive, BridgePaths.SubKey);
        var keepExisting = existing.Outcome == BridgeReadOutcome.Present;
        var outcome = _writer.Write(BridgePaths.Hive, BridgePaths.SubKey,
            _canonicalCompanionPath, sunshinePath, identity.UserSid);
        if (outcome != BridgeWriteOutcome.Written)
        {
            try {
                if (keepExisting)
                    _writer.Write(BridgePaths.Hive, BridgePaths.SubKey,
                        existing.CompanionPath!, existing.SunshinePath, existing.UserSid!);
                else _writer.Rollback(BridgePaths.Hive, BridgePaths.SubKey);
            } catch { /* Original failure remains actionable; consumers fail closed. */ }
            return new BridgeWriteResult(outcome,
                "Bridge write failed.", null);
        }
        var record = new BridgeRegistrationRecord(
            CompanionPath: _canonicalCompanionPath,
            SunshinePath: sunshinePath,
            UserSid: identity.UserSid,
            ManagerCanonicalPath: identity.CanonicalImagePath,
            ManagerProcessId: identity.ProcessId);
        return new BridgeWriteResult(BridgeWriteOutcome.Written, "Bridge registered.", record);
    }

    private static BridgeWriteResult MapVerify(CallerVerifyResult verify)
    {
        return verify.Status switch
        {
            CallerVerifyStatus.WrongImagePath => new BridgeWriteResult(BridgeWriteOutcome.WrongImage,
                "Caller image path is not the canonical manager: " + verify.Detail, null),
            CallerVerifyStatus.WrongSession => new BridgeWriteResult(BridgeWriteOutcome.WrongSession,
                "Caller session is not the active console session: " + verify.Detail, null),
            CallerVerifyStatus.AccessDenied => new BridgeWriteResult(BridgeWriteOutcome.AccessDenied,
                "Caller could not be inspected: " + verify.Detail, null),
            CallerVerifyStatus.Missing => new BridgeWriteResult(BridgeWriteOutcome.WrongImage,
                "Caller process is not running.", null),
            _ => new BridgeWriteResult(BridgeWriteOutcome.Other, verify.Detail, null),
        };
    }

    private static bool CanonicalPathsEqual(string? a, string? b)
    {
        if (a is null || b is null) return false;
        return NormalizePath(a) == NormalizePath(b);
    }

    private static bool CanonicalSunshineEqual(string? a, string? b)
    {
        // Both null = no Sunshine configured for either = match.
        if (string.IsNullOrEmpty(a) && string.IsNullOrEmpty(b)) return true;
        if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
        return NormalizePath(a) == NormalizePath(b);
    }

    private static string NormalizePath(string path)
    {
        try { return System.IO.Path.GetFullPath(path).Replace('\\', '/').TrimEnd('/').ToLowerInvariant(); }
        catch { return path.ToLowerInvariant(); }
    }
}