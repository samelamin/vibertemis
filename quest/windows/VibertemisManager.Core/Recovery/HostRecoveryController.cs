using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Integrity;
using VibertemisManager.Core.Network;

namespace VibertemisManager.Core.Recovery;

public enum HostRecoveryState { Stopped, WaitingForNetwork, Starting, Running, Retrying, IntegrityBlocked, Suspended, StopFailed }
public sealed record HostRecoveryStatus(HostRecoveryState State, string Message, bool DesiredRunning, int Failures);

// All transitions (including Start/Stop and update handoff) share one lock.
// No background task, sleep or Exited callback can launch a replacement.
// The manager ticks this controller; only it owns companion start/stop.
public sealed class HostRecoveryController
{
    private readonly object _gate = new();
    private readonly ICompanionRunner _runner;
    private readonly IAdapterEnumerator _adapters;
    private readonly IIntegrityVerifier _integrity;
    private readonly Func<NetworkAdapter, CompanionLaunchSpec> _buildSpec;
    private readonly TimeProvider _clock;
    private readonly long _origin;
    private bool _desired, _suspended, _integrityBlocked;
    private int _failures;
    private TimeSpan _retryAt, _startedAt, _pollAt;
    private CompanionLaunchSpec? _bound;
    private HostRecoveryStatus _status = new(HostRecoveryState.Stopped, "Stopped", false, 0);

