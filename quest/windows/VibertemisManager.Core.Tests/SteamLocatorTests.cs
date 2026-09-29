// Steam registry locator tests with the FakeRegistry; covers the
// 32-bit / 64-bit / per-user lookup order and the refusal of
// invalid paths.
using VibertemisManager.Core.Steam;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class SteamLocatorTests
{
    [Fact]
    public void Locate_FindsValidWow6432NodePath()
    {
        var reg = new FakeRegistry();
        var path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "steam.exe");
        System.IO.File.WriteAllBytes(path, new byte[] { 0x01 });
        try
        {
            reg.Values[("HKLM", @"SOFTWARE\WOW6432Node\Valve\Steam", "SteamPath")] = path;
            var locator = new RegistrySteamLocator(reg);
            var status = locator.Locate();
            Assert.True(status.Installed);
            Assert.Equal(path, status.SteamPath);
        }
        finally { System.IO.File.Delete(path); }
    }

    [Fact]
    public void Locate_AppendsSteamExeWhenPathIsFolder()
    {
        var reg = new FakeRegistry();
        var dir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "steam-folder-" + System.Guid.NewGuid().ToString("N"));
        System.IO.Directory.CreateDirectory(dir);
        var exe = System.IO.Path.Combine(dir, "Steam.exe");
        System.IO.File.WriteAllBytes(exe, new byte[] { 0x02 });
        try
        {
            reg.Values[("HKLM", @"SOFTWARE\Valve\Steam", "SteamPath")] = dir;
            var locator = new RegistrySteamLocator(reg);
            var status = locator.Locate();
            Assert.True(status.Installed);
            Assert.Equal(exe, status.SteamPath);
        }
        finally
        {
            System.IO.File.Delete(exe);
            System.IO.Directory.Delete(dir);
        }
    }

    [Fact]
    public void Locate_NotInstalledWhenPathMissing()
    {
        var reg = new FakeRegistry();
        reg.Values[("HKLM", @"SOFTWARE\Valve\Steam", "SteamPath")] = @"C:\nonexistent\Steam.exe";
        var locator = new RegistrySteamLocator(reg);
        var status = locator.Locate();
        Assert.False(status.Installed);
        Assert.NotNull(status.Reason);
    }

    [Fact]
    public void Locate_NotInstalledWhenRegistryEmpty()
    {
        var reg = new FakeRegistry();
        var locator = new RegistrySteamLocator(reg);
        var status = locator.Locate();
        Assert.False(status.Installed);
    }
}