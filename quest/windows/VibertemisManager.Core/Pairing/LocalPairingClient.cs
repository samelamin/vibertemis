using System.Globalization;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace VibertemisManager.Core.Pairing;

public sealed record PairingPending(
    [property: JsonPropertyName("open")] bool Open,
    [property: JsonPropertyName("session_id")] string? SessionId,
    [property: JsonPropertyName("code")] string? Code,
    [property: JsonPropertyName("state")] string State,
    [property: JsonPropertyName("expires_unix")] long Expires,
    [property: JsonPropertyName("devices")] int Devices);

// Local manager credentials never leave the loopback listener. Remote headset
// credentials cannot approve another device. No Vibeshine files are accessed.
public sealed class LocalPairingClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly string _token;
    public LocalPairingClient(string stateDir)
    {
        var path = Path.Combine(stateDir, "state.json");
        using var stream = File.OpenRead(path);
        if (stream.Length > 16384) throw new InvalidDataException("Invalid PC pairing state.");
        using var state = JsonDocument.Parse(stream);
        _token = state.RootElement.GetProperty("token").GetString() ?? "";
        var pin = state.RootElement.GetProperty("certpin").GetString() ?? "";
        if (!IsHex(_token, 64) || !IsHex(pin, 64)) throw new InvalidDataException("Invalid PC pairing state.");
        var handler = new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false };
        handler.ServerCertificateCustomValidationCallback = (request, cert, _, _) =>
            request.RequestUri?.Host == "127.0.0.1" && request.RequestUri.Port == 28541 && cert is not null &&
            string.Equals(Convert.ToHexString(SHA256.HashData(cert.RawData)), pin, StringComparison.OrdinalIgnoreCase);
        _http = new HttpClient(handler) { BaseAddress = new Uri("https://127.0.0.1:28541"), Timeout = TimeSpan.FromSeconds(5), MaxResponseContentBufferSize = 16384 };
    }
    // Injectable transport allows wire-level HMAC and response-bound tests.
    public LocalPairingClient(HttpClient http, string token) => (_http, _token) = (http, token);
    private static bool IsHex(string text, int length) => text.Length == length && text.All(c => "0123456789abcdef".Contains(c));
    public async Task<PairingPending> SendAsync(string action, object? payload, CancellationToken cancellationToken)
    {
        if (action is not ("open" or "pending" or "decision" or "close" or "forget")) throw new ArgumentException("Unknown pairing action.", nameof(action));
        var path = "/pairing/admin/" + action;
        var body = JsonSerializer.SerializeToUtf8Bytes(payload ?? new { });
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture);
        var nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var canonical = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant();
        var signature = Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(_token), Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant();
        using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = new ByteArrayContent(body) };
        request.Content.Headers.ContentType = new("application/json");
        request.Headers.Add("X-Vq-Ts", timestamp);
        request.Headers.Add("X-Vq-Nonce", nonce);
        request.Headers.Add("X-Vq-Sig", signature);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
        var data = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (data.Length > 16384) throw new InvalidDataException("Invalid pairing response.");
        using var json = JsonDocument.Parse(data);
        if (!response.IsSuccessStatusCode || json.RootElement.TryGetProperty("error", out _))
            throw new InvalidOperationException("Pairing request failed. Retry Pair headset on the PC.");
        var pending = JsonSerializer.Deserialize<PairingPending>(data) ?? throw new InvalidDataException("Invalid pairing response.");
        if (pending.State is not ("waiting" or "closed" or "pending" or "approved" or "denied")) throw new InvalidDataException("Invalid pairing state.");
        if (pending.State is "pending" or "approved" or "denied") {
            if (!IsHex(pending.SessionId ?? "", 64) || pending.Code is null ||
                !System.Text.RegularExpressions.Regex.IsMatch(pending.Code, "\\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\\z"))
                throw new InvalidDataException("Invalid pairing identity.");
        }
        return pending;
    }
    public void Dispose() => _http.Dispose();
}
