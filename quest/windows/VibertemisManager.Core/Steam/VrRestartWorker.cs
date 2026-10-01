// Managed half of the bounded SteamVR restart handshake.
//
// The native vrserver side spawns the installed manager directly:
//
//     VibertemisManager.App.exe --restart-vr <parent pid>
//
// with stdin/stdout redirected to a pipe pair it owns. The worker
// answers with one exact line
//
//     VIBERTEMIS_VR_RESTART_READY\n
//
// and only proceeds when the native side answers with the single
// exact line
//
//     VIBERTEMIS_VR_RESTART_COMMIT\n
//
// Everything this worker launches is derived from the registered
// SteamVR runtime (VrpathSteamVrLocator) and the installed manager
// payload (EnvironmentPathResolver + IntegrityVerifier). No launch
// path, hash, or identity ever comes from the command line, and no
// VR process is ever killed: the worker only waits for them.
//
// Two rules shape every comparison here:
//
//   - Every expected file is canonicalized through a file handle
//     (IVrRestartEnvironment.ResolveFilePath) before it is compared
//     against a real process image. The recorded runtime root can be a
//     junction or a short name, so a raw alias can never be compared
//     against a resolved path.
//   - Every deadline is measured on a monotonic clock
//     (IVrRestartClock.Elapsed). A wall clock jump can neither cut a
//     bounded wait short nor extend it. The wall clock is read only to
//     stamp the published result.
//
// Windows-specific concerns (Toolhelp32, module enumeration, final
// path resolution, CreateProcess, named mutex, stdin/stdout) live in
// the App adapter behind the ports below so the coordinator itself is
// unit testable on any OS.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Integrity;

namespace VibertemisManager.Core.Steam;

// Exact line protocol shared with the native vrserver side.
public static class VrRestartProtocol
{
    public const string ReadyLine = "VIBERTEMIS_VR_RESTART_READY";
    public const string CommitLine = "VIBERTEMIS_VR_RESTART_COMMIT";
    public const int MaxLineBytes = 128;

    public static bool IsCommitLine(string? line) =>
        string.Equals(line, CommitLine, StringComparison.Ordinal);
}

// The worker command line is exactly two arguments: the flag, then the
// parent PID. Nothing else is ever accepted, so no extra value, no
// second flag, and no prefix flag can smuggle anything in.
public static class VrRestartArguments
{
    public const string Flag = "--restart-vr";

    public static bool TryReadParentPid(string[]? args, out int parentPid) =>
        TryReadParentPid(args, Flag, out parentPid);

    public static bool TryReadParentPid(string[]? args, string flag, out int parentPid)
    {
        parentPid = 0;
        if (args is null || args.Length != 2) return false;
        if (!string.Equals(args[0], flag, StringComparison.Ordinal)) return false;
        // NumberStyles.None: no sign, no padding, no separators, no
        // culture specific digits.
        return int.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out parentPid)
            && parentPid > 0;
    }
}

// Bounded single line reader for the commit handshake. Anything that
// is not one short, LF terminated, printable ASCII line is a refusal,
// never a partial commit.
public enum VrRestartLineStatus { Line, EndOfStream, Oversize, Malformed, Failed, TimedOut }

