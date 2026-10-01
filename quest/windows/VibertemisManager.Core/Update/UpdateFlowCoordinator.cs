// One-click update orchestration for the Windows manager.
//
// The UI used to expose three separate controls (Check / Download /
// Install). That forced two redundant in-app confirmations for one
// user intent and let the release target drift between the two
// clicks, because every stage re-read the repository snapshot. This
// coordinator owns the whole attempt instead:
//
//   explicit Update click
//     -> pin an immutable target: the newest signed release on offer,
//        preferring a verified cache only when it holds that same
//        asset (digest + manifest + signature + cached file), so one
//        click can never leave two different installs on the machine
//     -> download, unless the pinned target is already cached
//     -> re-verify the cached bytes at the execution boundary
//     -> cross the irreversible handoff boundary
//     -> hand off to the existing UpdateWorker
//
// Contract notes that the WinForms layer depends on:
//   - The target is pinned at click. Newer background metadata may
//     land at any time and is ignored for the rest of the attempt.
//   - Nothing installs itself. The OS consent surface (UAC) plus the
//     system installer remains the only installation consent; the
//     user-visible Update click is the in-app authorization.
//   - Dispatching the worker is NOT success. The attempt ends in
//     AwaitingSystem until the worker writes a verified outcome.
//   - Cancel does not free the attempt slot. It publishes a
//     non-retryable Cancelling state, invalidates the continuation,
//     and keeps the slot until the interrupted transport has actually
//     unwound; only then is the terminal cancelled state published and
//     a retry admitted. That is what stops a retry from downloading to
//     the same destination while the cancelled transport is still
//     writing it. Every later side effect is generation-guarded too,
//     so an uncooperative download can never publish state, record a
//     cache, purge a cache, or launch the worker after a cancel.
//   - Every state publication sets the state and raises its event
//     under the same gate, and Cancelling is published and observed
//     before the token is signalled. A transport that unwinds inline
//     on the cancelling thread therefore cannot publish the terminal
//     cancelled state ahead of the Cancelling that preceded it, and a
//     stale state can never be handed to the UI after a newer one.
//   - The handoff boundary is irreversible. Once crossed, Cancel is
//     refused rather than reporting a change that cannot happen,
//     because the worker may already have been committed.
//   - A verified cache survives cancel, busy, and failure. Only a
//     cache whose bytes fail verification is purged.
//   - Busy (SteamVR / ALVR Dashboard / a pending pairing request)
//     blocks the attempt at the start and is rechecked immediately
//     before handoff. Nothing external is ever stopped for the user;
//     the attempt reports what to close and keeps the cache.
//
// The type is deliberately free of WinForms types: every side
// effect is an injected delegate, so the whole state machine is
// testable with deferred callbacks and no filesystem or network.
using System;
using System.IO;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.ALVR;

namespace VibertemisManager.Core.Update;

public enum UpdateFlowStage
{
    /// <summary>No attempt in flight and nothing to do.</summary>
    Idle,
    /// <summary>Resolving which release this click targets.</summary>
    Choosing,
    /// <summary>Transport is writing the pinned installer to cache.</summary>
    Downloading,
    /// <summary>Re-hashing cached bytes before the handoff.</summary>
    Verifying,
    /// <summary>
    /// A cancel was accepted and the interrupted transport is still
    /// unwinding. The attempt slot is deliberately still held: a retry
    /// must not start writing the same destination file while the
    /// cancelled transport may still hold it.
    /// </summary>
    Cancelling,
    /// <summary>Worker job is being launched and is reading it.</summary>
    HandingOff,
    /// <summary>
    /// The worker owns the install and the manager is closing.
    /// Not a success claim: the system installer has not reported yet.
    /// </summary>
    AwaitingSystem,
    /// <summary>Verified cache is ready but the machine is busy.</summary>
    Blocked,
    /// <summary>The attempt failed. A retry re-enters the right stage.</summary>
    Failed,
}

