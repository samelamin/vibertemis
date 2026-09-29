// Windows autostart registrar (HKCU Run).
//
// Writes/clears HKCU\Software\Microsoft\Windows\CurrentVersion\Run
// without spawning reg.exe. We never shell-parse anything.
#if WINDOWS
using Microsoft.Win32;
using VibertemisManager.Core.AutoStart;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsAutoStartRegistrar : IAutoStartRegistrarEx
{
    private readonly Microsoft.Win32.RegistryKey _hku;

    public WindowsAutoStartRegistrar()
    {
        _hku = Registry.CurrentUser;
    }

    public AutoStartState Inspect()
    {
        using var key = _hku.OpenSubKey(RegistryAutoStartRegistrar.RunKey, writable: true);
        if (key is null) return new AutoStartState(false, null);
        var data = key.GetValue(RegistryAutoStartRegistrar.ValueName) as string;
        if (string.IsNullOrEmpty(data)) return new AutoStartState(false, null);
        return new AutoStartState(true, data);
    }

    public void Enable(string commandLine)
    {
        using var key = _hku.CreateSubKey(RegistryAutoStartRegistrar.RunKey, writable: true);
        if (key is null) throw new System.InvalidOperationException("HKCU Run key not writable.");
        key.SetValue(RegistryAutoStartRegistrar.ValueName, commandLine, RegistryValueKind.String);
    }

    public void Disable()
    {
        using var key = _hku.OpenSubKey(RegistryAutoStartRegistrar.RunKey, writable: true);
        if (key is null) return;
        if (key.GetValue(RegistryAutoStartRegistrar.ValueName) != null)
            key.DeleteValue(RegistryAutoStartRegistrar.ValueName, throwOnMissingValue: false);
    }
}
#endif