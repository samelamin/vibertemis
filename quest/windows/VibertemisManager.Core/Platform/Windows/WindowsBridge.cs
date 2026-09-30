// Windows-side Bridge registration helpers.
//
// All Windows-native calls are gathered here so the Core
// controller can be exercised on POSIX tests via fakes.
//
// The bridges we touch:
//   * HKLM\SOFTWARE\Vibertemis\VRBridge - admin / SYSTEM write
//     only; ACL restricts read to the registering user +
//     Administrators + SYSTEM + Local Service.
//   * SunshineService SCM entry - reads ImagePath via
//     OpenSCManager / OpenService / QueryServiceConfigW. The
//     ImagePath is parsed with CommandLineToArgvW so a quoted
//     path with spaces resolves correctly.
//   * Kernel32 process / token queries for caller PID image
//     path + primary token session + string SID.
//
// The helper arg --register-vr-bridge <managerPID> triggers
// HandleRegistration; everything else in the helper stays the
// same. We do NOT add a --register-bridge-from-arg path
// (caller-controlled CompanionPath / SunshinePath); those are
// always derived.
#if WINDOWS
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using VibertemisManager.Core.Bridge;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsBridgeProcessProbe : IBridgeProcessProbe
{
    public CallerVerifyResult Resolve(int processId, string expectedCanonicalImagePath)
    {
        IntPtr processHandle = IntPtr.Zero;
        try
        {
            processHandle = OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */,
                false, processId);
            if (processHandle == IntPtr.Zero)
            {
                var err = Marshal.GetLastWin32Error();
                return new CallerVerifyResult(CallerVerifyStatus.Missing,
                    "OpenProcess returned NULL: Win32 " + err, null);
            }
            var sb = new StringBuilder(1024);
            uint size = 1024;
            if (!QueryFullProcessImageName(processHandle, 0, sb, ref size))
            {
                var err = Marshal.GetLastWin32Error();
                return new CallerVerifyResult(CallerVerifyStatus.Unknown,
                    "QueryFullProcessImageName failed: Win32 " + err, null);
            }
            var imagePath = sb.ToString();
            var canonical = Canonicalize(imagePath);
            if (!CanonicalEqual(canonical, expectedCanonicalImagePath))
                return new CallerVerifyResult(CallerVerifyStatus.WrongImagePath,
                    "Image path " + canonical + " != " + expectedCanonicalImagePath, null);

            IntPtr tokenHandle = IntPtr.Zero;
            if (!OpenProcessToken(processHandle, 0x0008 /* TOKEN_QUERY */, out tokenHandle))
            {
                var err = Marshal.GetLastWin32Error();
                return new CallerVerifyResult(CallerVerifyStatus.AccessDenied,
                    "OpenProcessToken failed: Win32 " + err, null);
            }
            try
            {
                int sessionId;
                if (!GetTokenSessionId(tokenHandle, out sessionId))
                {
                    var err = Marshal.GetLastWin32Error();
                    return new CallerVerifyResult(CallerVerifyStatus.Unknown,
                        "GetTokenInformation(TokenSessionId) failed: Win32 " + err, null);
                }
                string sid;
                if (!GetTokenStringSid(tokenHandle, out sid))
                    return new CallerVerifyResult(CallerVerifyStatus.UnreadableSid,
                        "Could not read token SID.", null);
                if (string.IsNullOrEmpty(sid))
                    return new CallerVerifyResult(CallerVerifyStatus.UnreadableSid,
                        "SID was empty.", null);
                return new CallerVerifyResult(CallerVerifyStatus.Valid, "ok",
                    new CallerIdentity(processId, canonical, sessionId, sid));
            }
            finally
            {
                CloseHandle(tokenHandle);
            }
        }
        catch (Win32Exception ex)
        {
            return new CallerVerifyResult(CallerVerifyStatus.AccessDenied,
                "Win32: " + ex.Message, null);
        }
        catch (Exception ex)
        {
            return new CallerVerifyResult(CallerVerifyStatus.Unknown,
                "Exception: " + ex.Message, null);
        }
        finally
        {
            if (processHandle != IntPtr.Zero) CloseHandle(processHandle);
        }
    }

    private static bool GetTokenSessionId(IntPtr tokenHandle, out int sessionId)
    {
        sessionId = -1;
        uint needed = 0;
        GetTokenInformation(tokenHandle, 12 /* TokenSessionId */, IntPtr.Zero, 0, out needed);
        if (needed == 0) return false;
        var buf = Marshal.AllocHGlobal((int)needed);
        try
        {
            if (!GetTokenInformation(tokenHandle, 12, buf, needed, out needed))
                return false;
            sessionId = Marshal.ReadInt32(buf);
            return true;
        }
        finally { Marshal.FreeHGlobal(buf); }
    }

    private static bool GetTokenStringSid(IntPtr tokenHandle, out string sid)
    {
        sid = "";
        uint needed = 0;
        GetTokenInformation(tokenHandle, 1 /* TokenUser */, IntPtr.Zero, 0, out needed);
        if (needed == 0) return false;
        var buf = Marshal.AllocHGlobal((int)needed);
        try
        {
            if (!GetTokenInformation(tokenHandle, 1, buf, needed, out needed))
                return false;
            var sidPtr = Marshal.ReadIntPtr(buf);
            if (sidPtr == IntPtr.Zero) return false;
            var si = new SecurityIdentifier(sidPtr);
            sid = si.Value ?? "";
            return sid.Length > 0;
        }
        finally { Marshal.FreeHGlobal(buf); }
    }

    private static string Canonicalize(string path)
    {
        try { return System.IO.Path.GetFullPath(path).Replace('\\', '/').TrimEnd('/'); }
        catch { return path; }
    }

    private static bool CanonicalEqual(string a, string b)
        => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool QueryFullProcessImageName(IntPtr processHandle, uint flags, StringBuilder text, ref uint textLen);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr tokenHandle, uint tokenInfoClass, IntPtr tokenInfo, uint tokenInfoLength, out uint returnLength);
}