public sealed record UpdateFlowState(
    UpdateFlowStage Stage,
    string? Version,
    string? Message,
    long BytesDone,
    long BytesTotal,
    bool Indeterminate,
    bool CanRetry,
    bool CanCancel,
    bool Busy)
{
    public static UpdateFlowState Idle { get; } = new(UpdateFlowStage.Idle, null, null, 0, 0, false, false, false, false);

    /// <summary>True while this stage reports real byte counts.</summary>
    public bool HasDeterminateProgress => !Indeterminate && BytesTotal > 0;
}

/// <summary>
/// Immutable identity of one update attempt. Built once, at click
/// time, from a repository snapshot, and never re-read afterwards.
/// The signed bytes are copied on construction so a later mutation of
/// the snapshot cannot reach into a live attempt.
/// </summary>
public sealed record UpdateTarget
{
    public SignedRelease Release { get; }
    public byte[] ManifestBytes { get; }
    public byte[] SignatureBytes { get; }
    public string? CachedFilePath { get; }

    public UpdateTarget(SignedRelease release, byte[] manifestBytes, byte[] signatureBytes, string? cachedFilePath)
    {
        Release = release ?? throw new ArgumentNullException(nameof(release));
        ManifestBytes = manifestBytes is null
            ? throw new ArgumentNullException(nameof(manifestBytes))
            : (byte[])manifestBytes.Clone();
        SignatureBytes = signatureBytes is null
            ? throw new ArgumentNullException(nameof(signatureBytes))
            : (byte[])signatureBytes.Clone();
        CachedFilePath = cachedFilePath;
    }

    public string Version => Release.Version;
    public long Sequence => Release.Sequence;
    public bool HasCachedFile => !string.IsNullOrEmpty(CachedFilePath) && File.Exists(CachedFilePath);

    /// <summary>Same release identity, different resolved file location.</summary>
    public UpdateTarget WithCachedFile(string? cachedFilePath)
        => new(Release, ManifestBytes, SignatureBytes, cachedFilePath);

    /// <summary>
    /// True when <paramref name="other"/> is the same release as this
    /// target, by the signed asset identity rather than by object
    /// reference or version string alone.
    /// </summary>
    public bool Matches(SignedRelease? other)
        => other is not null
           && Sequence == other.Sequence
           && string.Equals(Version, other.Version, StringComparison.Ordinal)
           && string.Equals(Release.Windows.Sha256, other.Windows.Sha256, StringComparison.OrdinalIgnoreCase);
}

/// <summary>Result of launching the update worker.</summary>
public sealed record UpdateHandoffOutcome(bool WorkerStarted, bool ReadySignaled, bool CommitSignaled, string? JobPath, string? Error)
{
    public static UpdateHandoffOutcome Failed(string error) => new(false, false, false, null, error);

    public bool Succeeded => WorkerStarted && ReadySignaled && CommitSignaled;

    /// <summary>User-facing reason the handoff did not complete.</summary>
    public string FailureReason => Error switch
    {
        null or "" when WorkerStarted && !ReadySignaled => "the update worker did not signal readiness in time",
        null or "" when !WorkerStarted => "the update worker did not start",
        null or "" => "the update worker did not accept the install signal",
        _ => Error!,
    };
}

/// <summary>
/// The one decision every handoff site has to make before it stops
/// the companion: is this machine allowed to install right now?
/// Shared so the flow's own gate and the UI-thread recheck cannot
/// drift apart.
/// </summary>
public static class UpdateHandoffGuard
{
    /// <summary>
    /// Non-null when the install must not start. Never stops anything:
    /// the user closes their own apps.
    /// </summary>
    public static string? Block(DashboardBusyReport busy, string? blockReason, CancellationToken cancellation)
    {
        if (cancellation.IsCancellationRequested)
            return "The update was cancelled before the installer started.";
        if (!string.IsNullOrEmpty(blockReason)) return blockReason!;
        if (!busy.IsBusy) return null;
        var names = busy.ActiveProcessNames.Count > 0
            ? " (" + string.Join(", ", busy.ActiveProcessNames) + ")"
            : "";
        return "Close " + Describe(busy.Reason) + names + ", then choose Update again.";
    }

