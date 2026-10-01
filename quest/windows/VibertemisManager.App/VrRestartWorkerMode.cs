// Windows adapter for the managed half of the SteamVR restart
// handshake. It runs before WinForms, before the single instance
// mutex, and before any settings/integrity wiring, because
// vrserver.exe spawns the installed manager directly with stdin and
// stdout redirected to a pipe pair it owns.
//
// This file holds every Windows specific detail on purpose: Toolhelp32
// identity, real final path resolution, module enumeration, process
// lifetime retention, the stdin/stdout line protocol with a bounded
// commit read, the coalescing named mutex, and the vrstartup launch
// (CreateProcess with bInheritHandles = FALSE so the vrserver pipe pair
// is never handed to a child). No dialog is ever shown from here; the
// outcome is published as JSON for the running manager to surface.
//
// Every snapshot failure is raised, never swallowed: an incomplete
// process or module view is refused, and it is never read as "no VR
// process is running".
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Steam;

namespace VibertemisManager.App;

internal static class VrRestartWorkerMode
{
    public const int ExitSucceeded = 0;
    public const int ExitFailed = 1;
    public const int ExitInvalidArguments = 2;
    public const int ExitCoalesced = 3;

    public const string ArgumentName = VrRestartArguments.Flag;

    public static int Run(string[] args)
    {
        // No launch path, hash, or identity is accepted from the
        // command line: the only accepted argument is the parent PID.
        if (!TryReadParentPid(args, out var parentPid)) return ExitInvalidArguments;
        try
        {
            var worker = new VrRestartWorker(
                new WindowsVrRestartEnvironment(),
                new ConsoleVrRestartProtocol(),
                new SystemVrRestartClock());
            return worker.Run(parentPid).Outcome switch
            {
                VrRestartOutcome.Succeeded => ExitSucceeded,
                VrRestartOutcome.Coalesced => ExitCoalesced,
                _ => ExitFailed,
            };
        }
        catch
        {
            // Even an unexpected failure must leave a visible outcome.
            TryPublishFallbackFailure();
            return ExitFailed;
        }
    }

    public static bool TryReadParentPid(string[] args, out int parentPid) =>
        VrRestartArguments.TryReadParentPid(args, out parentPid);

    private static void TryPublishFallbackFailure()
    {
        try
        {
            var paths = new EnvironmentPathResolver();
            var result = new VrRestartResult(VrRestartOutcome.WorkerError, VrRestartMessages.WorkerError);
            var path = VrRestartPaths.Combine(paths.ManagerStateDir, VrRestartWorker.ResultFileName);
            new WindowsVrRestartEnvironment().WriteResultAtomically(
                path, VrRestartResultWriter.Serialize(result, DateTime.UtcNow));
        }
        catch { /* nothing else can be done without a state directory */ }
    }
}

internal sealed class ConsoleVrRestartProtocol : IVrRestartProtocol
{
    private readonly Stream _input;
    private readonly Stream _output;

    public ConsoleVrRestartProtocol()
    {
        _input = Console.OpenStandardInput();
        _output = Console.OpenStandardOutput();
    }

    public void WriteReady()
    {
        var bytes = Encoding.ASCII.GetBytes(VrRestartProtocol.ReadyLine + "\n");
        _output.Write(bytes, 0, bytes.Length);
        _output.Flush();
    }

    public bool TryReadCommit(TimeSpan timeout)
    {
        // An inherited anonymous pipe handle does not support a read
        // timeout, so the line is read on a dedicated background thread
        // and waited for with a deadline. On timeout the read is
        // abandoned, the input is never disposed, the refusal is
        // published, and this process returns.
        var status = VrRestartBoundedRead.TryReadLineWithin(
            _input, VrRestartProtocol.MaxLineBytes, timeout, out var line);
        if (status != VrRestartLineStatus.Line) return false;   // EOF, oversize, malformed, or timeout
        return VrRestartProtocol.IsCommitLine(line);
    }
}

internal sealed class WindowsVrRestartEnvironment : IVrRestartEnvironment
{
    private const string IntegrityResource = "VibertemisManager.App.Resources.Integrity.integrity.json";

    // A snapshot retried for ERROR_BAD_LENGTH only: five attempts total,
    // 10ms apart, so the worst case stays far inside the worker's own
    // bounded waits.
    private const int ModuleSnapshotAttempts = 5;
    private const int ModuleSnapshotRetryDelayMilliseconds = 10;

    private readonly EnvironmentPathResolver _paths;
    private readonly RealFileSystemAccess _fs;
    private readonly ISteamVrLocator _steamVr;
    private readonly Lazy<IIntegrityVerifier?> _integrity;

