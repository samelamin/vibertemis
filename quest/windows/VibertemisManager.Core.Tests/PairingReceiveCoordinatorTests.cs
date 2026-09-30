// PairingReceiveCoordinator deterministic tests.
//
// The coordinator drives lease renewal + pending polling from a
// 2 s serial timer. The real companion is unreachable from a
// Linux runner; these tests use a controllable FakeAdminClient
// (latchable calls and forced responses) so every transition is
// reproducible. Each scenario drives the coordinator with manual
// TickAsync calls so timing assumptions don't flake.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Pairing;
using VibertemisManager.Core.Settings;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class PairingReceiveCoordinatorTests
{
    // ---- Fakes / latches -------------------------------------------------

    // Shared server state. Each per-tick lease references this
    // object so call counts / handlers survive across the fresh
    // client objects the coordinator constructs on every tick.
    // The lease itself is a thin disposable wrapper.
    private sealed class FakeServer
    {
        public List<(string Action, object? Payload)> Calls { get; } = new();
        public List<FakeAdminClient> Created { get; } = new();
        public Func<string, object?, CancellationToken, Task<CoordinatorPending>>? Respond { get; set; }
        public Exception? ThrowOnNext { get; set; }
        public Func<string, object?, Task>? OnCall { get; set; }
        public int DisposeCount { get; private set; }

        public int LeaseCount => Calls.Count(c => c.Action == "renew");
        public int PendingCount => Calls.Count(c => c.Action == "pending");
        public int CloseCount => Calls.Count(c => c.Action == "close");
        public int SuppressCount => Calls.Count(c => c.Action == "suppress");
        public int UnsuppressCount => Calls.Count(c => c.Action == "unsuppress");

        public void NoteDispose()
        {
            DisposeCount++;
        }
    }

    private sealed class FakeAdminClient : IAdminClient
    {
        private readonly FakeServer _server;
        public FakeAdminClient(FakeServer server) { _server = server; _server.Created.Add(this); }
        public bool Disposed { get; private set; }

        public Task<CoordinatorPending> SendAsync(string action, object? payload, CancellationToken cancellationToken)
        {
            if (Disposed) throw new ObjectDisposedException(nameof(FakeAdminClient));
            _server.Calls.Add((action, payload));
            _server.OnCall?.Invoke(action, payload);
            if (_server.ThrowOnNext is not null)
            {
                var ex = _server.ThrowOnNext;
                _server.ThrowOnNext = null;
                throw ex;
            }
            if (_server.Respond is null) throw new InvalidOperationException("FakeServer.Respond not set");
            return _server.Respond(action, payload, cancellationToken);
        }
        public void Dispose()
        {
            Disposed = true;
            _server.NoteDispose();
        }
    }

    private sealed class FakeFactory : IAdminClientFactory
    {
        public FakeServer Server { get; } = new();
        public Exception? Throw { get; set; }

        public IAdminClient Create()
        {
            if (Throw is not null) throw Throw;
            // The coordinator constructs a fresh client on every
            // tick. Each tick owns its lease; the test harness
            // shares server-side state (calls, respond handler)
            // across leases so call counts survive disposal.
            return new FakeAdminClient(Server);
        }
    }

    private static CoordinatorPending MakePending(string sessionId, string code, string state = "pending", long expires = 1800000000L, bool receiving = true, bool suppressed = false, int devices = 0, long leaseExpires = 1800000000L) =>
        new(Open: true, Receiving: receiving, Suppressed: suppressed, SuppressUntilUnix: suppressed ? 1800003600L : 0L,
            SessionId: sessionId, Code: code, State: state, Expires: expires, LeaseExpiresUnix: leaseExpires, TtlSeconds: 180L, Devices: devices);

    private static CoordinatorPending MakeClosed(int devices = 0) =>
        new(Open: false, Receiving: false, Suppressed: false, SuppressUntilUnix: 0L,
            SessionId: null, Code: null, State: "closed", Expires: 0L, LeaseExpiresUnix: 0L, TtlSeconds: 0L, Devices: devices);

    private sealed class Capture<T>
    {
        public List<T> Items { get; } = new();
        public T Last => Items.Count > 0 ? Items[^1] : default!;
        public void Add(T item) => Items.Add(item);
    }

    private static Func<DateTime> FixedClock(DateTime now) => () => now;

    // Build a ReceivingSnapshot delegate from the legacy boolean
    // flags the existing tests use. CompanionRunning defaults to
    // true so existing tests are unaffected; per-test overrides
    // are supplied via the snapshot factory helper below.
    private static Func<ReceivingSnapshot> Snapshot(
        Func<bool>? receiving = null,
        Func<bool>? suppressed = null,
        Func<DateTime?>? suppressionUntil = null,
        Func<bool>? companionRunning = null)
    {
        return () => new ReceivingSnapshot(
            Receiving: receiving?.Invoke() ?? true,
            Suppressed: suppressed?.Invoke() ?? false,
            SuppressUntilUtc: suppressionUntil?.Invoke(),
            CompanionRunning: companionRunning?.Invoke() ?? true,
            AsOfUtc: DateTime.UtcNow);
    }

    // Helper that yields once between factory construction and
    // the renew call so concurrent ticks actually overlap.
    private static Func<string, object?, CancellationToken, Task<CoordinatorPending>> Yielding(Func<string, object?, CoordinatorPending> sync)
        => (action, payload, ct) =>
        {
            var tcs = new TaskCompletionSource<CoordinatorPending>();
            _ = Task.Run(async () =>
            {
                await Task.Yield();
                if (ct.IsCancellationRequested) { tcs.TrySetCanceled(ct); return; }
                tcs.TrySetResult(sync(action, payload));
            });
            return tcs.Task;
        };

    // ---- Test scenarios --------------------------------------------------

    [Fact]
    public async Task OverlappingTicksAreSerialised()
    {
        // Yield-based fake so concurrent ticks actually contend.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);
        factory.Server.Respond = Yielding((_, _) => MakePending(new string('a', 64), "1111-2222-3333-4444"));

        var t1 = coord.TickAsync(CancellationToken.None);
        var t2 = coord.TickAsync(CancellationToken.None);
        var t3 = coord.TickAsync(CancellationToken.None);
        var t4 = coord.TickAsync(CancellationToken.None);
        var t5 = coord.TickAsync(CancellationToken.None);
        await Task.WhenAll(t1, t2, t3, t4, t5);

        // The first tick wins the gate and runs to completion;
        // the four overlapping ticks observe the gate is taken
        // and return without calling the transport. One renew +
        // one pending per successful tick.
        Assert.Equal(1, factory.Server.LeaseCount);
        Assert.Equal(1, factory.Server.PendingCount);
    }

    [Fact]
    public async Task OverlappingTicksAwaitingRenewSerialise()
    {
        // Latched renew: the first tick parks on the renew call,
        // the second tick enters before the renew completes.
        // Only one renew goes out.
        var factory = new FakeFactory();
        var releaseRenew = new TaskCompletionSource<CoordinatorPending>(TaskCreationOptions.RunContinuationsAsynchronously);
        var renewGate = new SemaphoreSlim(0, 1);
        factory.Server.Respond = (action, payload, ct) =>
        {
            if (action == "renew")
            {
                renewGate.Release();
                return releaseRenew.Task;
            }
            return Task.FromResult(MakePending(new string('a', 64), "1111-2222-3333-4444"));
        }
        ;
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);

        var t1 = coord.TickAsync(CancellationToken.None);
        await renewGate.WaitAsync();
        var t2 = coord.TickAsync(CancellationToken.None);
        await Task.Delay(50);
        // t2 is now waiting on the gate; release the renew.
        releaseRenew.TrySetResult(MakePending(new string('a', 64), "1111-2222-3333-4444"));
        await Task.WhenAll(t1, t2);

        Assert.Equal(1, factory.Server.LeaseCount);
    }

    [Fact]
    public async Task DisableBeforeTickSkipsRenewButStillPolls()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => false, suppressed: () => false),
            onPending: capture.Add);
        factory.Server.Respond = Yielding((_, _) => MakePending(new string('b', 64), "AAAA-BBBB-CCCC-DDDD", receiving: false));

        await coord.TickAsync(CancellationToken.None);

        Assert.Equal(0, factory.Server.LeaseCount);
        Assert.Equal(1, factory.Server.PendingCount);
        Assert.Equal(0, factory.Server.CloseCount); // no lease was ever established
    }

    [Fact]
    public async Task SuppressedSkipsRenewAndStillPolls()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => true),
            onPending: capture.Add);
        factory.Server.Respond = Yielding((_, _) => MakePending(new string('c', 64), "1111-2222-3333-4444", suppressed: true));

        await coord.TickAsync(CancellationToken.None);

        Assert.Equal(0, factory.Server.LeaseCount);
        Assert.Equal(1, factory.Server.PendingCount);
    }

    [Fact]
    public async Task SuppressEdgeTriggersAdminCallOnRisingEdgeOnly()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        bool suppressed = false;
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => suppressed),
            onPending: capture.Add);
        factory.Server.Respond = Yielding((_, _) => MakePending(new string('d', 64), "1111-2222-3333-4444"));

        suppressed = true;
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(1, factory.Server.SuppressCount);
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(1, factory.Server.SuppressCount); // no extra suppress while still suppressed
        suppressed = false;
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(1, factory.Server.UnsuppressCount);
    }

    [Fact]
    public async Task SuppressEdgeRetriesAfterTimeout()
    {
        // The first suppress call FAILS (timeout). The edge
        // detector MUST NOT advance because the server is not
        // actually suppressed yet; the next tick retries.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        bool suppressed = true;
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => suppressed),
            onPending: capture.Add, clock: FixedClock(new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc)));
        // The first call to /suppress throws; subsequent calls
        // succeed. This simulates a network blip on the very
        // first call.
        int suppressAttempts = 0;
        factory.Server.Respond = (action, payload, ct) =>
        {
            if (action == "suppress")
            {
                suppressAttempts++;
                if (suppressAttempts == 1) throw new OperationCanceledException();
                return Task.FromResult(MakePending(new string('d', 64), "1111-2222-3333-4444", suppressed: true));
            }
            if (action == "renew") throw new InvalidOperationException("renew must NOT be sent while suppressed");
            return Task.FromResult(MakePending(new string('d', 64), "1111-2222-3333-4444", suppressed: true));
        };
        await coord.TickAsync(CancellationToken.None); // attempt 1 — fails
        Assert.Equal(1, suppressAttempts);
        Assert.Equal(0, factory.Server.LeaseCount);
        await coord.TickAsync(CancellationToken.None); // attempt 2 — succeeds
        Assert.Equal(2, suppressAttempts);
        Assert.Equal(0, factory.Server.LeaseCount);
    }

    [Fact]
    public async Task SuppressRetryUsesPersistedDeadlineNotNowPlusHour()
    {
        // Spec: a failing /suppress POST retries with the SAME
        // deadline, not a recomputed now+1h. The user clicked
        // Pause1h at T=0; the first /suppress fails; the second
        // tick at T=2 must carry the same until_unix value the
        // user originally requested.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var now = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var persistedDeadline = new DateTime(2026, 1, 1, 1, 0, 0, DateTimeKind.Utc);
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => true, suppressionUntil: () => persistedDeadline),
            onPending: capture.Add,
            clock: () => now);

        long firstDeadline = -1;
        long secondDeadline = -1;
        var suppressPayloads = new List<object?>();
        factory.Server.OnCall = (action, payload) =>
        {
            if (action == "suppress") suppressPayloads.Add(payload);
            return Task.CompletedTask;
        };
        factory.Server.Respond = (action, payload, ct) =>
        {
            if (action == "suppress")
            {
                if (suppressPayloads.Count == 1) throw new OperationCanceledException();
                return Task.FromResult(MakePending(new string('d', 64), "1111-2222-3333-4444", suppressed: true));
            }
            return Task.FromResult(MakePending(new string('d', 64), "1111-2222-3333-4444", suppressed: true));
        };

        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(1, suppressPayloads.Count);
        firstDeadline = ExtractUntilUnix(suppressPayloads[0]);

        now = now.AddSeconds(2);
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(2, suppressPayloads.Count);
        secondDeadline = ExtractUntilUnix(suppressPayloads[1]);

        Assert.Equal(new DateTimeOffset(persistedDeadline, TimeSpan.Zero).ToUnixTimeSeconds(), firstDeadline);
        Assert.Equal(firstDeadline, secondDeadline);
    }

    private static long ExtractUntilUnix(object? payload)
    {
        // Anonymous-type payloads expose via reflection; the
        // IAdminClient receives the object before the fake
        // serialises it.
        if (payload is null) return -1;
        var prop = payload.GetType().GetProperty("until_unix");
        if (prop is null) return -1;
        return Convert.ToInt64(prop.GetValue(payload));
    }

    [Fact]
    public async Task StateChangeSameRequestStillFiresOnPending()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);
        var session = new string('e', 64);
        factory.Server.Respond = (action, payload, ct) =>
            action == "renew"
                ? Task.FromResult(MakePending(session, "1111-2222-3333-4444"))
                : Task.FromResult(MakePending(session, "1111-2222-3333-4444", state: "pending"));

        await coord.TickAsync(CancellationToken.None);
        // Same session_id, different state (server-side decision
        // arrived) — UI MUST see the new state, even though the
        // tray notification is suppressed.
        factory.Server.Respond = (action, payload, ct) =>
            action == "renew"
                ? Task.FromResult(MakePending(session, "1111-2222-3333-4444"))
                : Task.FromResult(MakePending(session, "1111-2222-3333-4444", state: "approved"));
        await coord.TickAsync(CancellationToken.None);

        Assert.Equal(4, capture.Items.Count); // renew + poll, twice
        Assert.Contains(capture.Items, x => x.State == "approved");
    }

    [Fact]
    public async Task DuplicatePendingSnapshotDoesNotReFireTray()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add);
        var session = new string('f', 64);
        factory.Server.Respond = (action, _, _) => Task.FromResult(
            action == "renew"
                ? MakePending(session, "1111-2222-3333-4444")
                : MakePending(session, "1111-2222-3333-4444"));

        await coord.TickAsync(CancellationToken.None);
        await coord.TickAsync(CancellationToken.None);
        await coord.TickAsync(CancellationToken.None);

        // Every tick: UI fires (renew + poll). Tray fires exactly
        // once for this session.
        Assert.True(capture.Items.Count >= 3);
        Assert.Single(trayCapture.Items);
    }

    [Fact]
    public async Task GlobalCooldownThrottlesAcrossRequests()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        var now = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        DateTime Now() => now;
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add, clock: Now);

        int n = 0;
        factory.Server.Respond = (action, _, _) => Task.FromResult(
            MakePending(new string((char)('0' + (n++ % 10)), 64), "1111-2222-3333-4444"));

        // Drive 5 ticks with distinct session_ids within the
        // 60 s cooldown window. Tray fires only once.
        for (var i = 0; i < 5; i++) await coord.TickAsync(CancellationToken.None);
        Assert.True(capture.Items.Count >= 5);
        Assert.Single(trayCapture.Items);
    }

    [Fact]
    public async Task SameSessionNeverRefiresAfterCooldownElapses()
    {
        // The corrected once-per-request rule: a fresh pending
        // session_id fires the tray exactly once. The same
        // session_id NEVER re-notifies even after the 60 s
        // anti-spam cooldown elapses (the user has either
        // ignored the request or already acted on it; the
        // foreground panel still mirrors the latest snapshot).
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        var now = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add,
            clock: () => now);

        var session = new string('h', 64);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(session, "1111-2222-3333-4444"));

        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);

        // Advance the clock past the cooldown window. Same
        // session_id: no re-fire.
        now = now.Add(PairingReceiveCoordinator.NotificationCooldown).AddSeconds(1);
        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);

        // And one more time.
        now = now.Add(PairingReceiveCoordinator.NotificationCooldown).AddSeconds(1);
        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);
    }

    [Fact]
    public async Task FreshSessionInsideCooldownIsMutedThenFiresAfter()
    {
        // Spec: a different pending request inside the global
        // 60 s anti-spam cooldown is muted. After the cooldown
        // elapses, a different session fires exactly once. The
        // same session id NEVER repeats.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        var now = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add,
            clock: () => now);

        // A first request A fires the tray.
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(new string('a', 64), "1111-2222-3333-4444"));
        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);

        // Switch to a fresh request B inside the cooldown
        // window. Tray is muted; the foreground panel still
        // surfaces the new snapshot so the owner can act.
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(new string('b', 64), "1111-2222-3333-4444"));
        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);

        // Advance the cooldown. Now B becomes eligible.
        now = now.Add(PairingReceiveCoordinator.NotificationCooldown).AddSeconds(1);
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(2, trayCapture.Items.Count);

        // B must NEVER repeat even after another cooldown.
        now = now.Add(PairingReceiveCoordinator.NotificationCooldown).AddSeconds(1);
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(2, trayCapture.Items.Count);
    }

    [Fact]
    public async Task DisableAfterLeaseTriggersImmediateClose()
    {
        // Spec: disable → /close on the same tick. NO 10 s wait.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        bool receiving = true;
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => receiving, suppressed: () => false),
            onPending: capture.Add);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(new string('i', 64), "1111-2222-3333-4444"));

        // First tick establishes the lease.
        await coord.TickAsync(CancellationToken.None);
        Assert.True(coord.LastLeaseSeenAtUtc > DateTime.MinValue);
        Assert.Equal(0, factory.Server.CloseCount);

        // Disable BEFORE the next tick; the close must fire on
        // the SAME tick that observes receiving=false.
        receiving = false;
        await coord.TickAsync(CancellationToken.None);

        Assert.True(factory.Server.CloseCount >= 1, "Disable must POST /close on the same tick.");
        Assert.Equal(DateTime.MinValue, coord.LastLeaseSeenAtUtc);
    }

    [Fact]
    public async Task DisableWhileRenewAwaitsSendsCloseBeforePublish()
    {
        // Spec: re-read receiving AFTER the renew awaits. If
        // disabled, send close BEFORE publishing the renew or
        // polling.
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        bool receiving = true;
        var releaseRenew = new TaskCompletionSource<CoordinatorPending>(TaskCreationOptions.RunContinuationsAsynchronously);
        var renewEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int closeSeen = 0;
        var closeEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        factory.Server.Respond = (action, payload, ct) =>
        {
            if (action == "renew")
            {
                renewEntered.TrySetResult();
                return releaseRenew.Task;
            }
            if (action == "close")
            {
                Interlocked.Increment(ref closeSeen);
                closeEntered.TrySetResult();
                return Task.FromResult(MakePending(new string('j', 64), "1111-2222-3333-4444", receiving: false, state: "closed"));
            }
            return Task.FromResult(MakeClosed());
        };
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => receiving, suppressed: () => false),
            onPending: capture.Add);

        var tickTask = coord.TickAsync(CancellationToken.None);
        await renewEntered.Task;
        // Disable racing with the awaiting renew.
        receiving = false;
        releaseRenew.TrySetResult(MakePending(new string('j', 64), "1111-2222-3333-4444"));
        await tickTask;

        Assert.Equal(1, Volatile.Read(ref closeSeen));
        await closeEntered.Task;
        // The close was sent BEFORE the poll this tick. The poll
        // was skipped. Only one snapshot fired through onPending:
        // the close response.
        Assert.Single(capture.Items);
        Assert.Equal("closed", capture.Items[0].State);
    }

    [Fact]
    public async Task ClientFactoryRecreatesAfterCompanionNotReady()
    {
        var factory = new FakeFactory();
        // Throw on the first Create() call to simulate companion
        // not ready; the second Create() succeeds.
        factory.Throw = new CompanionNotReadyException("not ready");
        factory.Server.Respond = Yielding((_, _) => MakePending(new string('7', 64), "1111-2222-3333-4444"));
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);

        await coord.TickAsync(CancellationToken.None); // factory throws
        // Clear the throw so the next tick constructs a client.
        factory.Throw = null;
        await coord.TickAsync(CancellationToken.None); // success
        Assert.Equal(1, factory.Server.Created.Count);
        Assert.Equal(1, factory.Server.PendingCount);
    }

    [Fact]
    public async Task TransportErrorEndsTickAndNextRebuilds()
    {
        // The shared server throws HttpRequestException on every
        // call until the test flips TransportOk. The coordinator
        // catches the transport error, ends the tick, and the
        // next tick constructs a fresh client. The fresh client
        // sees TransportOk=true and reports pending.
        var factory = new FakeFactory();
        bool transportOk = false;
        factory.Server.Respond = (action, _, _) =>
        {
            if (!transportOk) throw new HttpRequestException("transport gone");
            return Task.FromResult(MakePending(new string('k', 64), "1111-2222-3333-4444"));
        };

        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);

        await coord.TickAsync(CancellationToken.None);
        Assert.Single(factory.Server.Created);
        Assert.True(factory.Server.Created[0].Disposed, "tick 1 disposes its local client in finally");

        transportOk = true;
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(2, factory.Server.Created.Count);
        Assert.True(factory.Server.Created[1].Disposed);
        Assert.Equal(1, factory.Server.PendingCount);
    }

    [Fact]
    public async Task EachTickCreatesAndDisposesFreshClient()
    {
        // The coordinator no longer caches a long-lived client.
        // Each tick constructs a fresh client via the factory and
        // disposes it in its finally block.
        var factory = new FakeFactory();
        factory.Server.Respond = (action, _, _) =>
            Task.FromResult(MakePending(new string('a', 64), "1111-2222-3333-4444"));

        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);

        await coord.TickAsync(CancellationToken.None);
        await coord.TickAsync(CancellationToken.None);
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(3, factory.Server.Created.Count);
        Assert.All(factory.Server.Created, c => Assert.True(c.Disposed, "every tick disposes its local client"));
    }

    [Fact]
    public async Task DisposeMidAwaitTickIsSafe()
    {
        // Latched renew: the in-flight tick is parked when
        // Dispose() fires. The cached CancellationToken is
        // cancelled; the tick observes OCE on the gate WaitAsync
        // or the renew SendAsync and returns without side
        // effects.
        var factory = new FakeFactory();
        var releaseRenew = new TaskCompletionSource<CoordinatorPending>(TaskCreationOptions.RunContinuationsAsynchronously);
        var renewEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        factory.Server.Respond = (action, _, _) =>
        {
            if (action == "renew") { renewEntered.TrySetResult(); return releaseRenew.Task; }
            return Task.FromResult(MakePending(new string('z', 64), "1111-2222-3333-4444"));
        };
        var capture = new Capture<CoordinatorPending>();
        var errors = new List<Exception>();
        var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onError: errors.Add);

        var tickTask = coord.TickAsync(CancellationToken.None);
        await renewEntered.Task;
        coord.Dispose();
        releaseRenew.TrySetCanceled();
        // The tick observes cancellation and returns; no exception leaks.
        await tickTask;
        // A late TickAsync after Dispose is a clean no-op.
        await coord.TickAsync(CancellationToken.None);
    }

    [Fact]
    public async Task DisposeReleasesClientAfterBlockedTickSettles()
    {
        // Spec: Dispose cancels the timer and the in-flight HTTP
        // call. The blocked SendAsync is released; the tick's
        // finally block disposes its local client exactly once.
        // After the tick settles, no further onPending / tray /
        // error callbacks fire.
        var factory = new FakeFactory();
        var releaseRenew = new TaskCompletionSource<CoordinatorPending>(TaskCreationOptions.RunContinuationsAsynchronously);
        var renewEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        factory.Server.Respond = (action, _, _) =>
        {
            if (action == "renew") { renewEntered.TrySetResult(); return releaseRenew.Task; }
            return Task.FromResult(MakePending(new string('z', 64), "1111-2222-3333-4444"));
        };
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        var errors = new List<Exception>();
        var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add, onError: errors.Add);

        var tickTask = coord.TickAsync(CancellationToken.None);
        await renewEntered.Task;
        // Dispose returns promptly without waiting. The in-flight
        // tick is parked on the renew SendAsync; the coordinator's
        // Dispose cancels the timer and the shared CTS so the
        // tick's await observes cancellation.
        coord.Dispose();
        Assert.Equal(1, factory.Server.Created.Count);
        Assert.False(factory.Server.Created[0].Disposed,
            "Dispose must not block; the tick's finally owns disposal");
        // Release the blocked renew with cancellation so the
        // tick's await returns cleanly.
        releaseRenew.TrySetCanceled();
        await tickTask;
        Assert.True(factory.Server.Created[0].Disposed,
            "tick's finally must dispose its local client exactly once");
        Assert.Equal(1, factory.Server.DisposeCount);
        // No callback fires after the tick completes.
        var pendingCount = capture.Items.Count;
        var trayCount = trayCapture.Items.Count;
        var errorCount = errors.Count;
        await Task.Delay(50);
        Assert.Equal(pendingCount, capture.Items.Count);
        Assert.Equal(trayCount, trayCapture.Items.Count);
        Assert.Equal(errorCount, errors.Count);
        // A late TickAsync after Dispose is a clean no-op.
        await coord.TickAsync(CancellationToken.None);
    }

    [Fact]
    public async Task OnErrorHandlerReceivesExceptions()
    {
        var factory = new FakeFactory();
        factory.Server.ThrowOnNext = new IOException("network unreachable");
        var capture = new Capture<CoordinatorPending>();
        var errors = new List<Exception>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onError: errors.Add);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(new string('a', 64), "1111-2222-3333-4444"));

        await coord.TickAsync(CancellationToken.None);
        Assert.Contains(errors, e => e is IOException);
    }

    [Fact]
    public async Task ResetNotificationDedupReFiresForSameSession()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add);
        var session = new string('z', 64);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(session, "1111-2222-3333-4444"));

        await coord.TickAsync(CancellationToken.None);
        Assert.Single(trayCapture.Items);
        coord.ResetNotificationDedup();
        await coord.TickAsync(CancellationToken.None);
        Assert.Equal(2, trayCapture.Items.Count);
    }

    [Fact]
    public void SettingsRoundTrip_PreservesPersistedFlag_DropsSessionFlag()
    {
        // ReceivePairingRequests is persisted; ReceivePairingRequestsSession
        // is memory-only (JsonIgnore). A round-trip must preserve the
        // persisted flag and the session flag must not appear on disk.
        var fs = new VibertemisManager.Core.Paths.RealFileSystemAccess();
        var root = Path.Combine(Path.GetTempPath(), "vibt-rxc-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new JsonSettingsStore(new FixedPaths(root), fs);
            var settings = new UserSettings
            {
                ReceivePairingRequests = true,
                ReceivePairingRequestsSession = true,
                AutoStartWithWindows = false,
            };
            store.Save(settings);

            // Inspect the on-disk JSON: session flag MUST be absent.
            var path = Path.Combine(root, "VibertemisVRHostManager", "settings.json");
            var json = File.ReadAllText(path);
            Assert.DoesNotContain("ReceivePairingRequestsSession", json);
            Assert.Contains("ReceivePairingRequests", json);

            var loaded = store.Load();
            Assert.True(loaded.ReceivePairingRequests);
            Assert.False(loaded.ReceivePairingRequestsSession, "session flag must NOT survive round-trip");
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Fact]
    public void SettingsRoundTrip_NullPersistedFlagBecomesDefault()
    {
        var fs = new VibertemisManager.Core.Paths.RealFileSystemAccess();
        var root = Path.Combine(Path.GetTempPath(), "vibt-rxc-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new JsonSettingsStore(new FixedPaths(root), fs);
            var settings = new UserSettings
            {
                ReceivePairingRequests = null,
                ReceivePairingRequestsSession = false,
            };
            store.Save(settings);
            var loaded = store.Load();
            Assert.Null(loaded.ReceivePairingRequests);
            Assert.False(loaded.ReceivePairingRequestsSession);
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Fact]
    public async Task ClosedSnapshotSurfacesToUiButNotTray()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        var trayCapture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add, onTrayNotify: trayCapture.Add);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakeClosed());

        await coord.TickAsync(CancellationToken.None);
        // UI fires for both renew and poll (server is closed so
        // renew returns closed too). Tray does NOT fire because
        // ShouldNotify requires state=pending.
        Assert.Equal(2, capture.Items.Count);
        Assert.Empty(trayCapture.Items);
    }

    [Fact]
    public async Task CancellationOnTickDoesNotFireOnPending()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => true, suppressed: () => false),
            onPending: capture.Add);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakePending(new string('y', 64), "1111-2222-3333-4444"));

        using var cts = new CancellationTokenSource();
        cts.Cancel();
        await coord.TickAsync(cts.Token);
        Assert.Empty(capture.Items);
    }

    [Fact]
    public async Task RefreshCadenceStillPollsAfterMultipleDisabledTicks()
    {
        var factory = new FakeFactory();
        var capture = new Capture<CoordinatorPending>();
        using var coord = new PairingReceiveCoordinator(
            factory, snapshot: Snapshot(receiving: () => false, suppressed: () => false),
            onPending: capture.Add);
        factory.Server.Respond = (action, _, _) => Task.FromResult(MakeClosed());

        for (var i = 0; i < 3; i++) await coord.TickAsync(CancellationToken.None);

        Assert.Equal(0, factory.Server.LeaseCount);
        Assert.Equal(3, factory.Server.PendingCount);
    }
}

