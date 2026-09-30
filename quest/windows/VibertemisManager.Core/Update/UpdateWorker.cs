using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;

namespace VibertemisManager.Core.Update;

public enum UpdateStage
{
    Idle,
    Validating,
    WaitingForCommit,
    WaitingForParent,
    VerifyingInstaller,
    Installing,
    VerifyingInstall,
    Restarting,
    Completed,
    Failed,
}

public sealed record UpdateProgress2(UpdateStage Stage, string Message);

public sealed record UpdateRunResult(
    UpdateOutcome Outcome,
    int? RestartedPid,
    bool OriginalRelaunched,
    bool InstallerLaunched,
    int? InstallerExitCodeActual);

public interface IUpdateEnvironment
{
    string CurrentExecutablePath { get; }
    byte[] CurrentExecutableSha256 { get; }
    bool FileExists(string path);
    long FileSize(string path);
    byte[] ReadAllBytes(string path);
    string ReadAllText(string path);
    void WriteAllText(string path, string content);
    void CopyFile(string source, string destination, bool overwrite);
    void DeleteFile(string path);
    void EnsureDirectory(string path);
    string FullPath(string path);

    IUpdateProcess OpenParent(int pid);
    string? GetFileVersion(string path);

    IUpdateEvent? CreateOrOpenEvent(string name, bool initialState);

    // Launches a process and retains its handle so ExitCode is read
    // from the owned handle (avoiding PID reuse and "exitCode=-1"
    // races). The handle is disposed by the implementation after
    // exit; callers MUST NOT call this twice for the same logical
    // process.
    IUpdateProcess LaunchRetained(string exePath, IReadOnlyList<string> args, string? workingDir);

    // Same as LaunchRetained but the returned process is marked
    // owned-by-this-worker; a bounded Kill can be issued via the
    // returned handle if the worker needs to abort only this child.
    IUpdateProcess LaunchRetainedOwned(string exePath, IReadOnlyList<string> args, string? workingDir);

    bool WaitForExit(IUpdateProcess process, TimeSpan timeout);
    void KillOwned(IUpdateProcess process);
}

public interface IUpdateProcess : IDisposable
{
    int ProcessId { get; }
    bool HasExited { get; }
    int ExitCode { get; }
    string? ImagePath { get; }
}

public interface IUpdateEvent : IDisposable
{
    void Set();
    bool WaitOne(TimeSpan timeout);
}

public sealed class UpdateWorker
{
    public const string HelperExeName = "VibertemisVR-HostManager-Update.exe";

    public static readonly TimeSpan CommitTimeout = TimeSpan.FromSeconds(20);
    public static readonly TimeSpan ParentExitTimeout = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan ParentExitPoll = TimeSpan.FromMilliseconds(100);
    public static readonly TimeSpan InstallerTimeout = TimeSpan.FromHours(2);
    public static readonly TimeSpan VerifyInstallTimeout = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan OriginalVerifyTimeout = TimeSpan.FromSeconds(15);

    private readonly IUpdateEnvironment _env;
    private readonly string _publicKeyPem;
    private readonly string _expectedCacheRoot;

    public UpdateWorker(IUpdateEnvironment env, string publicKeyPem, string expectedCacheRoot)
    {
        _env = env;
        _publicKeyPem = publicKeyPem;
        _expectedCacheRoot = expectedCacheRoot;
    }

