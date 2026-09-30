using System.Diagnostics;
using System.Text.Json.Nodes;

namespace VibertemisManager.Core.ALVR;

// One-time setup. Driver registration persists across sign-in/reconnects.
// Never starts SteamVR or changes an existing session's codec/settings.
public sealed class VrSetup
{
    private readonly string _root;
    private readonly string _vrpaths;
    private readonly Action _requireIdle;
    private readonly Action _verifyPayload;
    private readonly Action<string, string, string> _run;
    public VrSetup(string root, string vrpaths, Action requireIdle, Action verifyPayload,
        Action<string, string, string>? run = null)
    {
        _root = Path.GetFullPath(root);
        _vrpaths = vrpaths;
        _requireIdle = requireIdle;
        _verifyPayload = verifyPayload;
        _run = run ?? RunVrpathreg;
    }

    private string Runtime => Path.Combine(_root, "runtime");
    private JsonObject ReadPaths() => JsonNode.Parse(File.ReadAllText(_vrpaths)) as JsonObject
        ?? throw new InvalidDataException("SteamVR paths are unreadable. Open SteamVR once, close it, then retry.");
    private static string[] Entries(JsonObject paths, string key) =>
        (paths[key] as JsonArray)?.Select(n => n?.GetValue<string>()
            ?? throw new InvalidDataException("Invalid SteamVR path")).ToArray() ?? [];
    private static bool SamePath(string a, string b) =>
        Path.GetFullPath(a).TrimEnd(Path.DirectorySeparatorChar).Equals(
            Path.GetFullPath(b).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase);

    public string[] ConflictingDrivers()
    {
        return Entries(ReadPaths(), "external_drivers").Where(path => {
            if (SamePath(path, Runtime)) return false;
            var manifest = Path.Combine(path, "driver.vrdrivermanifest");
            if (!File.Exists(manifest)) return false;
            var name = JsonNode.Parse(File.ReadAllText(manifest))?["name"]?.GetValue<string>();
            return name == "alvr_server";
        }).ToArray();
    }

    public void Prepare(bool replaceOtherAlvr)
    {
        _requireIdle();
        _verifyPayload();
        var conflicts = ConflictingDrivers();
        if (conflicts.Length > 0 && !replaceOtherAlvr)
            throw new InvalidOperationException("Another ALVR driver is registered. Confirm switching to this bundled driver first.");
        var tool = Entries(ReadPaths(), "runtime").Select(p => Path.Combine(p, "bin", "win64", "vrpathreg.exe"))
            .FirstOrDefault(File.Exists) ?? throw new FileNotFoundException("SteamVR registration tool missing. Install SteamVR through Steam, open it once, then close it.");
        var removed = new List<string>();
        bool registered = Entries(ReadPaths(), "external_drivers").Any(p => SamePath(p, Runtime));
        try
        {
            foreach (var path in conflicts) { _requireIdle(); _run(tool, "removedriver", path); removed.Add(path); }
            if (!registered) { _requireIdle(); _run(tool, "adddriver", Runtime); }
            if (!Entries(ReadPaths(), "external_drivers").Any(p => SamePath(p, Runtime)) || ConflictingDrivers().Length != 0)
                throw new InvalidOperationException("SteamVR did not confirm driver registration. Retry Prepare VR.");
            _requireIdle();
            InitializeSession(Path.Combine(Runtime, "session.json"));
        }
        catch
        {
            // Best effort registration rollback, only while still idle. Never
            // remove unrelated drivers or touch their files/settings.
            try {
                _requireIdle();
                if (!registered) _run(tool, "removedriver", Runtime);
                foreach (var path in removed) _run(tool, "adddriver", path);
            } catch { /* Original error remains actionable; Prepare can be retried. */ }
            throw;
        }
    }

    public static void InitializeSession(string path)
    {
        if (File.Exists(path)) {
            var existing = JsonNode.Parse(File.ReadAllText(path));
            if (existing?["server_version"]?.GetValue<string>() != "20.14.1-vibertemis-pyro.1")
                throw new InvalidDataException("Existing VR settings belong to a different runtime. Keep a backup and repair the matching host installation.");
            return;
        }
        using var resource = typeof(VrSetup).Assembly.GetManifestResourceStream("Vibertemis.VR.DefaultSession")
            ?? throw new InvalidDataException("Bundled VR defaults missing. Reinstall this package.");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try {
            using (var file = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                resource.CopyTo(file); file.Flush(true);
            }
            File.Move(temp, path, overwrite: false);
        } finally { if (File.Exists(temp)) File.Delete(temp); }
    }

    private static void RunVrpathreg(string tool, string operation, string path)
    {
        var info = new ProcessStartInfo(tool) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        info.ArgumentList.Add(operation); info.ArgumentList.Add(path);
        using var process = Process.Start(info) ?? throw new IOException("SteamVR registration could not start");
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(15000)) { process.Kill(); throw new IOException("SteamVR registration timed out. Retry Prepare VR."); }
        Task.WaitAll(stdout, stderr);
        if (process.ExitCode != 0) throw new IOException("SteamVR registration failed: " + stderr.Result);
    }
}