public static class VrRestartLineReader
{
    // Reads one line from an already bounded stream. Callers must wrap
    // this in VrRestartBoundedRead on any stream that can block.
    //
    //   - every byte read counts against maxBytes, including the
    //     terminating CR and the terminating LF
    //   - the LF is required: EOF in the middle of a line is refused
    //   - at most one CR is accepted, and only immediately before the LF
    //   - only printable ASCII is accepted
    public static VrRestartLineStatus TryReadLine(Stream stream, int maxBytes, out string line)
    {
        line = "";
        if (stream is null || maxBytes < 2) return VrRestartLineStatus.Failed;
        var one = new byte[1];
        var chars = new StringBuilder();
        var counted = 0;
        var sawCr = false;
        while (true)
        {
            int read;
            try { read = stream.Read(one, 0, 1); }
            catch (IOException) { return VrRestartLineStatus.Failed; }   // pipe torn down mid read
            catch (ObjectDisposedException) { return VrRestartLineStatus.Failed; }
            catch (InvalidOperationException) { return VrRestartLineStatus.Failed; }
            if (read == 0)
                return counted == 0 ? VrRestartLineStatus.EndOfStream : VrRestartLineStatus.Failed;   // partial EOF
            if (++counted > maxBytes) return VrRestartLineStatus.Oversize;
            var b = one[0];
            if (b == (byte)'\n')
            {
                line = chars.ToString();
                return VrRestartLineStatus.Line;
            }
            if (b == (byte)'\r')
            {
                if (sawCr) return VrRestartLineStatus.Malformed;   // more than one CR
                sawCr = true;
                continue;
            }
            if (b < 0x20 || b > 0x7e) return VrRestartLineStatus.Malformed;   // not printable ASCII
            if (sawCr) return VrRestartLineStatus.Malformed;                 // CR must sit immediately before LF
            chars.Append((char)b);
        }
    }
}

// A bounded read for a pipe that cannot be read with a read timeout. An
// inherited anonymous pipe handle does not support ReadTimeout, so a
// synchronous read on it blocks forever whenever the native side stays
// silent. The blocking read therefore runs on one dedicated background
// thread and the caller waits with a deadline.
//
// On timeout the pending read is abandoned. The input stream is never
// disposed on that path, because disposing a stream with a pending
// synchronous read blocks until that read returns, which is exactly the
// hang this type exists to remove. The abandoned thread is a background
// thread, so it cannot delay process exit: the caller publishes its
// refusal and returns immediately.
public static class VrRestartBoundedRead
{
    private static readonly TimeSpan MaxWait = TimeSpan.FromMilliseconds(int.MaxValue);

    public static VrRestartLineStatus TryReadLineWithin(Stream stream, int maxBytes, TimeSpan timeout, out string line)
    {
        line = "";
        if (stream is null) return VrRestartLineStatus.Failed;
        var completion = new TaskCompletionSource<Result>(TaskCreationOptions.RunContinuationsAsynchronously);
        // The runtime default stack size is used on purpose: a one byte
        // stack cannot host this read and overflows the process.
        var reader = new Thread(() =>
        {
            try
            {
                var status = VrRestartLineReader.TryReadLine(stream, maxBytes, out var value);
                completion.TrySetResult(new Result(status, value));
            }
            catch
            {
                completion.TrySetResult(new Result(VrRestartLineStatus.Failed, ""));
            }
        })
        {
            IsBackground = true,
            Name = "vibertemis-vr-restart-commit",
        };
        reader.Start();
        // Task.Wait is bounded by a monotonic internal clock, so a wall
        // clock jump can neither extend nor shorten this wait.
        var wait = timeout < TimeSpan.Zero ? TimeSpan.Zero
            : timeout > MaxWait ? MaxWait
            : timeout;
        if (!completion.Task.Wait(wait)) return VrRestartLineStatus.TimedOut;
        var result = completion.Task.GetAwaiter().GetResult();
        line = result.Line;
        return result.Status;
    }

    private readonly record struct Result(VrRestartLineStatus Status, string Line);
}

// Windows path helpers. The worker is Windows only by contract, so
// segments are joined with the Windows separator explicitly; that
// keeps the comparison logic identical when exercised off Windows.
public static class VrRestartPaths
{
    public static string Combine(params string[] parts) => string.Join('\\', parts);

    // Both sides must already be canonical. This is a comparison of two
    // resolved paths, never an identity claim about the bytes behind
    // them: a hard link has several equally valid names.
    public static bool PathsEqual(string? a, string? b) =>
        !string.IsNullOrEmpty(a) && !string.IsNullOrEmpty(b)
        && string.Equals(a.TrimEnd('\\'), b.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);

    // Containment only; never sufficient to identify a file.
    public static bool IsUnder(string? child, string directory)
    {
        if (string.IsNullOrEmpty(child) || string.IsNullOrEmpty(directory)) return false;
        return child.StartsWith(directory.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase);
    }
}

