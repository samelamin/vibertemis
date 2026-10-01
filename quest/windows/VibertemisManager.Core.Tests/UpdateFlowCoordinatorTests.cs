// One-click update flow: target pinning, deferred-callback
// orchestration, cancellation, busy gating, cache retention, and the
// honest "Windows is doing it now" terminal state.
//
// Every stage is an injected delegate, so these tests drive the real
// coordinator the WinForms layer uses without a filesystem, a
// network, or an installer. Deferred callbacks are the point: they
// let a background check land, or a cancel arrive, in the middle of
// an attempt and prove the attempt does not change its mind.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.Update;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class UpdateFlowCoordinatorTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "vibertemis-updateflow-" + Guid.NewGuid().ToString("N"));
    private readonly List<UpdateFlowState> _states = new();
    private readonly List<string> _notices = new();

    public UpdateFlowCoordinatorTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
        GC.SuppressFinalize(this);
    }

    private static SignedRelease Release(string version, long sequence, string digest = "d0", long bytes = 4096)
        => new(sequence, version, "20.14.1", new ReleaseAsset(
            "VibertemisVR-HostManager-Setup-" + version + ".exe",
            new Uri("https://example.invalid/" + version), bytes, digest));

    /// <summary>
    /// A release whose signed digest matches the bytes actually on disk,
    /// so the execution-boundary re-verify exercises the real path.
    /// </summary>
    private SignedRelease ReleasableWithInstaller(string version, long sequence, long bytes = 4096)
    {
        var draft = Release(version, sequence, "unset", bytes);
        return ReleaseWithRealDigest(draft, WriteInstaller(draft));
    }

    /// <summary>Writes a real file whose bytes hash to the release digest.</summary>
    private string WriteInstaller(SignedRelease release, bool corrupt = false)
    {
        var path = Path.Combine(_dir, release.Windows.Filename);
        var payload = new byte[release.Windows.Bytes];
        Array.Fill(payload, (byte)'A');
        if (corrupt) Array.Fill(payload, (byte)'Z');
        File.WriteAllBytes(path, payload);
        return path;
    }

    private static SignedRelease ReleaseWithRealDigest(SignedRelease release, string file)
    {
        using var stream = File.OpenRead(file);
        var digest = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
        return release with { Windows = release.Windows with { Sha256 = digest } };
    }

    /// <summary>
    /// A repository stand-in plus every coordinator side effect. The
    /// snapshot is rebuilt from the mutable fields on each read so a
    /// test can publish new metadata exactly like a background check.
    /// </summary>
    private Harness New(
        SignedRelease? available = null,
        SignedRelease? downloaded = null,
        string? downloadedPath = null)
        => new(_states, _notices)
        {
            Available = available,
            Downloaded = downloaded,
            DownloadedPath = downloadedPath,
        };

    private static UpdateRepository.Snapshot Empty() => new(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, false);

    private static UpdateRepository.Snapshot WithAvailable(SignedRelease release) => new(
        release, new byte[] { 1 }, new byte[] { 2 }, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, false);

    private sealed class Harness
    {
        private readonly List<UpdateFlowState> _states;
        private readonly List<string> _notices;

        public SignedRelease? Available;
        public byte[] AvailableManifest = new byte[] { 1 };
        public byte[] AvailableSignature = new byte[] { 2 };
        public SignedRelease? Downloaded;
        public byte[] DownloadedManifest = new byte[] { 3 };
        public byte[] DownloadedSignature = new byte[] { 4 };
        public string? DownloadedPath;
        public DateTime LastSuccess;
        public DashboardBusyReport Busy = DashboardBusyReport.Idle();
        public string BlockReason = "";

        public List<UpdateTarget> Downloads { get; } = new();
        public List<UpdateTarget> Recorded { get; } = new();
        public List<UpdateTarget> Purged { get; } = new();
        public List<UpdateTarget> HandedOff { get; } = new();
        public UpdateHandoffOutcome HandoffOutcome = new(true, true, true, "job.json", null);
        public Func<UpdateTarget, CancellationToken, Task<string>>? DownloadBody;
        public TaskCompletionSource DownloadEntered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource DownloadRelease = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public CancellationToken LastDownloadToken;

        public UpdateFlowCoordinator Flow { get; }

        public Harness(List<UpdateFlowState> states, List<string> notices)
        {
            _states = states;
            _notices = notices;
            Flow = new UpdateFlowCoordinator(
                snapshot: () => new UpdateRepository.Snapshot(
                    Available, AvailableManifest, AvailableSignature,
                    Downloaded, DownloadedManifest, DownloadedSignature, DownloadedPath,
                    LastSuccess, DateTime.MinValue, null, false),
                busy: () => Busy,
                blockReason: () => BlockReason,
                download: (target, token, progress) =>
                {
                    Downloads.Add(target);
                    LastDownloadToken = token;
                    progress?.Report(new UpdateProgress(1024, target.Release.Windows.Bytes, "downloading", false));
                    if (DownloadBody is null) return Task.FromResult(target.CachedFilePath!);
                    DownloadEntered.TrySetResult();
                    return DownloadBody(target, token);
                },
                recordDownloaded: (target, file) =>
                {
                    Recorded.Add(target.WithCachedFile(file));
                    Downloaded = target.Release;
                    DownloadedPath = file;
                },
                purgeDownloaded: target =>
                {
                    Purged.Add(target);
                    if (target.CachedFilePath is { } p) { try { File.Delete(p); } catch { } }
                    Downloaded = null;
                    DownloadedPath = null;
                },
                handoff: (target, _) =>
                {
                    HandedOff.Add(target);
                    return Task.FromResult(HandoffOutcome);
                });
            Flow.StateChanged += s => { lock (_states) _states.Add(s); };
            Flow.Notice += n => { lock (_notices) _notices.Add(n); };
        }
    }

    private IReadOnlyList<UpdateFlowStage> Stages => _states.Select(s => s.Stage).ToList();
    private UpdateFlowState Last => _states[^1];

    [Fact]
    public async Task OneClickRunsDownloadVerifyThenHandoffWithoutASecondPrompt()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        string? installed = null;
        h.DownloadBody = (target, _) =>
        {
            installed = WriteInstaller(target.Release);
            return Task.FromResult(installed);
        };

        Assert.True(await h.Flow.StartAsync());

        // Exactly one download, one recorded cache entry, one handoff,
        // and the attempt ends as "Windows owns it now", never as a
        // claim that the update is already installed.
        Assert.Single(h.Downloads);
        Assert.Single(h.Recorded);
        Assert.Single(h.HandedOff);
        Assert.Equal("0.1.0.10", h.HandedOff[0].Version);
        // Exactly one owner-visible attempt, and the second
        // Downloading entry is the real byte tick from the transport.
        Assert.Equal(new[]
        {
            UpdateFlowStage.Choosing,
            UpdateFlowStage.Downloading,
            UpdateFlowStage.Downloading,
            UpdateFlowStage.Verifying,
            UpdateFlowStage.HandingOff,
            UpdateFlowStage.AwaitingSystem,
        }, Stages);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, Last.Stage);
        Assert.DoesNotContain("installed", Last.Message!, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(installed, h.HandedOff[0].CachedFilePath);
    }

    [Fact]
    public async Task CachedMatchingReleaseSkipsTheDownloadEntirely()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        var h = New(downloaded: release, downloadedPath: path);

        Assert.True(await h.Flow.StartAsync());

        Assert.Empty(h.Downloads);
        Assert.Single(h.HandedOff);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, Last.Stage);
        Assert.Empty(h.Purged);
    }

    [Fact]
    public async Task TargetIsPinnedAtClickSoNewerMetadataCannotSwapTheFile()
    {
        var pinned = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: pinned);
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));

        var attempt = h.Flow.StartAsync();
        await h.DownloadEntered.Task;

        // A background check publishes a newer release and a newer
        // available manifest mid-attempt.
        var newer = ReleasableWithInstaller("0.1.0.11", SignedRelease.CurrentSequence + 2);
        h.Available = newer;
        h.AvailableManifest = new byte[] { 9 };
        h.AvailableSignature = new byte[] { 9 };

        await attempt;

        Assert.Single(h.HandedOff);
        Assert.Equal("0.1.0.10", h.HandedOff[0].Version);
        Assert.Equal(pinned.Windows.Sha256, h.HandedOff[0].Release.Windows.Sha256);
        Assert.Equal(new byte[] { 1 }, h.HandedOff[0].ManifestBytes);
    }

    [Fact]
    public async Task CancelDuringDownloadStopsTheAttemptAndNothingRunsLater()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = async (target, token) =>
        {
            await Task.Delay(Timeout.Infinite, token).ConfigureAwait(false);
            return WriteInstaller(target.Release);
        };

        var attempt = h.Flow.StartAsync();
        await h.DownloadEntered.Task;
        Assert.True(h.Flow.Cancel());
        await attempt;

        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Recorded);
        Assert.NotEqual(UpdateFlowStage.AwaitingSystem, h.Flow.State.Stage);
        // The cancelled attempt owns nothing afterwards.
        Assert.False(h.Flow.IsBusy);
        Assert.False(h.Flow.State.Busy);
        Assert.True(h.Flow.State.CanRetry);
        Assert.Equal(UpdateFlowStage.Failed, h.Flow.State.Stage);
        Assert.DoesNotContain("installed", h.Flow.State.Message!, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task CancelHoldsTheSlotUntilAnUncooperativeTransportUnwindsAndOnlyThenAllowsARetry()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        string? slowFile = null;
        // A transport that ignores its cancellation token and keeps
        // writing the destination after the user cancels: exactly the
        // case where a retry must not be admitted yet.
        h.DownloadBody = async (target, _) =>
        {
            await h.DownloadRelease.Task.ConfigureAwait(false);
            slowFile = WriteInstaller(target.Release);
            return slowFile;
        };

        var attempt = h.Flow.StartAsync();
        await h.DownloadEntered.Task;
        Assert.True(h.Flow.Cancel());

        // The slot is still held: the state says cancelling, cannot be
        // cancelled again, cannot be retried, and a retry is refused.
        Assert.Equal(UpdateFlowStage.Cancelling, h.Flow.State.Stage);
        Assert.True(h.Flow.IsBusy);
        Assert.True(h.Flow.State.Busy);
        Assert.False(h.Flow.State.CanCancel);
        Assert.False(h.Flow.State.CanRetry);
        Assert.False(await h.Flow.StartAsync());
        Assert.Single(h.Downloads);
        Assert.Empty(h.Recorded);

        // The interrupted transport writes its file and returns only
        // now. It may not record a cache or run anything else.
        h.DownloadRelease.TrySetResult();
        await attempt;
        Assert.Empty(h.Recorded);
        Assert.Empty(h.HandedOff);

        // Cleanup finished, so the attempt is terminal-cancelled and
        // the owner is offered a real retry.
        Assert.Equal(UpdateFlowStage.Failed, h.Flow.State.Stage);
        Assert.False(h.Flow.State.Busy);
        Assert.True(h.Flow.State.CanRetry);
        Assert.False(h.Flow.IsBusy);

        // And the retry works, proving the slot really was free.
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.HandedOff);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, h.Flow.State.Stage);
    }

    [Fact]
    public async Task CancellingWhileAVerifiedCacheIsBeingCheckedPublishesTheTerminalCancelledState()
    {
        // A cached release goes straight to verification, so the cancel
        // races the last pre-handoff stage instead of the download.
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        var h = New(downloaded: release, downloadedPath: path);
        void CancelWhileVerifying(UpdateFlowState state)
        {
            if (state.Stage == UpdateFlowStage.Verifying) h.Flow.Cancel();
        }
        h.Flow.StateChanged += CancelWhileVerifying;

        Assert.True(await h.Flow.StartAsync());

        // Cancel landed before the handoff boundary, so nothing ran.
        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Downloads);
        Assert.Empty(h.Purged);
        Assert.Equal(UpdateFlowStage.Failed, h.Flow.State.Stage);
        Assert.False(h.Flow.State.Busy);
        Assert.True(h.Flow.State.CanRetry);
        Assert.False(h.Flow.IsBusy);
        // The verified cache survives a cancel.
        Assert.True(File.Exists(path));

        h.Flow.StateChanged -= CancelWhileVerifying;
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.HandedOff);
        Assert.Empty(h.Downloads);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, h.Flow.State.Stage);
    }

    [Fact]
    public async Task CancelSignalThatUnwindsTheAttemptInlineStillEndsOnATerminalRetryableState()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        // A transport whose download completes from inside its own
        // cancellation callback, on a task that allows inline
        // continuations. The whole attempt therefore unwinds on the
        // thread that called Cancel - the interleaving that used to
        // publish the terminal cancelled state before Cancel published
        // its own Cancelling, leaving the UI on a non-retryable label
        // with a free attempt slot behind it.
        using var registered = new ManualResetEventSlim(false);
        h.DownloadBody = (target, token) =>
        {
            var completing = new TaskCompletionSource<string>();
            token.Register(() => completing.TrySetResult(WriteInstaller(target.Release)));
            registered.Set();
            return completing.Task;
        };

        var attempt = h.Flow.StartAsync();
        Assert.True(registered.Wait(TimeSpan.FromSeconds(30)));
        // Cancelled from a thread that carries no SynchronizationContext,
        // so the token callback and the transport continuation it
        // unblocks really do run inline on this call.
        Assert.True(await Task.Run(() => h.Flow.Cancel()));
        var finished = await Task.WhenAny(attempt, Task.Delay(TimeSpan.FromSeconds(30)));
        Assert.Same(attempt, finished);
        await attempt;

        // Cancelling is published first and the terminal cancelled state
        // last, so the owner is never left waiting on the stale label.
        var stages = Stages;
        Assert.Equal(UpdateFlowStage.Cancelling, stages[^2]);
        Assert.Equal(UpdateFlowStage.Failed, stages[^1]);
        Assert.Equal(UpdateFlowCoordinator.CancelledMessage, h.Flow.State.Message);
        Assert.Equal(1, stages.Count(s => s == UpdateFlowStage.Cancelling));
        Assert.True(h.Flow.State.CanRetry);
        Assert.False(h.Flow.State.Busy);
        Assert.False(h.Flow.IsBusy);
        // Nothing from the abandoned attempt ran.
        Assert.Empty(h.Recorded);
        Assert.Empty(h.HandedOff);

        // A duplicate cancel finds nothing left to cancel, and the
        // freed slot really admits a retry.
        Assert.False(h.Flow.Cancel());
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.HandedOff);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, h.Flow.State.Stage);
    }

    [Fact]
    public async Task CancelAfterTheHandoffBoundaryIsRefusedAndClaimsNothing()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));
        bool boundaryCancelRefused = false;
        void CancelAtHandoff(UpdateFlowState state)
        {
            if (state.Stage != UpdateFlowStage.HandingOff) return;
            boundaryCancelRefused = !h.Flow.Cancel();
        }
        h.Flow.StateChanged += CancelAtHandoff;

        Assert.True(await h.Flow.StartAsync());

        // The worker may already be committed, so nothing is claimed
        // about the install and the terminal state is not "unchanged".
        Assert.True(boundaryCancelRefused);
        Assert.Single(h.HandedOff);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, h.Flow.State.Stage);
        Assert.False(h.Flow.State.CanRetry);
        Assert.DoesNotContain("unchanged", h.Flow.State.Message!, StringComparison.OrdinalIgnoreCase);
        Assert.Empty(h.Purged);
        // Once the attempt is over there is nothing left to cancel.
        Assert.False(h.Flow.Cancel());
    }

    [Fact]
    public void CancelWithNothingInFlightIsRefusedAndPublishesNothing()
    {
        var h = New();
        Assert.False(h.Flow.Cancel());
        Assert.Empty(_states);
        Assert.Equal(UpdateFlowState.Idle, h.Flow.State);
    }

    [Fact]
    public async Task BusyMachineBlocksBeforeAnyDownloadAndKeepsTheCache()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        var h = New(downloaded: release, downloadedPath: path);
        h.Busy = new DashboardBusyReport(DashboardBusyReason.SteamvrBusy, new[] { "vrserver.exe" });

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Blocked, Last.Stage);
        Assert.Empty(h.Downloads);
        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Purged);
        Assert.Contains("SteamVR", Last.Message!);
        Assert.Contains("vrserver.exe", Last.Message!);
        // A blocked attempt is a plain "again once you close it".
        Assert.True(Last.CanRetry);
        // Explicit Update after the busy state clears resumes at
        // verify/handoff with the verified cache.
        h.Busy = DashboardBusyReport.Idle();
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.HandedOff);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, Last.Stage);
    }

    [Fact]
    public async Task PendingPairingRequestDefersTheAttemptWithoutLosingTheCache()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        var h = New(downloaded: release, downloadedPath: path);
        h.BlockReason = "A headset pairing request is waiting for a decision.";

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Blocked, Last.Stage);
        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Purged);
        Assert.True(File.Exists(path));

        h.BlockReason = "";
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.HandedOff);
    }

    [Fact]
    public async Task BusyOnRecheckBeforeHandoffStopsTheInstallAndKeepsTheCache()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = (target, _) =>
        {
            var path = WriteInstaller(target.Release);
            // The user starts SteamVR while the bytes were moving.
            h.Busy = new DashboardBusyReport(DashboardBusyReason.SteamvrBusy, new[] { "vrcompositor.exe" });
            return Task.FromResult(path);
        };

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Blocked, Last.Stage);
        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Purged);
        Assert.Single(h.Recorded);
        Assert.Equal("0.1.0.10", h.Flow.State.Version);
        Assert.True(File.Exists(h.Recorded[0].CachedFilePath!));
    }

    [Fact]
    public async Task DamagedCacheIsPurgedAndTheRetryDownloadsAgain()
    {
        // Metadata claims the good digest; the bytes on disk are then
        // damaged, so only the execution-boundary re-verify can catch
        // it. That is the case that must purge rather than keep.
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        File.WriteAllBytes(path, Enumerable.Repeat((byte)'Z', (int)release.Windows.Bytes).ToArray());
        var h = New(downloaded: release, downloadedPath: path);

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Failed, Last.Stage);
        Assert.Single(h.Purged);
        Assert.Empty(h.HandedOff);
        Assert.True(Last.CanRetry);
    }

    [Fact]
    public async Task HandoffFailureKeepsTheVerifiedCacheAndRetrySkipsRedownload()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));
        // The worker started but never accepted the install signal.
        h.HandoffOutcome = new UpdateHandoffOutcome(true, true, false, "job.json", null);

        Assert.True(await h.Flow.StartAsync());
        Assert.Equal(UpdateFlowStage.Failed, Last.Stage);
        Assert.Single(h.Downloads);
        Assert.Empty(h.Purged);

        // Retry re-enters at verify/handoff against the retained cache.
        h.HandoffOutcome = new UpdateHandoffOutcome(true, true, true, "job.json", null);
        Assert.True(await h.Flow.StartAsync());
        Assert.Single(h.Downloads);
        Assert.Equal(2, h.HandedOff.Count);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, Last.Stage);
    }

    [Fact]
    public async Task PermissionRefusalFromWindowsIsAFailureNotASuccess()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));
        h.HandoffOutcome = UpdateHandoffOutcome.Failed("the installer was declined at the system prompt");

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Failed, Last.Stage);
        Assert.Contains("declined", Last.Message!);
        Assert.Empty(h.Purged);
    }

    [Fact]
    public async Task NothingToInstallIsReportedWithoutAnAttempt()
    {
        var h = New();
        h.LastSuccess = DateTime.UtcNow;

        Assert.True(await h.Flow.StartAsync());

        Assert.Equal(UpdateFlowStage.Failed, Last.Stage);
        Assert.Empty(h.Downloads);
        Assert.Empty(h.HandedOff);
        Assert.False(Last.CanRetry);
    }

    [Fact]
    public async Task DisposeCancelsTheInFlightTokenAndTheAttemptFinishesInsteadOfHanging()
    {
        var release = Release("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = async (target, token) =>
        {
            await Task.Delay(Timeout.Infinite, token).ConfigureAwait(false);
            return WriteInstaller(target.Release);
        };

        var attempt = h.Flow.StartAsync();
        await h.DownloadEntered.Task;
        h.Flow.Dispose();

        // A closing manager cancels the work it started, so the attempt
        // completes on a bounded wait instead of blocking on a token
        // that was never cancelled.
        var finished = await Task.WhenAny(attempt, Task.Delay(TimeSpan.FromSeconds(10)));
        Assert.Same(attempt, finished);
        await attempt;
        Assert.True(h.LastDownloadToken.IsCancellationRequested);
        Assert.Empty(h.HandedOff);
        Assert.Empty(h.Recorded);
        Assert.False(h.Flow.IsBusy);
        Assert.False(await h.Flow.StartAsync());
        // Dispose publishes nothing on its way out: the last state the
        // owner ever saw is the pre-dispose download stage.
        Assert.Equal(UpdateFlowStage.Downloading, _states[^1].Stage);
    }

    [Fact]
    public async Task ProgressReportsRealBytesAndNeverInventsAVerificationPercent()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1, bytes: 4096);
        var h = New(available: release);
        h.DownloadBody = (target, _) => Task.FromResult(WriteInstaller(target.Release));

        await h.Flow.StartAsync();

        var downloading = _states.Where(s => s.Stage == UpdateFlowStage.Downloading).ToList();
        Assert.Contains(downloading, s => s.BytesDone == 1024 && s.BytesTotal == 4096 && !s.Indeterminate);
        Assert.All(downloading, s => Assert.True(s.HasDeterminateProgress));

        // Verification has no honest percentage, so it is reported as
        // indeterminate instead of a fabricated number.
        var verifying = _states.Single(s => s.Stage == UpdateFlowStage.Verifying);
        Assert.True(verifying.Indeterminate);
        Assert.False(verifying.HasDeterminateProgress);
        Assert.Equal(0, verifying.BytesTotal);
    }

    [Fact]
    public async Task ConcurrentClickWhileAnAttemptIsLiveIsIgnored()
    {
        var release = Release("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var h = New(available: release);
        h.DownloadBody = async (target, token) =>
        {
            await Task.Delay(Timeout.Infinite, token).ConfigureAwait(false);
            return WriteInstaller(target.Release);
        };

        var first = h.Flow.StartAsync();
        await h.DownloadEntered.Task;
        Assert.False(await h.Flow.StartAsync());

        h.Flow.Cancel();
        await first;
        Assert.Single(h.Downloads);
    }

    [Fact]
    public void NewerAvailableMetadataWinsOverAnOlderCache()
    {
        var cached = Release("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, "cached.exe");
        File.WriteAllBytes(path, new byte[16]);
        var newer = Release("0.1.0.11", SignedRelease.CurrentSequence + 2);
        var snap = new UpdateRepository.Snapshot(
            newer, new byte[] { 1 }, new byte[] { 2 },
            cached, new byte[] { 3 }, new byte[] { 4 }, path,
            DateTime.MinValue, DateTime.MinValue, null, false);

        var target = UpdateFlowCoordinator.PinTarget(snap);

        // One click must target one install: the newer release on offer
        // wins, so the owner cannot end up holding two different
        // installers and choosing between them later.
        Assert.NotNull(target);
        Assert.Equal("0.1.0.11", target!.Version);
        Assert.False(target.HasCachedFile);
        Assert.Equal(new byte[] { 1 }, target.ManifestBytes);
        Assert.Equal(new byte[] { 2 }, target.SignatureBytes);
    }

    [Fact]
    public async Task ACachedCopyOfTheNewestReleaseIsInstalledWithoutDownloadingAgain()
    {
        var release = ReleasableWithInstaller("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var path = Path.Combine(_dir, release.Windows.Filename);
        var h = New(available: release, downloaded: release, downloadedPath: path);

        Assert.True(await h.Flow.StartAsync());

        Assert.Empty(h.Downloads);
        Assert.Single(h.HandedOff);
        Assert.Equal(path, h.HandedOff[0].CachedFilePath);
        Assert.Equal(UpdateFlowStage.AwaitingSystem, Last.Stage);
    }

    [Fact]
    public void PinnedTargetCopiesTheSignedBytesSoALaterSnapshotMutationCannotReachIt()
    {
        var release = Release("0.1.0.10", SignedRelease.CurrentSequence + 1);
        var manifest = new byte[] { 1, 2, 3 };
        var signature = new byte[] { 4, 5, 6 };
        var snap = new UpdateRepository.Snapshot(
            release, manifest, signature, null, null, null, null,
            DateTime.MinValue, DateTime.MinValue, null, false);

        var target = UpdateFlowCoordinator.PinTarget(snap)!;
        manifest[0] = 99;
        signature[0] = 99;

        // The attempt is pinned at click: a snapshot whose buffers are
        // reused for the next check cannot rewrite the target's signed
        // bytes while the attempt is still running.
        Assert.Equal(new byte[] { 1, 2, 3 }, target.ManifestBytes);
        Assert.Equal(new byte[] { 4, 5, 6 }, target.SignatureBytes);
    }

    [Fact]
    public void AnInstalledOrOlderReleaseIsNeverPinned()
    {
        Assert.Null(UpdateFlowCoordinator.PinTarget(Empty()));
        var older = Release(SignedRelease.CurrentVersion, SignedRelease.CurrentSequence - 1);
        Assert.Null(UpdateFlowCoordinator.PinTarget(WithAvailable(older)));

        // A cached copy of the installed release is not an install
        // target either: the click must not reinstall what is running.
        var installed = Release(SignedRelease.CurrentVersion, SignedRelease.CurrentSequence);
        var path = Path.Combine(_dir, "installed.exe");
        File.WriteAllBytes(path, new byte[16]);
        Assert.Null(UpdateFlowCoordinator.PinTarget(new UpdateRepository.Snapshot(
            null, null, null, installed, new byte[] { 3 }, new byte[] { 4 }, path,
            DateTime.MinValue, DateTime.MinValue, null, false)));
    }

    [Fact]
    public void HandoffFailureReasonNamesTheUnreadinessInsteadOfAnEmptyString()
    {
        Assert.Contains("did not start", UpdateHandoffOutcome.Failed("").FailureReason);
        Assert.Contains("readiness", new UpdateHandoffOutcome(true, false, false, null, null).FailureReason);
        Assert.Contains("install signal", new UpdateHandoffOutcome(true, true, false, null, null).FailureReason);
    }

    // ---- The guard the App re-evaluates on the UI thread ---------------
    // The coordinator and MainForm call this same decision, so these
    // are the real gates: a cancelled token, an owner-visible reason,
    // and a busy machine each have to refuse the install without
    // stopping anything.

    [Fact]
    public void TheHandoffGuardRefusesAnInstallOnceTheAttemptIsCancelled()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        var blocked = UpdateHandoffGuard.Block(DashboardBusyReport.Idle(), "", cts.Token);
        Assert.NotNull(blocked);
        Assert.Contains("cancelled", blocked!, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void TheHandoffGuardNamesTheOwnerVisibleReasonBeforeLookingAtBusyApps()
    {
        var blocked = UpdateHandoffGuard.Block(
            new DashboardBusyReport(DashboardBusyReason.SteamvrBusy, new[] { "vrserver.exe" }),
            "A headset pairing request is waiting for a decision.", CancellationToken.None);
        Assert.Equal("A headset pairing request is waiting for a decision.", blocked);
    }

    [Fact]
    public void TheHandoffGuardNamesEveryBusyApplicationAndStopsNothing()
    {
        var blocked = UpdateHandoffGuard.Block(
            new DashboardBusyReport(DashboardBusyReason.SteamvrBusy, new[] { "vrserver.exe", "vrcompositor.exe" }),
            "", CancellationToken.None);
        Assert.NotNull(blocked);
        Assert.Contains("SteamVR", blocked!);
        Assert.Contains("vrserver.exe", blocked!);
        Assert.Contains("vrcompositor.exe", blocked!);
        Assert.Contains("Update again", blocked!);

        Assert.Null(UpdateHandoffGuard.Block(DashboardBusyReport.Idle(), "", CancellationToken.None));
        Assert.Contains("check", UpdateHandoffGuard.Block(
            new DashboardBusyReport(DashboardBusyReason.InspectionFailed, Array.Empty<string>()),
            "", CancellationToken.None)!, StringComparison.OrdinalIgnoreCase);
    }
}
