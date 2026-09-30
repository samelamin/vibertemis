// SteamVR discovery tests. The fake filesystem and Steam locator
// cover the four-way classification:
//
//   - InstalledAndReady: vrpath present + vrserver.exe / vrstartup.exe
//     in at least one runtime root.
//   - InstalledUninitialized: vrpath absent but Steam has the
//     appmanifest_250820.acf under a library's steamapps/.
//   - StaleRecorded: vrpath present but every runtime is missing
//     its binaries.
//   - Missing: no vrpath and no Steam library with the SteamVR
//     manifest.
//
// The SafeVdfReader tests cover the libraryfolders.vdf format with
// escapes, nested objects, alternative library roots, and
// malformed input.
using System;
using System.Collections.Generic;
using System.IO;
using VibertemisManager.Core.Paths;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.Steam;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class SteamVrLocatorTests
{
    /// <summary>
    /// A configurable Steam locator. Returns the configured
    /// install path or "not installed" as the test demands.
    /// </summary>
    private sealed class FakeSteamLocator : ISteamLocator
    {
        private readonly string? _steamPath;
        public FakeSteamLocator(string? steamPath) { _steamPath = steamPath; }
        public SteamInstallStatus Locate()
            => _steamPath is null
                ? SteamInstallStatus.NotInstalled("Steam not installed.")
                : SteamInstallStatus.Found(_steamPath);
    }

    private static void SeedRuntime(SteamVrTestFileSystem fs,string apps) {
        fs.Files[Path.Combine(apps,"appmanifest_250820.acf")]= "\"AppState\" { \"installdir\" \"SteamVR\" }";
        fs.Files[Path.Combine(apps,"common","SteamVR","bin","win64","vrserver.exe")]="";
        fs.Files[Path.Combine(apps,"common","SteamVR","bin","win64","vrstartup.exe")]="";
    }
    private static FixedPaths Fixed(string localAppData) => new(localAppData);

    [Fact]
    public void Locate_ReadsRuntimeArrayInOrder()
    {
        var fs = new SteamVrTestFileSystem();
        var path = Path.Combine(Fixed("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "{\"runtime\":[\"D:/Steam/steamapps/common/SteamVR\",\"C:/Backup/SteamVR\"]}";
        foreach(var runtime in new[]{"D:/Steam/steamapps/common/SteamVR","C:/Backup/SteamVR"}) {
            fs.Files[Path.Combine(runtime,"bin","win64","vrserver.exe")]="";
            fs.Files[Path.Combine(runtime,"bin","win64","vrstartup.exe")]="";
        }
        var locator = new VrpathSteamVrLocator(Fixed("/tmp/x"), fs, new FakeSteamLocator(null));
        var status = locator.Locate();
        Assert.True(status.Installed);
        Assert.Equal(2, status.RuntimePaths.Count);
        Assert.Equal("D:/Steam/steamapps/common/SteamVR", status.RuntimePaths[0]);
        Assert.Equal("C:/Backup/SteamVR", status.RuntimePaths[1]);
    }

    [Fact]
    public void Locate_NotInstalledWhenFileMissing()
    {
        var fs = new SteamVrTestFileSystem();
        var locator = new VrpathSteamVrLocator(Fixed("/tmp/x"), fs, new FakeSteamLocator(null));
        var status = locator.Locate();
        Assert.False(status.Installed);
    }

    [Fact]
    public void Locate_NotInstalledOnInvalidJson()
    {
        var fs = new SteamVrTestFileSystem();
        var path = Path.Combine(Fixed("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "not json";
        var locator = new VrpathSteamVrLocator(Fixed("/tmp/x"), fs, new FakeSteamLocator(null));
        var status = locator.Locate();
        Assert.False(status.Installed);
        Assert.Contains("invalid JSON", status.Reason);
    }

    [Fact]
    public void Locate_NotInstalledOnEmptyRuntimeArray()
    {
        var fs = new SteamVrTestFileSystem();
        var path = Path.Combine(Fixed("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "{\"runtime\":[]}";
        var locator = new VrpathSteamVrLocator(Fixed("/tmp/x"), fs, new FakeSteamLocator(null));
        var status = locator.Locate();
        Assert.False(status.Installed);
        Assert.Contains("empty", status.Reason);
    }

    /// <summary>
    /// Stale recorded: vrpath is present with a runtime path, but
    /// vrserver.exe / vrstartup.exe are missing.
    /// </summary>
    [Fact]
    public void Discover_StaleRecorded_WhenVrpathPresentButBinariesMissing()
    {
        var fs = new SteamVrTestFileSystem();
        var localAppData = "/tmp/x";
        var runtime = Path.Combine(localAppData, "SteamVR");
        var vrpath = Path.Combine(localAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[vrpath] = "{\"runtime\":[\"" + runtime + "\"]}";
        // Deliberately do NOT create vrserver.exe / vrstartup.exe
        var locator = new VrpathSteamVrLocator(Fixed(localAppData), fs, new FakeSteamLocator(null));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.StaleRecorded, discovery.Kind);
        Assert.False(discovery.Installed);
        Assert.Single(discovery.RuntimePaths);
        Assert.Contains("missing", discovery.Reason, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// InstalledAndReady: vrpath + vrserver.exe + vrstartup.exe.
    /// </summary>
    [Fact]
    public void Discover_InstalledAndReady_WhenBinariesPresent()
    {
        var fs = new SteamVrTestFileSystem();
        var localAppData = "/tmp/x";
        var runtime = Path.Combine(localAppData, "SteamVR");
        var win64 = Path.Combine(runtime, "bin", "win64");
        fs.Files[Path.Combine(localAppData, "openvr", "openvrpaths.vrpath")] = "{\"runtime\":[\"" + runtime + "\"]}";
        fs.Files[Path.Combine(win64, "vrserver.exe")] = "";
        fs.Files[Path.Combine(win64, "vrstartup.exe")] = "";
        var locator = new VrpathSteamVrLocator(Fixed(localAppData), fs, new FakeSteamLocator(null));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.InstalledAndReady, discovery.Kind);
        Assert.True(discovery.SteamVrReady);
        Assert.Single(discovery.RuntimePaths);
        Assert.Null(discovery.Reason);
    }

    /// <summary>
    /// InstalledUninitialized: vrpath missing, but Steam has the
    /// appmanifest_250820.acf under its primary library.
    /// </summary>
    [Fact]
    public void Discover_InstalledUninitialized_WhenVrpathMissingButSteamHasManifest()
    {
        var fs = new SteamVrTestFileSystem();
        var localAppData = "/tmp/x";
        var steamRoot = Path.Combine(localAppData, "Steam");
        var steamApps = Path.Combine(steamRoot, "steamapps");
        SeedRuntime(fs,steamApps);
        fs.DirectoryExistsResponses[steamApps] = true;
        var locator = new VrpathSteamVrLocator(Fixed(localAppData), fs,
            new FakeSteamLocator(Path.Combine(steamRoot, "Steam.exe")));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.InstalledUninitialized, discovery.Kind);
        Assert.False(discovery.SteamVrReady);
        Assert.True(discovery.Installed);
        Assert.Single(discovery.SteamLibraries);
    }

    /// <summary>
    /// Missing: vrpath missing, Steam not installed.
    /// </summary>
    [Fact]
    public void Discover_Missing_WhenSteamNotInstalled()
    {
        var fs = new SteamVrTestFileSystem();
        var locator = new VrpathSteamVrLocator(Fixed("/tmp/x"), fs, new FakeSteamLocator(null));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.Missing, discovery.Kind);
        Assert.False(discovery.Installed);
    }

    /// <summary>
    /// Missing: Steam installed but no SteamVR manifest.
    /// </summary>
    [Fact]
    public void Discover_Missing_WhenSteamInstalledButNoSteamVr()
    {
        var fs = new SteamVrTestFileSystem();
        var localAppData = "/tmp/x";
        var steamRoot = Path.Combine(localAppData, "Steam");
        var locator = new VrpathSteamVrLocator(Fixed(localAppData), fs,
            new FakeSteamLocator(Path.Combine(steamRoot, "Steam.exe")));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.Missing, discovery.Kind);
    }

    /// <summary>
    /// Valid alternate library: libraryfolders.vdf points at
    /// E:\OtherLib; the appmanifest_250820.acf lives there even
    /// though the primary Steam install has none.
    /// </summary>
    [Fact]
    public void Discover_InstalledUninitialized_FindsAlternateLibrary()
    {
        var fs = new SteamVrTestFileSystem();
        var localAppData = "/tmp/x";
        var steamRoot = Path.Combine(localAppData, "Steam");
        var primaryApps = Path.Combine(steamRoot, "steamapps");
        var altApps = Path.Combine("/mnt", "OtherLib", "steamapps");
        fs.DirectoryExistsResponses[primaryApps] = true;
        fs.DirectoryExistsResponses[altApps] = true;
        fs.Files[Path.Combine(primaryApps, "libraryfolders.vdf")] =
            "\"libraryfolders\"\n{\n\"0\"\n\t{\n\"path\"\t\"" + Path.Combine("/mnt", "OtherLib").Replace("\\", "\\\\") + "\"\n}\n}\n";
        SeedRuntime(fs,altApps);
        var locator = new VrpathSteamVrLocator(Fixed(localAppData), fs,
            new FakeSteamLocator(Path.Combine(steamRoot, "Steam.exe")));
        var discovery = locator.Discover();
        Assert.Equal(SteamVrDiscoveryKind.InstalledUninitialized, discovery.Kind);
        Assert.Single(discovery.SteamLibraries);
        Assert.Equal(altApps, discovery.SteamLibraries[0]);
    }

    /// <summary>
    /// SafeVdfReader escapes: a quoted path with embedded \" / \n /
    /// \t sequences must be unescaped correctly.
    /// </summary>
    [Fact]
    public void SafeVdfReader_UnescapesQuotedPath()
    {
        string vdf = "\"libraryfolders\" { \"0\" { \"path\" \"D:\\\\Steam\\tLib\" } }";
        var result = SafeVdfReader.ReadLibraryFolders(vdf);
        Assert.Single(result);
        // The escape rules decode \\\\ as \\ and \t as a tab.
        Assert.Contains("Steam\tLib", result[0]);
    }

    /// <summary>
    /// Malformed VDF: the parser stops cleanly and returns whatever
    /// it has already collected, never throwing.
    /// </summary>
    [Fact]
    public void SafeVdfReader_MalformedInputReturnsCollectedValues()
    {
        string vdf = "\"libraryfolders\"\n{\n\"0\"\n{\n\"path\"\t\"D:\\\\SteamLib\"\n}\n";
        // Truncated (missing closing braces).
        var result = SafeVdfReader.ReadLibraryFolders(vdf);
        Assert.Single(result);
        Assert.Equal("D:\\SteamLib", result[0]);
    }

    [Fact]
    public void SafeVdfReader_HandlesNestedLibraryObject()
    {
        // Steam's libraryfolders.vdf structure: each entry has a
        // path, label, contentid, etc. Verify the parser does not
        // lose track of which object contains which path.
        string vdf =
            "\"libraryfolders\"\n" +
            "{\n" +
            "  \"0\"\n  {\n    \"path\" \"D:/MainLib\"\n    \"label\" \"\"\n    \"contentid\" \"123\"\n  }\n" +
            "  \"1\"\n  {\n    \"path\" \"E:/OtherLib\"\n    \"label\" \"Alt\"\n    \"contentid\" \"456\"\n  }\n" +
            "}\n";
        var result = SafeVdfReader.ReadLibraryFolders(vdf);
        Assert.Equal(2, result.Count);
        Assert.Equal("D:/MainLib", result[0]);
        Assert.Equal("E:/OtherLib", result[1]);
    }

    [Fact]
    public void SafeVdfReader_EmptyInputReturnsEmpty()
    {
        Assert.Empty(SafeVdfReader.ReadLibraryFolders(""));
        Assert.Empty(SafeVdfReader.ReadLibraryFolders("   "));
    }

    [Fact]
    public void SafeVdfReader_NoLibraryfoldersKeyReturnsEmpty()
    {
        // Top-level object but no libraryfolders key.
        Assert.Empty(SafeVdfReader.ReadLibraryFolders("\"something_else\"\n{\n\"path\" \"X\"\n}\n"));
    }
}

/// <summary>
/// Fake filesystem that supports directory existence overrides so
/// the SteamVR locator's "is steamapps/ a real directory" check can
/// be exercised without writing files for the directories themselves.
/// </summary>
internal sealed class SteamVrTestFileSystem : IFileSystemAccess
{
    public Dictionary<string, string> Files { get; } = new();
    public Dictionary<string, bool> DirectoryExistsResponses { get; } = new();
    public bool FileExists(string path) => Files.ContainsKey(path);
    public string ReadAllText(string path) => Files[path];
    public bool DirectoryExists(string path)
        => DirectoryExistsResponses.TryGetValue(path, out var v) ? v : true;
    public IEnumerable<string> EnumerateFiles(string directory, string pattern)
    {
        foreach (var f in Files.Keys)
            if (f.StartsWith(directory)) yield return f;
    }
}