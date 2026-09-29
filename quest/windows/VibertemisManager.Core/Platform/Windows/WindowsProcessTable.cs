// Windows process table. Enumerates running processes via System
// Diagnostics.Process and exposes the same IProcessTable contract
// the rest of Core consumes. No WMI, no tasklist parsing, no
// shell-out. POSIX build excludes this file via TargetFramework.
//
// The Snapshot enumerator must tolerate processes that exit
// between GetProcesses() returning the array and our read of
// ProcessName / MainModule (a documented race). Expected exits
// are silently dropped; genuine errors (access denied to the
// process object, etc.) cause the entry to be omitted rather
// than misreported as "exited".
//
// TryGet differentiates "definitely missing" (no such PID) from
// "access denied" (process exists but we cannot enumerate it):
// the installer / updater checks must treat the latter as
// fail-closed because the absence of a verdict is not the same
// as the verdict "exited".
#if WINDOWS
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsProcessTable : IProcessTable
{
    private const int ErrorAccessDenied = 5;

    public IReadOnlyList<RunningProcess> Snapshot()
    {
        var list = new List<RunningProcess>();
        Process[] procs;
        try
        {
            procs = Process.GetProcesses();
        }
        catch (Win32Exception ex)
        {
            throw new InvalidOperationException("Unable to inspect running applications", ex);
        }
        foreach (var p in procs)
        {
            try
            {
                var name = p.ProcessName + ".exe";
                var path = SafeGetMainModulePath(p);
                list.Add(new RunningProcess(p.Id, name, path));
            }
            catch (InvalidOperationException)
            {
                // Expected race: process exited between GetProcesses
                // and our read. Silently drop it.
            }
            catch (Win32Exception)
            {
                throw new InvalidOperationException("Unable to identify a running application");
            }
            finally
            {
                try { p.Dispose(); } catch { /* ignore */ }
            }
        }
        return list;
    }

    public ProcessQueryStatus TryGet(int processId, out RunningProcess process)
    {
        process = default;
        Process? p = null;
        try
        {
            p = Process.GetProcessById(processId);
            var name = p.ProcessName + ".exe";
            var path = SafeGetMainModulePath(p);
            process = new RunningProcess(p.Id, name, path);
            return ProcessQueryStatus.Found;
        }
        catch (ArgumentException)
        {
            // ArgumentException indicates the PID no longer maps to
            // any running process; this is the canonical "missing".
            return ProcessQueryStatus.Missing;
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == ErrorAccessDenied)
        {
            // The process is alive but we cannot open it (typically
            // because it is a system / protected process). The
            // caller must NOT treat this as "exited" - install /
            // update checks fail-closed on this status.
            return ProcessQueryStatus.AccessDenied;
        }
        catch (Win32Exception)
        {
            return ProcessQueryStatus.Unknown;
        }
        catch (InvalidOperationException)
        {
            return ProcessQueryStatus.Missing;
        }
        finally
        {
            try { p?.Dispose(); } catch { /* ignore */ }
        }
    }

    public bool IsRunning(string executableName)
    {
        var bare = System.IO.Path.GetFileNameWithoutExtension(executableName);
        Process[] matches;
        try
        {
            matches = Process.GetProcessesByName(bare);
        }
        catch (Win32Exception ex)
        {
            throw new InvalidOperationException("Unable to inspect running applications", ex);
        }
        try
        {
            foreach (var p in matches)
            {
                try
                {
                    if (!p.HasExited) return true;
                }
                catch (InvalidOperationException)
                {
                    // Process exited between GetProcessesByName
                    // returning the array and our HasExited probe.
                }
            }
        }
        finally
        {
            foreach (var p in matches)
            {
                try { p.Dispose(); } catch { /* ignore */ }
            }
        }
        return false;
    }

    private static string SafeGetMainModulePath(Process p)
    {
        try { return p.MainModule?.FileName ?? ""; }
        catch (InvalidOperationException) { return ""; }
        catch (Win32Exception) { return ""; }
    }
}
#endif
