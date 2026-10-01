using System.Text;
using System.Text.Json;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Steam;

public enum SteamVrDiscoveryKind { InstalledAndReady, InstalledUninitialized, StaleRecorded, Missing }
public sealed record SteamVrDiscoveryStatus(SteamVrDiscoveryKind Kind, IReadOnlyList<string> RuntimePaths,
    IReadOnlyList<string> SteamLibraries, string? Reason)
{
    public bool Installed => Kind is SteamVrDiscoveryKind.InstalledAndReady or SteamVrDiscoveryKind.InstalledUninitialized;
    public bool SteamVrReady => Kind == SteamVrDiscoveryKind.InstalledAndReady;
}
public sealed record SteamVrInstallStatus(bool Installed, IReadOnlyList<string> RuntimePaths, string? Reason)
{
    public static SteamVrInstallStatus NotInstalled(string reason) => new(false, Array.Empty<string>(), reason);
    public static SteamVrInstallStatus Found(IReadOnlyList<string> paths) => new(true, paths, null);
}
public interface ISteamVrLocator { SteamVrInstallStatus Locate(); SteamVrDiscoveryStatus Discover(); }
public sealed class VrpathSteamVrLocator : ISteamVrLocator
{
    private readonly IPathResolver _paths;
    private readonly IFileSystemAccess _fs;
    private readonly ISteamLocator? _steam;
    public VrpathSteamVrLocator(IPathResolver paths, IFileSystemAccess fs, ISteamLocator? steam = null)
        => (_paths,_fs,_steam) = (paths,fs,steam);
    public SteamVrInstallStatus Locate() {
        var s=Discover();
        return new(s.SteamVrReady,s.RuntimePaths,s.Reason);
    }
    private bool HasBinaries(string root) => _fs.FileExists(Path.Combine(root,"bin","win64","vrserver.exe"))
        && _fs.FileExists(Path.Combine(root,"bin","win64","vrstartup.exe"));
    public SteamVrDiscoveryStatus Discover()
    {
        var recorded = new List<string>();
        string? reason = null;
        var path=Path.Combine(_paths.LocalAppData,"openvr","openvrpaths.vrpath");
        bool hadRecord=_fs.FileExists(path);
        if(hadRecord) {
            try {
                using var doc=JsonDocument.Parse(_fs.ReadAllText(path));
                foreach(var value in doc.RootElement.GetProperty("runtime").EnumerateArray())
                    if(value.GetString() is string runtime && !string.IsNullOrWhiteSpace(runtime)) recorded.Add(runtime);
                reason=recorded.Count==0 ? "Recorded runtime array is empty." : "Recorded SteamVR binaries are missing.";
            } catch { reason="SteamVR path record contains invalid JSON."; }
        }
        var verified=recorded.Where(HasBinaries).ToArray();
        if(verified.Length>0) return new(SteamVrDiscoveryKind.InstalledAndReady,verified,Array.Empty<string>(),null);
        var roots=new List<string>();var libraries=new List<string>();
        var steam=_steam?.Locate();
        if(steam?.Installed==true && !string.IsNullOrEmpty(steam.SteamPath)) {
            var root=Path.GetDirectoryName(steam.SteamPath);
            if(!string.IsNullOrEmpty(root)) {
                var candidates=new List<string>{root};
                var folders=Path.Combine(root,"steamapps","libraryfolders.vdf");
                if(_fs.FileExists(folders)) try { candidates.AddRange(SafeVdfReader.ReadLibraryFolders(_fs.ReadAllText(folders))); } catch { }
                foreach(var library in candidates.Distinct(StringComparer.OrdinalIgnoreCase)) {
                    var apps=Path.Combine(library,"steamapps");var manifest=Path.Combine(apps,"appmanifest_250820.acf");
                    if(!_fs.FileExists(manifest))continue;
                    try {
                        var dir=SafeVdfReader.InstallDirectory(_fs.ReadAllText(manifest));
                        if(string.IsNullOrWhiteSpace(dir)||dir is "." or ".."||dir.IndexOfAny(new[]{'/', '\\', ':'})>=0)continue;
                        var runtime=Path.Combine(apps,"common",dir);
                        if(!HasBinaries(runtime))continue;
                        roots.Add(runtime);libraries.Add(apps);
                    } catch { }
                }
            }
        }
        if(roots.Count>0)return new(SteamVrDiscoveryKind.InstalledUninitialized,roots,libraries,
            "SteamVR is installed. Open it once to finish setup, then close it and retry Set up VR.");
        return new(hadRecord?SteamVrDiscoveryKind.StaleRecorded:SteamVrDiscoveryKind.Missing,recorded,libraries,
            reason??"Install SteamVR through Steam.");
    }
}

// A bounded reader for Valve's quoted key/value format. Completed fields can
// still be used after a truncated file; executable checks remain authoritative.
public static class SafeVdfReader
{
    public static IReadOnlyList<string> ReadLibraryFolders(string text) {
        var result=new List<string>();var root=new Parser(text).ReadMap(0);
        if(root.TryGetValue("libraryfolders",out var value)&&value is Dictionary<string,object> libraries)
            foreach(var item in libraries.Values)
                if(item is Dictionary<string,object> entry && entry.TryGetValue("path",out var path)&&path is string s&&!string.IsNullOrWhiteSpace(s))result.Add(s);
        return result;
    }
    public static string? InstallDirectory(string text) {
        var root=new Parser(text).ReadMap(0);
        return root.TryGetValue("AppState",out var value)&&value is Dictionary<string,object> state
            && state.TryGetValue("installdir",out var dir) ? dir as string : null;
    }
    private sealed class Parser
    {
        private readonly string _text;private int _i,_tokens;
        public Parser(string text){_text=text.Length<=1048576?text:"";}
        public Dictionary<string,object> ReadMap(int depth) {
            var map=new Dictionary<string,object>(StringComparer.OrdinalIgnoreCase);
            if(depth>16)return map;
            while(Token() is string key) {
                if(key=="}")break;
                if(key=="{"){foreach(var p in ReadMap(depth+1))map[p.Key]=p.Value;continue;}
                string? value=Token();if(value==null||value=="}")break;
                map[key]=value=="{"?ReadMap(depth+1):value;
            }
            return map;
        }
        private string? Token() {
            if(++_tokens>20000)return null;
            while(_i<_text.Length) {
                if(char.IsWhiteSpace(_text[_i])){_i++;continue;}
                if(_i+1<_text.Length&&_text[_i]=='/'&&_text[_i+1]=='/'){
                    while(_i<_text.Length&&_text[_i]!='\n')_i++;continue;
                }
                break;
            }
            if(_i>=_text.Length)return null;
            char c=_text[_i++];if(c=='{'||c=='}')return c.ToString();
            if(c!='"')return null;
            var value=new StringBuilder();
            while(_i<_text.Length) {
                c=_text[_i++];if(c=='"')return value.ToString();
                if(c=='\\'&&_i<_text.Length){c=_text[_i++];value.Append(c switch{'n'=>'\n','t'=>'\t','r'=>'\r',_=>c});}
                else value.Append(c);
            }
            return null;
        }
    }
}
