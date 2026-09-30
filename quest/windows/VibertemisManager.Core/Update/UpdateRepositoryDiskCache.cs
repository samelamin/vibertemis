using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace VibertemisManager.Core.Update;

/// <summary>
/// Disk-backed <see cref="UpdateRepository.ICache"/>. Files live under
/// the per-user state directory's updates subdirectory:
///
/// <code>
///   updates/
///     available/
///       manifest.json       latest verified metadata body
///       manifest.sig        384-byte detached signature
///     downloaded/
///       &lt;version&gt;/         per-release verified download
///         manifest.json
///         manifest.sig
///         update.exe
/// </code>
/// </summary>
public sealed class UpdateRepositoryDiskCache : UpdateRepository.ICache
{
    private static readonly Regex VersionDir = new(@"^[0-9]{1,5}(\.[0-9]{1,5}){3}$", RegexOptions.Compiled);
    private readonly DirectoryInfo updatesRoot;
    private readonly DirectoryInfo availableRoot;
    private readonly DirectoryInfo downloadedRoot;

    public UpdateRepositoryDiskCache(DirectoryInfo baseStateDir)
    {
        if (baseStateDir == null) throw new ArgumentNullException(nameof(baseStateDir));
        updatesRoot = new DirectoryInfo(Path.Combine(baseStateDir.FullName, "updates"));
        availableRoot = new DirectoryInfo(Path.Combine(updatesRoot.FullName, "available"));
        downloadedRoot = new DirectoryInfo(Path.Combine(updatesRoot.FullName, "downloaded"));
        EnsureDir(updatesRoot);
        EnsureDir(availableRoot);
        EnsureDir(downloadedRoot);
    }

    private FileInfo AvailableManifestFile => new(Path.Combine(availableRoot.FullName, "manifest.json"));
    private FileInfo AvailableSignatureFile => new(Path.Combine(availableRoot.FullName, "manifest.sig"));

    public byte[]? ReadAvailableManifestBytes() => ReadCapped(AvailableManifestFile, UpdateRepository.MaxManifestBytes);
    public byte[]? ReadAvailableSignatureBytes() => ReadCapped(AvailableSignatureFile, UpdateRepository.MaxSignatureBytes);

    public void PersistAvailable(byte[] manifestBytes, byte[] signatureBytes)
    {
        if (manifestBytes == null) throw new ArgumentNullException(nameof(manifestBytes));
        if (signatureBytes == null) throw new ArgumentNullException(nameof(signatureBytes));
        WriteAtomically(AvailableManifestFile, manifestBytes, UpdateRepository.MaxManifestBytes);
        WriteAtomically(AvailableSignatureFile, signatureBytes, UpdateRepository.MaxSignatureBytes);
    }

    public string DownloadedRoot => downloadedRoot.FullName;

    public string DownloadedManifestPath(string version)
        => Path.Combine(VersionDirFor(version).FullName, "manifest.json");
    public string DownloadedSignaturePath(string version)
        => Path.Combine(VersionDirFor(version).FullName, "manifest.sig");
    public string DownloadedApkPath(string version)
        => Path.Combine(VersionDirFor(version).FullName, "update.exe");

    public byte[]? ReadDownloadedManifestBytes(string version)
        => ReadCapped(new FileInfo(DownloadedManifestPath(version)), UpdateRepository.MaxManifestBytes);
    public byte[]? ReadDownloadedSignatureBytes(string version)
        => ReadCapped(new FileInfo(DownloadedSignaturePath(version)), UpdateRepository.MaxSignatureBytes);

