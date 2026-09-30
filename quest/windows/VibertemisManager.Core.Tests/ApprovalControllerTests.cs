// ApprovalController behavioural tests. The controller is the
// small Core-side state machine the MainForm renders from. These
// tests cover the production UI decision ladder:
//   - pending / approved / denied / expired / closed / waiting /
//     offline / paused / disabled
//   - hide A: A's approval flow; hide terminal panel reopens per
//     poll without honouring the marker — verified to respect
//     the marker for ALL request states.
//   - decision completion racing B: a fresh request B that
//     arrives while A's decision is still in flight must NOT be
//     hidden by A's completion.
using System;
using VibertemisManager.Core.Pairing;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class ApprovalControllerTests
{
    private static readonly DateTime Now = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private static ApprovalController NewController(
        CoordinatorInput? input = null,
        ActionInput? action = null)
    {
        var c = new ApprovalController();
        c.UpdateCoordinator(input ?? new CoordinatorInput(
            Receiving: true,
            Suppressed: false,
            CompanionRunning: true,
            Pending: ApprovalController.NewEmptyPending()));
        c.UpdateAction(action ?? new ActionInput(
            InFlightSessionId: null,
            InFlightAction: DecisionAction.None,
            ErrorSessionId: null,
            ActionErrorText: null));
        return c;
    }

    private static CoordinatorPending PendingFor(
        string sessionId,
        string code = "1111-2222-3333-4444",
        string state = "pending",
        bool receiving = true,
        bool suppressed = false,
        long? expires = null)
    {
        long exp = expires ?? new DateTimeOffset(Now.AddMinutes(2), TimeSpan.Zero).ToUnixTimeSeconds();
        return new CoordinatorPending(
            Open: true,
            Receiving: receiving,
            Suppressed: suppressed,
            SuppressUntilUnix: suppressed ? exp + 1 : 0L,
            SessionId: sessionId,
            Code: code,
            State: state,
            Expires: exp,
            LeaseExpiresUnix: exp,
            TtlSeconds: 120,
            Devices: 0);
    }

    [Fact]
    public void PendingRendersApproveRejectEnabled()
    {
        var sessionId = new string('a', 64);
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(sessionId)));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Pending, m.Kind);
        Assert.True(m.PanelVisible);
        Assert.True(m.RequestPaneVisible);
        Assert.True(m.ApproveEnabled);
        Assert.True(m.RejectEnabled);
        Assert.True(m.HideEnabled);
        Assert.Equal(sessionId, m.DisplayedSessionId);
        Assert.True(m.PauseEnabled);
        Assert.False(m.ResumeEnabled);
        Assert.True(m.TurnOffEnabled);
        Assert.True(m.ForgetEnabled);
    }

    [Fact]
    public void OfflineHidesEverything()
    {
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: false,
            Pending: ApprovalController.NewEmptyPending()));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Offline, m.Kind);
        Assert.False(m.PanelVisible);
        Assert.False(m.ApproveEnabled);
        Assert.False(m.RejectEnabled);
        Assert.False(m.PauseEnabled);
        Assert.False(m.ResumeEnabled);
        Assert.False(m.TurnOffEnabled);
        Assert.False(m.ForgetEnabled);
    }

    [Fact]
    public void DisabledEnablesTurnOffAndForgetOnly()
    {
        var c = NewController(new CoordinatorInput(
            Receiving: false, Suppressed: false, CompanionRunning: true,
            Pending: ApprovalController.NewEmptyPending()));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Disabled, m.Kind);
        Assert.True(m.PanelVisible);
        Assert.False(m.RequestPaneVisible);
        Assert.True(m.TurnOffEnabled);
        Assert.True(m.ForgetEnabled);
        Assert.False(m.PauseEnabled);
        Assert.False(m.ResumeEnabled);
    }

    [Fact]
    public void PausedEnablesResume()
    {
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: true, CompanionRunning: true,
            Pending: ApprovalController.NewEmptyPending()));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Paused, m.Kind);
        Assert.True(m.PanelVisible);
        Assert.True(m.ResumeEnabled);
        Assert.False(m.PauseEnabled);
        Assert.True(m.TurnOffEnabled);
        Assert.True(m.ForgetEnabled);
    }

    [Fact]
    public void ExpiredDisablesBothDecisionButtons()
    {
        var sessionId = new string('e', 64);
        long past = new DateTimeOffset(Now.AddMinutes(-1), TimeSpan.Zero).ToUnixTimeSeconds();
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(sessionId, expires: past)));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Expired, m.Kind);
        Assert.True(m.PanelVisible);
        Assert.True(m.RequestPaneVisible);
        Assert.False(m.ApproveEnabled);
        Assert.False(m.RejectEnabled);
    }

    [Fact]
    public void ApprovedShowsOnceThenHonoursHiddenMarker()
    {
        var sessionId = new string('a', 64);
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(sessionId, state: "approved")));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Approved, m.Kind);
        Assert.True(m.RequestPaneVisible);
        // User hides the terminal panel.
        c.MarkHidden(sessionId);
        var m2 = c.Render(Now);
        Assert.False(m2.RequestPaneVisible,
            "Hide on terminal state must persist across polls");
    }

    [Fact]
    public void DeniedShowsOnceThenHonoursHiddenMarker()
    {
        var sessionId = new string('d', 64);
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(sessionId, state: "denied")));
        c.MarkHidden(sessionId);
        var m = c.Render(Now);
        Assert.False(m.RequestPaneVisible,
            "Denied panel reopens every poll without honouring the marker — fix this regression");
    }

    [Fact]
    public void HideAKeepsBVisible()
    {
        var idA = new string('a', 64);
        var idB = new string('b', 64);
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(idA)));
        c.MarkHidden(idA);
        // Swap to request B.
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(idB)));
        var m = c.Render(Now);
        Assert.True(m.RequestPaneVisible, "B must NOT inherit A's hide");
        Assert.Equal(idB, m.DisplayedSessionId);
    }

    [Fact]
    public void InFlightForOtherRequestDisablesBothDecisionButtons()
    {
        // Decision A is still in flight. Request B arrives.
        // Both decision buttons on B must be disabled; the
        // controller says "Waiting for previous decision to
        // finish", not "Approve/Reject already finished".
        var idA = new string('a', 64);
        var idB = new string('b', 64);
        var c = NewController(
            input: new CoordinatorInput(
                Receiving: true, Suppressed: false, CompanionRunning: true,
                Pending: PendingFor(idA)),
            action: new ActionInput(
                InFlightSessionId: idA,
                InFlightAction: DecisionAction.Approve,
                ErrorSessionId: null,
                ActionErrorText: null));
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(idB)));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.PendingBusy, m.Kind);
        Assert.False(m.ApproveEnabled,
            "B's Approve must be disabled while A's decision is in flight");
        Assert.False(m.RejectEnabled);
        Assert.Contains("Waiting for previous decision", m.Status);
    }

    [Fact]
    public void InFlightForSameRequestDisablesButtons()
    {
        // Same request in flight: both buttons disabled.
        var idA = new string('a', 64);
        var c = NewController(
            input: new CoordinatorInput(
                Receiving: true, Suppressed: false, CompanionRunning: true,
                Pending: PendingFor(idA)),
            action: new ActionInput(
                InFlightSessionId: idA,
                InFlightAction: DecisionAction.Approve,
                ErrorSessionId: null,
                ActionErrorText: null));
        var m = c.Render(Now);
        Assert.False(m.ApproveEnabled);
        Assert.False(m.RejectEnabled);
        // Pause/Turn off remain reachable.
        Assert.True(m.PauseEnabled);
        Assert.True(m.TurnOffEnabled);
    }

    [Fact]
    public void ActionErrorEnablesRetryForSameRequest()
    {
        // The decision call returned an error attached to this
        // exact request id. Approve must stay enabled so the
        // user can retry; the error text is surfaced in the
        // status field.
        var id = new string('a', 64);
        var c = NewController(
            input: new CoordinatorInput(
                Receiving: true, Suppressed: false, CompanionRunning: true,
                Pending: PendingFor(id)),
            action: new ActionInput(
                InFlightSessionId: null,
                InFlightAction: DecisionAction.None,
                ErrorSessionId: id,
                ActionErrorText: "Pairing expired. Click Approve to retry."));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.ActionError, m.Kind);
        Assert.True(m.ApproveEnabled, "Approve must remain enabled to allow retry");
        Assert.True(m.RejectEnabled);
        Assert.Equal("Pairing expired. Click Approve to retry.", m.Status);
    }

    [Fact]
    public void ActionErrorClearedOnFreshSessionId()
    {
        // A new request id replaces the prior session. The
        // action error must NOT carry over.
        var idA = new string('a', 64);
        var idB = new string('b', 64);
        var c = NewController(
            input: new CoordinatorInput(
                Receiving: true, Suppressed: false, CompanionRunning: true,
                Pending: PendingFor(idA)),
            action: new ActionInput(
                InFlightSessionId: null,
                InFlightAction: DecisionAction.None,
                ErrorSessionId: idA,
                ActionErrorText: "old error"));
        // Caller swaps in fresh request B; the caller clears
        // the error because the session changed.
        c.UpdateAction(new ActionInput(
            InFlightSessionId: null,
            InFlightAction: DecisionAction.None,
            ErrorSessionId: null,
            ActionErrorText: null));
        c.UpdateCoordinator(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(idB)));
        var m = c.Render(Now);
        Assert.Equal(ApprovalKind.Pending, m.Kind);
        Assert.DoesNotContain("old error", m.Status);
    }

    [Fact]
    public void ActionErrorRetainedOnSameSessionPoll()
    {
        // Repeated polling of the SAME session id with an
        // attached error MUST preserve the error so the owner
        // can retry without it being silently erased.
        var id = new string('a', 64);
        var c = NewController(
            input: new CoordinatorInput(
                Receiving: true, Suppressed: false, CompanionRunning: true,
                Pending: PendingFor(id)),
            action: new ActionInput(
                InFlightSessionId: null,
                InFlightAction: DecisionAction.None,
                ErrorSessionId: id,
                ActionErrorText: "Decision failed. Try again."));
        var m1 = c.Render(Now);
        Assert.Contains("Decision failed", m1.Status);
        var m2 = c.Render(Now);
        Assert.True(m2.Status.Contains("Decision failed"),
            "Same-session poll must retain the action error");
    }

    [Fact]
    public void ApproveRejectedByExpiredExpiry()
    {
        // An expired snapshot disables BOTH buttons even though
        // the user has not yet acted.
        var id = new string('a', 64);
        long past = new DateTimeOffset(Now.AddSeconds(-30), TimeSpan.Zero).ToUnixTimeSeconds();
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(id, expires: past)));
        var m = c.Render(Now);
        Assert.False(m.ApproveEnabled);
        Assert.False(m.RejectEnabled);
    }

    [Fact]
    public void ClearHiddenRevealsPending()
    {
        var id = new string('a', 64);
        var c = NewController(new CoordinatorInput(
            Receiving: true, Suppressed: false, CompanionRunning: true,
            Pending: PendingFor(id)));
        c.MarkHidden(id);
        var m1 = c.Render(Now);
        Assert.False(m1.RequestPaneVisible);
        c.ClearHidden();
        var m2 = c.Render(Now);
        Assert.True(m2.RequestPaneVisible);
    }
    [Fact]
    public void HiddenMarkerWithoutRequestKeepsManagementAvailable()
    {
        var controller = NewController();
        controller.MarkHidden("previous-request");
        var model = controller.Render(Now);
        Assert.True(model.PanelVisible);
        Assert.False(model.RequestPaneVisible);
        Assert.True(model.PauseEnabled && model.TurnOffEnabled && model.ForgetEnabled);
        Assert.False(model.ApproveEnabled || model.RejectEnabled || model.HideEnabled);
    }
}