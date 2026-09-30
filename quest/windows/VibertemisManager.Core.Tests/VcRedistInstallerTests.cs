// VcRedistInstaller tests.
//
// Verifies the bundled-package pipeline:
//   * Mandatory manifest SHA-256 + size; nulls are never accepted.
//   * Leaf Subject must contain O="Microsoft Corporation" exactly
//     (we do NOT trust Issuer substring match - that lets valid
//     foreign signers through if their chain mentions Microsoft).
//   * FileVersion must be >= the toolchain minimum.
//   * Launch only happens when the registry does NOT already
//     report a satisfactory runtime.
//   * In-progress installs are observed; we never launch a
//     duplicate. A still-running installer reports StillRunning.
//   * 3010 / 1641 are reported as RestartRequired, never as
//     Success.
//   * UAC denial (Win32Exception 1223) is reported as Denied.
//   * A still-running installer at the wait deadline is NOT
//     killed - subsequent calls re-detect and either report
//     Success or still-running.
using System;
using System.ComponentModel;
using System.IO;
using VibertemisManager.Core.Prerequisites;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class VcRedistInstallerTests : IDisposable
{
    private readonly string _tempRoot;
    private readonly string _packagePath;
    private readonly string _packageSha;
    private readonly long _packageSize;

    public VcRedistInstallerTests()
    {
        _tempRoot = Path.Combine(Path.GetTempPath(), "vibt-vcred-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempRoot);
        _packagePath = Path.Combine(_tempRoot, "vc_redist.x64.exe");
        File.WriteAllBytes(_packagePath, new byte[] { 0xCA, 0xFE, 0xBA, 0xBE });
        _packageSha = Sha256OfFile(_packagePath);
        _packageSize = new FileInfo(_packagePath).Length;
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempRoot, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public void EnsureInstalled_RequiresNonEmptyExpectedSha()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => new FakeVcRedistInspector(),
            () => true);
        var result = installer.EnsureInstalled(_packagePath, "", _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RequiresManifestSize()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => new FakeVcRedistInspector(),
            () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, 0,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RejectsSizeMismatch()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize + 1,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RejectsHashMismatch()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var wrongHash = new string('0', 64);
        var result = installer.EnsureInstalled(_packagePath, wrongHash, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RejectsWrongPublisher()
    {
        // Publisher string != Microsoft Corporation; even if
        // Subject mentions "Microsoft" via Issuer substring the
        // leaf-O check rejects.
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0),
            publisher: "Microsoft PCA",
            subject: "CN=Microsoft PCA, O=Microsoft Code Signing PCA, L=Redmond, S=Washington, C=US");
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RejectsForeignValidSigner_WithMicrosoftInIssuerChain()
    {
        // Valid foreign signer whose Issuer happens to mention
        // Microsoft (via cross-sign). Leaf O is "Contoso Corp"
        // and must be rejected even though the issuer chain
        // passes WinVerifyTrust and Issuer contains "Microsoft".
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0),
            publisher: "Microsoft Cross-Code PCA",
            subject: "CN=Contoso Signer, O=Contoso Corporation, L=Paris, C=FR");
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Contains("Contoso Corporation", result.Detail);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_AcceptsMicrosoftPublisher_ExactMatch()
    {
        var detector = new SequenceVcDetector(
            VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing),
            VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0),
            publisher: "Microsoft Corporation",
            subject: "CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US");
        var launcher = new FakeVcRedistLauncher { NextExitCode = 0 };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.Success, result.Exit);
        Assert.Equal(1, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RejectsOldFileVersion()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 40, 0, 0));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_SkipsLaunch_WhenRuntimeAlreadySatisfies()
    {
        var installed = new VcVersion(14, 44, 35207, 0);
        var detector = new StaticVcDetector(VcRuntimeStatus.Present(installed, "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.AlreadyInstalled, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_RunsAndReportsSuccess_WhenInstallerExitsZero_AndRecheckSatisfies()
    {
        var detector = new SequenceVcDetector(
            VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing),
            VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher { NextExitCode = 0 };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.Success, result.Exit);
        Assert.Equal(0, result.RawExitCode);
        Assert.Equal(1, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_ReportsRestartRequired_NotSuccess_For3010Exit()
    {
        var detector = new SequenceVcDetector(
            VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing),
            VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher { NextExitCode = 3010 };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.RestartRequired, result.Exit);
        Assert.Equal(3010, result.RawExitCode);
    }

    [Fact]
    public void EnsureInstalled_ReportsRestartRequired_NotSuccess_For1641Exit()
    {
        var detector = new SequenceVcDetector(
            VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing),
            VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher { NextExitCode = 1641 };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.RestartRequired, result.Exit);
    }

    [Fact]
    public void EnsureInstalled_MapsUacCensored1223_ToDenied()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new ThrowingLauncher();
        launcher.Throw = new Win32Exception(1223, "UAC cancelled");
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.Denied, result.Exit);
        Assert.Equal(1223, result.RawExitCode);
    }

    [Fact]
    public void EnsureInstalled_StillRunningInstall_ObservedOnNextCall_NoSecondLaunch()
    {
        var detector = new SequenceVcDetector(
            VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing),
            VcRuntimeStatus.NotInstalled("still missing", VcReadSource.Missing),
            VcRuntimeStatus.Present(new VcVersion(14, 44, 35207, 0), "ok", VcReadSource.VersionFields));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher { NextStillRunning = true };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);

        // First call: Begin returns null (still running), wrapper
        // returns StillRunning.
        var first = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.StillRunning, first.Exit);
        Assert.Equal(1, launcher.CallCount);
        Assert.True(installer.IsInstallInFlight);

        // Second call while still in-flight: launcher reports
        // still-running, wrapper returns StillRunning. NO second
        // launch.
        var second = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.StillRunning, second.Exit);
        Assert.Equal(1, launcher.CallCount);

        // Mark the install as completed with exit code 0. The
        // fake launcher will return 0 on the next TryGetResult.
        launcher.NextStillRunning = false;
        launcher.NextExitCode = 0;

        // Third call: launcher observes the completed process and
        // returns ExitCode 0; wrapper classifies as Success.
        var third = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.Success, third.Exit);
        Assert.Equal(1, launcher.CallCount);
    }

    [Fact]
    public void EnsureInstalled_FailsClosed_WhenInstallerExits0ButRuntimeStillMissing()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher { NextExitCode = 0 };
        var installer = new VcRedistInstaller(launcher, detector, _ => inspector, () => true);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Contains("runtime is not present", result.Detail);
    }

    [Fact]
    public void EnsureInstalled_RejectsWhenPlatformIsNotWindows()
    {
        var detector = new StaticVcDetector(VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing));
        var inspector = new FakeVcRedistInspector();
        inspector.Packages[_packagePath] = MakePackage(_packagePath, _packageSize, _packageSha,
            new VcVersion(14, 44, 35207, 0));
        var launcher = new FakeVcRedistLauncher();
        var installer = new VcRedistInstaller(launcher, detector, _ => null, () => false);
        var result = installer.EnsureInstalled(_packagePath, _packageSha, _packageSize,
            new VcVersion(14, 44, 35207, 0), VcRuntimeRequirements.MinimumX64Runtime);
        Assert.Equal(VcRedistExit.PackageCorrupt, result.Exit);
        Assert.Equal(0, launcher.CallCount);
    }

    private static VcRedistPackage MakePackage(string path, long size, string sha, VcVersion fileVersion,
        string publisher = "Microsoft Corporation",
        string subject = "CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US")
        => new(path, size, sha, publisher, subject, fileVersion);

    private static string Sha256OfFile(string path)
    {
        using var fs = File.OpenRead(path);
        using var sha = System.Security.Cryptography.SHA256.Create();
        var hash = sha.ComputeHash(fs);
        var sb = new System.Text.StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    private sealed class StaticVcDetector : IVcRuntimeDetector
    {
        private readonly VcRuntimeStatus _status;
        public StaticVcDetector(VcRuntimeStatus status) => _status = status;
        public VcRuntimeStatus Detect() => _status;
    }

    private sealed class SequenceVcDetector : IVcRuntimeDetector
    {
        private readonly VcRuntimeStatus[] _responses;
        private int _idx;
        public SequenceVcDetector(params VcRuntimeStatus[] responses) { _responses = responses; _idx = -1; }
        public VcRuntimeStatus Detect()
        {
            if (_responses.Length == 0) return VcRuntimeStatus.NotInstalled("missing", VcReadSource.Missing);
            var i = System.Math.Min(System.Threading.Interlocked.Increment(ref _idx), _responses.Length - 1);
            return _responses[i];
        }
    }

    private sealed class ThrowingLauncher : IVcRedistLauncher
    {
        public Exception? Throw;
        public int CallCount { get; private set; }
        public bool IsInFlight => false;
        public int? Begin(VcRedistPackage package)
        {
            CallCount++;
            if (Throw is null) return 0;
            throw Throw;
        }
        public bool TryGetResult(out int exitCode, out bool stillRunning)
        { exitCode = -1; stillRunning = false; return false; }
    }
}