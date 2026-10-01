// Persistent update status line renderer. The MainForm mirrors
// the Android hub badge ladder on a single always-visible Label;
// the renderer is the shared source of truth so the WinForms
// label and the tests agree on the wording for every state.
//
// Ladder:
//   1. an in-flight / ended one-click attempt (its stage owns the
//      line, so an error survives a collapsed activity log)
//   2. checking (a metadata check is in flight, whether the owner
//      asked for it with Update or the background tick did)
//   3. ready (verified installer is on disk and ready to install)
//   4. available (newer metadata is known, no verified installer)
//   5. error (last check failed; the one Update action retries it)
//   6. current (a successful check found no newer release)
//   7. not yet checked (no successful check has run yet)
//
// No "up to date" line appears before a successful check has
// happened — the default text is always visible from launch.
// There is exactly ONE update action, and its label never describes
// a first step of two: at rest it is "Update" (with or without the
// version it will fetch or install), while a click is in flight it
// is "Checking…", and a failed or busy attempt offers "Retry
// update". A label that said "Check for updates" would be a promise
// that something still has to be pressed afterwards.
// The ladder matches the Android UpdatesActivity visibility
// ladder and the hub badge ladder so the user sees the same
// outcome language on every surface.
using System;

namespace VibertemisManager.Core.Update;

public static class UpdateStatusLineRenderer
{
    /// <summary>
    /// The persistent status line. An in-flight or ended
    /// <paramref name="flow"/> attempt owns the line while it is not
    /// <see cref="UpdateFlowStage.Idle"/> so an error or a
    /// blocked-by-busy reason stays on screen even with the activity
    /// log collapsed. The metadata ladder is used otherwise.
    /// </summary>
    public static string Render(UpdateRepository.Snapshot s, UpdateFlowState? flow = null)
    {
        if (flow is not null && flow.Stage != UpdateFlowStage.Idle)
        {
            var detail = string.IsNullOrEmpty(flow.Message) ? StagePhrase(flow.Stage, flow.Version) : flow.Message!;
            return "Update status: " + detail;
        }
        if (s.Checking)
        {
            return "Update status: checking for updates\u2026";
        }
        if (s.HasDownloaded)
        {
            return $"Update status: {s.Downloaded!.Version} verified and ready to install.";
        }
        if (s.HasAvailable)
        {
            return $"Update status: {s.Available!.Version} available ({UpdateRepository.FormatBytes(s.Available.Windows.Bytes)}).";
        }
        if (!string.IsNullOrEmpty(s.LastError))
        {
            return $"Update status: check unavailable \u2014 {s.LastError}. Use Update to retry.";
        }
        if (s.LastSuccessAt != DateTime.MinValue)
        {
            return "Update status: up to date.";
        }
        return "Update status: not yet checked.";
    }

    /// <summary>
    /// Label for the single update action. There is exactly one
    /// primary update control, so this label has to tell the owner
    /// what the one click does without promising a second one. At
    /// rest it is always an Update: "Update" when the machine knows
    /// of nothing newer (the click checks and then installs), and
    /// "Update to &lt;version&gt;" for a release it can already name
    /// - one still to download or one already cached and verified.
    /// "Install update" is never used: the click that installs those
    /// cached bytes is the same Update click, not a separate step.
    /// </summary>
    public static string ActionLabel(UpdateRepository.Snapshot s, UpdateFlowState? flow = null)
    {
        if (flow is not null && flow.Stage != UpdateFlowStage.Idle)
        {
            return flow.Stage switch
            {
                // The click found nothing to install and is checking
                // for itself. Naming that step is honest - it is what
                // is happening - and it is still the same action, so
                // there is nothing else for the owner to press after
                // it.
                UpdateFlowStage.Checking => "Checking\u2026",
                UpdateFlowStage.Choosing or UpdateFlowStage.Downloading
                    or UpdateFlowStage.Verifying or UpdateFlowStage.HandingOff
                    => flow.Version is null ? "Updating\u2026" : $"Updating to {flow.Version}\u2026",
                UpdateFlowStage.Cancelling => "Cancelling\u2026",
                UpdateFlowStage.AwaitingSystem => flow.Version is null
                    ? "Waiting for Windows\u2026"
                    : $"Installing {flow.Version}\u2026",
                UpdateFlowStage.Blocked or UpdateFlowStage.Failed
                    => flow.Version is null ? "Retry update" : $"Retry update to {flow.Version}",
                _ => "Update",
            };
        }
        // A background metadata check in flight owns the label, so the
        // single control never looks idle while it is working.
        if (s.Checking) return "Checking\u2026";
        // An explicit Update on a verified cache installs exactly
        // those bytes; it must not silently fetch a newer release
        // instead, and it is still one Update, not an install step.
        if (s.HasDownloaded) return $"Update to {s.Downloaded!.Version}";
        if (s.HasNewerAvailable) return $"Update to {s.Available!.Version}";
        return "Update";
    }

    private static string StagePhrase(UpdateFlowStage stage, string? version)
    {
        var subject = version is null ? "the update" : $"update {version}";
        return stage switch
        {
            UpdateFlowStage.Choosing => $"starting {subject}\u2026",
            UpdateFlowStage.Checking => "checking for updates\u2026",
            UpdateFlowStage.Downloading => $"downloading {subject}\u2026",
            UpdateFlowStage.Verifying => $"verifying {subject}\u2026",
            UpdateFlowStage.Cancelling => $"cancelling {subject}\u2026",
            UpdateFlowStage.HandingOff => $"opening the installer for {subject}\u2026",
            UpdateFlowStage.AwaitingSystem => $"Windows is installing {subject}. The manager will close and pairing is kept.",
            UpdateFlowStage.Blocked => $"{subject} is ready to install once the busy applications are closed.",
            UpdateFlowStage.Failed => $"{subject} did not finish. Your current installation is unchanged.",
            _ => "not yet checked.",
        };
    }
}
