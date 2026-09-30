// Windows vc_redist.x64.exe launcher (UAC-elevated, stateful).
//
// The Visual C++ redistributable ALWAYS requires elevation to
// install (per Microsoft docs). The manager itself is per-user /
// unelevated; we therefore invoke the bundled vc_redist.x64.exe
// via ShellExecute + Verb="runas" so Windows prompts the user
// with its standard UAC dialog.
//
// We pass /install /passive /norestart and a real /log path
// pointing to a per-user absolute file so the documented
// Microsoft installer writes its log without ambiguity. We DO
// NOT pass a literal /log "" (empty quoted string); that is
// not a documented invocation and historically results in
// either silent logging to %TEMP% or, in some builds, the
// installer refusing the argument.
//
// We deliberately do NOT kill the installer on timeout:
// vc_redist.x64.exe is a Burn bootstrapper that downloads
// sub-packages, verifies them, and configures services. A
// legitimate install can be slow. Killing it mid-flight can
// leave the registry half-written and break subsequent
// re-detection. The launcher keeps the actual Process alive in
// _inFlight; subsequent Begin calls observe IsInFlight and
// either return the completed exit code or report still-
// running. The wrapper maps these to:
//
//   - Begin returns int? exitCode:
//       exitCode != null -> process completed within wait
//       exitCode == null -> process still running; in-flight
//
//   - TryGetResult on a still-running process returns
//     stillRunning=true; on a completed process returns
//     ExitCode and clears the in-flight handle.
//
// The manager closing (tray close, user exit) never kills the
// installer. The Windows handle is held by the .NET Process
// object inside the launcher; when the wrapper is finalised
// the Process is disposed and the kernel handle is released,
// but the redistributable continues until it exits naturally.
// On the next launch the registry is re-detected; a half-
// written registry is reported as Corrupt (Installed=1 with
// missing Major/Minor) so the user is prompted to retry.
#if WINDOWS
using System;
using System.Diagnostics;
using System.IO;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsVcRedistLauncher : IVcRedistLauncher
{
    private readonly Func<string> _logFilePath;
    private readonly object _gate = new();
    private Process? _inFlight;
    private int _exitCode;

    public WindowsVcRedistLauncher(Func<string>? logFilePath = null)
    {
        _logFilePath = logFilePath ?? DefaultLogPath;
    }

    public TimeSpan Wait { get; set; } = TimeSpan.FromMinutes(10);

    public bool IsInFlight
    {
        get
        {
            lock (_gate)
            {
                if (_inFlight is null) return false;
                // Observe the exit code before allowing a subsequent install.
                return true;
            }
        }
    }

    public int? Begin(VcRedistPackage package)
    {
        lock (_gate)
        {
            // Re-entry path: previous install still running.
            if (_inFlight is not null && !_inFlight.HasExited)
                return null;

            // Re-entry path: previous install completed but TryGetResult
            // was never called. Read the cached exit code.
            if (_inFlight is not null && _inFlight.HasExited)
            {
                var cached = ReadExitCode(_inFlight);
                DisposeInFlightLocked();
                return cached;
            }

            // Fresh launch.
            var logFile = _logFilePath();
            try { Directory.CreateDirectory(Path.GetDirectoryName(logFile)!); } catch { /* ignore */ }
            var psi = new ProcessStartInfo
            {
                FileName = package.AbsolutePath,
                UseShellExecute = true,
                Verb = "runas", // standard UAC elevation
                CreateNoWindow = true,
            };
            psi.ArgumentList.Add("/install");
            psi.ArgumentList.Add("/passive");
            psi.ArgumentList.Add("/norestart");
            psi.ArgumentList.Add("/log");
            psi.ArgumentList.Add(logFile);

            Process? p;
            try { p = Process.Start(psi); }
            catch (System.ComponentModel.Win32Exception) { throw; }
            if (p is null) throw new InvalidOperationException("vc_redist.x64.exe could not be launched.");
            _inFlight = p;
            // Wait WITHOUT disposing the process on timeout. If
            // WaitForExit returns false the process is still
            // running; we keep the Process alive so subsequent
            // Begin / TryGetResult calls can observe completion.
            if (p.WaitForExit((int)Wait.TotalMilliseconds))
            {
                _exitCode = ReadExitCode(p);
                DisposeInFlightLocked();
                return _exitCode;
            }
            return null;
        }
    }

    public bool TryGetResult(out int exitCode, out bool stillRunning)
    {
        lock (_gate)
        {
            if (_inFlight is null)
            {
                exitCode = -1;
                stillRunning = false;
                return false;
            }
            if (!_inFlight.HasExited)
            {
                exitCode = -1;
                stillRunning = true;
                return false;
            }
            exitCode = ReadExitCode(_inFlight);
            _exitCode = exitCode;
            DisposeInFlightLocked();
            stillRunning = false;
            return true;
        }
    }

    private static int ReadExitCode(Process p)
    {
        try { return p.ExitCode; }
        catch { return -1; }
    }

    private void DisposeInFlightLocked()
    {
        var p = _inFlight;
        _inFlight = null;
        if (p is null) return;
        try { p.Dispose(); } catch { /* ignore */ }
    }

    private static string DefaultLogPath()
    {
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(local, "VibertemisVRHostManager", "logs", "vc-redist-" + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + ".log");
    }
}
#endif