    public static string Describe(DashboardBusyReason reason) => reason switch
    {
        DashboardBusyReason.OtherDashboardBusy => "ALVR Dashboard",
        DashboardBusyReason.SteamvrBusy => "SteamVR and ALVR Dashboard",
        DashboardBusyReason.AnotherManagerBusy => "the other Vibertemis manager",
        DashboardBusyReason.InspectionFailed => "the running-app check (it could not be completed)",
        _ => "the busy applications",
    };
}

public sealed class UpdateFlowCoordinator : IDisposable
{
    private readonly Func<UpdateRepository.Snapshot> _snapshot;
    private readonly Func<DashboardBusyReport> _busy;
    private readonly Func<string> _blockReason;
    private readonly Func<UpdateTarget, CancellationToken, IProgress<UpdateProgress>?, Task<string>> _download;
    private readonly Action<UpdateTarget, string> _recordDownloaded;
    private readonly Action<UpdateTarget> _purgeDownloaded;
    private readonly Func<UpdateTarget, CancellationToken, Task<UpdateHandoffOutcome>> _handoff;
    private readonly object _gate = new();

    private CancellationTokenSource? _attempt;
    private long _generation;
    private bool _handoffBoundary;
    private bool _cancelRequested;
    private bool _disposed;

    /// <summary>The one user-facing sentence for a cancelled attempt.</summary>
    internal const string CancelledMessage =
        "Update cancelled. Your current installation is unchanged and any downloaded update is kept.";

    public UpdateFlowState State { get; private set; } = UpdateFlowState.Idle;

    /// <summary>The generation that owns <see cref="State"/>.</summary>
    public long CurrentGeneration { get { lock (_gate) return _generation; } }

    /// <summary>
    /// True once this attempt has crossed the point where the worker
    /// may already have been committed. Cancel is refused from here.
    /// </summary>
    public bool HandoffCommitted { get { lock (_gate) return _handoffBoundary; } }

    /// <summary>Raised on the caller's thread for every state change.</summary>
    public event Action<UpdateFlowState>? StateChanged;

    /// <summary>Raised for operator-facing log lines.</summary>
    public event Action<string>? Notice;

    public UpdateFlowCoordinator(
        Func<UpdateRepository.Snapshot> snapshot,
        Func<DashboardBusyReport> busy,
        Func<string> blockReason,
        Func<UpdateTarget, CancellationToken, IProgress<UpdateProgress>?, Task<string>> download,
        Action<UpdateTarget, string> recordDownloaded,
        Action<UpdateTarget> purgeDownloaded,
        Func<UpdateTarget, CancellationToken, Task<UpdateHandoffOutcome>> handoff)
    {
        _snapshot = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
        _busy = busy ?? throw new ArgumentNullException(nameof(busy));
        _blockReason = blockReason ?? throw new ArgumentNullException(nameof(blockReason));
        _download = download ?? throw new ArgumentNullException(nameof(download));
        _recordDownloaded = recordDownloaded ?? throw new ArgumentNullException(nameof(recordDownloaded));
        _purgeDownloaded = purgeDownloaded ?? throw new ArgumentNullException(nameof(purgeDownloaded));
        _handoff = handoff ?? throw new ArgumentNullException(nameof(handoff));
    }

    public bool IsBusy
    {
        get { lock (_gate) return _attempt is not null; }
    }

