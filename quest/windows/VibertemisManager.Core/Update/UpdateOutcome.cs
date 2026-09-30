// Outcome of a worker-driven install. Written to the per-user state
// directory so the next manager launch can surface a clear status
// without false-positive success claims. The outcome is the only
// authority for whether the installed manager is the new build.
//
// The outcome is parsed only by the manager app itself; no consumer
// may treat success paths as implicit. Every outcome has the actual
// measured values, not optimistic expectations.
using System;
using System.IO;
using System.Text.Json;

namespace VibertemisManager.Core.Update;

public enum UpdateOutcomeKind
{
    Success,
    InstallerFailed,
    InstallerCanceled,
    VerificationFailed,
    JobInvalid,
    ParentTimeout,
    WorkerError,
}

public sealed record UpdateOutcome(
    int Schema,
    string ExpectedVersion,
    string PreviousVersion,
    UpdateOutcomeKind Kind,
    int? InstallerExitCode,
    string? InstalledFileVersion,
    int? VerifyInstallExitCode,
    string InstallerLogPath,
    string Detail,
    DateTime Timestamp)
{
    public const int CurrentSchema = 1;

    public bool IsSuccess => Kind == UpdateOutcomeKind.Success;

    public string Serialize()
    {
        var doc = new
        {
            schema = Schema,
            expectedVersion = ExpectedVersion,
            previousVersion = PreviousVersion,
            kind = Kind.ToString(),
            installerExitCode = InstallerExitCode,
            installedFileVersion = InstalledFileVersion,
            verifyInstallExitCode = VerifyInstallExitCode,
            installerLogPath = InstallerLogPath,
            detail = Detail,
            timestamp = Timestamp.ToUniversalTime().ToString("O"),
        };
        var bytes = JsonSerializer.SerializeToUtf8Bytes(doc, new JsonSerializerOptions { WriteIndented = true });
        if (bytes.Length > 64 * 1024)
            throw new InvalidDataException("Update outcome exceeds size limit");
        return System.Text.Encoding.UTF8.GetString(bytes);
    }

    public static UpdateOutcome? TryLoad(string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path) || !File.Exists(path)) return null;
            var info = new FileInfo(path);
            if (info.Length > 64 * 1024) return null;
            using var stream = File.OpenRead(path);
            using var doc = JsonDocument.Parse(stream, new JsonDocumentOptions { MaxDepth = 6 });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("schema", out var s) || s.GetInt32() != CurrentSchema) return null;
            string Get(string name) =>
                root.TryGetProperty(name, out var e) && e.ValueKind == JsonValueKind.String
                    ? e.GetString() ?? ""
                    : "";
            int? GetInt(string name) =>
                root.TryGetProperty(name, out var e) && e.ValueKind == JsonValueKind.Number && e.TryGetInt32(out var v)
                    ? v : null;
            if (!Enum.TryParse<UpdateOutcomeKind>(Get("kind"), out var kind))
                return null;
            var ts = DateTime.TryParse(Get("timestamp"), null, System.Globalization.DateTimeStyles.RoundtripKind, out var parsed)
                ? parsed.ToUniversalTime() : DateTime.UtcNow;
            return new UpdateOutcome(
                Schema: CurrentSchema,
                ExpectedVersion: Get("expectedVersion"),
                PreviousVersion: Get("previousVersion"),
                Kind: kind,
                InstallerExitCode: GetInt("installerExitCode"),
                InstalledFileVersion: Get("installedFileVersion"),
                VerifyInstallExitCode: GetInt("verifyInstallExitCode"),
                InstallerLogPath: Get("installerLogPath"),
                Detail: Get("detail"),
                Timestamp: ts);
        }
        catch
        {
            return null;
        }
    }
}