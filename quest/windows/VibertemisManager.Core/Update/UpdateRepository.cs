using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Paths;

namespace VibertemisManager.Core.Update;

/// <summary>
/// App-scoped shared state for Quest preview updates on Windows.
///
/// <para>Mirrors the Android UpdateRepository shape: two independent slots
/// (the latest verified metadata, and a downloaded installer bound to its
/// exact signed bytes), throttle timestamps, and a coalesced in-flight
/// check handle.</para>
///
/// <para>The "installed" anchor is the trust-shipped constants
/// <see cref="SignedRelease.CurrentVersionReference"/> and
/// <see cref="SignedRelease.CurrentSequence"/>. The metadata source
/// already filters against these when fetching; hydration also applies
/// the same filter so a previously-cached manifest that is not strictly
/// newer than what the user is already running is dropped.</para>
/// </summary>
public sealed class UpdateRepository : IDisposable
{
    public static readonly TimeSpan SuccessInterval = TimeSpan.FromHours(6);
    public static readonly TimeSpan FailureInterval = TimeSpan.FromMinutes(15);

    public const int MaxManifestBytes = 65536;
    public const int MaxSignatureBytes = 384;
    public const int CopyBufferBytes = 65536;

    public sealed class Snapshot
    {
        public SignedRelease? Available { get; }
        public byte[]? AvailableManifestBytes { get; }
        public byte[]? AvailableSignatureBytes { get; }
        public SignedRelease? Downloaded { get; }
        public byte[]? DownloadedManifestBytes { get; }
        public byte[]? DownloadedSignatureBytes { get; }
        public string? DownloadedFilePath { get; }
        public DateTime LastSuccessAt { get; }
        public DateTime LastFailureAt { get; }
        public string? LastError { get; }
        public bool Checking { get; }

        public Snapshot(
            SignedRelease? available, byte[]? aBytes, byte[]? aSig,
            SignedRelease? downloaded, byte[]? dBytes, byte[]? dSig, string? apk,
            DateTime lastSuccessAt, DateTime lastFailureAt, string? lastError, bool checking)
        {
            Available = available;
            AvailableManifestBytes = Clone(aBytes);
            AvailableSignatureBytes = Clone(aSig);
            Downloaded = downloaded;
            DownloadedManifestBytes = Clone(dBytes);
            DownloadedSignatureBytes = Clone(dSig);
            DownloadedFilePath = apk;
            LastSuccessAt = lastSuccessAt;
            LastFailureAt = lastFailureAt;
            LastError = lastError;
            Checking = checking;
        }

        public bool HasAvailable => Available != null;
        public bool HasDownloaded
        {
            get
            {
                if (Downloaded == null || string.IsNullOrEmpty(DownloadedFilePath)) return false;
                return File.Exists(DownloadedFilePath);
            }
        }

        public bool HasNewerAvailable
        {
            get
            {
                if (!HasAvailable) return false;
                if (!HasDownloaded) return true;
                return CompareReleases(Available!, Downloaded!) > 0;
            }
        }

        public SignedRelease? PrimaryCandidate
            => HasDownloaded ? Downloaded : HasAvailable ? Available : null;

        /// <summary>
        /// Strict version+sequence comparator. Returns positive if
        /// <paramref name="a"/> is newer than <paramref name="b"/>.
        /// Sequence breaks ties by semantic Version comparison.
        /// </summary>
        public static int CompareReleases(SignedRelease a, SignedRelease b)
        {
            if (a.Sequence != b.Sequence) return a.Sequence > b.Sequence ? 1 : -1;
            var av = ParseVersionSafe(a.Version);
            var bv = ParseVersionSafe(b.Version);
            if (av == null && bv == null) return string.CompareOrdinal(a.Version, b.Version);
            if (av == null) return -1;
            if (bv == null) return 1;
            return av.CompareTo(bv);
        }

        private static Version? ParseVersionSafe(string s)
        {
            try { return new Version(s); } catch { return null; }
        }

        private static byte[]? Clone(byte[]? bytes) => bytes == null ? null : (byte[])bytes.Clone();
    }

    public interface IObserver
    {
        void OnUpdate(Snapshot snapshot);
    }

    public interface IClock
    {
        DateTime Now { get; }
    }

