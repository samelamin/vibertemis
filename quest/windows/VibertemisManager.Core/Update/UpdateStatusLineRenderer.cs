// Persistent update status line renderer. The MainForm mirrors
// the Android hub badge ladder on a single always-visible Label;
// the renderer is the shared source of truth so the WinForms
// label and the tests agree on the wording for every state.
//
// Ladder:
//   1. an in-flight / ended one-click attempt (its stage owns the
//      line, so an error survives a collapsed activity log)
//   2. checking (a metadata check is in flight)
//   3. ready (verified installer is on disk and ready to install)
//   4. available (newer metadata is known, no verified installer)
//   5. error (last check failed; user can retry with Check now)
//   6. current (a successful check found no newer release)
//   7. not yet checked (no successful check has run yet)
//
// No "up to date" line appears before a successful check has
// happened — the default text is always visible from launch.
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
            return $"Update status: check unavailable \u2014 {s.LastError}. Use Check for updates to retry.";
        }
        if (s.LastSuccessAt != DateTime.MinValue)
        {
            return "Update status: up to date.";
        }
        return "Update status: not yet checked.";
    }

    /// <summary>
    /// Label for the single update action. There is exactly one
    /// primary update control, so this label is what tells the owner
    /// whether the click will check, download, or install an
    /// already-verified download. It never promises two steps.
    /// </summary>
    public static string ActionLabel(UpdateRepository.Snapshot s, UpdateFlowState? flow = null)
    {
        if (flow is not null && flow.Stage != UpdateFlowStage.Idle)
        {
            return flow.Stage switch
            {
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
        if (s.Checking) return "Checking\u2026";
        // An explicit Update on a verified cache installs those
        // bytes; it must not silently fetch a newer release instead.
        if (s.HasDownloaded) return $"Install update {s.Downloaded!.Version}";
        if (s.HasNewerAvailable) return $"Update to {s.Available!.Version}";
        return "Check for updates";
    }

    /// <summary>
    /// True when the single primary action is already a metadata check.
    /// The UI uses this to hide its secondary manual Check button: at
    /// rest with nothing on offer, both controls would carry the same
    /// "Check for updates" label and do the same thing. It is derived
    /// from the same inputs as <see cref="ActionLabel"/> rather than by
    /// comparing label text, so the two cannot drift.
    /// </summary>
    public static bool PrimaryIsCheck(UpdateRepository.Snapshot s, UpdateFlowState? flow = null)
    {
        // A live or ended attempt owns the primary action, so it is
        // never a plain check.
        if (flow is not null && flow.Stage != UpdateFlowStage.Idle) return false;
        if (s.Checking) return false;
        return !s.HasDownloaded && !s.HasNewerAvailable;
    }

    private static string StagePhrase(UpdateFlowStage stage, string? version)
    {
        var subject = version is null ? "the update" : $"update {version}";
        return stage switch
        {
            UpdateFlowStage.Choosing => $"starting {subject}\u2026",
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