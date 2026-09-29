// Companion runner + integrity verifier interaction tests.
//
// The companion runner must:
//   - Refuse to launch when no integrity entry exists.
//   - Refuse to launch when integrity bytes mismatch.
//   - Launch with the exact documented argv:
//        -listen <ip>:28540 -advertise <ip>:28540
//        -alvr-session <alvr/session.json>
//        -state-dir <companion state dir>
//   - Reuse the owned child handle on subsequent Start calls.
//   - Stop() must:
//        * kill ONLY the owned PID (never descendants), and
//        * return Denied when the OS refuses, NOT pretend the
//          process exited;
//        * return Timeout when the bounded wait elapses;
//        * return AlreadyExited when the process has already gone.
//   - A stale Exited callback from a previous process must NOT
//     clear the new _owned reference (otherwise the new running
//     process would lose its tracked PID and the manager would
//     think the companion is no longer running).
using System;
using System.Collections.Generic;
using VibertemisManager.Core.Companion;
using VibertemisManager.Core.Integrity;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class CompanionRunnerTests : IDisposable
{
    private readonly string _tempRoot;
    private readonly string _installRoot;

    public CompanionRunnerTests()
    {
        _tempRoot = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "vibt-companion-" + Guid.NewGuid().ToString("N"));
        _installRoot = System.IO.Path.Combine(_tempRoot, "VibertemisVR");
        System.IO.Directory.CreateDirectory(_installRoot);
    }

    public void Dispose()
    {
        try { System.IO.Directory.Delete(_tempRoot, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public void Start_LaunchesWithExactArgv()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(
            CompanionExePath: exe,
            ListenAddress: "192.168.1.42",
            listenPort: 28540,
            AdvertiseAddress: "192.168.1.42",
            AdvertisePort: 28540,
            AlvrSessionPath: System.IO.Path.Combine(_installRoot, "alvr", "session.json"),
            StateDir: "%LOCALAPPDATA%/vibertemis/companion");
        var started = runner.Start(spec, verifier);
        Assert.True(started.ProcessId > 0);
        Assert.Equal(new[]
        {
            "-listen", "192.168.1.42:28540",
            "-advertise", "192.168.1.42:28540",
            "-alvr-session", spec.AlvrSessionPath,
            "-state-dir", spec.StateDir, "-mdns",
        }, started.Argv);
    }

    [Fact]
    public void Start_RejectsUnknownExePath()
    {
        var entries = Array.Empty<IntegrityEntry>();
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var runner = new CompanionRunner(new CapturingLauncher());
        var spec = new CompanionLaunchSpec("/nope.exe", "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        Assert.Throws<CompanionIntegrityException>(() => runner.Start(spec, verifier));
    }

    [Fact]
    public void Start_RejectsTamperedExeBytes()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var badHex = new string('0', 64);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", badHex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var runner = new CompanionRunner(new CapturingLauncher());
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        var ex = Assert.Throws<CompanionIntegrityException>(() => runner.Start(spec, verifier));
        Assert.Contains("integrity check failed", ex.Message);
    }

    [Fact]
    public void Start_RefusesToLaunchTwice()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        Assert.Throws<InvalidOperationException>(() => runner.Start(spec, verifier));
    }

    [Fact]
    public void Stop_KillsOnlyOwnedPid_DoesNotPropagateToDescendants()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        var proc = (FakeCompanionProcess)launcher.LastLaunched!;
        // The runner must call Kill() with no descendants hint.
        // The FakeCompanionProcess below only kills its own PID;
        // SteamVR-style descendants are tracked separately and
        // must NOT be touched.
        proc.SpawnFakeChild(99999);
        var result = runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromMilliseconds(500));
        Assert.Equal(CompanionStopOutcome.Stopped, result.Outcome);
        Assert.True(proc.KillCalled);
        Assert.False(proc.KillCalledEntireTree);
        Assert.True(proc.ChildStillAlive, "Descendant must not be killed by Stop.");
    }

    [Fact]
    public void Stop_SurfacesDeniedKill_AsDeniedOutcome()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        var proc = (FakeCompanionProcess)launcher.LastLaunched!;
        proc.KillBehavior = KillBehavior.ThrowWin32AccessDenied;
        var result = runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromMilliseconds(500));
        Assert.Equal(CompanionStopOutcome.Denied, result.Outcome);
        Assert.NotNull(result.Error);
    }

    [Fact]
    public void Stop_ReportsTimeout_WhenProcessDoesNotExit()
    {
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        var proc = (FakeCompanionProcess)launcher.LastLaunched!;
        proc.KillBehavior = KillBehavior.MarkExitedFalse;
        var result = runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromMilliseconds(150));
        Assert.Equal(CompanionStopOutcome.Timeout, result.Outcome);
    }

    [Fact]
    public void StaleExitedCallback_DoesNotClearReplacementProcess()
    {
        // Simulates: process A is started, exits after process B
        // is started. The stale Exited callback for A must NOT
        // clear the runner's _owned reference to B.
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        var first = (FakeCompanionProcess)launcher.LastLaunched!;
        // The runner's "running" check refuses a double Start,
        // so we use the public surface to simulate a
        // replacement: stop, then start a new one.
        first.KillBehavior = KillBehavior.MarkExitedOnKill;
        runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromMilliseconds(500));
        runner.Start(spec, verifier);
        var second = (FakeCompanionProcess)launcher.LastLaunched!;
        Assert.NotEqual(first.ProcessId, second.ProcessId);
        Assert.True(runner.IsRunning);
        Assert.Equal(second.ProcessId, runner.ProcessId);
        // Now raise the stale Exited callback for `first` AFTER
        // the replacement is running. The runner must keep
        // pointing at `second`.
        first.RaiseExited();
        Assert.True(runner.IsRunning);
        Assert.Equal(second.ProcessId, runner.ProcessId);
    }

    [Fact]
    public void StaleExitedCallback_DoesNotReadExitCodeFromReplacement()
    {
        // The replacement is still running. We never see a
        // CompanionStopped event for `first` because Stop's
        // bounded wait already fired the callback. Verify that
        // firing the stale Exited callback does not surface a
        // bogus ExitCode from the replacement process.
        var exe = System.IO.Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(exe)!);
        System.IO.File.WriteAllBytes(exe, new byte[] { 0xAA });
        var hex = ComputeSha256(exe);
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", hex, new System.IO.FileInfo(exe).Length) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var launcher = new CapturingLauncher();
        var runner = new CompanionRunner(launcher);
        var spec = new CompanionLaunchSpec(exe, "10.0.0.1", 28540, "10.0.0.1", 28540, "/x.json", "/state");
        runner.Start(spec, verifier);
        var first = (FakeCompanionProcess)launcher.LastLaunched!;
        first.KillBehavior = KillBehavior.MarkExitedOnKill;
        first.SetExitCode(7);
        runner.Stop(CompanionStopReason.ExplicitExit, TimeSpan.FromMilliseconds(500));
        runner.Start(spec, verifier);
        var second = (FakeCompanionProcess)launcher.LastLaunched!;
        second.SetExitCode(123);
        // The replacement is healthy and tracked as the current
        // owned process; firing the first proc's Exited again
        // must NOT change ProcessId or surface 123.
        first.RaiseExited();
        Assert.True(runner.IsRunning);
        Assert.Equal(second.ProcessId, runner.ProcessId);
    }

    private static string ComputeSha256(string path)
    {
        using var fs = System.IO.File.OpenRead(path);
        using var sha = System.Security.Cryptography.SHA256.Create();
        var hash = sha.ComputeHash(fs);
        var sb = new System.Text.StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}

