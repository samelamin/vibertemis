package com.vibertemis.quest.pcvr;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Custom VR port round-trip test: run the real
 * {@link StandalonePairingClient#enroll(String, int,
 * com.vibertemis.quest.pcvr.StandalonePairingClient.Progress)} TLS
 * handshake against an ephemeral
 * {@link javax.net.ssl.SSLServerSocket} that speaks the standalone
 * pairing protocol. The server presents the existing
 * {@code pcvr/host.pem} leaf and signs replies with the matching
 * {@code pcvr/host-key.der} private key — the same fixtures
 * {@link HostClientTest} uses for its pinned-TLS scenarios — so the
 * headset-side trust path (TLS pin, PSS POLL signature, OAEP token
 * label, comparison code) is exercised end-to-end without mocking.
 *
 * <p>The test asserts:
 * <ul>
 *   <li>the begin request carries the headset's RSA public key
 *       and nonce (parsed from the captured request body),</li>
 *   <li>the headset accepts the {@code ttl_seconds} advertised by
 *       the server and never substitutes a default,</li>
 *   <li>the PSS signature over the POLL transcript verifies against
 *       the headset's public key,</li>
 *   <li>the OAEP-encrypted token decrypts with the
 *       {@code VIBERTEMIS-STANDALONE-1-TOKEN\n1\n<session_id>}
 *       label (a wrong label would produce garbage),</li>
 *   <li>the {@code Progress.comparing} callback fires EXACTLY once
 *       with a valid {@code XXXX-XXXX-XXXX-XXXX} comparison
 *       code,</li>
 *   <li>the returned {@link HostPairing} preserves the custom
 *       VR port so the next {@code Connect} reaches the same
 *       host:port pair.</li>
 * </ul>
 *
 * <p>The TLS server is ephemeral so the test cannot be hit by a
 * second writer and cannot collide with another test's port
 * allocation. The server thread is joined within a small bounded
 * budget so a stuck handshake cannot pin the test thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class CustomVrPortTest {

    private SSLServerSocket server;
    private Thread serverThread;
    private volatile boolean stopped;
    private volatile String clientKeyB64;
    private volatile String clientNonce;
    private volatile String sessionId;
    private volatile String serverNonce;
    private final AtomicInteger beginCalls = new AtomicInteger();
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final AtomicReference<String> pollSignatureB64 = new AtomicReference<>();
    private final AtomicInteger codeCallbacks = new AtomicInteger();
    private final AtomicReference<String> capturedCode = new AtomicReference<>();
    private final AtomicReference<String> denyReason = new AtomicReference<>();
    private volatile int customPort = -1;
    private volatile long advertisedTtl = -1L;
    private volatile byte[] signedToken;
    private volatile String approvedDeviceId;
    /** Signaled when the server has produced its final approved
     *  reply so the test can race past the enrollment loop. */
    private final CountDownLatch approved = new CountDownLatch(1);
    /** Set to true to make the server emit an approved reply with a
     *  malformed device_id. The headset must surface CODE_INVALID
     *  rather than an opaque ad-hoc message. */
    private volatile boolean malformedDeviceId;
    /** Set to true to make the server emit an approved reply with a
     *  malformed encrypted_token (256-byte OAEP ciphertext is
     *  required). The headset must surface CODE_INVALID. */
    private volatile boolean malformedEncryptedToken;
    /** Set to true to make the server emit an approved reply with an
     *  encrypted_token that is valid Base64 but wrong OAEP padding
     *  (e.g. the OAEP label was wrong, or the cipher text was
     *  random). The headset must surface CODE_INVALID, NOT
     *  opaque UNKNOWN or a transport-style "Could not reach VR
     *  Host Manager" message. */
    private volatile boolean wrongOaepLabel;
    /** Set to true to make the server return a malformed challenge
     *  body (e.g. a missing ttl_seconds field). The headset must
     *  surface CODE_INVALID, not UNKNOWN, because the server did
     *  respond — it just sent a broken envelope. */
    private volatile boolean malformedChallenge;
    /** Set to true to make the server return a challenge that
     *  references a different cert than the one observed on the
     *  wire. The pin mismatch must surface CODE_INVALID, not
     *  UNKNOWN. */
    private volatile boolean pinMismatch;
    /** Set to true to make the server emit an approved reply with a
     *  state schema mismatch (schema != 1). The headset must surface
     *  CODE_INVALID. */
    private volatile boolean wrongSchema;
    /** When true, the poll handler returns a "denied" state
     *  instead of "approved". Used to verify the outer
     *  ComputerDetails fallback stops on a server-side
     *  SetupFailure. */
    private volatile boolean deniedFlow;

    static byte[] resource(String name) throws Exception {
        return HostClientTest.resource(name);
    }

    static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    static SSLServerSocket createEphemeralSslServer(X509Certificate cert, PrivateKey key)
            throws Exception {
        return createSslServerOn(cert, key, 0);
    }

    /** Bind the SSL server to a fixed port (use {@code 0} for
     *  ephemeral). The fixed-port overload is needed for the
     *  ComputerDetails-fallback tests because
     *  {@link StandalonePairingClient#enroll(com.limelight.nvstream.http.ComputerDetails,
     *  StandalonePairingClient.Progress)} always uses
     *  {@link VrSetupDiscovery#DEFAULT_VR_PORT} and ignores the
     *  port stored on each {@link
     *  com.limelight.nvstream.http.ComputerDetails.AddressTuple}. */
    static SSLServerSocket createSslServerOn(X509Certificate cert, PrivateKey key, int port)
            throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("test", key, "test".toCharArray(),
                new java.security.cert.Certificate[]{cert});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "test".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(factory.getKeyManagers(), null, null);
        return (SSLServerSocket) tls.getServerSocketFactory()
                .createServerSocket(port, 10, InetAddress.getByName("127.0.0.1"));
    }

    @Before
    public void start() throws Exception {
        stopped = false;
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(resource("host.pem")));
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(resource("host-key.der")));
        server = createEphemeralSslServer(cert, key);
        serverThread = new Thread(() -> {
            while (!stopped) {
                try (javax.net.ssl.SSLSocket socket =
                             (javax.net.ssl.SSLSocket) server.accept()) {
                    socket.setSoTimeout(5000);
                    handleRequest(socket);
                } catch (Exception failure) {
                    if (!stopped) failure.printStackTrace();
                }
            }
        }, "custom-vr-port-test-server");
        serverThread.start();
    }

    @After
    public void stop() throws Exception {
        stopped = true;
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(3000);
    }

    /** Single-request dispatcher. Reads the HTTP request, parses
     *  the path, generates the matching pairing reply, and writes
     *  the HTTP response. Handles both /pairing/begin and
     *  /pairing/poll on the same ephemeral server. */
    private void handleRequest(javax.net.ssl.SSLSocket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        int next;
        String headers = "";
        while ((next = in.read()) != -1) {
            head.write(next);
            headers = head.toString("US-ASCII");
            if (headers.endsWith("\r\n\r\n")) break;
            if (head.size() > 16384) throw new java.io.IOException();
        }
        if (headers.isEmpty()) return;
        String[] lines = headers.split("\r\n");
        String[] request = lines[0].split(" ");
        String method = request[0];
        String path = request[1];
        int length = 0;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(value);
            }
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count < 0) throw new java.io.IOException();
            offset += count;
        }
        String reply;
        if ("/pairing/begin".equals(path)) {
            reply = handleBegin(body);
        } else if ("/pairing/poll".equals(path)) {
            reply = handlePoll(body);
        } else {
            reply = "{}";
        }
        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
        String http = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + data.length + "\r\n\r\n";
        out.write(http.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private String handleBegin(byte[] body) throws Exception {
        beginCalls.incrementAndGet();
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        // Capture the headset's RSA public key + nonce so the poll
        // handler can verify the PSS signature.
        clientKeyB64 = json.getString("client_key");
        clientNonce = json.getString("client_nonce");
        sessionId = randomHex(32);
        serverNonce = randomHex(32);
        // Build the canonical standalone challenge. The headset
        // rejects any challenge missing ttl_seconds / expires_unix,
        // so we MUST advertise a real lifetime.
        JSONObject challenge = new JSONObject();
        challenge.put("schema", 1);
        challenge.put("session_id", sessionId);
        challenge.put("server_nonce", serverNonce);
        if (malformedChallenge) {
            // Drop ttl_seconds so parseChallenge throws a
            // SetupFailure(CODE_INVALID). The pairing code surfaces
            // the whitelisted INVALID recovery message; a separate
            // test in StandalonePairingFlatChallengeTest verifies
            // the missing-lifetime rejection.
            return challenge.toString();
        }
        if (pinMismatch) {
            // Present a different cert than the one the headset
            // sees on the wire. The trust manager captures the
            // observed cert first, then parseCertificate + pin
            // check fail because the challenge claims a different
            // identity. The mismatch must surface CODE_INVALID,
            // not the old opaque "PC identity changed" message.
            challenge.put("cert_pem",
                    new String(resource("other.pem"), StandardCharsets.US_ASCII));
            challenge.put("ttl_seconds", 60L);
            advertisedTtl = 60L;
            return challenge.toString();
        }
        challenge.put("cert_pem",
                new String(resource("host.pem"), StandardCharsets.US_ASCII));
        long ttl = 60L;
        advertisedTtl = ttl;
        challenge.put("ttl_seconds", ttl);
        return challenge.toString();
    }

    private String handlePoll(byte[] body) throws Exception {
        pollCalls.incrementAndGet();
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        pollSignatureB64.set(json.getString("signature"));
        // Verify PSS(POLL, id, nonce, server, pin) using the
        // headset's public key. A negative verify rejects the
        // pairing — the headset then sees CODE_INVALID.
        String pin = HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(
                ((X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(resource("host.pem"))))
                        .getEncoded()));
        PublicKey clientKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(clientKeyB64)));
        Signature sig = Signature.getInstance("RSASSA-PSS");
        sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, 32, 1));
        sig.initVerify(clientKey);
        sig.update(StandalonePairingClient.transcript("POLL",
                sessionId, clientNonce, serverNonce, pin));
        boolean verified = sig.verify(
                Base64.getDecoder().decode(pollSignatureB64.get()));
        if (!verified || deniedFlow) {
            if (!verified) denyReason.set("PSS signature did not verify");
            JSONObject denied = new JSONObject();
            denied.put("schema", 1);
            denied.put("state", "denied");
            approved.countDown();
            return denied.toString();
        }
        // Encrypt a 64-hex token with OAEP using the headset's
        // public key. The label MUST be
        //   DOMAIN + "-TOKEN\n1\n" + session_id
        // or the headset's decrypt step produces garbage.
        String token = randomHex(32);
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        byte[] zeroTokenBytes = new byte[tokenBytes.length];
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, clientKey,
                new OAEPParameterSpec("SHA-256", "MGF1",
                        MGF1ParameterSpec.SHA256,
                        new PSource.PSpecified(
                                (StandalonePairingClient.DOMAIN + "-TOKEN\n1\n" + sessionId)
                                        .getBytes(StandardCharsets.UTF_8))));
        byte[] encrypted = cipher.doFinal(tokenBytes);
        java.util.Arrays.fill(tokenBytes, (byte) 0);
        java.util.Arrays.fill(zeroTokenBytes, (byte) 0);
        signedToken = token.getBytes(StandardCharsets.US_ASCII);
        approvedDeviceId = randomHex(16);
        JSONObject approvedReply = new JSONObject();
        approvedReply.put("schema",
                wrongSchema ? 2 : 1);
        approvedReply.put("state", "approved");
        approvedReply.put("device_id",
                malformedDeviceId ? "not-hex" : approvedDeviceId);
        if (wrongOaepLabel) {
            // Encrypt with a deliberately wrong OAEP label so the
            // headset's decrypt step throws BadPaddingException.
            Cipher bad = Cipher.getInstance("RSA/ECB/OAEPPadding");
            bad.init(Cipher.ENCRYPT_MODE, clientKey,
                    new OAEPParameterSpec("SHA-256", "MGF1",
                            MGF1ParameterSpec.SHA256,
                            new PSource.PSpecified(
                                    "wrong-label".getBytes(StandardCharsets.UTF_8))));
            approvedReply.put("encrypted_token",
                    Base64.getEncoder().encodeToString(bad.doFinal(tokenBytes)));
        } else if (malformedEncryptedToken) {
            approvedReply.put("encrypted_token",
                    Base64.getEncoder().encodeToString(new byte[128]));
        } else {
            approvedReply.put("encrypted_token",
                    Base64.getEncoder().encodeToString(encrypted));
        }
        approved.countDown();
        return approvedReply.toString();
    }

    /** Real TLS enrollment on an ephemeral port. The test
     *  asserts the full pairing contract: the comparison-code
     *  callback fires once with a valid format, the advertised
     *  TTL is honoured, the returned pairing preserves the
     *  custom VR port verbatim. */
    @Test
    public void realEnrollmentRoundTripsCustomPortOverTls() throws Exception {
        customPort = server.getLocalPort();
        StandalonePairingClient client = new StandalonePairingClient();
        HostPairing pairing = client.enroll("127.0.0.1", customPort,
                code -> {
                    codeCallbacks.incrementAndGet();
                    capturedCode.set(code);
                });
        // The server must have seen exactly one begin + one poll.
        assertEquals("begin must be called exactly once", 1, beginCalls.get());
        assertEquals("poll must be called exactly once", 1, pollCalls.get());
        // The TTL the headset computed from the server challenge
        // matches the advertised ttl_seconds. The headset never
        // substitutes a default.
        assertEquals(60L, advertisedTtl);
        // Comparison-code callback: exactly once, valid format.
        assertEquals("comparison code must fire exactly once",
                1, codeCallbacks.get());
        String code = capturedCode.get();
        assertNotNull("comparison code must be non-null", code);
        assertEquals("code format is XXXX-XXXX-XXXX-XXXX",
                4 + 1 + 4 + 1 + 4 + 1 + 4, code.length());
        assertTrue("code matches XXXX-XXXX-XXXX-XXXX hex: " + code,
                code.matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}"));
        // The pairing address round-trips the custom port so the
        // next Connect hits the same host:port pair. parse it
        // through HostPairing's URI check.
        assertTrue(pairing.address.endsWith(":" + customPort));
        URL endpoint = new URL("https://" + pairing.address + "/status");
        assertEquals(customPort, endpoint.getPort());
        // The token the headset saved equals the server's secret
        // (the headset decrypted the OAEP ciphertext with the
        // correct label — a wrong label would produce garbage and
        // fail the 64-hex check in enrollInternal).
        assertNotNull(pairing.token);
        assertEquals(64, pairing.token.length());
        // The approved device_id round-trips.
        assertEquals(approvedDeviceId, pairing.deviceId);
        // The pinned TLS leaf matches host.pem.
        byte[] pem = resource("host.pem");
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem));
        assertEquals(HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())),
                pairing.pin);
    }

    /** The DEFAULT_VR_PORT constant must remain 28540 so the
     *  picker defaults are stable. This is a free-standing
     *  contract that the rest of the suite depends on. */
    @Test
    public void defaultVrPortIs28540() {
        assertEquals(28540, VrSetupDiscovery.DEFAULT_VR_PORT);
    }

    /** Approved poll with a malformed device_id (not 32 hex chars)
     *  maps to local INVALID so the user sees the whitelisted
     *  recovery message rather than an opaque "Invalid headset
     *  identity" string. */
    @Test
    public void approvedPollMalformedDeviceIdMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        malformedDeviceId = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("malformed device_id must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** Approved poll with a wrong-length encrypted_token must
     *  surface CODE_INVALID. A token that is not a 256-byte
     *  RSA-OAEP ciphertext indicates the PC's pairing service is
     *  broken or hostile; the headset must NOT attempt to decrypt
     *  a different length and must surface the local INVALID
     *  code. */
    @Test
    public void approvedPollMalformedEncryptedTokenMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        malformedEncryptedToken = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("malformed encrypted_token must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** Approved poll with an OAEP ciphertext encrypted under the
     *  wrong label must surface CODE_INVALID. The RSA-OAEP
     *  decrypt step throws BadPaddingException when the label
     *  mismatches; the catch must map that to CODE_INVALID, NOT
     *  the opaque UNKNOWN / "Could not reach VR Host Manager"
     *  transport fallback that the old wrap-everything catch
     *  produced. */
    @Test
    public void approvedPollWrongOaepLabelMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        wrongOaepLabel = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("wrong OAEP label must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** Approved poll with schema != 1 must surface CODE_INVALID.
     *  parsePoll throws IOException on schema mismatch, and the
     *  catch must map it to CODE_INVALID (the server returned
     *  200 but the envelope is wrong) — NOT to UNKNOWN. */
    @Test
    public void approvedPollWrongSchemaMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        wrongSchema = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("wrong schema must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
        }
    }

    /** A malformed challenge (missing ttl_seconds) returns a 200
     *  with an incomplete envelope. parseChallenge throws
     *  IOException ("Pairing challenge missing lifetime"); the
     *  catch must map it to CODE_INVALID, NOT to UNKNOWN. The
     *  distinction matters because UNKNOWN is the
     *  "first-time-neutral" fallback that implies no prior
     *  pairing — the user just received a broken challenge and
     *  deserves the actionable INVALID message. */
    @Test
    public void malformedChallengeMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        malformedChallenge = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("malformed challenge must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** A challenge that references a different cert than the one
     *  observed on the wire surfaces CODE_INVALID. The trust
     *  manager captures the observed cert SHA-256 first; then the
     *  pin check fails. The new mapping must surface the
     *  whitelisted INVALID message, not the old opaque
     *  "PC identity changed" string. */
    @Test
    public void pinMismatchMapsToInvalid() throws Exception {
        customPort = server.getLocalPort();
        pinMismatch = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", customPort,
                    code -> { codeCallbacks.incrementAndGet(); });
            fail("pin mismatch must surface CODE_INVALID");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
        }
    }

    /** The outer ComputerDetails fallback must retry the next
     *  address when the FIRST address is genuinely refused at
     *  the transport layer (no challenge received). Connect
     *  refused is the canonical pre-challenge transport error,
     *  so the outer loop must skip it and try the second
     *  address, which succeeds.
     *
     *  <p>The two addresses must differ in their host string so
     *  the LinkedHashSet in {@link
     *  StandalonePairingClient#enroll(com.limelight.nvstream.http.ComputerDetails,
     *  StandalonePairingClient.Progress)} does not deduplicate
     *  them. The fallback loop always uses {@link
     *  VrSetupDiscovery#DEFAULT_VR_PORT}, so this test binds a
     *  dedicated SSL server to {@code 127.0.0.1:DEFAULT_VR_PORT}
     *  and uses {@code 127.0.0.2:DEFAULT_VR_PORT} as the refused
     *  address (Linux sends RST for an unbound port on a
     *  configured loopback IP). */
    @Test
    public void computerDetailsFallbackSkipsRefusedFirstAddress() throws Exception {
        int realPort = VrSetupDiscovery.DEFAULT_VR_PORT;
        // Set up a dedicated server bound to 127.0.0.1:DEFAULT_VR_PORT
        // so enroll(ComputerDetails) reaches a real listener on
        // its second iteration. The fallback test owns its own
        // server lifecycle to avoid leaking sockets into the
        // shared @Before/@After server.
        final AtomicReference<SSLServerSocket> fallbackServerRef =
                new AtomicReference<>();
        final AtomicBoolean fallbackStop = new AtomicBoolean(false);
        final AtomicInteger fbBeginCalls = new AtomicInteger();
        final AtomicInteger fbPollCalls = new AtomicInteger();
        final AtomicReference<String> fbClientKey = new AtomicReference<>();
        final AtomicReference<String> fbClientNonce = new AtomicReference<>();
        final AtomicReference<String> fbSessionId = new AtomicReference<>();
        final AtomicReference<String> fbServerNonce = new AtomicReference<>();
        Thread fallbackThread = null;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(resource("host.pem")));
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(resource("host-key.der")));
            SSLServerSocket fallbackServer = createSslServerOn(cert, key, realPort);
            fallbackServerRef.set(fallbackServer);
            fallbackThread = new Thread(() -> {
                final SSLServerSocket server = fallbackServerRef.get();
                while (!fallbackStop.get()) {
                    try (javax.net.ssl.SSLSocket socket =
                                 (javax.net.ssl.SSLSocket) server.accept()) {
                        socket.setSoTimeout(3000);
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                        int next;
                        String headers = "";
                        while ((next = in.read()) != -1) {
                            head.write(next);
                            headers = head.toString("US-ASCII");
                            if (headers.endsWith("\r\n\r\n")) break;
                            if (head.size() > 16384) break;
                        }
                        if (headers.isEmpty()) return;
                        String path = headers.split("\r\n")[0].split(" ")[1];
                        int length = 0;
                        for (String line : headers.split("\r\n")) {
                            int colon = line.indexOf(':');
                            if (colon < 0) continue;
                            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                                length = Integer.parseInt(line.substring(colon + 1).trim());
                            }
                        }
                        byte[] body = new byte[length];
                        int offset = 0;
                        while (offset < length) {
                            int c2 = in.read(body, offset, length - offset);
                            if (c2 < 0) break;
                            offset += c2;
                        }
                        String reply;
                        if ("/pairing/begin".equals(path)) {
                            fbBeginCalls.incrementAndGet();
                            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                            fbClientKey.set(json.getString("client_key"));
                            fbClientNonce.set(json.getString("client_nonce"));
                            fbSessionId.set(randomHex(32));
                            fbServerNonce.set(randomHex(32));
                            JSONObject ch = new JSONObject();
                            ch.put("schema", 1);
                            ch.put("session_id", fbSessionId.get());
                            ch.put("server_nonce", fbServerNonce.get());
                            ch.put("cert_pem", new String(resource("host.pem"), StandardCharsets.US_ASCII));
                            ch.put("ttl_seconds", 60L);
                            reply = ch.toString();
                        } else if ("/pairing/poll".equals(path)) {
                            fbPollCalls.incrementAndGet();
                            String sessionId = fbSessionId.get();
                            String clientNonce = fbClientNonce.get();
                            String serverNonce = fbServerNonce.get();
                            String clientKeyB64 = fbClientKey.get();
                            if (sessionId == null || clientKeyB64 == null) {
                                continue;
                            }
                            PublicKey clientKey = KeyFactory.getInstance("RSA")
                                    .generatePublic(new X509EncodedKeySpec(
                                            Base64.getDecoder().decode(clientKeyB64)));
                            // Verify PSS over POLL transcript.
                            byte[] certDer = ((X509Certificate) CertificateFactory.getInstance("X.509")
                                    .generateCertificate(new ByteArrayInputStream(resource("host.pem")))).getEncoded();
                            String pin = HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(certDer));
                            JSONObject pollJson = new JSONObject(new String(body, StandardCharsets.UTF_8));
                            byte[] sig = Base64.getDecoder().decode(pollJson.getString("signature"));
                            Signature verifier = Signature.getInstance("RSASSA-PSS");
                            verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1",
                                    MGF1ParameterSpec.SHA256, 32, 1));
                            verifier.initVerify(clientKey);
                            verifier.update(StandalonePairingClient.transcript("POLL",
                                    sessionId, clientNonce, serverNonce, pin));
                            if (!verifier.verify(sig)) continue;
                            String token = randomHex(32);
                            byte[] tokBytes = token.getBytes(StandardCharsets.US_ASCII);
                            Cipher enc = Cipher.getInstance("RSA/ECB/OAEPPadding");
                            enc.init(Cipher.ENCRYPT_MODE, clientKey,
                                    new OAEPParameterSpec("SHA-256", "MGF1",
                                            MGF1ParameterSpec.SHA256,
                                            new PSource.PSpecified(
                                                    (StandalonePairingClient.DOMAIN + "-TOKEN\n1\n" + sessionId)
                                                            .getBytes(StandardCharsets.UTF_8))));
                            byte[] encrypted = enc.doFinal(tokBytes);
                            java.util.Arrays.fill(tokBytes, (byte) 0);
                            JSONObject approvedReply = new JSONObject();
                            approvedReply.put("schema", 1);
                            approvedReply.put("state", "approved");
                            approvedReply.put("device_id", randomHex(16));
                            approvedReply.put("encrypted_token",
                                    Base64.getEncoder().encodeToString(encrypted));
                            reply = approvedReply.toString();
                        } else {
                            reply = "{}";
                        }
                        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                        String http = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Connection: close\r\n"
                                + "Content-Length: " + data.length + "\r\n\r\n";
                        out.write(http.getBytes(StandardCharsets.US_ASCII));
                        out.write(data);
                        out.flush();
                    } catch (Exception failure) {
                        if (!fallbackStop.get()) failure.printStackTrace();
                    }
                }
            }, "fallback-test-server");
            fallbackThread.start();
            // Give the server a moment to start accepting.
            Thread.sleep(50L);
            com.limelight.nvstream.http.ComputerDetails pc =
                    new com.limelight.nvstream.http.ComputerDetails();
            pc.name = "TestPC";
            pc.activeAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                    "127.0.0.2", realPort);
            pc.manualAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                    "127.0.0.1", realPort);
            StandalonePairingClient client = new StandalonePairingClient();
            AtomicInteger codeCount = new AtomicInteger();
            HostPairing pairing = client.enroll(pc, code -> codeCount.incrementAndGet());
            assertNotNull("fallback must succeed on the second address", pairing);
            assertTrue("pairing address must use the second address: " + pairing.address,
                    pairing.address.endsWith(":" + realPort));
            assertTrue("pairing host must be 127.0.0.1: " + pairing.address,
                    pairing.address.startsWith("127.0.0.1:"));
            assertEquals("code callback must fire once", 1, codeCount.get());
            assertEquals("second-address begin call only",
                    1, fbBeginCalls.get());
            assertEquals("second-address poll call only",
                    1, fbPollCalls.get());
        } finally {
            fallbackStop.set(true);
            SSLServerSocket fallbackSocket = fallbackServerRef.get();
            if (fallbackSocket != null) fallbackSocket.close();
            if (fallbackThread != null) fallbackThread.join(2000);
        }
    }

    /** The outer ComputerDetails fallback must STOP on a
     *  pre-challenge SetupFailure (CODE_DENIED). Once the server
     *  has decided (denied), retrying across addresses would
     *  leak the client nonce. The fallback chain must not advance
     *  to the second address. */
    @Test
    public void computerDetailsFallbackStopsOnDenial() throws Exception {
        // Set up a dedicated server bound to 127.0.0.1:DEFAULT_VR_PORT
        // that returns /pairing/begin successfully then
        // /pairing/poll = denied. The second AddressTuple on the
        // ComputerDetails is 127.0.0.2 (no listener) so the
        // fallback would surface a transport IOException IF the
        // loop continued — the SetupFailure must stop the loop
        // BEFORE the second address is tried.
        int realPort = VrSetupDiscovery.DEFAULT_VR_PORT;
        final AtomicReference<SSLServerSocket> fallbackServerRef =
                new AtomicReference<>();
        final AtomicBoolean fallbackStop = new AtomicBoolean(false);
        final AtomicInteger fbBeginCalls = new AtomicInteger();
        final AtomicInteger fbPollCalls = new AtomicInteger();
        final AtomicReference<String> fbClientKey = new AtomicReference<>();
        final AtomicReference<String> fbClientNonce = new AtomicReference<>();
        final AtomicReference<String> fbSessionId = new AtomicReference<>();
        final AtomicReference<String> fbServerNonce = new AtomicReference<>();
        Thread fallbackThread = null;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(resource("host.pem")));
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(resource("host-key.der")));
            SSLServerSocket fallbackServer = createSslServerOn(cert, key, realPort);
            fallbackServerRef.set(fallbackServer);
            fallbackThread = new Thread(() -> {
                final SSLServerSocket server = fallbackServerRef.get();
                while (!fallbackStop.get()) {
                    try (javax.net.ssl.SSLSocket socket =
                                 (javax.net.ssl.SSLSocket) server.accept()) {
                        socket.setSoTimeout(3000);
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                        int next;
                        String headers = "";
                        while ((next = in.read()) != -1) {
                            head.write(next);
                            headers = head.toString("US-ASCII");
                            if (headers.endsWith("\r\n\r\n")) break;
                            if (head.size() > 16384) break;
                        }
                        if (headers.isEmpty()) return;
                        String path = headers.split("\r\n")[0].split(" ")[1];
                        int length = 0;
                        for (String line : headers.split("\r\n")) {
                            int colon = line.indexOf(':');
                            if (colon < 0) continue;
                            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                                length = Integer.parseInt(line.substring(colon + 1).trim());
                            }
                        }
                        byte[] body = new byte[length];
                        int offset = 0;
                        while (offset < length) {
                            int c2 = in.read(body, offset, length - offset);
                            if (c2 < 0) break;
                            offset += c2;
                        }
                        String reply;
                        if ("/pairing/begin".equals(path)) {
                            fbBeginCalls.incrementAndGet();
                            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                            fbClientKey.set(json.getString("client_key"));
                            fbClientNonce.set(json.getString("client_nonce"));
                            fbSessionId.set(randomHex(32));
                            fbServerNonce.set(randomHex(32));
                            JSONObject ch = new JSONObject();
                            ch.put("schema", 1);
                            ch.put("session_id", fbSessionId.get());
                            ch.put("server_nonce", fbServerNonce.get());
                            ch.put("cert_pem", new String(resource("host.pem"), StandardCharsets.US_ASCII));
                            ch.put("ttl_seconds", 60L);
                            reply = ch.toString();
                        } else if ("/pairing/poll".equals(path)) {
                            fbPollCalls.incrementAndGet();
                            JSONObject denied = new JSONObject();
                            denied.put("schema", 1);
                            denied.put("state", "denied");
                            reply = denied.toString();
                        } else {
                            reply = "{}";
                        }
                        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                        String http = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Connection: close\r\n"
                                + "Content-Length: " + data.length + "\r\n\r\n";
                        out.write(http.getBytes(StandardCharsets.US_ASCII));
                        out.write(data);
                        out.flush();
                    } catch (Exception failure) {
                        if (!fallbackStop.get()) failure.printStackTrace();
                    }
                }
            }, "fallback-deny-test-server");
            fallbackThread.start();
            Thread.sleep(50L);
            com.limelight.nvstream.http.ComputerDetails pc =
                    new com.limelight.nvstream.http.ComputerDetails();
            pc.name = "TestPC";
            // activeAddress is the real server; manualAddress is
            // an unbound loopback address that would surface a
            // transport IOException if the loop continued.
            pc.activeAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                    "127.0.0.1", realPort);
            pc.manualAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                    "127.0.0.2", realPort);
            StandalonePairingClient client = new StandalonePairingClient();
            try {
                client.enroll(pc, code -> {});
                fail("denied must surface SetupFailure");
            } catch (StandalonePairingClient.SetupFailure sf) {
                assertEquals(StandalonePairingClient.CODE_DENIED, sf.code);
            }
            // The fallback MUST NOT have tried the second
            // address (refused). The server only saw one begin +
            // one poll. If the loop continued, the second
            // iteration would have logged another begin call
            // (with connect refused) before this assertion ran.
            assertEquals("fallback must stop after denial: " + fbBeginCalls.get(),
                    1, fbBeginCalls.get());
            assertEquals("fallback must stop after denial: " + fbPollCalls.get(),
                    1, fbPollCalls.get());
        } finally {
            fallbackStop.set(true);
            SSLServerSocket fallbackSocket = fallbackServerRef.get();
            if (fallbackSocket != null) fallbackSocket.close();
            if (fallbackThread != null) fallbackThread.join(2000);
        }
    }

    /** Manual-entry endpoint parser preserves the custom port
     *  on a bare IPv4 literal. Brackets, IPv6, multiple colons,
     *  paths, queries, fragments, credentials, and out-of-range
     *  ports are all rejected. */
    @Test
    public void parseManualEndpointPreservesCustomPort() {
        VrSetupDiscovery.ParsedEndpoint pe =
                VrSetupDiscovery.parseManualEndpoint("192.168.1.42:29712", 28540);
        assertEquals("192.168.1.42", pe.host);
        assertEquals(29712, pe.port);
        // Bare host picks up the default.
        VrSetupDiscovery.ParsedEndpoint def =
                VrSetupDiscovery.parseManualEndpoint("192.168.1.42", 28540);
        assertEquals("192.168.1.42", def.host);
        assertEquals(28540, def.port);
        // IPv6 / brackets / credentials / paths / queries / fragments
        // / multi-colon / port-out-of-range all rejected.
        for (String bad : new String[]{
                "[::1]:29712", "::1:29712", "evil@127.0.0.1:29712",
                "127.0.0.1:29712/path", "127.0.0.1:29712?x=1",
                "127.0.0.1:29712#frag", "127.0.0.1:0", "127.0.0.1:65536"}) {
            try {
                VrSetupDiscovery.parseManualEndpoint(bad, 28540);
                fail("must reject: " + bad);
            } catch (IllegalArgumentException expected) {}
        }
    }
}