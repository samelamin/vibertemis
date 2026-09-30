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
using VibertemisManager.Core.Settings;
using VibertemisManager.Core.Steam;
using VibertemisManager.Core.Update;
using VibertemisManager.Core.Recovery;
using System.Net.NetworkInformation;

namespace VibertemisManager.App;

public sealed class MainForm : Form
{
    private readonly AppServices _svc;
    private NotifyIcon? _tray;
    private readonly Button _btnUpdate = new() { Text = "Check for updates", AutoSize = true };
    private readonly Button _btnCancelUpdate = new() { Text = "Cancel download", AutoSize = true, Visible = false };
    private readonly ReleaseClient _updates = new();
    private CancellationTokenSource? _updateCancellation;
    private bool _updateBusy;
    private readonly EventWaitHandle _wake = new(false, EventResetMode.AutoReset, Program.MutexName + "-Wake");
    private readonly System.Windows.Forms.Timer _statusTimer = new() { Interval = 1000 };
    private HostRecoveryController _recovery = null!;
    private string? _lastRecoveryMessage;
    private bool _populatingAdapters;

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
        Height = 640;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(720, 640);
        AutoScaleMode = AutoScaleMode.Dpi;

        BuildLayout();
        WireEvents();
        _ = Handle; // Create the UI handle even for tray-only ApplicationContext startup.
        InitialPopulation();
        _statusTimer.Tick += (_, _) => {
            if (_wake.WaitOne(0)) { Show(); ShowInTaskbar = true; WindowState = FormWindowState.Normal; Activate(); }
            ReconcileHost();
            RefreshDashboardStatus();
        };
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;
        _statusTimer.Start();
    }

    private void BuildLayout()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 12,
            Padding = new Padding(12),
            AutoSize = false,
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 200));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 11; i++) root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
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
        _btnVcRedistPage.Text = "Open VC++ redistributable";
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

        _btnSetupNetwork.Text = "Setup Network Access";
        root.Controls.Add(new Label { Text = "Firewall" }, 0, 8);
        root.Controls.Add(_btnSetupNetwork, 1, 8);

        _chkAutoStart.Text = "Keep host ready after Windows sign-in";
        root.Controls.Add(_chkAutoStart, 0, 9);
        root.SetColumnSpan(_chkAutoStart, 2);

        var readinessHint = new Label { Text = "One-time setup: network access, VR driver and headset pairing. Stop pauses hosting; Start resumes it.", AutoSize = true, MaximumSize = new Size(650, 0) };
        root.Controls.Add(readinessHint, 0, 10);
        root.SetColumnSpan(readinessHint, 2);

        _lstStatus.Dock = DockStyle.Fill;
        _lstStatus.HorizontalScrollbar = true;
        root.Controls.Add(_lstStatus, 0, 11);
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
        _lblVersion.Text = "0.1.0.5-quest-preview";
        _lblVersion.AutoSize = true;
        var updateActions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
        updateActions.Controls.Add(_btnUpdate);
        updateActions.Controls.Add(_btnCancelUpdate);
        footer.Controls.Add(updateActions, 0, 0);
        _btnUpdate.Click += async (_, _) => await CheckForUpdates();
        _btnCancelUpdate.Click += (_, _) => _updateCancellation?.Cancel();
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

        _btnRefreshAdapters.Click += (_, _) => RefreshAdapters();
        _cmbAdapter.SelectedIndexChanged += (_, _) => OnAdapterPicked();
        _btnOpenSteamPage.Click += (_, _) => OpenUrl("https://store.steampowered.com/about/");
        _btnInstallSteamVr.Click += (_, _) => OpenUrl("steam://install/250820");
        _btnVcRedistPage.Click += (_, _) => OpenUrl("https://aka.ms/vc14/vc_redist.x64.exe");
        _btnCompanionToggle.Click += (_, _) => ToggleCompanion();
        _btnOpenDashboard.Click += (_, _) => OpenDashboard();
        _btnExportPairing.Click += (_, _) => ExportPairing();
        _btnSetupNetwork.Click += (_, _) => SetupNetworkAccess();
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
        if (_settings.AutoStartWithWindows)
        {
            try { _svc.AutoStart.Enable(StartupCommand()); }
            catch (Exception ex) { LogStatus("Windows startup needs attention: " + ex.Message); }
        }
        if (_settings.RestoreCompanionOnStartup) _recovery.RequestStart();
        ReconcileHost();
    }

    private void OnResize(object? sender, EventArgs e)
    {
        if (WindowState == FormWindowState.Minimized) HideToTray();
    }

    private bool _exitRequested;

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
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
                selected = adapters.FirstOrDefault();
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
        if (_exitRequested) return;
        var status = _recovery.Tick(_settings.LastSelectedAdapterId, _settings.LastSelectedAdapterAddress);
        if (_lastRecoveryMessage != status.Message) { _lastRecoveryMessage = status.Message; LogStatus(status.Message); }
        RefreshCompanionStatus();
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
        var v = _svc.SteamVr.Locate();
        if (v.Installed)
        {
            _lblSteamVr.Text = "SteamVR configured: " + string.Join("; ", v.RuntimePaths);
            _btnInstallSteamVr.Enabled = false;
        }
        else
        {
            _lblSteamVr.Text = "SteamVR not configured: " + (v.Reason ?? "");
            _btnInstallSteamVr.Enabled = true;
        }
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
        _btnCompanionToggle.Enabled = true;
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

    private async void SetupNetworkAccess()
    {
        var helper = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "VibertemisNetworkHelper.exe");
        try
        {
            if (!_svc.IntegrityVerifier.Verify(helper, out _))
            {
                LogStatus("Network helper integrity check failed. Reinstall the host package.");
                return;
            }
        }
        catch (Exception ex) { LogStatus("Cannot verify network helper: " + ex.Message); return; }
        _btnSetupNetwork.Enabled = false;
        LogStatus("Approve the Windows prompt to configure network access...");
        try
        {
            var result = await Task.Run(() => _svc.UacHelper.Launch(helper, "--setup-network"));
            if (IsDisposed || Disposing) return;
            if (result.Launched && result.Completed && result.ExitCode == 0)
                LogStatus("Network access configured for trusted Private/Domain networks.");
            else
                LogStatus(result.Error.Length > 0 ? result.Error : "Network setup did not complete. Retry when ready.");
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

    private async Task CheckForUpdates()
    {
        if (_updateBusy) return;
        _updateBusy = true;
        _btnUpdate.Enabled = false;
        _btnCancelUpdate.Visible = true;
        using var cancellation = new CancellationTokenSource();
        _updateCancellation = cancellation;
        try
        {
            LogStatus("Checking signed Quest preview releases...");
            var release = await _updates.CheckAsync(cancellation.Token);
            if (IsDisposed || Disposing) return;
            if (release is null) { LogStatus("No newer compatible signed Quest preview is available."); return; }
            if (MessageBox.Show(this, $"Download host update {release.Version}? Your settings and pairing will be kept.",
                "Host update", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            LogStatus($"Downloading {release.Version} ({release.Windows.Bytes / 1048576} MB)...");
            var file = await _updates.DownloadAsync(release, _svc.Paths.UpdateCacheDir, cancellation.Token);
            if (IsDisposed || Disposing) return;
            if (_svc.BusyChecker.Check().IsBusy)
            {
                LogStatus("Update downloaded. Close SteamVR and ALVR Dashboard, then check for updates again to install.");
                return;
            }
            if (MessageBox.Show(this, "Install the verified update now? The host manager will close and the setup wizard will open.",
                "Install update", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            if (_svc.BusyChecker.Check().IsBusy) { LogStatus("Close SteamVR and ALVR Dashboard before updating."); return; }
            // Recheck cached bytes at the execution boundary, then stop only our
            // own companion. Failure keeps this manager open and usable.
            ReleaseClient.VerifyFile(file, release.Windows);
            var stopped = _recovery.SuspendAndStop();
            if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
            {
                _recovery.Resume();
                throw new InvalidOperationException("Could not stop the host companion. " + stopped.Error);
            }
            try
            {
                var start = new ProcessStartInfo(file) { UseShellExecute = true };
                start.ArgumentList.Add("/UPDATEPID=" + Environment.ProcessId);
                start.ArgumentList.Add("/DIR=" + _svc.Paths.ProgramsRoot);
                using var installer = Process.Start(start) ?? throw new IOException("Setup did not start");
                _exitRequested = true;
                Close();
            }
            catch { _recovery.Resume(); ReconcileHost(); throw; }
        }
        catch (OperationCanceledException) { if (!IsDisposed) LogStatus("Update cancelled or timed out. Your current installation is unchanged."); }
        catch (Exception ex) { if (!IsDisposed) LogStatus("Update unavailable: " + ex.Message); }
        finally
        {
            _updateCancellation = null;
            _updateBusy = false;
            if (!IsDisposed) { _btnUpdate.Enabled = true; _btnCancelUpdate.Visible = false; }
        }
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
            _updates.Dispose();
            _statusTimer.Dispose();
            _wake.Dispose();
            _tray?.Dispose();
        }
        base.Dispose(disposing);
    }
}
