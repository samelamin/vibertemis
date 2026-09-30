// VibertemisManager.Core - bounded platform abstraction surface.
//
// The Core library must compile and test cleanly on Linux (net8.0)
// and Windows (net8.0-windows). To keep the same test suite exercising
// both real code paths (Windows) and injected fakes (Linux), every
// I/O and OS call funnels through one of the IPlatform* interfaces
// declared here. The Windows-specific implementations live under
// Platform/Windows and are only loaded when the assembly is targeted
// to a Windows TFM.
using System.Collections.Generic;
using System.IO;
using System.Net;
using VibertemisManager.Core.Firewall;

namespace VibertemisManager.Core.Platform.Abstractions;

/// <summary>
/// A single running process that we can ask to stop.
/// </summary>
public readonly record struct RunningProcess(int ProcessId, string Name, string ExecutablePath);

public enum ProcessQueryStatus
{
    Found,
    Missing,
    AccessDenied,
    Unknown,
}

/// <summary>
/// Injectable snapshot of the OS process table. Linux tests use a
/// FakeProcessTable; Windows production uses a real enumerator.
/// </summary>
public interface IProcessTable
{
    IReadOnlyList<RunningProcess> Snapshot();
    ProcessQueryStatus TryGet(int processId, out RunningProcess process);
    bool IsRunning(string executableName);
}

/// <summary>
/// Injectable filesystem helper. Concrete implementations should not
/// parse registry paths or shell-escape anything.
/// </summary>
public interface IFileSystemAccess
{
    bool FileExists(string path);
    string ReadAllText(string path);
    bool DirectoryExists(string path);
    IEnumerable<string> EnumerateFiles(string directory, string pattern);
}

/// <summary>
/// Single-user installer / state paths. We resolve these from the
/// %LOCALAPPDATA% environment variable on Windows and from $HOME on
/// POSIX. Tests can substitute a rooted layout.
/// </summary>
public interface IPathResolver
{
    string LocalAppData { get; }
    string ProgramsRoot { get; }
    string ManagerStateDir { get; }
    string CompanionStateDir { get; }
    string UpdateCacheDir { get; }
    string LogDir { get; }
}

/// <summary>
/// OS-level named mutex / single-instance guard. The Windows production
/// binding uses CreateMutex(Local\), POSIX tests use a flock-backed
/// file in a temp dir.
/// </summary>
public interface ISingleInstanceGuard
{
    bool TryAcquire(out System.IDisposable? handle);
}

/// <summary>
/// Auto-start registration in HKCU\Run. Production is Windows-only,
/// POSIX tests no-op the write but still expose IsEnabled.
/// </summary>
public interface IAutoStartRegistrar
{
    bool IsEnabled();
    void Enable();
    void Disable();
}

/// <summary>
/// Reads / writes the HKCU Windows registry without spawning reg.exe.
/// POSIX build provides a non-Windows stub that returns false from
/// probe/lookup methods.
/// </summary>
public interface IRegistryAccess
{
    string? TryGetString(string hive, string subKey, string valueName);
    IEnumerable<(string ValueName, string Data)> EnumerateValues(string hive, string subKey);
    /// <summary>
    /// Reads a DWORD / DWord-style value. Strings that parse as a
    /// 32-bit integer are accepted (the Visual C++ runtime key on
    /// Windows 10+ records Major/Minor/Bld/Rbld as REG_DWORD but
    /// some legacy setups surface them as stringified numbers).
    /// Returns null on missing / unsupported value types.
    /// </summary>
    int? TryGetDword(string hive, string subKey, string valueName);
}

/// <summary>
/// Firewall rule inspection. Production wraps netsh advfirewall.
/// </summary>
public interface IFirewallScanner
{
    IReadOnlyList<FirewallRuleDescriptor> ListInboundRules();
    bool TryAddInbound(string ruleName, string programPath, ushort port, IReadOnlyList<string> profiles, IReadOnlyList<string> remoteAddresses);
    bool TryDeleteInbound(string ruleName);
}

/// <summary>
/// Caller-side helper that elevates only a tightly-scoped helper exe
/// via ShellExecute runas. POSIX tests fake the elevation.
/// </summary>
public interface IUacHelper
{
    UacLaunchResult Launch(string helperPath, string helperArgs);
}