    public interface ICheckSource
    {
        Task<CheckResult?> CheckAsync(CancellationToken cancellation);
        public sealed class CheckResult
        {
            public SignedRelease Release { get; }
            public byte[] ManifestBytes { get; }
            public byte[] SignatureBytes { get; }
            public CheckResult(SignedRelease release, byte[] manifestBytes, byte[] signatureBytes)
            {
                Release = release;
                ManifestBytes = manifestBytes;
                SignatureBytes = signatureBytes;
            }
        }
    }

    public interface ICache
    {
        byte[]? ReadAvailableManifestBytes();
        byte[]? ReadAvailableSignatureBytes();
        void PersistAvailable(byte[] manifestBytes, byte[] signatureBytes);

        string DownloadedRoot { get; }
        string DownloadedManifestPath(string version);
        string DownloadedSignaturePath(string version);
        string DownloadedApkPath(string version);
        byte[]? ReadDownloadedManifestBytes(string version);
        byte[]? ReadDownloadedSignatureBytes(string version);

        string PersistDownloadedInstaller(string version, string sourceFilePath);
        void PersistDownloadedMetadata(string version, byte[] manifestBytes, byte[] signatureBytes);
        void DeleteDownloaded(string version);
        IReadOnlyList<string> EnumerateDownloadedVersions();
    }

    public interface ITrustKeySource
    {
        string Pem { get; }
    }

    public sealed class InFlight
    {
        private volatile bool _force;
        private volatile bool _completed;
        private Snapshot? _result;
        private Exception? _failure;
        private Snapshot? _snapshot;
        private readonly TaskCompletionSource<Snapshot> _tcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public InFlight(bool force) { _force = force; }
        public bool Force => _force;
        public void BumpForce(bool force) { if (force) _force = true; }
        public bool Completed => _completed;
        public Snapshot? Result => _result;
        public Exception? Failure => _failure;
        public Task<Snapshot> Completion => _tcs.Task;
        internal void BindSnapshot(Snapshot s) { _snapshot = s; }
        internal void Complete(Snapshot s) { _result = s; _completed = true; _tcs.TrySetResult(s); }
        internal void CompleteWithFailure(Exception t)
        {
            _failure = t;
            _completed = true;
            _result = _snapshot ?? new Snapshot(null, null, null, null, null, null, null,
                DateTime.MinValue, DateTime.MinValue, t.Message, false);
            _tcs.TrySetResult(_result);
        }
    }

    private readonly ICache cache;
    private readonly IClock clock;
    private readonly ITrustKeySource trustKey;
    private readonly List<IObserver> observers = new();
    private readonly object observerLock = new();
    private Snapshot currentSnapshot;
    private InFlight? inFlight;
    private readonly object inFlightLock = new();
    private CancellationTokenSource? lifecycleCts;
    private TaskScheduler? scheduler;
    private ICheckSource? source;
    private bool disposed;

    public UpdateRepository(ICache cache, IClock clock, ITrustKeySource trustKey)
    {
        this.cache = cache;
        this.clock = clock;
        this.trustKey = trustKey;
        this.currentSnapshot = Empty(checking: false);
    }

    /// <summary>
    /// The trust-shipped "installed" anchor. Mirrors the
    /// <see cref="SignedRelease.CurrentVersionReference"/> /
    /// <see cref="SignedRelease.CurrentSequence"/> pair the metadata
    /// source already filters against.
    /// </summary>
    public static Version InstalledVersionReference => SignedRelease.CurrentVersionReference;
    public static long InstalledSequence => SignedRelease.CurrentSequence;

    public void BindSource(ICheckSource source, TaskScheduler scheduler)
    {
        this.source = source;
        this.scheduler = scheduler;
        lifecycleCts = new CancellationTokenSource();
        Task.Factory.StartNew(HydrateFromCache, lifecycleCts.Token,
            TaskCreationOptions.None, scheduler);
    }

    public Snapshot Current => currentSnapshot;

    public void AddObserver(IObserver o)
    {
        if (o == null) return;
        lock (observerLock)
        {
            if (!observers.Contains(o)) observers.Add(o);
        }
        try { o.OnUpdate(currentSnapshot); } catch { /* tolerant */ }
    }

    public void RemoveObserver(IObserver o)
    {
        lock (observerLock)
        {
            observers.Remove(o);
        }
    }

