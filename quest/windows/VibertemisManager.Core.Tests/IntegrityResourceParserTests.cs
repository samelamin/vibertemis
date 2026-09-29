// Integrity resource parser / builder tests.
//
// The parser / builder are the only way the build pipeline writes
// the embedded integrity resource; getting the bytes wrong makes
// the manager refuse to launch the companion. These tests cover:
//   - round-trip parse/build (forward-slash paths preserved)
//   - explicit JSON schema (array of {path,sha256,size})
//   - rejection of duplicates / absolute / traversal / empty paths
//   - rejection of malformed hex / nonpositive sizes / empty body
//   - normalise to lowercase hex on the way through
//   - real fixture sizes containing a 0x0a newline byte (e.g. 266)
//     to prevent regressions of the old line-based parser
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using VibertemisManager.Core.Integrity;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class IntegrityResourceParserTests
{
    [Fact]
    public void BuildThenParse_RoundTrips()
    {
        var entries = new List<IntegrityEntry>
        {
            new("alvr/dashboard/ALVR Dashboard.exe", new string('a', 64), 12345),
            new("manager/bin/vibertemis-host-companion.exe", new string('b', 64), 6789012),
            new("alvr/driver/bin/win64/driver_alvr_server.dll", new string('c', 64), 4096),
        };
        var bytes = IntegrityResourceParser.Build(entries);
        var parsed = IntegrityResourceParser.Parse(bytes);
        Assert.Equal(entries.Count, parsed.Count);
        for (var i = 0; i < entries.Count; i++)
        {
            Assert.Equal(entries[i].RelativePath, parsed[i].RelativePath);
            Assert.Equal(entries[i].Hex.ToLowerInvariant(), parsed[i].Hex);
            Assert.Equal(entries[i].Size, parsed[i].Size);
        }
    }

    [Fact]
    public void Build_PreservesHexCase_AsLowercaseOnParse()
    {
        var upper = new string('A', 32) + new string('F', 32);
        var entries = new List<IntegrityEntry>
        {
            new("foo", upper, 1),
        };
        var bytes = IntegrityResourceParser.Build(entries);
        var parsed = IntegrityResourceParser.Parse(bytes);
        Assert.Single(parsed);
        // The parser lower-cases to keep the digest comparison
        // uniform across the Windows + POSIX code paths.
        Assert.Equal(upper.ToLowerInvariant(), parsed[0].Hex);
    }

    [Fact]
    public void Parse_RejectsEmptyBody()
    {
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(Array.Empty<byte>()));
    }

    [Fact]
    public void Parse_RejectsNonJsonBody()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes("not json");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsNonArrayRoot()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes("{\"path\":\"foo\"}");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsEmptyArray()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes("[]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsAbsolutePath()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"/etc/passwd\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsDriveLetterPath()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"C:\\\\Windows\\\\System32\\\\cmd.exe\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsTraversalPath()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"../etc/passwd\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsEmptyPath()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsBadHashLength()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"deadbeef\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsNonHexHash()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"" + new string('z', 64) + "\",\"size\":1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsZeroSize()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":0}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsNegativeSize()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":-1}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsDuplicatePath()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[" +
            "{\"path\":\"foo\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}," +
            "{\"path\":\"foo\",\"sha256\":\"" + new string('b', 64) + "\",\"size\":2}" +
            "]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsDuplicatePath_BackslashVsForward()
    {
        // Windows tooling might emit "alvr\\driver\\foo.dll"; the
        // parser must reject duplicates regardless of separator.
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[" +
            "{\"path\":\"alvr/driver/foo.dll\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1}," +
            "{\"path\":\"alvr\\\\driver\\\\foo.dll\",\"sha256\":\"" + new string('b', 64) + "\",\"size\":2}" +
            "]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_HandlesRealFixtureSize_WithNewlineByte()
    {
        // The old bespoke binary parser broke on sizes whose
        // little-endian bytes contained 0x0a; size 266 is the
        // canonical "0x0a in byte 0" regression fixture. The new
        // JSON parser must round-trip any non-negative int64.
        var entries = new List<IntegrityEntry>
        {
            new("native/dll_with_newline_byte.dll", new string('d', 64), 266),
        };
        var bytes = IntegrityResourceParser.Build(entries);
        var parsed = IntegrityResourceParser.Parse(bytes);
        Assert.Single(parsed);
        Assert.Equal(266, parsed[0].Size);
    }

    [Fact]
    public void Parse_HandlesLargeSize()
    {
        var entries = new List<IntegrityEntry>
        {
            new("big.bin", new string('e', 64), long.MaxValue / 2),
        };
        var bytes = IntegrityResourceParser.Build(entries);
        var parsed = IntegrityResourceParser.Parse(bytes);
        Assert.Single(parsed);
        Assert.Equal(long.MaxValue / 2, parsed[0].Size);
    }

    [Fact]
    public void Parse_RejectsNonIntegerSize()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"" + new string('a', 64) + "\",\"size\":1.5}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Parse_RejectsMissingField()
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(
            "[{\"path\":\"foo\",\"sha256\":\"" + new string('a', 64) + "\"}]");
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }

    [Fact]
    public void Build_EmptyList_IsNotAnAcceptableResource()
    {
        var bytes = IntegrityResourceParser.Build(new List<IntegrityEntry>());
        Assert.Throws<InvalidDataException>(() => IntegrityResourceParser.Parse(bytes));
    }
}
