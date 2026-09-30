#if WINDOWS
using VibertemisManager.Core.Prerequisites;
using VibertemisManager.Core.Platform.Windows;
using Xunit;
namespace VibertemisManager.Core.Tests;

public class WindowsVcRedistInspectorSmoke
{
    private static string Bundle()
    {
        var path = Environment.GetEnvironmentVariable("VIBERTEMIS_VC_REDIST_TEST_FILE");
        Assert.True(!string.IsNullOrWhiteSpace(path) && File.Exists(path), "Build the installer and set VIBERTEMIS_VC_REDIST_TEST_FILE; this check must not silently skip.");
        return path!;
    }
    [Fact]
    public void RealMicrosoftPackagePasses()
    {
        var pkg = new WindowsVcRedistInspector().Inspect(Bundle(), new VcVersion(14,44,35207,0), VcRedistVerifier.ExpectedPublisher);
        Assert.NotNull(pkg);
        Assert.Equal("Microsoft Corporation", pkg.Publisher);
        Assert.Equal(64, pkg.Sha256.Length);
    }
    [Fact]
    public void TamperedPackageFails()
    {
        var path = Path.Combine(Path.GetTempPath(), "vibertemis-tamper-"+Guid.NewGuid()+".exe");
        try {
            File.Copy(Bundle(), path);
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.ReadWrite)) {
                stream.Position = 0x10000;
                var value = stream.ReadByte();
                Assert.NotEqual(-1, value);
                stream.Position--;
                stream.WriteByte((byte)(value ^ 0xff));
            }
            Assert.Null(new WindowsVcRedistInspector().Inspect(path, new VcVersion(14,44,35207,0), VcRedistVerifier.ExpectedPublisher));
        } finally { File.Delete(path); }
    }
}
#endif