public enum VrRestartOutcome
{
    Coalesced,
    Succeeded,
    InvalidRequest,
    SteamVrNotInstalled,
    ParentUnverified,
    ModuleMismatch,
    IntegrityFailed,
    RuntimeMissing,
    UnexpectedRuntime,
    NotCommitted,
    DidNotExit,
    LaunchFailed,
    StartupTimeout,
    WorkerError,
}

public sealed record VrRestartResult(VrRestartOutcome Outcome, string Message)
{
    public bool Succeeded => Outcome == VrRestartOutcome.Succeeded;
}

// Fixed, user facing strings. No PID, path, hash, or token is ever
// placed in a result message.
public static class VrRestartMessages
{
    public const string InvalidRequest = "The SteamVR restart request was not valid.";
    public const string AlreadyInProgress = "A SteamVR restart is already in progress.";
    public const string NotInstalled = "SteamVR is not installed. Install SteamVR, then connect again.";
    public const string ParentUnverified = "SteamVR restart could not start. Reopen the manager and try again.";
    public const string ModuleMismatch = "The running SteamVR session is not the Vibertemis runtime. Reconnect to restart it.";
    public const string IntegrityFailed = "The Vibertemis installation is incomplete. Reinstall, then connect again.";
    public const string RuntimeMissing = "SteamVR runtime files are missing. Reinstall SteamVR, then connect again.";
    public const string UnexpectedRuntime = "Another SteamVR runtime is still running. Close SteamVR, then connect again.";
    public const string NotCommitted = "SteamVR did not confirm the restart.";
    public const string DidNotExit = "SteamVR did not close. Close SteamVR, then connect again.";
    public const string AlreadyRestarted = "SteamVR was already restarted.";
    public const string LaunchFailed = "SteamVR could not be started. Reopen SteamVR, then connect again.";
    public const string StartupTimeout = "SteamVR did not start. Reopen SteamVR, then connect again.";
    public const string AddonNotLoaded = "SteamVR came up without the Vibertemis driver. Check the Vibertemis SteamVR add-on is enabled, then reopen SteamVR.";
    public const string Restarted = "SteamVR restarted. Reconnect your headset.";
    public const string WorkerError = "SteamVR restart failed. Reopen SteamVR, then connect again.";
}

public sealed record VrRestartResultDocument(int Schema, string TimestampUtc, bool Succeeded, string Message);

public static class VrRestartResultWriter
{
    public const int MaxMessageChars = 200;
    private static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    public static string Sanitize(string? message)
    {
        if (string.IsNullOrEmpty(message)) return "";
        var sb = new StringBuilder(Math.Min(message.Length, MaxMessageChars));
        foreach (var c in message)
        {
            if (sb.Length >= MaxMessageChars) break;
            sb.Append(char.IsControl(c) ? ' ' : c);
        }
        return sb.ToString().Trim();
    }

    public static string Serialize(VrRestartResult result, DateTime utcNow) =>
        JsonSerializer.Serialize(new VrRestartResultDocument(
            Schema: 1,
            TimestampUtc: utcNow.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fff'Z'", CultureInfo.InvariantCulture),
            Succeeded: result.Succeeded,
            Message: Sanitize(result.Message)), Options);
}

public sealed record VrRestartProcessEntry(int ProcessId, string ImageName);

// A retained handle to an already running external process. The
// adapter pins the exact OS handle plus the process start time so PID
// reuse can never redirect a later check. Disposing releases the
// handle; it never terminates the process.
public interface IVrRestartProcess : IDisposable
{
    int ProcessId { get; }
    // True only when the retained handle proves the process ended.
    // Unknown liveness (no handle at all, or a failed wait) is reported
    // as not exited, so the bounded wait fails closed instead of racing
    // past a process it could not read.
    bool HasExited { get; }
    string? ImageFinalPath { get; }
    DateTime StartTimeUtc { get; }
}

