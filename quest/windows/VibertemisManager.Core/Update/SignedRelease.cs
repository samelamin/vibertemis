using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace VibertemisManager.Core.Update;

public sealed record ReleaseAsset(string Filename, Uri Url, long Bytes, string Sha256);
public sealed record SignedRelease(long Sequence, string Version, string NativeProtocol, ReleaseAsset Windows)
{
    public const long CurrentSequence = 15;
    public const string CurrentVersion = "0.1.0.15";
    public const string Channel = "quest-preview";
    public const string Protocol = "20.14.1-vibertemis-pyro.1";
    public const string Repository = "https://github.com/samelamin/vibertemis/releases/download/";
    public const string TagPrefix = "quest-preview-v";

    private static readonly Version CurrentVersionValue = new(CurrentVersion);

    public static Version CurrentVersionReference => CurrentVersionValue;

    public static string EmbeddedPublicKey()
    {
        using var stream = typeof(SignedRelease).Assembly.GetManifestResourceStream("Vibertemis.Update.PublicKey")
            ?? throw new InvalidDataException("Update trust key missing");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }

    // Parses "0.1.0.9" out of "quest-preview-v0.1.0.9". Returns false for
    // anything that is not a numeric four-component version. Strict enough
    // to reject non-tag inputs and forgiving enough to ignore trailing junk.
    public static bool TryParseTagVersion(string tag, out Version version)
    {
        version = new Version(0, 0);
        if (string.IsNullOrEmpty(tag)) return false;
        if (!tag.StartsWith(TagPrefix, StringComparison.Ordinal)) return false;
        var suffix = tag.Substring(TagPrefix.Length);
        if (!Regex.IsMatch(suffix, @"\A[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\z"))
            return false;
        version = new Version(suffix);
        return true;
    }

    public static SignedRelease Verify(byte[] body, byte[] signature, string trustedPem)
    {
        if (body.Length is 0 or > 65536 || signature.Length != 384)
            throw new InvalidDataException("Invalid signed update size");
        using var rsa = RSA.Create();
        rsa.ImportFromPem(trustedPem);
        if (rsa.KeySize != 3072 || !rsa.VerifyData(body, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            throw new CryptographicException("Update signature could not be verified");
        using var doc = JsonDocument.Parse(body, new JsonDocumentOptions { MaxDepth = 12 });
        RejectDuplicates(doc.RootElement);
        var root = doc.RootElement;
        if (root.GetProperty("schema").GetInt32() != 1 || Text(root, "channel") != Channel)
            throw new InvalidDataException("Unsupported update channel/schema");
        var version = Text(root, "version");
        if (!Regex.IsMatch(version, @"\A[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\z"))
            throw new InvalidDataException("Invalid release version");
        var sequence = root.GetProperty("sequence").GetInt64();
        if (sequence <= 0) throw new InvalidDataException("Invalid update sequence");
        var native = Text(root, "native_protocol");
        if (native != Protocol) throw new InvalidDataException("This release requires a different VR protocol. Update both devices using the release page.");
        var windows = root.GetProperty("assets").GetProperty("windows");
        var filename = Text(windows, "filename");
        if (filename != $"VibertemisVR-HostManager-Setup-{version}.exe")
            throw new InvalidDataException("Unexpected installer name");
        var expectedUrl = Repository + TagPrefix + version + "/" + filename;
        if (Text(windows, "url") != expectedUrl) throw new InvalidDataException("Unexpected update source");
        var size = windows.GetProperty("bytes").GetInt64();
        var hash = Text(windows, "sha256");
        if (size is <= 0 or > 1073741824 || !Regex.IsMatch(hash, @"\A[a-f0-9]{64}\z"))
            throw new InvalidDataException("Invalid installer size/digest");
        return new(sequence, version, native, new(filename, new Uri(expectedUrl), size, hash));
    }

    private static string Text(JsonElement element, string name) => element.GetProperty(name).GetString()
        ?? throw new InvalidDataException("Missing update field: " + name);
    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw new InvalidDataException("Duplicate update field");
                RejectDuplicates(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var child in element.EnumerateArray()) RejectDuplicates(child);
    }
}