    public UpdateRunResult Run(UpdateJobInputs inputs, string outcomePath,
        IProgress<UpdateProgress2>? progress = null)
    {
        inputs = inputs with { ExpectedCacheRoot = _expectedCacheRoot };
        var (ctx, err) = UpdateJobParser.ParseTrustedOrError(inputs, _publicKeyPem);
        if (ctx is null || err is not null)
        {
            var failed = NewOutcome(inputs, UpdateOutcomeKind.JobInvalid, null, null, null,
                err?.Detail ?? "Job invalid", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        return RunTrusted(ctx, outcomePath, progress);
    }

    private UpdateRunResult RunTrusted(UpdateJobContext ctx, string outcomePath,
        IProgress<UpdateProgress2>? progress = null)
    {
        progress?.Report(new UpdateProgress2(UpdateStage.Validating, "Validating update job"));

        // Parent PID executable path must match originalManagerPath. Until
        // we see it match, we must NOT execute anything. The job is the
        // source of the expected path; the running OS is the source of
        // truth for what PID belongs to it.
        IUpdateProcess parent;
        try { parent = _env.OpenParent(ctx.ParentPid); }
        catch {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Could not verify the running manager. Reopen it and retry.", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        using var parentHandle = parent;
        if (parent.HasExited || parent.ImagePath is null || !UpdateJobParser.PathsEqual(parent.ImagePath, ctx.OriginalManagerPath)) {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Parent process image does not match original manager path", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        // Helper must equal the original manager bytes (proves it is the
        // genuine manager copy, not a substituted payload).
        var helperPath = _env.CurrentExecutablePath;
        if (!UpdateJobParser.IsUnder(helperPath, ctx.CacheDir))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Helper path is not under the cache directory", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        if (!_env.FileExists(ctx.OriginalManagerPath))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Original manager missing", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        var originalHash = _env.ReadAllBytes(ctx.OriginalManagerPath);
        var actualHelperHash = _env.CurrentExecutableSha256;
        var expectedOriginalHash = SHA256.HashData(originalHash);
        if (!UpdateJobParser.BytesEqual(actualHelperHash, expectedOriginalHash))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Helper bytes do not match the original manager copy", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }

        // Two-phase commit. Phase 1: signal READY. Phase 2: wait for
        // COMMIT (bounded). Without COMMIT the worker aborts. This
        // prevents a hung worker from surprising the user when the
        // manager eventually exits.
        IUpdateEvent? ready = null;
        IUpdateEvent? commit = null;
        try
        {
            ready = _env.CreateOrOpenEvent(ctx.ReadyEventName, false);
            commit = _env.CreateOrOpenEvent(ctx.CommitEventName, false);
            ready?.Set();
        }
        catch
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Could not open commit/ready events", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }
        finally
        {
            ready?.Dispose();
        }
        progress?.Report(new UpdateProgress2(UpdateStage.WaitingForCommit, "Waiting for manager commit"));
        bool committed;
        try { committed = commit?.WaitOne(CommitTimeout) ?? false; }
        catch { committed = false; }
        commit?.Dispose();
        if (!committed)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Manager did not signal commit. Aborting.", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }

        // Wait for the parent manager to exit. Bounded.
        progress?.Report(new UpdateProgress2(UpdateStage.WaitingForParent, "Waiting for previous manager to exit"));
        if (!_env.WaitForExit(parent, ParentExitTimeout))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.ParentTimeout, null, null, null,
                "Previous manager did not exit. Retry after closing it.", "");
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, false, null);
        }

        // Re-verify the installer digest at the execution boundary.
        progress?.Report(new UpdateProgress2(UpdateStage.VerifyingInstaller, "Re-verifying installer"));
        if (!_env.FileExists(ctx.InstallerPath))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Installer file missing in cache", "");
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        if (_env.FileSize(ctx.InstallerPath) != ctx.InstallerBytes)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Installer size mismatch at execution boundary", "");
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        var installerBytes = _env.ReadAllBytes(ctx.InstallerPath);
        var actualInstallerSha = SHA256.HashData(installerBytes);
        var expectedInstallerSha = HexToBytes(ctx.InstallerSha256);
        if (!UpdateJobParser.BytesEqual(actualInstallerSha, expectedInstallerSha))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Installer digest mismatch at execution boundary", "");
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }

        // Launch the installer and KEEP its handle. We do NOT restart
        // anything while the installer is alive.
        var logDir = _env.FullPath(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "VibertemisVRHostManager", "logs"));
        _env.EnsureDirectory(logDir);
        var logPath = Path.Combine(logDir, $"update-{DateTime.UtcNow:yyyyMMdd-HHmmss}.log");
        var installerArgs = new List<string>
        {
            "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART",
            "/DIR=" + ctx.ProgramsRoot,
            "/LOG=" + logPath,
        };
        progress?.Report(new UpdateProgress2(UpdateStage.Installing, "Running installer"));
        IUpdateProcess installer;
        try
        {
            installer = _env.LaunchRetained(ctx.InstallerPath, installerArgs, ctx.CacheDir);
        }
        catch (Exception ex)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Installer did not start: " + ex.Message, logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        if (installer.ProcessId <= 0)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.WorkerError, null, null, null,
                "Installer did not start", logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        if (!_env.WaitForExit(installer, InstallerTimeout))
        {
            // Installer still alive: never restart anything while it is.
            // The worker exits; the user will see an incomplete install.
            installer.Dispose();
            var pending = NewOutcome(ctx, UpdateOutcomeKind.InstallerFailed, null, null, null,
                "Installer did not exit within the bounded wait", logPath);
            WriteOutcome(outcomePath, pending);
            return new UpdateRunResult(pending, null, false, true, null);
        }
        int installerExit = installer.ExitCode;
        installer.Dispose();
        if (installerExit != 0)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.InstallerFailed, installerExit, null, null,
                "Installer exited with code " + installerExit, logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }

        // Verify installed manager. BOTH checks must succeed.
        progress?.Report(new UpdateProgress2(UpdateStage.VerifyingInstall, "Verifying installed manager"));
        var installedManager = Path.Combine(ctx.ProgramsRoot, "manager", "VibertemisManager.App.exe");
        if (!_env.FileExists(installedManager))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, null, null,
                "Installed manager executable not found", logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        var installedVersion = _env.GetFileVersion(installedManager);
        if (string.IsNullOrEmpty(installedVersion) || !VersionsMatch(installedVersion, ctx.ExpectedVersion))
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, installedVersion, null,
                "Installed manager FileVersion does not match expected: " +
                (installedVersion ?? "<missing>") + " vs " + ctx.ExpectedVersion, logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        IUpdateProcess verify;
        try
        {
            verify = _env.LaunchRetainedOwned(installedManager, new[] { "--verify-install" }, null);
        }
        catch (Exception ex)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, installedVersion, null,
                "--verify-install did not start: " + ex.Message, logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        if (verify.ProcessId <= 0 || !_env.WaitForExit(verify, VerifyInstallTimeout))
        {
            // Verify still alive: bounded kill of ONLY the owned verify
            // process. Never kill the installer (it already exited) and
            // never restart anything while the verify handle is live.
            if (verify.ProcessId > 0) _env.KillOwned(verify);
            if (!verify.HasExited) {
                var stillRunning = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, installedVersion, null,
                    "Verification process is still running; no manager was restarted.", logPath);
                WriteOutcome(outcomePath, stillRunning);
                verify.Dispose();
                return new UpdateRunResult(stillRunning, null, false, true, installerExit);
            }
            verify.Dispose();
            var failed = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, installedVersion, null,
                "Installed manager --verify-install did not return in time", logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        int verifyExit = verify.ExitCode;
        verify.Dispose();
        if (verifyExit != 0)
        {
            var failed = NewOutcome(ctx, UpdateOutcomeKind.VerificationFailed, installerExit, installedVersion, verifyExit,
                "Installed manager --verify-install did not return 0", logPath);
            WriteOutcome(outcomePath, failed);
            return OnFailure(failed, ctx, outcomePath);
        }
        // Both checks succeeded. Write outcome BEFORE launching the new
        // manager so the next startup sees the verified values.
        var success = NewOutcome(ctx, UpdateOutcomeKind.Success, installerExit, installedVersion, verifyExit,
            "Update installed successfully.", logPath);
        WriteOutcome(outcomePath, success);
        progress?.Report(new UpdateProgress2(UpdateStage.Restarting, "Restarting installed manager"));
        IUpdateProcess installed;
        try
        {
            installed = _env.LaunchRetained(installedManager, Array.Empty<string>(), Path.GetDirectoryName(installedManager));
        }
        catch (Exception ex)
        {
            var failed = success with { Kind = UpdateOutcomeKind.WorkerError, Detail = "Update installed, but the manager could not reopen: " + ex.Message };
            WriteOutcome(outcomePath, failed);
            return new UpdateRunResult(failed, null, false, true, installerExit);
        }
        var installedPid = installed.ProcessId;
        installed.Dispose();
        progress?.Report(new UpdateProgress2(UpdateStage.Completed, "Update completed"));
        return new UpdateRunResult(success, installedPid, false, true, installerExit);
    }

    // Failure recovery: try to relaunch the original manager ONLY if it
    // is still valid (file exists AND --verify-install on it returns 0).
    // This prevents executing a partially-installed payload. Outcome is
    // already written above by the caller; this method must NOT write
    // again or it could overwrite the recorded failure.
    private UpdateRunResult OnFailure(UpdateOutcome outcome, UpdateJobContext ctx, string outcomePath)
    {
        if (outcome.Kind == UpdateOutcomeKind.VerificationFailed && outcome.InstalledFileVersion == ctx.ExpectedVersion)
            return new UpdateRunResult(outcome, null, false, true, outcome.InstallerExitCode);
        if (!_env.FileExists(ctx.OriginalManagerPath))
            return new UpdateRunResult(outcome, null, false, false, outcome.InstallerExitCode);
        IUpdateProcess verify;
        try
        {
            verify = _env.LaunchRetainedOwned(ctx.OriginalManagerPath, new[] { "--verify-install" }, null);
        }
        catch { return new UpdateRunResult(outcome, null, false, false, outcome.InstallerExitCode); }
        if (verify.ProcessId <= 0 || !_env.WaitForExit(verify, OriginalVerifyTimeout))
        {
            if (verify.ProcessId > 0) _env.KillOwned(verify);
            return new UpdateRunResult(outcome, null, false, false, outcome.InstallerExitCode);
        }
        var originalValid = verify.ExitCode == 0;
        verify.Dispose();
        if (!originalValid) return new UpdateRunResult(outcome, null, false, true, outcome.InstallerExitCode);
        IUpdateProcess relaunched;
        try
        {
            relaunched = _env.LaunchRetained(ctx.OriginalManagerPath, Array.Empty<string>(),
                Path.GetDirectoryName(ctx.OriginalManagerPath));
        }
        catch { return new UpdateRunResult(outcome, null, false, false, outcome.InstallerExitCode); }
        var relaunchedPid = relaunched.ProcessId;
        relaunched.Dispose();
        return new UpdateRunResult(outcome, relaunchedPid, true, true, outcome.InstallerExitCode);
    }

    private static byte[] HexToBytes(string hex)
    {
        var len = hex.Length / 2;
        var bytes = new byte[len];
        for (var i = 0; i < len; i++) bytes[i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
        return bytes;
    }

    private static bool VersionsMatch(string installed, string expected)
    {
        try
        {
            var iParts = installed.Split('.', '+', '-');
            var eParts = expected.Split('.');
            for (var i = 0; i < 4; i++)
            {
                if (i >= iParts.Length || i >= eParts.Length) return false;
                if (int.Parse(iParts[i]) != int.Parse(eParts[i])) return false;
            }
            return true;
        }
        catch { return false; }
    }

    private void WriteOutcome(string outcomePath, UpdateOutcome outcome)
    {
        try
        {
            _env.EnsureDirectory(Path.GetDirectoryName(outcomePath) ?? ".");
            _env.WriteAllText(outcomePath, outcome.Serialize());
        }
        catch { /* outcome write failures are non-fatal */ }
    }

    private static UpdateOutcome NewOutcome(UpdateJobInputs inputs, UpdateOutcomeKind kind,
        int? installerExit, string? installedVersion, int? verifyExit, string detail, string logPath) =>
        new UpdateOutcome(
            Schema: UpdateOutcome.CurrentSchema,
            ExpectedVersion: "",
            PreviousVersion: SignedRelease.CurrentVersion,
            Kind: kind,
            InstallerExitCode: installerExit,
            InstalledFileVersion: installedVersion,
            VerifyInstallExitCode: verifyExit,
            InstallerLogPath: logPath,
            Detail: detail,
            Timestamp: DateTime.UtcNow);

    private static UpdateOutcome NewOutcome(UpdateJobContext ctx, UpdateOutcomeKind kind,
        int? installerExit, string? installedVersion, int? verifyExit, string detail, string logPath) =>
        new UpdateOutcome(
            Schema: UpdateOutcome.CurrentSchema,
            ExpectedVersion: ctx.ExpectedVersion,
            PreviousVersion: SignedRelease.CurrentVersion,
            Kind: kind,
            InstallerExitCode: installerExit,
            InstalledFileVersion: installedVersion,
            VerifyInstallExitCode: verifyExit,
            InstallerLogPath: logPath,
            Detail: detail,
            Timestamp: DateTime.UtcNow);
}