    public bool ShouldRunByThrottle(bool force)
    {
        if (force) return true;
        var now = clock.Now;
        var snap = currentSnapshot;
        // Failure beats success on a tie. When the latest outcome
        // was a failure, LastFailureAt equals or exceeds
        // LastSuccessAt AND LastError is non-null. The failure
        // branch wins on a tie so a forced retry is not blocked by
        // the 6-hour success window.
        bool failureIsLatestOrTie =
            snap.LastFailureAt >= snap.LastSuccessAt && !string.IsNullOrEmpty(snap.LastError);
        if (failureIsLatestOrTie)
        {
            return (now - snap.LastFailureAt) >= FailureInterval;
        }
        if (snap.LastSuccessAt != DateTime.MinValue)
        {
            return (now - snap.LastSuccessAt) >= SuccessInterval;
        }
        if (snap.LastFailureAt != DateTime.MinValue)
        {
            return (now - snap.LastFailureAt) >= FailureInterval;
        }
        return true;
    }

    public InFlight RequestCheck(bool force)
    {
        if (source == null || scheduler == null)
            throw new InvalidOperationException("UpdateRepository.BindSource must be called first.");
        if (lifecycleCts == null || lifecycleCts.IsCancellationRequested)
            throw new InvalidOperationException("UpdateRepository is shut down.");
        lock (inFlightLock)
        {
            if (inFlight != null)
            {
                inFlight.BumpForce(force);
                return inFlight;
            }
            var start = new InFlight(force);
            start.BindSnapshot(currentSnapshot);
            inFlight = start;
            Task.Factory.StartNew(() => RunInFlight(start), lifecycleCts.Token,
                TaskCreationOptions.None, scheduler);
            return start;
        }
    }

    public void RecordAvailable(SignedRelease release, byte[] manifestBytes, byte[] signatureBytes)
    {
        if (release == null) throw new ArgumentNullException(nameof(release));
        if (manifestBytes == null) throw new ArgumentNullException(nameof(manifestBytes));
        if (signatureBytes == null) throw new ArgumentNullException(nameof(signatureBytes));
        var now = clock.Now;
        var prev = currentSnapshot;
        currentSnapshot = new Snapshot(
            release, manifestBytes, signatureBytes,
            prev.Downloaded, prev.DownloadedManifestBytes, prev.DownloadedSignatureBytes, prev.DownloadedFilePath,
            now, prev.LastFailureAt, null, false);
        NotifyObservers();
    }

    public void RecordNoNewerAvailable()
    {
        var now = clock.Now;
        var prev = currentSnapshot;
        currentSnapshot = new Snapshot(
            prev.Available, prev.AvailableManifestBytes, prev.AvailableSignatureBytes,
            prev.Downloaded, prev.DownloadedManifestBytes, prev.DownloadedSignatureBytes, prev.DownloadedFilePath,
            now, prev.LastFailureAt, null, false);
        NotifyObservers();
    }

    public void RecordFailure(string message)
    {
        var now = clock.Now;
        var prev = currentSnapshot;
        currentSnapshot = new Snapshot(
            prev.Available, prev.AvailableManifestBytes, prev.AvailableSignatureBytes,
            prev.Downloaded, prev.DownloadedManifestBytes, prev.DownloadedSignatureBytes, prev.DownloadedFilePath,
            prev.LastSuccessAt, now, message, false);
        NotifyObservers();
    }

