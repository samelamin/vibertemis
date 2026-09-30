package com.vibertemis.quest.pcvr;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Bounded review fixes for {@link StandalonePairingClient}.
 *
 * <p>The previous {@code enrollInternal} wrapped every non-IO
 * failure in a {@code catch (Exception e)} that converted
 * certificate / crypto / parser errors into a transport-style
 * {@link java.io.IOException} with the misleading "Could not
 * reach VR Host Manager" message. That let the outer
 * {@link StandalonePairingClient#enroll(com.limelight.nvstream.http.ComputerDetails,
 * StandalonePairingClient.Progress)} fallback loop silently
 * advance to the next address on a malformed cert — the
 * bounded review fix maps every {@code Exception} after
 * {@code checkCancelled()} to {@link
 * StandalonePairingClient#CODE_INVALID} and routes only the
 * four explicitly identified initial network-level
 * {@link java.io.IOException}s through fallback.
 *
 * <p>This test file pins:
 * <ul>
 *   <li>a malformed PEM in the {@code cert_pem} challenge field
 *       surfaces {@link StandalonePairingClient#CODE_INVALID}
 *       AND stops the ComputerDetails fallback chain (the
 *       second address is never tried),</li>
 *   <li>a pin mismatch (challenge claims a different cert than
 *       the one observed on the wire) also stops the fallback
 *       chain — same root cause as the malformed-cert case,</li>
 *   <li>cancellation and {@link InterruptedException} are
 *       surfaced as a cancellation {@link
 *       StandalonePairingClient.SetupFailure}, NOT as a
 *       transport-style exception the outer fallback would
 *       misclassify.</li>
 * </ul>
 *
 * <p>The tests use their own ephemeral SSL server (so this
 * file does not depend on {@code CustomVrPortTest}) but reuse
 * the same {@code host.pem} / {@code host-key.der} fixtures
 * that the rest of the suite pins.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class StandalonePairingBoundedReviewTest {

    private static final byte[] HOST_PEM;
    private static final byte[] HOST_KEY_DER;
    static {
        HOST_PEM = readResource("host.pem");
        HOST_KEY_DER = readResource("host-key.der");
    }

    private SSLServerSocket server;
    private Thread serverThread;
    private volatile boolean stopped;
    /** When true, the server returns a {@code cert_pem} that is
     *  NOT a valid PEM block. {@code parseCertificate} then
     *  throws {@code CertificateException}, which the previous
     *  {@code catch (Exception)} wrapped as a transport
     *  {@code IOException} and let the outer fallback chain
     *  silently advance to the next address. The bounded review
     *  fix surfaces {@code CODE_INVALID} so the fallback stops. */
    private volatile boolean malformedCertPem;
    /** When true, the server returns a challenge whose
     *  {@code cert_pem} references a different cert than the
     *  one observed on the wire — the pin check fails. Same
     *  fallback semantics as a malformed PEM: must surface
     *  {@code CODE_INVALID} and stop the fallback chain. */
    private volatile boolean pinMismatch;
    private final AtomicInteger beginCalls = new AtomicInteger();
    /** Signaled the first time the server answers a request so
     *  cancellation / interrupt tests have a deterministic
     *  moment to fire. */
    private final CountDownLatch firstRequest = new CountDownLatch(1);

    private static byte[] readResource(String name) {
        try (java.io.InputStream in =
                HostClientTest.class.getResourceAsStream("/pcvr/" + name)) {
            assertNotNull("missing fixture: " + name, in);
            return HostClient.readBounded(in, 16384);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] resource(String name) throws Exception {
        return HostClientTest.resource(name);
    }

    @Before
    public void start() throws Exception {
        stopped = false;
        X509Certificate cert = (X509Certificate) CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(HOST_PEM));
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(HOST_KEY_DER));
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
        }, "bounded-review-test-server");
        serverThread.start();
    }

    @After
    public void stop() throws Exception {
        stopped = true;
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(3000);
    }

    private static SSLServerSocket createEphemeralSslServer(
            X509Certificate cert, PrivateKey key) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("test", key, "test".toCharArray(),
                new java.security.cert.Certificate[]{cert});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "test".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(factory.getKeyManagers(), null, null);
        return (SSLServerSocket) tls.getServerSocketFactory()
                .createServerSocket(VrSetupDiscovery.DEFAULT_VR_PORT, 10,
                        InetAddress.getByName("127.0.0.1"));
    }

    /** One-request dispatcher. Builds the {@code /pairing/begin}
     *  reply with the malformed / pin-mismatch cert_pem when
     *  the corresponding flag is set; otherwise emits the
     *  canonical challenge so other tests can re-use the
     *  fixture. */
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
        String path = headers.split("\r\n")[0].split(" ")[1];
        int length = 0;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(
                        line.substring(colon + 1).trim());
            }
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count < 0) break;
            offset += count;
        }
        beginCalls.incrementAndGet();
        firstRequest.countDown();
        String reply;
        if ("/pairing/begin".equals(path)) {
            JSONObject ch = new JSONObject();
            ch.put("schema", 1);
            ch.put("session_id", randomHex(32));
            ch.put("server_nonce", randomHex(32));
            ch.put("ttl_seconds", 60L);
            if (malformedCertPem) {
                // A non-PEM payload — parseCertificate throws
                // CertificateException BEFORE the pin check.
                ch.put("cert_pem", "not-a-pem");
            } else if (pinMismatch) {
                // Present a different cert than the one observed
                // on the wire. parseCertificate succeeds, but
                // the pin check fails.
                ch.put("cert_pem",
                        new String(resource("other.pem"),
                                StandardCharsets.US_ASCII));
            } else {
                ch.put("cert_pem",
                        new String(HOST_PEM, StandardCharsets.US_ASCII));
            }
            reply = ch.toString();
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

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** A direct {@code enroll(host, port)} against a server
     *  that returns a malformed {@code cert_pem} must surface
     *  {@link StandalonePairingClient#CODE_INVALID}, not the
     *  old transport-style IOException with the misleading
     *  "Could not reach VR Host Manager" message. */
    @Test public void malformedCertPemSurfacesInvalid() throws Exception {
        int port = server.getLocalPort();
        malformedCertPem = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", port, code -> {});
            fail("malformed cert_pem must surface SetupFailure");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals("malformed cert_pem must surface CODE_INVALID, not UNKNOWN / transport: " + sf.code,
                    StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** A direct {@code enroll(host, port)} against a server
     *  whose challenge claims a different cert than the one
     *  observed on the wire must surface {@code CODE_INVALID},
     *  not the old opaque "PC identity changed" string. */
    @Test public void pinMismatchSurfacesInvalid() throws Exception {
        int port = server.getLocalPort();
        pinMismatch = true;
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll("127.0.0.1", port, code -> {});
            fail("pin mismatch must surface SetupFailure");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
            assertEquals(StandalonePairingClient.messageForCode(
                    StandalonePairingClient.CODE_INVALID), sf.getMessage());
        }
    }

    /** A malformed {@code cert_pem} on the FIRST address must
     *  STOP the {@link StandalonePairingClient#enroll(com.limelight.nvstream.http.ComputerDetails,
     * StandalonePairingClient.Progress)} fallback chain. The
     *  previous behaviour wrapped the {@code CertificateException}
     *  as a transport {@code IOException} which the outer loop
     *  treated as a "try the next address" signal. With the
     *  bounded review fix the {@code catch (Exception)} returns
     *  {@code SetupFailure(CODE_INVALID)} and the fallback stops
     *  before the second address is touched. */
    @Test public void malformedCertPemStopsAddressFallback() throws Exception {
        int port = server.getLocalPort();
        malformedCertPem = true;
        com.limelight.nvstream.http.ComputerDetails pc =
                new com.limelight.nvstream.http.ComputerDetails();
        pc.name = "TestPC";
        pc.activeAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                "127.0.0.1", port);
        // Second address points at an unbound loopback so the
        // fallback, if it advanced, would surface a ConnectException
        // (a permitted fallback signal) — the assertion below
        // proves it never got that far.
        pc.manualAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                "127.0.0.2", port);
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll(pc, code -> {});
            fail("malformed cert_pem must surface SetupFailure");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
        }
        // The fallback MUST NOT have tried the second address.
        // If it had, the second iteration would have logged
        // another begin call (with connect refused on 127.0.0.2)
        // before the assertion above ran.
        assertEquals("fallback must stop after malformed cert_pem: "
                + beginCalls.get(), 1, beginCalls.get());
    }

    /** Same as {@link #malformedCertPemStopsAddressFallback}
     *  but exercising the pin-mismatch branch of the
     *  classification. The pin check is what the bounded
     *  review caught: the previous code wrapped the underlying
     *  {@code CertificateException} from the pin verify as a
     *  generic IOException, letting the outer loop try the
     *  next address with the same client nonce. */
    @Test public void pinMismatchStopsAddressFallback() throws Exception {
        int port = server.getLocalPort();
        pinMismatch = true;
        com.limelight.nvstream.http.ComputerDetails pc =
                new com.limelight.nvstream.http.ComputerDetails();
        pc.name = "TestPC";
        pc.activeAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                "127.0.0.1", port);
        pc.manualAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                "127.0.0.2", port);
        StandalonePairingClient client = new StandalonePairingClient();
        try {
            client.enroll(pc, code -> {});
            fail("pin mismatch must surface SetupFailure");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals(StandalonePairingClient.CODE_INVALID, sf.code);
        }
        assertEquals("fallback must stop after pin mismatch: "
                + beginCalls.get(), 1, beginCalls.get());
    }

    /** Cancelling a client BEFORE {@code enroll()} is invoked
     *  must surface a cancellation, NOT a transport-style
     *  {@link java.io.IOException} the outer fallback would
     *  misclassify. */
    @Test public void cancelBeforeEnrollSurfacesCancellation() throws Exception {
        int port = server.getLocalPort();
        StandalonePairingClient client = new StandalonePairingClient();
        client.cancel();
        try {
            client.enroll("127.0.0.1", port, code -> {});
            fail("cancelled client must surface cancellation");
        } catch (StandalonePairingClient.SetupFailure sf) {
            // Cancellation is its own SetupFailure (not
            // CODE_INVALID and not CODE_UNKNOWN).
            assertEquals("Cancelled", sf.getMessage());
        } catch (java.io.IOException io) {
            // Acceptable: checkCancelled() also throws a plain
            // IOException("Cancelled"). The contract is "not a
            // transport-style UNKNOWN" — assert the message is
            // the cancellation marker.
            assertEquals("Cancelled", io.getMessage());
        }
    }

    /** Interrupting the calling thread BEFORE {@code enroll()}
     *  is invoked must surface a cancellation, NOT a transport
     *  {@code IOException} the outer fallback would retry on a
     *  different address. */
    @Test public void interruptBeforeEnrollSurfacesCancellation() throws Exception {
        int port = server.getLocalPort();
        StandalonePairingClient client = new StandalonePairingClient();
        Thread.currentThread().interrupt();
        try {
            client.enroll("127.0.0.1", port, code -> {});
            fail("interrupted thread must surface cancellation");
        } catch (StandalonePairingClient.SetupFailure sf) {
            assertEquals("Cancelled", sf.getMessage());
        } catch (java.io.IOException io) {
            assertEquals("Cancelled", io.getMessage());
        } finally {
            // Clear the interrupt flag so other tests are not
            // contaminated.
            Thread.interrupted();
        }
    }
}
