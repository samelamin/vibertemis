// Dashboard busy checker tests.
//
// The checker must report:
//   - Idle when only unrelated processes are present.
//   - OtherDashboardBusy when another ALVR Dashboard is running.
//   - SteamvrBusy when vrserver/vrcompositor are running.
//   - AnotherManagerBusy when a second VibertemisManager.App
//     instance is detected.
using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class DashboardBusyCheckerTests
{
    [Fact]
    public void Check_Idle_WhenNoProcesses()
    {
        var table = new FakeProcessTable();
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.False(report.IsBusy);
        Assert.Equal(DashboardBusyReason.None, report.Reason);
    }

    [Fact]
    public void Check_Idle_WhenUnrelatedProcessesOnly()
    {
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(1, "explorer.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.False(report.IsBusy);
    }

    [Fact]
    public void Check_ReportsOtherDashboardBusy()
    {
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(42, "ALVR Dashboard.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.True(report.IsBusy);
        Assert.Equal(DashboardBusyReason.OtherDashboardBusy, report.Reason);
        Assert.Contains("ALVR Dashboard.exe", report.ActiveProcessNames);
    }

    [Fact]
    public void Check_ReportsSteamvrBusy()
    {
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(7, "vrserver.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.True(report.IsBusy);
        Assert.Equal(DashboardBusyReason.SteamvrBusy, report.Reason);
    }

    [Fact]
    public void Check_ReportsAnotherManagerBusy()
    {
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(100, "VibertemisManager.App.exe", ""));
        table.Processes.Add(new RunningProcess(101, "VibertemisManager.App.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.True(report.IsBusy);
        Assert.Equal(DashboardBusyReason.AnotherManagerBusy, report.Reason);
    }

    [Fact]
    public void Check_DetectsSteamvrBusy_CaseInsensitive()
    {
        // Real Windows process tables can return names with mixed
        // casing depending on how the running tool enumerates;
        // verify our comparison is OrdinalIgnoreCase.
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(7, "VRServer.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.True(report.IsBusy);
        Assert.Equal(DashboardBusyReason.SteamvrBusy, report.Reason);
    }

    [Fact]
    public void Check_DetectsOtherDashboardBusy_CaseInsensitive()
    {
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(42, "alvr dashboard.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.True(report.IsBusy);
        Assert.Equal(DashboardBusyReason.OtherDashboardBusy, report.Reason);
    }

    [Fact]
    public void Check_IdleWithSteamvrAndOnlyOtherUnrelatedProcesses()
    {
        // The single-instance manager mutex is the canonical
        // single-instance gate; this checker must NOT block
        // because of an unrelated companion / helper process.
        var table = new FakeProcessTable();
        table.Processes.Add(new RunningProcess(1, "explorer.exe", ""));
        table.Processes.Add(new RunningProcess(2, "vibertemis-host-companion.exe", ""));
        var checker = new ProcessTableDashboardBusyChecker(table);
        var report = checker.Check();
        Assert.False(report.IsBusy);
    }
}