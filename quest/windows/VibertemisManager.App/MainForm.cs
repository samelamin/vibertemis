// Main form for the Vibertemis Manager host application.
//
// Layout (top to bottom inside one autosized, scrolling root):
//   - Readiness header. Prominent, always mounted. States the actual
//     posture: ready / setup needed / receiving off / paused / pending
//     approval, plus the last failed owner action. It never claims setup
//     is done because the companion merely started.
//   - Request card. Only while a Quest is actually waiting: the code,
//     the countdown, and Approve / Reject / Hide. While it is on screen
//     it is the first control under the header and the prerequisite and
//     primary rows step aside, so a pending decision is never competing
//     with unrelated buttons. With no request there is no card, and the
//     idle posture stays on the header where it already lived.
//   - Prerequisite rows: shown only when Steam, SteamVR or the VC++
//     runtime are missing, hidden entirely when everything is in place.
//   - Primary action row: one prominent button at most. "Set up VR"
//     while prerequisites are missing; "Pair headset" once the host
//     runs; "Start" / "Stop" while the host is stopped.
//   - Advanced disclosure: adapter / Refresh, Open Dashboard, Export
//     pairing, receiving management (Pause 1 hour / Resume / Turn off
//     / Forget), Retry setup, Start with Windows. Collapsed by default.
//   - Footer (bottom-docked, outside the scroll host): collapsed activity
//     log + update status + version.
//
// Callback invariants preserved:
//   - Setup Network Access shells the elevated helper with
//     tightly-scoped argv; the manager itself stays unelevated.
//   - Export pairing calls CompanionPairingExportRunner and
//     opens the resulting PRIVATE file location in Explorer via
//     Process.Start(UseShellExecute=true). The contents are
//     NEVER read or displayed by the manager.
//   - Close-to-tray is the default; the first time it happens
//     the manager shows a one-shot explanation. Explicit Exit
//     owns/stops only the owned companion child.
//   - Updates use a single Update action. One click pins a release,
//     downloads it (skipping the download when those exact bytes are
//     already cached), verifies it, and hands the job to the existing
//     UpdateWorker, which closes the manager and runs the signed
//     installer. There is no second in-app Install confirmation: the
//     explicit Update click is the in-app authorization, and the OS
//     consent surface (UAC / installer UI) still applies. Cancellation
//     is available during the attempt and invalidates it completely.
//     Background checks are metadata only and never install.
//   - SteamVR is NEVER started by the manager. The
//     "Install SteamVR" affordance is a steam://install/250820
//     URL dispatch; the user runs Steam itself. The manager never
//     launches SteamVR on login, discovery, configuration, or
//     any other implicit event.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.AutoStart;
using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Firewall;
using VibertemisManager.Core.Network;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Settings;
using VibertemisManager.Core.Steam;
using VibertemisManager.Core.Update;
using VibertemisManager.Core.Recovery;
using System.Net.NetworkInformation;

namespace VibertemisManager.App;

public sealed partial class MainForm : Form
{
    private readonly AppServices _svc;
    private NotifyIcon? _tray;
    // One primary update control. Its label is derived from the
    // coordinator + repository state by UpdateStatusLineRenderer, so
    // the owner is never asked to click through Download then
    // Install: the single click IS the authorization and it runs
    // download -> verify -> installer handoff.
    private readonly Button _btnUpdate = new() { Text = "Check for updates", AutoSize = true };
    private readonly Button _btnCheckUpdate = new() { Text = "Check for updates", AutoSize = true };
    private readonly Button _btnCancelUpdate = new() { Text = "Cancel", AutoSize = true, Visible = false };
    private readonly ProgressBar _updateProgress = new() { Visible = false, Width = 220, Height = 16, Minimum = 0, Maximum = 100, Style = ProgressBarStyle.Continuous };
    private readonly Label _updateProgressLabel = new() { Visible = false, AutoSize = true, Text = "" };
    // Persistent status line that mirrors the hub badge ladder on
    // Windows: visible from first render, last-known outcome stays
    // until a newer state replaces it. "Up to date" only appears
    // after a successful check.
    private readonly Label _updateStatusLabel = new() { AutoSize = true, Text = "Update status: not yet checked.", MaximumSize = new Size(650, 0) };
    private UpdateRepository? _updateRepo;
    private ReleaseClient? _updateDownloader;
    private FormObserver? _updateObserver;
    private UpdateFlowCoordinator? _updateFlow;
    private bool _updateBusy;
    // The role the update action is currently painted with. Promotion
    // and demotion both go through RefreshUpdateButtonStyle, and this
    // cache is what keeps a 1 s status tick from restyling the button
    // (and repainting it) hundreds of times a minute.
    private UiTheme.ButtonRole _updateButtonRole = UiTheme.ButtonRole.Demoted;
    // Whether the primary action row is logically on screen, tracked
    // independently of Control.Visible. Hiding the form to the tray
    // flips the inherited Visible of every descendant, and that window
    // state says nothing about what the owner would see on the way
    // back, so it must never be read as "the primary row stepped
    // aside". RefreshPrimaryActionVisibility is the only writer.
    private bool _primaryActionIntended;
    // True while an update attempt or installer handoff owns the
    // screen. Pairing, setup, and Start/Stop read this so they cannot
    // race an install that is about to close the manager.
    private bool _updateActionLocked;
    // Last failed owner action, rendered on the persistent readiness
    // line. The activity log is collapsed by default, so an error that
    // only reaches the log is invisible exactly when it matters.
    private string? _actionError;
    // Last failed VR readiness inspection, tracked separately so it can
    // be withdrawn when a later inspection succeeds without clearing
    // another action's failure.
    private string? _vrReadinessFailure;
    private UpdateRepository.Snapshot _updateSnapshot = new UpdateRepository.Snapshot(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, false);
    private readonly EventWaitHandle _wake = new(false, EventResetMode.AutoReset, Program.MutexName + "-Wake");
    private readonly System.Windows.Forms.Timer _statusTimer = new() { Interval = 1000 };
    private HostRecoveryController _recovery = null!;
    private string? _lastRecoveryMessage;
    private bool _populatingAdapters;
    private bool _installHandOffInFlight;

    private readonly Label _lblHeader = new();
    private readonly Label _lblSubStatus = new();
    private readonly Label _lblAdapter = new();
    private readonly ComboBox _cmbAdapter = new();
    private readonly Button _btnRefreshAdapters = new();
    private readonly Label _lblSteam = new();
    private readonly Button _btnOpenSteamPage = new();
    private readonly Label _lblSteamVr = new();
    private readonly Button _btnInstallSteamVr = new();
    private readonly Label _lblVcRedist = new();
    private readonly Button _btnVcRedistPage = new();
    private readonly Label _lblCompanion = new();
    private readonly Button _btnCompanionToggle = new();
    private readonly Button _btnCompanionAdvanced = new();
    private readonly Label _lblDashboard = new();
    private readonly Button _btnOpenDashboard = new();
    private readonly Button _btnExportPairing = new();
    private readonly Button _btnPairAdvanced = new();
    private readonly Button _btnSetupNetwork = new();
    private readonly Button _btnSetupRetry = new();
    private readonly CheckBox _chkAutoStart = new();
    private readonly ListBox _lstStatus = new();
    private readonly Label _lblVersion = new();

    // UI containers (kept as fields so the render paths can
    // hide / show whole groups without iterating Controls).
    private TableLayoutPanel _prereqPanel = null!;
    private FlowLayoutPanel _primaryRow = null!;
    private ExpanderLikePanel _advanced = null!;
    private Panel _logHost = null!;
    private TableLayoutPanel _rootContent = null!;
    private Panel _scrollHost = null!;
    // True while the request card owns the screen. The prerequisite and
    // primary rows step aside for it and are restored by re-running the
    // real readiness probes, never by restoring a cached guess.
    private bool _requestDominates;
    // Re-entrancy guard for UpdateRootMaximumSize. Pinning the root to
    // the scroll host's client width decides whether the host needs a
    // vertical scrollbar, and that scrollbar changes the client width,
    // so the write can re-enter the same hook.
    private bool _updatingRootMaximumSize;
    // True while a single coalesced follow-up pass is already queued.
    // It is cleared when that pass runs, so a settling layout that
    // reports the same viewport several times still costs one callback.
    private bool _rootWidthUpdateQueued;

    private UserSettings _settings = new();
    private bool _shownFirstTimeTrayHint;
    private bool _suppressAutoStartEvent;

    public MainForm(AppServices svc, CliArgs args)
    {
        _svc = svc;
        Text = "VibertemisVR Host Manager";
        Width = 760;
        Height = 580;
        StartPosition = FormStartPosition.CenterScreen;
        // Small enough that the disclosures are both on screen at rest,
        // large enough that the request code fits without scrolling. A
        // taller body is reached through the scroll host, never by
        // refusing to let the owner shrink the window.
        MinimumSize = new Size(560, 440);
        UiTheme.ApplyForm(this);

        InitializeReceivingUx();
        BuildLayout();
        WireEvents();
        _ = Handle; // Create the UI handle even for tray-only ApplicationContext startup.
        InitialPopulation();
        _statusTimer.Tick += (_, _) => {
            if (_wake.WaitOne(0)) { Show(); ShowInTaskbar = true; WindowState = FormWindowState.Normal; Activate(); }
            ReconcileHost();
            RefreshDashboardStatus();
            RefreshRuntimeStatus();
            TriggerBackgroundUpdateCheck();
        };
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;
        _statusTimer.Start();
        SurfacePriorOutcome();
    }