    public WindowsVrRestartEnvironment()
    {
        _paths = new EnvironmentPathResolver();
        _fs = new RealFileSystemAccess();
        // Only the recorded (registered) runtime is trusted here: the
        // runtime vrserver.exe was started from, not a filesystem scan.
        _steamVr = new VrpathSteamVrLocator(_paths, _fs);
        _integrity = new Lazy<IIntegrityVerifier?>(() =>
        {
            try { return IntegrityVerifier.FromEmbedded(typeof(Program).Assembly, IntegrityResource, _paths.ProgramsRoot); }
            catch { return null; }   // absent or invalid manifest refuses the restart
        });
    }

    public string InstallRoot => _paths.ProgramsRoot;
    public string ManagerStateDir => _paths.ManagerStateDir;

    public IReadOnlyList<string> LocateRegisteredSteamVrRuntimes()
    {
        try
        {
            var status = _steamVr.Discover();
            return status.Kind == SteamVrDiscoveryKind.InstalledAndReady
                ? status.RuntimePaths
                : Array.Empty<string>();
        }
        catch { return Array.Empty<string>(); }
    }

    public bool FileExists(string path)
    {
        try { return File.Exists(path); } catch { return false; }
    }

    // The only form ever compared against a real process image.
    public string? ResolveFilePath(string path) => RealFilePath(path);

    public bool VerifyInstalledFile(string relativePath)
    {
        var verifier = _integrity.Value;
        if (verifier is null) return false;
        try
        {
            var absolute = Path.Combine(InstallRoot, relativePath.Replace('/', Path.DirectorySeparatorChar));
            return verifier.Verify(absolute, out _);
        }
        catch { return false; }
    }

    public VrRestartParentPin? TryPinRequestingVrserver(int parentPid, IReadOnlyList<string> runtimeRoots)
    {
        if (parentPid <= 0) return null;
        if (!IsActualParent(parentPid)) return null;
        var pin = TryPinProcess(parentPid);
        if (pin is null) return null;
        if (pin.ImageFinalPath is null) { pin.Dispose(); return null; }
        foreach (var root in runtimeRoots)
        {
            if (string.IsNullOrWhiteSpace(root)) continue;
            var expected = RealFilePath(Path.Combine(root, "bin", "win64", VrRestartWorker.VrserverImageName));
            if (expected is null || !VrRestartPaths.PathsEqual(expected, pin.ImageFinalPath)) continue;
            return new VrRestartParentPin(root, pin);
        }
        pin.Dispose();
        return null;
    }

    public IReadOnlyList<string> GetModuleImagePaths(IVrRestartProcess process)
    {
        if (process is not WindowsVrRestartProcess pin)
            throw new VrRestartInspectionException("The process is not inspectable.");
        // A module snapshot is addressed by PID, so a process that has
        // already exited could answer with a reused PID's modules. The
        // retained handle is checked before and after the walk.
        if (pin.HasExited)
            throw new VrRestartInspectionException("The process exited before its modules could be read.");
        var result = new List<string>();
        var snapshot = CreateModuleSnapshot(pin);
        if (snapshot == VrRestartNative.InvalidHandleValue)
            throw new VrRestartInspectionException("The module list could not be snapshotted.");
        try
        {
            var entry = default(VrRestartNative.MODULEENTRY32);
            entry.dwSize = (uint)Marshal.SizeOf<VrRestartNative.MODULEENTRY32>();
            if (!VrRestartNative.Module32FirstW(snapshot, ref entry))
            {
                if (Marshal.GetLastWin32Error() != VrRestartNative.ErrorNoMoreFiles)
                    throw new VrRestartInspectionException("The module list could not be read.");
                return result;   // a process with no modules at all
            }
            do
            {
                if (entry.th32ProcessID != (uint)pin.ProcessId) continue;   // PID reuse defence
                // Resolve the real final path so a junction or short name
                // cannot masquerade as the installed driver.
                if (string.IsNullOrEmpty(entry.szExePath))
                    throw new VrRestartInspectionException("A module reported no path.");
                var real = RealFilePath(entry.szExePath);
                if (real is null)
                    throw new VrRestartInspectionException("A module path could not be resolved.");
                result.Add(real);
            } while (VrRestartNative.Module32NextW(snapshot, ref entry));
            if (Marshal.GetLastWin32Error() != VrRestartNative.ErrorNoMoreFiles)
                throw new VrRestartInspectionException("The module list ended early.");
        }
        finally { VrRestartNative.CloseHandle(snapshot); }
        if (pin.HasExited)
            throw new VrRestartInspectionException("The process exited during module enumeration.");
        return result;
    }

