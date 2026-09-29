package com.vibertemis.quest.pcvr;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.json.JSONObject;

/** Imported trust for one PC. Never log or display the serialized pairing. */
public final class HostPairing {
  public static final int MAX_BYTES = 16384;
  public final String address, pin, token, certificate;

  private HostPairing(String address, String pin, String token, String certificate) {
    this.address = address;
    this.pin = pin;
    this.token = token;
    this.certificate = certificate;
  }

  public static HostPairing parse(String text) throws Exception {
    if (text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Pairing file is too large.");
    JSONObject json = new JSONObject(text.trim());
    String address = json.getString("host_address");
    URI uri = new URI("https://" + address);
    if (!address.matches("[A-Za-z0-9.\\-:\\[\\]]+")
        || uri.getHost() == null
        || uri.getPort() < 1
        || uri.getPort() > 65535
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null)
      throw new IllegalArgumentException("Pairing requires a PC address and port.");
    String pin = json.getString("certpin"),
        token = json.getString("token"),
        pem = json.getString("cert_pem");
    if (!pin.matches("[0-9a-f]{64}") || !token.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid pairing identity.");
    X509Certificate cert =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    cert.checkValidity();
    if (!pin.equals(hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))))
      throw new IllegalArgumentException("Pairing certificate does not match its identity.");
    return new HostPairing(address, pin, token, pem);
  }

  public URL endpoint(String path) throws Exception {
    if (!path.equals("/status") && !path.equals("/capabilities") && !path.equals("/start_pcvr"))
      throw new IllegalArgumentException("Unknown host action.");
    return new URL("https://" + address + path);
  }

  public String serialize() throws Exception {
    return new JSONObject()
        .put("host_address", address)
        .put("certpin", pin)
        .put("token", token)
        .put("cert_pem", certificate)
        .toString();
  }

  public static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2], digits = "0123456789abcdef".toCharArray();
    for (int i = 0; i < bytes.length; i++) {
      out[i * 2] = digits[(bytes[i] & 255) >>> 4];
      out[i * 2 + 1] = digits[bytes[i] & 15];
    }
    return new String(out);
  }
}