    /// <summary>
    /// The single user action. Resolves the target, then runs
    /// download -> verify -> handoff without further prompts.
    /// Returns true when an attempt actually started.
    /// </summary>
    public async Task<bool> StartAsync()
    {
        CancellationTokenSource cts;
        long generation;
        lock (_gate)
        {
            if (_disposed || _attempt is not null) return false;
            generation = ++_generation;
            _handoffBoundary = false;
            _cancelRequested = false;
            cts = new CancellationTokenSource();
            _attempt = cts;
        }

        try
        {
            // Resolve the target from the snapshot the user acted on.
            // Everything below uses only this object, so a concurrent
            // background check publishing a newer release cannot move
            // the attempt to a different file.
            UpdateTarget? target;
            try
            {
                target = PinTarget(_snapshot());
            }
            catch (Exception ex)
            {
                Fail(generation, "Update unavailable: " + ex.Message, retryable: false);
                return true;
            }
            if (target is null)
            {
                Fail(generation, "No verified update is ready to install.", retryable: false);
                return true;
            }

            Publish(generation, new UpdateFlowState(UpdateFlowStage.Choosing, target.Version,
                "Updating to " + target.Version + "…", 0, 0, true, false, true, true));

            // Busy is checked before any work so the user is not made
            // to wait through a download they cannot finish.
            var block = Blocked(cts.Token);
            if (block is not null)
            {
                // A cancel that arrived during the check owns the
                // outcome instead of this block reason.
                if (!IsStale(generation)) Publish(generation, Blocked(target, block));
                return true;
            }

            string file;
            if (target.HasCachedFile)
            {
                Notice?.Invoke("Update " + target.Version + " is already downloaded; verifying it.");
                file = target.CachedFilePath!;
            }
            else
            {
                var downloaded = await DownloadAsync(target, generation, cts.Token).ConfigureAwait(false);
                if (downloaded is null) return true; // a terminal state is already published
                file = downloaded;
            }

            // Everything from here on refers to the pinned release at
            // its resolved on-disk location. The identity is still the
            // one chosen at click; only the path is filled in.
            var resolved = target.WithCachedFile(file);

            Publish(generation, new UpdateFlowState(UpdateFlowStage.Verifying, target.Version,
                "Verifying " + target.Version + "…", 0, 0, true, false, true, true));
            if (!TryVerify(generation, resolved)) return true;

            // Recheck immediately before handoff: the user may have
            // started SteamVR or a pairing request may have arrived
            // while the bytes were moving.
            block = Blocked(cts.Token);
            if (block is not null)
            {
                if (!IsStale(generation)) Publish(generation, Blocked(resolved, block));
                return true;
            }

            // The irreversible boundary. Crossing it happens under the
            // same lock Cancel uses, so exactly one of the two wins:
            // a cancel that got there first stops the launch below, and
            // a boundary that got there first makes Cancel report that
            // nothing was cancelled.
            lock (_gate)
            {
                if (_disposed || _cancelRequested || generation != _generation) return true;
                _handoffBoundary = true;
            }
            Publish(generation, new UpdateFlowState(UpdateFlowStage.HandingOff, target.Version,
                "Opening the installer for " + target.Version + "…", 0, 0, true, false, false, true));

            UpdateHandoffOutcome outcome;
            try
            {
                outcome = await _handoff(resolved, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Past the boundary the worker may already be
                // committed, so this deliberately does not claim the
                // installation was left unchanged.
                Fail(generation, "The installer handoff was interrupted. Check whether Windows completed the update before choosing Update again.",
                    retryable: true);
                return true;
            }
            catch (Exception ex)
            {
                Fail(generation, "Update handoff failed: " + ex.Message, retryable: true);
                return true;
            }

            if (IsStale(generation)) return true;
            if (!outcome.Succeeded)
            {
                // Nothing installed. The verified cache stays so a
                // retry does not download again.
                Fail(generation, "Update " + target.Version + " was not applied: " + outcome.FailureReason
                    + ". The downloaded update is still ready.", retryable: true);
                return true;
            }

            // The worker has the job and the OS owns the install now.
            // This is deliberately NOT reported as a completed update.
            Publish(generation, new UpdateFlowState(UpdateFlowStage.AwaitingSystem, target.Version,
                "Updating to " + target.Version + ": Windows is running the installer. "
                + "The manager will close and pairing is kept.", 0, 0, false, false, false, false));
            return true;
        }
        finally
        {
            lock (_gate)
            {
                // Only the attempt that still owns the slot clears it,
                // and it does so only now that the transport it
                // interrupted has unwound. The terminal cancelled state
                // is published after that cleanup so the UI is never
                // offered a retry while the old writer could still be
                // touching the same file.
                if (ReferenceEquals(_attempt, cts))
                {
                    // The intent is read BEFORE it is cleared. The
                    // transport can unwind after Cancel recorded the
                    // intent but before the token was actually
                    // signalled, and that attempt still owes the owner a
                    // terminal cancelled state - reading only the token
                    // would free the slot while the UI still showed a
                    // non-retryable Cancelling label.
                    bool cancelIntent = _cancelRequested || cts.IsCancellationRequested;
                    _attempt = null;
                    _handoffBoundary = false;
                    _cancelRequested = false;
                    // A disposed flow publishes nothing: a closing
                    // manager must not raise a state change on its way
                    // out.
                    if (cancelIntent && !_disposed)
                    {
                        Emit(new UpdateFlowState(UpdateFlowStage.Failed, State.Version,
                            CancelledMessage, 0, 0, false, true, false, false));
                    }
                }
            }
            cts.Dispose();
        }
    }

    /// <summary>
    /// Accepts a cancel of the in-flight attempt. The attempt slot is
    /// NOT released here: it is released by the attempt itself once its
    /// interrupted transport has unwound, and that is when the terminal
    /// cancelled state becomes visible and a retry is admitted. Until
    /// then the published state is a non-retryable Cancelling so no
    /// second attempt can start writing the same destination.
    /// Returns false when there was nothing to cancel or the handoff
    /// boundary has already been crossed, in which case nothing is
    /// claimed.
    /// </summary>
    public bool Cancel()
    {
        CancellationTokenSource cts;
        lock (_gate)
        {
            // Already unwinding: the owner already has the Cancelling
            // label, so a second call publishes nothing and claims
            // nothing.
            if (_disposed || _handoffBoundary || _attempt is null || _cancelRequested) return false;
            cts = _attempt;
            _cancelRequested = true;
            // Linearized publication: the state, its log line and its
            // event are all emitted while the gate is held, and
            // strictly BEFORE the token is signalled. The other order
            // lets an inline cancellation continuation unwind the whole
            // attempt and publish the terminal cancelled state first,
            // after which this stale Cancelling would strand the UI on a
            // non-retryable label with a free attempt slot behind it.
            Emit(new UpdateFlowState(UpdateFlowStage.Cancelling, State.Version,
                "Cancelling the update\u2026", 0, 0, true, false, false, true));
        }
        // Signalling happens outside the gate so the interrupted
        // transport can take it while unwinding.
        try { cts.Cancel(); } catch (ObjectDisposedException) { }
        return true;
    }

    /// <summary>
    /// Cancels whatever the attempt was doing so a closing manager
    /// never resumes an update later, and drops every later callback.
    /// The token is cancelled directly rather than through
    /// <see cref="Cancel"/>: this path must not report a user-visible
    /// cancellation, and it must work after the disposed flag is set.
    /// </summary>
    public void Dispose()
    {
        CancellationTokenSource? cts;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _cancelRequested = true;
            cts = _attempt;
        }
        try { cts?.Cancel(); } catch (ObjectDisposedException) { }
    }