    public IReadOnlyList<VrRestartProcessEntry> ListProcesses()
    {
        // Fail closed. The worker uses this list to decide whether a
        // second vrserver exists, so an incomplete view must never be
        // reported as an empty or partial one.
        var list = new List<VrRestartProcessEntry>();
        var snapshot = VrRestartNative.CreateToolhelp32Snapshot(VrRestartNative.TH32CS_SNAPPROCESS, 0);
        if (snapshot == VrRestartNative.InvalidHandleValue)
            throw new VrRestartInspectionException("The process table could not be snapshotted.");
        try
        {
            var entry = default(VrRestartNative.PROCESSENTRY32);
            entry.dwSize = (uint)Marshal.SizeOf<VrRestartNative.PROCESSENTRY32>();
            if (!VrRestartNative.Process32FirstW(snapshot, ref entry))
            {
                if (Marshal.GetLastWin32Error() != VrRestartNative.ErrorNoMoreFiles)
                    throw new VrRestartInspectionException("The process table could not be read.");
                return list;
            }
            do { list.Add(new VrRestartProcessEntry((int)entry.th32ProcessID, entry.szExeFile ?? "")); }
            while (VrRestartNative.Process32NextW(snapshot, ref entry));
            if (Marshal.GetLastWin32Error() != VrRestartNative.ErrorNoMoreFiles)
                throw new VrRestartInspectionException("The process table read ended early.");
        }
        finally { VrRestartNative.CloseHandle(snapshot); }
        return list;
    }

    public IVrRestartProcess? TryPinProcess(int processId)
    {
        if (processId <= 0) return null;
        // Only what is actually read is requested: the image and the
        // start time come from QueryFullProcessImageNameW and
        // GetProcessTimes, liveness from a signalled wait, and every
        // module view comes from an independent Toolhelp32 snapshot.
        // PROCESS_VM_READ is not needed and can be denied outright where
        // the limited query right is granted.
        var handle = VrRestartNative.OpenProcess(
            VrRestartNative.ProcessQueryLimitedInformation | VrRestartNative.Synchronize,
            false, (uint)processId);
        if (handle == IntPtr.Zero)
        {
            // ERROR_INVALID_PARAMETER is the only answer that proves the
            // PID does not exist. Access denied, a protected process, or
            // any other failure yields an uninspectable pin whose image
            // is unknown, so the caller refuses instead of assuming the
            // process has already exited.
            return Marshal.GetLastWin32Error() == VrRestartNative.ErrorInvalidParameter
                ? null
                : WindowsVrRestartProcess.Uninspectable(processId);
        }
        var image = VrRestartNative.QueryImagePath(handle);
        if (image is not null) image = RealFilePath(image);
        return new WindowsVrRestartProcess(processId, handle, image, VrRestartNative.GetStartTimeUtc(handle));
    }

    public bool TryLaunchVrStartup(string exePath, string workingDirectory)
    {
        // The worker passes the canonical path it already resolved
        // through a file handle, and it is launched exactly as resolved:
        // comparing it back against the raw alias would reject a
        // perfectly valid startup binary. Only the file name is still
        // required, so this can never become a general launch.
        var real = RealFilePath(exePath);
        if (real is null) return false;
        if (!string.Equals(Path.GetFileName(real), VrRestartWorker.VrstartupImageName, StringComparison.OrdinalIgnoreCase))
            return false;
        var startup = new VrRestartNative.STARTUPINFO { cb = Marshal.SizeOf<VrRestartNative.STARTUPINFO>() };
        var created = VrRestartNative.CreateProcessW(
            real,
            new StringBuilder("\"" + real + "\""),
            IntPtr.Zero, IntPtr.Zero,
            false,                                    // bInheritHandles: never leak the vrserver pipes
            VrRestartNative.CreateNewProcessGroup | VrRestartNative.CreateNoWindow,
            IntPtr.Zero,
            Path.GetDirectoryName(real) ?? workingDirectory,
            ref startup, out var info);
        if (!created) return false;
        VrRestartNative.CloseHandle(info.hThread);
        VrRestartNative.CloseHandle(info.hProcess);
        return true;
    }

    public IVrRestartMutex? TryAcquireMutex(string name)
    {
        try
        {
            var mutex = new Mutex(false, name);
            bool taken;
            try { taken = mutex.WaitOne(0); }
            catch (AbandonedMutexException) { taken = true; }   // we now own the abandoned mutex
            if (!taken)
            {
                mutex.Dispose();
                return null;
            }
            return new MutexLease(mutex);
        }
        catch (UnauthorizedAccessException) { return null; }
        catch (IOException) { return null; }
    }

