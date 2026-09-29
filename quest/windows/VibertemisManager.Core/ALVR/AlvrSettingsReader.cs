// ALVR session.json reader.
//
// ALVR's documented session.json shape (verified against
// quest/host/internal/alvr/adapter.go:validateSchema and the
// upstream ALVR codebase) places the dashboard-launch behaviour
// under:
//
//     session_settings.extra.steamvr_launcher.open_close_steamvr_with_dashboard
//
// The bundled ALVR Dashboard.exe defaults to
// open_close_steamvr_with_dashboard=false. If a user has manually
// imported an older ALVR config with that flag set to true,
// opening the dashboard launches SteamVR; the manager must
// surface that as an actionable warning and refuse to start the
// dashboard in that state. The manager NEVER silently mutates
// user settings.
//
// We deliberately do NOT enumerate every flag ALVR ships. The
// only field we read is open_close_steamvr_with_dashboard;
// everything else stays under ALVR's own dashboard UI. Adding
// fields here would duplicate ALVR's schema in a second place
// and drift when ALVR evolves.
using System;
using System.Text.Json;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.ALVR;

public enum AlvrLauncherWarning
{
    None,
    OpenCloseSteamvrWithDashboardTrue,
    MalformedSessionJson,
}

public enum AlvrLauncherState
{
    BundledDefaults,    // No session.json yet; ALVR's first run will produce it.
    ParsedOpenClose,    // session.json read; we have a verdict.
}

public sealed record AlvrLauncherAnalysis(
    AlvrLauncherState State,
    bool OpenCloseSteamvrWithDashboard,
    AlvrLauncherWarning Warning,
    string? Detail)
{
    public bool HasConflict => Warning != AlvrLauncherWarning.None;
}

public interface IAlvrSettingsReader
{
    bool TryRead(string sessionJsonPath, out AlvrLauncherAnalysis analysis);
    bool FileExists(string path);
}

public sealed class JsonAlvrSettingsReader : IAlvrSettingsReader
{
    private readonly IFileSystemAccess _fs;
    public JsonAlvrSettingsReader(IFileSystemAccess fs) => _fs = fs;

    public bool FileExists(string path) => _fs.FileExists(path);

    public bool TryRead(string sessionJsonPath, out AlvrLauncherAnalysis analysis)
    {
        analysis = default!;
        if (!_fs.FileExists(sessionJsonPath))
        {
            // Bundled defaults: ALVR's first run will produce the
            // session.json inside its own dashboard UI. Until
            // then, we have nothing to surface as a warning.
            analysis = new AlvrLauncherAnalysis(
                AlvrLauncherState.BundledDefaults,
                OpenCloseSteamvrWithDashboard: false,
                Warning: AlvrLauncherWarning.None,
                Detail: null);
            return true;
        }
        string text;
        try { text = _fs.ReadAllText(sessionJsonPath); }
        catch (Exception ex)
        {
            analysis = new AlvrLauncherAnalysis(
                AlvrLauncherState.ParsedOpenClose,
                OpenCloseSteamvrWithDashboard: false,
                Warning: AlvrLauncherWarning.MalformedSessionJson,
                Detail: $"Could not read session.json: {ex.Message}");
            return true;
        }
        try
        {
            using var doc = JsonDocument.Parse(text);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                analysis = new AlvrLauncherAnalysis(
                    AlvrLauncherState.ParsedOpenClose,
                    OpenCloseSteamvrWithDashboard: false,
                    Warning: AlvrLauncherWarning.MalformedSessionJson,
                    Detail: "session.json root must be a JSON object.");
                return true;
            }
            var path = new[] { "session_settings", "extra", "steamvr_launcher", "open_close_steamvr_with_dashboard" };
            var openClose = ReadBool(root, path, out var malformedDetail);
            if (malformedDetail != null)
            {
                analysis = new AlvrLauncherAnalysis(
                    AlvrLauncherState.ParsedOpenClose,
                    OpenCloseSteamvrWithDashboard: false,
                    Warning: AlvrLauncherWarning.MalformedSessionJson,
                    Detail: malformedDetail);
                return true;
            }
            var warning = openClose
                ? AlvrLauncherWarning.OpenCloseSteamvrWithDashboardTrue
                : AlvrLauncherWarning.None;
            analysis = new AlvrLauncherAnalysis(
                AlvrLauncherState.ParsedOpenClose,
                OpenCloseSteamvrWithDashboard: openClose,
                Warning: warning,
                Detail: null);
            return true;
        }
        catch (JsonException ex)
        {
            analysis = new AlvrLauncherAnalysis(
                AlvrLauncherState.ParsedOpenClose,
                OpenCloseSteamvrWithDashboard: false,
                Warning: AlvrLauncherWarning.MalformedSessionJson,
                Detail: $"session.json is not valid JSON: {ex.Message}");
            return true;
        }
    }

    private static bool ReadBool(JsonElement root, string[] path, out string? malformedDetail)
    {
        malformedDetail = null;
        var node = Navigate(root, path, out var detail);
        if (detail != null) { malformedDetail = detail; return false; }
        if (!node.HasValue) return false;
        var v = node.Value;
        if (v.ValueKind == JsonValueKind.True) return true;
        if (v.ValueKind == JsonValueKind.False) return false;
        malformedDetail = $"Expected boolean at {string.Join(".", path)}, got {v.ValueKind}.";
        return false;
    }

    private static JsonElement? Navigate(JsonElement root, string[] path, out string? malformedDetail)
    {
        malformedDetail = null;
        var cur = root;
        for (var i = 0; i < path.Length; i++)
        {
            if (cur.ValueKind != JsonValueKind.Object)
            {
                var prefix = string.Join(".", path, 0, i);
                malformedDetail = $"Expected object at {prefix}, got {cur.ValueKind}.";
                return null;
            }
            if (!cur.TryGetProperty(path[i], out var next)) return null;
            cur = next;
        }
        return cur;
    }
}
