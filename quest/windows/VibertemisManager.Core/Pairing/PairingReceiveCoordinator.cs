// PairingReceiveCoordinator is the Windows-side driver for the
// seamless-standalone Quest pairing flow.
//
// Responsibilities:
//   - Maintain a 10 s lease on the Go companion's enrollment
//     service via /pairing/admin/renew on a serial timer (every
//     ~2 s). The lease is renewed ONLY while the manager has the
//     receiving mode enabled AND the mode is not suppressed AND
//     the companion is currently running.
//   - Poll /pairing/admin/pending on the same timer. New pending
//     requests surface a single tray notification per session_id
//     (a fresh session id always notifies once); the foreground
//     panel always updates immediately so the owner can act.
//   - Honor Suppress1h: post the admin endpoint and stop renewing
//     the lease while the suppression window is active.
//   - Be safe across dialog lifecycle: lease renewal runs on a
//     background serial queue, never on the WinForms thread; the
//     approval panel can be opened from any thread.
//
// Serialisation invariants (enforced here, tested in
// PairingReceiveCoordinatorTests):
//   - TickAsync holds a SemaphoreSlim(1,1) so two timer callbacks
//     never overlap. A late callback from a previous tick that
//     fires after Dispose is cancelled before any side effect.
//   - Tray notifications fire exactly once per session_id. A new
//     pending request inside the global 60 s anti-spam cooldown
//     is muted (the cooldown throttles spam); a still-pending
//     request with the SAME session_id is never re-notified.
//     The cooldown is a clock-driven gate: a DIFFERENT session
//     whose first observation occurs after the cooldown elapses
//     becomes eligible to notify exactly once.
//   - The dispatcher surfaces EVERY snapshot from the server to
//     the UI so the panel can transition; the foreground panel
//     is always current even when the tray is silenced.
//   - Disabling the receiving mode actively closes the lease
//     (/pairing/admin/close). The close is sent BEFORE the
//     pending poll so the server-side state is correct on the
//     next snapshot. A late renew after disable cannot resurrect
//     the lease: the tick re-reads the receiving flag AFTER the
//     renew completes and POSTs /close before publishing.
//   - The companion-running flag is read as a snapshot under the
//     state lock so a stopped companion does not poll every 2 s.
//     When the companion is not running the timer is paused; the
//     UI re-arms the timer when the companion transitions back
//     to running.
//   - Errors are reported through one centralised, deduplicated
//     path. Repeated identical errors are suppressed; recovery
//     (a successful pending observation) clears the dedup key.
//   - The admin client is NOT cached across ticks. Each tick
//     constructs a fresh client via the factory and disposes it
//     in the tick's finally block. Disposal cancels the timer
//     and the in-flight HTTP call without blocking the caller;
//     the in-flight tick's finally block runs the client
//     disposal and gate release.
//   - The SemaphoreSlim and CTS are not disposed: the in-flight
//     tick may still hold them. They are reclaimed by GC; the
//     coordinator is intended to be long-lived.
using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace VibertemisManager.Core.Pairing;

public sealed record CoordinatorPending(
    [property: JsonPropertyName("open")] bool Open,
    [property: JsonPropertyName("receiving")] bool Receiving,
    [property: JsonPropertyName("suppressed")] bool Suppressed,
    [property: JsonPropertyName("suppress_until_unix")] long SuppressUntilUnix,
    [property: JsonPropertyName("session_id")] string? SessionId,
    [property: JsonPropertyName("code")] string? Code,
    [property: JsonPropertyName("state")] string State,
    [property: JsonPropertyName("expires_unix")] long Expires,
    [property: JsonPropertyName("lease_expires_unix")] long LeaseExpiresUnix,
    [property: JsonPropertyName("ttl_seconds")] long TtlSeconds,
    [property: JsonPropertyName("devices")] int Devices);

public interface IAdminClient : IDisposable
{
    Task<CoordinatorPending> SendAsync(string action, object? payload, CancellationToken cancellationToken);
}

