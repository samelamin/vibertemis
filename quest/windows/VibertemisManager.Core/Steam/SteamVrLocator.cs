// SteamVR locator.
//
// SteamVR's runtime path is recorded in
// %LOCALAPPDATA%\openvr\openvrpaths.vrpath as a JSON document with
// a "runtime" array. We never assume C:\Program Files (x86)\Steam
// and we never shell-parse the file. If the vrpath file is missing
// or unparseable we report "not installed" so the UI can show the
// steam://install/250820 button.
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Steam;

public sealed record SteamVrInstallStatus(bool Installed, IReadOnlyList<string> RuntimePaths, string? Reason)
{
    public static SteamVrInstallStatus NotInstalled(string reason)
        => new(false, System.Array.Empty<string>(), reason);
    public static SteamVrInstallStatus Found(IReadOnlyList<string> paths)
        => new(true, paths, null);
}

public interface ISteamVrLocator
{
    SteamVrInstallStatus Locate();
}

public sealed class VrpathSteamVrLocator : ISteamVrLocator
{
    private readonly IPathResolver _paths;
    private readonly IFileSystemAccess _fs;

    public VrpathSteamVrLocator(IPathResolver paths, IFileSystemAccess fs)
    {
        _paths = paths;
        _fs = fs;
    }

    public SteamVrInstallStatus Locate()
    {
        // The vrpath file lives at
        //   %LOCALAPPDATA%\openvr\openvrpaths.vrpath
        // Its "runtime" array is the canonical SteamVR runtime list
        // (one per configured runtime, in priority order). The UI
        // surfaces "installed" if any of those paths contain a
        // vrserver.exe / vrstartup.exe.
        var vrpathFile = Path.Combine(_paths.LocalAppData, "openvr", "openvrpaths.vrpath");
        if (!_fs.FileExists(vrpathFile))
            return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath not present; SteamVR not installed.");

        string text;
        try { text = _fs.ReadAllText(vrpathFile); }
        catch (System.Exception ex)
        {
            return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath unreadable: " + ex.Message);
        }

        string[]? runtimes;
        try
        {
            using var doc = JsonDocument.Parse(text);
            if (!doc.RootElement.TryGetProperty("runtime", out var runtime))
                return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath: no 'runtime' field.");
            if (runtime.ValueKind != JsonValueKind.Array)
                return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath: 'runtime' is not an array.");
            var list = new List<string>();
            foreach (var r in runtime.EnumerateArray())
            {
                if (r.ValueKind == JsonValueKind.String)
                {
                    var s = r.GetString();
                    if (!string.IsNullOrWhiteSpace(s)) list.Add(s!);
                }
            }
            runtimes = list.ToArray();
        }
        catch (JsonException ex)
        {
            return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath: invalid JSON: " + ex.Message);
        }

        if (runtimes.Length == 0)
            return SteamVrInstallStatus.NotInstalled("openvrpaths.vrpath: empty runtime array.");

        // The file lists runtime roots, each containing bin\win64
        // \\vrserver.exe / vrstartup.exe. We do NOT assert the file
        // exists here because the locator should report the
        // configured paths; the busy-check / process-table code is
        // the one that decides whether SteamVR is actually running.
        return SteamVrInstallStatus.Found(runtimes);
    }
}