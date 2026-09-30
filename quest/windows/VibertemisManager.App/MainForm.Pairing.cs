using VibertemisManager.Core.Pairing;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private readonly Button _btnPairHeadset = new();
    private bool _pairingBusy;

    // Primary Pair headset path: enable persisted receiving
    // immediately and reveal the non-modal approval panel. The
    // legacy 2-minute one-shot window is gone; the seamless
    // receiving mode is the only path. If hosting is not
    // running, surface an actionable hint that points the
    // owner at Setup VR.
    private async Task PairHeadset()
    {
        if (_pairingBusy || _preparingVr || _updateBusy || _installHandOffInFlight) return;
        var spec = _recovery.RunningSpec;
        if (spec is null)
        {
            LogStatus("Pair headset needs the host service. Click Setup VR first; it enables headset pairing and persists the opt-in.");
            return;
        }
        _pairingBusy = true;
        try
        {
            try
            {
                using var probe = new LocalPairingClient(spec.StateDir);
                await probe.SendAsync("pending", null, CancellationToken.None);
            }
            catch (CompanionNotReadyException) { LogStatus("Companion is starting up. Try again in a second."); return; }
            catch (PairingAdminException pae) { LogStatus(pae.OwnerText); return; }
            catch (Exception ex)
            {
                LogStatus("Could not reach host service: " + ex.Message + ". Stop and Start hosting, then retry Pair headset.");
                return;
            }
            EnableReceivingFromSuccess();
            RefreshHostReady();
            RevealApprovalPanel();
            LogStatus("Pair headset is ready. In Vibertemis on Quest, choose Setup VR and select this PC; the code appears here when a request arrives.");
        }
        catch (Exception ex) { LogStatus("Could not enable headset pairing: " + ex.Message); }
        finally { _pairingBusy = false; }
    }
}