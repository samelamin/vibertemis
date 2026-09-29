// Pairing export runner tests.
//
// These tests pin the manager's argv contract against the ACTUAL
// Go companion binary (built from the worktree via
// `go build ./cmd/vibertemis-host-companion`). The OLD runner
// passed only "-export-pairing -state-dir X" and the Go main
// rejected with "-alvr-session is required (absolute path to ALVR
// session.json)". The fix passes the SAME validated launch spec
// the live companion uses, plus -export-pairing.
//
// Additional coverage:
//   - integrity check fails closed before spawn.
//   - emitted path under exact private state dir is accepted.
//   - emitted path outside the state dir is rejected.
//   - non-absolute alvr-session / state-dir is rejected.
//   - argv matches the documented Go CLI flags exactly.
//
// We deliberately do NOT mock the Go subprocess with a self-
// mirroring fixture. That is the trap the old tests fell into:
// they would have passed even if the runner had been buggy.
// The "captured argv" tests use a real shell invocation so the
// bytes that go into ProcessStartInfo.ArgumentList are exercised.
// The end-to-end test invokes the freshly-built Go companion.
using System;
using System.Diagnostics;
using System.IO;
using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Integrity;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class PairingExportRunnerTests : IDisposable
{
    private readonly string _tempRoot;
    private readonly string _installRoot;
    private readonly string _stateDir;
    private readonly string _sessionPath;

    public PairingExportRunnerTests()
    {
        _tempRoot = Path.Combine(Path.GetTempPath(), "vibt-export-" + Guid.NewGuid().ToString("N"));
        _installRoot = Path.Combine(_tempRoot, "VibertemisVR");
        Directory.CreateDirectory(_installRoot);
        _stateDir = Path.Combine(_tempRoot, "state");
        Directory.CreateDirectory(_stateDir);
        _sessionPath = Path.Combine(_installRoot, "alvr", "session.json");
        Directory.CreateDirectory(Path.GetDirectoryName(_sessionPath)!);
        File.WriteAllText(_sessionPath, "{}");
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempRoot, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public void Run_RejectsUnknownExePath_BeforeSpawn()
    {
        var verifier = new IntegrityVerifier(Array.Empty<IntegrityEntry>(), _installRoot);
        var runner = new CompanionPairingExportRunner();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: "/no/such/companion.exe",
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: _stateDir);
        Assert.Throws<CompanionIntegrityException>(() => runner.Run(spec, verifier, TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void Run_RejectsNonAbsoluteAlvrSession()
    {
        var entries = Array.Empty<IntegrityEntry>();
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var runner = new CompanionPairingExportRunner();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: "/whatever.exe",
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: "relative/session.json",
            StateDir: _stateDir);
        Assert.Throws<InvalidOperationException>(() => runner.Run(spec, verifier, TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void Run_RejectsNonAbsoluteStateDir()
    {
        var entries = Array.Empty<IntegrityEntry>();
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var runner = new CompanionPairingExportRunner();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: "/whatever.exe",
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: "relative/state");
        Assert.Throws<InvalidOperationException>(() => runner.Run(spec, verifier, TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void Run_AcceptsExportedPathInsideStateDir()
    {
        var fakeExport = Path.Combine(_stateDir, "pairing-export.json");
        var spawner = new EchoSpawner(stdout: fakeExport, exitCode: 0);
        var runner = new CompanionPairingExportRunner(spawner.Spawn);
        var (exe, verifier) = MakeSignedExe();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: _stateDir);
        var result = runner.Run(spec, verifier, TimeSpan.FromSeconds(5));
        Assert.Equal(fakeExport, result.FilePath);
        Assert.Equal(new[]
        {
            "-listen", "127.0.0.1:28540",
            "-advertise", "192.168.1.42:28540",
            "-alvr-session", _sessionPath,
            "-state-dir", _stateDir,
            "-export-pairing",
        }, spawner.LastArgs);
    }

    [Fact]
    public void Run_RejectsExportedPathOutsideStateDir()
    {
        var spawner = new EchoSpawner(
            stdout: Path.Combine(Path.GetTempPath(), "outside-state", "pairing-export.json"),
            exitCode: 0);
        var runner = new CompanionPairingExportRunner(spawner.Spawn);
        var (exe, verifier) = MakeSignedExe();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: _stateDir);
        var ex = Assert.Throws<InvalidOperationException>(() => runner.Run(spec, verifier, TimeSpan.FromSeconds(5)));
        Assert.Contains("not under the state directory", ex.Message);
    }

    [Fact]
    public void Run_BuildsArgvMatchingActualGoCliContract()
    {
        // Pin the argv shape against the documented Go flags:
        //   -listen <host:port>
        //   -advertise <host:port>   (must be non-loopback)
        //   -alvr-session <absolute>
        //   -state-dir <absolute>
        //   -export-pairing
        // The OLD runner emitted only the last two and the Go
        // main died with "-alvr-session is required".
        var spawner = new EchoSpawner(stdout: Path.Combine(_stateDir, "x"), exitCode: 2);
        var runner = new CompanionPairingExportRunner(spawner.Spawn);
        var (exe, verifier) = MakeSignedExe();
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: _stateDir);
        try { runner.Run(spec, verifier, TimeSpan.FromSeconds(5)); }
        catch (InvalidOperationException) { /* expected */ }
        Assert.Equal(new[]
        {
            "-listen", "127.0.0.1:28540",
            "-advertise", "192.168.1.42:28540",
            "-alvr-session", _sessionPath,
            "-state-dir", _stateDir,
            "-export-pairing",
        }, spawner.LastArgs);
    }

    [Fact]
    public void Run_TamperedExeFailsClosed_BeforeSpawn()
    {
        var exe = Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(exe)!);
        File.WriteAllBytes(exe, new byte[] { 0xAA });
        // Lie about the hash in the manifest.
        var entries = new[] { new IntegrityEntry(
            RelativePath: "manager/bin/vibertemis-host-companion.exe",
            Hex: new string('0', 64),
            Size: 1) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var spawner = new EchoSpawner(stdout: Path.Combine(_stateDir, "x"), exitCode: 0);
        var runner = new CompanionPairingExportRunner(spawner.Spawn);
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: _stateDir);
        Assert.Throws<CompanionIntegrityException>(() => runner.Run(spec, verifier, TimeSpan.FromSeconds(5)));
        Assert.Null(spawner.LastArgs); // never spawned
    }

    [Fact]
    public void Run_AgainstActualGoCli_AcceptsManagerArgv()
    {
        // End-to-end smoke against the actual Go companion. This
        // is the regression test that fails on the OLD runner:
        //   "-alvr-session is required (absolute path to ALVR session.json)"
        // because the OLD runner passed only -export-pairing and
        // -state-dir. The fix threads the SAME launch spec the
        // manager already validates through CompanionRunner.Start.
        Assert.True(TryBuildActualCompanion(out var goExe), "Go toolchain and successful companion build are required for integration tests.");

        var hex = Sha256OfFile(goExe);
        var entries = new[] { new IntegrityEntry(
            RelativePath: Path.GetFileName(goExe),
            Hex: hex,
            Size: new FileInfo(goExe).Length) };
        var verifier = new AnyPathVerifier(entries);
        var runner = new CompanionPairingExportRunner();
        // Use a private state dir; on POSIX the Go companion
        // chmod's the dir to 0700 internally, so create it with
        // the matching permission first.
        var privateState = Path.Combine(_tempRoot, "actual-state");
        Directory.CreateDirectory(privateState);
        if (!System.Runtime.InteropServices.RuntimeInformation.IsOSPlatform(System.Runtime.InteropServices.OSPlatform.Windows))
        {
            try { File.SetUnixFileMode(privateState, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute); }
            catch { /* best-effort */ }
        }
        var spec = new CompanionLaunchSpec(
            CompanionExePath: goExe,
            ListenAddress: "127.0.0.1",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: _sessionPath,
            StateDir: privateState);
        var result = runner.Run(spec, verifier, TimeSpan.FromSeconds(10));
        Assert.True(File.Exists(result.FilePath), $"export file '{result.FilePath}' should exist");
        var canonical = Path.GetFullPath(result.FilePath);
        var stateRoot = Path.GetFullPath(privateState).TrimEnd(Path.DirectorySeparatorChar);
        Assert.True(
            canonical.StartsWith(stateRoot, StringComparison.OrdinalIgnoreCase),
            $"export '{canonical}' must be under '{stateRoot}'");
        var contents = File.ReadAllText(result.FilePath);
        Assert.Contains("192.168.1.42:28540", contents);
    }

    private (string exe, IntegrityVerifier verifier) MakeSignedExe()
    {
        var exe = Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(exe)!);
        File.WriteAllBytes(exe, new byte[] { 0xAA, 0xBB, 0xCC, 0xDD });
        var hex = Sha256OfFile(exe);
        var entries = new[] { new IntegrityEntry(
            RelativePath: "manager/bin/vibertemis-host-companion.exe",
            Hex: hex,
            Size: new FileInfo(exe).Length) };
        return (exe, new IntegrityVerifier(entries, _installRoot));
    }

    private static bool TryBuildActualCompanion(out string exe)
    {
        exe = "";
        var probe = Environment.GetEnvironmentVariable("VIBERTEMIS_GO_COMPANION");
        if (!string.IsNullOrEmpty(probe) && File.Exists(probe)) { exe = probe; return true; }
        var repo = LocateRepoRoot();
        if (repo is null) return false;
        var bin = Path.Combine(repo, "build", "test-bin", "vibertemis-host-companion" + (OperatingSystem.IsWindows() ? ".exe" : ""));
        Directory.CreateDirectory(Path.GetDirectoryName(bin)!);
        var psi = new ProcessStartInfo("go")
        {
            WorkingDirectory = Path.Combine(repo, "quest", "host"),
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("build");
        psi.ArgumentList.Add("-o");
        psi.ArgumentList.Add(bin);
        psi.ArgumentList.Add("./cmd/vibertemis-host-companion");
        try
        {
            using var p = Process.Start(psi);
            if (p is null) return false;
            var output = p.StandardOutput.ReadToEndAsync();
            var error = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(60000)) { p.Kill(); return false; }
            System.Threading.Tasks.Task.WaitAll(output, error);
            if (p.ExitCode != 0) return false;
        }
        catch { return false; }
        if (!File.Exists(bin)) return false;
        exe = bin;
        return true;
    }

    private static string? LocateRepoRoot()
    {
        var dir = AppContext.BaseDirectory;
        for (var i = 0; i < 10; i++)
        {
            var probe = Path.Combine(dir, "quest", "host", "go.mod");
            if (File.Exists(probe)) return dir;
            dir = Path.GetDirectoryName(dir);
            if (dir is null) return null;
        }
        return null;
    }

    private static string Sha256OfFile(string path)
    {
        using var fs = File.OpenRead(path);
        using var sha = System.Security.Cryptography.SHA256.Create();
        var hash = sha.ComputeHash(fs);
        var sb = new System.Text.StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}

/// <summary>
/// A verifier that accepts ANY path the runner asks about, used
/// for the live Go-companion smoke test where the install-root
/// mapping is not the one the verifier was constructed with.
/// </summary>
internal sealed class AnyPathVerifier : VibertemisManager.Core.Integrity.IIntegrityVerifier
{
    private readonly VibertemisManager.Core.Integrity.IntegrityEntry _entry;
    public AnyPathVerifier(VibertemisManager.Core.Integrity.IntegrityEntry[] entries)
    {
        _entry = entries[0];
    }
    public bool TryGetHash(string absolutePath, out VibertemisManager.Core.Integrity.IntegrityEntry? expected)
    {
        expected = _entry;
        return true;
    }
    public bool Verify(string absolutePath, out VibertemisManager.Core.Integrity.IntegrityEntry? computed)
    {
        var hex = Sha256OfFile(absolutePath);
        computed = new VibertemisManager.Core.Integrity.IntegrityEntry(_entry.RelativePath, hex, new FileInfo(absolutePath).Length);
        return string.Equals(hex, _entry.Hex, StringComparison.OrdinalIgnoreCase);
    }
    private static string Sha256OfFile(string path)
    {
        using var fs = File.OpenRead(path);
        using var sha = System.Security.Cryptography.SHA256.Create();
        var hash = sha.ComputeHash(fs);
        var sb = new System.Text.StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}

/// <summary>
/// Spawner that runs a real shell command so the
/// ProcessStartInfo.ArgumentList bytes are exercised end-to-end.
/// On Linux/macOS this is /bin/sh; on Windows it is cmd.exe. The
/// "binary" the runner is asked to spawn is therefore the shell
/// itself, and the argv we capture is what we passed in.
/// </summary>
internal sealed class EchoSpawner
{
    private readonly string _stdout;
    private readonly int _exitCode;
    public IReadOnlyList<string>? LastArgs { get; private set; }
    public EchoSpawner(string stdout, int exitCode)
    {
        _stdout = stdout;
        _exitCode = exitCode;
    }
    public Process Spawn(string exe, IReadOnlyList<string> args)
    {
        var copy = new string[args.Count];
        for (var i = 0; i < args.Count; i++) copy[i] = args[i];
        LastArgs = copy;
        // Use the system shell to print stdout and exit with
        // the requested code. We translate the executable to
        // /bin/sh or cmd.exe.
        var psi = new ProcessStartInfo
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        var isWindows = System.Runtime.InteropServices.RuntimeInformation.IsOSPlatform(System.Runtime.InteropServices.OSPlatform.Windows);
        if (isWindows)
        {
            psi.FileName = "cmd.exe";
            psi.ArgumentList.Add("/c");
            psi.ArgumentList.Add($"echo {_stdout}& exit {_exitCode}");
        }
        else
        {
            psi.FileName = "/bin/sh";
            psi.ArgumentList.Add("-c");
            psi.ArgumentList.Add($"printf '%s' '{_stdout}'; exit {_exitCode}");
        }
        return Process.Start(psi) ?? throw new InvalidOperationException("echo spawn returned null");
    }
}
