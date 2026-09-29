package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class HostClientTest {
  private SSLServerSocket server;
  private Thread serverThread;
  private volatile boolean stopped;
  private String response = "{\"state\":\"STARTED\",\"negotiated_codec\":\"\"}";
  private int status = 200;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile String signature, nonce, timestamp, method, path;
  private volatile byte[] received;

  static byte[] resource(String name) throws Exception {
    return HostClient.readBounded(HostClientTest.class.getResourceAsStream("/pcvr/" + name), 16384);
  }

  static HostPairing pairing(String certificate, int port) throws Exception {
    byte[] pem = resource(certificate + ".pem");
    X509Certificate cert =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(pem));
    return HostPairing.parse(
        new JSONObject()
            .put("host_address", "127.0.0.1:" + port)
            .put("cert_pem", new String(pem, StandardCharsets.US_ASCII))
            .put(
                "certpin",
                HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())))
            .put("token", String.join("", java.util.Collections.nCopies(64, "0")))
            .toString());
  }

  @Before
  public void startServer() throws Exception {
    X509Certificate cert =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(resource("host.pem")));
    PrivateKey key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(resource("host-key.der")));
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    keys.setKeyEntry(
        "test", key, "test".toCharArray(), new java.security.cert.Certificate[] {cert});
    KeyManagerFactory factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(keys, "test".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(factory.getKeyManagers(), null, null);
    server =
        (SSLServerSocket)
            tls.getServerSocketFactory()
                .createServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
    serverThread =
        new Thread(
            () -> {
              while (!stopped) {
                try (java.net.Socket socket = server.accept()) {
                  socket.setSoTimeout(3000);
                  java.io.InputStream in = socket.getInputStream();
                  java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                  int next;
                  String headers = "";
                  while ((next = in.read()) != -1) {
                    head.write(next);
                    headers = head.toString("US-ASCII");
                    if (headers.endsWith("\r\n\r\n")) break;
                    if (head.size() > 16384) throw new java.io.IOException();
                  }
                  if (headers.isEmpty()) continue;
                  String[] lines = headers.split("\r\n");
                  String[] request = lines[0].split(" ");
                  method = request[0];
                  path = request[1];
                  int length = 0;
                  for (String line : lines) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String name = line.substring(0, colon),
                        value = line.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
                    if (name.equalsIgnoreCase("X-Vq-Sig")) signature = value;
                    if (name.equalsIgnoreCase("X-Vq-Ts")) timestamp = value;
                    if (name.equalsIgnoreCase("X-Vq-Nonce")) nonce = value;
                  }
                  if (length > 16384) throw new java.io.IOException();
                  received = new byte[length];
                  int offset = 0;
                  while (offset < length) {
                    int count = in.read(received, offset, length - offset);
                    if (count < 0) throw new java.io.IOException();
                    offset += count;
                  }
                  calls.incrementAndGet();
                  byte[] data = response.getBytes(StandardCharsets.UTF_8);
                  String reply =
                      "HTTP/1.1 "
                          + status
                          + " Test\r\n"
                          + "Content-Type: application/json\r\n"
                          + "Connection: close\r\n"
                          + "Content-Length: "
                          + data.length
                          + "\r\nLocation: https://127.0.0.1:"
                          + server.getLocalPort()
                          + "/redirect-target\r\n\r\n";
                  socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().write(data);
                  socket.getOutputStream().flush();
                } catch (Exception failure) {
                  if (!stopped) failure.printStackTrace();
                }
              }
            });
    serverThread.start();
  }

  @After
  public void stop() throws Exception {
    stopped = true;
    if (server != null) server.close();
    if (serverThread != null) serverThread.join(4000);
  }

  @Test
  public void sharedGoWireVector() throws Exception {
    assertEquals(
        "8b47d46a1c346d86ce68b08118a63c95040fa919fd5a2f0ccef4fc0a185fc294",
        HostClient.signature(
            "POST",
            "/start_pcvr",
            String.join("", java.util.Collections.nCopies(64, "0")),
            1700000000L,
            "0123456789abcdef0123456789abcdef",
            "{\"role\":\"headset\",\"mode\":\"pcvr\"}".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void pinnedLeafWorksDespiteLocalhostSanAndSignsExactPayload() throws Exception {
    HostPairing pairing = pairing("host", server.getLocalPort());
    new HostClient().start(pairing, "AV1");
    assertEquals(1, calls.get());
    assertEquals("POST", method);
    assertEquals("/start_pcvr", path);
    JSONObject body = new JSONObject(new String(received, StandardCharsets.UTF_8));
    assertEquals("headset", body.getString("role"));
    assertEquals("pcvr", body.getString("mode"));
    assertEquals("AV1", body.getString("requested_codec"));
    assertFalse(body.getString("request_id").isEmpty());
    assertEquals(
        HostClient.signature(
            method, path, pairing.token, Long.parseLong(timestamp), nonce, received),
        signature);
  }

  @Test
  public void wrongPinnedLeafSendsNoRequest() throws Exception {
    try {
      new HostClient().start(pairing("other", server.getLocalPort()), "auto");
      fail();
    } catch (HostClient.Failure e) {
      assertEquals("IDENTITY", e.code);
    }
    assertEquals(0, calls.get());
  }

  @Test
  public void redirectsAreNeverFollowed() throws Exception {
    status = 302;
    try {
      new HostClient().start(pairing("host", server.getLocalPort()), "auto");
      fail();
    } catch (HostClient.Failure e) {
      assertEquals("REDIRECT", e.code);
    }
    assertEquals(1, calls.get());
  }

  @Test
  public void errorEnvelopeWithHttp200IsNotSuccess() throws Exception {
    response = "{\"state\":\"DENIED\",\"error\":\"AUTH_FAILED\"}";
    try {
      new HostClient().start(pairing("host", server.getLocalPort()), "auto");
      fail();
    } catch (HostClient.Failure e) {
      assertEquals("AUTH_FAILED", e.code);
    }
  }

  @Test
  public void cancelBeforeStartSendsNothing() throws Exception {
    HostClient client = new HostClient();
    client.cancel();
    try {
      client.start(pairing("host", server.getLocalPort()), "auto");
      fail();
    } catch (java.io.IOException expected) {
    }
    assertEquals(0, calls.get());
  }

  @Test
  public void pairingRejectsEndpointInjectionAndWrongIdentity() throws Exception {
    JSONObject valid = new JSONObject(pairing("host", server.getLocalPort()).serialize());
    for (String address :
        new String[] {
          "evil@127.0.0.1:28540",
          "127.0.0.1:28540/path",
          "127.0.0.1:28540?x=1",
          "127.0.0.1:0",
          "127.0.0.1:65536",
          "127.0.0.1:28540#x"
        }) {
      valid.put("host_address", address);
      try {
        HostPairing.parse(valid.toString());
        fail(address);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void travelPreservesStandardAndHomeChoices() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("vq_pcvr_options", 0).edit().clear().commit();
    PcvrOptions options = new PcvrOptions(context);
    options.standardCodec("AV1");
    options.pyro(true);
    options.travel(true);
    assertEquals("AV1", options.requestedCodec());
    assertTrue(options.pyro());
    options.travel(false);
    assertEquals("AV1", options.standardCodec());
  }
}
