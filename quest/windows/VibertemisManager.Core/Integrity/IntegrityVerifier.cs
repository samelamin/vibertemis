// Integrity model + verifier.
//
// The build pipeline (build-installer.ps1) computes SHA-256
// fingerprints for the companion and native runtime DLLs and
// writes them into an embedded resource in the manager assembly
// before `dotnet publish`. The Core verifier loads that resource
// and refuses to launch the companion if the on-disk bytes
// disagree.
//
// Resource format: a single bounded UTF-8 JSON document:
//
//   [
//     {"path":"runtime/ALVR Dashboard.exe",
//      "sha256":"<64-char lowercase hex>",
//      "size": <positive int64>},
//     ...
//   ]
//
// The document body is bounded to <= 64 KiB. We deliberately do
// NOT use a bespoke binary protocol here (no nul-terminated
// lines, no little-endian uint64 size, no UTF-8 newlines mixed
// with binary sizes). A line-based format breaks when a
// size field or path contains 0x0a; a binary protocol forces
// every consumer (PowerShell builder, .NET verifier, future Go
// verifier for cross-check) to maintain a second parser. Bounded
// JSON keeps both ends simple and cross-platform.
//
// Embedded resource is built by IntegrityResourceBuilder; tests
// inject a deterministic fixture via the IReadOnlyList<IntegrityEntry>
// constructor.
using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace VibertemisManager.Core.Integrity;

public sealed record IntegrityEntry(string RelativePath, string Hex, long Size)
{
    public override string ToString() => $"{RelativePath} {Hex} size={Size}";
}

public interface IIntegritySource
{
    bool TryGetEntries(out IReadOnlyList<IntegrityEntry> entries);
    string ResourceName { get; }
}

public interface IIntegrityVerifier
{
    bool TryGetHash(string absolutePath, out IntegrityEntry? expected);
    bool Verify(string absolutePath, out IntegrityEntry? computed);
}

public sealed class IntegrityVerifier : IIntegrityVerifier
{
    private const int MaxResourceBytes = 64 * 1024;
    private readonly IReadOnlyDictionary<string, IntegrityEntry> _byRelative;
    private readonly string _installRoot;

    public IntegrityVerifier(IReadOnlyList<IntegrityEntry> entries, string installRoot)
    {
        if (entries is null) throw new ArgumentNullException(nameof(entries));
        var dict = new Dictionary<string, IntegrityEntry>(StringComparer.OrdinalIgnoreCase);
        foreach (var e in entries)
        {
            if (e is null) throw new InvalidDataException("Integrity entry must not be null.");
            IntegrityResourceParser.ValidateRelativePath(e.RelativePath);
            IntegrityResourceParser.ValidateHex(e.Hex);
            IntegrityResourceParser.ValidateSize(e.Size);
            var key = NormalizeKey(e.RelativePath);
            if (dict.ContainsKey(key))
                throw new InvalidDataException($"Integrity manifest has duplicate relative path: {e.RelativePath}");
            dict[key] = e;
        }
        _byRelative = dict;
        _installRoot = NormalizeRoot(installRoot);
    }

    public static IntegrityVerifier FromEmbedded(Assembly assembly, string resourceName, string installRoot)
    {
        using var s = assembly.GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException($"Embedded integrity resource {resourceName} not found.");
        if (s.Length > MaxResourceBytes)
            throw new InvalidDataException($"Embedded integrity resource {resourceName} is {s.Length} bytes, max {MaxResourceBytes}.");
        var bytes = ReadAll(s);
        var entries = IntegrityResourceParser.Parse(bytes);
        return new IntegrityVerifier(entries, installRoot);
    }

    public bool TryGetHash(string absolutePath, out IntegrityEntry? expected)
    {
        expected = null;
        var rel = ToRelative(absolutePath);
        if (rel is null) return false;
        return _byRelative.TryGetValue(rel, out expected);
    }

    public bool Verify(string absolutePath, out IntegrityEntry? computed)
    {
        computed = null;
        if (!TryGetHash(absolutePath, out var expected) || expected is null) return false;
        if (!File.Exists(absolutePath)) return false;
        var info = new FileInfo(absolutePath);
        if (info.Length != expected.Size) return false;
        string hex;
        using (var fs = File.OpenRead(absolutePath))
        using (var sha = SHA256.Create())
        {
            var hash = sha.ComputeHash(fs);
            hex = ToHex(hash);
        }
        computed = new IntegrityEntry(expected.RelativePath, hex, info.Length);
        return string.Equals(hex, expected.Hex, StringComparison.OrdinalIgnoreCase);
    }