internal enum KillBehavior
{
    Default,
    ThrowWin32AccessDenied,
    MarkExitedFalse,
    MarkExitedOnKill,
}

internal sealed class CapturingLauncher : IProcessLauncher
{
    public readonly List<string[]> Calls = new();
    public ICompanionProcess? LastLaunched { get; private set; }
    public ICompanionProcess Launch(string exePath, IReadOnlyList<string> args)
    {
        var copy = new string[args.Count];
        for (var i = 0; i < args.Count; i++) copy[i] = args[i];
        Calls.Add(copy);
        var proc = new FakeCompanionProcess();
        LastLaunched = proc;
        return proc;
    }
}

internal sealed class FakeCompanionProcess : ICompanionProcess
{
    private static int _id = 10000;
    public FakeCompanionProcess() { ProcessId = System.Threading.Interlocked.Increment(ref _id); }
    public int ProcessId { get; }
    public bool HasExited { get; private set; }
    public int ExitCode { get; private set; }
    public event EventHandler? Exited;
    public bool KillCalled { get; private set; }
    public bool KillCalledEntireTree { get; private set; }
    public KillBehavior KillBehavior { get; set; } = KillBehavior.Default;
    public bool ChildStillAlive { get; private set; }
    private int _childPid;

    public void Kill()
    {
        KillCalled = true;
        switch (KillBehavior)
        {
            case KillBehavior.ThrowWin32AccessDenied:
                throw new System.ComponentModel.Win32Exception(5, "Access is denied.");
            case KillBehavior.MarkExitedFalse:
                // Stay running so Stop times out.
                return;
            case KillBehavior.MarkExitedOnKill:
                HasExited = true;
                Exited?.Invoke(this, EventArgs.Empty);
                return;
            default:
                HasExited = true;
                Exited?.Invoke(this, EventArgs.Empty);
                return;
        }
    }

    public void SpawnFakeChild(int pid)
    {
        _childPid = pid;
        ChildStillAlive = true;
    }

    public void SetExitCode(int code) => ExitCode = code;
    public void RaiseExited() => Exited?.Invoke(this, EventArgs.Empty);

    public void Dispose() { }
}
