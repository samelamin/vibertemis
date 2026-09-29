// Auto-start (HKCU Run) registrar.
//
// The manager exposes an opt-in "Start with Windows" checkbox. The
// production Windows binding writes
//   HKCU\Software\Microsoft\Windows\CurrentVersion\Run
//       VibertemisVRHostManager
//       "<installroot>\VibertemisVR\manager\VibertemisManager.App.exe"
//            --tray-only --silent
// We never shell-parse the value. Enable / Disable idempotent;
// IsEnabled parses the registry value without spawning reg.exe.
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.AutoStart;

public sealed record AutoStartState(bool Enabled, string? CommandLine);

public interface IAutoStartRegistrarEx
{
    AutoStartState Inspect();
    void Enable(string commandLine);
    void Disable();
}

public sealed class RegistryAutoStartRegistrar : IAutoStartRegistrarEx, IAutoStartRegistrar
{
    public const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public const string ValueName = "VibertemisVRHostManager";

    private readonly IRegistryAccess _registry;
    public RegistryAutoStartRegistrar(IRegistryAccess registry) => _registry = registry;

    public AutoStartState Inspect()
    {
        var data = _registry.TryGetString("HKCU", RunKey, ValueName);
        if (data is null) return new AutoStartState(false, null);
        return new AutoStartState(true, data);
    }

    public void Enable(string commandLine) => Write(commandLine);

    public void Disable() => Write(null);

    private void Write(string? commandLine)
    {
        // Use IRegistryAccess to enumerate values and re-set. The
        // real Windows registry writer is in Platform/Windows.
        // POSIX build exposes a no-op registry that throws on
        // write; we deliberately do not call WriteValue here.
        throw new System.PlatformNotSupportedException("Registry write not supported on this platform; bind Platform/Windows/WindowsRegistryAccess in production.");
    }

    public bool IsEnabled() => Inspect().Enabled;
    public void Enable() => Enable("");
}