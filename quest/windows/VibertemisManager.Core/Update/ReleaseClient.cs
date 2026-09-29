using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace VibertemisManager.Core.Update;

public sealed class ReleaseClient : IDisposable
{
    private readonly HttpClient _http = new(new HttpClientHandler { AllowAutoRedirect = false })
    { Timeout = TimeSpan.FromMinutes(10) };
    public ReleaseClient() { _http.DefaultRequestHeaders.UserAgent.ParseAdd("VibertemisVR/0.1.0.4"); }
    public void Dispose() => _http.Dispose();

    public async Task<SignedRelease?> CheckAsync(CancellationToken cancellation)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromSeconds(45));
        cancellation = deadline.Token;
        // Quest preview releases only. Never use the desktop /latest endpoint.
        var body = await ReadBoundedAsync(new Uri("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100"), 2*1024*1024, cancellation);
        using var releases = JsonDocument.Parse(body);
        SignedRelease? newest = null;
        foreach (var release in releases.RootElement.EnumerateArray())
        {
            if (release.GetProperty("draft").GetBoolean()) continue;
            var tag = release.GetProperty("tag_name").GetString() ?? "";
            if (!Regex.IsMatch(tag, @"\Aquest-preview-v[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\z")) continue;
            var names = release.GetProperty("assets").EnumerateArray().Select(x => x.GetProperty("name").GetString()).ToHashSet();
            if (!names.Contains("quest-update.json") || !names.Contains("quest-update.json.sig")) continue;
            var prefix = SignedRelease.Repository + tag + "/";
            var manifest = await ReadBoundedAsync(new Uri(prefix + "quest-update.json"), 65536, cancellation);
            var signature = await ReadBoundedAsync(new Uri(prefix + "quest-update.json.sig"), 384, cancellation);
            var verified = SignedRelease.Verify(manifest, signature, SignedRelease.EmbeddedPublicKey());
            if (tag != "quest-preview-v" + verified.Version) throw new InvalidDataException("Release tag does not match signed metadata");
            if (verified.Sequence > SignedRelease.CurrentSequence && new Version(verified.Version) > new Version("0.1.0.4") && (newest is null || verified.Sequence > newest.Sequence)) newest = verified;
        }
        return newest;
    }

    public async Task<string> DownloadAsync(SignedRelease release, string cache, CancellationToken cancellation)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(10));
        cancellation = deadline.Token;
        if (release.Sequence <= SignedRelease.CurrentSequence || new Version(release.Version) <= new Version("0.1.0.4")) throw new InvalidDataException("Update would downgrade this installation");
        Directory.CreateDirectory(cache);
        var final = Path.Combine(cache, release.Windows.Filename);
        if (File.Exists(final))
        {
            try { VerifyFile(final, release.Windows); return final; }
            catch (CryptographicException) { }
        }
        var partial = final + "." + Guid.NewGuid().ToString("N") + ".part";
        try
        {
            using (var response = await OpenAsync(release.Windows.Url, cancellation))
            await using (var input = await response.Content.ReadAsStreamAsync(cancellation))
            await using (var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
            {
                var buffer = new byte[65536];
                long total = 0;
                int read;
                while ((read = await input.ReadAsync(buffer, cancellation)) != 0)
                {
                    total += read;
                    if (total > release.Windows.Bytes) throw new InvalidDataException("Update exceeds its signed size");
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellation);
                }
                if (total != release.Windows.Bytes) throw new InvalidDataException("Incomplete update download");
                await output.FlushAsync(cancellation);
            }
            VerifyFile(partial, release.Windows);
            File.Move(partial, final, overwrite: true);
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
