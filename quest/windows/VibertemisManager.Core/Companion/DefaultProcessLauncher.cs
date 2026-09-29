// Process launcher that builds exact argv via ProcessStartInfo
// ArgumentList. ArgumentList is the only safe way to pass argv on
// .NET: any string-built CommandLine is subject to Win32 quoting
// bugs. UseShellExecute=false + CreateNoWindow=true is required so
// the companion starts silently (the manager UI must show its own
// status; the companion has no visible window of its own).
using System;
using System.Collections.Generic;
using System.Diagnostics;

namespace VibertemisManager.Core.Companion;

public sealed class DefaultProcessLauncher : IProcessLauncher
{
    public ICompanionProcess Launch(string exePath, IReadOnlyList<string> args)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = false,
            RedirectStandardError = false,
            WorkingDirectory = System.IO.Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory,
        };
        foreach (var a in args)
            psi.ArgumentList.Add(a);
        try
        {
            var p = Process.Start(psi) ?? throw new InvalidOperationException("Process.Start returned null");
            return new ProcessCompanionAdapter(p);
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            throw new CompanionLaunchException($"Failed to launch {exePath}: {ex.Message}", ex);
        }
    }
}

internal sealed class ProcessCompanionAdapter : ICompanionProcess
{
    private readonly Process _p;
    public ProcessCompanionAdapter(Process p) => _p = p;
    public int ProcessId => _p.Id;
    public bool HasExited => _p.HasExited;
    public int ExitCode => _p.ExitCode;
    public event EventHandler? Exited
    {
        add { _p.Exited += value; _p.EnableRaisingEvents = true; }
        remove { _p.Exited -= value; }
    }
    public void Kill()
    {
        // Kill only the owned PID; never propagate to descendants.
        // The companion may have spawned Steam / SteamVR via its
        // own internal launcher; those children are Steam-owned and
        // must keep running.
        _p.Kill();
    }
    public void Dispose() => _p.Dispose();
}

public sealed class CompanionLaunchException : Exception
{
    public CompanionLaunchException(string message, Exception inner) : base(message, inner) { }
}