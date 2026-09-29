// Companion (vibertemis-host-companion) launcher and owned-child
// state machine.
//
// Contract:
//   - Manager launches the companion with exact argv via
//     ProcessStartInfo.ArgumentList (no shell).
//   - The companion is started silently (CreateNoWindow=true,
//     UseShellExecute=false).
//   - The exact command line is:
//       -listen <ipv4>:28540 -advertise <ipv4>:28540
//       -alvr-session <bundledALVRdirectory>/session.json
//       -state-dir <companion state dir>
//   - No tokens / cert PEMs / passwords are ever placed on the
//     command line.
//   - The manager owns the child process handle until Exit; closing
//     the UI does NOT stop the companion (tray close); only explicit
//     Exit does.
//   - The manager stops ONLY the owned companion PID. The
//     companion may have spawned Steam / SteamVR via its own
//     -start_pcvr handler; those are Steam's children, not ours,
//     and must keep running. Process.Kill(entireProcessTree:true)
//     would propagate across the Steam-owned subtree; we never
//     request it.
//   - When Stop completes, the caller is told whether the
//     process actually exited within the bounded wait or whether
//     a deny / timeout prevented us from confirming stop.
//   - The Exited callback is wired to a captured proc reference;
//     a stale callback (one that fires after a fresh Start)
//     cannot nullify the new _owned process or read its
//     ExitCode.
using System;
using System.Collections.Generic;
using VibertemisManager.Core.Integrity;

namespace VibertemisManager.Core.Companion;

public sealed record CompanionLaunchSpec(
    string CompanionExePath,
    string ListenAddress,
    ushort listenPort,
    string AdvertiseAddress,
    ushort AdvertisePort,
    string AlvrSessionPath,
    string StateDir);

public interface ICompanionProcess : IDisposable
{
    int ProcessId { get; }
    bool HasExited { get; }
    int ExitCode { get; }
    event EventHandler? Exited;
    /// <summary>
    /// Terminates only the owned process handle. Never propagates
    /// to descendants (the companion may have spawned
    /// Steam / SteamVR via its own internal launcher and those
    /// are Steam-owned, not ours).
    /// </summary>
    void Kill();
}

public interface IProcessLauncher
{
    ICompanionProcess Launch(string exePath, IReadOnlyList<string> args);
}

public sealed record CompanionStarted(int ProcessId, IReadOnlyList<string> Argv);

public enum CompanionStopReason
{
    ExplicitExit,
    ManagerExit,
}

public enum CompanionStopOutcome
{
    AlreadyExited,
    Stopped,
    Denied,
    Timeout,
}

public sealed record CompanionStopped(int ProcessId, CompanionStopReason Reason, int ExitCode, CompanionStopOutcome Outcome);

public sealed record CompanionStopResult(CompanionStopOutcome Outcome, int? ExitCode, string? Error);

public interface ICompanionRunner
{
    bool IsRunning { get; }
    int? ProcessId { get; }
    CompanionStarted Start(CompanionLaunchSpec spec, IIntegrityVerifier verifier);
    CompanionStopResult Stop(CompanionStopReason reason, TimeSpan wait);
    event EventHandler<CompanionStopped>? Exited;
}

public sealed class CompanionRunner : ICompanionRunner
{
    private sealed class Owned(ICompanionProcess process)
    {
        public readonly ICompanionProcess Process = process;
        public readonly int Id = process.ProcessId;
        public bool Done;
        public int ExitCode = -1;
        public CompanionStopReason Reason = CompanionStopReason.ManagerExit;
    }
    private readonly IProcessLauncher _launcher;
    private readonly object _lock = new();
    private Owned? _owned;
    public CompanionRunner(IProcessLauncher launcher) => _launcher = launcher;
    public event EventHandler<CompanionStopped>? Exited;
    public bool IsRunning { get { lock (_lock) return _owned is { Done: false } && !_owned.Process.HasExited; } }
    public int? ProcessId { get { lock (_lock) return _owned?.Id; } }

    public CompanionStarted Start(CompanionLaunchSpec spec, IIntegrityVerifier verifier)
    {
        lock (_lock)
        {
            if (_owned is { Done: false })
            {
                if (!_owned.Process.HasExited) throw new InvalidOperationException("Companion already running.");
                Complete(_owned);
            }
            if (!verifier.Verify(spec.CompanionExePath, out _))
                throw new CompanionIntegrityException("Host companion integrity check failed. Reinstall the host package.");
            var argv = new List<string> {
                "-listen", $"{spec.ListenAddress}:{spec.listenPort}",
                "-advertise", $"{spec.AdvertiseAddress}:{spec.AdvertisePort}",
                "-alvr-session", spec.AlvrSessionPath, "-state-dir", spec.StateDir, "-mdns"
            };
            var owned = new Owned(_launcher.Launch(spec.CompanionExePath, argv));
            _owned = owned;
            owned.Process.Exited += (_, _) => { lock (_lock) Complete(owned); };
            if (!owned.Done && owned.Process.HasExited) Complete(owned);
            if (owned.Done) throw new InvalidOperationException("Host companion exited during startup. Check your network address and host settings.");
            return new CompanionStarted(owned.Id, argv);
        }
    }

    // All handle accesses and disposal share one lock. Stop retains cached
    // completion state, never a disposed process handle. Duplicate/stale OS
    // events cannot clear or report a replacement process.
    private void Complete(Owned owned)
    {
        if (owned.Done) return;
        owned.Done = true;
        try { owned.ExitCode = owned.Process.ExitCode; } catch { owned.ExitCode = -1; }
        var current = ReferenceEquals(_owned, owned);
        if (current) _owned = null;
        owned.Process.Dispose();
        if (current)
        {
            var report = new CompanionStopped(owned.Id, owned.Reason, owned.ExitCode, CompanionStopOutcome.AlreadyExited);
            System.Threading.ThreadPool.QueueUserWorkItem(_ => Exited?.Invoke(this, report));
        }
    }

    public CompanionStopResult Stop(CompanionStopReason reason, TimeSpan wait)
    {
        Owned? owned;
        lock (_lock)
        {
            owned = _owned;
            if (owned is null) return new(CompanionStopOutcome.AlreadyExited, null, null);
            owned.Reason = reason;
            try
            {
                if (owned.Process.HasExited) { Complete(owned); return new(CompanionStopOutcome.AlreadyExited, owned.ExitCode, null); }
                // Only this owned PID. Never stop Steam/SteamVR descendants.
                owned.Process.Kill();
            }
            catch (Exception ex)
            {
                if (owned.Done) return new(CompanionStopOutcome.Stopped, owned.ExitCode, null);
                try
                {
                    if (owned.Process.HasExited) { Complete(owned); return new(CompanionStopOutcome.AlreadyExited, owned.ExitCode, null); }
                }
                catch { }
                return new(CompanionStopOutcome.Denied, null, ex.Message);
            }
        }
        var timer = System.Diagnostics.Stopwatch.StartNew();
        do
        {
            lock (_lock)
            {
                if (!owned.Done && owned.Process.HasExited) Complete(owned);
                if (owned.Done) return new(CompanionStopOutcome.Stopped, owned.ExitCode, null);
            }
            if (timer.Elapsed >= wait) break;
            System.Threading.Thread.Sleep(25);
        } while (true);
        return new(CompanionStopOutcome.Timeout, null, $"Host companion PID {owned.Id} has not exited. Retry before updating.");
    }
}

public sealed class CompanionIntegrityException : Exception
{
    public CompanionIntegrityException(string message) : base(message) { }
}
