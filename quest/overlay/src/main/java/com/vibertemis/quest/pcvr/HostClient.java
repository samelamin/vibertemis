package com.vibertemis.quest.pcvr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;

/** One cancellable connection attempt. No global TLS changes; no redirects. */
public class HostClient {
  public static final class Failure extends Exception {
    public final String code;

    public Failure(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private volatile boolean cancelled;
  private HttpsURLConnection active;
  private String headsetHostname;

  public void setHeadsetHostname(String hostname) { headsetHostname = hostname; }

  public synchronized void cancel() {
    cancelled = true;
    if (active != null) active.disconnect();
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public static String signature(
      String method, String path, String token, long timestamp, String nonce, byte[] body)
      throws Exception {
    String canonical =
        method
            + "\n"
            + path
            + "\n"
            + timestamp
            + "\n"
            + nonce
            + "\n"
            + HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(body));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HostPairing.hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  private static void checkCertificate(HostPairing pairing, X509Certificate cert)
      throws CertificateException {
    try {
      cert.checkValidity();
      if (!pairing.pin.equals(
          HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))))
        throw new CertificateException("PC identity changed. Pair again.");
    } catch (CertificateException e) {
      throw e;
    } catch (Exception e) {
      throw new CertificateException("Cannot verify PC identity.", e);
    }
  }

