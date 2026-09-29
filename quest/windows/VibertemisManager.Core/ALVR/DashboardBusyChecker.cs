// ALVR Dashboard busy / SteamVR busy detection.
//
// The bundled ALVR Dashboard.exe will kill other Dashboard
// instances on launch, so the manager must refuse to launch the
// dashboard if any other Dashboard or SteamVR runtime is
// currently busy. SteamVR itself is never killed by the manager:
// the refusal surfaces a clear actionable message so the user
// closes it by hand.
//
// Companion startup is NOT gated by this checker. The companion
// serves control requests (status, capabilities, /start_pcvr)
// and does not interact with SteamVR; running the companion
// while SteamVR is busy is the supported way to drive an
// authenticated connect once the user is ready.
//
// Detection runs through IProcessTable (Windows: real
// Process.GetProcessesByName under Platform/Windows; POSIX tests:
// fake). We never spawn cmd.exe or parse tasklist output.
//
// All name comparisons use StringComparison.OrdinalIgnoreCase
// because Windows process names are case-insensitive and Process
// snapshots from different tools normalise differently.
using System;
using System.Collections.Generic;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.ALVR;

public enum DashboardBusyReason
{
    None,
    OtherDashboardBusy,
    SteamvrBusy,
    AnotherManagerBusy,
    InspectionFailed,
}

public sealed record DashboardBusyReport(DashboardBusyReason Reason, IReadOnlyList<string> ActiveProcessNames)
{
    public bool IsBusy => Reason != DashboardBusyReason.None;
    public static DashboardBusyReport Idle() => new(DashboardBusyReason.None, System.Array.Empty<string>());
}

public interface IDashboardBusyChecker
{
    DashboardBusyReport Check();
}

public sealed class ProcessTableDashboardBusyChecker : IDashboardBusyChecker
{
    // The immutable preview-3 ZIP names the dashboard exe
    //   ALVR Dashboard.exe
    // SteamVR's process names are vrserver.exe + vrcompositor.exe
    // (the binaries SteamVR installs in bin/win64). Another manager
    // instance shows up as VibertemisManager.App running twice,
    // which is blocked upstream by the single-instance mutex, but
    // we surface it defensively here too.
    private static readonly string[] DashboardNames = { "ALVR Dashboard" };
    private static readonly string[] SteamvrNames = { "vrserver", "vrcompositor", "vrmonitor" };
    private static readonly string[] ManagerNames = { "VibertemisManager.App" };

    private static readonly StringComparer NameComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IProcessTable _table;
    public ProcessTableDashboardBusyChecker(IProcessTable table) => _table = table;

    public DashboardBusyReport Check()
    {
        IReadOnlyList<RunningProcess> snap;
        try { snap = _table.Snapshot(); }
        catch { return new(DashboardBusyReason.InspectionFailed, Array.Empty<string>()); }

        // Other manager instance: a second process whose exe name
        // matches the manager's. The single-instance mutex is the
        // primary gate; this is a defensive backstop. We count
        // *before* the busy verdict so a manager instance can
        // still see its own sibling names without itself being
        // reported as "another manager".
        var managerCount = 0;
        foreach (var proc in snap)
        {
            var bare = StripExe(proc.Name);
            if (ContainsCi(ManagerNames, bare)) managerCount++;
        }
        if (managerCount > 1)
            return new DashboardBusyReport(DashboardBusyReason.AnotherManagerBusy, Array.Empty<string>());

        var hits = new List<string>();
        var hasOtherDashboard = false;
        var hasSteamvr = false;
        foreach (var proc in snap)
        {
            var bare = StripExe(proc.Name);
            if (ContainsCi(DashboardNames, bare))
            {
                hits.Add(proc.Name);
                hasOtherDashboard = true;
            }
            else if (ContainsCi(SteamvrNames, bare))
            {
                hits.Add(proc.Name);
                hasSteamvr = true;
            }
        }

        // The dashboard busy check is only meaningful for the
        // "Open ALVR Dashboard" button. SteamVR busy blocks that
        // button first because launching a fresh Dashboard over a
        // live SteamVR is the conflict that ALVR itself raises
        // when it kills other Dashboard instances.
        if (hasSteamvr) return new DashboardBusyReport(DashboardBusyReason.SteamvrBusy, hits);
        if (hasOtherDashboard) return new DashboardBusyReport(DashboardBusyReason.OtherDashboardBusy, hits);
        return DashboardBusyReport.Idle();
    }

    private static string StripExe(string name)
    {
        if (name is null) return string.Empty;
        return name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            ? name.Substring(0, name.Length - 4)
            : name;
    }

    private static bool ContainsCi(string[] candidates, string value)
    {
        foreach (var c in candidates)
            if (NameComparer.Equals(c, value)) return true;
        return false;
    }
}
