// Settings persistence.
//
// User settings live at
//   %LOCALAPPDATA%\VibertemisVRHostManager\settings.json
// They capture the user's IPv4 selection (so we can re-bind the
// companion on subsequent launches without asking again), the
// opt-in autostart flag, and an opt-in flag for restoring the
// companion on startup. Settings never contain credentials or
// tokens.
using System;
using System.IO;
using System.Text.Json;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Settings;

public sealed class UserSettings
{
    public string? LastSelectedAdapterId { get; set; }
    public string? LastSelectedAdapterAddress { get; set; }
    public bool AutoStartWithWindows { get; set; }
    public bool RestoreCompanionOnStartup { get; set; }
    public bool ShowTrayOnClose { get; set; } = true;
    public int SchemaVersion { get; set; } = 1;
    public string CompanionListenAddress { get; set; } = "127.0.0.1";
    public ushort CompanionListenPort { get; set; } = 28540;
}

public interface ISettingsStore
{
    UserSettings Load();
    void Save(UserSettings settings);
}

public sealed class JsonSettingsStore : ISettingsStore
{
    private readonly IPathResolver _paths;
    private readonly IFileSystemAccess _fs;

    public JsonSettingsStore(IPathResolver paths, IFileSystemAccess fs)
    {
        _paths = paths;
        _fs = fs;
    }

    public UserSettings Load()
    {
        var file = Path.Combine(_paths.ManagerStateDir, "settings.json");
        if (!_fs.FileExists(file)) return new UserSettings();
        try
        {
            var text = _fs.ReadAllText(file);
            var s = JsonSerializer.Deserialize<UserSettings>(text, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            return s ?? new UserSettings();
        }
        catch
        {
            // Settings file is corrupt; return defaults so the
            // manager can still launch. The corrupt file is left
            // in place so a power user can inspect it; next Save
            // overwrites with valid JSON.
            return new UserSettings();
        }
    }

    public void Save(UserSettings settings)
    {
        Directory.CreateDirectory(_paths.ManagerStateDir);
        var file = Path.Combine(_paths.ManagerStateDir, "settings.json");
        var temp = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
        var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                var bytes = System.Text.Encoding.UTF8.GetBytes(json);
                stream.Write(bytes);
                stream.Flush(flushToDisk: true);
            }
            // Failed replacement preserves the original and reports the error.
            File.Move(temp, file, overwrite: true);
        }
        finally
        {
            if (File.Exists(temp)) File.Delete(temp);
        }
    }
}