    private void BuildLayout()
    {
        // WinForms docks from the last added control backwards, so the
        // footer is declared first and the scroll host last: the scroll
        // host then fills whatever the footer leaves.

        // Footer: collapsed activity log + update status + version.
        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Bottom,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(0, 8, 0, 0),
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 4; i++) footer.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var logExpander = new ExpanderLikePanel { Text = "Activity log" };
        _logHost = new Panel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = SystemColors.Control,
        };
        _lstStatus.Dock = DockStyle.Fill;
        _lstStatus.HorizontalScrollbar = true;
        _lstStatus.IntegralHeight = false;
        _lstStatus.BorderStyle = BorderStyle.None;
        _logHost.Controls.Add(_lstStatus);
        // A fixed short body: the log is a reference, not a page. The
        // row height is in logical units and scales with the display.
        logExpander.AddBodyRow(_logHost, fixedLogicalHeight: 96);
        footer.Controls.Add(logExpander, 0, 0);

        // Update status line + action row.
        _lblVersion.Text = $"v{SignedRelease.CurrentVersion}";
        _lblVersion.AutoSize = true;
        _lblVersion.ForeColor = SystemColors.ControlText;
        _updateStatusLabel.AutoSize = true;
        _updateStatusLabel.ForeColor = SystemColors.ControlText;

        var updateBar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Margin = new Padding(0),
        };
        // The update action starts demoted and is promoted only by
        // RefreshUpdateButtonStyle, once a real update exists and
        // nothing more specific owns the screen.
        UiTheme.ApplyButton(_btnUpdate, _updateButtonRole);
        UiTheme.ApplyButton(_btnCheckUpdate, UiTheme.ButtonRole.Demoted);
        UiTheme.ApplyButton(_btnCancelUpdate, UiTheme.ButtonRole.Demoted);
        updateBar.Controls.AddRange(new Control[] { _btnUpdate, _btnCancelUpdate, _updateProgress, _updateProgressLabel, _btnCheckUpdate });
        var progress = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
        };
        progress.Controls.Add(updateBar);
        footer.Controls.Add(_updateStatusLabel, 0, 1);
        footer.Controls.Add(progress, 0, 2);

        var versionRow = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
        };
        versionRow.Controls.Add(_lblVersion);
        footer.Controls.Add(versionRow, 0, 3);

        // Scroll host: readiness header, request card, prerequisites,
        // primary action, advanced disclosure. Everything the owner can
        // act on lives in here so an expanded Advanced can never push
        // the last row out of reach.
        _scrollHost = new Panel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            Padding = new Padding(0),
        };
        _rootContent = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            ColumnCount = 1,
            RowCount = 5,
            Padding = UiTheme.OuterPadding,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
        };
        _rootContent.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 5; i++) _rootContent.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        // Header row: title + readiness status + next action.
        var header = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
        };
        var title = new Label
        {
            Text = "VibertemisVR Host Manager",
            Font = UiTheme.HeaderFont(),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 4),
        };
        // The two readiness lines wrap to whatever width is actually
        // available (see UpdateRootMaximumSize). A hard pixel cap would
        // truncate them at 150% DPI or in a narrow window.
        _lblHeader.AutoSize = true;
        _lblHeader.AutoEllipsis = true;
        _lblHeader.Margin = new Padding(0, 0, 0, 2);
        _lblHeader.Text = "Checking PC...";
        _lblSubStatus.AutoSize = true;
        _lblSubStatus.AutoEllipsis = true;
        _lblSubStatus.Margin = new Padding(0, 0, 0, 8);
        _lblSubStatus.Text = "";
        header.Controls.Add(title);
        header.Controls.Add(_lblHeader);
        header.Controls.Add(_lblSubStatus);
        _rootContent.Controls.Add(header, 0, 0);

        // Prerequisite panel: visible only when at least one of
        // Steam / SteamVR / VC++ runtime is missing. The label column
        // sizes to its text and the button column takes the rest, so a
        // larger body font or a 150% DPI window widens the labels
        // instead of clipping them into an unreadable 220 px strip.
        _prereqPanel = new TableLayoutPanel
        {
            Visible = false,
            Dock = DockStyle.Top,
            ColumnCount = 2,
            RowCount = 3,
            Padding = UiTheme.SectionPadding,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
        };
        _prereqPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        _prereqPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 3; i++) _prereqPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        _lblSteam.Text = "Steam install: checking...";
        _lblSteam.AutoSize = true;
        _lblSteam.AutoEllipsis = true;
        _lblSteam.Margin = new Padding(0, 4, 12, 4);
        _btnOpenSteamPage.Text = "Get Steam";
        UiTheme.ApplyButton(_btnOpenSteamPage, UiTheme.ButtonRole.Demoted);
        _btnOpenSteamPage.Enabled = false;
        _lblSteamVr.Text = "SteamVR install: checking...";
        _lblSteamVr.AutoSize = true;
        _lblSteamVr.AutoEllipsis = true;
        _lblSteamVr.Margin = new Padding(0, 4, 12, 4);
        _btnInstallSteamVr.Text = "Install SteamVR";
        UiTheme.ApplyButton(_btnInstallSteamVr, UiTheme.ButtonRole.Demoted);
        _btnInstallSteamVr.Enabled = false;
        _lblVcRedist.Text = "VC++ runtime: checking...";
        _lblVcRedist.AutoSize = true;
        _lblVcRedist.AutoEllipsis = true;
        _lblVcRedist.Margin = new Padding(0, 4, 12, 4);
        _btnVcRedistPage.Text = "Install runtime";
        UiTheme.ApplyButton(_btnVcRedistPage, UiTheme.ButtonRole.Demoted);

        _prereqPanel.Controls.Add(_lblSteam, 0, 0);
        _prereqPanel.Controls.Add(_btnOpenSteamPage, 1, 0);
        _prereqPanel.Controls.Add(_lblSteamVr, 0, 1);
        _prereqPanel.Controls.Add(_btnInstallSteamVr, 1, 1);
        _prereqPanel.Controls.Add(_lblVcRedist, 0, 2);
        _prereqPanel.Controls.Add(_btnVcRedistPage, 1, 2);
        foreach (var button in new[] { _btnOpenSteamPage, _btnInstallSteamVr, _btnVcRedistPage })
        {
            button.Anchor = AnchorStyles.Left;
            button.AutoSize = true;
            button.Margin = new Padding(0, 4, 0, 4);
        }
        _rootContent.Controls.Add(_prereqPanel, 0, 2);

        // Primary action row: at most one prominent button, with the
        // pairing entry demoted beside it.
        _primaryRow = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Margin = new Padding(0, 8, 0, 8),
        };
        _btnSetupNetwork.Text = "Set up VR";
        UiTheme.ApplyButton(_btnSetupNetwork, UiTheme.ButtonRole.Primary);
        _btnSetupNetwork.Visible = false;
        _btnPairHeadset.Text = "Pair headset";
        UiTheme.ApplyButton(_btnPairHeadset, UiTheme.ButtonRole.Secondary);
        _btnPairHeadset.Enabled = false;
        _btnPairHeadset.Visible = false;
        _btnCompanionToggle.Text = "Start";
        UiTheme.ApplyButton(_btnCompanionToggle, UiTheme.ButtonRole.Primary);
        _btnCompanionToggle.Enabled = false;
        _btnCompanionToggle.Visible = false;
        _primaryRow.Controls.AddRange(new Control[] { _btnSetupNetwork, _btnCompanionToggle, _btnPairHeadset });
        _rootContent.Controls.Add(_primaryRow, 0, 3);

        // The request card sits directly under the readiness header. A
        // pending Quest is the only thing the owner can act on, so it
        // must be the first control on screen instead of a strip pinned
        // to the bottom of the window, where a long readiness line or a
        // 150% DPI window could push it off the viewport.
        _rootContent.Controls.Add(_panelApproval, 0, 1);

        // Advanced container: real secondary controls. Collapsed
        // by default. Management (Pause / Resume / Turn off /
        // Forget) lives here, not at the bottom of the form.
        _advanced = new ExpanderLikePanel { Text = "Advanced" };
        _advanced.AddBodyRow(BuildAdvancedAdapterRow());
        _advanced.AddBodyRow(BuildAdvancedHostRow());
        _advanced.AddBodyRow(BuildAdvancedPairingRow());
        _advanced.AddBodyRow(BuildAdvancedManualSetupRow());
        _advanced.AddBodyRow(BuildAdvancedDiagnosticsRow());
        _advanced.AddBodyRow(BuildAdvancedAutoStartRow());
        _advanced.ExpandedChanged += OnAdvancedExpandedChanged;
        _rootContent.Controls.Add(_advanced, 0, 4);

        _scrollHost.Controls.Add(_rootContent);

        // Re-pin the root panel to the scroll host width so a long line
        // wraps rather than horizontally scrolling the root.
        _rootContent.MinimumSize = new Size(0, 0);
        // ClientSizeChanged, not Resize: the viewport this root is
        // pinned to is the scroll host's client area, and that is what
        // changes when the vertical scrollbar appears or disappears.
        _scrollHost.ClientSizeChanged += (_, _) => UpdateRootMaximumSize();
        UpdateRootMaximumSize();

        // The scroll host is the only content layer: the footer docks
        // under it and the request card lives inside the scrolling root.
        Controls.Add(_scrollHost);
        Controls.Add(footer);
    }

    // Expanding Advanced reveals content below the fold, so bring the
    // body into view rather than leaving the owner to find it. Focus is
    // deliberately left alone: an expansion must never move the caret
    // to a decision button.
    private void OnAdvancedExpandedChanged(object? sender, EventArgs e)
    {
        RefreshAdvancedEnabledState();
        if (_advanced.Expanded) RevealInScrollHost(_advanced);
        else if (!_requestDominates) ScrollRootToTop();
    }

    // Brings a control into view inside the root scroll host. A card
    // that appears off-screen is indistinguishable from no card.
    private void RevealInScrollHost(Control control)
    {
        if (control is null || !control.Visible || _scrollHost is null) return;
        if (_scrollHost.ClientSize.Width <= 0) return;
        try { _scrollHost.ScrollControlIntoView(control); }
        catch (InvalidOperationException) { }
    }

    private void ScrollRootToTop()
    {
        if (_scrollHost is null) return;
        try { if (_scrollHost.AutoScrollPosition != Point.Empty) _scrollHost.AutoScrollPosition = Point.Empty; }
        catch (InvalidOperationException) { }
    }

    private FlowLayoutPanel BuildAdvancedAdapterRow()
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        _lblAdapter.Text = "Adapter:";
        _lblAdapter.AutoSize = true;
        _lblAdapter.Margin = new Padding(0, 6, 8, 0);
        _cmbAdapter.DropDownStyle = ComboBoxStyle.DropDownList;
        _cmbAdapter.MinimumSize = new Size(220, 0);
        _cmbAdapter.Margin = new Padding(0, 4, 8, 4);
        _btnRefreshAdapters.Text = "Refresh";
        UiTheme.ApplyButton(_btnRefreshAdapters, UiTheme.ButtonRole.Demoted);
        row.Controls.AddRange(new Control[] { _lblAdapter, _cmbAdapter, _btnRefreshAdapters });
        return row;
    }

    private FlowLayoutPanel BuildAdvancedHostRow()
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        _lblCompanion.Text = "Host service";
        _lblCompanion.AutoSize = true;
        _lblCompanion.Margin = new Padding(0, 6, 8, 0);
        _btnCompanionAdvanced.Text = "Start";
        UiTheme.ApplyButton(_btnCompanionAdvanced, UiTheme.ButtonRole.Demoted);
        _btnCompanionAdvanced.Margin = new Padding(0, 4, 8, 4);
        _lblDashboard.Text = "Dashboard";
        _lblDashboard.AutoSize = true;
        _lblDashboard.Margin = new Padding(0, 6, 16, 0);
        UiTheme.ApplyButton(_btnOpenDashboard, UiTheme.ButtonRole.Demoted);
        _btnOpenDashboard.Text = "Open ALVR Dashboard";
        row.Controls.AddRange(new Control[] { _lblCompanion, _btnCompanionAdvanced, _lblDashboard, _btnOpenDashboard });
        return row;
    }

    private FlowLayoutPanel BuildAdvancedPairingRow()
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        _btnExportPairing.Text = "Export pairing file";
        UiTheme.ApplyButton(_btnExportPairing, UiTheme.ButtonRole.Demoted);
        _btnExportPairing.Enabled = false;
        // Pairing fallback: on a machine that still owes setup the
        // primary row is taken by Set up VR, but the owner can and
        // must still pair a Quest while the host is running.
        _btnPairAdvanced.Text = "Pair headset";
        UiTheme.ApplyButton(_btnPairAdvanced, UiTheme.ButtonRole.Demoted);
        row.Controls.Add(_btnExportPairing);
        row.Controls.Add(_btnPairAdvanced);
        return row;
    }

    private FlowLayoutPanel BuildAdvancedManualSetupRow()
    {
        // Set up VR is always reachable here so the owner can
        // manually retry the unified setup path (driver switch,
        // network access, receiving enable) even after the
        // primary row's button has hidden itself.
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        // Deliberately not "Set up VR": the primary row already owns
        // that exact label, and a duplicate label makes a text search
        // over this window ambiguous about which button it found.
        _btnSetupRetry.Text = "Retry setup";
        UiTheme.ApplyButton(_btnSetupRetry, UiTheme.ButtonRole.Demoted);
        _btnSetupRetry.Margin = new Padding(0, 4, 8, 4);
        row.Controls.Add(_btnSetupRetry);
        return row;
    }

    private FlowLayoutPanel BuildAdvancedDiagnosticsRow()
    {
        // Receiving management lives here, so the request card only ever
        // carries the one decision it exists for: the code and
        // Approve / Reject / Hide.
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        UiTheme.ApplyButton(_btnPause, UiTheme.ButtonRole.Demoted);
        UiTheme.ApplyButton(_btnResume, UiTheme.ButtonRole.Demoted);
        UiTheme.ApplyButton(_btnTurnOff, UiTheme.ButtonRole.Demoted);
        UiTheme.ApplyButton(_btnForgetAll, UiTheme.ButtonRole.Demoted);
        row.Controls.AddRange(new Control[] { _btnPause, _btnResume, _btnTurnOff, _btnForgetAll });
        return row;
    }

    private FlowLayoutPanel BuildAdvancedAutoStartRow()
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
        };
        _chkAutoStart.Text = "Start with Windows";
        _chkAutoStart.AutoSize = true;
        _chkAutoStart.Margin = new Padding(0, 8, 0, 0);
        _chkAutoStart.MinimumSize = new Size(0, 34);
        row.Controls.Add(_chkAutoStart);
        return row;
    }

    // The width the single-column root is pinned to, derived from the
    // scroll host's actual client width. ClientSize already excludes the
    // vertical scrollbar, so no scrollbar allowance is deducted here. The
    // 32 px is the root's own left + right outer padding, and the result
    // is clamped so a viewport narrower than the padding cannot produce
    // a negative size.
    private static int RootContentTargetWidth(int viewportWidth)
        => Math.Max(0, viewportWidth - 32);

    private void UpdateRootMaximumSize()
    {
        if (_scrollHost is null) return;
        // Re-entrant by construction (see the guard field): the pin
        // below can change the client width that produced it. A nested
        // call is dropped here and picked up by the coalesced follow-up.
        if (_updatingRootMaximumSize) return;
        var appliedViewportWidth = 0;
        _updatingRootMaximumSize = true;
        try
        {
            appliedViewportWidth = _scrollHost.ClientSize.Width;
            if (appliedViewportWidth <= 0) return;
            var targetWidth = RootContentTargetWidth(appliedViewportWidth);
            // Height stays 0: the root is vertically AutoSize and must
            // keep growing downward, so only the width is constrained.
            // Both bounds are pinned to the same width, so the autosized
            // root follows the viewport in both directions.
            var bounds = new Size(targetWidth, 0);
            if (_rootContent is not null)
            {
                // Written only on a real change: an identical
                // MaximumSize/MinimumSize write restarts layout and
                // costs a pass for nothing.
                if (_rootContent.MaximumSize != bounds) _rootContent.MaximumSize = bounds;
                if (_rootContent.MinimumSize != bounds) _rootContent.MinimumSize = bounds;
            }
            // The prose lines wrap to the width that is actually available
            // rather than to a fixed pixel count, so a larger body font, a
            // 150% DPI window, or a narrow window wraps instead of clipping.
            // The comparison code is deliberately left uncapped.
            var textWidth = new Size(Math.Max(120, targetWidth), 0);
            if (_lblHeader.MaximumSize != textWidth) _lblHeader.MaximumSize = textWidth;
            if (_lblSubStatus.MaximumSize != textWidth) _lblSubStatus.MaximumSize = textWidth;
            if (_lblPanelStatus.MaximumSize != textWidth) _lblPanelStatus.MaximumSize = textWidth;
            if (_updateStatusLabel.MaximumSize != textWidth) _updateStatusLabel.MaximumSize = textWidth;
        }
        finally
        {
            _updatingRootMaximumSize = false;
        }
        // A viewport that moved while this update was in flight (the
        // scrollbar appearing or disappearing) is stranded by the guard
        // above, so it gets exactly ONE follow-up pass after the current
        // layout finishes - never a synchronous recursion, and never a
        // second callback while one is already pending. A viewport that
        // maps to the same target width schedules nothing.
        if (_scrollHost.ClientSize.Width == appliedViewportWidth) return;
        if (RootContentTargetWidth(_scrollHost.ClientSize.Width)
            == RootContentTargetWidth(appliedViewportWidth)) return;
        QueueRootWidthUpdate();
    }

    // One coalesced pass, requested through the UI queue so it runs
    // after the layout that stranded it has completed.
    private void QueueRootWidthUpdate()
    {
        if (_rootWidthUpdateQueued) return;
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        _rootWidthUpdateQueued = true;
        try
        {
            BeginInvoke(new Action(() => {
                _rootWidthUpdateQueued = false;
                if (IsDisposed || Disposing || !IsHandleCreated) return;
                UpdateRootMaximumSize();
            }));
        }
        catch (InvalidOperationException) { _rootWidthUpdateQueued = false; } /* Window closed during callback. */
    }

    private void WireEvents()
    {
        FormClosing += OnFormClosing;
        Resize += OnResize;

        Activated += (_, _) => {
            if (_recovery is null) return;
            RefreshSteamStatus(); RefreshSteamVrStatus(); RefreshRuntimeStatus();
            TriggerBackgroundUpdateCheck();
        };
        _btnRefreshAdapters.Click += (_, _) => RefreshAdapters();
        _cmbAdapter.SelectedIndexChanged += (_, _) => OnAdapterPicked();
        // The single update action. One click resolves the release,
        // downloads it if needed, verifies it, and hands off to the
        // existing installer worker. There is no second in-app
        // Install prompt; the OS consent surface still applies.
        _btnUpdate.Click += (_, _) => BeginUpdateFlow();
        // Cancel only ever invalidates the in-flight attempt. It
        // never discards a verified download and never resumes the
        // attempt later.
        _btnCancelUpdate.Click += (_, _) => CancelUpdateFlow();
        // Manual metadata re-check. Metadata only: it never installs.
        _btnCheckUpdate.Click += (_, _) => TriggerUpdateCheck(force: true);
        _btnOpenSteamPage.Click += (_, _) => OpenUrl("https://store.steampowered.com/about/");
        _btnInstallSteamVr.Click += (_, _) => OpenUrl(_svc.SteamVr.Discover().Installed ? "steam://rungameid/250820" : "steam://install/250820");
        _btnVcRedistPage.Click += async (_, _) => await PrepareVr();
        _btnCompanionToggle.Click += (_, _) => ToggleCompanion();
        _btnCompanionAdvanced.Click += (_, _) => ToggleCompanion();
        _btnOpenDashboard.Click += (_, _) => OpenDashboard();
        _btnExportPairing.Click += (_, _) => ExportPairing();
        _btnPairHeadset.Click += async (_, _) => await PairHeadset();
        _btnPairAdvanced.Click += async (_, _) => await PairHeadset();
        _btnSetupNetwork.Click += async (_, _) => await PrepareVr();
        _btnSetupRetry.Click += async (_, _) => await PrepareVr();
        _chkAutoStart.CheckedChanged += (_, _) => OnAutoStartToggled();
        WireReceivingUxEvents();
    }

    private void InitialPopulation()
    {
        _settings = _svc.SettingsStore.Load();
        // The session flag mirrors the persisted flag on load so
        // the coordinator's first tick observes the right
        // posture. It is intentionally NOT persisted itself.
        _settings.ReceivePairingRequestsSession = _settings.ReceivePairingRequests == true;
        _suppressAutoStartEvent = true;
        _chkAutoStart.Checked = StartupPreference.IsEnabled(_settings);
        _suppressAutoStartEvent = false;
        _recovery = new HostRecoveryController(_svc.Companion, _svc.Adapters, _svc.IntegrityVerifier, BuildCompanionSpec);
        RefreshAdapters();
        RefreshSteamStatus();
        RefreshSteamVrStatus();
        RefreshCompanionStatus();
        RefreshDashboardStatus();
        RefreshFirewallStatus();
        EnsureTrayIcon();
        InitializeUpdateRepository();
        InitializeGuidedSetup();
        RefreshRuntimeStatus();
        RefreshHostReady();
        StartReceivingCoordinator();
        if (_settings.AutoStartWithWindows)
        {
            try { _svc.AutoStart.Enable(StartupCommand()); }
            catch (Exception ex) { LogStatus("Windows startup needs attention: " + ex.Message); }
        }
        if (_settings.RestoreCompanionOnStartup) _recovery.RequestStart();
        ReconcileHost();
        // Explicit startup automatic metadata check. The shared
        // repository throttles by the persisted 6 h success / 15 min
        // failure windows; on a fresh app process the throttle is
        // effectively open (timestamps do not persist across process
        // boundaries). The timer keeps dispatching this on a 1 s
        // tick so any user-visible busy window (VR setup, update
        // handoff, SteamVR runtime busy) suppresses it cleanly via
        // the same gate.
        TriggerBackgroundUpdateCheck();
    }

    // An install that did not complete stands in the update footer until
    // a real update attempt starts. The activity log is collapsed by
    // default, so a failure that only reached the log was invisible
    // exactly when it mattered.
    private string? _priorUpdateNotice;
    private string? _priorOutcomePath;
    private DateTime _priorOutcomeStamp;
    private long _priorOutcomeBytes;

    // Strict success: the outcome file is the only authority, and every
    // measured value has to agree with this build before the update is
    // called done.
    private static bool IsStrictUpdateSuccess(UpdateOutcome outcome)
        => outcome.Kind == UpdateOutcomeKind.Success
            && outcome.ExpectedVersion == SignedRelease.CurrentVersion
            && outcome.InstalledFileVersion == SignedRelease.CurrentVersion
            && outcome.InstallerExitCode == 0
            && outcome.VerifyInstallExitCode == 0;

    private void SurfacePriorOutcome()
    {
        try
        {
            var path = Program.DefaultOutcomePath();
            if (!File.Exists(path)) return;
            var info = new FileInfo(path);
            var outcome = UpdateOutcome.TryLoad(path);
            if (outcome is null)
            {
                LogStatus("Could not read the last update result at " + path + ".");
                StandPriorUpdateNotice(path, info, RunningNotice(
                    "The last update result could not be read, so what the last install did is unknown."));
                return;
            }
            switch (outcome.Kind)
            {
                case UpdateOutcomeKind.Success when IsStrictUpdateSuccess(outcome):
                    LogStatus($"Update to {outcome.ExpectedVersion} completed on {outcome.Timestamp.ToLocalTime():yyyy-MM-dd HH:mm}.");
                    if (!string.IsNullOrEmpty(outcome.InstallerLogPath))
                        LogStatus("Installer log: " + outcome.InstallerLogPath);
                    // A verified success needs no standing notice.
                    try { File.Move(path, path + ".seen", overwrite: true); } catch { /* keep for retry */ }
                    break;
                case UpdateOutcomeKind.Success:
                    // Success reported, but not by a set of measured
                    // values this build can confirm. The wording never
                    // names a version it cannot compare, because a
                    // failed exit or a failed verification can make
                    // strict success false even when the versions match.
                    LogStatus($"Update needs checking: this manager is {SignedRelease.CurrentVersion}; the requested update was {outcome.ExpectedVersion}.");
                    StandPriorUpdateNotice(path, info,
                        "The last update could not be confirmed."
                        + (string.IsNullOrEmpty(outcome.ExpectedVersion)
                            || string.Equals(outcome.ExpectedVersion, SignedRelease.CurrentVersion, StringComparison.Ordinal)
                            ? $" This manager is running {SignedRelease.CurrentVersion}."
                            : $" This manager is running {SignedRelease.CurrentVersion}, not {outcome.ExpectedVersion}."));
                    break;
                case UpdateOutcomeKind.InstallerCanceled:
                    // A cancelled install is the owner's own action, not
                    // a failure, and is reported as exactly that.
                    LogStatus($"Last update to {outcome.ExpectedVersion} was cancelled: {outcome.Detail}");
                    if (!string.IsNullOrEmpty(outcome.InstallerLogPath))
                        LogStatus("Installer log: " + outcome.InstallerLogPath);
                    StandPriorUpdateNotice(path, info, RunningNotice(
                        $"The last update to {PriorVersion(outcome)} was cancelled before it finished."));
                    break;
                case UpdateOutcomeKind.InstallerFailed:
                case UpdateOutcomeKind.VerificationFailed:
                case UpdateOutcomeKind.ParentTimeout:
                case UpdateOutcomeKind.WorkerError:
                case UpdateOutcomeKind.JobInvalid:
                    LogStatus($"Last update to {outcome.ExpectedVersion} failed: {outcome.Detail}");
                    if (!string.IsNullOrEmpty(outcome.InstallerLogPath))
                        LogStatus("Installer log: " + outcome.InstallerLogPath);
                    StandPriorUpdateNotice(path, info, PriorOutcomeSentence(outcome));
                    break;
            }
        }
        catch (Exception ex)
        {
            LogStatus("Could not read prior update outcome: " + ex.Message);
        }
    }

    // The standing notice stays short: the worker's raw detail, exit
    // codes and installer paths belong in the activity log, which is
    // where they have always been written.
    private static string RunningNotice(string lead) =>
        $"{lead} This manager is running {SignedRelease.CurrentVersion}. See Activity log for details.";

    private static string PriorVersion(UpdateOutcome outcome)
        => string.IsNullOrEmpty(outcome.ExpectedVersion) ? "the requested release" : outcome.ExpectedVersion;

    // One factual sentence per outcome kind. Nothing here claims the
    // install was rolled back or left the previous build in place: the
    // worker does not report that, so the notice must not either.
    private static string PriorOutcomeSentence(UpdateOutcome outcome) => outcome.Kind switch
    {
        UpdateOutcomeKind.VerificationFailed => RunningNotice(
            "The last update's installation could not be verified."),
        UpdateOutcomeKind.JobInvalid => RunningNotice(
            "The last update was rejected as an invalid job, so the installer was never run."),
        UpdateOutcomeKind.ParentTimeout => RunningNotice(
            "The last update could not start because the previous manager was still open."),
        _ => RunningNotice($"The last update to {PriorVersion(outcome)} did not finish."),
    };

    // Holds the notice and the exact file it came from. The file is
    // deliberately left in place: the install really did not complete,
    // so the next launch has to say so again.
    private void StandPriorUpdateNotice(string path, FileInfo info, string notice)
    {
        _priorUpdateNotice = notice;
        _priorOutcomePath = path;
        _priorOutcomeStamp = info.LastWriteTimeUtc;
        _priorOutcomeBytes = info.Length;
        if (!IsDisposed && !Disposing && IsHandleCreated) RenderUpdateStatusLine(_updateSnapshot);
    }

    // Called when the flow publishes a real attempt stage. The old notice
    // is stood down and the exact file read at startup is moved aside -
    // never a newer one, so a concurrent worker's outcome is untouched.
    private void AcknowledgePriorUpdateOutcome()
    {
        if (_priorUpdateNotice is null && _priorOutcomePath is null) return;
        var notice = _priorUpdateNotice;
        var path = _priorOutcomePath;
        _priorUpdateNotice = null;
        _priorOutcomePath = null;
        if (path is not null)
        {
            try
            {
                var info = new FileInfo(path);
                if (info.Exists && info.LastWriteTimeUtc == _priorOutcomeStamp && info.Length == _priorOutcomeBytes)
                    File.Move(path, path + ".seen", overwrite: true);
            }
            catch (Exception ex) { LogStatus("Could not clear the previous update result: " + ex.Message); }
        }
        if (notice is not null) LogStatus("Previous update result cleared: " + notice);
        if (!IsDisposed && !Disposing && IsHandleCreated) RenderUpdateStatusLine(_updateSnapshot);
    }

    private void OnResize(object? sender, EventArgs e)
    {
        if (WindowState == FormWindowState.Minimized) HideToTray();
    }

    private bool _exitRequested;

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_installHandOffInFlight)
        {
            // The update worker has the manager copy and job; do not
            // intercept the close.
            return;
        }
        if (e.CloseReason == CloseReason.UserClosing && !_exitRequested)
        {
            // Tray close.
            e.Cancel = true;
            HideToTray();
            return;
        }
        // Explicit Exit (or process shutdown). Stop only the
        // owned companion child. Stop the pairing coordinator
        // BEFORE suspending the companion so it cannot keep
        // renewing or emit late errors while the companion is
        // being torn down.
        StopReceivingCoordinator();
        var result = _recovery.SuspendAndStop();
        if (result.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
        {
            LogStatus("Could not stop the host companion: " + result.Error + ". Retry Exit.");
            e.Cancel = true;
            _exitRequested = false;
            _recovery.Resume();
            // The host is still up; restart the coordinator so
            // the receiving-mode renewal can resume.
            StartReceivingCoordinator();
            return;
        }
        _statusTimer.Stop();
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        // An exiting manager must not leave an update continuation
        // that could fire after the process is gone.
        _updateFlow?.Dispose();
        _tray?.Dispose();
    }

    private void HideToTray()
    {
        Hide();
        if (!_shownFirstTimeTrayHint)
        {
            _shownFirstTimeTrayHint = true;
            _tray?.ShowBalloonTip(5000, "VibertemisVR Host Manager",
                "Still running in the tray. Right-click the icon to exit, or open this window again.",
                ToolTipIcon.Info);
        }
    }

    private void EnsureTrayIcon()
    {
        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Visible = true,
            Text = "VibertemisVR Host Manager",
        };
        var menu = new ContextMenuStrip();
        var open = new ToolStripMenuItem("Open manager");
        open.Click += (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); };
        var exitItem = new ToolStripMenuItem("Exit");
        exitItem.Click += (_, _) => { _exitRequested = true; Close(); };
        menu.Items.Add(open);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(exitItem);
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); };
    }

    private void RefreshAdapters()
    {
        if (_svc.Companion.IsRunning) return;
        _populatingAdapters = true;
        try
        {
            var adapters = _svc.Adapters.Enumerate().Where(a => HostRecoveryController.Usable(a.Address)).ToArray();
            _cmbAdapter.Items.Clear();
            foreach (var a in adapters) _cmbAdapter.Items.Add(a);
            var selected = HostRecoveryController.SelectAdapter(adapters, _settings.LastSelectedAdapterId, _settings.LastSelectedAdapterAddress);
            // Suggest the first NIC only for initial setup. Never persist a
            // programmatic fallback over a temporarily missing saved NIC.
            if (selected is null && string.IsNullOrEmpty(_settings.LastSelectedAdapterId) && string.IsNullOrEmpty(_settings.LastSelectedAdapterAddress))
                selected = adapters.OrderBy(a => a.IsTailscale).FirstOrDefault();
            if (selected is not null) _cmbAdapter.SelectedItem = selected;
        }
        catch (Exception ex) { LogStatus("Network list unavailable; automatic recovery will retry: " + ex.Message); }
        finally { _populatingAdapters = false; }
    }

    private void OnAdapterPicked()
    {
        if (_populatingAdapters || _cmbAdapter.SelectedItem is not NetworkAdapter a) return;
        _settings.LastSelectedAdapterId = a.Id;
        _settings.LastSelectedAdapterAddress = a.Address.ToString();
        _settings.CompanionListenAddress = a.Address.ToString();
        SaveSettings();
        _recovery?.NetworkChanged();
    }

    private void OnNetworkChanged(object? sender, EventArgs args)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        try { BeginInvoke(new Action(() => {
            if (IsDisposed || Disposing || _exitRequested) return;
            _recovery.NetworkChanged();
            ReconcileHost();
            RefreshAdapters();
        })); } catch (InvalidOperationException) { /* Window closed during callback. */ }
    }

    private CompanionLaunchSpec BuildCompanionSpec(NetworkAdapter adapter) => new(
        Path.Combine(_svc.Paths.ProgramsRoot, "manager", "bin", "vibertemis-host-companion.exe"),
        adapter.Address.ToString(), _settings.CompanionListenPort,
        adapter.Address.ToString(), _settings.CompanionListenPort,
        _svc.AlvrLocator.Resolve().SessionJsonPath, _svc.Paths.CompanionStateDir);

    private void ReconcileHost()
    {
        if (_exitRequested || _guidedSetupBusy || _pairingBusy) return;
        if (!RuntimeReady()) { _lblCompanion.Text = "Set up VR will install the required Windows runtime."; return; }
        var status = _recovery.Tick(_settings.LastSelectedAdapterId, _settings.LastSelectedAdapterAddress);
        if (_lastRecoveryMessage != status.Message) { _lastRecoveryMessage = status.Message; LogStatus(status.Message); }
        RefreshCompanionStatus();
        // The recovery controller's running-spec state is one of
        // the inputs to the receiving snapshot. Re-publish on
        // every reconcile so a Stop / Start transition is
        // observed by the coordinator without restarting it.
        PublishReceivingSnapshot();
    }

    private void RefreshRuntimeStatus()
    {
        bool ready = RuntimeReady();
        _lblVcRedist.Text = ready ? "VC++ runtime: ready" : "VC++ runtime: setup needed";
        _btnVcRedistPage.Visible = !ready;
        _btnVcRedistPage.Enabled = !_preparingVr && !_guidedSetupBusy && !_updateBusy && !_updateActionLocked;
        RefreshPrereqPanelVisibility();
    }

    private void RefreshSteamStatus()
    {
        var s = _svc.Steam.Locate();
        if (s.Installed)
        {
            _lblSteam.Text = "Steam: installed";
            _btnOpenSteamPage.Enabled = false;
        }
        else
        {
            _lblSteam.Text = "Steam: not installed";
            _btnOpenSteamPage.Enabled = true;
        }
        RefreshPrereqPanelVisibility();
    }

    private void RefreshSteamVrStatus()
    {
        var v = _svc.SteamVr.Discover();
        _lblSteamVr.Text = v.SteamVrReady ? "SteamVR: ready" : (v.Reason ?? "SteamVR: install");
        _btnInstallSteamVr.Text = v.Kind == VibertemisManager.Core.Steam.SteamVrDiscoveryKind.InstalledUninitialized
            ? "Finish SteamVR" : "Install SteamVR";
        _btnInstallSteamVr.Enabled = !v.SteamVrReady;
        RefreshPrereqPanelVisibility();
    }

    private void RefreshCompanionStatus()
    {
        var running = _svc.Companion.IsRunning;
        var blocked = _recovery.Status.State == HostRecoveryState.IntegrityBlocked;
        _lblCompanion.Text = _recovery.Status.Message;
        _cmbAdapter.Enabled = !running;
        _btnRefreshAdapters.Enabled = !running;
        _btnExportPairing.Enabled = running && _recovery.RunningSpec is not null;
        var canPair = running && _recovery.RunningSpec is not null && !_pairingBusy && !_updateActionLocked;
        _btnPairHeadset.Enabled = canPair;
        _btnPairAdvanced.Enabled = canPair;
        var startStopText = running || (_recovery.DesiredRunning && !blocked) ? "Stop" : "Start";
        _btnCompanionToggle.Text = startStopText;
        _btnCompanionAdvanced.Text = startStopText;
        _btnCompanionToggle.Enabled = (running || RuntimeReady()) && !_updateActionLocked;
        RefreshPrimaryActionVisibility();
        RefreshAdvancedEnabledState();
    }

    private void RefreshDashboardStatus()
    {
        var r = _svc.BusyChecker.Check();
        if (r.IsBusy)
        {
            _lblDashboard.Text = $"ALVR Dashboard: busy ({r.Reason}; {string.Join(", ", r.ActiveProcessNames)})";
            _btnOpenDashboard.Enabled = false;
        }
        else
        {
            _lblDashboard.Text = "ALVR Dashboard: ready";
            _btnOpenDashboard.Enabled = true;
        }
    }

    private void RefreshPrimaryActionVisibility()
    {
        if (_primaryRow is null) return;
        // A pending request owns the screen: its Approve / Reject
        // decision is the only thing the owner can act on, so the whole
        // row steps aside instead of offering a parallel action. It
        // comes back from the real probes below as soon as the request
        // is resolved or hidden.
        if (_requestDominates)
        {
            _primaryRow.Visible = false;
            SetPrimaryActionIntended(false);
            return;
        }
        _primaryRow.Visible = true;
        bool setupNeeded = IsSetupNeeded();
        bool running = _svc.Companion.IsRunning;
        // Set up VR is the unified setup path: it installs the runtime,
        // switches drivers, opens network access, and enables
        // receiving. It is shown whenever something is still owed.
        _btnSetupNetwork.Visible = setupNeeded;
        // Pairing is the point of the host, so it stays reachable
        // whenever the host runs - including on a machine that still
        // owes VR setup. Hiding it behind Set up VR would make a
        // perfectly pairable host look unpairable, and hiding it in
        // Advanced would bury the only action that needs a headset.
        _btnPairHeadset.Visible = running;
        // Start / Stop only leads when the host is not running yet;
        // a running host needs no toggle here (Advanced still has it).
        _btnCompanionToggle.Visible = !running && !setupNeeded;
        // Pair headset is a demoted secondary, so the row counts as
        // owning the screen only for Set up VR or Start.
        SetPrimaryActionIntended(setupNeeded || !running);
    }

    private void SetPrimaryActionIntended(bool visible)
    {
        if (_primaryActionIntended == visible) return;
        _primaryActionIntended = visible;
        RefreshUpdateButtonStyle();
    }

    // Exactly one prominent action per screen. The update action gets
    // the accent only when a real update is waiting to be taken AND
    // nothing more specific is on screen: a pairing decision the owner
    // has to make, or the Set up VR / Start primary. Otherwise it is an
    // ordinary native button and the thing that matters keeps the
    // accent. Deliberately isolated to _btnUpdate and deliberately free
    // of RenderUpdateUi, which calls RefreshCompanionStatus and would
    // recurse.
    private void RefreshUpdateButtonStyle()
    {
        var s = _updateSnapshot;
        bool updateWaiting = s.HasAvailable || s.HasDownloaded;
        bool rolePrimary = updateWaiting && !_requestDominates && !_primaryActionIntended;
        var role = rolePrimary ? UiTheme.ButtonRole.Primary : UiTheme.ButtonRole.Demoted;
        if (_updateButtonRole == role) return;
        _updateButtonRole = role;
        UiTheme.ApplyButton(_btnUpdate, role);
    }

    // Setup is needed when any of the inputs the Set up VR
    // action repairs is missing. Driven by real runtime / install
    // probes, not by the receiving preference (which has its own
    // primary path through PairHeadset).
    private bool IsSetupNeeded()
    {
        if (!RuntimeReady()) return true;
        if (!_svc.Steam.Locate().Installed) return true;
        if (!_svc.SteamVr.Discover().SteamVrReady) return true;
        if (!VrDriverPrepared()) return true;
        if (_recovery?.Status.State == HostRecoveryState.IntegrityBlocked) return true;
        if (_recovery?.Status.State == HostRecoveryState.StopFailed) return true;
        return false;
    }

    // Steam and SteamVR being installed is not readiness: the bundled
    // driver still has to be registered in SteamVR and its compatible
    // session has to exist. Asking only about the first two hid Set up
    // VR before the very first VR configuration. Inspection only - this
    // never registers or writes anything.
    private bool VrDriverPrepared()
    {
        try
        {
            var prepared = new VrSetup(_svc.Paths.ProgramsRoot,
                Path.Combine(_svc.Paths.LocalAppData, "openvr", "openvrpaths.vrpath"),
                static () => { },
                static () => { }).IsPrepared();
            SetVrReadinessFailure(null);
            return prepared;
        }
        catch (Exception ex)
        {
            // An inspection that could not complete is not readiness, so
            // this never claims the driver is prepared. Set up VR stays
            // offered and the reason is published to the readiness line
            // rather than only to the collapsed activity log.
            SetVrReadinessFailure("the SteamVR driver registration could not be read (" + ex.Message + ")");
            return false;
        }
    }

    // A failed readiness inspection is an owner-visible failure, but
    // only while it is actually failing, so the line is withdrawn as
    // soon as a later inspection succeeds.
    private void SetVrReadinessFailure(string? detail)
    {
        var message = detail is null ? null
            : "Set up VR could not confirm VR readiness: " + detail + ". Choose Set up VR again.";
        if (_vrReadinessFailure == message) return;
        _vrReadinessFailure = message;
        if (message is null)
        {
            // Only ever withdraw this line, never another action's failure.
            if (_actionError is not null && _actionError.StartsWith("Set up VR could not confirm VR readiness:", StringComparison.Ordinal))
                SetActionError(null);
        }
        else FailAction(message);
    }

    private void RefreshPrereqPanelVisibility()
    {
        if (_prereqPanel is null) return;
        // Prerequisites are setup instructions, not part of a pairing
        // decision. They step aside for a pending request and are
        // re-probed when it resolves.
        if (_requestDominates)
        {
            _prereqPanel.Visible = false;
            return;
        }
        bool steamOk = _svc.Steam.Locate().Installed;
        bool steamVrOk = _svc.SteamVr.Discover().SteamVrReady;
        bool runtimeOk = RuntimeReady();
        // Each row hides independently so the form only shows the
        // prereq the owner still owes. The outer panel hides when
        // every row is hidden.
        _lblSteam.Visible = !steamOk;
        _btnOpenSteamPage.Visible = !steamOk;
        _lblSteamVr.Visible = !steamVrOk;
        _btnInstallSteamVr.Visible = !steamVrOk;
        _lblVcRedist.Visible = !runtimeOk;
        _btnVcRedistPage.Visible = !runtimeOk;
        _prereqPanel.Visible = !(steamOk && steamVrOk && runtimeOk);
    }

    private void RefreshAdvancedEnabledState()
    {
        if (_advanced is null) return;
        bool running = _svc.Companion.IsRunning;
        _cmbAdapter.Enabled = !running;
        _btnRefreshAdapters.Enabled = !running;
        _btnExportPairing.Enabled = running && _recovery.RunningSpec is not null;
        // The Advanced Start/Stop toggle is reachable at all
        // times; it is only disabled when the host is in a
        // busy state (preparing VR, handoff, etc.).
        _btnCompanionAdvanced.Enabled = !_preparingVr && !_installHandOffInFlight && !_updateActionLocked;
    }

    private void RefreshFirewallStatus()
    {
        var alvr = _svc.AlvrLocator.Resolve();
        var exe = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "bin", "vibertemis-host-companion.exe");
        var inspection = _svc.FirewallAdapter.Inspect("Vibertemis Companion TCP 28540", exe, _settings.CompanionListenPort);
        LogStatus($"Firewall rule: {inspection.State}.");
    }

    private void ToggleCompanion()
    {
        if (_preparingVr || _installHandOffInFlight) return;
        if (_svc.Companion.IsRunning || (_recovery.DesiredRunning && _recovery.Status.State != HostRecoveryState.IntegrityBlocked))
        {
            RememberCompanion(false);
            var result = _recovery.RequestStop();
            if (result.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
                LogStatus("Failed to stop companion: " + result.Error);
        }
        else
        {
            OnAdapterPicked(); // Persist the suggested NIC only on explicit Start.
            if (StartupPreference.IsEnabled(_settings)) ApplyStartupPreference(true);
            RememberCompanion(true);
            _recovery.RequestStart();
        }
        ReconcileHost();
    }

    private void OpenDashboard()
    {
        if (_preparingVr || _installHandOffInFlight) return;
        var busy = _svc.BusyChecker.Check();
        if (busy.IsBusy)
        {
            LogStatus($"Refusing to open dashboard: {busy.Reason} ({string.Join(", ", busy.ActiveProcessNames)}).");
            return;
        }
        var loc = _svc.AlvrLocator.Resolve();
        var analysis = _svc.AlvrSettings.TryRead(loc.SessionJsonPath, out var a);
        if (!analysis)
        {
            LogStatus("Could not read ALVR session.json: " + (a?.Detail ?? "unknown"));
            return;
        }
        if (a!.Warning == AlvrLauncherWarning.MalformedSessionJson)
        {
            LogStatus("ALVR session.json is malformed: " + a.Detail);
            return;
        }
        if (a.Warning == AlvrLauncherWarning.OpenCloseSteamvrWithDashboardTrue)
        {
            LogStatus("Refusing to open dashboard: open_close_steamvr_with_dashboard=true would launch SteamVR.");
            return;
        }
        try
        {
            foreach (var relative in InstalledPayload.NativePaths)
                if (!_svc.IntegrityVerifier.Verify(Path.Combine(_svc.Paths.ProgramsRoot, relative), out _))
                    throw new InvalidOperationException("VR runtime integrity check failed. Reinstall the host package.");
            var psi = new ProcessStartInfo
            {
                FileName = loc.DashboardExePath,
                UseShellExecute = false,
                CreateNoWindow = false,
                WorkingDirectory = Path.GetDirectoryName(loc.DashboardExePath),
            };
            Process.Start(psi);
            LogStatus("ALVR Dashboard launched.");
        }
        catch (Exception ex)
        {
            LogStatus("ALVR Dashboard launch failed: " + ex.Message);
        }
    }

    private async void ExportPairing()
    {
        if (!_svc.Companion.IsRunning)
        {
            LogStatus("Start the companion first; pairing export reuses its advertised address.");
            return;
        }
        var spec = _recovery.RunningSpec;
        if (spec is null) { LogStatus("Restart the companion before exporting pairing."); return; }
        _btnExportPairing.Enabled = false;
        try
        {
            var result = await Task.Run(() => _svc.PairingExport.Run(spec, _svc.IntegrityVerifier, TimeSpan.FromSeconds(10)));
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = $"/select,\"{result.FilePath}\"",
                    UseShellExecute = true,
                };
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                LogStatus("Reveal pairing file failed: " + ex.Message + " (path: " + result.FilePath + ")");
            }
            LogStatus("Pairing export written: " + result.FilePath);
        }
        catch (Exception ex)
        {
            LogStatus("Pairing export failed: " + ex.Message);
        }
    }

    private async Task<bool> SetupNetworkAccess()
    {
        var helper = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "VibertemisNetworkHelper.exe");
        try
        {
            if (!_svc.IntegrityVerifier.Verify(helper, out _))
            {
                FailAction("Network access was not configured: the network helper failed its integrity check. "
                    + "Reinstall the host package, then choose Set up VR again.");
                return false;
            }
        }
        catch (Exception ex)
        {
            FailAction("Network access was not configured: the network helper could not be verified (" + ex.Message + "). "
                + "Reinstall the host package, then choose Set up VR again.");
            return false;
        }
        _btnSetupNetwork.Enabled = false;
        LogStatus("Approve the Windows prompt to configure network access...");
        try
        {
            var adapter = _cmbAdapter.SelectedItem as NetworkAdapter;
            var arguments = adapter?.IsTailscale == true
                ? "--setup-tailscale " + TailscaleNetwork.ParseAddress(adapter.Address.ToString()).ToString()
                : "--setup-network";
            var result = await Task.Run(() => _svc.UacHelper.Launch(helper, arguments));
            if (IsDisposed || Disposing) return false;
            if (result.Launched && result.Completed && result.ExitCode == 0)
            {
                LogStatus(adapter?.IsTailscale == true
                    ? "VPN access configured for this PC's Tailscale address. Connect Quest to the same tailnet before importing pairing."
                    : "Network access configured for trusted Private/Domain networks.");
                return true;
            }
            // The helper's own detail already distinguishes a cancelled
            // Windows prompt, a helper that is still running, and a real
            // failure, so it is kept verbatim instead of collapsing every
            // case into one retry hint.
            FailAction("Network access is not configured yet: "
                + (result.Error.Length > 0 ? result.Error : "The Windows prompt did not finish.")
                + " Choose Set up VR again to retry.");
            return false;
        }
        finally { if (!IsDisposed) _btnSetupNetwork.Enabled = true; }
    }

    private string StartupCommand() => $"\"{Path.Combine(_svc.Paths.ProgramsRoot, "manager", "VibertemisManager.App.exe")}\" --tray-only --silent";

    private void OnAutoStartToggled()
    {
        if (_suppressAutoStartEvent) return;
        var enabled = _chkAutoStart.Checked;
        if (ApplyStartupPreference(enabled) && enabled)
        {
            OnAdapterPicked();
            _recovery.RequestStart();
            ReconcileHost();
        }
    }

    private bool ApplyStartupPreference(bool enabled)
    {
        var before = (_settings.AutoStartWithWindows, _settings.RestoreCompanionOnStartup,
            _settings.KeepHostReadyAfterSignIn, _settings.StartupPreferencePersisted);
        AutoStartState? priorRun = null;
        try
        {
            priorRun = _svc.AutoStart.Inspect();
            if (enabled) _svc.AutoStart.Enable(StartupCommand()); else _svc.AutoStart.Disable();
            StartupPreference.Apply(_settings, enabled);
            _svc.SettingsStore.Save(_settings);
            LogStatus(enabled ? "Automatic hosting enabled after Windows sign-in." : "Automatic startup disabled. Stop hosting also stops this session.");
            return true;
        }
        catch (Exception ex)
        {
            (_settings.AutoStartWithWindows, _settings.RestoreCompanionOnStartup,
                _settings.KeepHostReadyAfterSignIn, _settings.StartupPreferencePersisted) = before;
            if (priorRun is not null)
            {
                try { if (priorRun.Enabled && priorRun.CommandLine is not null) _svc.AutoStart.Enable(priorRun.CommandLine); else _svc.AutoStart.Disable(); }
                catch (Exception rollback) { LogStatus("Windows startup needs repair: " + rollback.Message); }
            }
            _suppressAutoStartEvent = true;
            _chkAutoStart.Checked = StartupPreference.IsEnabled(_settings);
            _suppressAutoStartEvent = false;
            LogStatus("Could not save automatic hosting: " + ex.Message);
            return false;
        }
    }

    private void SaveSettings()
    {
        try { _svc.SettingsStore.Save(_settings); }
        catch (Exception ex) { LogStatus("Could not save settings: " + ex.Message); }
        // Republish the snapshot so a persisted setting change
        // (suppression deadline, receiving flag) is visible to
        // the coordinator on the next tick.
        PublishReceivingSnapshot();
    }

    private void RememberCompanion(bool enabled)
    {
        _settings.RestoreCompanionOnStartup = enabled;
        SaveSettings();
    }

    // Stage 1: metadata-only check. Triggered manually by the user
    // (force=true) or by the background tick (force=false, throttled).
    // The check is coalesced by the shared repository; concurrent
    // triggers share the in-flight handle.
    private void TriggerUpdateCheck(bool force)
    {
        if (_updateRepo == null) return;
        if (_updateBusy) return;
        // Do not even attempt the check while the user is handoff-ing
        // an install or preparing VR.
        if (_installHandOffInFlight || _preparingVr) return;
        try
        {
            _updateRepo.RequestCheck(force);
        }
        catch (InvalidOperationException) { /* not bound yet */ }
    }

    // Auto-trigger from the status timer or explicit Activated
    // handler. The repository throttles repeated calls (6 h
    // success, 15 min failure). Errors are absorbed because a busy
    // background check must not block gaming. The auto-trigger is
    // deferred while the host is busy (VR setup, install handoff,
    // SteamVR runtime busy); the next 1 s tick picks it up
    // automatically once the busy state clears.
    private void TriggerBackgroundUpdateCheck()
    {
        if (_updateRepo == null) return;
        if (!IsHandleCreated || Disposing || IsDisposed) return;
        if (_installHandOffInFlight || _preparingVr || _updateBusy) return;
        // Defer while SteamVR / ALVR Dashboard are running so the
        // check does not race an in-progress setup or download.
        var busy = _svc.BusyChecker.Check();
        if (busy.IsBusy) return;
        if (_updateRepo.ShouldRunByThrottle(false))
        {
            TriggerUpdateCheck(false);
        }
    }

    // The shared repository's UI observer. Runs on the repository's
    // scheduler; we marshal to the UI thread before mutating controls.
    private void OnUpdateSnapshot(UpdateRepository.Snapshot snapshot)
    {
        if (IsDisposed || Disposing) return;
        _updateSnapshot = snapshot;
        if (InvokeRequired)
        {
            try { BeginInvoke(new Action(RenderUpdateUi)); } catch (InvalidOperationException) { }
        }
        else
        {
            RenderUpdateUi();
        }
    }

    /// <summary>
    /// Adapter that implements <see cref="UpdateRepository.IObserver"/>
    /// without the form's OnUpdateSnapshot method having to take an
    /// explicit parameter type that does not match.
    /// </summary>
    private sealed class FormObserver : UpdateRepository.IObserver
    {
        private readonly MainForm _form;
        public FormObserver(MainForm form) { _form = form; }
        public void OnUpdate(UpdateRepository.Snapshot snapshot) => _form.OnUpdateSnapshot(snapshot);
    }

    private void RenderUpdateUi()
    {
        var s = _updateSnapshot;
        var flow = _updateFlow?.State;
        var stage = flow?.Stage ?? UpdateFlowStage.Idle;

        // Conflicting actions are disabled for the whole attempt and
        // the handoff, so setup / pairing / start cannot race an
        // install that is about to close the manager. A cancelling
        // attempt stays locked until the interrupted transport has
        // unwound, because only then is a retry safe.
        var locked = stage is UpdateFlowStage.Choosing or UpdateFlowStage.Downloading
            or UpdateFlowStage.Verifying or UpdateFlowStage.Cancelling
            or UpdateFlowStage.HandingOff or UpdateFlowStage.AwaitingSystem;
        _updateActionLocked = locked;
        bool idle = !locked && !_updateBusy && !_installHandOffInFlight && !_preparingVr;

        _btnUpdate.Text = UpdateStatusLineRenderer.ActionLabel(s, flow);
        _btnUpdate.Enabled = idle && _updateFlow is not null;
        // One action per state. The manual re-check is a secondary that
        // exists only while the primary is doing something else, so the
        // owner never sees two identical "Check for updates" buttons side
        // by side at rest, and it disappears while work is in flight.
        _btnCheckUpdate.Text = s.Checking ? "Checking…" : "Check for updates";
        _btnCheckUpdate.Visible = idle && !s.Checking
            && !UpdateStatusLineRenderer.PrimaryIsCheck(s, flow);
        _btnCheckUpdate.Enabled = _btnCheckUpdate.Visible;
        // Cancel is offered only while the attempt itself says it can
        // still be cancelled: not after the irreversible handoff
        // boundary, and not again while it is already cancelling.
        _btnCancelUpdate.Visible = flow is { CanCancel: true };
        _btnCancelUpdate.Enabled = _btnCancelUpdate.Visible;
        // Setup is held while an attempt is live so nothing else tears
        // the host down mid-download. Pairing and Start/Stop read the
        // same flag from RefreshCompanionStatus so their own rules
        // stay authoritative.
        _btnSetupNetwork.Enabled = idle;
        _btnSetupRetry.Enabled = idle;
        RefreshCompanionStatus();

        // Progress is either real bytes or indeterminate. Verification
        // has no meaningful percentage, so it never invents one, and a
        // cancelling attempt stops showing a bar it can no longer move.
        var showProgress = locked && stage != UpdateFlowStage.AwaitingSystem
            && stage != UpdateFlowStage.Cancelling;
        _updateProgress.Visible = showProgress;
        _updateProgressLabel.Visible = showProgress;
        if (!showProgress)
        {
            _updateProgress.Style = ProgressBarStyle.Continuous;
            _updateProgress.Value = 0;
            _updateProgressLabel.Text = "";
        }
        RenderUpdateStatusLine(s, flow);
        // A new snapshot can make an update the one thing worth
        // promoting, or retire the last one. RefreshUpdateButtonStyle
        // is a leaf: it never calls back into this render.
        RefreshUpdateButtonStyle();
    }

    // Persistent status ladder mirrors the Android hub badge. The
    // wording lives in UpdateStatusLineRenderer so the WinForms
    // label and the tests share a single source of truth; this
    // method is just the label write-through.
    private void RenderUpdateStatusLine(UpdateRepository.Snapshot s, UpdateFlowState? flow = null)
    {
        flow ??= _updateFlow?.State;
        string status = UpdateStatusLineRenderer.Render(s, flow);
        // A prior install that did not finish is printed above the live
        // status, not instead of it: the owner needs both the standing
        // problem and the current state at a glance. The label wraps, so
        // this stays readable in a narrow window.
        string text = _priorUpdateNotice is null ? status : _priorUpdateNotice + "\r\n" + status;
        if (_updateStatusLabel.Text != text) _updateStatusLabel.Text = text;
        // Tray notice — only when the form is hidden from the user
        // so a visible window never balloons. HideToTray() (the
        // X-to-tray gesture) calls Hide() without minimising, so
        // the guard uses (NOT Visible) OR (Minimized). The
        // deduper collapses repeated notifications for the same
        // (bucket, version) tuple; balloon click is routed to
        // the update UI via OnTrayBalloonClicked.
        if (_tray is not null && (!Visible || WindowState == FormWindowState.Minimized))
        {
            DeduplicatedUpdateTrayNotice.Bucket bucket = default;
            string version = "";
            string message = "";
            if (s.HasDownloaded)
            {
                bucket = DeduplicatedUpdateTrayNotice.Bucket.Downloaded;
                version = s.Downloaded!.Version;
                message = $"Update {version} is verified and ready. Open VibertemisVR Host Manager to install.";
            }
            else if (s.HasAvailable)
            {
                bucket = DeduplicatedUpdateTrayNotice.Bucket.Available;
                version = s.Available!.Version;
                message = $"Update {version} is available. Open VibertemisVR Host Manager to download.";
            }
            else if (!string.IsNullOrEmpty(s.LastError))
            {
                bucket = DeduplicatedUpdateTrayNotice.Bucket.Offline;
                version = SignedRelease.CurrentVersion;
                message = "Update check failed. Open VibertemisVR Host Manager to retry.";
            }
            // Pairing priority: do NOT consume the dedup slot
            // for an update while a pairing notification is
            // outstanding. The next eligible attempt (after
            // pairing resolves) sees the same bucket/version and
            // surfaces the deferred update.
            if (version.Length == 0) return;
            if (PairingNoticePending) return;
            if (!_updateNotice.TryFire(bucket, version)) return;
            ShowUpdateTrayBalloon("VibertemisVR Host Manager", message);
        }
    }

    // The single update action. Everything about the attempt lives
    // in UpdateFlowCoordinator: the release is pinned here (so a
    // newer background check cannot move the target mid-attempt),
    // the download is skipped when those exact bytes are already
    // cached, the bytes are re-verified at the execution boundary,
    // and the existing UpdateWorker handoff happens automatically.
    // There is no second in-app confirmation: this click is the
    // authorization. The OS installer consent surface still applies.
    private async void BeginUpdateFlow()
    {
        var flow = _updateFlow;
        if (flow == null || flow.IsBusy) return;
        if (_installHandOffInFlight || _preparingVr) return;
        // A pairing request owns the screen while it is pending. The
        // attempt is deferred, not lost: no cache is touched and the
        // owner re-clicks Update once the request is resolved.
        if (_latestPending is { State: "pending" } pending && !string.IsNullOrEmpty(pending.SessionId))
        {
            LogStatus("Approve or reject the headset pairing request first, then choose Update again. Nothing was downloaded.");
            RenderUpdateStatusLine(_updateSnapshot);
            return;
        }
        try
        {
            await flow.StartAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            LogStatus("Update unavailable: " + ex.Message);
        }
        finally
        {
            _updateBusy = false;
            _updateActionLocked = false;
            if (!IsDisposed && !Disposing) RenderUpdateUi();
        }
    }

    // Cancel only invalidates the in-flight attempt. The verified
    // cache is kept so a later Update resumes at verify/handoff, and
    // nothing runs automatically after the cancel. The coordinator
    // publishes the outcome, so a refused Cancel - nothing in flight,
    // or the irreversible handoff boundary already crossed - must not
    // print a cancellation the owner never got.
    private void CancelUpdateFlow()
    {
        if (_updateFlow?.Cancel() != true) return;
        if (!IsDisposed && !Disposing) RenderUpdateUi();
    }

    // Builds the coordinator with every side effect injected. This
    // is the only place the App decides what a stage actually does,
    // which keeps the state machine itself in Core and testable.
    private void InitializeUpdateFlow()
    {
        if (_updateRepo == null || _updateDownloader == null)
        {
            _btnUpdate.Enabled = false;
            // No update source at all, so there is nothing for the
            // secondary manual check to do either.
            _btnCheckUpdate.Visible = false;
            _updateStatusLabel.Text = "Update status: check unavailable \u2014 updates are not configured for this install.";
            return;
        }
        var repo = _updateRepo;
        var downloader = _updateDownloader;
        _updateFlow = new UpdateFlowCoordinator(
            snapshot: () => repo.Current,
            busy: () => _svc.BusyChecker.Check(),
            blockReason: () => UpdateBlockReason(),
            download: (target, token, progress) =>
                downloader.DownloadAsync(target.Release, _svc.Paths.UpdateCacheDir, token, progress),
            recordDownloaded: (target, file) =>
                repo.RecordDownloaded(target.Release, target.ManifestBytes, target.SignatureBytes, file),
            purgeDownloaded: target => repo.ClearDownloadedAfterInstall(target.Version),
            handoff: (target, token) => InvokeOnUiAsync(() => RunUpdateHandoffAsync(target, token)));
        _updateFlow.StateChanged += OnUpdateFlowStateChanged;
        _updateFlow.Notice += LogStatus;
    }

    // The coordinator resumes on a pool thread after a download, so
    // anything that touches the form, the recovery controller, or the
    // published receiving snapshot is marshalled back to the UI thread
    // first. This fails closed: the window is re-checked when the
    // delegate actually executes, not only when it is queued, and there
    // is deliberately no inline fallback that would run the body off
    // the UI thread.
    //
    // Abandonment only applies to a delegate that has NOT started. A
    // transient handle recreation (DPI change, style refresh) while the
    // body is already running must not fake a failure: the body may
    // have crossed the irreversible handoff boundary, so the worker it
    // launched can still commit. Once execution begins the lifecycle
    // handlers are detached and the completion tracks the real outcome.
    private Task<T> InvokeOnUiAsync<T>(Func<Task<T>> body)
    {
        var gone = new InvalidOperationException("The manager window closed before this step could run.");
        if (IsDisposed || Disposing || !IsHandleCreated) return Task.FromException<T>(gone);
        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Release()
        {
            HandleDestroyed -= Abandon;
            FormClosed -= Abandon;
            Disposed -= Abandon;
        }
        void Abandon(object? sender, EventArgs e)
        {
            Release();
            completion.TrySetException(gone);
        }
        HandleDestroyed += Abandon;
        FormClosed += Abandon;
        Disposed += Abandon;
        try
        {
            BeginInvoke(new Action(async () =>
            {
                // Release first: from here the body owns the outcome, and
                // no lifecycle event can fail a step that already started.
                Release();
                try
                {
                    // A destroyed handle abandons the waiter, and a
                    // recreated one lets this queued delegate run later.
                    // It must not launch side effects after that.
                    if (completion.Task.IsCompleted || IsDisposed || Disposing || !IsHandleCreated)
                    {
                        completion.TrySetException(gone);
                        return;
                    }
                    completion.TrySetResult(await body().ConfigureAwait(true));
                }
                catch (Exception ex) { completion.TrySetException(ex); }
            }));
        }
        catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException)
        {
            Release();
            return Task.FromException<T>(gone);
        }
        return completion.Task;
    }

    /// <summary>
    /// Why an update must wait right now. Shared by the flow's gate and
    /// the UI-thread recheck before the companion is stopped, so the
    /// two can never disagree about whether it is safe to proceed.
    /// Only genuinely external conflicts are listed: this attempt's own
    /// stage lock is not one of them, or the recheck would refuse every
    /// handoff it is supposed to authorise.
    /// </summary>
    private string UpdateBlockReason()
    {
        if (_preparingVr) return "Set up VR is running. Finish it, then choose Update again.";
        if (_installHandOffInFlight) return "An update is already being handed to Windows.";
        if (_latestPending is { State: "pending" } pending && !string.IsNullOrEmpty(pending.SessionId))
            return "A headset pairing request is waiting for a decision. Approve or reject it, then choose Update again.";
        if (_pairingBusy) return "Pairing is busy right now. Try Update again in a moment.";
        return "";
    }

    private void OnUpdateFlowStateChanged(UpdateFlowState state)
    {
        if (IsDisposed || Disposing) return;
        BeginInvokeSafe(() =>
        {
            // The event carries the state as it was at publication, but
            // it is dispatched later, so a dequeued event can already be
            // older than the flow. Rendering it would move the screen
            // backwards onto a stage the owner has already left.
            if (_updateFlow is not null && !ReferenceEquals(_updateFlow.State, state)) return;
            _updateBusy = state.Busy;
            // Only a stage that means the attempt is really moving bytes
            // or the installer retires the standing notice from the last
            // attempt. Choosing is deliberately excluded: the coordinator
            // publishes it before its own busy check, so a click that was
            // refused did no update work and the previous result is still
            // the truth about this install. Blocked and Failed are not an
            // attempt either, and a metadata check never reaches here.
            if (state.Stage is UpdateFlowStage.Downloading or UpdateFlowStage.Verifying
                or UpdateFlowStage.HandingOff or UpdateFlowStage.AwaitingSystem)
                AcknowledgePriorUpdateOutcome();
            if (state.Stage is UpdateFlowStage.Downloading or UpdateFlowStage.Verifying)
                ApplyUpdateProgress(state);
            RenderUpdateUi();
        });
    }

    // Stops only what the manager owns, then hands the pinned job to
    // the existing worker. SteamVR, ALVR Dashboard, and anything else
    // the user started are never stopped for them: a busy machine was
    // already refused before this point and rechecked.
    private async Task<UpdateHandoffOutcome> RunUpdateHandoffAsync(UpdateTarget target, CancellationToken token)
    {
        if (IsDisposed || Disposing) return UpdateHandoffOutcome.Failed("the manager is closing");
        // The flow's own gate ran on a pool thread and may be stale by
        // the time this delegate reaches the UI thread, so the same
        // decision is re-evaluated here - on the UI thread, immediately
        // before anything owned by the user-visible host is torn down:
        // busy SteamVR / ALVR, a pairing request or pairing work in
        // flight, Set up VR running, and the attempt's own
        // cancellation token. Failing this check stops the companion
        // from being stopped for an install that must not start.
        var blocked = UpdateHandoffGuard.Block(
            _svc.BusyChecker.Check(), UpdateBlockReason(), token);
        if (blocked is not null)
        {
            LogStatus(blocked);
            return UpdateHandoffOutcome.Failed(blocked);
        }
        // Stop the pairing coordinator BEFORE the orderly companion
        // shutdown so it cannot keep renewing or emit late errors
        // while the companion is being torn down.
        StopReceivingCoordinator();
        var stopped = _recovery.SuspendAndStop();
        if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
        {
            return ResumeAfterFailedHandoff("Could not stop the host companion: " + stopped.Error + ".");
        }
        try
        {
            token.ThrowIfCancellationRequested();
            UpdateHandoffResult result = await Task.Run(() => UpdateHandoff.Launch(
                _svc.Paths, _svc.Paths.UpdateCacheDir,
                target.CachedFilePath ?? string.Empty, target.Release,
                target.ManifestBytes, target.SignatureBytes)).ConfigureAwait(true);
            if (!result.WorkerStarted)
            {
                return ResumeAfterFailedHandoff("Update worker did not start. The current installation is unchanged.");
            }
            if (!result.ReadySignaled)
            {
                return ResumeAfterFailedHandoff("Update worker did not signal readiness in time. The current installation is unchanged.");
            }
            if (!result.CommitSignaled)
            {
                return ResumeAfterFailedHandoff("Update worker did not accept the install signal. The current installation is unchanged.");
            }
            // The worker owns the job and waits for this process to
            // exit. It is NOT installed yet, so the attempt stays in
            // AwaitingSystem and the manager closes only now.
            _installHandOffInFlight = true;
            _updateActionLocked = true;
            LogStatus("Update worker is ready; committing and closing the manager to apply the update.");
            BeginInvokeSafe(() =>
            {
                if (IsDisposed || Disposing) return;
                RenderUpdateUi();
                _exitRequested = true;
                Close();
            });
            return new UpdateHandoffOutcome(true, true, true, result.JobPath, null);
        }
        catch (OperationCanceledException)
        {
            return ResumeAfterFailedHandoff("Update cancelled before the installer started.");
        }
        catch (Exception ex)
        {
            return ResumeAfterFailedHandoff("Update handoff failed: " + ex.Message);
        }
    }

    // A failed handoff must leave the user exactly where they were:
    // the recovery controller and the pairing coordinator are both
    // running again and the verified download is still ready.
    private UpdateHandoffOutcome ResumeAfterFailedHandoff(string reason)
    {
        _installHandOffInFlight = false;
        if (!_exitRequested && !IsDisposed && !Disposing)
        {
            _recovery?.Resume();
            StartReceivingCoordinator();
        }
        LogStatus(reason);
        return UpdateHandoffOutcome.Failed(reason);
    }

    // Progress rendering. Bytes are only shown when the stage actually
    // knows them; verification has no honest percentage, so it runs a
    // marquee rather than inventing one.
    private void ApplyUpdateProgress(UpdateFlowState state)
    {
        _updateProgress.Visible = true;
        _updateProgressLabel.Visible = true;
        if (state.HasDeterminateProgress)
        {
            _updateProgress.Style = ProgressBarStyle.Continuous;
            _updateProgress.Value = (int)Math.Clamp(
                (state.BytesDone * 100) / state.BytesTotal, 0, 100);
            _updateProgressLabel.Text = string.Format(
                "{0} {1} of {2}",
                state.Stage == UpdateFlowStage.Downloading ? "Downloaded" : state.Message,
                UpdateRepository.FormatBytes(state.BytesDone),
                UpdateRepository.FormatBytes(state.BytesTotal));
            return;
        }
        _updateProgress.Style = ProgressBarStyle.Marquee;
        _updateProgress.MarqueeAnimationSpeed = 30;
        _updateProgressLabel.Text = state.Message ?? "";
    }

    private bool BeginInvokeSafe(Action action)
    {
        if (IsDisposed || Disposing) return true;
        try
        {
            if (InvokeRequired) BeginInvoke(action);
            else action();
        }
        catch (ObjectDisposedException) { return true; }
        catch (InvalidOperationException) { return true; }
        catch (System.ComponentModel.Win32Exception) { return true; }
        return false;
    }

    private void OpenUrl(string url)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true,
            };
            Process.Start(psi);
        }
        catch (Exception ex)
        {
            LogStatus("Open URL failed: " + ex.Message);
        }
    }

    // Records a failed owner action and republishes the readiness line.
    // Pass null to clear it, which every retry and every success does.
    private void SetActionError(string? message)
    {
        if (_actionError == message) return;
        _actionError = message;
        RefreshHostReady();
    }

    private void FailAction(string message)
    {
        LogStatus(message);
        SetActionError(message);
    }

    private void LogStatus(string line)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        var stamped = DateTime.Now.ToString("HH:mm:ss") + "  " + line;
        if (InvokeRequired)
        {
            try { BeginInvoke(new Action(() => { if (!IsDisposed && !Disposing) LogStatus(line); })); }
            catch (InvalidOperationException) { }
            return;
        }
        _lstStatus.Items.Add(stamped);
        while (_lstStatus.Items.Count > 500) _lstStatus.Items.RemoveAt(0);
        _lstStatus.TopIndex = _lstStatus.Items.Count - 1;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
            // Dispose invalidates any in-flight continuation so a
            // closing manager never resumes an update later.
            _updateFlow?.Dispose();
            if (_updateRepo != null && _updateObserver != null) _updateRepo.RemoveObserver(_updateObserver);
            _updateRepo?.Shutdown();
            _updateDownloader?.Dispose();
            _statusTimer.Dispose();
            _expiryTimer.Dispose();
            _wake.Dispose();
            StopReceivingCoordinator();
            _tray?.Dispose();
        }
        base.Dispose(disposing);
    }

    private void InitializeUpdateRepository()
    {
        try
        {
            var cache = new UpdateRepositoryDiskCache(new DirectoryInfo(_svc.Paths.ManagerStateDir));
            _updateDownloader = new ReleaseClient();
            var source = new HttpReleaseCheckSource(_updateDownloader, _svc.Paths.UpdateCacheDir);
            _updateRepo = new UpdateRepository(cache, new SystemClock(), new EmbeddedKeySource());
            _updateRepo.BindSource(source, TaskScheduler.Default);
            _updateObserver = new FormObserver(this);
            _updateRepo.AddObserver(_updateObserver);
            InitializeUpdateFlow();
            // Initial render from whatever hydration surfaced.
            OnUpdateSnapshot(_updateRepo.Current);
        }
        catch (Exception ex)
        {
            LogStatus("Update check unavailable: " + ex.Message);
            _updateRepo = null;
            _updateDownloader = null;
        }
    }

    private sealed class SystemClock : UpdateRepository.IClock
    {
        public DateTime Now => DateTime.UtcNow;
    }

    private sealed class EmbeddedKeySource : UpdateRepository.ITrustKeySource
    {
        public string Pem => SignedRelease.EmbeddedPublicKey();
    }

    /// <summary>
    /// Bridges <see cref="ReleaseClient"/> to the repository's
    /// <see cref="UpdateRepository.ICheckSource"/> contract. The
    /// transport does the actual signed-manifest fetch + verify;
    /// we surface a no-newer-available result for null/older
    /// releases so the repository's no-update success path applies.
    /// </summary>
    private sealed class HttpReleaseCheckSource : UpdateRepository.ICheckSource
    {
        private readonly ReleaseClient _client;
        private readonly string _cache;
        public HttpReleaseCheckSource(ReleaseClient client, string cache)
        {
            _client = client;
            _cache = cache;
        }
        public async Task<UpdateRepository.ICheckSource.CheckResult?> CheckAsync(CancellationToken cancellation)
        {
            Directory.CreateDirectory(_cache);
            var result = await _client.CheckAsync(cancellation).ConfigureAwait(false);
            if (result is null) return null;
            return new UpdateRepository.ICheckSource.CheckResult(
                result.Release, result.Manifest, result.Signature);
        }
    }
}