    /// <summary>
    /// Picks the release this click targets: the newest release we can
    /// legitimately install, and never a downgrade. A verified cache is
    /// only used when it is that newest release, so one Update click
    /// can never leave the owner with two different installs to choose
    /// between; the download is skipped only when the cached file is
    /// the very same signed asset as the newest release on offer.
    /// </summary>
    internal static UpdateTarget? PinTarget(UpdateRepository.Snapshot snapshot)
    {
        UpdateTarget? cached = null;
        if (snapshot.HasDownloaded
            && snapshot.Downloaded is { } downloaded
            && !string.IsNullOrEmpty(snapshot.DownloadedFilePath)
            && snapshot.DownloadedManifestBytes is { } manifest
            && snapshot.DownloadedSignatureBytes is { } signature
            && UpdateRepository.IsNewerThanInstalled(downloaded))
        {
            cached = new UpdateTarget(downloaded, manifest, signature, snapshot.DownloadedFilePath);
        }
        UpdateTarget? available = null;
        if (snapshot.HasAvailable
            && snapshot.Available is { } release
            && snapshot.AvailableManifestBytes is { } aManifest
            && snapshot.AvailableSignatureBytes is { } aSignature
            && UpdateRepository.IsNewerThanInstalled(release))
        {
            available = new UpdateTarget(release, aManifest, aSignature, null);
        }
        if (available is null) return cached;
        // The same signed asset: keep the verified cache so the click
        // does not re-download bytes the machine already has.
        if (cached is not null && cached.Matches(available.Release)) return cached;
        // Anything else: newer metadata wins so the owner never
        // installs a stale file while a newer release is on offer.
        return available;
    }

