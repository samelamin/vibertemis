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
        _preparingVr = true;
        _btnSetupNetwork.Enabled = false;
        _cmbAdapter.Enabled = false;
        _chkAutoStart.Enabled = false;
        _btnUpdate.Enabled = false;
        _btnInstallUpdate.Enabled = false;
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
            var setup = new VrSetup(_svc.Paths.ProgramsRoot,
                Path.Combine(_svc.Paths.LocalAppData, "openvr", "openvrpaths.vrpath"), RequireIdle, VerifyPayload);
            var conflicts = setup.ConflictingDrivers();
            if (conflicts.Length > 0 && MessageBox.Show(this,
                "SteamVR currently uses another ALVR installation. Switch to the matching Vibertemis driver? Its files and settings will be kept.\n\n" + string.Join("\n", conflicts),
                "Switch VR driver", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            var stopped = _recovery.SuspendAndStop();
            suspended = true;
            if (stopped.Outcome is CompanionStopOutcome.Denied or CompanionStopOutcome.Timeout)
                throw new InvalidOperationException("Could not pause the connection service. Retry Prepare VR.");
            LogStatus("Registering the matching VR driver and checking settings...");
            await Task.Run(() => setup.Prepare(conflicts.Length > 0));
            if (IsDisposed || Disposing) return;
            if (!await SetupNetworkAccess()) return;
            _recovery.Resume(); suspended = false;
            OnAdapterPicked();
            if (StartupPreference.IsEnabled(_settings)) ApplyStartupPreference(true);
            RememberCompanion(true);
            _recovery.RequestStart();
            ReconcileHost();
            LogStatus("VR setup complete. Export pairing once and import it in Quest PCVR settings. Then choose Connect; SteamVR starts from the paired headset.");
        }
        catch (Exception ex) { if (!IsDisposed) LogStatus("VR setup needs attention: " + ex.Message); }
        finally
        {
            if (suspended && !_exitRequested && !IsDisposed) _recovery.Resume();
            _preparingVr = false;
            if (!IsDisposed) {
                _btnSetupNetwork.Enabled = true;
                _cmbAdapter.Enabled = true;
                _chkAutoStart.Enabled = true;
                _btnUpdate.Enabled = !_updateBusy;
                _btnInstallUpdate.Enabled = _pendingUpdate != null;
            }
        }
    }
}
