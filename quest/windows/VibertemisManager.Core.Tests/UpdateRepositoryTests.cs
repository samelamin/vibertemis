// Tests for the new UpdateRepository shared state. Mirrors the
// Android UpdateRepositoryTest shape: throttle, in-flight coalescing,
// the no-newer-available success path, available-vs-downloaded slot
// independence, defensive cloning, and end-to-end disk-cache hydration
// after a process restart.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Update;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class UpdateRepositoryTests : IDisposable
{
    private readonly RSA _signing = RSA.Create(3072);
    private readonly string _trustKey;
    private readonly string _stateRoot;

    public UpdateRepositoryTests()
    {
        _trustKey = _signing.ExportSubjectPublicKeyInfoPem();
        _stateRoot = Path.Combine(Path.GetTempPath(), "vibt-urepo-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_stateRoot);
    }

    public void Dispose()
    {
        _signing.Dispose();
        try { Directory.Delete(_stateRoot, recursive: true); } catch { }
    }

    private sealed class TestClock : UpdateRepository.IClock
    {
        public DateTime Now { get; set; } = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
    }

    private sealed class FixedKeySource : UpdateRepository.ITrustKeySource
    {
        public string Pem { get; }
        public FixedKeySource(string pem) { Pem = pem; }
    }

    private sealed class StubCheckSource : UpdateRepository.ICheckSource
    {
        private readonly Func<CancellationToken, Task<UpdateRepository.ICheckSource.CheckResult?>> _fn;
        public StubCheckSource(Func<CancellationToken, Task<UpdateRepository.ICheckSource.CheckResult?>> fn) { _fn = fn; }
        public Task<UpdateRepository.ICheckSource.CheckResult?> CheckAsync(CancellationToken cancellation)
            => _fn(cancellation);
    }

    private byte[] MakeManifest(string version, long sequence, long versionCode, long installerBytes, string installerSha, out byte[] body, out byte[] sig)
    {
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
                    bytes = installerBytes,
                    sha256 = installerSha,
                }
            }
        };
        body = JsonSerializer.SerializeToUtf8Bytes(obj, new JsonSerializerOptions { WriteIndented = true });
        sig = _signing.SignData(body, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return body;
    }

    private byte[] Sha256(byte[] bytes)
    {
        using var sha = SHA256.Create();
        return sha.ComputeHash(bytes);
    }

    private string Sha256Hex(byte[] bytes) => Convert.ToHexString(Sha256(bytes)).ToLowerInvariant();

    private UpdateRepository NewRepo(UpdateRepository.IClock clock,
                                     UpdateRepository.ICache cache,
                                     UpdateRepository.ICheckSource? source = null)
    {
        var repo = new UpdateRepository(cache, clock, new FixedKeySource(_trustKey));
        if (source != null)
        {
            repo.BindSource(source, TaskScheduler.Default);
        }
        return repo;
    }

    [Fact]
    public void FreshRepositoryRunsByThrottle()
    {
        using var repo = NewRepo(new TestClock(), new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        Assert.True(repo.ShouldRunByThrottle(false));
        Assert.True(repo.ShouldRunByThrottle(true));
    }

    [Fact]
    public void SuccessThrottleBlocksForSixHours()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        var body = MakeManifest("0.1.0.99", 99, 99, 4L, Sha256Hex(new byte[] { 1, 2, 3, 4 }), out _, out _);
        SignedRelease m;
        try { m = SignedRelease.Verify(body, _signing.SignData(body, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1), _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        repo.RecordAvailable(m, body, _signing.SignData(body, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
        Assert.False(repo.ShouldRunByThrottle(false));
        Assert.True(repo.ShouldRunByThrottle(true));
        clock.Now = clock.Now.Add(UpdateRepository.SuccessInterval).AddSeconds(1);
        Assert.True(repo.ShouldRunByThrottle(false));
    }

    [Fact]
    public void FailureThrottleBlocksForFifteenMinutes()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        repo.RecordFailure("network unreachable");
        Assert.False(repo.ShouldRunByThrottle(false));
        clock.Now = clock.Now.Add(UpdateRepository.FailureInterval).AddSeconds(1);
        Assert.True(repo.ShouldRunByThrottle(false));
    }

    /// <summary>
    /// Tie-resolution regression: when the latest outcome is a
    /// failure that follows a prior success, the throttle must
    /// pick the failure branch (15 min) NOT the success branch
    /// (6 h). Otherwise a forced retry would be blocked by the
    /// 6 h window after the latest outcome was a failure.
    /// </summary>
    [Fact]
    public void TieTimestampsUseFailureBranchWhenLastErrorSet()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        var t0 = clock.Now;
        repo.RecordNoNewerAvailable();
        // 1 min later: a failure. LastFailureAt (t0+1m) >=
        // LastSuccessAt (t0), so the failure branch wins the
        // tie resolution.
        clock.Now = t0.AddMinutes(1);
        repo.RecordFailure("network unreachable");
        var snap = repo.Current;
        Assert.True(snap.LastFailureAt >= snap.LastSuccessAt);
        Assert.False(string.IsNullOrEmpty(snap.LastError));
        // Immediately after the failure, no auto-check.
        Assert.False(repo.ShouldRunByThrottle(false));
        // 1 min + 14 min: still inside the 15-min failure window.
        clock.Now = t0.AddMinutes(1).Add(UpdateRepository.FailureInterval).AddMinutes(-1);
        Assert.False(repo.ShouldRunByThrottle(false));
        // 1 min + 16 min: past the failure window, the failure
        // branch unlocks.
        clock.Now = t0.AddMinutes(1).Add(UpdateRepository.FailureInterval).AddMinutes(1);
        Assert.True(repo.ShouldRunByThrottle(false));
    }

    /// <summary>
    /// True timestamp tie: when RecordNoNewerAvailable advances the
    /// success timestamp to the same value RecordFailure advanced
    /// the failure timestamp to, the failure branch must still win.
    /// Without the tie-failure-wins logic, the throttle would be
    /// blocked by the 6 h success window even after a failure
    /// landed on the same tick.
    /// </summary>
    [Fact]
    public void ExactTieTimestampUnlocksAfterFifteenMinutes()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        var t0 = clock.Now;
        // Advance to a known tick; do an initial success so
        // LastSuccessAt is set, then a second success followed
        // immediately by a failure on the same tick.
        clock.Now = t0.AddMinutes(1);
        repo.RecordNoNewerAvailable();
        var sameTick = t0.AddMinutes(2);
        clock.Now = sameTick;
        repo.RecordNoNewerAvailable(); // advances success to sameTick
        repo.RecordFailure("network unreachable"); // advances failure to sameTick
        var snap = repo.Current;
        Assert.Equal(snap.LastSuccessAt, snap.LastFailureAt);
        Assert.False(string.IsNullOrEmpty(snap.LastError));
        // Past 15 min: failure branch unlocks even though
        // LastSuccessAt is the same instant.
        clock.Now = sameTick.Add(UpdateRepository.FailureInterval).AddMinutes(1);
        Assert.True(repo.ShouldRunByThrottle(false),
            "exact-tie + failure must unlock after 15 min");
    }

    /// <summary>
    /// Explicit force=true must always bypass the throttle window
    /// for the user-triggered "Check now" button.
    /// </summary>
    [Fact]
    public void ForceBypassRegardlessOfWindow()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        repo.RecordNoNewerAvailable();
        Assert.True(repo.ShouldRunByThrottle(true));
        repo.RecordFailure("network unreachable");
        Assert.True(repo.ShouldRunByThrottle(true));
    }

    /// <summary>
    /// A new success AFTER a successful check clears the failure
    /// branch and the throttle returns to the success interval.
    /// </summary>
    [Fact]
    public void NewSuccessAfterOldFailureUsesSuccessBranch()
    {
        var clock = new TestClock();
        using var repo = NewRepo(clock, new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot)));
        var t0 = clock.Now;
        clock.Now = t0.AddMinutes(1);
        repo.RecordFailure("network unreachable");
        // 1 ms later, a successful check.
        clock.Now = t0.AddMinutes(1).AddMilliseconds(1);
        repo.RecordNoNewerAvailable();
        Assert.False(repo.ShouldRunByThrottle(false));
        // Past the success window: success branch unlocks.
        clock.Now = t0.AddMinutes(1).AddMilliseconds(1).Add(UpdateRepository.SuccessInterval).AddMinutes(1);
        Assert.True(repo.ShouldRunByThrottle(false));
    }

    [Fact]
    public async Task NoNewerAvailableIsSuccess()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var source = new StubCheckSource(ct => Task.FromResult<UpdateRepository.ICheckSource.CheckResult?>(null));
        using var repo = NewRepo(clock, cache, source);
        var inflight = repo.RequestCheck(false);
        await inflight.Completion;
        Assert.True(repo.Current.LastSuccessAt > DateTime.MinValue);
        Assert.Null(repo.Current.Available);
        Assert.Null(repo.Current.LastError);
        Assert.False(repo.ShouldRunByThrottle(false));
        Assert.Equal(DateTime.MinValue, repo.Current.LastFailureAt);
    }

    [Fact]
    public void AvailableCheckPreservesDownloaded()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);

        var version = "0.1.0.99";
        var apk = new byte[] { 1, 2, 3, 4 };
        var apkSha = Sha256Hex(apk);
        var m7body = MakeManifest(version, 7, 7, apk.Length, apkSha, out var m7Body, out var m7Sig);
        SignedRelease m7;
        try { m7 = SignedRelease.Verify(m7Body, m7Sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source = new FileInfo(Path.Combine(_stateRoot, "transport-7.bin"));
        File.WriteAllBytes(source.FullName, apk);
        repo.RecordDownloaded(m7, m7Body, m7Sig, source.FullName);
        Assert.True(repo.Current.HasDownloaded);

        var m8Body = MakeManifest("0.1.0.9", 9, 9, apk.Length, apkSha, out var body8Bytes, out var sig8Bytes);
        SignedRelease m8;
        try { m8 = SignedRelease.Verify(body8Bytes, sig8Bytes, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        repo.RecordAvailable(m8, body8Bytes, sig8Bytes);
        Assert.True(repo.Current.HasDownloaded);
        Assert.Equal("0.1.0.99", repo.Current.Downloaded!.Version);
        Assert.True(repo.Current.HasAvailable);
        Assert.Equal("0.1.0.9", repo.Current.Available!.Version);
        Assert.Equal("0.1.0.99", repo.Current.PrimaryCandidate!.Version);
        Assert.True(repo.Current.HasNewerAvailable);
    }

    [Fact]
    public void RecordDownloadedRejectsMismatchedDigest()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);
        var declared = new byte[] { 1, 2, 3, 4 };
        var declaredSha = Sha256Hex(declared);
        var mBody = MakeManifest("0.1.0.99", 99, 99, declared.Length, declaredSha, out var body, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(body, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source = new FileInfo(Path.Combine(_stateRoot, "tampered.bin"));
        File.WriteAllBytes(source.FullName, new byte[] { 9, 9, 9, 9 });
        Assert.Throws<ArgumentException>(() => repo.RecordDownloaded(m, body, sig, source.FullName));
        var dir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99"));
        Assert.False(Directory.Exists(dir.FullName));
    }

    [Fact]
    public void RecordDownloadedRejectsMismatchedLength()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);
        var declared = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        var declaredSha = Sha256Hex(declared);
        var mBody = MakeManifest("0.1.0.99", 99, 99, declared.Length, declaredSha, out var body, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(body, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source = new FileInfo(Path.Combine(_stateRoot, "wrong-length.bin"));
        File.WriteAllBytes(source.FullName, new byte[] { 1, 2, 3 });
        Assert.Throws<ArgumentException>(() => repo.RecordDownloaded(m, body, sig, source.FullName));
    }

    [Fact]
    public async Task ConcurrentRequestsCoalesce()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        int invocations = 0;
        var source = new StubCheckSource(async ct =>
        {
            Interlocked.Increment(ref invocations);
            await Task.Delay(200, ct);
            return null;
        });
        using var repo = NewRepo(clock, cache, source);
        var a = repo.RequestCheck(false);
        var b = repo.RequestCheck(false);
        var c = repo.RequestCheck(true);
        Assert.Same(a, b);
        Assert.Same(a, c);
        await a.Completion;
        Assert.Equal(1, invocations);
    }

    [Fact]
    public void SnapshotBytesAreDefensivelyCloned()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);
        var apk = new byte[] { 1, 2, 3, 4 };
        var apkSha = Sha256Hex(apk);
        var body = MakeManifest("0.1.0.99", 99, 99, apk.Length, apkSha, out var bodyBytes, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(bodyBytes, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        repo.RecordAvailable(m, bodyBytes, sig);
        var snap = repo.Current;
        bodyBytes[0] ^= 1;
        sig[0] ^= 1;
        Assert.NotEqual(bodyBytes[0], snap.AvailableManifestBytes![0]);
        Assert.NotEqual(sig[0], snap.AvailableSignatureBytes![0]);
    }

    [Fact]
    public async Task SourceThrowingIsFailure()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var source = new StubCheckSource(ct => throw new IOException("network unreachable"));
        using var repo = NewRepo(clock, cache, source);
        var inflight = repo.RequestCheck(false);
        await inflight.Completion;
        Assert.NotEqual(DateTime.MinValue, repo.Current.LastFailureAt);
        Assert.Equal("network unreachable", repo.Current.LastError);
        Assert.False(repo.ShouldRunByThrottle(false));
    }

    [Fact]
    public void DiskCachePersistRoundtrip()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var body = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        var sig = new byte[384];
        new Random().NextBytes(sig);
        cache.PersistAvailable(body, sig);
        Assert.Equal(body, cache.ReadAvailableManifestBytes());
        Assert.Equal(sig, cache.ReadAvailableSignatureBytes());
    }

    [Fact]
    public void DiskCacheSizeCapRejectsOversize()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var dir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "available"));
        dir.Create();
        var big = new FileInfo(Path.Combine(dir.FullName, "manifest.json"));
        var data = new byte[UpdateRepository.MaxManifestBytes + 1];
        File.WriteAllBytes(big.FullName, data);
        Assert.Null(cache.ReadAvailableManifestBytes());
    }

    [Fact]
    public void DiskCacheDeleteRemovesDirectory()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var source = new FileInfo(Path.Combine(_stateRoot, "del.bin"));
        File.WriteAllBytes(source.FullName, new byte[] { 1, 2 });
        cache.PersistDownloadedInstaller("0.1.0.99", source.FullName);
        var dir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99"));
        Assert.True(Directory.Exists(dir.FullName));
        var files = dir.GetFiles();
        Assert.True(files.Length > 0, "expected update.exe to be present, got " + files.Length);
        cache.DeleteDownloaded("0.1.0.99");
        Assert.False(Directory.Exists(dir.FullName));
        Assert.True(File.Exists(source.FullName), "source file must not be removed");
    }

    [Fact]
    public void DiskCacheEnumerateFiltersIncomplete()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var source = new FileInfo(Path.Combine(_stateRoot, "enum.bin"));
        File.WriteAllBytes(source.FullName, new byte[] { 1, 2 });
        cache.PersistDownloadedInstaller("0.1.0.99", source.FullName);
        cache.PersistDownloadedMetadata("0.1.0.99", new byte[] { 1, 2, 3, 4 }, new byte[384]);
        var incompleteDir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.9"));
        incompleteDir.Create();
        File.WriteAllBytes(Path.Combine(incompleteDir.FullName, "manifest.json"), new byte[] { 1, 2, 3, 4 });
        Assert.Equal(new[] { "0.1.0.99" }, cache.EnumerateDownloadedVersions());
    }

    /// <summary>
    /// Real DiskCache end-to-end: stream a 1 MiB file through
    /// PersistDownloadedInstaller and verify the digest returned
    /// matches what an independent hash yields, and the on-disk
    /// bytes match the source.
    /// </summary>
    [Fact]
    public void DiskCachePersistDownloadedInstallerStreamsLargeFile()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var data = new byte[1024 * 1024];
        for (int i = 0; i < data.Length; i++) data[i] = (byte)((i * 31 + 7) & 0xFF);
        var source = new FileInfo(Path.Combine(_stateRoot, "big.bin"));
        File.WriteAllBytes(source.FullName, data);
        var actualSha = cache.PersistDownloadedInstaller("0.1.0.99", source.FullName);
        var expectedSha = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
        Assert.Equal(expectedSha, actualSha);
        var persisted = new FileInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99", "update.exe"));
        Assert.True(persisted.Exists);
        Assert.Equal(data.Length, persisted.Length);
        var readBack = File.ReadAllBytes(persisted.FullName);
        Assert.Equal(data, readBack);
    }

    /// <summary>
    /// Real end-to-end: write a transport-style file, call
    /// RecordDownloaded, then create a fresh repository against the
    /// same cache (process restart) and verify hydration restores
    /// the downloaded slot without any hidden pre-population.
    /// </summary>
    [Fact]
    public async Task EndToEndDownloadThenHydrateAfterRestart()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var apk = new byte[] { 5, 6, 7, 8 };
        var apkSha = Sha256Hex(apk);
        var body = MakeManifest("0.1.0.99", 99, 99, apk.Length, apkSha, out var bodyBytes, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(bodyBytes, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var transportOutput = new FileInfo(Path.Combine(_stateRoot, "transport-output.bin"));
        File.WriteAllBytes(transportOutput.FullName, apk);

        // First "process": bind source (no-op) and record the download.
        using (var first = NewRepo(clock, cache,
                   new StubCheckSource(ct => Task.FromResult<UpdateRepository.ICheckSource.CheckResult?>(null))))
        {
            await Task.Delay(50); // allow hydration to run
            first.RecordDownloaded(m, bodyBytes, sig, transportOutput.FullName);
            Assert.True(first.Current.HasDownloaded);
            var persisted = new FileInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99", "update.exe"));
            Assert.True(persisted.Exists);
            Assert.Equal(apk, File.ReadAllBytes(persisted.FullName));
        }

        // Second "process": fresh repository against the same cache.
        // Hydration alone must restore the downloaded slot.
        using (var second = NewRepo(clock, cache,
                   new StubCheckSource(ct => Task.FromResult<UpdateRepository.ICheckSource.CheckResult?>(null))))
        {
            for (int i = 0; i < 100; i++)
            {
                if (second.Current.HasDownloaded) break;
                await Task.Delay(50);
            }
            Assert.True(second.Current.HasDownloaded, "second process: hydration must restore downloaded slot");
            Assert.Equal("0.1.0.99", second.Current.Downloaded!.Version);
            Assert.Equal(99, second.Current.Downloaded.Sequence);
        }
    }

    [Fact]
    public void DigestMismatchEvictsDownloadDir()
    {
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        var clock = new TestClock();
        using var repo = NewRepo(clock, cache);
        var declared = new byte[] { 1, 2, 3, 4 };
        var declaredSha = Sha256Hex(declared);
        var mBody = MakeManifest("0.1.0.99", 99, 99, declared.Length, declaredSha, out var body, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(body, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source = new FileInfo(Path.Combine(_stateRoot, "mismatch.bin"));
        File.WriteAllBytes(source.FullName, new byte[] { 7, 7, 7, 7 });
        Assert.Throws<ArgumentException>(() => repo.RecordDownloaded(m, body, sig, source.FullName));
        var dir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99"));
        Assert.False(Directory.Exists(dir.FullName));
    }

    [Fact]
    public void ClearDownloadedAfterInstallPreservesAvailable()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);
        var apk = new byte[] { 1, 2, 3, 4 };
        var apkSha = Sha256Hex(apk);
        var m7Body = MakeManifest("0.1.0.99", 99, 99, apk.Length, apkSha, out var body7, out var sig7);
        SignedRelease m7;
        try { m7 = SignedRelease.Verify(body7, sig7, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source7 = new FileInfo(Path.Combine(_stateRoot, "d7.bin"));
        File.WriteAllBytes(source7.FullName, apk);
        repo.RecordDownloaded(m7, body7, sig7, source7.FullName);
        var m8Body = MakeManifest("0.1.0.9", 9, 9, apk.Length, apkSha, out var body8, out var sig8);
        SignedRelease m8;
        try { m8 = SignedRelease.Verify(body8, sig8, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        repo.RecordAvailable(m8, body8, sig8);
        repo.ClearDownloadedAfterInstall("0.1.0.99");
        Assert.False(repo.Current.HasDownloaded);
        Assert.True(repo.Current.HasAvailable);
        Assert.Equal("0.1.0.9", repo.Current.Available!.Version);
    }

    [Fact]
    public void ClearDownloadedAfterInstallEvictsDirectory()
    {
        var clock = new TestClock();
        var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_stateRoot));
        using var repo = NewRepo(clock, cache);
        var apk = new byte[] { 1, 2, 3, 4 };
        var apkSha = Sha256Hex(apk);
        var body = MakeManifest("0.1.0.99", 99, 99, apk.Length, apkSha, out var bodyBytes, out var sig);
        SignedRelease m;
        try { m = SignedRelease.Verify(bodyBytes, sig, _trustKey); }
        catch (Exception e) { throw new InvalidOperationException("Setup failed: " + e.Message, e); }
        var source = new FileInfo(Path.Combine(_stateRoot, "evict.bin"));
        File.WriteAllBytes(source.FullName, apk);
        repo.RecordDownloaded(m, bodyBytes, sig, source.FullName);
        var dir = new DirectoryInfo(Path.Combine(_stateRoot, "updates", "downloaded", "0.1.0.99"));
        Assert.True(Directory.Exists(dir.FullName));
        repo.ClearDownloadedAfterInstall("0.1.0.99");
        Assert.False(Directory.Exists(dir.FullName));
    }
}