public sealed record UpdateHandoffResult(bool WorkerStarted, bool ReadySignaled, bool CommitSignaled, string? JobPath);

public static class UpdateHandoff
{
    public static readonly TimeSpan ReadyHandshakeTimeout = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan CommitSendTimeout = TimeSpan.FromSeconds(5);

    // Build the per-user cache UUID directory, copy the running
    // manager exe under a helper basename (so BusyReason never
    // matches it), write the bounded UpdateJob JSON, launch the
    // worker, wait for READY, then send COMMIT. The worker only
    // installs after both signals are observed and the original
    // PID has exited.
    public static UpdateHandoffResult Launch(
        IPathResolver paths,
        string cacheRoot,
        string installerFile,
        SignedRelease release,
        byte[] manifestBytes,
        byte[] signatureBytes)
    {
        var ourExe = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName
            ?? throw new InvalidOperationException("Could not determine current executable path");
        if (!UpdateJobParser.IsSafeAbsolutePath(cacheRoot))
            return new UpdateHandoffResult(false, false, false, "Cache root is not fully qualified");
        var uuid = Guid.NewGuid().ToString("N");
        var cacheDir = Path.Combine(cacheRoot, uuid);
        Directory.CreateDirectory(cacheDir);
        var helperPath = Path.Combine(cacheDir, UpdateWorker.HelperExeName);
        File.Copy(ourExe, helperPath, overwrite: true);
        var installerInCache = Path.Combine(cacheDir, release.Windows.Filename);
        File.Copy(installerFile, installerInCache, overwrite: true);
        var originalHash = System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(ourExe));
        var readyEvent = "Local\\VibertemisUpdateWorker-" + uuid + "-Ready";
        var commitEvent = "Local\\VibertemisUpdateWorker-" + uuid + "-Commit";
        var job = new UpdateJob(
            Schema: 1,
            ExpectedVersion: release.Version,
            ExpectedSequence: release.Sequence,
            OriginalManagerPath: ourExe,
            ProgramsRoot: paths.ProgramsRoot,
            ParentPid: Environment.ProcessId,
            CacheDir: cacheDir,
            InstallerFilename: release.Windows.Filename,
            InstallerSha256: release.Windows.Sha256,
            InstallerBytes: release.Windows.Bytes,
            ManifestBytes: manifestBytes,
            SignatureBytes: signatureBytes,
            ReadyEventName: readyEvent,
            CommitEventName: commitEvent,
            OriginalManagerHash: originalHash);
        var jobPath = Path.Combine(cacheDir, "update-job.json");
        File.WriteAllText(jobPath, UpdateJobWriter.Serialize(job));

