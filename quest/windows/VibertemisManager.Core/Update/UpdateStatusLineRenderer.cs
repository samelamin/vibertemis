// Persistent update status line renderer. The MainForm mirrors
// the Android hub badge ladder on a single always-visible Label;
// the renderer is the shared source of truth so the WinForms
// label and the tests agree on the wording for every state.
//
// Ladder:
//   1. checking (a metadata check is in flight)
//   2. ready (verified installer is on disk and ready to install)
//   3. available (newer metadata is known, no verified installer)
//   4. error (last check failed; user can retry with Check now)
//   5. current (a successful check found no newer release)
//   6. not yet checked (no successful check has run yet)
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
    public static string Render(UpdateRepository.Snapshot s)
    {
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
}