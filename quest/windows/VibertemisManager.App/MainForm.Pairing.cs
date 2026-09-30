using VibertemisManager.Core.Pairing;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private readonly Button _btnPairHeadset = new();
    private bool _pairingBusy;

    private async Task PairHeadset()
    {
        if (_pairingBusy || _preparingVr || _updateBusy || _installHandOffInFlight) return;
        var spec = _recovery.RunningSpec;
        if (spec is null) { LogStatus("Choose Setup VR and start hosting before pairing a headset."); return; }
        _pairingBusy = true;
        try {
            using var client = new LocalPairingClient(spec.StateDir);
            using var cancel = new CancellationTokenSource();
            using var dialog = new Form { Text = "Pair headset", Width = 540, Height = 340,
                StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false,
                FormBorderStyle = FormBorderStyle.FixedDialog };
            var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
                WrapContents = false, Padding = new Padding(18) };
            var instructions = new Label { AutoSize = false, Width = 490, Height = 62,
                Text = "On Quest, open Vibertemis → Setup VR and select this PC. Compare every character of the code on both screens before approving." };
            var code = new Label { AutoSize = false, Width = 490, Height = 45,
                Font = new Font("Consolas", 22, FontStyle.Bold), Text = "Waiting for Quest…" };
            var status = new Label { AutoSize = false, Width = 490, Height = 48, Text = "Opening pairing for two minutes…" };
            var actions = new FlowLayoutPanel { Width = 490, Height = 40 };
            var approve = new Button { Text = "Codes match — approve", AutoSize = true, Enabled = false };
            var deny = new Button { Text = "Reject", AutoSize = true, Enabled = false };
            var close = new Button { Text = "Close", AutoSize = true };
            var forget = new Button { Text = "Forget paired headsets…", AutoSize = true };
            actions.Controls.AddRange(new Control[] { approve, deny, close });
            layout.Controls.AddRange(new Control[] { instructions, code, status, actions, forget });
            dialog.Controls.Add(layout);
            PairingPending? pending = null;
            bool busy = false, approved = false;
            using var timer = new System.Windows.Forms.Timer { Interval = 1000 };
            void Render(PairingPending value) {
                pending = value;
                approve.Enabled = deny.Enabled = value.State == "pending" && !busy;
                code.Text = value.Code ?? "Waiting for Quest…";
                approved |= value.State == "approved";
                status.Text = value.State switch {
                    "pending" => "Approve only if every character matches the code on your Quest.",
                    "approved" => "Approved. Wait for “VR pairing ready” on Quest, then close this window.",
                    "denied" => "Request rejected. Close this window and choose Pair headset to retry.",
                    "closed" => approved ? "Pairing window closed. Your approved headset stays paired." : "Pairing window expired. Close and choose Pair headset to retry.",
                    _ => "Ready for a request. Pairing closes automatically after two minutes."
                };
                forget.Text = $"Forget paired headsets ({value.Devices})…";
                if (!value.Open || value.State == "denied") timer.Stop();
                if (value.State == "approved") {
                    LogStatus("Headset approved. Quest will finish saving its pairing automatically.");
                    dialog.DialogResult = DialogResult.OK;
                    dialog.Close();
                }
            }
            async Task Send(string action, object? body = null) {
                if (busy || cancel.IsCancellationRequested) return;
                busy = true; approve.Enabled = deny.Enabled = forget.Enabled = false;
                try {
                    var value = await client.SendAsync(action, body, cancel.Token);
                    if (!dialog.IsDisposed && !cancel.IsCancellationRequested) Render(value);
                } catch (OperationCanceledException) when (cancel.IsCancellationRequested) { }
                catch (Exception) {
                    if (!dialog.IsDisposed && !cancel.IsCancellationRequested) {
                        timer.Stop(); status.Text = "Could not contact VR hosting. Close, start hosting, then retry Pair headset.";
                        pending = null;
                    }
                } finally {
                    busy = false;
                    if (!dialog.IsDisposed && !cancel.IsCancellationRequested) {
                        approve.Enabled = deny.Enabled = pending?.State == "pending";
                        forget.Enabled = true;
                    }
                }
            }
            dialog.Shown += async (_, _) => { await Send("open"); if (!cancel.IsCancellationRequested && pending?.Open == true) timer.Start(); };
            timer.Tick += async (_, _) => await Send("pending");
            approve.Click += async (_, _) => {
                var request = pending; if (request?.State != "pending") return;
                await Send("decision", new { session_id = request.SessionId, code = request.Code, approve = true });
            };
            deny.Click += async (_, _) => {
                var request = pending; if (request?.State != "pending") return;
                await Send("decision", new { session_id = request.SessionId, code = request.Code, approve = false });
            };
            forget.Click += async (_, _) => {
                if (MessageBox.Show(dialog, "Forget all headsets paired through this approval screen? They will need approval again. Imported legacy pairing files are unaffected.",
                    "Forget headsets", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                    await Send("forget");
            };
            close.Click += (_, _) => dialog.Close();
            dialog.FormClosed += (_, _) => { timer.Stop(); cancel.Cancel(); };
            dialog.ShowDialog(this);
            // Best effort; server expiry also closes the window if the manager exits.
            try { await client.SendAsync("close", null, CancellationToken.None); } catch { }
        } catch (Exception) { LogStatus("Could not open headset pairing. Start hosting and retry; reinstall the VR Manager if its pairing state is damaged."); }
        finally { _pairingBusy = false; }
    }
}