public sealed class WindowsBridgeScmProbe : IBridgeScmProbe
{
    public ScmQueryResult ResolveSunshine(string serviceName, string sunshineExeBasename)
    {
        IntPtr scm = IntPtr.Zero;
        try
        {
            scm = OpenSCManager(null, null, 0x0001 /* SC_MANAGER_CONNECT */);
            if (scm == IntPtr.Zero)
            {
                var err = Marshal.GetLastWin32Error();
                return ScmQueryResult.AccessDenied("OpenSCManager failed: Win32 " + err);
            }
            IntPtr svc = IntPtr.Zero;
            try
            {
                svc = OpenService(scm, serviceName, 0x0001 /* SERVICE_QUERY_CONFIG */);
                if (svc == IntPtr.Zero)
                {
                    var err = Marshal.GetLastWin32Error();
                    if (err == 1060 /* ERROR_SERVICE_DOES_NOT_EXIST */)
                        return ScmQueryResult.NotInstalled("Service not present.");
                    return ScmQueryResult.Unknown("OpenService failed: Win32 " + err);
                }
                uint needed = 0;
                QueryServiceConfig(svc, IntPtr.Zero, 0, out needed);
                if (needed == 0)
                    return ScmQueryResult.Unknown("QueryServiceConfig returned needed=0.");
                var buf = Marshal.AllocHGlobal((int)needed);
                try
                {
                    if (!QueryServiceConfig(svc, buf, needed, out needed))
                        return ScmQueryResult.Unknown("QueryServiceConfig failed.");
                    var config = (QUERY_SERVICE_CONFIG)Marshal.PtrToStructure(buf, typeof(QUERY_SERVICE_CONFIG))!;
                    var imagePath = config.lpBinaryPathName == IntPtr.Zero ? "" : (Marshal.PtrToStringUni(config.lpBinaryPathName) ?? "");
                    var args = ParseCommandLine(imagePath);
                    string? first = null;
                    foreach (var a in args)
                    {
                        if (string.IsNullOrEmpty(a)) continue;
                        if (a.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                            || a.EndsWith(sunshineExeBasename, StringComparison.OrdinalIgnoreCase))
                        {
                            first = a;
                            break;
                        }
                        if (first is null && !a.StartsWith("--") && !a.StartsWith("/") && !a.StartsWith("-"))
                            first = a;
                    }
                    if (string.IsNullOrEmpty(first))
                        return ScmQueryResult.Unknown("Could not parse SCM ImagePath: " + imagePath);
                    var serviceExe = first.Trim('"');
                    var serviceDir = System.IO.Path.GetDirectoryName(serviceExe) ?? "";
                    var candidate = string.Equals(System.IO.Path.GetFileName(serviceExe), sunshineExeBasename, StringComparison.OrdinalIgnoreCase)
                        ? System.IO.Path.GetFullPath(serviceExe)
                        : System.IO.Path.GetFullPath(System.IO.Path.Combine(serviceDir, "..", sunshineExeBasename));
                    if (!System.IO.File.Exists(candidate))
                        return ScmQueryResult.NotInstalled("sunshine.exe not present at " + candidate);
                    return ScmQueryResult.Resolved(candidate, "sunshine.exe resolved at " + candidate);
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            finally { if (svc != IntPtr.Zero) CloseServiceHandle(svc); }
        }
        catch (Exception ex)
        {
            return ScmQueryResult.Unknown(ex.Message);
        }
        finally { if (scm != IntPtr.Zero) CloseServiceHandle(scm); }
    }

    private static System.Collections.Generic.IList<string> ParseCommandLine(string commandLine)
    {
        var result = new System.Collections.Generic.List<string>();
        if (string.IsNullOrEmpty(commandLine)) return result;
        var ptr = CommandLineToArgvW(commandLine, out int count);
        if (ptr == IntPtr.Zero) return result;
        try
        {
            for (var i = 0; i < count; i++)
            {
                var argPtr = Marshal.ReadIntPtr(ptr, i * IntPtr.Size);
                result.Add(Marshal.PtrToStringUni(argPtr) ?? "");
            }
        }
        finally { LocalFree(ptr); }
        return result;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenSCManager(string? machineName, string? databaseName, uint desiredAccess);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenService(IntPtr scmHandle, string serviceName, uint desiredAccess);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool QueryServiceConfig(IntPtr serviceHandle, IntPtr queryConfig, uint bufferSize, out uint bytesNeeded);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool CloseServiceHandle(IntPtr handle);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CommandLineToArgvW(string commandLine, out int numArgs);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr handle);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct QUERY_SERVICE_CONFIG
    {
        public uint dwServiceType;
        public uint dwStartType;
        public uint dwErrorControl;
        public IntPtr lpBinaryPathName;
        public IntPtr lpLoadOrderGroup;
        public uint dwTagId;
        public IntPtr lpDependencies;
        public IntPtr lpServiceStartName;
        public IntPtr lpDisplayName;
    }
}

public sealed class WindowsBridgeKeyReader : IBridgeKeyReader
{
    public BridgeReadResult Read(string hive, string subKey)
    {
        try
        {
            using var baseKey = Microsoft.Win32.RegistryKey.OpenBaseKey(
                Microsoft.Win32.RegistryHive.LocalMachine, Microsoft.Win32.RegistryView.Registry64);
            using var key = baseKey.OpenSubKey(subKey, writable: false);
            if (key is null)
                return new BridgeReadResult(BridgeReadOutcome.Absent, null, null, null, "Key absent.");
            var companion = key.GetValue(BridgePaths.ValueCompanionPath) as string;
            var sunshine = key.GetValue(BridgePaths.ValueSunshinePath) as string;
            var sid = key.GetValue(BridgePaths.ValueUserSid) as string;
            if (string.IsNullOrEmpty(companion) || string.IsNullOrEmpty(sid))
                return new BridgeReadResult(BridgeReadOutcome.Corrupt, companion, sunshine, sid,
                    "Missing values: companion=" + (companion ?? "null") + " sid=" + (sid ?? "null"));
            return new BridgeReadResult(BridgeReadOutcome.Present, companion, sunshine, sid, "ok");
        }
        catch (System.Security.SecurityException ex)
        {
            return new BridgeReadResult(BridgeReadOutcome.AccessDenied, null, null, null, ex.Message);
        }
        catch (UnauthorizedAccessException ex)
        {
            return new BridgeReadResult(BridgeReadOutcome.AccessDenied, null, null, null, ex.Message);
        }
        catch (Exception ex)
        {
            return new BridgeReadResult(BridgeReadOutcome.AccessDenied, null, null, null, ex.Message);
        }
    }
}

public sealed class WindowsBridgeKeyWriter : IBridgeKeyWriter
{
    public BridgeWriteOutcome Write(string hive, string subKey,
        string companionPath, string? sunshinePath, string userSid)
    {
        Microsoft.Win32.RegistryKey? key = null;
        try
        {
            using var baseKey = Microsoft.Win32.RegistryKey.OpenBaseKey(
                Microsoft.Win32.RegistryHive.LocalMachine, Microsoft.Win32.RegistryView.Registry64);
            using (var created = baseKey.CreateSubKey(subKey, Microsoft.Win32.RegistryKeyPermissionCheck.Default)) {
                if (created is null) return BridgeWriteOutcome.AccessDenied;
            }
            key = baseKey.OpenSubKey(subKey, Microsoft.Win32.RegistryKeyPermissionCheck.ReadWriteSubTree,
                System.Security.AccessControl.RegistryRights.ReadKey | System.Security.AccessControl.RegistryRights.WriteKey |
                System.Security.AccessControl.RegistryRights.ChangePermissions | System.Security.AccessControl.RegistryRights.TakeOwnership);
            if (key is null) return BridgeWriteOutcome.AccessDenied;
            // Fail closed: registration is a trust anchor, never an ordinary
            // user-writable key. Replace inherited or stale permissions.
            var security = new System.Security.AccessControl.RegistrySecurity();
            security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            foreach (var principal in new[] {
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) })
                security.AddAccessRule(new System.Security.AccessControl.RegistryAccessRule(
                    principal, System.Security.AccessControl.RegistryRights.FullControl,
                    System.Security.AccessControl.AccessControlType.Allow));
            security.AddAccessRule(new System.Security.AccessControl.RegistryAccessRule(
                new SecurityIdentifier(userSid), System.Security.AccessControl.RegistryRights.ReadKey,
                System.Security.AccessControl.AccessControlType.Allow));
            security.SetOwner(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null));
            key.SetAccessControl(security);
            key.SetValue(BridgePaths.ValueCompanionPath, companionPath, Microsoft.Win32.RegistryValueKind.String);
            key.SetValue(BridgePaths.ValueSunshinePath, sunshinePath ?? "", Microsoft.Win32.RegistryValueKind.String);
            key.SetValue(BridgePaths.ValueUserSid, userSid, Microsoft.Win32.RegistryValueKind.String);
            return BridgeWriteOutcome.Written;
        }
        catch (System.Security.SecurityException) { return BridgeWriteOutcome.AccessDenied; }
        catch (UnauthorizedAccessException) { return BridgeWriteOutcome.AccessDenied; }
        catch (Exception) { return BridgeWriteOutcome.Other; }
        finally
        {
            key?.Dispose();
        }
    }

    public void Rollback(string hive, string subKey)
    {
        try
        {
            using var baseKey = Microsoft.Win32.RegistryKey.OpenBaseKey(
                Microsoft.Win32.RegistryHive.LocalMachine, Microsoft.Win32.RegistryView.Registry64);
            using var key = baseKey.OpenSubKey(subKey, writable: true);
            if (key is null) return;
            try { key.DeleteValue(BridgePaths.ValueCompanionPath, throwOnMissingValue: false); } catch { /* ignore */ }
            try { key.DeleteValue(BridgePaths.ValueSunshinePath, throwOnMissingValue: false); } catch { /* ignore */ }
            try { key.DeleteValue(BridgePaths.ValueUserSid, throwOnMissingValue: false); } catch { /* ignore */ }
        }
        catch { /* best effort */ }
    }
}

public static class WindowsBridgeActiveSession
{
    public static int Read()
    {
        try
        {
            return WTSGetActiveConsoleSessionId();
        }
        catch
        {
            return 0;
        }
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int WTSGetActiveConsoleSessionId();
}

#endif