        var psi = new ProcessStartInfo
        {
            FileName = helperPath,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = cacheDir,
        };
        psi.ArgumentList.Add("--apply-update");
        psi.ArgumentList.Add(jobPath);
        using var readyHandle = new EventWaitHandle(false, EventResetMode.AutoReset, readyEvent);
        using var commitHandle = new EventWaitHandle(false, EventResetMode.AutoReset, commitEvent);
        System.Diagnostics.Process? worker;
        try { worker = System.Diagnostics.Process.Start(psi); }
        catch (Exception ex)
        {
            return new UpdateHandoffResult(false, false, false, "Worker start failed: " + ex.Message);
        }
        if (worker is null) return new UpdateHandoffResult(false, false, false, "Worker process null");
        using var workerHandle = worker;

        bool ready;
        try
        {
            ready = readyHandle.WaitOne(ReadyHandshakeTimeout);
        }
        catch { ready = false; }
        if (!ready) return new UpdateHandoffResult(true, false, false, jobPath);

        bool committed;
        try
        {
            commitHandle.Set();
            committed = true;
        }
        catch { committed = false; }
        return new UpdateHandoffResult(true, ready, committed, jobPath);
    }
}

// Disclosure container for the secondary management controls (network
// adapter, dashboard launch, manual export, receiving management) and
// for the activity log. WinForms ships no Expander, so this is a
// purpose-built control.
//
// Sizing is deliberately not automatic. A UserControl that both docks
// its children and measures itself with AutoSize collapses to a
// zero-height strip inside an AutoSize table row, which is what made
// the disclosure itself disappear. Here the panel is a Dock.Top strip
// whose height is a DPI-scaled toggle row plus the body, and
// GetPreferredSize reports exactly that.
public sealed class ExpanderLikePanel : UserControl
{
    // A collapsed disclosure must still be a real, clickable, focusable
    // row. This is a logical height, scaled per display, so a 150% DPI
    // window gets a taller row instead of a clipped one.
    private const int MinToggleLogicalHeight = 34;

