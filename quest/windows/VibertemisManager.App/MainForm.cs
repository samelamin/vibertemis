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

namespace VibertemisManager.App;

public sealed class MainForm : Form
{
    private readonly AppServices _svc;
    private readonly CliArgs _args;
    private NotifyIcon? _tray;
    private readonly Button _btnUpdate = new() { Text = "Check for updates", AutoSize = true };
    private readonly Button _btnCancelUpdate = new() { Text = "Cancel download", AutoSize = true, Visible = false };
    private readonly ReleaseClient _updates = new();
    private CancellationTokenSource? _updateCancellation;
    private bool _updateBusy;
    private readonly EventWaitHandle _wake = new(false, EventResetMode.AutoReset, Program.MutexName + "-Wake");
    private readonly System.Windows.Forms.Timer _statusTimer = new() { Interval = 1000 };
    private CompanionLaunchSpec? _runningSpec;

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
    private readonly CheckBox _chkRestoreCompanion = new();
    private readonly ListBox _lstStatus = new();
    private readonly Label _lblVersion = new();

    private UserSettings _settings = new();
    private bool _shownFirstTimeTrayHint;
    private bool _suppressAutoStartEvent;
    private bool _suppressRestoreEvent;

    public MainForm(AppServices svc, CliArgs args)
    {
        _svc = svc;
        _args = args;
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
        Shown += (_, _) => { if (_args.TrayOnly || _args.Silent) HideToTray(); };
        _statusTimer.Tick += (_, _) => {
            if (_wake.WaitOne(0)) { Show(); ShowInTaskbar = true; WindowState = FormWindowState.Normal; Activate(); }
            RefreshCompanionStatus();
            RefreshDashboardStatus();
        };
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

        _chkAutoStart.Text = "Start VibertemisVR Host Manager with Windows (tray-only)";
        root.Controls.Add(_chkAutoStart, 0, 9);
        root.SetColumnSpan(_chkAutoStart, 2);

        _chkRestoreCompanion.Text = "Restore companion on next manager launch";
        root.Controls.Add(_chkRestoreCompanion, 0, 10);
        root.SetColumnSpan(_chkRestoreCompanion, 2);

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
        _lblVersion.Text = "0.1.0.4-quest-preview";
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
        Load += (_, _) => OnLoad();
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
        _chkRestoreCompanion.CheckedChanged += (_, _) => OnRestoreToggled();

        _svc.Companion.Exited += (_, e) => OnCompanionExited(e);
    }

    private void InitialPopulation()
    {
        _settings = _svc.SettingsStore.Load();
        _suppressAutoStartEvent = true;
        _chkAutoStart.Checked = _settings.AutoStartWithWindows;
        _suppressAutoStartEvent = false;
        _suppressRestoreEvent = true;
        _chkRestoreCompanion.Checked = _settings.RestoreCompanionOnStartup;
        _suppressRestoreEvent = false;
        RefreshAdapters();
        RefreshSteamStatus();
        RefreshSteamVrStatus();
        RefreshCompanionStatus();
        RefreshDashboardStatus();
        RefreshFirewallStatus();
        EnsureTrayIcon();
        if (_settings.RestoreCompanionOnStartup && !_svc.Companion.IsRunning)
        {
            TryStartCompanion(silentIfFails: true);
        }
    }

    private void OnLoad()
    {
        if (_args.Silent || _args.TrayOnly) BeginInvoke(new Action(HideToTray));
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
        var result = _svc.Companion.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromSeconds(5));
        if (result.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
        {
            LogStatus("Could not stop the host companion: " + result.Error + ". Retry Exit.");
            e.Cancel = true;
            _exitRequested = false;
            return;
        }
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
        var adapters = _svc.Adapters.Enumerate();
        _cmbAdapter.Items.Clear();
        NetworkAdapter? selected = null;
        foreach (var a in adapters)
        {
            _cmbAdapter.Items.Add(a);
            if (selected is null)
            {
                if (_settings.LastSelectedAdapterId is not null
                    && string.Equals(_settings.LastSelectedAdapterId, a.Id, StringComparison.Ordinal))
                    selected = a;
                else if (_settings.LastSelectedAdapterAddress is not null
                    && string.Equals(_settings.LastSelectedAdapterAddress, a.Address.ToString(), StringComparison.Ordinal))
                    selected = a;
            }
        }
        if (selected is null && _cmbAdapter.Items.Count > 0)
            selected = (NetworkAdapter)_cmbAdapter.Items[0]!;
        if (selected is not null)
        {
            _cmbAdapter.SelectedItem = selected;
        }
        LogStatus($"Adapter enumeration: {adapters.Count} reachable IPv4 interface(s).");
    }

