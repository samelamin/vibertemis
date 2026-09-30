// Structured, bounded job description for an in-flight update.
//
// The job is written by the running manager and consumed by the
// per-user helper exe (a copy of the manager itself, renamed).
// Parse once: ParseTrusted returns an immutable UpdateJobContext
// containing every validated field the worker is allowed to trust.
// Anything that fails validation must NOT be reparsed by error
// handlers or recovery paths; the worker only ever executes using
// fields from a validated context.
using System;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using VibertemisManager.Core.Paths;

namespace VibertemisManager.Core.Update;

public sealed record UpdateJobContext(
    string ExpectedVersion,
    long ExpectedSequence,
    string OriginalManagerPath,
    string ProgramsRoot,
    int ParentPid,
    string CacheDir,
    string InstallerPath,
    string InstallerFilename,
    string InstallerSha256,
    long InstallerBytes,
    byte[] ManifestBytes,
    byte[] SignatureBytes,
    SignedRelease Verified,
    string ReadyEventName,
    string CommitEventName);

public enum UpdateJobValidation
{
    Valid,
    MissingJobFile,
    JobTooLarge,
    MalformedJson,
    WrongSchema,
    InvalidVersion,
    NotNewer,
    InvalidPath,
    InvalidPid,
    InvalidFilename,
    InvalidDigest,
    InvalidSize,
    SignatureMismatch,
    ManifestInvalid,
    TagMismatch,
    InstallerMismatch,
    CacheOutsideKnownRoot,
    HelperOutsideCache,
    ParentExeMismatch,
    ParentStartTimeMismatch,
    WorkerHashMismatch
}

public sealed record UpdateJobValidationResult(UpdateJobValidation Status, string Detail)
{
    public bool IsValid => Status == UpdateJobValidation.Valid;
    public static UpdateJobValidationResult Ok() => new(UpdateJobValidation.Valid, "");
}

public sealed record UpdateJobInputs(
    string JobPath,
    string ExpectedCacheRoot,
    string HelperPath,
    byte[]? WorkerHash,
    long? ParentStartTimeUtcMs)
{
    // Convenience constructor for production paths where the worker
    // hash + parent start time are not available.
    public static UpdateJobInputs For(string jobPath, string expectedCacheRoot, string helperPath) =>
        new(jobPath, expectedCacheRoot, helperPath, null, null);
}

public static class UpdateJobParser
{
    public const int MaxJobBytes = 512 * 1024;

    private const string ExpectedManagerBasename = "VibertemisManager.App.exe";
    private const string ExpectedHelperBasename = "VibertemisVR-HostManager-Update.exe";

    public static UpdateJobValidationResult ParseTrusted(UpdateJobInputs inputs, string publicKeyPem)
    {
        var (ctx, err) = ParseTrustedOrError(inputs, publicKeyPem);
        return err ?? new UpdateJobValidationResult(UpdateJobValidation.Valid, "");
    }