  public JSONObject request(HostPairing pairing, String method, String path, byte[] body)
      throws Exception {
    if (cancelled) throw new IOException("Cancelled");
    URL url = pairing.endpoint(path);
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(
        null,
        new X509TrustManager[] {
          new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] c, String a)
                throws CertificateException {
              throw new CertificateException("Client certificate not supported");
            }

            public void checkServerTrusted(X509Certificate[] c, String a)
                throws CertificateException {
              if (c == null || c.length == 0) throw new CertificateException("Missing PC identity");
              checkCertificate(pairing, c[0]);
            }
          }
        },
        null);
    HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
    connection.setSSLSocketFactory(tls.getSocketFactory());
    connection.setHostnameVerifier(
        (host, session) -> {
          if (!url.getHost().equalsIgnoreCase(host)) return false;
          try {
            checkCertificate(pairing, (X509Certificate) session.getPeerCertificates()[0]);
            return true;
          } catch (Exception e) {
            return false;
          }
        });
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(8000);
    connection.setUseCaches(false);
    connection.setRequestMethod(method);
    long timestamp = System.currentTimeMillis() / 1000L;
    byte[] random = new byte[16];
    new SecureRandom().nextBytes(random);
    String nonce = HostPairing.hex(random);
    connection.setRequestProperty("X-Vq-Ts", Long.toString(timestamp));
    connection.setRequestProperty("X-Vq-Nonce", nonce);
    connection.setRequestProperty(
        "X-Vq-Sig", signature(method, path, pairing.token, timestamp, nonce, body));
    synchronized (this) {
      if (cancelled) throw new IOException("Cancelled");
      active = connection;
    }
    try {
      if (method.equals("POST")) {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json");
        try (java.io.OutputStream out = connection.getOutputStream()) {
          out.write(body);
        }
      }
      int status = connection.getResponseCode();
      if (status >= 300 && status < 400)
        throw new Failure("REDIRECT", "PC address redirected. Pair directly with your PC again.");
      InputStream stream =
          status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      if (stream == null)
        throw new Failure("RESPONSE", "PC returned an incomplete response. Retry the connection.");
      byte[] data = readBounded(stream, 32768);
      JSONObject result;
      try {
        result = new JSONObject(new String(data, StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new Failure(
            "RESPONSE", "PC returned an invalid response. Check the companion version.");
      }
      String error = result.optString("error", "");
      if (!error.isEmpty() && !error.equals("ALREADY_STARTING")) throw failure(error);
      if (status != 200)
        throw new Failure("RESPONSE", "PC could not complete the request. Retry the connection.");
      return result;
    } finally {
      connection.disconnect();
      synchronized (this) {
        if (active == connection) active = null;
      }
    }
  }

  public void start(HostPairing pairing, String codec) throws Exception {
    start(pairing, codec, headsetHostname);
  }

  public void start(HostPairing pairing, String codec, String hostname) throws Exception {
    if (hostname != null) {
      JSONObject capabilities = request(pairing, "GET", "/capabilities", new byte[0]);
      if (capabilities.optInt("sequence", 0) < 6
          || !"20.14.1-vibertemis-pyro.1".equals(capabilities.optString("native_protocol", ""))) {
        throw new Failure("HOST_UPDATE", "Update VibertemisVR Host Manager on Windows to preview6 or later, then choose Prepare VR once. Your pairing will be kept.");
      }
    }
    byte[] body =
        new JSONObject()
            .put("role", "headset")
            .put("client_hostname", hostname)
            .put("mode", "pcvr")
            .put("requested_codec", codec)
            .put("native_protocol", PcvrOptions.PYROWAVE_BUILD ? "20.14.1-vibertemis-pyro.1" : "")
            .put("request_id", UUID.randomUUID().toString())
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    JSONObject reply = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        reply = request(pairing, "POST", "/start_pcvr", body);
        break;
      } catch (javax.net.ssl.SSLException e) {
        throw new Failure("IDENTITY", "Could not verify this PC. Pair again on a trusted network.");
      } catch (IOException e) {
        if (cancelled || attempt == 1) throw e;
      }
    }
    String state = reply == null ? "" : reply.optString("state", "");
    if (state.equals("STARTED") || state.equals("ALREADY_RUNNING")) return;
    if (!state.equals("STARTING"))
      throw new Failure(
          "RESPONSE", "PC could not start SteamVR. Check the companion and ALVR setup.");
    long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
    while (!cancelled && android.os.SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(1000);
      JSONObject status = request(pairing, "GET", "/status", new byte[0]);
      if (status.opt("vrserver") instanceof Boolean && status.getBoolean("vrserver")) {
        // STARTING may belong to a different in-flight request. Re-submit
        // our signed intent so its codec is validated before entering VR.
        JSONObject confirmed = request(pairing, "POST", "/start_pcvr", body);
        String confirmedState = confirmed.optString("state", "");
        if (confirmedState.equals("STARTED") || confirmedState.equals("ALREADY_RUNNING")) return;
        throw new Failure("BUSY", "Another startup is still being checked. Retry in a moment.");
      }
    }
    if (cancelled) throw new IOException("Cancelled");
    throw new Failure(
        "TIMEOUT",
        "SteamVR has not started yet. Check your PC, then retry. Cancelling does not close SteamVR"
            + " on the PC.");
  }

  public static byte[] readBounded(InputStream stream, int max) throws IOException {
    try (InputStream in = stream;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[2048];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > max) throw new IOException("Response too large");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static Failure failure(String code) {
    switch (code) {
      case "HEADSET_NETWORK":
        return new Failure(code, "VR needs the same local network or an encrypted VPN between Quest and PC. Forwarded Vibeshine ports support flat-screen streaming only.");
      case "HEADSET_SETUP":
        return new Failure(code, "Close SteamVR and ALVR Dashboard on your PC, then reconnect once to register this headset. If this is your first connection, complete Prepare VR in the Windows manager.");
      case "AUTH_FAILED":
        return new Failure(
            code,
            "Pairing or PC clock does not match. Check both clocks, then pair again if needed.");
      case "RATE_LIMITED":
        return new Failure(code, "Too many connection attempts. Wait 30 seconds, then retry.");
      case "ALVR_CODEC":
        return new Failure(
            code,
            "Set the requested codec in the ALVR Dashboard, then reconnect. This host needs manual"
                + " codec configuration.");
      case "ALVR_MISSING":
      case "ALVR_SCHEMA":
      case "ALVR_VERSION":
      case "ALVR_UNREADABLE":
        return new Failure(
            code, "Check the matching ALVR installation and session file on your PC.");
      case "STEAMVR_LAUNCH":
        return new Failure(
            code,
            "Run the companion from your Windows desktop and check that SteamVR is installed.");
      default:
        return new Failure(
            code,
            "The PC rejected this connection. Check the companion, then retry or pair again.");
    }
  }
}
