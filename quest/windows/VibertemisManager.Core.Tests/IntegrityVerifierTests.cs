// Integrity verifier tests using a temporary file fixture.
//
// We build an integrity manifest that references a real file on
// disk, then mutate either the file bytes or the manifest and
// assert that Verify rejects. This is the "do not hardcode
// all-zero trust" gate from INSTALLER_PLAN.md.
using System;
using System.IO;
using System.Security.Cryptography;
using VibertemisManager.Core.Integrity;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class IntegrityVerifierTests : IDisposable
{
    private readonly string _tempRoot;
    private readonly string _installRoot;

    public IntegrityVerifierTests()
    {
        _tempRoot = Path.Combine(Path.GetTempPath(), "vibt-int-" + Guid.NewGuid().ToString("N"));
        _installRoot = Path.Combine(_tempRoot, "VibertemisVR");
        Directory.CreateDirectory(_installRoot);
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempRoot, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public void Verify_PassesForMatchingBytes()
    {
        var path = Path.Combine(_installRoot, "alvr", "dashboard", "ALVR Dashboard.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0xCA, 0xFE, 0xBA, 0xBE });
        var hex = Sha256OfFile(path);
        var size = new FileInfo(path).Length;
        var entries = new[] { new IntegrityEntry("alvr/dashboard/ALVR Dashboard.exe", hex, size) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.True(verifier.Verify(path, out var computed));
        Assert.Equal(hex, computed!.Hex);
    }

    [Fact]
    public void Verify_RejectsSizeMismatch()
    {
        var path = Path.Combine(_installRoot, "alvr", "dashboard", "ALVR Dashboard.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x01, 0x02, 0x03, 0x04 });
        var hex = Sha256OfFile(path);
        // Manifest lies about the size by 1 byte.
        var entries = new[] { new IntegrityEntry("alvr/dashboard/ALVR Dashboard.exe", hex, 3) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.False(verifier.Verify(path, out _));
    }

    [Fact]
    public void Verify_RejectsHashMismatch()
    {
        var path = Path.Combine(_installRoot, "alvr", "dashboard", "ALVR Dashboard.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x42 });
        var badHex = new string('0', 64);
        var entries = new[] { new IntegrityEntry("alvr/dashboard/ALVR Dashboard.exe", badHex, 1) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.False(verifier.Verify(path, out _));
    }

    [Fact]
    public void Verify_RejectsMissingEntry()
    {
        var path = Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x99 });
        var entries = Array.Empty<IntegrityEntry>();
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.False(verifier.Verify(path, out _));
    }

    [Fact]
    public void Verify_RejectsUnknownPathOutsideInstallRoot()
    {
        var path = Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x99 });
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", new string('a', 64), 1) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        var outside = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N") + ".exe");
        File.WriteAllBytes(outside, new byte[] { 0x99 });
        try { Assert.False(verifier.Verify(outside, out _)); }
        finally { try { File.Delete(outside); } catch { /* ignore */ } }
    }

    [Fact]
    public void Verify_DoesNotTrustAllZeroHash()
    {
        var path = Path.Combine(_installRoot, "manager", "bin", "vibertemis-host-companion.exe");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x12, 0x34 });
        var entries = new[] { new IntegrityEntry("manager/bin/vibertemis-host-companion.exe", new string('0', 64), 2) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.False(verifier.Verify(path, out _));
    }

    [Fact]
    public void Verify_MatchesAcrossSeparatorStyles_LinuxInstallRoot()
    {
        // On Linux the install root is a forward-slash path. The
        // verifier must canonicalise both sides via Path.GetFullPath
        // and compare with forward slashes regardless of how the
        // caller passes the absolute path (backslash vs forward
        // slash vs mixed). The old implementation converted only
        // the candidate's slashes and not the install root,
        // breaking Linux tests.
        var rel = "manager/bin/vibertemis-host-companion.exe";
        var path = Path.Combine(_installRoot, rel.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[] { 0x77, 0x88, 0x99 });
        var hex = Sha256OfFile(path);
        var size = new FileInfo(path).Length;
        var entries = new[] { new IntegrityEntry(rel, hex, size) };
        var verifier = new IntegrityVerifier(entries, _installRoot);
        Assert.True(verifier.TryGetHash(path, out var expected));
        Assert.NotNull(expected);
        Assert.True(verifier.Verify(path, out _));
    }

    [Fact]
    public void Verify_RejectsDuplicateManifestEntries()
    {
        var entries = new[]
        {
            new IntegrityEntry("foo.dll", new string('a', 64), 1),
            new IntegrityEntry("foo.dll", new string('b', 64), 1),
        };
        Assert.Throws<System.IO.InvalidDataException>(() => new IntegrityVerifier(entries, _installRoot));
    }

    [Fact]
    public void Verify_RejectsAbsoluteManifestPath()
    {
        var entries = new[] { new IntegrityEntry("/etc/passwd", new string('a', 64), 1) };
        Assert.Throws<System.IO.InvalidDataException>(() => new IntegrityVerifier(entries, _installRoot));
    }

    [Fact]
    public void Verify_RejectsNonPositiveManifestSize()
    {
        var entries = new[] { new IntegrityEntry("foo.dll", new string('a', 64), 0) };
        Assert.Throws<System.IO.InvalidDataException>(() => new IntegrityVerifier(entries, _installRoot));
    }

    [Fact]
    public void Verify_EmptyManifestIsConstructible_NoEntriesVerifyAsMissing()
    {
        // An empty manifest is constructible (the parser allows
        // []). The runtime refuses to launch any file because no
        // entry can ever resolve.
        var verifier = new IntegrityVerifier(Array.Empty<IntegrityEntry>(), _installRoot);
        var path = Path.Combine(_installRoot, "x.exe");
        Assert.False(verifier.TryGetHash(path, out _));
    }

    private static string Sha256OfFile(string path)
    {
        using var fs = File.OpenRead(path);
        using var sha = SHA256.Create();
        var hash = sha.ComputeHash(fs);
        var sb = new System.Text.StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}