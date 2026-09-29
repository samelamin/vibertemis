using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using VibertemisManager.Core.Update;
using Xunit;
namespace VibertemisManager.Core.Tests;
public class SignedReleaseTests : IDisposable
{
    private readonly RSA _key = RSA.Create(3072);
    public void Dispose() => _key.Dispose();
    private byte[] Manifest() => JsonSerializer.SerializeToUtf8Bytes(new {
        schema = 1, channel = "quest-preview", sequence = 5, version = "0.1.0.5",
        native_protocol = SignedRelease.Protocol,
        assets = new { windows = new {
            filename = "VibertemisVR-HostManager-Setup-0.1.0.5.exe",
            url = SignedRelease.Repository + "quest-preview-v0.1.0.5/VibertemisVR-HostManager-Setup-0.1.0.5.exe",
            bytes = 100, sha256 = new string('a', 64)
        }}
    });
    private byte[] Sign(byte[] bytes) => _key.SignData(bytes, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
    [Fact] public void ValidSignedManifestIsAccepted()
    {
        var bytes = Manifest();
        Assert.Equal(5, SignedRelease.Verify(bytes, Sign(bytes), _key.ExportSubjectPublicKeyInfoPem()).Sequence);
    }
    [Fact] public void FakeSignatureCannotPassWithNonzeroPublicKey()
    {
        Assert.Throws<CryptographicException>(() => SignedRelease.Verify(Manifest(), new byte[384], _key.ExportSubjectPublicKeyInfoPem()));
    }
    [Fact] public void ChangedBytesAndWrongSigningKeyAreRejected()
    {
        var bytes = Manifest(); var sig = Sign(bytes); bytes[10] ^= 1;
        Assert.Throws<CryptographicException>(() => SignedRelease.Verify(bytes, sig, _key.ExportSubjectPublicKeyInfoPem()));
        bytes = Manifest(); sig = Sign(bytes);
        using var other = RSA.Create(3072);
        Assert.Throws<CryptographicException>(() => SignedRelease.Verify(bytes, sig, other.ExportSubjectPublicKeyInfoPem()));
    }
    [Theory]
    [InlineData("\"schema\":1", "\"schema\":1,\"schema\":1")]
    [InlineData("quest-preview\"", "stable\"")]
    [InlineData("https://github.com/", "https://example.com/")]
    [InlineData("\"bytes\":100", "\"bytes\":-1")]
    [InlineData("\"bytes\":100", "\"bytes\":2000000000")]
    public void SignedButInvalidMetadataIsRejected(string from, string to)
    {
        var json = Encoding.UTF8.GetString(Manifest());
        Assert.Contains(from, json);
        var bytes = Encoding.UTF8.GetBytes(json.Replace(from, to));
        Assert.Throws<InvalidDataException>(() => SignedRelease.Verify(bytes, Sign(bytes), _key.ExportSubjectPublicKeyInfoPem()));
    }
    [Fact] public void CacheIsReverifiedAgainstSignedSizeAndHash()
    {
        var path = Path.GetTempFileName();
        try {
            File.WriteAllText(path, "original");
            var asset = new ReleaseAsset("a.exe", new Uri("https://github.com/"), 8,
                Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("original"))).ToLowerInvariant());
            ReleaseClient.VerifyFile(path, asset);
            File.WriteAllText(path, "tampered");
            Assert.Throws<CryptographicException>(() => ReleaseClient.VerifyFile(path, asset));
        } finally { File.Delete(path); }
    }
}