public sealed record VrRestartParentPin(string RuntimeRoot, IVrRestartProcess Process);

public interface IVrRestartMutex : IDisposable { }

public interface IVrRestartProtocol
{
    void WriteReady();
    bool TryReadCommit(TimeSpan timeout);
}

// Deterministic clock. Elapsed is the only source of a deadline; UtcNow
// exists to stamp the published result.
public interface IVrRestartClock
{
    TimeSpan Elapsed { get; }
    DateTime UtcNow { get; }
    void Delay(TimeSpan duration);
}

public interface IVrRestartEnvironment
{
    string InstallRoot { get; }
    string ManagerStateDir { get; }

    // Registered SteamVR runtimes only. Never derived from arguments.
    IReadOnlyList<string> LocateRegisteredSteamVrRuntimes();
    bool FileExists(string path);
    bool VerifyInstalledFile(string relativePath);

    // The canonical path of an existing file, resolved through a file
    // handle, or null when it is absent or cannot be opened. This is
    // the only form ever compared against a real process image.
    string? ResolveFilePath(string path);

    // Returns the retained parent pin only when parentPid really is
    // this worker's parent and its vrserver.exe lives inside a
    // registered runtime (canonical final path compared).
    VrRestartParentPin? TryPinRequestingVrserver(int parentPid, IReadOnlyList<string> runtimeRoots);
    IReadOnlyList<string> GetModuleImagePaths(IVrRestartProcess process);
    // Throws VrRestartInspectionException on any snapshot or read
    // failure. An empty or partial list is returned only for a complete
    // successful read, so a failure can never be mistaken for "no VR
    // process is running".
    IReadOnlyList<VrRestartProcessEntry> ListProcesses();
    // null only when the process is confirmed gone. Every other error
    // yields a pin with no image, so the caller refuses instead of
    // assuming the process has already exited.
    IVrRestartProcess? TryPinProcess(int processId);
    bool TryLaunchVrStartup(string exePath, string workingDirectory);
    IVrRestartMutex? TryAcquireMutex(string name);
    void WriteResultAtomically(string path, string content);
}

// Raised when a process table or module list cannot be trusted. The
// worker fails closed: an incomplete view is never treated as an empty
// one, and it is never a reason to launch anything.
public sealed class VrRestartInspectionException : Exception
{
    public VrRestartInspectionException(string message) : base(message) { }
}

public sealed class SystemVrRestartClock : IVrRestartClock
{
    private readonly Stopwatch _monotonic = Stopwatch.StartNew();

    public TimeSpan Elapsed => _monotonic.Elapsed;
    public DateTime UtcNow => DateTime.UtcNow;
    public void Delay(TimeSpan duration)
    {
        if (duration > TimeSpan.Zero) Thread.Sleep(duration);
    }
}

public sealed class VrRestartWorker
{
    public const string MutexName = @"Local\VibertemisVRHostManager-vr-restart";
    public const string ResultFileName = "vr-restart-result.json";
    public const string VrserverImageName = "vrserver.exe";
    public const string VrstartupImageName = "vrstartup.exe";
    public const string VrMonitorImageName = "vrmonitor.exe";
    public const string VrCompositorImageName = "vrcompositor.exe";
    public const string DriverModuleName = "driver_alvr_server.dll";

    // The bundled driver is installed by this product, so the one
    // expected driver module lives under the manager install root. A
    // file of the same name inside the SteamVR runtime folder is never
    // ours, no matter what it is called.
    public const string DriverModuleRelativePath = @"runtime\bin\win64\driver_alvr_server.dll";

    public static readonly TimeSpan CommitTimeout = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan ExitTimeout = TimeSpan.FromSeconds(45);
    public static readonly TimeSpan StartupTimeout = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan PollInterval = TimeSpan.FromMilliseconds(100);

    private readonly IVrRestartEnvironment _env;
    private readonly IVrRestartProtocol _protocol;
    private readonly IVrRestartClock _clock;
    private VrRestartResult? _published;

