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

// Owner-controlled error mapping. The Go companion emits a
// structured `{error, code}` envelope where `code` is one of
// the seven stable sentinel codes defined in
// enrollment.ErrorCode. This whitelist is the ONLY source of
// user-facing text for pairing errors. The server-side `error`
// string is NEVER echoed — a misbehaving client or proxy
// cannot inject arbitrary text into the manager UI.
public sealed class PairingAdminException : Exception
{
    public string ErrorCode { get; }
    public string OwnerText { get; }
    public PairingAdminException(string code, string ownerText) : base(ownerText)
    {
        ErrorCode = code;
        OwnerText = ownerText;
    }
}

// Local manager credentials never leave the loopback listener. Remote headset
// credentials cannot approve another device. No Vibeshine files are accessed.
//
// StateDir is a *directory* that may or may not contain state.json yet — the
// companion is launched by HostRecoveryController on a recovery tick and may
// take a beat to write its state. Constructors that take a path therefore
// validate eagerly, while the factory pattern used by the coordinator defers
// construction until the first call so a freshly-started companion can finish
// before the client is created.
public sealed class LocalPairingClient : IDisposable
{
    private static readonly System.Collections.Generic.Dictionary<string, string> ErrorMessages =
        new(System.StringComparer.Ordinal)
        {
            ["CLOSED"] = "Pairing is closed. On the PC, choose Pair headset in VR Host Manager.",
            ["EXPIRED"] = "Pairing expired or was cancelled. Retry from your Quest headset.",
            ["BUSY"] = "Another headset is awaiting approval. Check the request on the PC first.",
            ["RATE_LIMITED"] = "Too many pairing requests. Wait about two minutes before retrying.",
            ["INVALID"] = "Pairing identity did not match. Cancel and retry on both devices.",
            ["CAPACITY"] = "Headset limit reached. Forget paired headsets in the PC manager before retrying.",
            ["STORAGE_FAILED"] = "Could not save pairing on this PC. Retry, or reinstall the VR Manager if the error persists.",
            ["UNKNOWN"] = "Pairing request rejected. Retry Pair headset on the PC.",
        };

    private readonly HttpClient _http;
    private readonly string _token;
    private bool _disposed;