public sealed class WindowsUpdateEnvironment : IUpdateEnvironment
{

    public string CurrentExecutablePath => Process.GetCurrentProcess().MainModule?.FileName
        ?? AppContext.BaseDirectory;

    public byte[] CurrentExecutableSha256
    {
        get
        {
            using var s = File.OpenRead(CurrentExecutablePath);
            return SHA256.HashData(s);
        }
    }

    public bool FileExists(string path) => File.Exists(path);
    public long FileSize(string path) => new FileInfo(path).Length;
    public byte[] ReadAllBytes(string path) => File.ReadAllBytes(path);
    public string ReadAllText(string path) => File.ReadAllText(path);
    public void WriteAllText(string path, string content) => File.WriteAllText(path, content);
    public void CopyFile(string source, string destination, bool overwrite) => File.Copy(source, destination, overwrite);
    public void DeleteFile(string path) { if (File.Exists(path)) File.Delete(path); }
    public void EnsureDirectory(string path) => Directory.CreateDirectory(path);
    public string FullPath(string path) => Path.GetFullPath(path);

    public IUpdateProcess OpenParent(int pid) => new WinProcess(Process.GetProcessById(pid));
    public string? GetFileVersion(string path) => FileVersionInfo.GetVersionInfo(path).FileVersion;