    private async Task<string?> DownloadAsync(UpdateTarget target, long generation, CancellationToken token)
    {
        // The signed asset already declares the exact size, so the
        // download starts determinate rather than faking a marquee.
        Publish(generation, new UpdateFlowState(UpdateFlowStage.Downloading, target.Version,
            "Downloading " + target.Version + "…", 0, target.Release.Windows.Bytes, false, false, true, true));
        var progress = new InlineProgress<UpdateProgress>(p => ReportProgress(generation, target, p));
        string file;
        try
        {
            file = await _download(target, token, progress).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            Fail(generation, "Update cancelled. Your current installation is unchanged.", retryable: true);
            return null;
        }
        catch (Exception ex)
        {
            Fail(generation, "Update " + target.Version + " could not be downloaded: " + ex.Message
                + ". Nothing was changed.", retryable: true);
            return null;
        }
        // The transport may have ignored cancellation, so the
        // generation is rechecked here: an invalidated attempt must not
        // write a cache that a retry is now responsible for.
        if (IsStale(generation)) return null;

        try
        {
            _recordDownloaded(target, file);
        }
        catch (Exception ex)
        {
            Fail(generation, "Downloaded update could not be saved: " + ex.Message, retryable: true);
            return null;
        }
        if (IsStale(generation)) return null;

        Notice?.Invoke("Update " + target.Version + " downloaded.");
        return file;
    }

    /// <summary>
    /// Re-verifies cached bytes at the execution boundary. A digest
    /// mismatch means the cache is corrupt, so the cache is purged;
    /// every other failure keeps a possibly-valid cache.
    /// </summary>
    private bool TryVerify(long generation, UpdateTarget resolved)
    {
        if (IsStale(generation)) return false;
        var file = resolved.CachedFilePath ?? "";
        try
        {
            if (string.IsNullOrEmpty(file))
                throw new InvalidDataException("The verified update has no file path.");
            ReleaseClient.VerifyFile(file, resolved.Release.Windows);
            // A cache published for a different release than the one
            // pinned here is a stale-writer hazard, not a valid cache.
            var live = _snapshot();
            if (live.HasDownloaded && !resolved.Matches(live.Downloaded))
            {
                Purge(generation, resolved);
                Fail(generation, "The downloaded update did not match the release being installed. Download it again.",
                    retryable: true);
                return false;
            }
            return true;
        }
        catch (CryptographicException ex)
        {
            Purge(generation, resolved);
            Fail(generation, "The downloaded update is damaged (" + ex.Message
                + "). It was removed; download it again.", retryable: true);
            return false;
        }
        catch (Exception ex)
        {
            Fail(generation, "The downloaded update could not be verified: " + ex.Message
                + ". The downloaded update is still ready.", retryable: true);
            return false;
        }
    }