    public void WriteResultAtomically(string path, string content)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
        var temp = path + "." + Environment.ProcessId.ToString(CultureInfo.InvariantCulture) + ".tmp";
        File.WriteAllText(temp, content, new UTF8Encoding(false));
        try { File.Move(temp, path, overwrite: true); }
        catch
        {
            try { File.Delete(temp); } catch { }
            throw;
        }
    }

    // A module snapshot taken while the target is still loading DLLs can
    // transiently fail with ERROR_BAD_LENGTH, which says nothing about the
    // process itself. That one answer is retried, the retry is bounded, and
    // the retained handle is rechecked before every attempt so a process
    // that exited while retrying is never walked. Every other failure is
    // returned as-is and still refuses the read.
    private static IntPtr CreateModuleSnapshot(WindowsVrRestartProcess pin)
    {
        for (var attempt = 1; ; attempt++)
        {
            var snapshot = VrRestartNative.CreateToolhelp32Snapshot(
                VrRestartNative.TH32CS_SNAPMODULE | VrRestartNative.TH32CS_SNAPMODULE32, (uint)pin.ProcessId);
            if (snapshot != VrRestartNative.InvalidHandleValue) return snapshot;
            if (Marshal.GetLastWin32Error() != VrRestartNative.ErrorBadLength) return VrRestartNative.InvalidHandleValue;
            if (attempt >= ModuleSnapshotAttempts || pin.HasExited) return VrRestartNative.InvalidHandleValue;
            Thread.Sleep(ModuleSnapshotRetryDelayMilliseconds);
        }
    }

    private static bool IsActualParent(int parentPid)
    {
        var snapshot = VrRestartNative.CreateToolhelp32Snapshot(VrRestartNative.TH32CS_SNAPPROCESS, 0);
        if (snapshot == VrRestartNative.InvalidHandleValue) return false;
        try
        {
            var self = (uint)Environment.ProcessId;
            var entry = default(VrRestartNative.PROCESSENTRY32);
            entry.dwSize = (uint)Marshal.SizeOf<VrRestartNative.PROCESSENTRY32>();
            if (!VrRestartNative.Process32FirstW(snapshot, ref entry)) return false;
            do
            {
                if (entry.th32ProcessID == self)
                    return entry.th32ParentProcessID == (uint)parentPid;
            } while (VrRestartNative.Process32NextW(snapshot, ref entry));
            return false;
        }
        catch { return false; }
        finally { VrRestartNative.CloseHandle(snapshot); }
    }

    // Resolves a path through a file handle, so junctions, symlinks,
    // short names, and relative segments are followed to the path the OS
    // considers the file's own. A hard link has several equally valid
    // names, so the result is a path identity and never a unique file
    // identity: nothing here may be read as a claim about the bytes.
    private static string? RealFilePath(string path)
    {
        var handle = VrRestartNative.CreateFileW(
            path,
            VrRestartNative.FileReadAttributes,
            VrRestartNative.FileShareAll,
            IntPtr.Zero,
            VrRestartNative.OpenExisting,
            0, IntPtr.Zero);
        if (handle == VrRestartNative.InvalidHandleValue) return null;
        try
        {
            var buffer = new StringBuilder(32768);
            var length = VrRestartNative.GetFinalPathNameByHandleW(handle, buffer, (uint)buffer.Capacity, 0);
            if (length == 0 || length >= buffer.Capacity) return null;
            return Normalize(buffer.ToString());
        }
        catch { return null; }
        finally { VrRestartNative.CloseHandle(handle); }
    }

    private static string Normalize(string path) =>
        path.StartsWith(@"\\?\UNC\", StringComparison.Ordinal) ? @"\\" + path[8..]
        : path.StartsWith(@"\\?\", StringComparison.Ordinal) ? path[4..]
        : path;

    private sealed class MutexLease : IVrRestartMutex
    {
        private Mutex? _mutex;
        public MutexLease(Mutex mutex) => _mutex = mutex;
        public void Dispose()
        {
            var mutex = _mutex;
            _mutex = null;
            if (mutex is null) return;
            try { mutex.ReleaseMutex(); } catch { }
            try { mutex.Dispose(); } catch { }
        }
    }
}

internal sealed class WindowsVrRestartProcess : IVrRestartProcess
{
    private readonly IntPtr _handle;
    private bool _disposed;

    public int ProcessId { get; }
    public string? ImageFinalPath { get; }
    public DateTime StartTimeUtc { get; }

    // Liveness is read from the retained handle, so a reused PID can
    // never be mistaken for the original process. Only a signalled
    // handle proves an exit: no handle at all, or a failed wait, is
    // reported as "not exited" so the bounded wait fails closed instead
    // of walking past a process that could not be inspected.
    public bool HasExited
    {
        get
        {
            if (_handle == IntPtr.Zero) return false;
            return VrRestartNative.WaitForSingleObject(_handle, 0) == VrRestartNative.WaitObject0;
        }
    }

    public WindowsVrRestartProcess(int processId, IntPtr handle, string? imageFinalPath, DateTime startTimeUtc) =>
        (ProcessId, _handle, ImageFinalPath, StartTimeUtc) = (processId, handle, imageFinalPath, startTimeUtc);

    public static WindowsVrRestartProcess Uninspectable(int processId) => new(processId, IntPtr.Zero, null, default);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        // Releases our handle only. No VR process is ever terminated.
        if (_handle != IntPtr.Zero) VrRestartNative.CloseHandle(_handle);
    }
}

