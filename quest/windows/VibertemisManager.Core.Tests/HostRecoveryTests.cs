using System.Net;
using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.Network;
using VibertemisManager.Core.Recovery;
using VibertemisManager.Core.Settings;
using Xunit;
namespace VibertemisManager.Core.Tests;

public class HostRecoveryTests
{
    private sealed class Clock : TimeProvider
    {
        private long _ticks;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => _ticks;
        public void Advance(int seconds) => _ticks += TimeSpan.FromSeconds(seconds).Ticks;
    }
    private sealed class Adapters : IAdapterEnumerator
    {
        public List<NetworkAdapter> Items = new();
        public IReadOnlyList<NetworkAdapter> Enumerate() => Items.ToArray();
    }
    private sealed class Integrity : IIntegrityVerifier
    {
        public bool Verify(string path, out IntegrityEntry? entry) { entry = null; return true; }
        public bool TryGetHash(string path, out IntegrityEntry? entry) { entry = null; return true; }
    }
    private sealed class Runner : ICompanionRunner
    {
        public bool IsRunning { get; set; }
        public int? ProcessId { get; private set; }
        public event EventHandler<CompanionStopped>? Exited;
        public int Starts, Stops;
        public Exception? Failure;
        public CompanionStopOutcome StopOutcome = CompanionStopOutcome.Stopped;
        public Action? DuringStart;
        public CompanionLaunchSpec? LastSpec;
        public CompanionStarted Start(CompanionLaunchSpec spec, IIntegrityVerifier verifier)
        {
            Starts++;
            if (IsRunning) throw new InvalidOperationException("Duplicate start");
            DuringStart?.Invoke();
            if (Failure is not null) throw Failure;
            LastSpec = spec; IsRunning = true; ProcessId = Starts;
            return new(Starts, Array.Empty<string>());
        }
        public CompanionStopResult Stop(CompanionStopReason reason, TimeSpan wait)
        {
            Stops++;
            if (StopOutcome is CompanionStopOutcome.Stopped or CompanionStopOutcome.AlreadyExited) IsRunning = false;
            return new(StopOutcome, 0, StopOutcome == CompanionStopOutcome.Stopped ? null : "fixture refusal");
        }
        public void OldExit() => Exited?.Invoke(this, new(1, CompanionStopReason.ManagerExit, 1, CompanionStopOutcome.AlreadyExited));
    }
    private static NetworkAdapter Nic(string id = "home", string address = "192.168.1.5") => new(id, id, id, IPAddress.Parse(address), IPAddress.Parse("255.255.255.0"), true, true);
    private sealed class Fixture
    {
        public readonly Runner Runner = new();
        public readonly Adapters Net = new();
        public readonly Clock Clock = new();
        public readonly HostRecoveryController Host;
        public Fixture()
        {
            Net.Items.Add(Nic());
            Host = new(Runner, Net, new Integrity(), a => new("companion.exe", a.Address.ToString(), 28540, a.Address.ToString(), 28540, "session.json", "unchanged-state"), Clock);
        }
        public HostRecoveryStatus Tick() => Host.Tick("home", "192.168.1.5");
        public void Start() { Host.RequestStart(); Tick(); }
        public void Advance(int seconds) { Clock.Advance(seconds); Tick(); }
    }
    [Fact] public void LateNetworkStartsWithoutAnotherUserAction()
    {
        var f = new Fixture(); f.Net.Items.Clear(); f.Start();
        Assert.Equal(HostRecoveryState.WaitingForNetwork, f.Host.Status.State); Assert.Equal(0, f.Runner.Starts);
        f.Net.Items.Add(Nic()); f.Advance(2);
        Assert.True(f.Runner.IsRunning); Assert.Equal(1, f.Runner.Starts);
    }
    [Fact] public void SavedAdapterNeverFallsBackToUnrelatedNetwork()
    {
        var f = new Fixture(); f.Net.Items = new() { Nic("vpn"), Nic("other", "192.168.1.6") }; f.Start(); f.Advance(120);
        Assert.Equal(0, f.Runner.Starts); Assert.Equal(HostRecoveryState.WaitingForNetwork, f.Host.Status.State);
    }
    [Theory] [InlineData("169.254.4.2")] [InlineData("127.0.0.1")] [InlineData("0.0.0.0")] [InlineData("224.0.0.2")] [InlineData("::1")]
    public void UnusableAddressesWait(string address)
    {
        var f = new Fixture(); f.Net.Items = new() { Nic(address: address) }; f.Start(); Assert.Equal(0, f.Runner.Starts);
    }
    [Fact] public void UsableAddressOnSameMultiAddressNicIsSelected()
    {
        var list = new[] { Nic(address: "169.254.3.4"), Nic(address: "192.168.1.9") };
        Assert.Equal("192.168.1.9", HostRecoveryController.SelectAdapter(list, "home", "192.168.1.5")!.Address.ToString());
        Assert.Equal("192.168.1.9", HostRecoveryController.SelectAdapter(list, null, "192.168.1.9")!.Address.ToString());
    }
    [Fact] public void RapidCrashBackoffGrowsAndResetsOnlyAfterStableRun()
    {
        var f = new Fixture(); f.Start();
        foreach (var delay in new[] { 2, 4, 8, 16, 30, 60 })
        {
            f.Runner.IsRunning = false; f.Tick(); var count = f.Runner.Starts;
            f.Advance(delay - 1); Assert.Equal(count, f.Runner.Starts);
            f.Advance(1); Assert.Equal(count + 1, f.Runner.Starts);
        }
        f.Advance(60); Assert.Equal(0, f.Host.Status.Failures);
        f.Runner.IsRunning = false; f.Tick(); var before = f.Runner.Starts;
        f.Advance(1); Assert.Equal(before, f.Runner.Starts);
        f.Advance(1); Assert.Equal(before + 1, f.Runner.Starts);
    }
    [Fact] public void StartFailureDoesNotPretendToBeRunning()
    {
        var f = new Fixture(); f.Runner.Failure = new InvalidOperationException("exited during startup"); f.Start();
        Assert.Equal(HostRecoveryState.Retrying, f.Host.Status.State); Assert.Null(f.Host.RunningSpec);
        f.Advance(1); Assert.Equal(1, f.Runner.Starts);
        f.Runner.Failure = null; f.Advance(1); Assert.True(f.Runner.IsRunning);
    }
    [Fact] public void ExplicitStopCancelsBackoffAndStaleExitCannotResurrect()
    {
        var f = new Fixture(); f.Start(); f.Runner.IsRunning = false; f.Tick(); f.Host.RequestStop();
        f.Runner.OldExit(); f.Advance(120); Assert.Equal(1, f.Runner.Starts); Assert.False(f.Host.DesiredRunning);
    }
    [Fact] public void UpdateSuspendsBeforeStopAndResumePreservesIntent()
    {
        var f = new Fixture(); f.Start(); f.Host.SuspendAndStop(); f.Advance(120);
        Assert.Equal(1, f.Runner.Starts); Assert.False(f.Runner.IsRunning);
        f.Host.Resume(); f.Tick(); Assert.Equal(2, f.Runner.Starts);
        f.Host.SuspendAndStop(); f.Host.RequestStop(); f.Host.Resume(); f.Advance(120);
        Assert.Equal(2, f.Runner.Starts); Assert.False(f.Host.DesiredRunning);
    }
    [Fact] public void DhcpRebindKeepsPairingDirectoryAndIgnoresDelayedOldExit()
    {
        var f = new Fixture(); f.Start(); f.Net.Items = new() { Nic(address: "192.168.1.9") }; f.Advance(2);
        Assert.Equal(2, f.Runner.Starts); Assert.Equal(1, f.Runner.Stops);
        Assert.Equal("unchanged-state", f.Runner.LastSpec!.StateDir);
        f.Runner.OldExit(); f.Advance(2); Assert.Equal(2, f.Runner.Starts);
        Assert.Equal("192.168.1.9", f.Host.RunningSpec!.ListenAddress);
    }
    [Fact] public void NetworkDisappearsAndReturnsWithoutLosingSavedIdentity()
    {
        var f = new Fixture(); f.Start(); f.Net.Items.Clear(); f.Advance(2);
        Assert.False(f.Runner.IsRunning); Assert.Equal(HostRecoveryState.WaitingForNetwork, f.Host.Status.State);
        f.Net.Items.Add(Nic()); f.Advance(2); Assert.Equal(2, f.Runner.Starts);
    }
    [Theory] [InlineData(CompanionStopOutcome.Denied)] [InlineData(CompanionStopOutcome.Timeout)]
    public void FailedRebindStopNeverStartsDuplicate(CompanionStopOutcome outcome)
    {
        var f = new Fixture(); f.Start(); f.Runner.StopOutcome = outcome;
        f.Net.Items = new() { Nic(address: "192.168.1.9") };
        f.Advance(2); f.Advance(60); Assert.Equal(1, f.Runner.Starts);
        Assert.Equal("192.168.1.5", f.Host.RunningSpec!.ListenAddress);
        f.Runner.StopOutcome = CompanionStopOutcome.Stopped; f.Advance(60); Assert.Equal(2, f.Runner.Starts);
    }
    [Fact] public void IntegrityFailureRequiresExplicitRetry()
    {
        var f = new Fixture(); f.Runner.Failure = new CompanionIntegrityException("bad hash"); f.Start(); f.Advance(600);
        Assert.Equal(1, f.Runner.Starts); Assert.Equal(HostRecoveryState.IntegrityBlocked, f.Host.Status.State);
        f.Runner.Failure = null; f.Host.RequestStart(); f.Tick(); Assert.Equal(2, f.Runner.Starts);
    }
    [Fact] public async Task ConcurrentStopCannotMissInFlightStart()
    {
        var f = new Fixture(); using var entered = new ManualResetEventSlim(); using var release = new ManualResetEventSlim();
        f.Runner.DuringStart = () => { entered.Set(); Assert.True(release.Wait(TimeSpan.FromSeconds(5))); };
        var start = Task.Run(f.Start);
        Assert.True(entered.Wait(TimeSpan.FromSeconds(5)));
        var stop = Task.Run(() => f.Host.RequestStop()); release.Set();
        await Task.WhenAll(start, stop); f.Advance(120);
        Assert.False(f.Runner.IsRunning); Assert.Equal(1, f.Runner.Starts);
    }
    [Fact] public void StartupPreferencePreservesLegacyOptOutAndCombinesBothSettings()
    {
        var old = new UserSettings { RestoreCompanionOnStartup = true };
        Assert.False(StartupPreference.IsEnabled(old)); Assert.True(old.RestoreCompanionOnStartup);
        StartupPreference.Apply(old, true); Assert.True(old.AutoStartWithWindows); Assert.True(old.RestoreCompanionOnStartup);
        StartupPreference.Apply(old, false); Assert.False(old.AutoStartWithWindows); Assert.False(old.RestoreCompanionOnStartup);
        Assert.True(old.StartupPreferencePersisted);
    }
}