    public string PersistDownloadedInstaller(string version, string sourceFilePath)
    {
        if (string.IsNullOrEmpty(version)) throw new ArgumentNullException(nameof(version));
        if (string.IsNullOrEmpty(sourceFilePath)) throw new ArgumentNullException(nameof(sourceFilePath));
        if (!File.Exists(sourceFilePath))
            throw new IOException("source file missing: " + sourceFilePath);
        var dir = VersionDirFor(version);
        EnsureDir(dir);
        var apk = new FileInfo(DownloadedApkPath(version));
        var tmp = new FileInfo(Path.Combine(dir.FullName, "update.exe.tmp"));

        string actualSha;
        using (var sha = SHA256.Create())
        {
            using (var inFs = new FileStream(sourceFilePath, FileMode.Open, FileAccess.Read, FileShare.Read, UpdateRepository.CopyBufferBytes, FileOptions.SequentialScan))
            using (var outFs = new FileStream(tmp.FullName, FileMode.Create, FileAccess.Write, FileShare.None, UpdateRepository.CopyBufferBytes, FileOptions.WriteThrough))
            {
                var buf = new byte[UpdateRepository.CopyBufferBytes];
                int n;
                while ((n = inFs.Read(buf, 0, buf.Length)) != 0)
                {
                    sha.TransformBlock(buf, 0, n, null, 0);
                    outFs.Write(buf, 0, n);
                }
                sha.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
                outFs.Flush(true);
            }
            actualSha = Convert.ToHexString(sha.Hash!).ToLowerInvariant();
        }

        try
        {
            File.Move(tmp.FullName, apk.FullName, overwrite: true);
        }
        catch (IOException)
        {
            File.Copy(tmp.FullName, apk.FullName, overwrite: true);
            try { tmp.Delete(); } catch { /* tolerated */ }
        }

        return actualSha;
    }

    public void PersistDownloadedMetadata(string version, byte[] manifestBytes, byte[] signatureBytes)
    {
        if (string.IsNullOrEmpty(version)) throw new ArgumentNullException(nameof(version));
        if (manifestBytes == null) throw new ArgumentNullException(nameof(manifestBytes));
        if (signatureBytes == null) throw new ArgumentNullException(nameof(signatureBytes));
        var dir = VersionDirFor(version);
        EnsureDir(dir);
        WriteAtomically(new FileInfo(DownloadedManifestPath(version)), manifestBytes, UpdateRepository.MaxManifestBytes);
        WriteAtomically(new FileInfo(DownloadedSignaturePath(version)), signatureBytes, UpdateRepository.MaxSignatureBytes);
    }

    public void DeleteDownloaded(string version)
    {
        if (string.IsNullOrEmpty(version)) return;
        if (!VersionDir.IsMatch(version)) return;
        var dir = new DirectoryInfo(Path.Combine(downloadedRoot.FullName, version));
        if (!dir.Exists) return;
        foreach (var f in dir.EnumerateFiles())
        {
            try { f.Delete(); }
            catch (IOException) { f.Refresh(); if (f.Exists) throw; }
        }
        dir.Refresh();
        if (dir.Exists) dir.Delete(recursive: false);
    }

    public IReadOnlyList<string> EnumerateDownloadedVersions()
    {
        if (!downloadedRoot.Exists) return Array.Empty<string>();
        var found = new List<string>();
        foreach (var dir in downloadedRoot.EnumerateDirectories())
        {
            if (!VersionDir.IsMatch(dir.Name)) continue;
            if (!File.Exists(DownloadedManifestPath(dir.Name))) continue;
            if (!File.Exists(DownloadedSignaturePath(dir.Name))) continue;
            if (!File.Exists(DownloadedApkPath(dir.Name))) continue;
            found.Add(dir.Name);
        }
        return found;
    }

    private DirectoryInfo VersionDirFor(string version)
    {
        if (version == null || !VersionDir.IsMatch(version))
            throw new ArgumentException("Invalid version directory name: " + version, nameof(version));
        return new DirectoryInfo(Path.Combine(downloadedRoot.FullName, version));
    }

    private static void EnsureDir(DirectoryInfo dir)
    {
        if (!dir.Exists) dir.Create();
    }

    private static byte[]? ReadCapped(FileInfo file, int max)
    {
        if (!file.Exists) return null;
        if (file.Length > max) return null;
        using var fs = file.OpenRead();
        using var ms = new MemoryStream((int)file.Length);
        var buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = fs.Read(buf, 0, buf.Length)) != 0)
        {
            total += n;
            if (total > max) return null;
            ms.Write(buf, 0, n);
        }
        return ms.ToArray();
    }

    private static void WriteAtomically(FileInfo target, byte[] bytes, int max)
    {
        if (bytes.Length > max) throw new IOException("File exceeds cache cap");
        var tmp = new FileInfo(target.FullName + ".tmp");
        using (var fs = tmp.OpenWrite())
        {
            fs.Write(bytes, 0, bytes.Length);
            fs.Flush(true);
        }
        try
        {
            File.Move(tmp.FullName, target.FullName, overwrite: true);
        }
        catch (IOException)
        {
            File.Copy(tmp.FullName, target.FullName, overwrite: true);
            try { tmp.Delete(); } catch { /* tolerated */ }
        }
    }
}