    // AutoSize is off and the height is ours: a docked AutoSize child
    // leaves the row height to a font measurement that can collapse.
    private readonly CheckBox _toggle = new()
    {
        Text = "Advanced",
        AutoSize = false,
        Dock = DockStyle.Top,
        TextAlign = ContentAlignment.MiddleLeft,
    };

    // One control per row. A single-column table gives every row the
    // full body width, so an expanded Advanced wraps inside a narrow
    // window instead of running off the side of it.
    private readonly TableLayoutPanel _inner = new()
    {
        Dock = DockStyle.Top,
        ColumnCount = 1,
        RowCount = 0,
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        GrowStyle = TableLayoutPanelGrowStyle.AddRows,
        Visible = false,
        Margin = new Padding(0),
        Padding = new Padding(2, 0, 0, 0),
    };

    private int _appliedHeight = -1;
    private bool _applyingGeometry;
    // Per body row: the height to hold in logical units, or 0 when the
    // row grows with its content.
    private readonly List<int> _fixedRowLogicalHeights = new();
    // Rows that wrap. Their width is pinned to the body width so an
    // expanded Advanced reflows inside a narrow window instead of
    // running off the side of it, whatever the table hands them.
    private readonly List<Control> _wrappingRows = new();

    public Panel InnerPanel => _inner;
    public bool Expanded => _toggle.Checked;
    public event EventHandler? ExpandedChanged;

