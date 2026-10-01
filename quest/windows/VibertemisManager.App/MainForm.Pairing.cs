using VibertemisManager.Core.Pairing;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private readonly Button _btnPairHeadset = new();
    private bool _pairingBusy;

    // Pair headset path: enable persisted receiving immediately and
    // reveal the non-modal approval panel. The legacy 2-minute
    // one-shot window is gone; the seamless receiving mode is the only
    // path. Failures are shown on the persistent readiness line, not
    // only in the activity log, which is collapsed by default.
    private async Task PairHeadset()
    {
        if (_pairingBusy || _preparingVr || _updateBusy || _installHandOffInFlight) return;
        var spec = _recovery.RunningSpec;
        if (spec is null)
        {
            FailAction("Pair headset needs the host service. Choose Start first.");
            return;
        }
        // A retry starts from a clean slate.
        SetActionError(null);
        _pairingBusy = true;
        try
        {
            try
            {
                using var probe = new LocalPairingClient(spec.StateDir);
                await probe.SendAsync("pending", null, CancellationToken.None);
            }
            catch (CompanionNotReadyException) { FailAction("Pair headset: the host service is still starting. Try again in a second."); return; }
            catch (PairingAdminException pae) { FailAction("Pair headset: " + pae.OwnerText); return; }
            catch (Exception ex)
            {
                FailAction("Pair headset could not reach the host service (" + ex.Message + "). Choose Stop, then Start, then retry.");
                return;
            }
            EnableReceivingFromSuccess();
            RefreshHostReady();
            RevealApprovalPanel();
            LogStatus("Pair headset is ready. On your Quest, choose Set up PC and select this PC; the code appears here when a request arrives.");
        }
        catch (Exception ex) { FailAction("Could not enable headset pairing: " + ex.Message); }
        finally { _pairingBusy = false; }
    }
}