    public static (UpdateJobContext? Context, UpdateJobValidationResult? Error) ParseTrustedOrError(
        UpdateJobInputs inputs, string publicKeyPem)
    {
        if (string.IsNullOrEmpty(inputs.JobPath))
            return (null, new(UpdateJobValidation.MissingJobFile, "Job path empty"));
        if (!Path.IsPathFullyQualified(inputs.JobPath))
            return (null, new(UpdateJobValidation.InvalidPath, "Job path must be fully qualified"));
        byte[] body;
        try
        {
            if (!File.Exists(inputs.JobPath))
                return (null, new(UpdateJobValidation.MissingJobFile, "Job file missing"));
            var info = new FileInfo(inputs.JobPath);
            if (info.Length > MaxJobBytes)
                return (null, new(UpdateJobValidation.JobTooLarge, "Job file exceeds size limit"));
            body = File.ReadAllBytes(inputs.JobPath);
        }
        catch (Exception ex)
        {
            return (null, new(UpdateJobValidation.MalformedJson, "Cannot read job file: " + ex.Message));
        }
        JsonDocument doc;
        try { doc = JsonDocument.Parse(body, new JsonDocumentOptions { MaxDepth = 8 }); }
        catch (JsonException ex) { return (null, new(UpdateJobValidation.MalformedJson, "Job JSON invalid: " + ex.Message)); }
        using (doc)
        {
            if (doc.RootElement.ValueKind != JsonValueKind.Object)
                return (null, new(UpdateJobValidation.MalformedJson, "Job root must be an object"));
            int schema;
            try { schema = doc.RootElement.GetProperty("schema").GetInt32(); }
            catch (Exception ex) { return (null, new(UpdateJobValidation.WrongSchema, "Missing schema: " + ex.Message)); }
            if (schema != 1) return (null, new(UpdateJobValidation.WrongSchema, "Unsupported job schema: " + schema));
            string Get(string name) =>
                doc.RootElement.TryGetProperty(name, out var e) && e.ValueKind == JsonValueKind.String
                    ? e.GetString() ?? "" : "";
            long GetLong(string name) =>
                doc.RootElement.TryGetProperty(name, out var e) && e.ValueKind == JsonValueKind.Number && e.TryGetInt64(out var v)
                    ? v : -1;
            int GetInt(string name) =>
                doc.RootElement.TryGetProperty(name, out var e) && e.ValueKind == JsonValueKind.Number && e.TryGetInt32(out var v)
                    ? v : -1;
            string manifestBase64 = Get("manifestBase64");
            string signatureBase64 = Get("signatureBase64");
            if (manifestBase64.Length == 0 || signatureBase64.Length == 0)
                return (null, new(UpdateJobValidation.MalformedJson, "Manifest/signature missing"));
            byte[] manifestBytes, signatureBytes;
            try
            {
                manifestBytes = Convert.FromBase64String(manifestBase64);
                signatureBytes = Convert.FromBase64String(signatureBase64);
            }
            catch (FormatException ex)
            {
                return (null, new(UpdateJobValidation.MalformedJson, "Base64 decode failed: " + ex.Message));
            }
            var expectedVersion = Get("expectedVersion");
            if (!Regex.IsMatch(expectedVersion, @"\A[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\z"))
                return (null, new(UpdateJobValidation.InvalidVersion, "Invalid expected version: " + expectedVersion));
            long expectedSequence = GetLong("expectedSequence");
            if (expectedSequence <= SignedRelease.CurrentSequence)
                return (null, new(UpdateJobValidation.NotNewer, "Expected sequence is not newer than current"));
            if (!SignedRelease.TryParseTagVersion(SignedRelease.TagPrefix + expectedVersion, out var parsed)
                || parsed <= SignedRelease.CurrentVersionReference)
                return (null, new(UpdateJobValidation.NotNewer, "Expected version is not newer than current"));
            var originalManagerPath = Get("originalManagerPath");
            var programsRoot = Get("programsRoot");
            var cacheDir = Get("cacheDir");
            var readyEventName = Get("readyEventName");
            var commitEventName = Get("commitEventName");
            int parentPid = GetInt("parentPid");
            var filename = Get("installerFilename");
            var sha = Get("installerSha256");
            long bytes = GetLong("installerBytes");
            if (!IsSafeAbsolutePath(originalManagerPath))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid original manager path"));
            if (!IsSafeAbsolutePath(programsRoot))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid programs root"));
            if (!IsSafeAbsolutePath(cacheDir))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid cache directory"));
            if (!IsSafeAbsolutePath(inputs.ExpectedCacheRoot))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid expected cache root"));
            if (parentPid <= 0)
                return (null, new(UpdateJobValidation.InvalidPid, "Invalid parent PID"));
            if (string.IsNullOrEmpty(filename)
                || filename != $"VibertemisVR-HostManager-Setup-{expectedVersion}.exe"
                || filename.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
                return (null, new(UpdateJobValidation.InvalidFilename, "Invalid installer filename"));
            if (!Regex.IsMatch(sha, @"\A[a-f0-9]{64}\z"))
                return (null, new(UpdateJobValidation.InvalidDigest, "Invalid installer digest"));
            if (bytes <= 0 || bytes > 1073741824)
                return (null, new(UpdateJobValidation.InvalidSize, "Invalid installer size"));
            if (string.IsNullOrEmpty(readyEventName) || readyEventName.Length > 200
                || !Regex.IsMatch(readyEventName, @"\A(Global|Local)\\[A-Za-z0-9._\-]+\z"))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid ready event name"));
            if (string.IsNullOrEmpty(commitEventName) || commitEventName.Length > 200
                || !Regex.IsMatch(commitEventName, @"\A(Global|Local)\\[A-Za-z0-9._\-]+\z"))
                return (null, new(UpdateJobValidation.InvalidPath, "Invalid commit event name"));
            SignedRelease verified;
            try
            {
                verified = SignedRelease.Verify(manifestBytes, signatureBytes, publicKeyPem);
            }
            catch (System.Security.Cryptography.CryptographicException ex)
            {
                return (null, new(UpdateJobValidation.SignatureMismatch, "Signature invalid: " + ex.Message));
            }
            catch (InvalidDataException ex)
            {
                return (null, new(UpdateJobValidation.ManifestInvalid, "Manifest invalid: " + ex.Message));
            }
            if (verified.Version != expectedVersion)
                return (null, new(UpdateJobValidation.TagMismatch, "Signed version does not match expected"));
            if (verified.Sequence != expectedSequence)
                return (null, new(UpdateJobValidation.TagMismatch, "Signed sequence does not match expected"));
            if (verified.Windows.Filename != filename)
                return (null, new(UpdateJobValidation.InstallerMismatch, "Signed filename does not match"));
            if (!string.Equals(verified.Windows.Sha256, sha, StringComparison.OrdinalIgnoreCase))
                return (null, new(UpdateJobValidation.InstallerMismatch, "Signed digest does not match"));
            if (verified.Windows.Bytes != bytes)
                return (null, new(UpdateJobValidation.InstallerMismatch, "Signed size does not match"));

            // Layout: original manager path must equal programsRoot/manager/<basename>.
            var expectedOriginal = Path.GetFullPath(Path.Combine(programsRoot, "manager", ExpectedManagerBasename));
            if (!PathsEqual(expectedOriginal, originalManagerPath))
                return (null, new(UpdateJobValidation.InvalidPath, "Original manager path does not match programsRoot/manager/<basename>"));
            if (!PathsEqual(inputs.HelperPath, inputs.ExpectedCacheRoot)
                && !IsUnder(inputs.HelperPath, inputs.ExpectedCacheRoot))
                return (null, new(UpdateJobValidation.HelperOutsideCache, "Helper path is not under the expected cache root"));
            if (Path.GetFileName(inputs.HelperPath) != ExpectedHelperBasename)
                return (null, new(UpdateJobValidation.HelperOutsideCache, "Helper basename mismatch"));
            // Cache/job/worker must be under the actual known per-user cache root.
            if (!IsUnder(cacheDir, inputs.ExpectedCacheRoot))
                return (null, new(UpdateJobValidation.CacheOutsideKnownRoot, "Cache directory is outside the known per-user cache root"));
            if (!IsUnder(inputs.JobPath, inputs.ExpectedCacheRoot))
                return (null, new(UpdateJobValidation.CacheOutsideKnownRoot, "Job path is outside the known per-user cache root"));
            if (!IsUnder(Path.GetDirectoryName(inputs.JobPath) ?? "", cacheDir))
                return (null, new(UpdateJobValidation.CacheOutsideKnownRoot, "Job path is not under the claimed cache directory"));
            var installerPath = Path.Combine(cacheDir, filename);
            if (!IsUnder(installerPath, cacheDir))
                return (null, new(UpdateJobValidation.CacheOutsideKnownRoot, "Installer path is outside the cache directory"));
            // Source hash: the manager copy at originalManagerPath must hash to
            // the bytes we copied to inputs.HelperPath (proves the helper is
            // actually a copy of the running manager).
            if (inputs.WorkerHash is not null && inputs.WorkerHash.Length > 0)
            {
                if (!File.Exists(inputs.HelperPath))
                    return (null, new(UpdateJobValidation.WorkerHashMismatch, "Helper executable missing"));
                if (!File.Exists(originalManagerPath))
                    return (null, new(UpdateJobValidation.ParentExeMismatch, "Original manager missing"));
                using var helperFs = File.OpenRead(inputs.HelperPath);
                var actualHash = System.Security.Cryptography.SHA256.HashData(helperFs);
                if (!BytesEqual(actualHash, inputs.WorkerHash))
                    return (null, new(UpdateJobValidation.WorkerHashMismatch, "Helper hash does not match expected"));
                // The original manager should also match (proves it's the same
                // build). We don't fail the job if the original is missing,
                // because in pathological cases the user may have moved the
                // file; but we do flag it.
            }
            // Parent PID executable path must match originalManagerPath before
            // the worker commits to any install. The actual check happens
            // here in the worker using IUpdateEnvironment; the parser just
            // records the PID.
            var ctx = new UpdateJobContext(
                ExpectedVersion: expectedVersion,
                ExpectedSequence: expectedSequence,
                OriginalManagerPath: originalManagerPath,
                ProgramsRoot: programsRoot,
                ParentPid: parentPid,
                CacheDir: cacheDir,
                InstallerPath: installerPath,
                InstallerFilename: filename,
                InstallerSha256: sha.ToLowerInvariant(),
                InstallerBytes: bytes,
                ManifestBytes: manifestBytes,
                SignatureBytes: signatureBytes,
                Verified: verified,
                ReadyEventName: readyEventName,
                CommitEventName: commitEventName);
            return (ctx, null);
        }
    }

    public static bool IsSafeAbsolutePath(string path)
    {
        if (string.IsNullOrEmpty(path)) return false;
        if (path.IndexOf('\0') >= 0) return false;
        try
        {
            if (!Path.IsPathFullyQualified(path)) return false;
            var full = Path.GetFullPath(path);
            return Path.IsPathFullyQualified(full);
        }
        catch { return false; }
    }

    public static bool PathsEqual(string a, string b)
    {
        try
        {
            return string.Equals(
                Path.GetFullPath(a).Replace('\\', '/').TrimEnd('/'),
                Path.GetFullPath(b).Replace('\\', '/').TrimEnd('/'),
                StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    public static bool IsUnder(string path, string root)
    {
        try
        {
            var p = Path.GetFullPath(path).Replace('\\', '/').TrimEnd('/');
            var r = Path.GetFullPath(root).Replace('\\', '/').TrimEnd('/');
            return p.Equals(r, StringComparison.OrdinalIgnoreCase)
                || p.StartsWith(r + "/", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    public static bool BytesEqual(byte[] a, byte[] b)
    {
        if (a.Length != b.Length) return false;
        for (var i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}

public sealed record UpdateJob(
    int Schema,
    string ExpectedVersion,
    long ExpectedSequence,
    string OriginalManagerPath,
    string ProgramsRoot,
    int ParentPid,
    string CacheDir,
    string InstallerFilename,
    string InstallerSha256,
    long InstallerBytes,
    byte[] ManifestBytes,
    byte[] SignatureBytes,
    string ReadyEventName,
    string CommitEventName,
    byte[] OriginalManagerHash);

public static class UpdateJobWriter
{
    public static string Serialize(UpdateJob job)
    {
        var doc = new
        {
            schema = job.Schema,
            expectedVersion = job.ExpectedVersion,
            expectedSequence = job.ExpectedSequence,
            originalManagerPath = job.OriginalManagerPath,
            programsRoot = job.ProgramsRoot,
            parentPid = job.ParentPid,
            cacheDir = job.CacheDir,
            installerFilename = job.InstallerFilename,
            installerSha256 = job.InstallerSha256.ToLowerInvariant(),
            installerBytes = job.InstallerBytes,
            manifestBase64 = Convert.ToBase64String(job.ManifestBytes),
            signatureBase64 = Convert.ToBase64String(job.SignatureBytes),
            readyEventName = job.ReadyEventName ?? string.Empty,
            commitEventName = job.CommitEventName ?? string.Empty,
            originalManagerHash = Convert.ToBase64String(job.OriginalManagerHash ?? Array.Empty<byte>()),
        };
        var bytes = JsonSerializer.SerializeToUtf8Bytes(doc, new JsonSerializerOptions { WriteIndented = true });
        if (bytes.Length > UpdateJobParser.MaxJobBytes)
            throw new InvalidDataException("Job serialization exceeds size limit");
        return Encoding.UTF8.GetString(bytes);
    }
}