    public void RecordDownloaded(SignedRelease release, byte[] manifestBytes, byte[] signatureBytes, string sourceFilePath)
    {
        if (release == null) throw new ArgumentNullException(nameof(release));
        if (manifestBytes == null) throw new ArgumentNullException(nameof(manifestBytes));
        if (signatureBytes == null) throw new ArgumentNullException(nameof(signatureBytes));
        if (string.IsNullOrEmpty(sourceFilePath)) throw new ArgumentNullException(nameof(sourceFilePath));
        if (!File.Exists(sourceFilePath))
            throw new ArgumentException("Downloaded source file is missing: " + sourceFilePath, nameof(sourceFilePath));
        var sourceInfo = new FileInfo(sourceFilePath);
        if (sourceInfo.Length != release.Windows.Bytes)
            throw new ArgumentException("Downloaded source length does not match signed size");

        string actualSha;
        try
        {
            actualSha = cache.PersistDownloadedInstaller(release.Version, sourceFilePath);
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException("Failed to persist downloaded installer: " + ex.Message, ex);
        }
        if (!string.Equals(actualSha, release.Windows.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            try { cache.DeleteDownloaded(release.Version); } catch { /* tolerated */ }
            throw new ArgumentException("Downloaded installer digest does not match signed metadata");
        }
        try
        {
            cache.PersistDownloadedMetadata(release.Version, manifestBytes, signatureBytes);
        }
        catch (Exception ex)
        {
            try { cache.DeleteDownloaded(release.Version); } catch { /* tolerated */ }
            throw new InvalidOperationException("Failed to persist downloaded metadata: " + ex.Message, ex);
        }
        var now = clock.Now;
        var prev = currentSnapshot;
        string apkPath = cache.DownloadedApkPath(release.Version);
        currentSnapshot = new Snapshot(
            prev.Available, prev.AvailableManifestBytes, prev.AvailableSignatureBytes,
            release, manifestBytes, signatureBytes, apkPath,
            now, prev.LastFailureAt, null, false);
        NotifyObservers();
    }

    public void ClearDownloadedAfterInstall(string version)
    {
        if (string.IsNullOrEmpty(version)) return;
        var prev = currentSnapshot;
        try { cache.DeleteDownloaded(version); } catch { /* best effort */ }
        if (prev.Downloaded != null && version == prev.Downloaded.Version)
        {
            currentSnapshot = new Snapshot(
                prev.Available, prev.AvailableManifestBytes, prev.AvailableSignatureBytes,
                null, null, null, null,
                prev.LastSuccessAt, prev.LastFailureAt, prev.LastError, prev.Checking);
            NotifyObservers();
        }
    }

    private void NotifyObservers()
    {
        IObserver[] snapshot;
        lock (observerLock)
        {
            snapshot = observers.ToArray();
        }
        var s = this.currentSnapshot;
        foreach (var o in snapshot)
        {
            try { o.OnUpdate(s); } catch { /* tolerant */ }
        }
    }

    /// <summary>
    /// True when <paramref name="candidate"/> is strictly newer than
    /// the installed anchor (sequence, then semantic version). Used
    /// by both the available and downloaded slot hydration paths.
    /// </summary>
    public static bool IsNewerThanInstalled(SignedRelease candidate)
    {
        return candidate.Sequence > InstalledSequence
            && Version.TryParse(candidate.Version, out var candidateVersion)
            && candidateVersion.CompareTo(InstalledVersionReference) > 0;
    }

    private void HydrateFromCache()
    {
        SignedRelease? available = null;
        byte[]? aBytes = null, aSig = null;
        SignedRelease? downloaded = null;
        byte[]? dBytes = null, dSig = null;
        string? dApk = null;

        try
        {
            aBytes = cache.ReadAvailableManifestBytes();
            aSig = cache.ReadAvailableSignatureBytes();
            if (aBytes != null && aSig != null
                && aBytes.Length > 0 && aBytes.Length <= MaxManifestBytes
                && aSig.Length == MaxSignatureBytes)
            {
                SignedRelease m = null!;
                try { m = SignedRelease.Verify(aBytes, aSig, trustKey.Pem); }
                catch { aBytes = null; aSig = null; m = null!; }
                if (m != null && IsNewerThanInstalled(m))
                {
                    available = m;
                }
            }
        }
        catch { available = null; aBytes = null; aSig = null; }

        try
        {
            var versions = cache.EnumerateDownloadedVersions().ToArray();
            // Sort by parsed (major, minor, build, revision); tie-break
            // by string. Lexicographic directory-name sort would put
            // "0.1.0.10" before "0.1.0.9", which is wrong.
            Array.Sort(versions, VersionDirectoryComparer.Instance);
            for (int i = versions.Length - 1; i >= 0; i--)
            {
                var version = versions[i];
                var mBytes = cache.ReadDownloadedManifestBytes(version);
                var sBytes = cache.ReadDownloadedSignatureBytes(version);
                if (mBytes == null || sBytes == null) continue;
                if (mBytes.Length <= 0 || mBytes.Length > MaxManifestBytes) continue;
                if (sBytes.Length != MaxSignatureBytes) continue;
                SignedRelease m;
                try { m = SignedRelease.Verify(mBytes, sBytes, trustKey.Pem); }
                catch { cache.DeleteDownloaded(version); continue; }
                if (!IsNewerThanInstalled(m))
                {
                    // Cached release is at or below the installed
                    // version; evict so the user cannot accidentally
                    // reinstall or downgrade after a successful
                    // upgrade. After the user upgrades, the installed
                    // anchor advances (via SignedRelease constants in
                    // the new binary) and the older cache is dropped.
                    cache.DeleteDownloaded(version);
                    continue;
                }
                var apkPath = cache.DownloadedApkPath(version);
                if (!File.Exists(apkPath)) { cache.DeleteDownloaded(version); continue; }
                var apkInfo = new FileInfo(apkPath);
                if (apkInfo.Length != m.Windows.Bytes) { cache.DeleteDownloaded(version); continue; }
                string hex;
                using (var fs = File.OpenRead(apkPath))
                {
                    hex = Convert.ToHexString(SHA256.HashData(fs)).ToLowerInvariant();
                }
                if (hex != m.Windows.Sha256) { cache.DeleteDownloaded(version); continue; }
                downloaded = m;
                dBytes = mBytes;
                dSig = sBytes;
                dApk = apkPath;
                break; // newest valid
            }
        }
        catch { downloaded = null; dBytes = null; dSig = null; dApk = null; }

        var prev = currentSnapshot;
        currentSnapshot = new Snapshot(
            available, aBytes, aSig,
            downloaded, dBytes, dSig, dApk,
            prev.LastSuccessAt, prev.LastFailureAt, prev.LastError, false);
        NotifyObservers();
    }

    /// <summary>
    /// Compare version directory names numerically. The four numeric
    /// components are padded so lexicographic order matches semantic
    /// order even when one component rolls over (e.g. 0.1.0.9 &lt;
    /// 0.1.0.10).
    /// </summary>
    private sealed class VersionDirectoryComparer : IComparer<string>
    {
        public static readonly VersionDirectoryComparer Instance = new();
        public int Compare(string? x, string? y)
        {
            if (ReferenceEquals(x, y)) return 0;
            if (x == null) return -1;
            if (y == null) return 1;
            var xParts = ParseParts(x);
            var yParts = ParseParts(y);
            for (int i = 0; i < 4; i++)
            {
                int cmp = xParts[i].CompareTo(yParts[i]);
                if (cmp != 0) return cmp;
            }
            return string.CompareOrdinal(x, y);
        }
        private static int[] ParseParts(string version)
        {
            var parts = version.Split('.');
            var result = new int[4];
            for (int i = 0; i < 4 && i < parts.Length; i++)
            {
                if (!int.TryParse(parts[i], out var n)) n = 0;
                result[i] = n;
            }
            return result;
        }
    }

    private async Task RunInFlight(InFlight job)
    {
        var forced = job.Force;
        currentSnapshot = WithChecking(currentSnapshot, true);
        NotifyObservers();
        try
        {
            if (!ShouldRunByThrottle(forced))
            {
                FinishInFlight(job);
                return;
            }
            var src = source;
            if (src == null) throw new InvalidOperationException("no ICheckSource bound");
            var token = lifecycleCts?.Token ?? CancellationToken.None;
            var result = await src.CheckAsync(token).ConfigureAwait(false);
            if (result == null || !IsNewerThanInstalled(result.Release))
            {
                RecordNoNewerAvailable();
            }
            else
            {
                cache.PersistAvailable(result.ManifestBytes, result.SignatureBytes);
                RecordAvailable(result.Release, result.ManifestBytes, result.SignatureBytes);
            }
            FinishInFlight(job);
        }
        catch (Exception ex)
        {
            RecordFailure(ex.Message ?? ex.GetType().Name);
            FinishInFlight(job, ex);
        }
        finally
        {
            currentSnapshot = WithChecking(currentSnapshot, false);
            NotifyObservers();
        }
    }

    private void FinishInFlight(InFlight job, Exception? failure = null)
    {
        lock (inFlightLock)
        {
            inFlight = null;
        }
        if (failure != null) job.CompleteWithFailure(failure);
        else job.Complete(currentSnapshot);
    }

    private static Snapshot WithChecking(Snapshot s, bool checking)
        => new Snapshot(s.Available, s.AvailableManifestBytes, s.AvailableSignatureBytes,
            s.Downloaded, s.DownloadedManifestBytes, s.DownloadedSignatureBytes, s.DownloadedFilePath,
            s.LastSuccessAt, s.LastFailureAt, s.LastError, checking);

    public static string FormatBytes(long bytes)
    {
        if (bytes <= 0) return "0 MB";
        return (bytes / 1048576.0).ToString("0.0") + " MB";
    }

    public static readonly IReadOnlyList<IObserver> NoObservers = Array.Empty<IObserver>();

    private static Snapshot Empty(bool checking) => new Snapshot(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, checking);

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        try { lifecycleCts?.Cancel(); } catch { }
        lifecycleCts?.Dispose();
    }

    public void Shutdown() => Dispose();
}