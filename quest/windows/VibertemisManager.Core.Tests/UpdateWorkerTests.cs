// UpdateWorker tests with a fake environment.
//
// The worker is the trust anchor for the install. Tests must cover:
//   - malformed job -> zero launches
//   - parent PID mismatch -> zero launches
//   - installer timeout -> no relaunch
//   - actual ExitCode retained from the owned handle
//   - outcome written BEFORE any GUI relaunch
//   - no commit -> no install
//   - successful install only when both FileVersion AND --verify-install pass
//   - failure recovery requires --verify-install on the original
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Update;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class UpdateWorkerTests : IDisposable
{
    private readonly RSA _signing = RSA.Create(3072);
    private readonly string _tempRoot;
    private readonly string _cacheRoot;
    private readonly string _cacheDir;
    private readonly string _programsRoot;
    private readonly string _helperPath;
    private readonly string _originalManagerPath;
    private readonly byte[] _originalManagerBytes;
    private readonly byte[] _originalManagerHash;
    private readonly FakeEnv _env;
    private readonly UpdateWorker _worker;

    public UpdateWorkerTests()
    {
        _tempRoot = Path.Combine(Path.GetTempPath(), "vibt-update-" + Guid.NewGuid().ToString("N"));
        _cacheRoot = Path.Combine(_tempRoot, "cache");
        _cacheDir = Path.Combine(_cacheRoot, Guid.NewGuid().ToString("N"));
        _programsRoot = Path.Combine(_tempRoot, "VibertemisVR");
        Directory.CreateDirectory(_cacheDir);
        Directory.CreateDirectory(Path.Combine(_programsRoot, "manager"));
        Directory.CreateDirectory(Path.Combine(_programsRoot, "manager", "bin"));
        _originalManagerBytes = new byte[] { 0xCD, 0xEF };
        _originalManagerPath = Path.Combine(_programsRoot, "manager", "VibertemisManager.App.exe");
        File.WriteAllBytes(_originalManagerPath, _originalManagerBytes);
        _originalManagerHash = SHA256.HashData(_originalManagerBytes);
        _helperPath = Path.Combine(_cacheDir, UpdateWorker.HelperExeName);
        File.WriteAllBytes(_helperPath, _originalManagerBytes);
        _env = new FakeEnv
        {
            CurrentExecutablePath = _helperPath,
            CurrentExecutableSha256Value = _originalManagerHash,
            FullPathImpl = p => Path.GetFullPath(p),
        };
        _worker = new UpdateWorker(_env, _signing.ExportSubjectPublicKeyInfoPem(), _cacheRoot);
    }

    public void Dispose()
    {
        _signing.Dispose();
        try { Directory.Delete(_tempRoot, recursive: true); } catch { /* ignore */ }
    }

    private (byte[] Manifest, byte[] Signature, byte[] Installer) MakePackageFor(string version, long sequence)
    {
        var installer = new byte[] { 0x10, 0x20, 0x30, 0x40 };
        var sha = Convert.ToHexString(SHA256.HashData(installer)).ToLowerInvariant();
        var obj = new
        {
            schema = 1,
            channel = "quest-preview",
            sequence,
            version,
            native_protocol = SignedRelease.Protocol,
            assets = new
            {
                windows = new
                {
                    filename = $"VibertemisVR-HostManager-Setup-{version}.exe",
                    url = SignedRelease.Repository + SignedRelease.TagPrefix + version +
                        $"/VibertemisVR-HostManager-Setup-{version}.exe",
                    bytes = (long)installer.Length,
                    sha256 = sha,
                }
            }
        };
        var manifest = JsonSerializer.SerializeToUtf8Bytes(obj, new JsonSerializerOptions { WriteIndented = true });
        return (manifest, _signing.SignData(manifest, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1), installer);
    }

    private string WriteJob(
        byte[] manifest, byte[] signature, byte[] installer,
        string version, long sequence,
        Action<JobOverrides>? overrideJob = null)
    {
        var overrides = new JobOverrides();
        overrideJob?.Invoke(overrides);
        var sha = Convert.ToHexString(SHA256.HashData(installer)).ToLowerInvariant();
        var doc = new Dictionary<string, object?>
        {
            ["schema"] = 1,
            ["expectedVersion"] = overrides.ExpectedVersion ?? version,
            ["expectedSequence"] = overrides.ExpectedSequence ?? sequence,
            ["originalManagerPath"] = overrides.OriginalManagerPath ?? _originalManagerPath,
            ["programsRoot"] = overrides.ProgramsRoot ?? _programsRoot,
            ["parentPid"] = overrides.ParentPid ?? 99999,
            ["cacheDir"] = overrides.CacheDir ?? _cacheDir,
            ["installerFilename"] = overrides.InstallerFilename ?? $"VibertemisVR-HostManager-Setup-{version}.exe",
            ["installerSha256"] = sha,
            ["installerBytes"] = installer.LongLength,
            ["manifestBase64"] = Convert.ToBase64String(manifest),
            ["signatureBase64"] = Convert.ToBase64String(signature),
            ["readyEventName"] = overrides.ReadyEventName ?? "Local\\TestReady",
            ["commitEventName"] = overrides.CommitEventName ?? "Local\\TestCommit",
            ["originalManagerHash"] = Convert.ToBase64String(overrides.OriginalManagerHash ?? _originalManagerHash),
        };
        var path = Path.Combine(_cacheDir, "job.json");
        File.WriteAllText(path, JsonSerializer.Serialize(doc));
        File.WriteAllBytes(Path.Combine(_cacheDir, $"VibertemisVR-HostManager-Setup-{version}.exe"), installer);
        return path;
    }

    private UpdateJobInputs DefaultInputs(string jobPath) =>
        new UpdateJobInputs(
            JobPath: jobPath,
            ExpectedCacheRoot: _cacheRoot,
            HelperPath: _helperPath,
            WorkerHash: _originalManagerHash,
            ParentStartTimeUtcMs: null);

    [Fact]
    public void Worker_OnMalformedJob_LaunchesNothing()
    {
        var inputs = new UpdateJobInputs(
            JobPath: Path.Combine(_cacheDir, "missing.json"),
            ExpectedCacheRoot: _cacheRoot,
            HelperPath: _helperPath,
            WorkerHash: _originalManagerHash,
            ParentStartTimeUtcMs: null);
        var result = _worker.Run(inputs, Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
        Assert.Null(result.RestartedPid);
    }

    [Fact]
    public void Worker_OnBadSignature_LaunchesNothing()
    {
        var (manifest, _, installer) = MakePackageFor("0.1.0.8", 8);
        var bogus = new byte[384];
        var jobPath = WriteJob(manifest, bogus, installer, "0.1.0.8", 8);
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnNotNewerVersion_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.4", 4);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.4", 4);
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnHelperOutsideCache_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        // Move the helper to the programs root: that's NOT under cache.
        var rogueHelper = Path.Combine(_programsRoot, "manager", "rogue.exe");
        File.WriteAllBytes(rogueHelper, _originalManagerBytes);
        var inputs = new UpdateJobInputs(
            JobPath: jobPath,
            ExpectedCacheRoot: _cacheRoot,
            HelperPath: rogueHelper,
            WorkerHash: _originalManagerHash,
            ParentStartTimeUtcMs: null);
        var result = _worker.Run(inputs, Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnCacheOutsideExpectedRoot_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 7,
            o => o.CacheDir = Path.Combine(_tempRoot, "rogue-cache"));
        Directory.CreateDirectory(Path.Combine(_tempRoot, "rogue-cache"));
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnWrongManagerBasename_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var wrong = Path.Combine(_programsRoot, "manager", "Other.exe");
        File.WriteAllBytes(wrong, _originalManagerBytes);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 7,
            o => o.OriginalManagerPath = wrong);
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnNoCommitSignal_DoesNotInstall()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = false;
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.WorkerError, result.Outcome.Kind);
        Assert.DoesNotContain(_env.Launches, x => x.Exe.Contains("VibertemisVR-HostManager-Setup"));
        Assert.False(result.InstallerLaunched);
    }

    [Fact]
    public void Worker_OnParentPidMismatch_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = "C:\\Windows\\System32\\notepad.exe";
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.WorkerError, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnHelperHashMismatch_LaunchesNothing()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        File.WriteAllBytes(_helperPath, new byte[] { 0xAA, 0xBB, 0xCC });
        var inputs = new UpdateJobInputs(
            JobPath: jobPath,
            ExpectedCacheRoot: _cacheRoot,
            HelperPath: _helperPath,
            WorkerHash: _originalManagerHash,
            ParentStartTimeUtcMs: null);
        var result = _worker.Run(inputs, Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.JobInvalid, result.Outcome.Kind);
        Assert.Empty(_env.Launches);
    }

    [Fact]
    public void Worker_OnInstallerTimeout_DoesNotRelaunch()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerHangs = true;
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.InstallerFailed, result.Outcome.Kind);
        Assert.True(result.InstallerLaunched);
        Assert.Null(result.RestartedPid);
        Assert.False(result.OriginalRelaunched);
    }

    [Fact]
    public void Worker_RetainsActualInstallerExitCode()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 7;
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.InstallerFailed, result.Outcome.Kind);
        Assert.Equal(7, result.Outcome.InstallerExitCode);
        Assert.Equal(7, result.InstallerExitCodeActual);
    }

    [Fact]
    public void Worker_OnInstalledVersionMismatch_DoesNotRelaunchInstalled()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 0;
        _env.VerifyInstallPidToReturn = 6666;
        _env.VerifyInstallExitToReturn = 0;
        _env.InstalledManagerFileVersion = "0.1.0.7";
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.VerificationFailed, result.Outcome.Kind);
        Assert.True(result.OriginalRelaunched);
    }

    [Fact]
    public void Worker_OnVerifyInstallFailure_DoesNotRelaunchInstalled()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 0;
        _env.VerifyInstallPidToReturn = 6666;
        _env.VerifyInstallExitToReturn = 7;
        _env.InstalledManagerFileVersion = "0.1.0.8";
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.VerificationFailed, result.Outcome.Kind);
        Assert.Equal(7, result.Outcome.VerifyInstallExitCode);
        Assert.Null(result.RestartedPid);
    }

    [Fact]
    public void Worker_OnInstallerFailure_RelaunchesOriginalAfterVerify()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 1;
        _env.OriginalVerifyInstallExit = 0;
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.InstallerFailed, result.Outcome.Kind);
        Assert.True(result.OriginalRelaunched);
        Assert.NotNull(result.RestartedPid);
    }

    [Fact]
    public void Worker_OnInstallerFailure_DoesNotRelaunchOriginalWhenVerifyFails()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 1;
        _env.OriginalVerifyInstallExit = 2;
        var result = _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        Assert.Equal(UpdateOutcomeKind.InstallerFailed, result.Outcome.Kind);
        Assert.False(result.OriginalRelaunched);
        Assert.Null(result.RestartedPid);
    }

    [Fact]
    public void Worker_OnSuccess_RetainsActualExitCodeAndRelaunchesInstalled()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 0;
        _env.VerifyInstallPidToReturn = 6666;
        _env.VerifyInstallExitToReturn = 0;
        _env.InstalledManagerFileVersion = "0.1.0.8";
        var outcomePath = Path.Combine(_cacheDir, "out.json");
        var result = _worker.Run(DefaultInputs(jobPath), outcomePath);
        Assert.Equal(UpdateOutcomeKind.Success, result.Outcome.Kind);
        Assert.Equal(0, result.Outcome.InstallerExitCode);
        Assert.Equal(0, result.Outcome.VerifyInstallExitCode);
        Assert.Equal(0, result.InstallerExitCodeActual);
        Assert.Equal("0.1.0.8", result.Outcome.InstalledFileVersion);
        Assert.NotNull(result.RestartedPid);
        // Outcome must be written BEFORE the GUI relaunch.
        Assert.True(File.Exists(outcomePath));
    }

    [Fact]
    public void Worker_OnSuccess_InstallerArgsIncludeSilentAndDir()
    {
        var (manifest, sig, installer) = MakePackageFor("0.1.0.8", 8);
        var jobPath = WriteJob(manifest, sig, installer, "0.1.0.8", 8);
        _env.ParentImagePath = _originalManagerPath;
        _env.ReadySignaled = true;
        _env.CommitSignaled = true;
        _env.ParentAlreadyExited = true;
        _env.InstallerPidToReturn = 5555;
        _env.InstallerExitToReturn = 0;
        _env.VerifyInstallPidToReturn = 6666;
        _env.VerifyInstallExitToReturn = 0;
        _env.InstalledManagerFileVersion = "0.1.0.8";
        _worker.Run(DefaultInputs(jobPath), Path.Combine(_cacheDir, "out.json"));
        var installerLaunch = _env.Launches.FirstOrDefault(x => x.Exe.Contains("VibertemisVR-HostManager-Setup"));
        Assert.NotNull(installerLaunch);
        Assert.Contains(installerLaunch!.Args, a => a == "/SILENT");
        Assert.Contains(installerLaunch.Args, a => a == "/SUPPRESSMSGBOXES");
        Assert.Contains(installerLaunch.Args, a => a == "/NORESTART");
        Assert.Contains(installerLaunch.Args, a => a.StartsWith("/DIR="));
        Assert.Contains(installerLaunch.Args, a => a.StartsWith("/LOG="));
    }

    private sealed class JobOverrides
    {
        public string? ExpectedVersion;
        public long? ExpectedSequence;
        public string? OriginalManagerPath;
        public string? ProgramsRoot;
        public int? ParentPid;
        public string? CacheDir;
        public string? InstallerFilename;
        public string? ReadyEventName;
        public string? CommitEventName;
        public byte[]? OriginalManagerHash;
    }

    private sealed class FakeEnv : IUpdateEnvironment
    {
        public string CurrentExecutablePath { get; set; } = "";
        public byte[] CurrentExecutableSha256Value { get; set; } = Array.Empty<byte>();
        public byte[] CurrentExecutableSha256 => CurrentExecutableSha256Value;
        public Func<string, string>? FullPathImpl { get; set; }
        public readonly Dictionary<string, byte[]> Files = new(StringComparer.OrdinalIgnoreCase);
        public readonly List<Launch> Launches = new();
        public string? ParentImagePath;
        public bool ParentAlreadyExited = true;
        public bool ReadySignaled = true;
        public bool CommitSignaled = true;
        public int InstallerPidToReturn = 5555;
        public bool InstallerHangs;
        public int InstallerExitToReturn = 0;
        public int VerifyInstallPidToReturn = 6666;
        public int VerifyInstallExitToReturn = 0;
        public int OriginalVerifyInstallPidToReturn = 7777;
        public int OriginalVerifyInstallExit = 0;
        public string InstalledManagerFileVersion = "0.1.0.8";

        public bool FileExists(string path) => Files.ContainsKey(path) || File.Exists(path);
        public long FileSize(string path) => Files.TryGetValue(path, out var b) ? b.LongLength : new FileInfo(path).Length;
        public byte[] ReadAllBytes(string path) => Files.TryGetValue(path, out var b) ? b : File.ReadAllBytes(path);
        public string ReadAllText(string path) => Files.TryGetValue(path, out var b) ? Encoding.UTF8.GetString(b) : File.ReadAllText(path);
        public void WriteAllText(string path, string content) { Files[path] = Encoding.UTF8.GetBytes(content); Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllText(path,content); }
        public void CopyFile(string source, string destination, bool overwrite) => Files[destination] = File.ReadAllBytes(source);
        public void DeleteFile(string path) => Files.Remove(path);
        public void EnsureDirectory(string path) { /* no-op */ }
        public string FullPath(string path) => FullPathImpl?.Invoke(path) ?? Path.GetFullPath(path);

        public IUpdateProcess OpenParent(int pid) => new FakeProcess { ProcessId = pid, ImagePath = ParentImagePath, Hang = !ParentAlreadyExited };
        public string? GetFileVersion(string path) {
            Assert.EndsWith(Path.Combine("manager", "VibertemisManager.App.exe"),path);
            return InstalledManagerFileVersion;
        }

        public IUpdateEvent? CreateOrOpenEvent(string name, bool initialState) => new FakeEvent(CommitSignaled);

        public IUpdateProcess LaunchRetained(string exePath, IReadOnlyList<string> args, string? workingDir) =>
            LaunchInternal(exePath, args, owned: false);

        public IUpdateProcess LaunchRetainedOwned(string exePath, IReadOnlyList<string> args, string? workingDir) =>
            LaunchInternal(exePath, args, owned: true);

        public bool WaitForExit(IUpdateProcess process, TimeSpan timeout)
        {
            if (process is FakeProcess p)
            {
                if (p.Hang) return false;
                p.HasExited = true;
                return true;
            }
            return false;
        }

        public void KillOwned(IUpdateProcess process)
        {
            if (process is FakeProcess p) p.HasExited = true;
        }

        private IUpdateProcess LaunchInternal(string exePath, IReadOnlyList<string> args, bool owned)
        {
            Launches.Add(new Launch { Exe = exePath, Args = args.ToArray(), Owned = owned });
            var name = Path.GetFileName(exePath);
            int pid;
            int exit;
            if (name.StartsWith("VibertemisVR-HostManager-Setup", StringComparison.OrdinalIgnoreCase))
            {
                pid = InstallerPidToReturn;
                exit = InstallerExitToReturn;
                return new FakeProcess { ProcessId = pid, Exit = exit, Hang = InstallerHangs, ImagePath = exePath };
            }
            if (args.Contains("--verify-install"))
            {
                pid = InstallerExitToReturn == 0 ? VerifyInstallPidToReturn : OriginalVerifyInstallPidToReturn;
                exit = InstallerExitToReturn == 0 ? VerifyInstallExitToReturn : OriginalVerifyInstallExit;
                return new FakeProcess { ProcessId = pid, Exit = exit, ImagePath = exePath };
            }
            if (name == "VibertemisManager.App.exe")
            {
                return new FakeProcess { ProcessId = 8888, Exit = 0, ImagePath = exePath };
            }
            return new FakeProcess { ProcessId = 9999, Exit = 0, ImagePath = exePath };
        }

        private sealed class FakeEvent(bool committed) : IUpdateEvent
        {
            public void Set() { }
            public bool WaitOne(TimeSpan timeout) => committed;
            public void Dispose() { }
        }
    }

    public sealed class Launch
    {
        public string Exe { get; set; } = "";
        public string[] Args { get; set; } = Array.Empty<string>();
        public bool Owned { get; set; }
    }

    private sealed class FakeProcess : IUpdateProcess
    {
        public int ProcessId { get; set; }
        public bool HasExited { get; set; }
        public int Exit { get; set; }
        public bool Hang { get; set; }
        public int ExitCode => Exit;
        public string? ImagePath { get; set; }
        public void Dispose() {}
    }
}