    public new string Text
    {
        get => _toggle.Text;
        set => _toggle.Text = value ?? "";
    }

    public ExpanderLikePanel()
    {
        // The parent owns the width (Dock.Top strip); this control owns
        // its height. No AutoSize on the panel itself, so there is no
        // measurement loop between it and its container.
        Dock = DockStyle.Top;
        AutoSize = false;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        _inner.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        // Docking runs from the last added control backwards, so the
        // toggle claims the top strip and the body starts below it.
        Controls.Add(_inner);
        Controls.Add(_toggle);
        _toggle.CheckedChanged += (_, _) =>
        {
            _inner.Visible = _toggle.Checked;
            ApplyGeometry();
            ExpandedChanged?.Invoke(this, EventArgs.Empty);
        };
        // Body content can change size after the fact: a row that wraps
        // in a narrower window, a longer status line, a DPI change. Each
        // of those arrives as a body resize, not as a toggle click.
        _inner.SizeChanged += (_, _) => ApplyGeometry();
        DpiChangedAfterParent += (_, _) => { _appliedHeight = -1; ApplyGeometry(); };
        ApplyGeometry();
    }

    // Appends one full-width row to the body. A row with a fixed logical
    // height (the activity log host, whose content is a scrolling list)
    // keeps exactly that height and scales it with the display; every
    // other row grows with its own content. Rows are added before the
    // panel is first shown, so an expansion measures real content.
    public void AddBodyRow(Control row, int fixedLogicalHeight = 0)
    {
        ArgumentNullException.ThrowIfNull(row);
        _fixedRowLogicalHeights.Add(fixedLogicalHeight);
        _inner.RowStyles.Add(RowStyleFor(fixedLogicalHeight));
        _inner.Controls.Add(row, 0, _inner.RowCount);
        _inner.RowCount++;
        // Anchored to all four edges so the row really fills its cell.
        row.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom;
        if (row.AutoSize) _wrappingRows.Add(row);
        if (Expanded) ApplyGeometry();
    }

