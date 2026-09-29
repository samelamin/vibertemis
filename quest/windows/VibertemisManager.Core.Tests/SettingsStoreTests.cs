// User settings store round-trip tests.
using System;
using System.IO;
using VibertemisManager.Core.Settings;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class SettingsStoreTests : IDisposable
{
    private readonly string _root;

    public SettingsStoreTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "vibt-settings-" + Guid.NewGuid().ToString("N"));
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public void Load_ReturnsDefaultsWhenFileMissing()
    {
        var store = new JsonSettingsStore(new FixedPaths(_root), new FakeFileSystem());
        var settings = store.Load();
        Assert.Null(settings.LastSelectedAdapterId);
        Assert.False(settings.AutoStartWithWindows);
        Assert.False(settings.RestoreCompanionOnStartup);
        Assert.True(settings.ShowTrayOnClose);
        Assert.Equal(28540, settings.CompanionListenPort);
    }

    [Fact]
    public void SaveThenLoad_RoundTrips()
    {
        var fs = new VibertemisManager.Core.Paths.RealFileSystemAccess();
        var store = new JsonSettingsStore(new FixedPaths(_root), fs);
        var settings = new UserSettings
        {
            LastSelectedAdapterId = "{AAAAAAAA-AAAA}",
            LastSelectedAdapterAddress = "192.168.1.10",
            AutoStartWithWindows = true,
            RestoreCompanionOnStartup = true,
            ShowTrayOnClose = false,
            CompanionListenAddress = "192.168.1.10",
            CompanionListenPort = 28541,
        };
        store.Save(settings);
        var loaded = store.Load();
        Assert.Equal(settings.LastSelectedAdapterId, loaded.LastSelectedAdapterId);
        Assert.Equal(settings.LastSelectedAdapterAddress, loaded.LastSelectedAdapterAddress);
        Assert.True(loaded.AutoStartWithWindows);
        Assert.True(loaded.RestoreCompanionOnStartup);
        Assert.False(loaded.ShowTrayOnClose);
        Assert.Equal((ushort)28541, loaded.CompanionListenPort);
    }

    [Fact]
    public void Load_ReturnsDefaultsOnCorruptJson()
    {
        var fs = new FakeFileSystem();
        var path = Path.Combine(_root, "VibertemisVRHostManager", "settings.json");
        fs.Files[path] = "<<not json>>";
        var store = new JsonSettingsStore(new FixedPaths(_root), fs);
        var s = store.Load();
        Assert.Null(s.LastSelectedAdapterId);
        Assert.False(s.AutoStartWithWindows);
    }
}