internal static class VrRestartNative
{
    public const uint TH32CS_SNAPPROCESS = 0x00000002;
    public const uint TH32CS_SNAPMODULE = 0x00000008;
    public const uint TH32CS_SNAPMODULE32 = 0x00000010;
    public const uint ProcessQueryLimitedInformation = 0x00001000;
    public const uint ProcessVmRead = 0x00000010;
    public const uint Synchronize = 0x00100000;
    public const uint FileReadAttributes = 0x0080;
    public const uint FileShareAll = 0x00000007;
    public const uint OpenExisting = 3;
    public const uint CreateNewProcessGroup = 0x00000200;
    public const uint CreateNoWindow = 0x08000000;
    public const uint WaitObject0 = 0x00000000;
    public const uint WaitTimeout = 0x00000102;
    public const uint WaitFailed = 0xFFFFFFFF;
    public const int ErrorAccessDenied = 5;
    public const int ErrorInvalidParameter = 87;
    public const int ErrorNoMoreFiles = 18;
    public const int ErrorBadLength = 24;
    public static readonly IntPtr InvalidHandleValue = new(-1);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct PROCESSENTRY32
    {
        public uint dwSize;
        public uint cntUsage;
        public uint th32ProcessID;
        public IntPtr th32DefaultHeapID;
        public uint th32ModuleID;
        public uint cntThreads;
        public uint th32ParentProcessID;
        public int pcPriClassBase;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string? szExeFile;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct MODULEENTRY32
    {
        public uint dwSize;
        public uint th32ModuleID;
        public uint th32ProcessID;
        public uint GlblcntUsage;
        public uint ProccntUsage;
        public IntPtr modBaseAddr;
        public uint modBaseSize;
        public IntPtr hModule;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string? szModule;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string? szExePath;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO
    {
        public int cb;
        public string? lpReserved;
        public string? lpDesktop;
        public string? lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public uint dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
    public static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool Process32FirstW(IntPtr snapshot, ref PROCESSENTRY32 entry);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool Process32NextW(IntPtr snapshot, ref PROCESSENTRY32 entry);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool Module32FirstW(IntPtr snapshot, ref MODULEENTRY32 entry);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool Module32NextW(IntPtr snapshot, ref MODULEENTRY32 entry);

    [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
    public static extern IntPtr OpenProcess(uint access, bool inheritHandle, uint processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool QueryFullProcessImageNameW(IntPtr process, uint flags, StringBuilder imageName, ref uint size);

    [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
    public static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);

    [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
    public static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
    public static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern IntPtr CreateFileW(string fileName, uint desiredAccess, uint shareMode,
        IntPtr securityAttributes, uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern uint GetFinalPathNameByHandleW(IntPtr handle, StringBuilder path, uint size, uint flags);

    // lpCommandLine is an in/out LPWSTR, so it is marshalled as a mutable
    // StringBuilder rather than an immutable string.
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern bool CreateProcessW(string? applicationName, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags,
        IntPtr environment, string? currentDirectory, ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);

    public static string? QueryImagePath(IntPtr process)
    {
        try
        {
            var buffer = new StringBuilder(32768);
            uint size = (uint)buffer.Capacity;
            return QueryFullProcessImageNameW(process, 0, buffer, ref size) ? buffer.ToString() : null;
        }
        catch (Win32Exception) { return null; }
    }

    public static DateTime GetStartTimeUtc(IntPtr process)
    {
        if (!GetProcessTimes(process, out var creation, out _, out _, out _)) return default;
        return DateTime.FromFileTimeUtc(creation);
    }
}