    public VrRestartWorker(IVrRestartEnvironment env, IVrRestartProtocol protocol, IVrRestartClock clock) =>
        (_env, _protocol, _clock) = (env, protocol, clock);

    public string ResultPath => VrRestartPaths.Combine(_env.ManagerStateDir, ResultFileName);

    public VrRestartResult Run(int parentPid)
    {
        if (parentPid <= 0) return Publish(VrRestartOutcome.InvalidRequest, VrRestartMessages.InvalidRequest);

        // One worker at a time. The lease is held for the whole
        // attempt; a lost (abandoned) mutex is recovered by the
        // adapter and reported as acquired.
        var lease = _env.TryAcquireMutex(MutexName);
        if (lease is null) return new VrRestartResult(VrRestartOutcome.Coalesced, VrRestartMessages.AlreadyInProgress);
        using (lease)
        {
            try { return Execute(parentPid); }
            catch { return Publish(VrRestartOutcome.WorkerError, VrRestartMessages.WorkerError); }
        }
    }

    private VrRestartResult Execute(int parentPid)
    {
        // 1. Registered runtime, from the vrpath record only.
        var runtimes = _env.LocateRegisteredSteamVrRuntimes();
        if (runtimes.Count == 0) return Publish(VrRestartOutcome.SteamVrNotInstalled, VrRestartMessages.NotInstalled);

        // 2. The requesting PID must be this worker's real parent and a
        //    vrserver.exe inside a registered runtime. The pin retains
        //    the exact handle and start time.
        var pin = _env.TryPinRequestingVrserver(parentPid, runtimes);
        if (pin is null) return Publish(VrRestartOutcome.ParentUnverified, VrRestartMessages.ParentUnverified);
        using var vrserver = pin.Process;

        // 3. Embedded installed native hashes must all verify.
        foreach (var relative in InstalledPayload.NativePaths)
            if (!_env.VerifyInstalledFile(relative))
                return Publish(VrRestartOutcome.IntegrityFailed, VrRestartMessages.IntegrityFailed);

        // 4. Runtime binaries must exist before anything is signalled.
        var win64 = VrRestartPaths.Combine(pin.RuntimeRoot, "bin", "win64");
        var vrserverRaw = VrRestartPaths.Combine(win64, VrserverImageName);
        var vrstartupRaw = VrRestartPaths.Combine(win64, VrstartupImageName);
        if (!_env.FileExists(vrserverRaw) || !_env.FileExists(vrstartupRaw))
            return Publish(VrRestartOutcome.RuntimeMissing, VrRestartMessages.RuntimeMissing);

        // 5. Canonical form of every expected file, resolved through a
        //    file handle. The parent image and the module paths are both
        //    real, resolved paths, so a raw alias can never be compared
        //    against them.
        var vrserverImage = _env.ResolveFilePath(vrserverRaw);
        var vrstartup = _env.ResolveFilePath(vrstartupRaw);
        var monitorImage = _env.ResolveFilePath(VrRestartPaths.Combine(win64, VrMonitorImageName));
        var compositorImage = _env.ResolveFilePath(VrRestartPaths.Combine(win64, VrCompositorImageName));
        if (vrserverImage is null || vrstartup is null || monitorImage is null || compositorImage is null)
            return Publish(VrRestartOutcome.RuntimeMissing, VrRestartMessages.RuntimeMissing);
        // The driver verified in step 3, so an unresolvable path is a
        // fault in our own installed payload, not a SteamVR one.
        var driverModule = _env.ResolveFilePath(VrRestartPaths.Combine(_env.InstallRoot, DriverModuleRelativePath));
        if (driverModule is null)
            return Publish(VrRestartOutcome.IntegrityFailed, VrRestartMessages.IntegrityFailed);

        var expected = new RuntimeExpectations(
            Win64: win64,
            VrserverImage: vrserverImage,
            Vrstartup: vrstartup,
            MonitorImage: monitorImage,
            CompositorImage: compositorImage,
            DriverModule: driverModule);

        if (!VrRestartPaths.PathsEqual(vrserver.ImageFinalPath, expected.VrserverImage))
            return Publish(VrRestartOutcome.ParentUnverified, VrRestartMessages.ParentUnverified);

        // 6. The loaded driver must be exactly our installed one, which
        //    lives in the install root rather than the SteamVR folder.
        //    An unreadable module list is a refusal, not an empty match.
        try
        {
            if (!Contains(_env.GetModuleImagePaths(vrserver), expected.DriverModule))
                return Publish(VrRestartOutcome.ModuleMismatch, VrRestartMessages.ModuleMismatch);
        }
        catch (VrRestartInspectionException)
        {
            return Publish(VrRestartOutcome.ModuleMismatch, VrRestartMessages.ModuleMismatch);
        }

        // 7. The committed handshake. Every retained handle is released
        //    on the way out, and no VR process is ever terminated.
        var retained = new List<IVrRestartProcess>();
        try
        {
            return RunCommitted(vrserver, expected, retained);
        }
        catch (VrRestartInspectionException)
        {
            // An untrustworthy process table or module list is never read
            // as "nothing is running", and never as permission to launch.
            return Publish(VrRestartOutcome.UnexpectedRuntime, VrRestartMessages.UnexpectedRuntime);
        }
        finally
        {
            foreach (var pinned in retained) pinned.Dispose();
        }
    }