public interface IAdminClientFactory
{
    IAdminClient Create();
}

public sealed class LoopbackAdminClient : IAdminClient
{
    private readonly LocalPairingClient _inner;
    public LoopbackAdminClient(LocalPairingClient inner) { _inner = inner; }
    public Task<CoordinatorPending> SendAsync(string action, object? payload, CancellationToken cancellationToken)
        => _inner.SendCoordinatorAsync(action, payload, cancellationToken);
    public void Dispose() => _inner.Dispose();
}

public sealed class FileSystemAdminClientFactory : IAdminClientFactory
{
    private readonly Func<string> _stateDir;
    public FileSystemAdminClientFactory(Func<string> stateDir) { _stateDir = stateDir; }
    public IAdminClient Create()
    {
        var dir = _stateDir();
        if (string.IsNullOrEmpty(dir)) throw new CompanionNotReadyException("State directory not resolved.");
        var path = System.IO.Path.Combine(dir, "state.json");
        if (!System.IO.File.Exists(path)) throw new CompanionNotReadyException("Companion state.json not yet written.");
        var client = new LocalPairingClient(dir);
        return new LoopbackAdminClient(client);
    }
}

public sealed class CompanionNotReadyException : Exception
{
    public CompanionNotReadyException(string message) : base(message) { }
}

// Thread-safe immutable snapshot read by the coordinator on every
// tick. The manager-owned running companion flag, the persisted
// receiving mode, and the persisted suppression window are read
// here so a torn read of nullable DateTime / mutable settings
// cannot reach the coordinator.
public sealed record ReceivingSnapshot(
    bool Receiving,
    bool Suppressed,
    DateTime? SuppressUntilUtc,
    bool CompanionRunning,
    DateTime AsOfUtc);

public sealed class PairingReceiveCoordinator : IDisposable
{
    public static readonly TimeSpan LeaseTickInterval = TimeSpan.FromSeconds(2);
    public static readonly TimeSpan AdminCallTimeout = TimeSpan.FromSeconds(2);
    public static readonly TimeSpan NotificationCooldown = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan LeaseFallbackAfter = TimeSpan.FromSeconds(10);

    private readonly IAdminClientFactory _factory;
    private readonly Func<ReceivingSnapshot> _snapshot;
    private readonly Action<CoordinatorPending> _onPending;
    private readonly Action<CoordinatorPending>? _onTrayNotify;
    private readonly Action<CoordinatorPending>? _onPendingStatus;
    private readonly Action<Exception> _onError;
    private readonly Func<DateTime> _clock;
    private readonly System.Threading.Timer _timer;
    private readonly CancellationTokenSource _cts = new();
    private readonly CancellationToken _ctsToken;
    private readonly SemaphoreSlim _tickGate = new(1, 1);

    private readonly object _stateLock = new();
    private string? _lastNotifiedSessionId;
    private DateTime _lastNotificationAtUtc = DateTime.MinValue;
    private DateTime _lastLeaseSeenAtUtc = DateTime.MinValue;
    private bool _disposed;
    private bool _suppressedLastTick;
    private long _generation;
    private string? _lastErrorSurfaceKey;