    private void OnAdapterPicked()
    {
        if (!(_cmbAdapter.SelectedItem is NetworkAdapter a)) return;
        _settings.LastSelectedAdapterId = a.Id;
        _settings.LastSelectedAdapterAddress = a.Address.ToString();
        _settings.CompanionListenAddress = a.Address.ToString();
        SaveSettings();
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
        _cmbAdapter.Enabled = !_svc.Companion.IsRunning;
        _btnRefreshAdapters.Enabled = !_svc.Companion.IsRunning;
        _btnExportPairing.Enabled = _svc.Companion.IsRunning;
        if (_svc.Companion.IsRunning)
        {
            _lblCompanion.Text = $"Host companion: running (PID {_svc.Companion.ProcessId})";
            _btnCompanionToggle.Text = "Stop companion";
            _btnCompanionToggle.Enabled = true;
            _btnExportPairing.Enabled = true;
        }
        else
        {
            _lblCompanion.Text = "Host companion: idle";
            _btnCompanionToggle.Text = "Start companion";
            _btnCompanionToggle.Enabled = _cmbAdapter.SelectedItem is NetworkAdapter;
        }
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
        if (_svc.Companion.IsRunning)
        {
            var result = _svc.Companion.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromSeconds(5));
            if (result.Outcome != CompanionStopOutcome.Stopped && result.Outcome != CompanionStopOutcome.AlreadyExited)
                LogStatus("Failed to stop companion: " + (result.Error ?? result.Outcome.ToString()));
            else { _runningSpec = null; RememberCompanion(false); }
        }
        else
        {
            TryStartCompanion(silentIfFails: false);
        }
        RefreshCompanionStatus();
    }

    private void TryStartCompanion(bool silentIfFails)
    {
        if (_cmbAdapter.SelectedItem is not NetworkAdapter a)
        {
            if (!silentIfFails) LogStatus("Pick an IPv4 adapter first.");
            return;
        }
        var alvr = _svc.AlvrLocator.Resolve();
        var exe = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "bin", "vibertemis-host-companion.exe");
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: a.Address.ToString(),
            listenPort: _settings.CompanionListenPort,
            AdvertiseAddress: a.Address.ToString(),
            AdvertisePort: _settings.CompanionListenPort,
            AlvrSessionPath: alvr.SessionJsonPath,
            StateDir: _svc.Paths.CompanionStateDir);
        try
        {
            _svc.Companion.Start(spec, _svc.IntegrityVerifier);
            _runningSpec = spec;
            RememberCompanion(true);
            RefreshCompanionStatus();
            LogStatus($"Companion started (PID {_svc.Companion.ProcessId}).");
        }
        catch (CompanionIntegrityException ex)
        {
            LogStatus("Integrity check failed: " + ex.Message);
        }
        catch (InvalidOperationException ex)
        {
            LogStatus("Cannot start companion: " + ex.Message);
        }
        catch (Exception ex)
        {
            LogStatus("Companion launch failed: " + ex.Message);
        }
    }

    private void OnCompanionExited(CompanionStopped e)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => OnCompanionExited(e)));
            return;
        }
        LogStatus($"Companion exited (PID {e.ProcessId}, code {e.ExitCode}, reason {e.Reason}).");
        RefreshCompanionStatus();
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
        if (_cmbAdapter.SelectedItem is not NetworkAdapter a)
        {
            LogStatus("Pick an IPv4 adapter first.");
            return;
        }
        if (!_svc.Companion.IsRunning)
        {
            LogStatus("Start the companion first; pairing export reuses its advertised address.");
            return;
        }
        var spec = _runningSpec;
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

    private void OnAutoStartToggled()
    {
        if (_suppressAutoStartEvent) return;
        var enabled = _chkAutoStart.Checked;
        var exe = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "VibertemisManager.App.exe");
        var cmd = $"\"{exe}\" --tray-only --silent";
        try
        {
            if (enabled) _svc.AutoStart.Enable(cmd);
            else _svc.AutoStart.Disable();
            _settings.AutoStartWithWindows = enabled;
            SaveSettings();
            LogStatus("Start with Windows: " + (enabled ? "enabled" : "disabled"));
        }
        catch (Exception ex)
        {
            LogStatus("Auto-start toggle failed: " + ex.Message);
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
        _suppressRestoreEvent = true;
        _chkRestoreCompanion.Checked = enabled;
        _suppressRestoreEvent = false;
        SaveSettings();
    }

    private void OnRestoreToggled()
    {
        if (_suppressRestoreEvent) return;
        _settings.RestoreCompanionOnStartup = _chkRestoreCompanion.Checked;
        SaveSettings();
        LogStatus("Restore companion on startup: " + (_settings.RestoreCompanionOnStartup ? "enabled" : "disabled"));
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
            var wasRunning = _svc.Companion.IsRunning;
            var stopped = _svc.Companion.Stop(CompanionStopReason.ManagerExit, TimeSpan.FromSeconds(5));
            if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
                throw new InvalidOperationException("Could not stop the host companion. " + stopped.Error);
            try
            {
                var start = new ProcessStartInfo(file) { UseShellExecute = true };
                start.ArgumentList.Add("/UPDATEPID=" + Environment.ProcessId);
                start.ArgumentList.Add("/DIR=" + _svc.Paths.ProgramsRoot);
                using var installer = Process.Start(start) ?? throw new IOException("Setup did not start");
                _exitRequested = true;
                Close();
            }
            catch { if (wasRunning) TryStartCompanion(silentIfFails: false); throw; }
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
        _lstStatus.TopIndex = _lstStatus.Items.Count - 1;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _updateCancellation?.Cancel();
            _updates.Dispose();
            _statusTimer.Dispose();
            _wake.Dispose();
            _tray?.Dispose();
        }
        base.Dispose(disposing);
    }
}
