using System.IO;
using VibertemisManager.Core.Platform.Abstractions;
namespace VibertemisManager.Core.ALVR;
public sealed record AlvrSessionLocation(string SessionJsonPath, string DashboardExePath, string DriverManifestPath, string ConfigRoot);
public interface IAlvrSessionLocator { AlvrSessionLocation Resolve(); }
public sealed class DefaultAlvrSessionLocator : IAlvrSessionLocator
{
    private readonly IPathResolver _paths;
    public DefaultAlvrSessionLocator(IPathResolver paths, IFileSystemAccess fs) => _paths = paths;
    public AlvrSessionLocation Resolve()
    {
        // Portable ALVR derives config/driver paths from Dashboard's parent.
        var root = Path.Combine(_paths.ProgramsRoot, "runtime");
        return new(Path.Combine(root, "session.json"),
            Path.Combine(root, "ALVR Dashboard.exe"),
            Path.Combine(root, "driver.vrdrivermanifest"), root);
    }
}