    public IUpdateEvent? CreateOrOpenEvent(string name, bool initialState)
    {
        try
        {
            var h = new EventWaitHandle(initialState, EventResetMode.AutoReset, name);
            return new WinEvent(h);
        }
        catch { return null; }
    }

    public IUpdateProcess LaunchRetained(string exePath, IReadOnlyList<string> args, string? workingDir)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = workingDir ?? Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        var p = Process.Start(psi) ?? throw new IOException("Could not start " + exePath);
        var handle = new WinProcess(p);
        return handle;
    }

    public IUpdateProcess LaunchRetainedOwned(string exePath, IReadOnlyList<string> args, string? workingDir)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = workingDir ?? Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        var p = Process.Start(psi) ?? throw new IOException("Could not start " + exePath);
        var handle = new WinProcess(p, owned: true);
        return handle;
    }

    public bool WaitForExit(IUpdateProcess process, TimeSpan timeout)
    {
        if (process is not WinProcess w) return false;
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (w.HasExited) return true;
            Thread.Sleep(100);
        }
        return w.HasExited;
    }

    public void KillOwned(IUpdateProcess process)
    {
        if (process is WinProcess w && w.Owned)
        {
            try { if (!w.HasExited) w.Kill(); } catch { /* tolerated */ }
        }
    }

    private sealed class WinProcess : IUpdateProcess
    {
        private readonly Process _p;
        private readonly bool _owned;
        public WinProcess(Process p, bool owned = false) { _p = p; _owned = owned; }
        public int ProcessId => _p.Id;
        public bool HasExited
        {
            get => _p.HasExited;
        }
        public int ExitCode
        {
            get
            {
                try { return _p.ExitCode; } catch { return -1; }
            }
        }
        public string? ImagePath { get { try { return _p.MainModule?.FileName; } catch { return null; } } }
        public bool Owned => _owned;
        public void Kill() { _p.Kill(); _p.WaitForExit(5000); }
        public void Dispose() => _p.Dispose();
    }

    private sealed class WinEvent : IUpdateEvent
    {
        private readonly EventWaitHandle _h;
        public WinEvent(EventWaitHandle h) => _h = h;
        public void Set() => _h.Set();
        public bool WaitOne(TimeSpan timeout) { try { return _h.WaitOne(timeout); } catch { return false; } }
        public void Dispose() { try { _h.Close(); } catch { } }
    }
}