    public HostRecoveryController(ICompanionRunner runner, IAdapterEnumerator adapters,
        IIntegrityVerifier integrity, Func<NetworkAdapter, CompanionLaunchSpec> buildSpec, TimeProvider? clock = null)
    {
        _runner = runner; _adapters = adapters; _integrity = integrity;
        _buildSpec = buildSpec; _clock = clock ?? TimeProvider.System; _origin = _clock.GetTimestamp();
    }
    private TimeSpan Now => _clock.GetElapsedTime(_origin);
    public HostRecoveryStatus Status { get { lock (_gate) return _status; } }
    public CompanionLaunchSpec? RunningSpec { get { lock (_gate) return _runner.IsRunning ? _bound : null; } }
    public bool DesiredRunning { get { lock (_gate) return _desired; } }
    public void RequestStart()
    {
        lock (_gate) { _desired = true; _integrityBlocked = false; _retryAt = _pollAt = TimeSpan.Zero; }
    }
    public CompanionStopResult RequestStop()
    {
        lock (_gate)
        {
            _desired = false; // Publish intent before the process can exit.
            var result = _runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromSeconds(2));
            if (Stopped(result)) { _bound = null; Set(HostRecoveryState.Stopped, "Stopped by you. Start companion to resume."); }
            else Set(HostRecoveryState.StopFailed, "Could not stop host service. Retry Stop: " + result.Error);
            return result;
        }
    }
    public CompanionStopResult SuspendAndStop()
    {
        lock (_gate)
        {
            _suspended = true; // Must precede Stop for Exit and installer handoff.
            Set(HostRecoveryState.Suspended, "Automatic recovery paused");
            var result = _runner.Stop(CompanionStopReason.ManagerExit, TimeSpan.FromSeconds(5));
            if (Stopped(result)) _bound = null;
            return result;
        }
    }
    public void Resume()
    {
        lock (_gate) { _suspended = false; _retryAt = _pollAt = TimeSpan.Zero; }
    }
    public void NetworkChanged() { lock (_gate) _pollAt = TimeSpan.Zero; }

    public HostRecoveryStatus Tick(string? savedId, string? savedAddress)
    {
        lock (_gate)
        {
            if (_suspended) return _status;
            if (!_desired)
            {
                if (!_runner.IsRunning) { _bound = null; Set(HostRecoveryState.Stopped, "Stopped by you. Start companion to resume."); }
                return _status;
            }
            if (_integrityBlocked) return _status;
            var now = Now;
            var running = _runner.IsRunning;
            if (!running && _bound is not null)
            {
                _bound = null;
                Retry(now, "Host service exited");
            }
            if (now < _retryAt) return _status;
            if (now < _pollAt) return _status;
            _pollAt = now + TimeSpan.FromSeconds(2);
            NetworkAdapter? adapter;
            try { adapter = SelectAdapter(_adapters.Enumerate(), savedId, savedAddress, _bound?.ListenAddress); }
            catch (Exception ex) { Retry(now, "Could not inspect network: " + ex.Message); return _status; }

            // A vanished/renumbered interface requires a rebind, even if the
            // old process is alive. Stop only the owned child, never SteamVR.
            if (running && (_bound is null || adapter is null || _bound.ListenAddress != adapter.Address.ToString()))
            {
                var result = _runner.Stop(CompanionStopReason.ManagerExit, TimeSpan.FromSeconds(1));
                if (!Stopped(result)) { Retry(now, "Waiting to stop old host service: " + result.Error); return _status; }
                running = false; _bound = null;
            }
            if (adapter is null)
            {
                Set(HostRecoveryState.WaitingForNetwork, string.IsNullOrEmpty(savedId) && string.IsNullOrEmpty(savedAddress)
                    ? "Choose your PC network once, then start the host."
                    : "Waiting for your saved network. Recovery is automatic.");
                return _status;
            }
            if (running)
            {
                if (now - _startedAt >= TimeSpan.FromSeconds(60)) _failures = 0;
                Set(HostRecoveryState.Running, "Host service running on " + adapter.Address);
                return _status;
            }
            try
            {
                var spec = _buildSpec(adapter);
                _runner.Start(spec, _integrity);
                _bound = spec; _startedAt = now;
                Set(HostRecoveryState.Starting, "Starting host service on " + adapter.Address);
                // Don't reset failures here: rapid repeated crashes must back off.
            }
            catch (CompanionIntegrityException ex)
            {
                _integrityBlocked = true;
                Set(HostRecoveryState.IntegrityBlocked, ex.Message + " Reinstall, then retry Start.");
            }
            catch (Exception ex) { Retry(now, "Host service could not start: " + ex.Message); }
            return _status;
        }
    }
    private void Retry(TimeSpan now, string reason)
    {
        _failures = Math.Min(_failures + 1, 100);
        var seconds = _failures > 5 ? 60 : Math.Min(30, 1 << _failures);
        _retryAt = now + TimeSpan.FromSeconds(seconds);
        Set(HostRecoveryState.Retrying, reason + $". Retrying in {seconds}s.");
    }
    private void Set(HostRecoveryState state, string message) => _status = new(state, message, _desired, _failures);
    private static bool Stopped(CompanionStopResult result) => result.Outcome is CompanionStopOutcome.Stopped or CompanionStopOutcome.AlreadyExited;

    public static NetworkAdapter? SelectAdapter(IReadOnlyList<NetworkAdapter> adapters, string? savedId, string? savedAddress, string? boundAddress = null)
    {
        var matches = adapters.Where(a => a.IsUp && a.IsOperational && Usable(a.Address) &&
            (!string.IsNullOrEmpty(savedId) ? string.Equals(a.Id, savedId, StringComparison.OrdinalIgnoreCase)
                : !string.IsNullOrEmpty(savedAddress) && a.Address.ToString() == savedAddress)).ToArray();
        return matches.FirstOrDefault(a => a.Address.ToString() == boundAddress)
            ?? matches.FirstOrDefault(a => a.Address.ToString() == savedAddress)
            ?? matches.OrderBy(a => a.Address.ToString(), StringComparer.Ordinal).FirstOrDefault();
    }
    public static bool Usable(System.Net.IPAddress address)
    {
        if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork || System.Net.IPAddress.IsLoopback(address)) return false;
        var b = address.GetAddressBytes();
        return b[0] != 0 && b[0] < 224 && !(b[0] == 169 && b[1] == 254);
    }
}
