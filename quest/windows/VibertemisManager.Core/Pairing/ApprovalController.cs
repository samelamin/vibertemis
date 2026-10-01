// ApprovalController is the small Core-side state machine that
// drives the Windows approval panel. It exists so the WinForms
// MainForm is not the source of truth for pairing state and so the
// pending/approved/denied/expired/offline/paused/disabled ladder
// has deterministic behavioural coverage.
//
// The controller is intentionally tiny: it does not perform any
// network or process work. The MainForm supplies immutable input
// snapshots (latest server-side CoordinatorPending, receiving /
// suppression / running companion flags, the captured request
// session id of an in-flight decision action, an optional error
// attached to a specific request id). The controller computes
// the UI-side PanelModel the WinForm should render.
//
// Concurrency: every input is read under a lock so torn reads of
// nullable DateTime and mutable settings never reach the UI. The
// controller does not own settings persistence — the UI does that.
//
// Behavioural guarantees (enforced here, tested in
// ApprovalControllerTests):
//   - An in-flight decision action disables BOTH decision buttons,
//     regardless of which request it belongs to. A fresh request
//     B that arrives while A's decision is still waiting shows
//     the Busy model with both buttons disabled until A's
//     decision completes; then B is actionable.
//   - The hidden-by-user marker is honoured for ALL request
//     states (pending, approved, denied, expired, action error).
//     Once a user hides a request id, repeated polling does NOT
//     reopen it. A different request id is not affected by a
//     hide.
//   - The action error attached to a specific request id is
//     retained on repeated polling of that same request id. A
//     different request id clears the error.
//   - The outer panel is visible whenever the coordinator can
//     surface ANY actionable control (paused / disabled / pending
//     request / approved / denied). The request pane (code,
//     approve / reject / hide) is independently gated so the
//     management row stays reachable even when no request is
//     pending.
using System;
using System.Collections.Generic;

namespace VibertemisManager.Core.Pairing;

public enum ApprovalKind
{
    None,
    Pending,
    Approved,
    Denied,
    Expired,
    Closed,
    Waiting,
    Offline,
    Paused,
    Disabled,
    PendingBusy,
    ActionError,
}

public enum DecisionAction
{
    None,
    Approve,
    Reject,
}

public sealed record ApprovalPanelModel(
    ApprovalKind Kind,
    string Title,
    string Code,
    string Status,
    string Expiry,
    string Recovery,
    bool PanelVisible,
    bool RequestPaneVisible,
    bool ApproveEnabled,
    bool RejectEnabled,
    bool PauseEnabled,
    bool ResumeEnabled,
    bool TurnOffEnabled,
    bool ForgetEnabled,
    bool HideEnabled,
    string? DisplayedSessionId);

public sealed record CoordinatorInput(
    bool Receiving,
    bool Suppressed,
    bool CompanionRunning,
    CoordinatorPending Pending);

public sealed record ActionInput(
    string? InFlightSessionId,
    DecisionAction InFlightAction,
    string? ErrorSessionId,
    string? ActionErrorText);

public sealed class ApprovalController
{
    private readonly object _gate = new();

    private CoordinatorInput _input = new(
        Receiving: false,
        Suppressed: false,
        CompanionRunning: false,
        Pending: NewEmptyPending());

    private ActionInput _action = new(
        InFlightSessionId: null,
        InFlightAction: DecisionAction.None,
        ErrorSessionId: null,
        ActionErrorText: null);

    private string? _hiddenSessionId;

    public static CoordinatorPending NewEmptyPending() => new(
        Open: false, Receiving: false, Suppressed: false, SuppressUntilUnix: 0L,
        SessionId: null, Code: null, State: "waiting",
        Expires: 0L, LeaseExpiresUnix: 0L, TtlSeconds: 0L, Devices: 0);

    public void UpdateCoordinator(CoordinatorInput input)
    {
        lock (_gate) _input = input ?? throw new ArgumentNullException(nameof(input));
    }

    public void UpdateAction(ActionInput action)
    {
        lock (_gate) _action = action ?? new ActionInput(null, DecisionAction.None, null, null);
    }

    public void MarkHidden(string? sessionId)
    {
        lock (_gate) _hiddenSessionId = sessionId;
    }

    public void ClearHidden()
    {
        lock (_gate) _hiddenSessionId = null;
    }

    public string? HiddenSessionId
    {
        get { lock (_gate) return _hiddenSessionId; }
    }

    // True when the server snapshot is a fresh pending request
    // WITH a valid future expiry AND the coordinator inputs
    // (companion running, receiving enabled, suppression clear)
    // allow the owner to act. Used by MainForm to drive the
    // pairing notice flag and the deferred update re-render.
    // The expires field is Unix seconds; do NOT round-trip
    // through DateTimeOffset construction because
    // `new DateTimeOffset(longTicks, TimeSpan.Zero)` interprets
    // the long as DateTime ticks (100-ns intervals since year 1),
    // not Unix seconds. Compare seconds directly.
    public static bool IsLivePending(
        CoordinatorPending snapshot,
        ReceivingSnapshot input,
        DateTime utcNow)
    {
        if (snapshot.State != "pending") return false;
        if (string.IsNullOrEmpty(snapshot.SessionId)) return false;
        if (snapshot.Expires <= 0) return false;
        long nowUnix = new DateTimeOffset(DateTime.SpecifyKind(utcNow, DateTimeKind.Utc), TimeSpan.Zero).ToUnixTimeSeconds();
        if (snapshot.Expires <= nowUnix) return false;
        if (!input.CompanionRunning) return false;
        if (!input.Receiving) return false;
        if (input.Suppressed) return false;
        return true;
    }

