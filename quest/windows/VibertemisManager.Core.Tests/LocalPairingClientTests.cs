using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using VibertemisManager.Core.Pairing;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class LocalPairingClientTests
{
    private sealed class Transport(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request);
    }
    private static HttpResponseMessage Reply(string body, HttpStatusCode code = HttpStatusCode.OK)
        => new(code) { Content = new StringContent(body) };

    [Fact]
    public async Task ApprovalUsesGlobalHmacAndExactRequestBody()
    {
        var token = new string('b', 64);
        string? previousNonce = null;
        using var http = new HttpClient(new Transport(async request => {
            Assert.Equal("https://127.0.0.1:28541/pairing/admin/decision", request.RequestUri!.AbsoluteUri);
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.False(request.Headers.Contains("X-Vq-Device"));
            var body = await request.Content!.ReadAsByteArrayAsync();
            var nonce = request.Headers.GetValues("X-Vq-Nonce").Single();
            Assert.NotEqual(previousNonce, nonce); previousNonce = nonce;
            var timestamp = request.Headers.GetValues("X-Vq-Ts").Single();
            var canonical = "POST\n/pairing/admin/decision\n" + timestamp + "\n" + nonce + "\n" + Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant();
            var signature = Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(token), Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant();
            Assert.Equal(signature, request.Headers.GetValues("X-Vq-Sig").Single());
            return Reply("{\"open\":true,\"state\":\"waiting\",\"expires_unix\":1800000000,\"devices\":0}");
        })) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, token);
        for (var i = 0; i < 2; i++) {
            var response = await client.SendAsync("decision", new { session_id = "request", code = "ABCD", approve = true }, CancellationToken.None);
            Assert.Equal("waiting", response.State);
        }
    }

    [Theory]
    [InlineData("{\"error\":\"AUTH_FAILED\"}")]
    [InlineData("{\"state\":\"unknown\"}")]
    [InlineData("{\"state\":\"pending\",\"session_id\":\"bad\",\"code\":\"0000-0000-0000-0000\"}")]
    public async Task AuthFailureOrMalformedApprovalCannotEnableApprove(string response)
    {
        using var http = new HttpClient(new Transport(_ => Task.FromResult(Reply(response)))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        await Assert.ThrowsAnyAsync<Exception>(() => client.SendAsync("pending", null, CancellationToken.None));
    }

    [Fact]
    public async Task InvalidActionNeverReachesTransport()
    {
        using var http = new HttpClient(new Transport(_ => throw new Exception("must not send"))) { BaseAddress = new Uri("https://127.0.0.1:28541") };
        using var client = new LocalPairingClient(http, new string('b', 64));
        await Assert.ThrowsAsync<ArgumentException>(() => client.SendAsync("../../start_pcvr", null, CancellationToken.None));
    }
    [Fact]
    public async Task RealCompanionAcceptsManagerHmacAndRejectsWrongTlsPin()
    {
        var root = new DirectoryInfo(AppContext.BaseDirectory);
        while (root is not null && !File.Exists(Path.Combine(root.FullName, "quest", "host", "go.mod"))) root = root.Parent;
        Assert.NotNull(root);
        var temp = Path.Combine(Path.GetTempPath(), "vr-pairing-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);
        System.Diagnostics.Process? companion = null;
        try {
            var exe = Path.Combine(temp, OperatingSystem.IsWindows() ? "companion.exe" : "companion");
            var build = new System.Diagnostics.ProcessStartInfo("go") { WorkingDirectory = Path.Combine(root!.FullName, "quest", "host"), UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var arg in new[] { "build", "-o", exe, "./cmd/vibertemis-host-companion" }) build.ArgumentList.Add(arg);
            using (var process = System.Diagnostics.Process.Start(build)!) {
                var output = process.StandardOutput.ReadToEndAsync(); var error = process.StandardError.ReadToEndAsync();
                using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));
                try { await process.WaitForExitAsync(timeout.Token); }
                catch { try { process.Kill(entireProcessTree: true); } catch { } throw; }
                await output; var details = await error;
                Assert.True(process.ExitCode == 0, "Companion build failed: " + details);
            }
            var stateDir = Path.Combine(temp, "state");
            var session = Path.Combine(temp, "session.json"); File.WriteAllText(session, "{}");
            var start = new System.Diagnostics.ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var arg in new[] { "-listen", "127.0.0.1:0", "-advertise", "192.168.1.42:28540", "-state-dir", stateDir, "-alvr-session", session }) start.ArgumentList.Add(arg);
            companion = System.Diagnostics.Process.Start(start)!;
            var stdout = companion.StandardOutput.ReadToEndAsync(); var stderr = companion.StandardError.ReadToEndAsync();
            var ready = false;
            for (var attempt = 0; attempt < 50 && !companion.HasExited; attempt++) {
                try {
                    using var probe = new LocalPairingClient(stateDir);
                    var pending = await probe.SendAsync("pending", null, CancellationToken.None);
                    Assert.False(pending.Open); ready = true; break;
                } catch (IOException) { }
                catch (HttpRequestException) { }
                await Task.Delay(100);
            }
            Assert.True(ready, "Real companion pairing listener never became ready.");
            using var client = new LocalPairingClient(stateDir);
            Assert.True((await client.SendAsync("open", null, CancellationToken.None)).Open);
            Assert.Equal("waiting", (await client.SendAsync("pending", null, CancellationToken.None)).State);
            var statePath = Path.Combine(stateDir, "state.json");
            var state = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(statePath))!;
            state["certpin"] = new string('f', 64); File.WriteAllText(statePath, state.ToJsonString());
            using var wrongPin = new LocalPairingClient(stateDir);
            await Assert.ThrowsAsync<HttpRequestException>(() => wrongPin.SendAsync("pending", null, CancellationToken.None));
            Assert.False((await client.SendAsync("close", null, CancellationToken.None)).Open);
            companion.Kill(); await companion.WaitForExitAsync(); await stdout; await stderr;
        } finally {
            if (companion is not null) { try { if (!companion.HasExited) { companion.Kill(); await companion.WaitForExitAsync(); } } finally { companion.Dispose(); } }
            try { Directory.Delete(temp, true); } catch { }
        }
    }

}
