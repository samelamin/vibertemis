// Single instance guard tests.
//
// Both the Windows named-mutex path and the file-backed test
// path share the same TryAcquire contract: first call wins,
// subsequent calls lose.
using System.IO;
using VibertemisManager.Core.SingleInstance;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class SingleInstanceGuardTests
{
    [Fact]
    public void AlwaysAcquires_AlwaysSucceeds()
    {
        var g = new AlwaysAcquiresSingleInstanceGuard();
        Assert.True(g.TryAcquire(out var h1));
        Assert.True(g.TryAcquire(out var h2));
        h1!.Dispose();
        h2!.Dispose();
    }

    [Fact]
    public void FileBacked_TwoAcquiresOnlyFirstSucceeds()
    {
        var dir = Path.Combine(Path.GetTempPath(), "vibt-si-" + System.Guid.NewGuid().ToString("N"));
        var f1 = new FileBackedSingleInstanceGuard(Path.Combine(dir, "v1.lock"));
        var f2 = new FileBackedSingleInstanceGuard(Path.Combine(dir, "v1.lock"));
        try
        {
            Assert.True(f1.TryAcquire(out var h1));
            Assert.False(f2.TryAcquire(out var h2));
            Assert.Null(h2);
            h1!.Dispose();
            Assert.True(f2.TryAcquire(out var h3));
            h3!.Dispose();
        }
        finally { try { Directory.Delete(dir, recursive: true); } catch { } }
    }

    [Fact]
    public void Factory_ProducesGuardsPerName()
    {
        var dir = Path.Combine(Path.GetTempPath(), "vibt-si-" + System.Guid.NewGuid().ToString("N"));
        try
        {
            var factory = new FileBackedSingleInstanceGuardFactory(dir);
            var a = factory.Create("manager");
            var b = factory.Create("manager");
            var c = factory.Create("other");
            Assert.True(a.TryAcquire(out var ha));
            Assert.False(b.TryAcquire(out var hb));
            Assert.Null(hb);
            Assert.True(c.TryAcquire(out var hc));
            ha!.Dispose();
            hc!.Dispose();
        }
        finally { try { Directory.Delete(dir, recursive: true); } catch { } }
    }
}