    public ApprovalPanelModel Render(DateTime nowUtc)
    {
        CoordinatorInput input;
        ActionInput action;
        string? hidden;
        lock (_gate)
        {
            input = _input;
            action = _action;
            hidden = _hiddenSessionId;
        }
        return RenderInternal(input, action, hidden, nowUtc);
    }

    private static ApprovalPanelModel RenderInternal(
        CoordinatorInput input,
        ActionInput action,
        string? hidden,
        DateTime nowUtc)
    {
        var pending = input.Pending ?? NewEmptyPending();
        bool receiving = input.Receiving;
        bool suppressed = input.Suppressed;
        bool companionRunning = input.CompanionRunning;
        bool isPending = pending.State == "pending" && !string.IsNullOrEmpty(pending.SessionId);
        bool hiddenThisRequest = !string.IsNullOrEmpty(pending.SessionId)
            && string.Equals(hidden, pending.SessionId, StringComparison.Ordinal);

        bool anyInFlight = !string.IsNullOrEmpty(action.InFlightSessionId);
        bool busyForThisRequest = isPending
            && !string.IsNullOrEmpty(action.InFlightSessionId)
            && string.Equals(action.InFlightSessionId, pending.SessionId, StringComparison.Ordinal);
        bool busyForOtherRequest = anyInFlight && !busyForThisRequest;

        if (!companionRunning)
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Offline,
                Title: "",
                Code: "",
                Status: "Host service stopped. Choose Start before pairing.",
                Expiry: "",
                Recovery: "Next action: click Start, or choose Set up VR to enable headset pairing.",
                PanelVisible: false,
                RequestPaneVisible: false,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: false,
                ResumeEnabled: false,
                TurnOffEnabled: false,
                ForgetEnabled: false,
                HideEnabled: false,
                DisplayedSessionId: pending.SessionId);
        }

        if (!receiving)
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Disabled,
                Title: "",
                Code: "",
                Status: "Receiving is off. Saved pairings are kept.",
                Expiry: "",
                Recovery: "Next action: choose Set up VR or Pair headset to enable receiving.",
                PanelVisible: true,
                RequestPaneVisible: false,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: false,
                ResumeEnabled: false,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: false,
                DisplayedSessionId: pending.SessionId);
        }

        if (suppressed)
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Paused,
                Title: "",
                Code: "",
                Status: "Paused for 1 hour. Saved pairings are kept; click Resume to continue.",
                Expiry: "",
                Recovery: "Next action: click Resume to re-enable receiving now.",
                PanelVisible: true,
                RequestPaneVisible: false,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: false,
                ResumeEnabled: true,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: false,
                DisplayedSessionId: pending.SessionId);
        }

        if (isPending)
        {
            long expires = pending.Expires;
            long secondsLeft = expires > 0
                ? Math.Max(0, expires - new DateTimeOffset(nowUtc, TimeSpan.Zero).ToUnixTimeSeconds())
                : 0;
            bool expired = expires > 0 && secondsLeft <= 0;

            if (busyForThisRequest)
            {
                // The decision action for THIS request is in
                // flight. Both buttons stay disabled; the user
                // cannot fire another until the action returns.
                // Pause / Turn off / Forget remain reachable.
                return new ApprovalPanelModel(
                    Kind: ApprovalKind.PendingBusy,
                    Title: "Pairing request from Quest",
                    Code: pending.Code ?? "Waiting for Quest…",
                    Status: "Approve in progress. The decision is being sent to the host service.",
                    Expiry: expired
                        ? "Time remaining: expired. The request will be removed shortly."
                        : FormatExpiry(secondsLeft),
                    Recovery: "",
                    PanelVisible: true,
                    RequestPaneVisible: !hiddenThisRequest,
                    ApproveEnabled: false,
                    RejectEnabled: false,
                    PauseEnabled: true,
                    ResumeEnabled: false,
                    TurnOffEnabled: true,
                    ForgetEnabled: true,
                    HideEnabled: true,
                    DisplayedSessionId: pending.SessionId);
            }

            if (busyForOtherRequest)
            {
                // A different request's decision is still in
                // flight. Both decision buttons on this request
                // stay disabled so the user does not act on this
                // request until the previous action completes.
                return new ApprovalPanelModel(
                    Kind: ApprovalKind.PendingBusy,
                    Title: "Pairing request from Quest",
                    Code: pending.Code ?? "Waiting for Quest…",
                    Status: "Waiting for previous decision to finish. Approve and Reject will become available when the prior action completes.",
                    Expiry: "",
                    Recovery: "",
                    PanelVisible: true,
                    RequestPaneVisible: !hiddenThisRequest,
                    ApproveEnabled: false,
                    RejectEnabled: false,
                    PauseEnabled: true,
                    ResumeEnabled: false,
                    TurnOffEnabled: true,
                    ForgetEnabled: true,
                    HideEnabled: !hiddenThisRequest,
                    DisplayedSessionId: pending.SessionId);
            }

            if (!string.IsNullOrEmpty(action.ActionErrorText)
                && string.Equals(action.ErrorSessionId, pending.SessionId, StringComparison.Ordinal))
            {
                // Action error for the SAME request id. The
                // in-flight marker has been cleared (the retry
                // path); the error text + retry hint stays in
                // the panel until the user retries or a fresh
                // request arrives.
                return new ApprovalPanelModel(
                    Kind: ApprovalKind.ActionError,
                    Title: "Pairing request from Quest",
                    Code: pending.Code ?? "Waiting for Quest…",
                    Status: action.ActionErrorText,
                    Expiry: expired
                        ? "Time remaining: expired. The request will be removed shortly."
                        : FormatExpiry(secondsLeft),
                    Recovery: "Next action: try the action again, or choose Hide.",
                    PanelVisible: true,
                    RequestPaneVisible: !hiddenThisRequest,
                    ApproveEnabled: !expired,
                    RejectEnabled: !expired,
                    PauseEnabled: true,
                    ResumeEnabled: false,
                    TurnOffEnabled: true,
                    ForgetEnabled: true,
                    HideEnabled: true,
                    DisplayedSessionId: pending.SessionId);
            }

            return new ApprovalPanelModel(
                Kind: expired ? ApprovalKind.Expired : ApprovalKind.Pending,
                Title: "Pairing request from Quest",
                Code: pending.Code ?? "Waiting for Quest…",
                Status: expired
                    ? "This request expired before a decision. A fresh request will appear here."
                    : "Approve only if every character matches the code on your Quest.",
                Expiry: expired
                    ? "Time remaining: expired. The request will be removed shortly."
                    : FormatExpiry(secondsLeft),
                Recovery: "",
                PanelVisible: true,
                RequestPaneVisible: !hiddenThisRequest,
                ApproveEnabled: !expired,
                RejectEnabled: !expired,
                PauseEnabled: true,
                ResumeEnabled: false,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: true,
                DisplayedSessionId: pending.SessionId);
        }

        if (pending.State == "approved")
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Approved,
                Title: "Pairing request from Quest",
                Code: pending.Code ?? "",
                Status: "Approved. Quest will finish saving its pairing automatically.",
                Expiry: "",
                Recovery: "Next action: closing this window keeps the host ready in the tray.",
                PanelVisible: true,
                RequestPaneVisible: !hiddenThisRequest,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: true,
                ResumeEnabled: false,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: true,
                DisplayedSessionId: pending.SessionId);
        }

        if (pending.State == "denied")
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Denied,
                Title: "Pairing request from Quest",
                Code: pending.Code ?? "",
                Status: "Rejected. Receiving stays enabled; the headset can retry.",
                Expiry: "",
                Recovery: "Next action: in Vibertemis on Quest, choose Set up PC and select this PC.",
                PanelVisible: true,
                RequestPaneVisible: !hiddenThisRequest,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: true,
                ResumeEnabled: false,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: true,
                DisplayedSessionId: pending.SessionId);
        }

        if (pending.State == "closed")
        {
            return new ApprovalPanelModel(
                Kind: ApprovalKind.Closed,
                Title: "",
                Code: "",
                Status: "Waiting for a Quest request. Put on your Quest, choose Set up PC and select this PC.",
                Expiry: "",
                Recovery: "Next action: in Vibertemis on Quest, choose Set up PC and select this PC.",
                PanelVisible: true,
                RequestPaneVisible: false,
                ApproveEnabled: false,
                RejectEnabled: false,
                PauseEnabled: true,
                ResumeEnabled: false,
                TurnOffEnabled: true,
                ForgetEnabled: true,
                HideEnabled: false,
                DisplayedSessionId: pending.SessionId);
        }

        return new ApprovalPanelModel(
            Kind: ApprovalKind.Waiting,
            Title: "",
            Code: "",
            Status: "Waiting for a Quest request. Put on your Quest, choose Set up PC and select this PC.",
            Expiry: "",
            Recovery: "Next action: in Vibertemis on Quest, choose Set up PC and select this PC.",
            PanelVisible: true,
            RequestPaneVisible: false,
            ApproveEnabled: false,
            RejectEnabled: false,
            PauseEnabled: true,
            ResumeEnabled: false,
            TurnOffEnabled: true,
            ForgetEnabled: true,
            HideEnabled: false,
            DisplayedSessionId: pending.SessionId);
    }

    private static string FormatExpiry(long secondsLeft)
        => $"Time remaining: {secondsLeft / 60:D2}:{secondsLeft % 60:D2}.";
}