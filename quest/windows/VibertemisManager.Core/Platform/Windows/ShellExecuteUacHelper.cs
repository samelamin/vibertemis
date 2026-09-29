#if WINDOWS
using System;
using System.Collections.Generic;
using System.Diagnostics;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class ShellExecuteUacHelper : IUacHelper
{
    public UacLaunchResult Launch(string helperPath, string helperArgs)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = helperPath,
                Arguments = helperArgs,
                UseShellExecute = true,
                Verb = "runas",
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi);
            if (p is null) return new UacLaunchResult(false, 0, "Process.Start returned null.");
            return new UacLaunchResult(true, p.Id, "");
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            // 1223 == ERROR_CANCELLED: the user clicked No on the
            // UAC dialog. We treat this as "not launched" but
            // distinguish it from a real failure so the UI can
            // show a different message.
            return new UacLaunchResult(false, 0, ex.NativeErrorCode == 1223 ? "UAC cancelled." : ex.Message);
        }
        catch (Exception ex)
        {
            return new UacLaunchResult(false, 0, ex.Message);
        }
    }
}
#endif