    private sealed record RuntimeExpectations(
        string Win64, string VrserverImage, string Vrstartup, string MonitorImage,
        string CompositorImage, string DriverModule);

    private VrRestartResult RunCommitted(
        IVrRestartProcess vrserver, RuntimeExpectations expected, List<IVrRestartProcess> retained)
    {
        // 8. Retain the same runtime vrmonitor/vrcompositor handles and
        //    refuse anything unexpected, before READY. The comparison is
        //    against the exact canonical image, not a directory prefix.
        foreach (var entry in _env.ListProcesses())
        {
            var isVrserver = IsName(entry.ImageName, VrserverImageName);
            if (!isVrserver && !IsName(entry.ImageName, VrMonitorImageName)
                && !IsName(entry.ImageName, VrCompositorImageName)) continue;
            // The requesting parent was already pinned, image compared,
            // and module checked above, so it is never pinned a second
            // time here: a process id is unique among live processes, and
            // a second handle to the parent would only duplicate retained
            // state the caller already owns and releases.
            if (entry.ProcessId == vrserver.ProcessId) continue;
            var other = _env.TryPinProcess(entry.ProcessId);
            if (other is null) continue;                       // confirmed gone
            if (other.ImageFinalPath is null || !IsRuntimeImage(other.ImageFinalPath, expected))
            {
                other.Dispose();
                return Publish(VrRestartOutcome.UnexpectedRuntime, VrRestartMessages.UnexpectedRuntime);
            }
            retained.Add(other);
        }

        // 9. READY, then a bounded, exact COMMIT. No launch and no VR
        //    mutation without it.
        _protocol.WriteReady();
        if (!_protocol.TryReadCommit(CommitTimeout))
            return Publish(VrRestartOutcome.NotCommitted, VrRestartMessages.NotCommitted);

        // 10. Every retained handle must exit inside one shared 45s
        //     budget, measured monotonically. Nothing is ever killed.
        var exitDeadline = _clock.Elapsed + ExitTimeout;
        while (true)
        {
            if (vrserver.HasExited && retained.All(p => p.HasExited)) break;
            if (_clock.Elapsed >= exitDeadline)
                return Publish(VrRestartOutcome.DidNotExit, VrRestartMessages.DidNotExit);
            _clock.Delay(PollInterval);
        }

        // 11. Someone else may already have brought the runtime back.
        //     Never start a second vrserver.
        var before = ScanReplacement(expected);
        if (before == VrserverScan.Conflict)
            return Publish(VrRestartOutcome.UnexpectedRuntime, VrRestartMessages.UnexpectedRuntime);
        var relaunch = before == VrserverScan.None;
        if (relaunch && !_env.TryLaunchVrStartup(expected.Vrstartup, expected.Win64))
            return Publish(VrRestartOutcome.LaunchFailed, VrRestartMessages.LaunchFailed);

        // 12. Success only after the replacement runs the same canonical
        //     vrserver.exe AND has our installed driver loaded. A runtime
        //     that came up without it is a safe mode or a blocked add-on,
        //     never a successful restart, and it is waited out inside the
        //     same bounded budget.
        var startupDeadline = _clock.Elapsed + StartupTimeout;
        while (true)
        {
            var scan = ScanReplacement(expected);
            if (scan == VrserverScan.Conflict)
                return Publish(VrRestartOutcome.UnexpectedRuntime, VrRestartMessages.UnexpectedRuntime);
            if (scan == VrserverScan.Ready)
                return Publish(VrRestartOutcome.Succeeded, relaunch
                    ? VrRestartMessages.Restarted
                    : VrRestartMessages.AlreadyRestarted);
            if (_clock.Elapsed >= startupDeadline)
                return Publish(VrRestartOutcome.StartupTimeout, scan == VrserverScan.MissingDriver
                    ? VrRestartMessages.AddonNotLoaded
                    : VrRestartMessages.StartupTimeout);
            _clock.Delay(PollInterval);
        }
    }

