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
  public final String deviceId, hostCertificatePin, clientUuid;

  private HostPairing(String address, String pin, String token, String certificate, String deviceId, String hostCertificatePin, String clientUuid) {
    this.deviceId=deviceId; this.hostCertificatePin=hostCertificatePin; this.clientUuid=clientUuid;
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
    String device = json.optString("device_id", ""), host = json.optString("host_cert_sha256", ""), uuid = json.optString("client_uuid", "");
    if (json.optString("pairing_kind", "").equals("standalone")) {
      if (!device.matches("[0-9a-f]{32}") || json.has("host_cert_sha256") || json.has("client_uuid"))
        throw new IllegalArgumentException("Invalid standalone VR pairing.");
    } else if (json.has("pairing_kind")) {
      throw new IllegalArgumentException("Unknown VR pairing kind.");
    } else if (json.has("device_id") || json.has("host_cert_sha256") || json.has("client_uuid")) {
      if (!device.matches("[0-9a-f]{32}") || !host.matches("[0-9a-f]{64}") || !uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
        throw new IllegalArgumentException("Incomplete VR device pairing.");
    }
    return new HostPairing(address, pin, token, pem, device, host, uuid);
  }

  public HostPairing withAddress(String endpoint) throws Exception {
    JSONObject copy = new JSONObject(serialize());
    copy.put("host_address", endpoint);
    return parse(copy.toString());
  }

  public URL endpoint(String path) throws Exception {
    if (!path.equals("/status") && !path.equals("/capabilities") && !path.equals("/start_pcvr"))
      throw new IllegalArgumentException("Unknown host action.");
    return new URL("https://" + address + path);
  }

  public String serialize() throws Exception {
    JSONObject json = new JSONObject()
        .put("host_address", address)
        .put("certpin", pin)
        .put("token", token)
        .put("cert_pem", certificate);
    if (!deviceId.isEmpty()) {
      json.put("device_id",deviceId);
      if (hostCertificatePin.isEmpty()) json.put("pairing_kind","standalone");
      else json.put("host_cert_sha256",hostCertificatePin).put("client_uuid",clientUuid);
    }
    return json.toString();
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
