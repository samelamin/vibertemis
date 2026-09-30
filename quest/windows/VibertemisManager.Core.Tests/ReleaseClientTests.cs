// Mocked HTTP tests for ReleaseClient. Each test wires an
// HttpMessageHandler that returns the bytes the release page
// would have shipped for a particular release tag. The handler
// also records which URLs were requested so we can assert the
// newest-first pre-filter does not spend bandwidth on outdated
// manifests.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VibertemisManager.Core.Update;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class ReleaseClientTests : IDisposable
{
    private readonly RSA _signing = RSA.Create(3072);
    private readonly RSA _otherSigning = RSA.Create(3072);
    private readonly MockHandler _handler = new();
    private readonly ReleaseClient _client;
    private readonly string _trustKey;

    public ReleaseClientTests()
    {
        _client = new ReleaseClient(_handler);
        _trustKey = _signing.ExportSubjectPublicKeyInfoPem();
    }

    public void Dispose()
    {
        _signing.Dispose();
        _otherSigning.Dispose();
        _client.Dispose();
    }

    private byte[] ManifestFor(string version, long sequence, RSA? key = null, string protocol = SignedRelease.Protocol)
    {
        key ??= _signing;
        var obj = new
        {
            schema = 1,
            channel = "quest-preview",
            sequence,
            version,
            native_protocol = protocol,
            assets = new
            {
                windows = new
                {
                    filename = $"VibertemisVR-HostManager-Setup-{version}.exe",
                    url = SignedRelease.Repository + SignedRelease.TagPrefix + version +
                        $"/VibertemisVR-HostManager-Setup-{version}.exe",
                    bytes = 4L,
                    sha256 = new string('a', 64),
                }
            }
        };
        return JsonSerializer.SerializeToUtf8Bytes(obj, new JsonSerializerOptions { WriteIndented = true });
    }

    private byte[] Sign(byte[] body, RSA? key = null) =>
        (key ?? _signing).SignData(body, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

    private string ReleasesListJson(params string[] tags)
    {
        var array = tags.Select(tag => new
        {
            tag_name = tag,
            draft = false,
            assets = new[]
            {
                new { name = "quest-update.json" },
                new { name = "quest-update.json.sig" },
            }
        }).ToArray();
        return JsonSerializer.Serialize(array);
    }

    [Fact]
    public async Task CheckAsync_ReturnsNewestValidRelease_NewestFirst()
    {
        // Three candidates. The middle version is newest by tag, the
        // others are older. The handler must serve only the newest
        // manifest; the older manifests must NEVER be requested.
        var old = ManifestFor("0.1.0.4", 4);
        var newest = ManifestFor("0.1.0.7", 7);
        var middle = ManifestFor("0.1.0.6", 6);
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.4", "quest-preview-v0.1.0.7", "quest-preview-v0.1.0.6"));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json", newest);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json.sig", Sign(newest));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.6/quest-update.json", middle);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.6/quest-update.json.sig", Sign(middle));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.4/quest-update.json", old);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.4/quest-update.json.sig", Sign(old));
        var result = await _client.CheckAsync(CancellationToken.None, publicKeyOverride: _trustKey);
        Assert.NotNull(result);
        Assert.Equal("0.1.0.7", result!.Release.Version);
        Assert.Equal(7, result.Release.Sequence);
        // Older candidates must not have been fetched.
        Assert.DoesNotContain(_handler.Requests,
            u => u.Contains("quest-preview-v0.1.0.6/quest-update.json"));
        Assert.DoesNotContain(_handler.Requests,
            u => u.Contains("quest-preview-v0.1.0.4/quest-update.json"));
    }

    [Fact]
    public async Task CheckAsync_FailsVisibly_OnNewestInvalidSignature()
    {
        // Newest tag (0.1.0.7) is signed with the WRONG key, older
        // (0.1.0.6) is valid. Must surface the invalid newest
        // candidate rather than silently falling back.
        var newest = ManifestFor("0.1.0.7", 7);
        var older = ManifestFor("0.1.0.6", 6);
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.7", "quest-preview-v0.1.0.6"));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json", newest);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json.sig", Sign(newest, _otherSigning));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.6/quest-update.json", older);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.6/quest-update.json.sig", Sign(older));
        var ex = await Assert.ThrowsAsync<System.Security.Cryptography.CryptographicException>(
            () => _client.CheckAsync(CancellationToken.None, publicKeyOverride: _trustKey));
        Assert.Equal("0.1.0.6", SignedRelease.CurrentVersion);
        // Newest invalid must trigger; we should never have silently
        // downgraded to 0.1.0.6.
        Assert.DoesNotContain(_handler.Requests,
            u => u.Contains("quest-preview-v0.1.0.6/quest-update.json.sig"));
    }

    [Fact]
    public async Task CheckAsync_SkipsOlderBrokenManifest_AfterNewestInvalid()
    {
        // Newest tag is valid. Older tag's manifest is unreachable.
        // The newest should still be returned; the older 404 must
        // not abort processing once the newest succeeds.
        var newest = ManifestFor("0.1.0.7", 7);
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.7", "quest-preview-v0.1.0.6"));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json", newest);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json.sig", Sign(newest));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.6/quest-update.json", statusCode: 404);
        var result = await _client.CheckAsync(CancellationToken.None, publicKeyOverride: _trustKey);
        Assert.NotNull(result);
        Assert.Equal("0.1.0.7", result!.Release.Version);
    }

    [Fact]
    public async Task CheckAsync_ReturnsNull_WhenOnlyOlderReleases()
    {
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.4", "quest-preview-v0.1.0.6"));
        var result = await _client.CheckAsync(CancellationToken.None, publicKeyOverride: _trustKey);
        Assert.Null(result);
        // Nothing newer, so no manifest or signature should have
        // been fetched beyond the releases list.
        Assert.Single(_handler.Requests);
    }

    [Fact]
    public async Task CheckAsync_RespectsCancellation()
    {
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.7"));
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => _client.CheckAsync(cts.Token, publicKeyOverride: _trustKey));
    }

    [Fact]
    public async Task DownloadAsync_DetectsTruncatedDownload()
    {
        var manifest = ManifestFor("0.1.0.7", 7);
        var sig = Sign(manifest);
        _handler.Map("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100",
            ReleasesListJson("quest-preview-v0.1.0.7"));
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json", manifest);
        _handler.Map(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/quest-update.json.sig", sig);
        var url = SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/VibertemisVR-HostManager-Setup-0.1.0.7.exe";
        _handler.MapTruncated(url, totalBytes: 4L, deliverBytes: 3);
        var release = (await _client.CheckAsync(CancellationToken.None, publicKeyOverride: _trustKey))!.Release;
        var temp = Path.Combine(Path.GetTempPath(), "vibt-release-trunc-" + Guid.NewGuid().ToString("N"));
        try
        {
            await Assert.ThrowsAsync<InvalidDataException>(
                () => _client.DownloadAsync(release, temp, CancellationToken.None));
        }
        finally { try { Directory.Delete(temp, recursive: true); } catch { } }
    }

    [Fact]
    public async Task DownloadAsync_ClosesFreshDownloadBeforeVerificationAndRename()
    {
        var temp = Path.Combine(Path.GetTempPath(), "vibt-release-fresh-" + Guid.NewGuid().ToString("N"));
        var bytes = new byte[] { 3, 4, 5, 6 };
        var name = "VibertemisVR-HostManager-Setup-0.1.0.7.exe";
        var url = new Uri(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/" + name);
        var release = new SignedRelease(7, "0.1.0.7", SignedRelease.Protocol,
            new ReleaseAsset(name, url, bytes.Length, Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()));
        _handler.Map(url.ToString(), bytes);
        try {
            var file = await _client.DownloadAsync(release, temp, CancellationToken.None);
            Assert.Equal(bytes, File.ReadAllBytes(file));
            Assert.Single(Directory.GetFiles(temp));
            using var exclusive = File.Open(file, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
        } finally { if (Directory.Exists(temp)) Directory.Delete(temp, true); }
    }

    [Fact]
    public async Task DownloadAsync_VerifiesCachedBytesAndReportsCompletion()
    {
        var temp = Path.Combine(Path.GetTempPath(), "vibt-release-cached-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);
        try
        {
            // Lay down a cached file with the correct size and hash.
            var release = new SignedRelease(7, "0.1.0.7", SignedRelease.Protocol,
                new ReleaseAsset(
                    "VibertemisVR-HostManager-Setup-0.1.0.7.exe",
                    new Uri(SignedRelease.Repository + SignedRelease.TagPrefix + "0.1.0.7/VibertemisVR-HostManager-Setup-0.1.0.7.exe"),
                    4L,
                    ""));
            var bytes = new byte[] { 1, 2, 3, 4 };
            var sha = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var asset = release.Windows with { Sha256 = sha, Bytes = bytes.LongLength };
            var final = Path.Combine(temp, asset.Filename);
            File.WriteAllBytes(final, bytes);
            var progresses = new List<UpdateProgress>();
            var result = await _client.DownloadAsync(release with { Windows = asset }, temp, CancellationToken.None,
                new InlineProgress(p => progresses.Add(p)));
            Assert.Equal(final, result);
            Assert.Contains(progresses, p => p.Completed && p.BytesDone == asset.Bytes);
            // The HTTP handler should not have been hit for the
            // installer at all when the cache is valid.
            Assert.DoesNotContain(_handler.Requests,
                u => u.Contains("VibertemisVR-HostManager-Setup-0.1.0.7.exe"));
        }
        finally { try { Directory.Delete(temp, recursive: true); } catch { } }
    }

    private sealed class InlineProgress(Action<UpdateProgress> report) : IProgress<UpdateProgress>
    {
        public void Report(UpdateProgress value) => report(value);
    }

    private sealed class MockHandler : HttpMessageHandler
    {
        public readonly Dictionary<string, byte[]> Responses = new(StringComparer.Ordinal);
        public readonly Dictionary<string, Action<HttpResponseMessage>> Overrides = new(StringComparer.Ordinal);
        public readonly List<string> Requests = new();
        private readonly object _lock = new();

        public void Map(string url, byte[] body) =>
            Responses[url] = body;

        public void Map(string url, string body) =>
            Responses[url] = Encoding.UTF8.GetBytes(body);

        public void Map(string url, int statusCode)
        {
            Overrides[url] = msg =>
            {
                msg.StatusCode = (HttpStatusCode)statusCode;
            };
        }

        public void MapTruncated(string url, long totalBytes, int deliverBytes)
        {
            Overrides[url] = msg =>
            {
                msg.StatusCode = HttpStatusCode.OK;
                msg.Content = new TruncatingStreamContent(totalBytes, deliverBytes);
            };
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.ToString();
            lock (_lock) Requests.Add(url);
            var resp = new HttpResponseMessage(HttpStatusCode.OK);
            if (Overrides.TryGetValue(url, out var apply))
            {
                apply(resp);
            }
            else if (Responses.TryGetValue(url, out var body))
            {
                resp.Content = new ByteArrayContent(body);
            }
            else
            {
                resp.StatusCode = HttpStatusCode.NotFound;
            }
            return Task.FromResult(resp);
        }
    }

    private sealed class TruncatingStreamContent : HttpContent
    {
        private readonly long _total;
        private readonly int _deliver;
        public TruncatingStreamContent(long total, int deliver) { _total = total; _deliver = deliver; }
        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context)
        {
            var buf = new byte[_deliver];
            for (var i = 0; i < buf.Length; i++) buf[i] = (byte)(i + 1);
            return stream.WriteAsync(buf, 0, buf.Length);
        }
        protected override bool TryComputeLength(out long length) { length = _total; return false; }
    }
}