public sealed class LocalPairingClientErrorMappingTests
{
    private sealed class Transport(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request);
    }
    private static HttpResponseMessage Reply(string body, HttpStatusCode code = HttpStatusCode.OK)
        => new(code) { Content = new StringContent(body) };

    [Theory]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"BUSY\"}", "BUSY",
        "Another headset is awaiting approval. Check the request on the PC first.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"RATE_LIMITED\"}", "RATE_LIMITED",
        "Too many pairing requests. Wait about two minutes before retrying.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"INVALID\"}", "INVALID",
        "Pairing identity did not match. Cancel and retry on both devices.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"EXPIRED\"}", "EXPIRED",
        "Pairing expired or was cancelled. Retry from your Quest headset.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"CLOSED\"}", "CLOSED",
        "Pairing is closed. On the PC, choose Pair headset in VR Host Manager.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"CAPACITY\"}", "CAPACITY",
        "Headset limit reached. Forget paired headsets in the PC manager before retrying.")]
    [InlineData("{\"error\":\"raw server text\",\"code\":\"STORAGE_FAILED\"}", "STORAGE_FAILED",
        "Could not save pairing on this PC. Retry, or reinstall the VR Manager if the error persists.")]
    public async Task ErrorEnvelopeMapsToOwnerControlledText(string body, string expectedCode, string expectedText)
    {
        using var http = new HttpClient(new Transport(_ => Task.FromResult(Reply(body)))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        var ex = await Assert.ThrowsAsync<PairingAdminException>(() => client.SendAsync("pending", null, CancellationToken.None));
        Assert.Equal(expectedCode, ex.ErrorCode);
        Assert.Equal(expectedText, ex.OwnerText);
        Assert.DoesNotContain("raw server text", ex.OwnerText);
    }

    [Fact]
    public async Task UnknownCodeMapsToNeutralFallback()
    {
        var body = "{\"error\":\"some text\",\"code\":\"MYSTERY\"}";
        using var http = new HttpClient(new Transport(_ => Task.FromResult(Reply(body)))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        var ex = await Assert.ThrowsAsync<PairingAdminException>(() => client.SendAsync("pending", null, CancellationToken.None));
        Assert.Equal("UNKNOWN", ex.ErrorCode);
        Assert.DoesNotContain("some text", ex.OwnerText);
    }

    [Fact]
    public async Task NonSuccessStatusMapsToNeutralFallback()
    {
        using var http = new HttpClient(new Transport(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.InternalServerError)
        {
            Content = new StringContent("{\"foo\":\"bar\"}")
        }))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        var ex = await Assert.ThrowsAsync<PairingAdminException>(() => client.SendAsync("pending", null, CancellationToken.None));
        Assert.Equal("UNKNOWN", ex.ErrorCode);
    }

    [Fact]
    public async Task ApprovedStateWithOpenFalseIsAccepted()
    {
        // The Go side will eventually emit approved/open=false
        // until the per-challenge TTL expires. The strict parser
        // must accept it (state=approved carries session_id/code).
        var body = "{\"open\":false,\"receiving\":false,\"suppressed\":false,\"session_id\":\"" + new string('a', 64) +
            "\",\"code\":\"1111-2222-3333-4444\",\"state\":\"approved\",\"expires_unix\":1800000000,\"devices\":1}";
        using var http = new HttpClient(new Transport(_ => Task.FromResult(Reply(body)))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        var snap = await client.SendCoordinatorAsync("pending", null, CancellationToken.None);
        Assert.Equal("approved", snap.State);
    }
}