    private string? ToRelative(string absolutePath)
    {
        if (string.IsNullOrEmpty(absolutePath)) return null;
        string norm;
        try { norm = Path.GetFullPath(absolutePath); }
        catch { return null; }
        // Normalize to forward slashes for stable comparison across
        // Windows backslash paths and POSIX forward-slash paths.
        norm = norm.Replace('\\', '/');
        var root = _installRoot;
        if (norm.Equals(root, StringComparison.OrdinalIgnoreCase)) return "";
        var prefix = root + "/";
        if (!norm.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return null;
        return norm.Substring(prefix.Length);
    }

    internal static string NormalizeKey(string rel) =>
        rel.Replace('\\', '/').TrimStart('/');

    internal static string NormalizeRoot(string installRoot)
    {
        var full = Path.GetFullPath(installRoot).Replace('\\', '/').TrimEnd('/');
        return full;
    }

    private static byte[] ReadAll(Stream s)
    {
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        return ms.ToArray();
    }

    private static string ToHex(byte[] bytes)
    {
        var sb = new StringBuilder(bytes.Length * 2);
        foreach (var b in bytes) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}

public static class IntegrityResourceParser
{
    public const int MaxBodyBytes = 64 * 1024;
    public const int MaxEntries = 256;
    public const int MaxPathBytes = 1024;

    public static IReadOnlyList<IntegrityEntry> Parse(byte[] payload)
    {
        if (payload is null) throw new ArgumentNullException(nameof(payload));
        if (payload.Length == 0)
            throw new InvalidDataException("Integrity resource is empty.");
        if (payload.Length > MaxBodyBytes)
            throw new InvalidDataException($"Integrity resource {payload.Length} > {MaxBodyBytes}.");
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(payload);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"Integrity resource is not valid JSON: {ex.Message}", ex);
        }
        using (doc)
        {
            if (doc.RootElement.ValueKind != JsonValueKind.Array)
                throw new InvalidDataException("Integrity resource must be a JSON array.");
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var list = new List<IntegrityEntry>();
            var idx = 0;
            foreach (var el in doc.RootElement.EnumerateArray())
            {
                if (list.Count >= MaxEntries)
                    throw new InvalidDataException($"Integrity resource has more than {MaxEntries} entries.");
                if (el.ValueKind != JsonValueKind.Object)
                    throw new InvalidDataException($"Integrity entry {idx} must be an object.");
                if (!el.TryGetProperty("path", out var pathEl) || pathEl.ValueKind != JsonValueKind.String)
                    throw new InvalidDataException($"Integrity entry {idx} missing string 'path'.");
                if (!el.TryGetProperty("sha256", out var hashEl) || hashEl.ValueKind != JsonValueKind.String)
                    throw new InvalidDataException($"Integrity entry {idx} missing string 'sha256'.");
                if (!el.TryGetProperty("size", out var sizeEl) || sizeEl.ValueKind != JsonValueKind.Number)
                    throw new InvalidDataException($"Integrity entry {idx} missing number 'size'.");
                if (!sizeEl.TryGetInt64(out long size))
                    throw new InvalidDataException($"Integrity entry {idx} 'size' is not a valid int64.");
                var path = pathEl.GetString()!;
                var hex = hashEl.GetString()!;
                ValidateRelativePath(path);
                ValidateHex(hex);
                ValidateSize(size);
                var key = IntegrityVerifier.NormalizeKey(path);
                if (!seen.Add(key))
                    throw new InvalidDataException($"Integrity entry {idx} duplicates '{path}'.");
                list.Add(new IntegrityEntry(path, hex.ToLowerInvariant(), size));
                idx++;
            }
            if (list.Count == 0) throw new InvalidDataException("Integrity manifest is empty.");
            return list;
        }
    }

    public static byte[] Build(IReadOnlyList<IntegrityEntry> entries)
    {
        if (entries is null) throw new ArgumentNullException(nameof(entries));
        using var ms = new MemoryStream();
        using (var writer = new Utf8JsonWriter(ms, new JsonWriterOptions { Indented = true }))
        {
            writer.WriteStartArray();
            foreach (var e in entries)
            {
                if (e is null) throw new InvalidDataException("Integrity entry must not be null.");
                ValidateRelativePath(e.RelativePath);
                ValidateHex(e.Hex);
                ValidateSize(e.Size);
                writer.WriteStartObject();
                writer.WriteString("path", e.RelativePath);
                writer.WriteString("sha256", e.Hex.ToLowerInvariant());
                writer.WriteNumber("size", e.Size);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
        }
        var bytes = ms.ToArray();
        if (bytes.Length > MaxBodyBytes)
            throw new InvalidDataException($"Built integrity resource {bytes.Length} > {MaxBodyBytes}.");
        return bytes;
    }

    public static void ValidateRelativePath(string path)
    {
        if (string.IsNullOrEmpty(path))
            throw new InvalidDataException("Integrity path must be non-empty.");
        if (path.Length > MaxPathBytes)
            throw new InvalidDataException($"Integrity path '{Truncate(path, 64)}...' exceeds {MaxPathBytes} bytes.");
        if (path.IndexOf('\0') >= 0)
            throw new InvalidDataException("Integrity path must not contain NUL.");
        // Disallow absolute paths (POSIX leading slash, Windows drive letter,
        // or UNC prefix).
        if (path.StartsWith("/") || path.StartsWith("\\"))
            throw new InvalidDataException($"Integrity path '{path}' must not be absolute.");
        if (path.Length >= 2 && path[1] == ':')
            throw new InvalidDataException($"Integrity path '{path}' must not include a drive letter.");
        // Normalise for traversal check.
        var parts = path.Replace('\\', '/').Split('/');
        foreach (var part in parts)
        {
            if (part.Length == 0) continue; // allow leading ./ segments trimmed later
            if (part == "." || part == "..")
                throw new InvalidDataException($"Integrity path '{path}' contains '.' or '..' segment.");
        }
        // The relative path must point at a file (not a directory): no
        // trailing slash.
        if (path.EndsWith("/") || path.EndsWith("\\"))
            throw new InvalidDataException($"Integrity path '{path}' must not end with a separator.");
    }

    public static void ValidateHex(string hex)
    {
        if (string.IsNullOrEmpty(hex))
            throw new InvalidDataException("Integrity sha256 must be non-empty.");
        if (hex.Length != 64)
            throw new InvalidDataException($"Integrity sha256 length {hex.Length}, expected 64.");
        for (var i = 0; i < hex.Length; i++)
        {
            var c = hex[i];
            var ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok)
                throw new InvalidDataException($"Integrity sha256 contains non-hex character at index {i}.");
        }
    }

    public static void ValidateSize(long size)
    {
        if (size <= 0)
            throw new InvalidDataException($"Integrity size {size} must be positive.");
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s.Substring(0, max);
}
