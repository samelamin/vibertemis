using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.Settings;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private bool _preparingVr;
    private async Task PrepareVr()
    {
        if (_preparingVr || _updateBusy || _installHandOffInFlight) return;
        // A retry starts from a clean slate.
        SetActionError(null);
        _preparingVr = true;
        _btnSetupNetwork.Enabled = false;
        _btnSetupNetwork.Text = "Setting up…";
        _cmbAdapter.Enabled = false;
        _chkAutoStart.Enabled = false;
        // Setup and an update must not run at the same time; the
        // flow state owns the single update action.
        _btnUpdate.Enabled = false;
        _btnCheckUpdate.Enabled = false;
        _updateActionLocked = true;
        bool suspended = false;
        try
        {
            void RequireIdle() {
                var busy = _svc.BusyChecker.Check();
                if (busy.IsBusy) throw new InvalidOperationException("Close SteamVR and ALVR Dashboard before preparing VR. Your session will not be stopped automatically.");
            }
            void VerifyPayload() {
                foreach (var relative in InstalledPayload.NativePaths)
                    if (!_svc.IntegrityVerifier.Verify(Path.Combine(_svc.Paths.ProgramsRoot, relative), out _))
                        throw new InvalidDataException("VR runtime integrity check failed. Reinstall the host package.");
            }
            RequireIdle();
            if (!await RunGuidedSetupAsync()) return;
            RefreshSteamStatus(); RefreshSteamVrStatus();
            if (!_svc.Steam.Locate().Installed || !_svc.SteamVr.Discover().SteamVrReady)
                throw new InvalidOperationException("Install Steam and finish SteamVR setup using the buttons at the top of this window, then choose Set up VR again.");
            RequireIdle();
            var vrSetup = new VrSetup(_svc.Paths.ProgramsRoot,
                Path.Combine(_svc.Paths.LocalAppData, "openvr", "openvrpaths.vrpath"), RequireIdle, VerifyPayload);
            var conflicts = vrSetup.ConflictingDrivers();
            if (conflicts.Length > 0 && MessageBox.Show(this,
                "SteamVR currently uses another ALVR installation. Switch to the matching Vibertemis driver? Its files and settings will be kept.\n\n" + string.Join("\n", conflicts),
                "Switch VR driver", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            var stopped = _recovery.SuspendAndStop();
            suspended = true;
            if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
                throw new InvalidOperationException("Could not pause the connection service. Choose Set up VR again.");
            LogStatus("Registering the matching VR driver and checking settings...");
            await Task.Run(() => vrSetup.Prepare(conflicts.Length > 0));
            if (IsDisposed || Disposing) return;
            if (!await SetupNetworkAccess()) return;
            _recovery.Resume(); suspended = false;
            OnAdapterPicked();
            if (StartupPreference.IsEnabled(_settings)) ApplyStartupPreference(true);
            RememberCompanion(true);
            _recovery.RequestStart();
            ReconcileHost();
            // The Set up VR action is the visible consent gesture
            // for the seamless receiving mode. Enable it after
            // the companion is confirmed live so the coordinator
            // starts renewing immediately.
            EnableReceivingFromSuccess();
            RefreshHostReady();
            LogStatus("PC setup complete. Put on your Quest, choose Set up PC, and select this PC. Compare the code and approve once. SteamVR starts when you connect for VR.");
        }
        catch (Exception ex) { if (!IsDisposed) FailAction("Set up VR needs attention: " + ex.Message); }
        finally
        {
            if (suspended && !_exitRequested && !IsDisposed) _recovery.Resume();
            _preparingVr = false;
            if (!IsDisposed) {
                _btnSetupNetwork.Enabled = true;
                _btnSetupNetwork.Text = "Set up VR";
                _cmbAdapter.Enabled = true;
                _chkAutoStart.Enabled = true;
                _updateActionLocked = false;
                RenderUpdateUi();
            }
        }
    }
}
