// Main form for the Vibertemis Manager host application.
//
// Layout:
//   - Status section at the top shows current Steam / SteamVR /
//     companion / dashboard busy state. Status messages append
//     to the log list at the bottom of the form.
//   - Adapter choice is a single combo; the user picks the IPv4
//     interface the companion will bind to. The combo is
//     repopulated on Refresh.
//   - Buttons are explicit and disabled while their action is
//     impossible (e.g. Open Dashboard is greyed out when the
//     busy check reports AnotherDashboardBusy / SteamvrBusy).
//   - Setup Network Access shells the elevated helper with
//     tightly-scoped argv; the manager itself stays unelevated.
//   - Export pairing calls CompanionPairingExportRunner and
//     opens the resulting PRIVATE file location in Explorer via
//     Process.Start(UseShellExecute=true). The contents are
//     NEVER read or displayed by the manager.
//   - Close-to-tray is the default; the first time it happens
//     the manager shows a one-shot explanation. Explicit Exit
//     owns/stops only the owned companion child.
//   - Updates use a three-stage UX:
//       Check -> Download (cached in per-user state) -> Install.
//     The verified update is held in memory until the user
//     explicitly clicks "Install update". Cancellation is
//     available only during check/download; install is a single
//     confirmation, not a re-check.
//
// SteamVR is NEVER started by the manager. The "Install SteamVR"
// button is a steam://install/250820 URL dispatch; the user runs
// Steam itself. The manager never launches SteamVR on login,
// discovery, configuration, or any other implicit event.
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
    private readonly Button _btnUpdate = new() { Text = "Check for updates", AutoSize = true };
    private readonly Button _btnDownloadUpdate = new() { Text = "Download update", AutoSize = true, Visible = false, Enabled = false };
    private readonly Button _btnCancelUpdate = new() { Text = "Cancel", AutoSize = true, Visible = false };
    private readonly Button _btnInstallUpdate = new() { Text = "Install update", AutoSize = true, Visible = false, Enabled = false };
    private readonly ProgressBar _updateProgress = new() { Visible = false, Width = 220, Height = 16, Minimum = 0, Maximum = 100, Style = ProgressBarStyle.Continuous };
    private readonly Label _updateProgressLabel = new() { Visible = false, AutoSize = true, Text = "" };
    private UpdateRepository? _updateRepo;
    private ReleaseClient? _updateDownloader;
    private FormObserver? _updateObserver;
    private CancellationTokenSource? _updateCancellation;
    private bool _updateBusy;
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
    private readonly Label _lblDashboard = new();
    private readonly Button _btnOpenDashboard = new();
    private readonly Button _btnExportPairing = new();
    private readonly Button _btnSetupNetwork = new();
    private readonly CheckBox _chkAutoStart = new();
    private readonly ListBox _lstStatus = new();
    private readonly Label _lblVersion = new();

    private UserSettings _settings = new();
    private bool _shownFirstTimeTrayHint;
    private bool _suppressAutoStartEvent;

    public MainForm(AppServices svc, CliArgs args)
    {
        _svc = svc;
        Text = "VibertemisVR Host Manager";
        Width = 720;
        Height = 700;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(720, 700);
        AutoScaleMode = AutoScaleMode.Dpi;

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
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 13,
            Padding = new Padding(12),
            AutoSize = false,
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 200));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 12; i++) root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _lblHeader.Text = "VibertemisVR Host Manager";
        _lblHeader.Font = new Font(SystemFonts.MessageBoxFont!, FontStyle.Bold);
        root.Controls.Add(_lblHeader, 0, 0);
        root.SetColumnSpan(_lblHeader, 2);

        _lblAdapter.Text = "Reachable IPv4 adapter:";
        _cmbAdapter.DropDownStyle = ComboBoxStyle.DropDownList;
        _cmbAdapter.Width = 280;
        _btnRefreshAdapters.Text = "Refresh";
        var adapterPanel = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true };
        adapterPanel.Controls.Add(_cmbAdapter);
        adapterPanel.Controls.Add(_btnRefreshAdapters);
        root.Controls.Add(_lblAdapter, 0, 1);
        root.Controls.Add(adapterPanel, 1, 1);

        _lblSteam.Text = "Steam install: checking...";
        _btnOpenSteamPage.Text = "Open Steam download";
        _btnOpenSteamPage.Enabled = false;
        root.Controls.Add(_lblSteam, 0, 2);
        root.Controls.Add(_btnOpenSteamPage, 1, 2);

        _lblSteamVr.Text = "SteamVR install: checking...";
        _btnInstallSteamVr.Text = "Install SteamVR via Steam";
        _btnInstallSteamVr.Enabled = false;
        root.Controls.Add(_lblSteamVr, 0, 3);
        root.Controls.Add(_btnInstallSteamVr, 1, 3);

        _lblVcRedist.Text = "Prerequisite: VC++ runtime";
        _btnVcRedistPage.Text = "Install required runtime";
        _btnVcRedistPage.Enabled = true;
        root.Controls.Add(_lblVcRedist, 0, 4);
        root.Controls.Add(_btnVcRedistPage, 1, 4);

        _lblCompanion.Text = "Host companion: idle";
        _btnCompanionToggle.Text = "Start companion";
        _btnCompanionToggle.Enabled = false;
        root.Controls.Add(_lblCompanion, 0, 5);
        root.Controls.Add(_btnCompanionToggle, 1, 5);

        _lblDashboard.Text = "ALVR Dashboard: not started";
        _btnOpenDashboard.Text = "Open ALVR Dashboard";
        _btnOpenDashboard.Enabled = false;
        root.Controls.Add(_lblDashboard, 0, 6);
        root.Controls.Add(_btnOpenDashboard, 1, 6);

        _btnExportPairing.Text = "Export pairing file";
        _btnExportPairing.Enabled = false;
        root.Controls.Add(new Label { Text = "Pairing export" }, 0, 7);
        root.Controls.Add(_btnExportPairing, 1, 7);

        _btnSetupNetwork.Text = "Setup VR";
        root.Controls.Add(new Label { Text = "VR setup" }, 0, 8);
        root.Controls.Add(_btnSetupNetwork, 1, 8);

        _chkAutoStart.Text = "Keep host ready after Windows sign-in";
        root.Controls.Add(_chkAutoStart, 0, 9);
        root.SetColumnSpan(_chkAutoStart, 2);

        var readinessHint = new Label { Text = "Choose Setup VR once, then select this paired PC in Vibertemis on Quest. SteamVR starts when you connect for VR. Closing this window keeps the host ready in the tray.", AutoSize = true, MaximumSize = new Size(650, 0) };
        var details = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.TopDown, Dock = DockStyle.Fill };
        var advanced = new CheckBox { Text = "Advanced: network adapter, runtime dashboard and manual pairing", AutoSize = true };
        details.Controls.Add(readinessHint);
        details.Controls.Add(advanced);
        root.Controls.Add(details, 0, 10);
        root.SetColumnSpan(details, 2);
        void ShowAdvanced(bool visible) {
            foreach (Control control in root.Controls) {
                int row = root.GetRow(control);
                if (row is 1 or 6 or 7) control.Visible = visible;
            }
        }
        advanced.CheckedChanged += (_, _) => ShowAdvanced(advanced.Checked);
        ShowAdvanced(false);

        var progressRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, FlowDirection = FlowDirection.LeftToRight };
        progressRow.Controls.Add(_updateProgress);
        progressRow.Controls.Add(_updateProgressLabel);
        root.Controls.Add(new Label { Text = "Update download" }, 0, 11);
        root.Controls.Add(progressRow, 1, 11);

        _lstStatus.Dock = DockStyle.Fill;
        _lstStatus.HorizontalScrollbar = true;
        root.Controls.Add(_lstStatus, 0, 12);
        root.SetColumnSpan(_lstStatus, 2);

        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Bottom,
            ColumnCount = 2,
            RowCount = 1,
            Padding = new Padding(12, 0, 12, 12),
            AutoSize = true,
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        _lblVersion.Text = SignedRelease.CurrentVersion + "-quest-preview";
        _lblVersion.AutoSize = true;
        var updateActions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
        updateActions.Controls.Add(_btnUpdate);
        updateActions.Controls.Add(_btnDownloadUpdate);
        updateActions.Controls.Add(_btnCancelUpdate);
        updateActions.Controls.Add(_btnInstallUpdate);
        footer.Controls.Add(updateActions, 0, 0);
        _btnUpdate.Click += (_, _) => TriggerUpdateCheck(force: true);
        _btnDownloadUpdate.Click += (_, _) => DownloadPendingUpdate();
        _btnCancelUpdate.Click += (_, _) => _updateCancellation?.Cancel();
        _btnInstallUpdate.Click += (_, _) => InstallPendingUpdate();
        footer.Controls.Add(_lblVersion, 1, 0);

        Controls.Add(root);
        Controls.Add(footer);
        foreach (Control c in root.Controls)
        {
            if (c is Label or CheckBox or Button) c.AutoSize = true;
            c.Margin = new Padding(4, 7, 4, 7);
        }
        foreach (var button in new[] { _btnRefreshAdapters, _btnOpenSteamPage, _btnInstallSteamVr,
            _btnVcRedistPage, _btnCompanionToggle, _btnOpenDashboard, _btnExportPairing, _btnSetupNetwork })
            button.AutoSize = true;
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
        _btnOpenSteamPage.Click += (_, _) => OpenUrl("https://store.steampowered.com/about/");
        _btnInstallSteamVr.Click += (_, _) => OpenUrl(_svc.SteamVr.Discover().Installed ? "steam://rungameid/250820" : "steam://install/250820");
        _btnVcRedistPage.Click += async (_, _) => await PrepareVr();
        _btnCompanionToggle.Click += (_, _) => ToggleCompanion();
        _btnOpenDashboard.Click += (_, _) => OpenDashboard();
        _btnExportPairing.Click += (_, _) => ExportPairing();
        _btnSetupNetwork.Click += async (_, _) => await PrepareVr();
        _chkAutoStart.CheckedChanged += (_, _) => OnAutoStartToggled();
    }

    private void InitialPopulation()
    {
        _settings = _svc.SettingsStore.Load();
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
        if (_settings.AutoStartWithWindows)
        {
            try { _svc.AutoStart.Enable(StartupCommand()); }
            catch (Exception ex) { LogStatus("Windows startup needs attention: " + ex.Message); }
        }
        if (_settings.RestoreCompanionOnStartup) _recovery.RequestStart();
        ReconcileHost();
    }

    private void SurfacePriorOutcome()
    {
        try
        {
            var path = Program.DefaultOutcomePath();
            if (!File.Exists(path)) return;
            var outcome = UpdateOutcome.TryLoad(path);
            if (outcome is null) return;
            switch (outcome.Kind)
            {
                case UpdateOutcomeKind.Success when outcome.ExpectedVersion == SignedRelease.CurrentVersion
                    && outcome.InstalledFileVersion == SignedRelease.CurrentVersion
                    && outcome.InstallerExitCode == 0 && outcome.VerifyInstallExitCode == 0:
                    LogStatus($"Update to {outcome.ExpectedVersion} completed on {outcome.Timestamp.ToLocalTime():yyyy-MM-dd HH:mm}.");
                    if (!string.IsNullOrEmpty(outcome.InstallerLogPath))
                        LogStatus("Installer log: " + outcome.InstallerLogPath);
                    break;
                case UpdateOutcomeKind.Success:
                    LogStatus($"Update needs checking: this manager is {SignedRelease.CurrentVersion}; the requested update was {outcome.ExpectedVersion}.");
                    break;
                case UpdateOutcomeKind.InstallerFailed:
                case UpdateOutcomeKind.InstallerCanceled:
                case UpdateOutcomeKind.VerificationFailed:
                case UpdateOutcomeKind.ParentTimeout:
                case UpdateOutcomeKind.WorkerError:
                case UpdateOutcomeKind.JobInvalid:
                    LogStatus($"Last update to {outcome.ExpectedVersion} failed: {outcome.Detail}");
                    if (!string.IsNullOrEmpty(outcome.InstallerLogPath))
                        LogStatus("Installer log: " + outcome.InstallerLogPath);
                    break;
            }
            // Surface once; remove so a later launch starts clean.
            try { File.Move(path, path + ".seen", overwrite: true); } catch { /* keep for retry */ }
        }
        catch (Exception ex)
        {
            LogStatus("Could not read prior update outcome: " + ex.Message);
        }
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
        // owned companion child.
        var result = _recovery.SuspendAndStop();
        if (result.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
        {
            LogStatus("Could not stop the host companion: " + result.Error + ". Retry Exit.");
            e.Cancel = true;
            _exitRequested = false;
            _recovery.Resume();
            return;
        }
        _statusTimer.Stop();
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        _updateCancellation?.Cancel();
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
        if (_exitRequested || _guidedSetupBusy) return;
        if (!RuntimeReady()) { _lblCompanion.Text = "Setup VR will install the required Windows runtime."; return; }
        var status = _recovery.Tick(_settings.LastSelectedAdapterId, _settings.LastSelectedAdapterAddress);
        if (_lastRecoveryMessage != status.Message) { _lastRecoveryMessage = status.Message; LogStatus(status.Message); }
        RefreshCompanionStatus();
    }

    private void RefreshRuntimeStatus()
    {
        bool ready = RuntimeReady();
        _lblVcRedist.Text = ready ? "Windows runtime: ready" : "Windows runtime: setup needed";
        _btnVcRedistPage.Visible = !ready;
        _btnVcRedistPage.Enabled = !_preparingVr && !_guidedSetupBusy && !_updateBusy;
    }

    private void RefreshSteamStatus()
    {
        var s = _svc.Steam.Locate();
        if (s.Installed)
        {
            _lblSteam.Text = $"Steam installed: {s.SteamPath}";
            _btnOpenSteamPage.Enabled = false;
        }
        else
        {
            _lblSteam.Text = "Steam not installed.";
            _btnOpenSteamPage.Enabled = true;
        }
    }

    private void RefreshSteamVrStatus()
    {
        var v = _svc.SteamVr.Discover();
        _lblSteamVr.Text = v.SteamVrReady ? "SteamVR ready" : v.Reason ?? "Install SteamVR";
        _btnInstallSteamVr.Text = v.Kind == VibertemisManager.Core.Steam.SteamVrDiscoveryKind.InstalledUninitialized
            ? "Finish SteamVR setup" : "Install SteamVR";
        _btnInstallSteamVr.Enabled = !v.SteamVrReady;
    }

    private void RefreshCompanionStatus()
    {
        var running = _svc.Companion.IsRunning;
        var blocked = _recovery.Status.State == HostRecoveryState.IntegrityBlocked;
        _lblCompanion.Text = _recovery.Status.Message;
        _cmbAdapter.Enabled = !running;
        _btnRefreshAdapters.Enabled = !running;
        _btnExportPairing.Enabled = running && _recovery.RunningSpec is not null;
        _btnCompanionToggle.Text = running || (_recovery.DesiredRunning && !blocked) ? "Stop companion" : "Start companion";
        _btnCompanionToggle.Enabled = running || RuntimeReady();
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
                LogStatus("Network helper integrity check failed. Reinstall the host package.");
                return false;
            }
        }
        catch (Exception ex) { LogStatus("Cannot verify network helper: " + ex.Message); return false; }
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
            else
                LogStatus(result.Error.Length > 0 ? result.Error : "Network setup did not complete. Retry when ready.");
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
            LogStatus(enabled ? "Automatic hosting enabled after Windows sign-in." : "Automatic startup disabled. Stop companion also stops this session.");
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

    // Auto-trigger from the status timer. The repository throttles
    // repeated calls (6 h success, 15 min failure). Errors are absorbed
    // because a busy background check must not block gaming.
    private void TriggerBackgroundUpdateCheck()
    {
        if (_updateRepo == null) return;
        if (!IsHandleCreated || Disposing || IsDisposed) return;
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
        bool canDownload = !_updateBusy && s.HasAvailable
            && !(s.HasDownloaded && s.Available!.Version == s.Downloaded!.Version
                 && s.Available.Sequence == s.Downloaded.Sequence);
        bool canInstall = !_updateBusy && s.HasDownloaded;
        _btnDownloadUpdate.Visible = canDownload;
        _btnDownloadUpdate.Enabled = canDownload;
        _btnInstallUpdate.Visible = canInstall;
        _btnInstallUpdate.Enabled = canInstall;
        _btnCancelUpdate.Visible = _updateBusy && _updateCancellation != null;
        if (s.HasDownloaded)
        {
            _btnInstallUpdate.Text = $"Install update {s.Downloaded!.Version}";
        }
        else
        {
            _btnInstallUpdate.Text = "Install update";
        }
        if (s.Checking)
        {
            _updateProgressLabel.Visible = true;
            _updateProgressLabel.Text = "Checking signed Quest preview releases...";
            _updateProgress.Visible = true;
        }
        else if (_updateBusy)
        {
            _updateProgress.Visible = true;
        }
        else
        {
            _updateProgress.Visible = false;
            _updateProgressLabel.Visible = false;
            _updateProgressLabel.Text = "";
        }
    }

    // Stage 2: download the verified available metadata's installer.
    // The repository records the downloaded slot when the digest
    // matches and the per-version cache directory is populated.
    private async void DownloadPendingUpdate()
    {
        if (_updateRepo == null || _updateDownloader == null) return;
        if (_updateBusy || _installHandOffInFlight || _preparingVr) return;
        var s = _updateRepo.Current;
        if (!s.HasAvailable) return;
        var release = s.Available!;
        if (s.HasDownloaded && release.Version == s.Downloaded!.Version
            && release.Sequence == s.Downloaded.Sequence)
        {
            // Already downloaded this release; nothing to do.
            return;
        }
        if (MessageBox.Show(this,
            $"Download host update {release.Version}? Your settings and pairing will be kept.",
            "Host update", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        _updateBusy = true;
        using var cancellation = new CancellationTokenSource();
        _updateCancellation = cancellation;
        RenderUpdateUi();
        try
        {
            SetProgressUi(visible: true);
            LogStatus($"Downloading {release.Version} ({UpdateRepository.FormatBytes(release.Windows.Bytes)})...");
            var progress = new Progress<UpdateProgress>(p => ReportProgress(p));
            // 1) Transport writes the verified installer into the
            //    shared per-update cache directory
            //    (<state>/updates/<file>.part), verifies digest +
            //    size, and renames atomically. The path returned is
            //    the verified file under the flat cache dir.
            string downloadedPath = await _updateDownloader.DownloadAsync(
                release, _svc.Paths.UpdateCacheDir, cancellation.Token, progress);
            if (IsDisposed || Disposing) return;
            // 2) Stream the verified file into the per-version
            //    download directory, hash during the copy, persist
            //    the bound manifest + signature bytes, and publish
            //    the downloaded slot — all atomic and bounded by
            //    UpdateRepository.RecordDownloaded.
            _updateRepo.RecordDownloaded(
                release, s.AvailableManifestBytes!, s.AvailableSignatureBytes!,
                downloadedPath);
            SetProgressUi(visible: false);
            var busy = _svc.BusyChecker.Check();
            if (busy.IsBusy)
            {
                LogStatus($"Update {release.Version} downloaded. Close SteamVR and ALVR Dashboard, then click Install update.");
            }
            else
            {
                LogStatus($"Update {release.Version} downloaded. Click Install update to apply it.");
            }
        }
        catch (OperationCanceledException)
        {
            if (!IsDisposed) LogStatus("Update cancelled or timed out. Your current installation is unchanged.");
        }
        catch (Exception ex)
        {
            if (!IsDisposed) LogStatus("Update unavailable: " + ex.Message);
        }
        finally
        {
            _updateCancellation = null;
            _updateBusy = false;
            if (!IsDisposed)
            {
                SetProgressUi(visible: false);
                RenderUpdateUi();
            }
        }
    }

    private void ClearPending()
    {
        // The repository owns the downloaded slot now; clear via the
        // repository's API rather than touching any local fields.
        var s = _updateRepo?.Current;
        if (s?.HasDownloaded == true)
        {
            _updateRepo!.ClearDownloadedAfterInstall(s.Downloaded!.Version);
        }
    }

    // Stage 3: install. Single explicit confirmation. Re-verify the
    // cached bytes at the execution boundary; refuse if VR is busy
    // and request the user close it; stop only the owned companion
    // (Steam / SteamVR are never killed by the manager); copy the
    // manager exe to a per-user cache UUID subdirectory under a
    // helper basename so the installer's BusyReason cannot mistake
    // the worker for the running manager; write the bounded job
    // JSON; launch the worker; wait for the worker's readiness
    // event before exiting so the worker has the job in hand.
    private async void InstallPendingUpdate()
    {
        if (_installHandOffInFlight || _preparingVr) return;
        if (_updateRepo == null) return;
        var s = _updateRepo.Current;
        if (!s.HasDownloaded || s.Downloaded == null
            || string.IsNullOrEmpty(s.DownloadedFilePath)
            || s.DownloadedManifestBytes == null
            || s.DownloadedSignatureBytes == null) return;
        if (_updateBusy) return;
        var release = s.Downloaded;
        var file = s.DownloadedFilePath;
        var manifestBytes = s.DownloadedManifestBytes;
        var signatureBytes = s.DownloadedSignatureBytes;
        try
        {
            // Re-verify the cached bytes at the execution boundary.
            try
            {
                ReleaseClient.VerifyFile(file, release.Windows);
            }
            catch (System.Security.Cryptography.CryptographicException ex)
            {
                LogStatus("Cached update failed integrity check: " + ex.Message + ". Re-check for updates.");
                ClearPending();
                return;
            }

            var busy = _svc.BusyChecker.Check();
            if (busy.IsBusy)
            {
                LogStatus("Refusing to install: " + busy.Reason +
                    " (" + string.Join(", ", busy.ActiveProcessNames) +
                    "). Close SteamVR and ALVR Dashboard, then click Install update again. No further download is required.");
                return;
            }

            if (MessageBox.Show(this,
                $"Install host update {release.Version} now? The manager will close and the installer will run silently. Your settings and pairing will be kept.",
                "Install update", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;

            // Re-check busy after the user has had time to close things.
            busy = _svc.BusyChecker.Check();
            if (busy.IsBusy)
            {
                LogStatus("Close SteamVR and ALVR Dashboard before installing.");
                return;
            }

            // Stop only our own companion.
            var stopped = _recovery.SuspendAndStop();
            if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
            {
                _recovery.Resume();
                LogStatus("Could not stop the host companion: " + stopped.Error + ". Retry Install update.");
                return;
            }

            await LaunchUpdateHandoffAsync(release, file, manifestBytes, signatureBytes);
        }
        catch (Exception ex)
        {
            LogStatus("Install failed: " + ex.Message);
            if (!_exitRequested && !IsDisposed && !Disposing) _recovery?.Resume();
        }
    }

    private async Task LaunchUpdateHandoffAsync(SignedRelease release, string file, byte[] manifestBytes, byte[] signatureBytes)
    {
        _installHandOffInFlight = true;
        _btnUpdate.Enabled = false;
        _btnDownloadUpdate.Enabled = false;
        _btnInstallUpdate.Enabled = false;
        _btnCancelUpdate.Visible = false;
        _btnCancelUpdate.Enabled = false;
        LogStatus($"Handing off update {release.Version} to update worker...");
        UpdateHandoffResult? result = null;
        try
        {
            result = await Task.Run(() => UpdateHandoff.Launch(
                _svc.Paths, _svc.Paths.UpdateCacheDir, file, release,
                manifestBytes, signatureBytes));
        }
        catch (Exception ex)
        {
            LogStatus("Update handoff failed: " + ex.Message);
            RestoreUiAfterFailedHandoff();
            return;
        }
        if (result is null || !result.WorkerStarted)
        {
            LogStatus("Update worker did not start. The current installation is unchanged.");
            RestoreUiAfterFailedHandoff();
            return;
        }
        if (!result.ReadySignaled)
        {
            LogStatus("Update worker did not signal readiness in time. The current installation is unchanged.");
            RestoreUiAfterFailedHandoff();
            return;
        }
        // Worker has read+validated the job. Send the COMMIT signal so
        // the worker is allowed to install when the parent exits. After
        // COMMIT we close this manager.
        LogStatus("Update worker is ready; committing and closing manager to apply update.");
        if (!result.CommitSignaled)
        {
            LogStatus("Update worker did not receive commit signal. Aborting.");
            RestoreUiAfterFailedHandoff();
            return;
        }
        _exitRequested = true;
        Close();
    }

    private void RestoreUiAfterFailedHandoff()
    {
        if (!_exitRequested && !IsDisposed && !Disposing) _recovery?.Resume();
        _installHandOffInFlight = false;
        if (!IsDisposed && !Disposing)
        {
            _btnUpdate.Enabled = !_updateBusy && !_preparingVr;
            // Re-render the install/download buttons from the
            // repository snapshot so the user can still act on a
            // verified candidate that survived a failed handoff.
            RenderUpdateUi();
            _btnCancelUpdate.Visible = false;
        }
    }

    private void ReportStage(string stage)
    {
        if (IsDisposed || Disposing) return;
        if (BeginInvokeSafe(() => _updateProgressLabel.Text = stage)) return;
    }

    private void ReportProgress(UpdateProgress p)
    {
        if (IsDisposed || Disposing) return;
        BeginInvokeSafe(() =>
        {
            _updateProgressLabel.Text = $"{p.Stage} {p.BytesDone / 1048576} / {p.BytesTotal / 1048576} MB";
            var pct = p.BytesTotal > 0 ? (int)Math.Min(100, (p.BytesDone * 100) / p.BytesTotal) : 0;
            _updateProgress.Value = pct;
            if (p.Completed) _updateProgress.Value = 100;
        });
    }

    private void SetProgressUi(bool visible)
    {
        if (IsDisposed || Disposing) return;
        BeginInvokeSafe(() =>
        {
            _updateProgress.Visible = visible;
            _updateProgressLabel.Visible = visible;
            if (!visible)
            {
                _updateProgress.Value = 0;
                _updateProgressLabel.Text = "";
            }
        });
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

    private void LogStatus(string line)
    {
        var stamped = DateTime.Now.ToString("HH:mm:ss") + "  " + line;
        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => _lstStatus.Items.Add(stamped)));
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
            _updateCancellation?.Cancel();
            if (_updateRepo != null && _updateObserver != null) _updateRepo.RemoveObserver(_updateObserver);
            _updateRepo?.Shutdown();
            _updateDownloader?.Dispose();
            _statusTimer.Dispose();
            _wake.Dispose();
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
