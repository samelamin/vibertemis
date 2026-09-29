// Steam install locator.
//
// We never shell-parse the registry. The Windows binding
// (Platform/Windows/SteamLocator.cs) uses Microsoft.Win32.Registry
// directly via the IRegistryAccess abstraction; POSIX build returns
// "not installed" and lets the UI show the official download link.
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Steam;

public sealed record SteamInstallStatus(bool Installed, string? SteamPath, string? Reason)
{
    public static SteamInstallStatus NotInstalled(string reason) => new(false, null, reason);
    public static SteamInstallStatus Found(string path) => new(true, path, null);
}

public interface ISteamLocator
{
    SteamInstallStatus Locate();
}

public sealed class RegistrySteamLocator : ISteamLocator
{
    private readonly IRegistryAccess _registry;
    public RegistrySteamLocator(IRegistryAccess registry) => _registry = registry;

    public SteamInstallStatus Locate()
    {
        // Steam's documented Windows location is
        //   HKLM\SOFTWARE\WOW6432Node\Valve\Steam  : SteamPath
        //   HKLM\SOFTWARE\Valve\Steam             : SteamPath (32-bit)
        //   HKCU\SOFTWARE\Valve\Steam             : SteamPath (per-user)
        // The locator returns the first valid, existing Steam.exe
        // under the SteamPath. If the value is missing or the file
        // does not exist we report NotInstalled.
        foreach (var hive in new[] { "HKLM", "HKCU" })
        {
            foreach (var sub in new[]
                     {
                         @"SOFTWARE\WOW6432Node\Valve\Steam",
                         @"SOFTWARE\Valve\Steam",
                     })
            {
                var path = _registry.TryGetString(hive, sub, "SteamPath");
                if (string.IsNullOrWhiteSpace(path)) continue;
                if (!path!.EndsWith("Steam.exe", System.StringComparison.OrdinalIgnoreCase))
                    path = System.IO.Path.Combine(path!, "Steam.exe");
                if (System.IO.File.Exists(path))
                    return SteamInstallStatus.Found(path);
            }
        }
        return SteamInstallStatus.NotInstalled("Steam not installed (no valid registry SteamPath).");
    }
}