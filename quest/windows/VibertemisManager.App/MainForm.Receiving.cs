using System;
using System.Drawing;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using VibertemisManager.Core.Pairing;
using VibertemisManager.Core.Settings;
using VibertemisManager.Core.Update;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private PairingReceiveCoordinator? _pairingCoordinator;
    private FileSystemAdminClientFactory? _pairingFactory;
    private long _coordinatorGeneration;
    private long Generation => Interlocked.Read(ref _coordinatorGeneration);
    private CoordinatorPending _latestPending = ApprovalController.NewEmptyPending();

    private readonly ApprovalController _approvalController = new();

    // The session id the user explicitly hid via the Hide
    // button. The controller's hidden marker is the source of
    // truth for the request pane; this field is the mirror used
    // by the WinForms host so the hide survives across polls.
    private string? _hiddenRequestSessionId;

    // Captured session id of the in-flight Approve / Reject
    // decision. The completion path must compare to this exact
    // id and never hide a different currently displayed request.
    private string? _decisionInFlightSessionId;
    private DecisionAction _decisionInFlightAction;
    private string? _decisionErrorSessionId;
    private string? _decisionActionErrorText;

    // Thread-safe snapshot publishing. The UI thread writes a
    // complete immutable ReceivingSnapshot under _snapshotLock and
    // releases the lock before any await. The coordinator's
    // background tick Volatile.Read's the field — a single
    // pointer-width atomic read that cannot tear. Settings reads
    // happen under the lock so a torn read of nullable DateTime
    // never reaches the coordinator.
    private readonly object _snapshotLock = new();
    private ReceivingSnapshot _publishedSnapshot = new(
        Receiving: false,
        Suppressed: false,
        SuppressUntilUtc: null,
        CompanionRunning: false,
        AsOfUtc: DateTime.UtcNow);

    private TableLayoutPanel _panelApproval = null!;
    private readonly Label _lblHostReady = new() { AutoSize = true, Text = "Host ready: not yet known.", MaximumSize = new Size(650, 0) };
    private readonly Label _lblHostReadyNext = new() { AutoSize = true, Text = "", MaximumSize = new Size(650, 0) };
    private Label _lblPanelTitle = new() { AutoSize = true, Font = new Font(SystemFonts.MessageBoxFont!, FontStyle.Bold) };
    private Label _lblPanelCode = new() { AutoSize = true, Font = new Font("Consolas", 22f, FontStyle.Bold) };
    private Label _lblPanelStatus = new() { AutoSize = true, MaximumSize = new Size(700, 0) };
    private Label _lblPanelExpiry = new() { AutoSize = true };
    private Label _lblPanelRecovery = new() { AutoSize = true, MaximumSize = new Size(700, 0) };
    private FlowLayoutPanel _panelActions = null!;
    private Button _btnApprove = new() { Text = "Codes match — approve", AutoSize = true, Enabled = false };
    private Button _btnReject = new() { Text = "Reject", AutoSize = true, Enabled = false };
    private Button _btnPause = new() { Text = "Pause 1 hour", AutoSize = true };
    private Button _btnResume = new() { Text = "Resume", AutoSize = true };
    private Button _btnTurnOff = new() { Text = "Turn off", AutoSize = true };
    private Button _btnForgetAll = new() { Text = "Forget paired headsets…", AutoSize = true };
    private Button _btnPanelClose = new() { Text = "Hide", AutoSize = true };

    private readonly System.Windows.Forms.Timer _expiryTimer = new() { Interval = 1000 };
    private readonly DeduplicatedUpdateTrayNotice _updateNotice = new();
    private TrayNoticeKind _lastTrayNoticeKind = TrayNoticeKind.None;

    // Tracks whether a pairing notification is currently
    // outstanding (the balloon is on screen OR the request is
    // pending in the UI). The update balloon guard consults this
    // BEFORE TryFire so the update notice is preserved across the
    // pairing window and surfaced once pairing resolves.
    private bool _pairingNoticePending;

    private enum TrayNoticeKind { None, Pairing, Update }

    internal bool PairingNoticePending => _pairingNoticePending;

    private void InitializeReceivingUx()
    {
        _panelActions = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
        };
        _panelActions.Controls.AddRange(new Control[] { _btnApprove, _btnReject, _btnPause, _btnResume, _btnTurnOff, _btnForgetAll, _btnPanelClose });

        _panelApproval = new TableLayoutPanel
        {
            Visible = false,
            Dock = DockStyle.Bottom,
            ColumnCount = 1,
            RowCount = 6,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Padding = new Padding(12),
            BorderStyle = BorderStyle.FixedSingle,
        };
        _panelApproval.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 6; i++) _panelApproval.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        _panelApproval.Controls.Add(_lblPanelTitle, 0, 0);
        _panelApproval.Controls.Add(_lblPanelCode, 0, 1);
        _panelApproval.Controls.Add(_lblPanelStatus, 0, 2);
        _panelApproval.Controls.Add(_lblPanelExpiry, 0, 3);
        _panelApproval.Controls.Add(_lblPanelRecovery, 0, 4);
        _panelApproval.Controls.Add(_panelActions, 0, 5);
    }

    private void StartReceivingCoordinator()
    {
        if (_pairingCoordinator is not null) return;
        _pairingFactory = new FileSystemAdminClientFactory(() => _svc.Paths.CompanionStateDir);
        Interlocked.Increment(ref _coordinatorGeneration);
        PublishReceivingSnapshot();
        _pairingCoordinator = new PairingReceiveCoordinator(
            _pairingFactory,
            snapshot: ReadPublishedSnapshot,
            onPending: OnCoordinatorPending,
            onTrayNotify: OnCoordinatorTrayNotify,
            onPendingStatus: OnCoordinatorPendingStatus,
            onError: OnCoordinatorError);
        _pairingCoordinator.Start();
    }

    private void StopReceivingCoordinator()
    {
        Interlocked.Increment(ref _coordinatorGeneration);
        _pairingCoordinator?.Dispose();
        _pairingCoordinator = null;
        _pairingFactory = null;
        PublishReceivingSnapshot();
    }

    // Atomic Volatile.Read by the coordinator on a background
    // thread. The published field is a single pointer-width
    // immutable record — the read is tear-free.
    private ReceivingSnapshot ReadPublishedSnapshot()
        => Volatile.Read(ref _publishedSnapshot);

    // Publish a fresh immutable ReceivingSnapshot from the UI
    // thread. All settings reads happen under the snapshot lock;
    // the DateTime conversion treats Unspecified as legacy UTC and
    // Local as UTC for comparison. Call this whenever the source
    // fields change: setting edits, recovery tick, initialize,
    // start/stop/handoff.
    internal void PublishReceivingSnapshot()
    {
        ReceivingSnapshot snap;
        lock (_snapshotLock)
        {
            var settings = _settings;
            var running = _recovery is { RunningSpec: not null };
            bool suppressed = settings.SuppressPairingUntilUtc is { } raw
                && ToUtcAssumeLegacyUtc(raw) > DateTime.UtcNow;
            snap = new ReceivingSnapshot(
                Receiving: settings.ReceivePairingRequestsSession,
                Suppressed: suppressed,
                SuppressUntilUtc: settings.SuppressPairingUntilUtc is { } u
                    ? ToUtcAssumeLegacyUtc(u)
                    : null,
                CompanionRunning: running,
                AsOfUtc: DateTime.UtcNow);
        }
        Volatile.Write(ref _publishedSnapshot, snap);
    }

    private static DateTime ToUtcAssumeLegacyUtc(DateTime value)
    {
        // Persisted values written by older manager versions are
        // DateTimeKind.Unspecified. Treat them as UTC so a stale
        // persisted flag never leaks a Local-as-UTC interpretation
        // into the live comparison.
        return value.Kind switch
        {
            DateTimeKind.Utc => value,
            DateTimeKind.Local => value.ToUniversalTime(),
            _ => DateTime.SpecifyKind(value, DateTimeKind.Utc),
        };
    }

    private void RefreshHostReady()
    {
        var s = _latestPending;
        var snap = ReadPublishedSnapshot();
        bool serverReceiving = s.Receiving;
        string status;
        string action;
        if (!snap.CompanionRunning)
        {
            status = "Host ready: stopped. Click Start hosting, then Setup VR or Pair headset.";
            action = "Next action: choose Start hosting to enable the host service.";
        }
        else if (!snap.Receiving)
        {
            status = "Host ready: receiving pairing requests is OFF.";
            action = "Next action: choose Setup VR or Pair headset to enable receiving.";
        }
        else if (snap.Suppressed)
        {
            status = "Host ready: receiving pairing requests is paused for the next hour.";
            action = "Next action: click Resume to re-enable receiving now.";
        }
        else if (s.State == "pending")
        {
            status = "Host ready: a Quest is waiting for approval. Compare the code, then Approve or Reject.";
            action = "Next action: click Approve only when the code matches every character on your Quest.";
        }
        else if (serverReceiving)
        {
            // Receiving is desired AND the latest /pending or
            // /renew snapshot confirms the server-side lease is
            // live. Only then do we say ON; before the first
            // confirmed live lease, fall through to "starting".
            status = "Host ready: receiving pairing requests is ON.";
            action = "Next action: in Vibertemis on Quest, choose Setup VR and select this PC.";
        }
        else if (snap.Receiving)
        {
            // Desired ON, companion running, suppression clear,
            // but the latest server snapshot has Receiving=false
            // (admin renew failed or lease not yet observed).
            status = "Host ready: receiving pairing requests is starting up. Waiting for the host service to confirm.";
            action = "Next action: if this persists, click Pause 1 hour and Resume to retry the renewal.";
        }
        else
        {
            status = "Host ready: receiving pairing requests is starting up.";
            action = "Next action: in Vibertemis on Quest, choose Setup VR and select this PC.";
        }
        if (_lblHostReady.Text != status) _lblHostReady.Text = status;
        if (_lblHostReadyNext.Text != action) _lblHostReadyNext.Text = action;
    }

    private void OnCoordinatorPending(CoordinatorPending snap)
    {
        if (IsDisposed || Disposing) return;
        long gen = Generation;
        if (InvokeRequired)
        {
            try { BeginInvoke(new Action(() => ApplyPending(snap, gen))); }
            catch (InvalidOperationException) { }
            return;
        }
        ApplyPending(snap, gen);
    }

    private void OnCoordinatorTrayNotify(CoordinatorPending snap)
    {
        if (IsDisposed || Disposing) return;
        long gen = Generation;
        if (InvokeRequired)
        {
            try { BeginInvoke(new Action(() => ShowPairingTrayBalloon(snap, gen))); }
            catch (InvalidOperationException) { }
            return;
        }
        ShowPairingTrayBalloon(snap, gen);
    }

    private void OnCoordinatorPendingStatus(CoordinatorPending snap)
    {
        if (IsDisposed || Disposing) return;
        long gen = Generation;
        if (InvokeRequired)
        {
            try { BeginInvoke(new Action(() => ApplyPendingStatus(snap, gen))); }
            catch (InvalidOperationException) { }
            return;
        }
        ApplyPendingStatus(snap, gen);
    }

    private void OnCoordinatorError(Exception ex)
    {
        if (IsDisposed || Disposing) return;
        long gen = Generation;
        Action surface = () =>
        {
            if (IsDisposed || Disposing) return;
            if (gen != Generation) return;
            var detail = ex is PairingAdminException pae
                ? pae.OwnerText
                : "Host service unavailable. Start hosting, then try again.";
            LogStatus("Pairing: " + detail);
            var hint = IsLikelyPairingSetupError(ex)
                ? "Next action: in Vibertemis on Quest, choose Setup VR and select this PC, then try again."
                : "Next action: choose Pause 1h, Resume, Turn off, or Forget all from the pairing panel.";
            if (_lblHostReadyNext.Text != hint) _lblHostReadyNext.Text = hint;
            RenderApprovalPanelFromController();
        };
        if (InvokeRequired)
        {
            try { BeginInvoke(surface); } catch (InvalidOperationException) { }
            return;
        }
        surface();
    }

    private static bool IsLikelyPairingSetupError(Exception ex) =>
        ex is PairingAdminException pae && (pae.ErrorCode is "CLOSED" or "EXPIRED" or "INVALID" or "BUSY");

    private void ApplyPending(CoordinatorPending snap, long gen)
    {
        if (IsDisposed || Disposing) return;
        if (gen != Generation) return;
        var previousSession = _latestPending.SessionId;
        bool previousWasLivePending = _pairingNoticePending;
        _latestPending = snap;
        // A different session id clears any prior action error
        // bound to the old session. Repeated polling of the SAME
        // session id preserves the error so the owner can retry.
        if (!string.Equals(previousSession, snap.SessionId, StringComparison.Ordinal))
        {
            _decisionErrorSessionId = null;
            _decisionActionErrorText = null;
            // A fresh request reveals the request pane unless
            // the user explicitly hid that exact id.
            if (_hiddenRequestSessionId is null
                || !string.Equals(_hiddenRequestSessionId, snap.SessionId, StringComparison.Ordinal))
            {
                _approvalController.ClearHidden();
                _hiddenRequestSessionId = null;
            }
        }
        // The pairing notice is "live" only when the snapshot is
        // a fresh pending request WITH a valid future expiry AND
        // the coordinator inputs (companion running, receiving
        // enabled, suppression clear) allow the owner to act.
        // Any non-pending state — including waiting / closed /
        // approved / denied / expired — clears the notice so a
        // deferred update balloon can surface on the next render.
        var input = ReadPublishedSnapshot();
        bool livePending = ApprovalController.IsLivePending(snap, input, DateTime.UtcNow);
        _pairingNoticePending = livePending;
        if (previousWasLivePending && !livePending)
        {
            // The pairing notice just cleared — restore the
            // default tray tooltip so the next click does not
            // route to a stale balloon kind. Reset regardless of
            // the last TrayNoticeKind so a cooldown-suppressed
            // balloon still leaves the tooltip clean.
            RestoreDefaultTrayTooltip();
            // A deferred update may have been muted while the
            // pairing notice was outstanding. Re-render the
            // current update snapshot once so a waiting update
            // surfaces through the normal dedup path.
            RenderUpdateStatusLine(_updateSnapshot);
        }
        RenderApprovalPanelFromController();
        RefreshHostReady();
    }

    private void RestoreDefaultTrayTooltip()
    {
        if (_tray is null || IsDisposed || Disposing) return;
        try
        {
            _tray.Text = "VibertemisVR Host Manager";
            _lastTrayNoticeKind = TrayNoticeKind.None;
        }
        catch { }
    }

    private void ApplyPendingStatus(CoordinatorPending snap, long gen)
    {
        if (IsDisposed || Disposing) return;
        if (gen != Generation) return;
        if (_lastTrayNoticeKind == TrayNoticeKind.Pairing) return;
        if (_tray is null) return;
        try
        {
            _tray.Text = "VibertemisVR Host Manager — a Quest is waiting for approval";
        }
        catch { }
    }

    private void ShowPairingTrayBalloon(CoordinatorPending snap, long gen)
    {
        if (IsDisposed || Disposing) return;
        if (gen != Generation) return;
        if (_tray is null) return;
        try
        {
            _tray.BalloonTipClicked -= OnTrayBalloonClicked;
            _tray.BalloonTipClicked += OnTrayBalloonClicked;
            _lastTrayNoticeKind = TrayNoticeKind.Pairing;
            _pairingNoticePending = true;
            _tray.ShowBalloonTip(5000, "VibertemisVR Host Manager",
                "A Quest is waiting for approval. Compare the code on your headset, then click here.",
                ToolTipIcon.Info);
        }
        catch (Exception ex) { LogStatus("Could not show pairing notification: " + ex.Message); }
    }

    private void ShowUpdateTrayBalloon(string title, string text)
    {
        if (IsDisposed || Disposing) return;
        if (_tray is null) return;
        try
        {
            _tray.BalloonTipClicked -= OnTrayBalloonClicked;
            _tray.BalloonTipClicked += OnTrayBalloonClicked;
            _lastTrayNoticeKind = TrayNoticeKind.Update;
            _tray.ShowBalloonTip(5000, title, text, ToolTipIcon.Info);
        }
        catch (Exception ex) { LogStatus("Could not show update notification: " + ex.Message); }
    }

    private void OnTrayBalloonClicked(object? sender, EventArgs e)
    {
        if (IsDisposed || Disposing) return;
        switch (_lastTrayNoticeKind)
        {
            case TrayNoticeKind.Pairing:
                _pairingNoticePending = false;
                RevealApprovalPanel();
                break;
            case TrayNoticeKind.Update:
                // Restore the taskbar entry so the user can find
                // the manager window after an X-to-tray close.
                Show(); WindowState = FormWindowState.Normal; ShowInTaskbar = true; Activate();
                break;
        }
    }

    private void RevealApprovalPanel()
    {
        if (IsDisposed || Disposing) return;
        _approvalController.ClearHidden();
        _hiddenRequestSessionId = null;
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Show(); ShowInTaskbar = true; Activate();
        RenderApprovalPanelFromController();
    }

    private void RenderApprovalPanelFromController()
    {
        var snap = ReadPublishedSnapshot();
        _approvalController.UpdateCoordinator(new CoordinatorInput(
            Receiving: snap.Receiving,
            Suppressed: snap.Suppressed,
            CompanionRunning: snap.CompanionRunning,
            Pending: _latestPending));
        _approvalController.UpdateAction(new ActionInput(
            InFlightSessionId: _decisionInFlightSessionId,
            InFlightAction: _decisionInFlightAction,
            ErrorSessionId: _decisionErrorSessionId,
            ActionErrorText: _decisionActionErrorText));
        var model = _approvalController.Render(DateTime.UtcNow);
        ApplyApprovalModel(model);
    }

    private void ApplyApprovalModel(ApprovalPanelModel model)
    {
        if (_panelApproval.Visible != model.PanelVisible)
        {
            _panelApproval.Visible = model.PanelVisible;
        }
        // The request-specific rows (title/code/status/expiry/recovery)
        // hide when the controller says no request is on screen.
        // The action row stays visible so management controls are
        // reachable.
        bool requestRows = model.RequestPaneVisible;
        _lblPanelTitle.Visible = requestRows;
        _lblPanelCode.Visible = requestRows;
        _lblPanelStatus.Visible = requestRows;
        _lblPanelExpiry.Visible = requestRows;
        _lblPanelRecovery.Visible = requestRows;
        if (requestRows)
        {
            if (_lblPanelTitle.Text != model.Title) _lblPanelTitle.Text = model.Title;
            if (_lblPanelCode.Text != model.Code) _lblPanelCode.Text = model.Code;
            if (_lblPanelStatus.Text != model.Status) _lblPanelStatus.Text = model.Status;
            if (_lblPanelExpiry.Text != model.Expiry) _lblPanelExpiry.Text = model.Expiry;
            if (_lblPanelRecovery.Text != model.Recovery) _lblPanelRecovery.Text = model.Recovery;
        }
        else
        {
            // Status and recovery are always visible to the user;
            // they describe the current pairing posture and the
            // next action the owner can take.
            if (_lblPanelStatus.Text != model.Status) _lblPanelStatus.Text = model.Status;
            if (_lblPanelRecovery.Text != model.Recovery) _lblPanelRecovery.Text = model.Recovery;
            _lblPanelStatus.Visible = true;
            _lblPanelRecovery.Visible = true;
        }
        _btnApprove.Visible = requestRows;
        _btnReject.Visible = requestRows;
        _btnPanelClose.Visible = requestRows;
        _btnApprove.Enabled = model.ApproveEnabled;
        _btnReject.Enabled = model.RejectEnabled;
        _btnPause.Enabled = model.PauseEnabled;
        _btnResume.Enabled = model.ResumeEnabled;
        _btnTurnOff.Enabled = model.TurnOffEnabled;
        _btnForgetAll.Enabled = model.ForgetEnabled;
        _btnPanelClose.Enabled = model.HideEnabled;
        if (model.Kind is ApprovalKind.Pending or ApprovalKind.ActionError or ApprovalKind.PendingBusy)
            _expiryTimer.Start();
        else
            _expiryTimer.Stop();
    }

    private void RefreshPanelExpiry()
    {
        if (IsDisposed || Disposing) return;
        if (!_panelApproval.Visible) return;
        RenderApprovalPanelFromController();
    }

    private void WireReceivingUxEvents()
    {
        _btnApprove.Click += async (_, _) => await SendDecisionAsync(true);
        _btnReject.Click += async (_, _) => await SendDecisionAsync(false);
        _btnPause.Click += (_, _) => SetSuppression(TimeSpan.FromHours(1));
        _btnResume.Click += (_, _) => ClearSuppression();
        _btnTurnOff.Click += (_, _) => TurnOffReceiving();
        _btnForgetAll.Click += async (_, _) => await ForgetAllAsync();
        _btnPanelClose.Click += (_, _) => HidePanel();
        _expiryTimer.Tick += (_, _) => RefreshPanelExpiry();
    }

    // Hide ANY exact displayed session id. The controller honours
    // the hidden marker for pending / action-error / pending-busy /
    // approved / denied states; a fresh request (different id)
    // reveals the pane again.
    private void HidePanel()
    {
        var model = _approvalController.Render(DateTime.UtcNow);
        if (!string.IsNullOrEmpty(model.DisplayedSessionId))
        {
            _approvalController.MarkHidden(model.DisplayedSessionId);
            _hiddenRequestSessionId = model.DisplayedSessionId;
        }
        RenderApprovalPanelFromController();
    }

    private void SetSuppression(TimeSpan window)
    {
        _settings.SuppressPairingUntilUtc = DateTime.SpecifyKind(DateTime.UtcNow.Add(window), DateTimeKind.Utc);
        SaveSettings();
        PublishReceivingSnapshot();
        LogStatus("Receiving paused. Saved pairings are kept; click Resume to re-enable.");
        RefreshHostReady();
        RenderApprovalPanelFromController();
    }

    private void ClearSuppression()
    {
        _settings.SuppressPairingUntilUtc = null;
        SaveSettings();
        PublishReceivingSnapshot();
        LogStatus("Receiving resumed. Lease renewal is back on the timer.");
        RefreshHostReady();
        RenderApprovalPanelFromController();
    }

    private void TurnOffReceiving()
    {
        _settings.ReceivePairingRequests = false;
        _settings.ReceivePairingRequestsSession = false;
        _settings.SuppressPairingUntilUtc = null;
        SaveSettings();
        PublishReceivingSnapshot();
        LogStatus("Receiving pairing requests turned off. Saved pairings are kept; open Pair headset from the manager to re-enable.");
        RefreshHostReady();
        RenderApprovalPanelFromController();
    }

    private async Task SendDecisionAsync(bool approve)
    {
        // Gate by the actual model surface AND the latest
        // displayed session id / code. An action-error state
        // keeps Approve/Reject enabled so the user can retry;
        // an in-flight or expired state disables them.
        var model = _approvalController.Render(DateTime.UtcNow);
        var displayed = _latestPending;
        if (string.IsNullOrEmpty(displayed.SessionId) || displayed.State != "pending")
        {
            LogStatus(approve ? "Approval ignored: no pending request on screen." : "Rejection ignored: no pending request on screen.");
            return;
        }
        if (!string.Equals(displayed.SessionId, model.DisplayedSessionId, StringComparison.Ordinal))
        {
            LogStatus("Approval ignored: stale request on screen.");
            return;
        }
        bool buttonEnabled = approve ? model.ApproveEnabled : model.RejectEnabled;
        if (!buttonEnabled)
        {
            LogStatus(approve ? "Approval ignored: request is in flight, expired, or another action is running."
                : "Rejection ignored: request is in flight, expired, or another action is running.");
            return;
        }
        if (!string.IsNullOrEmpty(_decisionInFlightSessionId))
        {
            LogStatus("Decision ignored: another action is already in progress.");
            return;
        }
        var captured = new DecisionCapture(
            SessionId: displayed.SessionId!,
            Code: displayed.Code ?? "",
            Approve: approve);
        if (!EnsureCoordinatorClient(out var client, out var error))
        {
            LogStatus(error);
            _decisionErrorSessionId = captured.SessionId;
            _decisionActionErrorText = "Companion is starting up. Try again in a second.";
            RenderApprovalPanelFromController();
            return;
        }
        _decisionInFlightSessionId = captured.SessionId;
        _decisionInFlightAction = approve ? DecisionAction.Approve : DecisionAction.Reject;
        if (string.Equals(_decisionErrorSessionId, captured.SessionId, StringComparison.Ordinal))
        {
            _decisionErrorSessionId = null;
            _decisionActionErrorText = null;
        }
        RenderApprovalPanelFromController();
        try
        {
            using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            var snap = await client.SendAsync(
                "decision",
                new { session_id = captured.SessionId, code = captured.Code, approve = captured.Approve },
                cancellation.Token);
            var after = _approvalController.Render(DateTime.UtcNow);
            if (!string.Equals(after.DisplayedSessionId, captured.SessionId, StringComparison.Ordinal))
            {
                _decisionInFlightSessionId = null;
                _decisionInFlightAction = DecisionAction.None;
                RenderApprovalPanelFromController();
                return;
            }
            if (snap.SessionId != captured.SessionId || snap.Code != captured.Code)
            {
                LogStatus("Decision ignored: server returned a different request identity.");
                return;
            }
            if (approve && snap.State == "approved")
                LogStatus("Headset approved. Quest will finish saving its pairing automatically.");
            else if (!approve && snap.State == "denied")
                LogStatus("Pairing request rejected. Receiving stays enabled; the headset can retry.");
            _approvalController.MarkHidden(captured.SessionId);
            _hiddenRequestSessionId = captured.SessionId;
            _decisionInFlightSessionId = null;
            _decisionInFlightAction = DecisionAction.None;
            _decisionErrorSessionId = null;
            _decisionActionErrorText = null;
            _pairingNoticePending = false;
            _latestPending = snap;
            RenderApprovalPanelFromController();
        }
        catch (OperationCanceledException)
        {
            LogStatus("Decision timed out. Try again.");
            _decisionErrorSessionId = captured.SessionId;
            _decisionActionErrorText = "Decision timed out. Try again.";
            RenderApprovalPanelFromController();
        }
        catch (PairingAdminException pae)
        {
            LogStatus(pae.OwnerText);
            _decisionErrorSessionId = captured.SessionId;
            _decisionActionErrorText = pae.OwnerText + " Click Approve to retry.";
            RenderApprovalPanelFromController();
        }
        catch (Exception ex)
        {
            LogStatus("Decision failed: " + ex.Message);
            _decisionErrorSessionId = captured.SessionId;
            _decisionActionErrorText = "Decision failed. Click Approve to retry.";
            RenderApprovalPanelFromController();
        }
        finally
        {
            client.Dispose();
            _decisionInFlightSessionId = null;
            _decisionInFlightAction = DecisionAction.None;
            if (IsHandleCreated && _panelApproval.Visible)
            {
                RenderApprovalPanelFromController();
            }
        }
    }

    private async Task ForgetAllAsync()
    {
        if (MessageBox.Show(this,
            "Forget all headsets paired through this approval screen? They will need approval again. Imported legacy pairing files are unaffected.",
            "Forget headsets", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
            return;
        if (!EnsureCoordinatorClient(out var client, out var error))
        {
            LogStatus(error);
            return;
        }
        try
        {
            using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await client.SendAsync("forget", null, cancellation.Token);
            LogStatus("All paired headsets forgotten through this approval screen.");
        }
        catch (OperationCanceledException) { LogStatus("Forget timed out. Try again."); }
        catch (PairingAdminException pae) { LogStatus(pae.OwnerText); }
        catch (Exception ex) { LogStatus("Forget failed: " + ex.Message); }
        finally { client.Dispose(); }
    }

    private bool EnsureCoordinatorClient(out IAdminClient client, out string error)
    {
        client = null!;
        error = "";
        if (_pairingFactory is null) { error = "Pairing coordinator is not initialised."; return false; }
        try
        {
            client = _pairingFactory.Create();
            return true;
        }
        catch (CompanionNotReadyException cnr) { error = "Companion is starting up. " + cnr.Message; return false; }
        catch (Exception ex) { error = "Could not reach host service: " + ex.Message; return false; }
    }

    private void EnableReceivingFromSuccess()
    {
        _settings.ReceivePairingRequests = true;
        _settings.ReceivePairingRequestsSession = true;
        _settings.SuppressPairingUntilUtc = null;
        SaveSettings();
        PublishReceivingSnapshot();
        RefreshHostReady();
        RenderApprovalPanelFromController();
    }

    private readonly record struct DecisionCapture(string SessionId, string Code, bool Approve);
}