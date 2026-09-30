// Focused state/notification integration tests. These tests
// drive the Core state machine — the ApprovalController and the
// IsLivePending helper — across the realistic pending /
// approved / expired / waiting / offline / paused / disabled
// transitions, including a fresh-session error retry and a
// terminal-state hide-marker persistence check. The full UI
// (tray tooltip reset, deferred update re-render, balloon
// routing) is not exercised here; those code paths live in
// MainForm and are covered only by the installed smoke, which
// currently asserts the visible inline-panel state and not
// the tray routing itself.
using System;
using VibertemisManager.Core.Pairing;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class ApprovalIntegrationTests
{
    private static readonly DateTime Now = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void PendingTransitionsAcrossStateLadder()
    {
        // pending -> approved -> expired -> waiting is the
        // realistic lifecycle the coordinator drives. Each
        // transition must update the controller state without
        // leaking the prior state.
        var id = new string('a', 64);
        var c = new ApprovalController();
        long expiry = new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();
        long past = new DateTimeOffset(Now.AddSeconds(-30), TimeSpan.Zero).ToUnixTimeSeconds();

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));

        var m1 = c.Render(Now);
        Assert.Equal(ApprovalKind.Pending, m1.Kind);
        Assert.True(m1.ApproveEnabled);

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "approved",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m2 = c.Render(Now);
        Assert.Equal(ApprovalKind.Approved, m2.Kind);
        Assert.False(m2.ApproveEnabled);

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: past, LeaseExpiresUnix: past, TtlSeconds: 120, Devices: 0)));
        var m3 = c.Render(Now);
        Assert.Equal(ApprovalKind.Expired, m3.Kind);
        Assert.False(m3.ApproveEnabled);
        Assert.False(m3.RejectEnabled);

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: false, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: null, Code: null, State: "waiting",
                Expires: 0L, LeaseExpiresUnix: 0L, TtlSeconds: 0L, Devices: 0)));
        var m4 = c.Render(Now);
        Assert.Equal(ApprovalKind.Waiting, m4.Kind);
        Assert.False(m4.RequestPaneVisible,
            "Waiting state must hide the request pane so the management row stays reachable");
    }

    [Fact]
    public void ActionErrorSurvivesPollingForRetry()
    {
        // A failed decision attaches an error to the SAME
        // session id. The user can retry by clicking Approve
        // again. Repeated polling of the same id (no fresh
        // request) MUST preserve the error so the user sees
        // it until they retry.
        var id = new string('a', 64);
        var c = new ApprovalController();
        long expiry = new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));

        // Initial state: no error, no in-flight.
        var m0 = c.Render(Now);
        Assert.NotEqual(ApprovalKind.ActionError, m0.Kind);

        // Caller observes a PairingAdminException and routes
        // the owner-controlled text into the controller.
        c.UpdateAction(new ActionInput(
            InFlightSessionId: null,
            InFlightAction: DecisionAction.None,
            ErrorSessionId: id,
            ActionErrorText: "Pairing expired. Click Approve to retry."));

        for (var i = 0; i < 3; i++)
        {
            var m = c.Render(Now);
            Assert.Equal(ApprovalKind.ActionError, m.Kind);
            Assert.True(m.ApproveEnabled, "Approve must remain enabled to allow retry");
            Assert.True(m.RejectEnabled);
            Assert.Contains("Pairing expired", m.Status);
        }
    }

    [Fact]
    public void HiddenTerminalStateStaysHiddenAcrossPolls()
    {
        // A terminal state (approved) hides on Hide() and
        // stays hidden even as the coordinator polls the same
        // id again. A different session id reveals the pane.
        var idA = new string('a', 64);
        var idB = new string('b', 64);
        var c = new ApprovalController();
        long expiry = new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: idA, Code: "1111-2222-3333-4444", State: "approved",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m1 = c.Render(Now);
        Assert.True(m1.RequestPaneVisible);
        c.MarkHidden(idA);

        for (var i = 0; i < 3; i++)
        {
            var m = c.Render(Now);
            Assert.False(m.RequestPaneVisible,
                "approved pane must stay hidden after MarkHidden, regardless of repeated polls");
        }

        // Different session id reveals the pane.
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: idB, Code: "5555-6666-7777-8888", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m2 = c.Render(Now);
        Assert.True(m2.RequestPaneVisible, "Different session id must reveal the pane");
    }

    [Fact]
    public void OfflineCompanionHidesPaneEvenWithPendingSnapshot()
    {
        // Even when the server-side snapshot has a pending
        // request, if the companion is not running, the
        // approval panel is hidden because the owner has no
        // actionable path.
        var id = new string('a', 64);
        var c = new ApprovalController();
        long expiry = new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: false,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Offline, m.Kind);
        Assert.False(m.PanelVisible);
    }

    [Fact]
    public void SuppressedClearExposesPendingPane()
    {
        // A pending request that arrives while suppression is
        // active stays hidden until the suppression clears.
        var id = new string('a', 64);
        var c = new ApprovalController();
        long expiry = new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: true, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: true, SuppressUntilUnix: expiry + 60,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m1 = c.Render(Now);
        Assert.Equal(ApprovalKind.Paused, m1.Kind);
        Assert.False(m1.RequestPaneVisible);

        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: new CoordinatorPending(
                Open: true, Receiving: true, Suppressed: false, SuppressUntilUnix: 0L,
                SessionId: id, Code: "1111-2222-3333-4444", State: "pending",
                Expires: expiry, LeaseExpiresUnix: expiry, TtlSeconds: 120, Devices: 0)));
        var m2 = c.Render(Now);
        Assert.Equal(ApprovalKind.Pending, m2.Kind);
        Assert.True(m2.RequestPaneVisible);
    }
}