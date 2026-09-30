using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
namespace VibertemisManager.Core.Update;

public sealed record UpdateProgress(long BytesDone, long BytesTotal, string Stage, bool Completed);

public sealed record CheckResult(SignedRelease Release, byte[] Manifest, byte[] Signature);

public sealed class ReleaseClient : IDisposable
{
    private readonly HttpClient _http;
    public ReleaseClient() : this(new HttpClientHandler { AllowAutoRedirect = false }, disposeHandler: true) { }
    public ReleaseClient(HttpMessageHandler handler) : this(handler, disposeHandler: false) { }

    private ReleaseClient(HttpMessageHandler handler, bool disposeHandler)
    {
        if (handler is null) throw new ArgumentNullException(nameof(handler));
        _http = new HttpClient(handler, disposeHandler)
        {
            Timeout = TimeSpan.FromMinutes(10)
        };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("VibertemisVR/" + SignedRelease.CurrentVersion);
    }

    public void Dispose() => _http.Dispose();

    // Returns the verified newer release plus the exact signed bytes
    // it came from. Callers must keep the bytes for the eventual
    // update job so the worker re-verifies them itself.
    public async Task<CheckResult?> CheckAsync(CancellationToken cancellation,
        IProgress<UpdateProgress>? progress = null, string? publicKeyOverride = null)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromSeconds(45));
        cancellation = deadline.Token;
        progress?.Report(new UpdateProgress(0, 0, "checking", false));
        // Quest preview releases only. Never use the desktop /latest endpoint.
        var body = await ReadBoundedAsync(new Uri("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100"), 2 * 1024 * 1024, cancellation);
        using var releases = JsonDocument.Parse(body);
        var candidates = new List<(string Tag, JsonElement Element)>();
        foreach (var release in releases.RootElement.EnumerateArray())
        {
            if (release.GetProperty("draft").GetBoolean()) continue;
            var tag = release.GetProperty("tag_name").GetString() ?? "";
            // Tag prefix + numeric version filter BEFORE any manifest
            // download so older releases cost nothing.
            if (!SignedRelease.TryParseTagVersion(tag, out var parsed)) continue;
            if (parsed <= SignedRelease.CurrentVersionReference) continue;
            var names = release.GetProperty("assets").EnumerateArray().Select(x => x.GetProperty("name").GetString()).ToHashSet();
            if (!names.Contains("quest-update.json") || !names.Contains("quest-update.json.sig")) continue;
            candidates.Add((tag, release));
        }
        candidates.Sort((a, b) =>
        {
            SignedRelease.TryParseTagVersion(a.Tag, out var va);
            SignedRelease.TryParseTagVersion(b.Tag, out var vb);
            return vb!.CompareTo(va);
        });
        var trustKey = publicKeyOverride ?? SignedRelease.EmbeddedPublicKey();
        for (var i = 0; i < candidates.Count; i++)
        {
            var (currentTag, _) = candidates[i];
            var prefix = SignedRelease.Repository + currentTag + "/";
            byte[] manifest;
            byte[] signature;
            try
            {
                manifest = await ReadBoundedAsync(new Uri(prefix + "quest-update.json"), 65536, cancellation);
                signature = await ReadBoundedAsync(new Uri(prefix + "quest-update.json.sig"), 384, cancellation);
            }
            catch (InvalidDataException) when (i > 0)
            {
                continue; // older manifest missing/unreadable: skip silently
            }
            SignedRelease verified;
            try
            {
                verified = SignedRelease.Verify(manifest, signature, trustKey);
            }
            catch (Exception) when (i > 0)
            {
                continue; // older broken signature/metadata: skip silently
            }
            if (currentTag != SignedRelease.TagPrefix + verified.Version)
                throw new InvalidDataException("Release tag does not match signed metadata");
            if (verified.Sequence <= SignedRelease.CurrentSequence) continue;
            if (new Version(verified.Version) <= SignedRelease.CurrentVersionReference) continue;
            progress?.Report(new UpdateProgress(0, 0, "checked", true));
            return new CheckResult(verified, manifest, signature);
        }
        progress?.Report(new UpdateProgress(0, 0, "checked", true));
        return null;
    }

    public async Task<string> DownloadAsync(SignedRelease release, string cache, CancellationToken cancellation,
        IProgress<UpdateProgress>? progress = null)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(10));
        cancellation = deadline.Token;
        if (release.Sequence <= SignedRelease.CurrentSequence
            || new Version(release.Version) <= SignedRelease.CurrentVersionReference)
            throw new InvalidDataException("Update would downgrade this installation");
        Directory.CreateDirectory(cache);
        var final = Path.Combine(cache, release.Windows.Filename);
        if (File.Exists(final))
        {
            try
            {
                VerifyFile(final, release.Windows);
                progress?.Report(new UpdateProgress(release.Windows.Bytes, release.Windows.Bytes, "verified", true));
                return final;
            }
            catch (CryptographicException)
            {
                File.Delete(final);
            }
        }
        var partial = final + "." + Guid.NewGuid().ToString("N") + ".part";
        try
        {
            using var response = await OpenAsync(release.Windows.Url, cancellation);
            await using var input = await response.Content.ReadAsStreamAsync(cancellation);
            await using var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true);
            var buffer = new byte[65536];
            long total = 0;
            int read;
            while ((read = await input.ReadAsync(buffer, cancellation)) != 0)
            {
                total += read;
                if (total > release.Windows.Bytes) throw new InvalidDataException("Update exceeds its signed size");
                await output.WriteAsync(buffer.AsMemory(0, read), cancellation);
                if (progress is not null && (total == release.Windows.Bytes || (total / 65536) % 16 == 0))
                    progress.Report(new UpdateProgress(total, release.Windows.Bytes, "downloading", false));
            }
            if (total != release.Windows.Bytes) throw new InvalidDataException("Incomplete update download");
            await output.FlushAsync(cancellation);
            await output.DisposeAsync();
            VerifyFile(partial, release.Windows);
            File.Move(partial, final, overwrite: true);
            progress?.Report(new UpdateProgress(release.Windows.Bytes, release.Windows.Bytes, "downloaded", true));
            return final;
        }
        finally { if (File.Exists(partial)) File.Delete(partial); }
    }

    public static void VerifyFile(string file, ReleaseAsset asset)
    {
        using var stream = File.OpenRead(file);
        if (stream.Length != asset.Bytes || Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant() != asset.Sha256)
            throw new CryptographicException("Downloaded update integrity check failed");
    }

    private async Task<byte[]> ReadBoundedAsync(Uri uri, int limit, CancellationToken cancellation)
    {
        using var response = await OpenAsync(uri, cancellation);
        await using var input = await response.Content.ReadAsStreamAsync(cancellation);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        int read;
        while ((read = await input.ReadAsync(buffer, cancellation)) != 0)
        {
            if (output.Length + read > limit) throw new InvalidDataException("Update metadata exceeds size limit");
            output.Write(buffer, 0, read);
        }
        return output.ToArray();
    }

    private async Task<HttpResponseMessage> OpenAsync(Uri uri, CancellationToken cancellation)
    {
        for (int count = 0; count < 5; count++)
        {
            if (uri.Scheme != "https" || !uri.IsDefaultPort || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0
                || !(uri.Host is "github.com" or "api.github.com" or "release-assets.githubusercontent.com" or "objects.githubusercontent.com"))
                throw new InvalidDataException("Update redirected to an untrusted location");
            var response = await _http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellation);
            if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
            {
                var redirect = response.Headers.Location;
                response.Dispose();
                if (redirect is null) throw new InvalidDataException("Invalid update redirect");
                uri = new Uri(uri, redirect);
                continue;
            }
            if ((int)response.StatusCode is 403 or 429)
            {
                response.Dispose();
                throw new IOException("Update checks are temporarily rate limited. Try again later.");
            }
            try { response.EnsureSuccessStatusCode(); return response; }
            catch { response.Dispose(); throw; }
        }
        throw new InvalidDataException("Too many update redirects");
    }
}