    private enum VrserverScan { None, MissingDriver, Ready, Conflict }

    // Classifies every running vrserver.exe. Anything outside the
    // canonical image is a conflict; the canonical image only counts as
    // a restart when our installed driver is loaded into it. The whole
    // table is always scanned: a valid vrserver that appears first never
    // hides a foreign one behind it, so one match is a match only after
    // every other entry has been checked too.
    private VrserverScan ScanReplacement(RuntimeExpectations expected)
    {
        var found = VrserverScan.None;
        var loaded = false;
        foreach (var entry in _env.ListProcesses())
        {
            if (!IsName(entry.ImageName, VrserverImageName)) continue;
            using var p = _env.TryPinProcess(entry.ProcessId);
            if (p is null) continue;
            var image = p.ImageFinalPath;
            // A conflict anywhere outranks every success, including one
            // already seen, so it is reported immediately.
            if (image is null || !VrRestartPaths.PathsEqual(image, expected.VrserverImage))
                return VrserverScan.Conflict;
            if (HasDriverModule(p, expected.DriverModule)) { loaded = true; continue; }
            if (found == VrserverScan.None) found = VrserverScan.MissingDriver;
        }
        return loaded ? VrserverScan.Ready : found;
    }

    // Unknown is never "loaded": a module list that cannot be read at
    // this instant is treated as not ready, and the bounded startup wait
    // turns that into a failure instead of a false success.
    private bool HasDriverModule(IVrRestartProcess process, string driverModule)
    {
        try { return Contains(_env.GetModuleImagePaths(process), driverModule); }
        catch { return false; }
    }

    private static bool IsRuntimeImage(string image, RuntimeExpectations expected) =>
        VrRestartPaths.PathsEqual(image, expected.VrserverImage)
        || VrRestartPaths.PathsEqual(image, expected.MonitorImage)
        || VrRestartPaths.PathsEqual(image, expected.CompositorImage);

    // Writes the outcome exactly once, atomically, into the manager
    // state directory. A later failure can never overwrite a recorded
    // outcome.
    private VrRestartResult Publish(VrRestartOutcome outcome, string message)
    {
        if (_published is not null) return _published;
        var result = new VrRestartResult(outcome, message);
        _published = result;
        try { _env.WriteResultAtomically(ResultPath, VrRestartResultWriter.Serialize(result, _clock.UtcNow)); }
        catch { /* result publication is best effort; the exit code still reports */ }
        return result;
    }

    private static bool Contains(IReadOnlyList<string> values, string exact) =>
        values.Any(v => VrRestartPaths.PathsEqual(v, exact));

    private static bool IsName(string name, string exact) =>
        string.Equals(name, exact, StringComparison.OrdinalIgnoreCase);
}
