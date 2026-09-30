// Settings persistence.
//
// User settings live at
//   %LOCALAPPDATA%\VibertemisVRHostManager\settings.json
// They capture the user's IPv4 selection (so we can re-bind the
// companion on subsequent launches without asking again), the
// opt-in "Keep host ready after Windows sign-in" preference,
// and an opt-in flag for restoring the companion on startup.
// Settings never contain credentials or tokens.
//
// Existing startup choices survive upgrades. A missing settings file receives
// a recommended pending choice; the Run entry is written on explicit Start.
using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
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

    // Phase-2 unified keep-ready preference. Optional in the
    // file so older settings JSON round-trips cleanly.
    public bool? KeepHostReadyAfterSignIn { get; set; }
    public bool? StartupPreferencePersisted { get; set; }

    // Receiving-mode opt-in (PERSISTED). Default false so a fresh
    // manager starts CLOSED on the Go companion's enrollment
    // service. The Setup VR / Pair headset action enables and
    // persists this preference; explicit "Turn off" disables it
    // and clears the persisted flag. Optional bool so older
    // settings JSON round-trips cleanly.
    public bool? ReceivePairingRequests { get; set; }

    // In-memory session flag. The tray coordinator toggles this
    // when the user turns the mode on / off for this session
    // without touching disk. It is intentionally NOT serialized:
    // a) the persisted ReceivePairingRequests is the source of
    //    truth across launches; b) leaking a stale session flag
    //    into disk would resurrect a receiving lease the user
    //    thought they had turned off; c) the session flag has
    //    no meaning outside the running process.
    [JsonIgnore]
    public bool ReceivePairingRequestsSession { get; set; }

    // Persisted Suppress1h wall-clock deadline. The coordinator
    // POSTs /suppress with this exact value on its rising-edge
    // detection; the value is the canonical owner-controlled
    // deadline and is NOT recomputed on retry (a failing POST
    // retries with the SAME value so the cooldown cannot be
    // extended by repeated transient failures). Nullable so a
    // missing field round-trips to "not suppressed".
    public DateTime? SuppressPairingUntilUtc { get; set; }
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
        if (!_fs.FileExists(file)) return new UserSettings { KeepHostReadyAfterSignIn = true, StartupPreferencePersisted = false };
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