    public LocalPairingClient(string stateDir)
    {
        if (string.IsNullOrEmpty(stateDir)) throw new ArgumentException("State directory is required.", nameof(stateDir));
        var path = Path.Combine(stateDir, "state.json");
        if (!File.Exists(path)) throw new FileNotFoundException("Companion pairing state not ready yet.", path);
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
    private static readonly System.Text.RegularExpressions.Regex CodePattern =
        new("\\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\\z");

    public async Task<PairingPending> SendAsync(string action, object? payload, CancellationToken cancellationToken)
    {
        if (action is not ("open" or "pending" or "decision" or "close" or "forget")) throw new ArgumentException("Unknown pairing action.", nameof(action));
        var raw = await SendRawAsync(action, payload, cancellationToken).ConfigureAwait(false);
        var pending = JsonSerializer.Deserialize<PairingPending>(raw)
            ?? throw new InvalidDataException("Invalid pairing response.");
        ValidatePendingEnvelope(pending);
        return pending;
    }

    /// <summary>
    /// Send a manager action that returns the canonical pending
    /// snapshot including the receiving / suppressed / lease
    /// fields added by the lease-mode wire envelope. Used by
    /// <see cref="PairingReceiveCoordinator"/> to drive lease
    /// renewal and pending polling.
    /// </summary>
    public async Task<CoordinatorPending> SendCoordinatorAsync(string action, object? payload, CancellationToken cancellationToken)
    {
        if (action is not ("renew" or "suppress" or "unsuppress" or "pending" or "open" or "close" or "decision" or "forget"))
            throw new ArgumentException("Unknown coordinator action.", nameof(action));
        var raw = await SendRawAsync(action, payload, cancellationToken).ConfigureAwait(false);
        var pending = JsonSerializer.Deserialize<CoordinatorPending>(raw)
            ?? throw new InvalidDataException("Invalid pairing response.");
        ValidateCoordinatorEnvelope(pending);
        return pending;
    }

    private static void ValidatePendingEnvelope(PairingPending pending)
    {
        if (pending.State is not ("waiting" or "closed" or "pending" or "approved" or "denied"))
            throw new InvalidDataException("Invalid pairing state.");
        if (pending.State is "pending" or "approved" or "denied")
        {
            if (!IsHex(pending.SessionId ?? "", 64) || pending.Code is null ||
                !CodePattern.IsMatch(pending.Code))
                throw new InvalidDataException("Invalid pairing identity.");
        }
        // waiting/closed MUST NOT carry a code+session pair for a stale
        // session: the server clears current on close. Defense in depth —
        // the JSON parser does not enforce this and a buggy peer could
        // echo back stale fields. approved is exempt: the server
        // returns approved until the per-challenge TTL expires even
        // when the window is closed.
        if (pending.State is "waiting" or "closed")
        {
            if (!string.IsNullOrEmpty(pending.SessionId) || !string.IsNullOrEmpty(pending.Code))
                throw new InvalidDataException("Stale pairing identity in closed state.");
        }
        // Devices is bounded by 32 server-side; refuse impossibly large counts.
        if (pending.Devices < 0 || pending.Devices > 32)
            throw new InvalidDataException("Invalid pairing device count.");
    }

    private static void ValidateCoordinatorEnvelope(CoordinatorPending pending)
    {
        if (pending.State is not ("waiting" or "closed" or "pending" or "approved" or "denied"))
            throw new InvalidDataException("Invalid pairing state.");
        if (pending.State is "pending" or "approved" or "denied")
        {
            if (!IsHex(pending.SessionId ?? "", 64) || pending.Code is null ||
                !CodePattern.IsMatch(pending.Code))
                throw new InvalidDataException("Invalid pairing identity.");
        }
        if (pending.State is "waiting" or "closed")
        {
            if (!string.IsNullOrEmpty(pending.SessionId) || !string.IsNullOrEmpty(pending.Code))
                throw new InvalidDataException("Stale pairing identity in closed state.");
        }
        if (pending.Devices < 0 || pending.Devices > 32)
            throw new InvalidDataException("Invalid pairing device count.");
        if (pending.SuppressUntilUnix < 0)
            throw new InvalidDataException("Invalid pairing suppress timestamp.");
        // Lease expiry is only meaningful when the receiving mode is active.
        if (pending.LeaseExpiresUnix != 0 && !pending.Receiving)
            throw new InvalidDataException("Lease expiry set without receiving mode.");
    }

    private async Task<byte[]> SendRawAsync(string action, object? payload, CancellationToken cancellationToken)
    {
        if (_disposed) throw new ObjectDisposedException(nameof(LocalPairingClient));
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
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken).ConfigureAwait(false);
        var data = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        if (data.Length > 16384) throw new InvalidDataException("Invalid pairing response.");
        using var json = JsonDocument.Parse(data);
        if (json.RootElement.TryGetProperty("error", out var errElem))
        {
            var rawCode = json.RootElement.TryGetProperty("code", out var codeElem)
                ? codeElem.GetString() ?? ""
                : "";
            // Whitelist mapping: only the seven stable wire codes
            // map to owner-controlled text. An unknown code falls
            // back to UNKNOWN so the manager UI NEVER echoes
            // arbitrary server text (a misbehaving client or
            // proxy cannot inject text into the manager).
            var mappedCode = ErrorMessages.ContainsKey(rawCode) ? rawCode : "UNKNOWN";
            throw new PairingAdminException(mappedCode, ErrorMessages[mappedCode]);
        }
        if (!response.IsSuccessStatusCode)
            throw new PairingAdminException("UNKNOWN", ErrorMessages["UNKNOWN"]);
        return data;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _http.Dispose(); } catch { /* ignore */ }
    }
}