    /// <summary>Purges only while this attempt is still the owner.</summary>
    private void Purge(long generation, UpdateTarget target)
    {
        if (IsStale(generation)) return;
        _purgeDownloaded(target);
    }

    private string? Blocked(CancellationToken cancellation)
        => UpdateHandoffGuard.Block(_busy(), _blockReason(), cancellation);

    private void ReportProgress(long generation, UpdateTarget target, UpdateProgress p)
    {
        if (IsStale(generation)) return;
        if (p.Completed) return; // the verifying stage owns the tail
        // Only real byte counts are reported. A stage without a
        // known total is indeterminate rather than a made-up percent.
        Publish(generation, new UpdateFlowState(UpdateFlowStage.Downloading, target.Version,
            "Downloading " + target.Version + "…",
            p.BytesDone, p.BytesTotal, p.BytesTotal <= 0, false, true, true));
    }

    private UpdateFlowState Blocked(UpdateTarget target, string reason)
    {
        // The cache is intact, so this is a plain "try again once you
        // close it" state rather than a failure.
        return new UpdateFlowState(UpdateFlowStage.Blocked, target.Version, reason,
            0, 0, false, true, false, false);
    }

    /// <summary>
    /// True once this attempt may no longer act: it was disposed, a
    /// later attempt took the slot over, or a cancel was accepted and
    /// its terminal state is still pending the transport unwind. Every
    /// side effect is gated on this, so an interrupted transport can
    /// never publish progress, record or purge a cache, or launch the
    /// worker after the fact.
    /// </summary>
    private bool IsStale(long generation)
    {
        lock (_gate) return _disposed || _cancelRequested || generation != _generation;
    }

    private void Fail(long generation, string message, bool retryable)
    {
        lock (_gate)
        {
            if (_disposed || _cancelRequested || generation != _generation) return;
            Emit(new UpdateFlowState(UpdateFlowStage.Failed, State.Version, message,
                0, 0, false, retryable, false, false));
        }
    }

    /// <summary>
    /// Publishes only while <paramref name="generation"/> still owns
    /// the flow. This is what makes a cancelled attempt's late
    /// callbacks harmless instead of a source of stale UI state.
    /// </summary>
    private void Publish(long generation, UpdateFlowState state)
    {
        lock (_gate)
        {
            if (_disposed || generation != _generation) return;
            Emit(state);
        }
    }

    /// <summary>
    /// Sets the visible state and raises both observers while the gate
    /// is held. The state and its event are therefore one atomic step,
    /// so no publication can interleave between them and an owner can
    /// never be handed a terminal state followed by the stale state
    /// that preceded it. Reentrant by design: an observer that calls
    /// back into this flow on the same thread re-enters the gate rather
    /// than deadlocking, and the nested publication is the newer state.
    /// </summary>
    private void Emit(UpdateFlowState state)
    {
        State = state;
        if (!string.IsNullOrEmpty(state.Message)) Notice?.Invoke(state.Message);
        StateChanged?.Invoke(state);
    }

    /// <summary>
    /// Reports inline instead of posting to a captured context. The
    /// coordinator already resumes on a pool thread after a download,
    /// so a posted callback could arrive after the attempt ended; the
    /// UI layer marshals its own state changes explicitly.
    /// </summary>
    private sealed class InlineProgress<T> : IProgress<T>
    {
        private readonly Action<T> _report;
        public InlineProgress(Action<T> report) => _report = report;
        public void Report(T value) => _report(value);
    }
}