    private RowStyle RowStyleFor(int logicalHeight) => logicalHeight > 0
        ? new RowStyle(SizeType.Absolute, LogicalToDeviceUnits(logicalHeight))
        : new RowStyle(SizeType.AutoSize);

    private int ToggleHeight => LogicalToDeviceUnits(Math.Max(MinToggleLogicalHeight, UiTheme.TextHeightLogical() + 8));

    private int BodyHeight
    {
        get
        {
            if (!_inner.Visible) return 0;
            // The body is a Dock.Top AutoSize container, so after a
            // layout pass its own height is the content height. Before
            // the first pass only the preferred size is meaningful.
            return Math.Max(_inner.Height, _inner.PreferredSize.Height);
        }
    }

    // Single writer for Height and for the body's own geometry. The
    // guard covers every mutation, because changing a row style or a row
    // width can raise the body's size change again. Styles are only
    // written when they are actually wrong, and the height is only
    // written when it changed, so the cycle settles after one layout.
    private void ApplyGeometry()
    {
        if (_applyingGeometry) return;
        _applyingGeometry = true;
        try
        {
            for (var i = 0; i < _fixedRowLogicalHeights.Count && i < _inner.RowStyles.Count; i++)
            {
                if (_fixedRowLogicalHeights[i] <= 0) continue;
                var want = RowStyleFor(_fixedRowLogicalHeights[i]);
                var have = _inner.RowStyles[i];
                if (have.SizeType == want.SizeType && have.Height == want.Height) continue;
                _inner.RowStyles[i] = want;
            }
            var bodyWidth = _inner.ClientSize.Width;
            if (bodyWidth > 0)
                foreach (var row in _wrappingRows)
                    if (row.MaximumSize.Width != bodyWidth) row.MaximumSize = new Size(bodyWidth, 0);
            var height = ToggleHeight + BodyHeight;
            if (height == _appliedHeight) return;
            _appliedHeight = height;
            _toggle.Height = ToggleHeight;
            _toggle.MinimumSize = new Size(0, ToggleHeight);
            Height = height;
            // The floor keeps a squeezing parent from clipping the body
            // back to the toggle row.
            MinimumSize = new Size(0, height);
        }
        finally { _applyingGeometry = false; }
        PerformLayout();
    }

    public override Size GetPreferredSize(Size proposedSize)
        => new(proposedSize.Width, ToggleHeight + BodyHeight);
}