    public PairingReceiveCoordinator(
        IAdminClientFactory factory,
        Func<ReceivingSnapshot> snapshot,
        Action<CoordinatorPending> onPending,
        Action<CoordinatorPending>? onTrayNotify = null,
        Action<CoordinatorPending>? onPendingStatus = null,
        Action<Exception>? onError = null,
        Func<DateTime>? clock = null)
    {
        _factory = factory ?? throw new ArgumentNullException(nameof(factory));
        _snapshot = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
        _onPending = onPending ?? throw new ArgumentNullException(nameof(onPending));
        _onTrayNotify = onTrayNotify;
        _onPendingStatus = onPendingStatus;
        _onError = onError ?? (_ => { });
        _clock = clock ?? (() => DateTime.UtcNow);
        _ctsToken = _cts.Token;
        _timer = new System.Threading.Timer(OnTick, null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        lock (_stateLock)
        {
            if (_disposed) return;
            _timer.Change(TimeSpan.Zero, LeaseTickInterval);
        }
    }

    public void Stop()
    {
        lock (_stateLock)
        {
            if (_disposed) return;
            _timer.Change(Timeout.Infinite, Timeout.Infinite);
        }
    }

    private void OnTick(object? state)
    {
        if (Volatile.Read(ref _disposed)) return;
        _ = TickAsync(_ctsToken);
    }

    public async Task TickAsync(CancellationToken cancellation)
    {
        if (Volatile.Read(ref _disposed)) return;

        var gateTaken = false;
        IAdminClient? client = null;
        try
        {
            try { gateTaken = await _tickGate.WaitAsync(0, cancellation).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            if (!gateTaken) return;
            if (Volatile.Read(ref _disposed)) return;

            // Snapshot reads the running-companion flag under the
            // lock. When the companion is not running we neither
            // poll nor renew; the next timer tick retries once
            // the manager re-arms it.
            var snap = _snapshot();
            if (!snap.CompanionRunning)
            {
                SurfaceWaitingStatus();
                return;
            }

            try
            {
                client = _factory.Create();
            }
            catch (CompanionNotReadyException)
            {
                SurfaceWaitingStatus();
                return;
            }
            catch (Exception ex)
            {
                ReportError("factory:" + ex.GetType().Name, ex);
                return;
            }

            long myGeneration = Interlocked.Read(ref _generation);

            bool receivingNow = snap.Receiving;
            bool suppressedNow = snap.Suppressed;
            if (suppressedNow != _suppressedLastTick)
            {
                var suppressAction = suppressedNow ? "suppress" : "unsuppress";
                object? suppressPayload = null;
                if (suppressedNow)
                {
                    var until = snap.SuppressUntilUtc;
                    long untilUnix = until is { } u
                        ? new DateTimeOffset(DateTime.SpecifyKind(u, DateTimeKind.Utc), TimeSpan.Zero).ToUnixTimeSeconds()
                        : new DateTimeOffset(_clock().AddHours(1), TimeSpan.Zero).ToUnixTimeSeconds();
                    suppressPayload = new { until_unix = untilUnix };
                }
                try
                {
                    using var edgeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                    edgeTimeout.CancelAfter(AdminCallTimeout);
                    var s = await client.SendAsync(suppressAction, suppressPayload, edgeTimeout.Token).ConfigureAwait(false);
                    DispatchPending(s, myGeneration);
                    lock (_stateLock) _suppressedLastTick = suppressedNow;
                }
                catch (OperationCanceledException) { }
                catch (HttpRequestException ex) { ReportError("suppress:transport", ex); return; }
                catch (System.IO.IOException ex) { ReportError("suppress:io", ex); return; }
                catch (Exception ex) { ReportError("suppress:" + ex.GetType().Name, ex); }
            }

            if (receivingNow && !suppressedNow)
            {
                CoordinatorPending? renewSnap = null;
                bool renewSucceeded = false;
                try
                {
                    using var renewTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                    renewTimeout.CancelAfter(AdminCallTimeout);
                    renewSnap = await client.SendAsync("renew", null, renewTimeout.Token).ConfigureAwait(false);
                    renewSucceeded = true;
                }
                catch (OperationCanceledException) { }
                catch (HttpRequestException ex) { ReportError("renew:transport", ex); return; }
                catch (System.IO.IOException ex) { ReportError("renew:io", ex); return; }
                catch (Exception ex) { ReportError("renew:" + ex.GetType().Name, ex); }

                if (renewSucceeded && renewSnap is not null)
                {
                    // Re-read AFTER the renew awaited. A pause
                    // that happened during the await is honoured
                    // here so the renewal does not display stale
                    // state.
                    var afterSnap = _snapshot();
                    bool stillReceiving = afterSnap.Receiving;
                    bool stillSuppressed = afterSnap.Suppressed;
                    if (!stillReceiving || stillSuppressed)
                    {
                        try
                        {
                            using var closeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                            closeTimeout.CancelAfter(AdminCallTimeout);
                            var closeSnap = await client.SendAsync("close", null, closeTimeout.Token).ConfigureAwait(false);
                            DispatchPending(closeSnap, myGeneration);
                            lock (_stateLock) _lastLeaseSeenAtUtc = DateTime.MinValue;
                        }
                        catch (OperationCanceledException) { }
                        catch (HttpRequestException ex) { ReportError("close:transport", ex); }
                        catch (System.IO.IOException ex) { ReportError("close:io", ex); }
                        catch (Exception ex) { ReportError("close:" + ex.GetType().Name, ex); }
                        return;
                    }
                    else
                    {
                        if (renewSnap.Receiving) lock (_stateLock) _lastLeaseSeenAtUtc = _clock();
                        DispatchPending(renewSnap, myGeneration);
                    }
                }
            }
            else if (!receivingNow && !suppressedNow)
            {
                bool leaseSeen = false;
                lock (_stateLock) leaseSeen = _lastLeaseSeenAtUtc != DateTime.MinValue;
                if (leaseSeen)
                {
                    try
                    {
                        using var closeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                        closeTimeout.CancelAfter(AdminCallTimeout);
                        var s = await client.SendAsync("close", null, closeTimeout.Token).ConfigureAwait(false);
                        DispatchPending(s, myGeneration);
                        lock (_stateLock) _lastLeaseSeenAtUtc = DateTime.MinValue;
                    }
                    catch (OperationCanceledException) { }
                    catch (HttpRequestException ex) { ReportError("close:transport", ex); }
                    catch (System.IO.IOException ex) { ReportError("close:io", ex); }
                    catch (Exception ex) { ReportError("close:" + ex.GetType().Name, ex); }
                    return;
                }
            }

            try
            {
                using var pollTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                pollTimeout.CancelAfter(AdminCallTimeout);
                var pending = await client.SendAsync("pending", null, pollTimeout.Token).ConfigureAwait(false);
                DispatchPending(pending, myGeneration);
                // A successful poll resets the error dedup key so
                // a follow-on transient failure can surface again.
                lock (_stateLock) _lastErrorSurfaceKey = null;
            }
            catch (OperationCanceledException) { }
            catch (HttpRequestException ex) { ReportError("poll:transport", ex); }
            catch (System.IO.IOException ex) { ReportError("poll:io", ex); }
            catch (Exception ex) { ReportError("poll:" + ex.GetType().Name, ex); }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { ReportError("tick:" + ex.GetType().Name, ex); }
        finally
        {
            // The tick is the sole owner of its local client
            // reference. Disposal is safe here regardless of
            // whether Dispose() ran: the local is a fresh
            // construction from this tick.
            if (client is not null)
            {
                try { client.Dispose(); } catch { }
            }
            if (gateTaken)
            {
                try { _tickGate.Release(); } catch { }
            }
        }
    }

    // Surface a "no live snapshot available" marker so the UI can
    // reflect the offline posture deterministically. The
    // CoordinatorPending shape matches the normal wire envelope so
    // the existing UI path handles it without new branching.
    private void SurfaceWaitingStatus()
    {
        if (Volatile.Read(ref _disposed)) return;
        var waiting = new CoordinatorPending(
            Open: false, Receiving: false, Suppressed: false, SuppressUntilUnix: 0L,
            SessionId: null, Code: null, State: "waiting",
            Expires: 0L, LeaseExpiresUnix: 0L, TtlSeconds: 0L, Devices: 0);
        try { _onPending(waiting); } catch (Exception ex) { ReportError("surface:" + ex.GetType().Name, ex); }
    }

    // Centralised, deduplicated error reporting. Same exception
    // type + fingerprint is suppressed across ticks until a
    // successful pending observation resets the dedup key.
    private void ReportError(string key, Exception ex)
    {
        if (Volatile.Read(ref _disposed)) return;
        lock (_stateLock)
        {
            if (_lastErrorSurfaceKey == key) return;
            _lastErrorSurfaceKey = key;
        }
        try { _onError(ex); } catch { }
    }

    /// <summary>
    /// Deduplicated dispatcher. The UI callback fires for EVERY
    /// snapshot (so the foreground panel always mirrors the server
    /// state) but the tray notification is gated on a per-session
    /// id once-per-request rule with a 60 s global anti-spam
    /// cooldown across distinct requests.
    /// </summary>
    internal void DispatchPending(CoordinatorPending pending, long generation)
    {
        if (Volatile.Read(ref _disposed)) return;
        if (generation != Interlocked.Read(ref _generation)) return;

        bool notifyTray = false;
        string? newNotifiedSessionId = null;
        lock (_stateLock)
        {
            if (ShouldNotify(pending))
            {
                var now = _clock();
                bool fresh = !string.Equals(_lastNotifiedSessionId, pending.SessionId, StringComparison.Ordinal);
                bool cooldownElapsed = _lastNotificationAtUtc == DateTime.MinValue ||
                    now - _lastNotificationAtUtc >= NotificationCooldown;
                if (fresh && cooldownElapsed)
                {
                    notifyTray = true;
                    _lastNotificationAtUtc = now;
                    _lastNotifiedSessionId = pending.SessionId;
                    newNotifiedSessionId = pending.SessionId;
                }
                else if (fresh)
                {
                    newNotifiedSessionId = pending.SessionId;
                }
            }
        }
        try { _onPending(pending); } catch (Exception ex) { ReportError("dispatch:" + ex.GetType().Name, ex); }
        if (Volatile.Read(ref _disposed)) return;
        if (notifyTray && _onTrayNotify is not null)
        {
            try { _onTrayNotify(pending); } catch (Exception ex) { ReportError("tray:" + ex.GetType().Name, ex); }
        }
        if (Volatile.Read(ref _disposed)) return;
        if (newNotifiedSessionId is not null && _onPendingStatus is not null)
        {
            try { _onPendingStatus(pending); } catch (Exception ex) { ReportError("status:" + ex.GetType().Name, ex); }
        }
    }

    private static bool ShouldNotify(CoordinatorPending pending)
    {
        return pending.State == "pending" && !string.IsNullOrEmpty(pending.SessionId);
    }

    /// <summary>
    /// Reset the per-session notification dedup so the next fresh
    /// pending snapshot re-fires the tray balloon. Used when the
    /// user dismisses a balloon or after an explicit "show next
    /// request" gesture.
    /// </summary>
    public void ResetNotificationDedup()
    {
        lock (_stateLock)
        {
            _lastNotifiedSessionId = null;
            _lastNotificationAtUtc = DateTime.MinValue;
        }
    }

    internal DateTime LastLeaseSeenAtUtc
    {
        get { lock (_stateLock) return _lastLeaseSeenAtUtc; }
    }

    internal long Generation => Interlocked.Read(ref _generation);

    public void Dispose()
    {
        // Returns promptly without blocking the caller. The
        // cached client (no longer cached — each tick owns its
        // own local) is released by the in-flight tick's finally
        // block. The timer is disposed and the CTS is cancelled
        // so any pending await observes OperationCanceledException
        // and exits.
        bool wasDisposed;
        lock (_stateLock)
        {
            wasDisposed = _disposed;
            _disposed = true;
        }
        if (wasDisposed) return;
        Interlocked.Increment(ref _generation);
        try { _timer.Dispose(); } catch { }
        try { _cts.Cancel(); } catch { }
        // The SemaphoreSlim and CTS are reclaimed by GC; the
        // coordinator is intended to be long-lived (process
        // lifetime) so this is fine.
    }
}