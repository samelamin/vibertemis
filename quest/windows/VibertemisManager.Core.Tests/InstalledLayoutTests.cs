using System;
using System.IO;
using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.Paths;
using Xunit;
namespace VibertemisManager.Core.Tests;
public class InstalledLayoutTests
{
    [Fact]
    public void CustomInstallUsesPortableRuntimeEvenBeforeSessionExists()
    {
        var root = Path.Combine(Path.GetTempPath(), "Custom VR ü folder");
        var profile = Path.Combine(Path.GetTempPath(), "test user profile");
        var paths = new EnvironmentPathResolver(profile, null, Path.Combine(root, "manager"));
        var session = new DefaultAlvrSessionLocator(paths, new FakeFileSystem()).Resolve();
        Assert.Equal(root, paths.ProgramsRoot);
        Assert.Equal(Path.Combine(root, "runtime", "session.json"), session.SessionJsonPath);
        Assert.Equal(Path.GetDirectoryName(session.DashboardExePath), session.ConfigRoot);
        Assert.Equal(session.ConfigRoot, Path.GetDirectoryName(session.DriverManifestPath));
        Assert.StartsWith(profile, paths.CompanionStateDir);
    }
}
