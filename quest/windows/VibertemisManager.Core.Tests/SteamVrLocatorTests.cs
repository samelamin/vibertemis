// SteamVR vrpath parser tests with a fake filesystem; verifies
// that the parser:
//   - reads the runtime array (in order)
//   - returns NotInstalled when the file is missing
//   - returns NotInstalled when JSON is invalid
//   - never assumes C:\Program Files (x86)\Steam
using VibertemisManager.Core.Steam;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class SteamVrLocatorTests
{
    [Fact]
    public void Locate_ReadsRuntimeArrayInOrder()
    {
        var fs = new FakeFileSystem();
        var path = System.IO.Path.Combine(new FixedPaths("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "{\"runtime\":[\"D:/Steam/steamapps/common/SteamVR\",\"C:/Backup/SteamVR\"]}";
        var locator = new VrpathSteamVrLocator(new FixedPaths("/tmp/x"), fs);
        var status = locator.Locate();
        Assert.True(status.Installed);
        Assert.Equal(2, status.RuntimePaths.Count);
        Assert.Equal("D:/Steam/steamapps/common/SteamVR", status.RuntimePaths[0]);
        Assert.Equal("C:/Backup/SteamVR", status.RuntimePaths[1]);
    }

    [Fact]
    public void Locate_NotInstalledWhenFileMissing()
    {
        var fs = new FakeFileSystem();
        var locator = new VrpathSteamVrLocator(new FixedPaths("/tmp/x"), fs);
        var status = locator.Locate();
        Assert.False(status.Installed);
    }

    [Fact]
    public void Locate_NotInstalledOnInvalidJson()
    {
        var fs = new FakeFileSystem();
        var path = System.IO.Path.Combine(new FixedPaths("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "not json";
        var locator = new VrpathSteamVrLocator(new FixedPaths("/tmp/x"), fs);
        var status = locator.Locate();
        Assert.False(status.Installed);
        Assert.Contains("invalid JSON", status.Reason);
    }

    [Fact]
    public void Locate_NotInstalledOnEmptyRuntimeArray()
    {
        var fs = new FakeFileSystem();
        var path = System.IO.Path.Combine(new FixedPaths("/tmp/x").LocalAppData, "openvr", "openvrpaths.vrpath");
        fs.Files[path] = "{\"runtime\":[]}";
        var locator = new VrpathSteamVrLocator(new FixedPaths("/tmp/x"), fs);
        var status = locator.Locate();
        Assert.False(status.Installed);
        Assert.Contains("empty", status.Reason);
    }
}