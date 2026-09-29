package com.vibertemis.quest.update;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Exact-byte signed release metadata, shared with the Windows updater. */
public final class UpdateManifest {
    public static final String PROTOCOL = "20.14.1-vibertemis-pyro.1";
    public static final String PREFIX = "https://github.com/samelamin/vibertemis/releases/download/";
    public final long sequence, versionCode, bytes;
    public final String version, filename, url, sha256, packageName, signer;
    private UpdateManifest(JSONObject root) throws Exception {
        if (root.getInt("schema") != 1 || !root.getString("channel").equals("quest-preview")) fail("Wrong release channel/schema");
        if (!root.getString("native_protocol").equals(PROTOCOL)) fail("Update requires a different VR protocol; update both devices from the release page.");
        sequence = root.getLong("sequence"); version = root.getString("version");
        if (sequence <= 0 || !version.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")) fail("Invalid version");
        JSONObject apk = root.getJSONObject("assets").getJSONObject("android");
        filename = apk.getString("filename"); url = apk.getString("url"); bytes = apk.getLong("bytes");
        sha256 = apk.getString("sha256"); packageName = apk.getString("package");
        versionCode = apk.getLong("version_code"); signer = apk.getString("signer_sha256");
        if (!filename.equals("vibertemis-quest-preview-" + version + ".apk")
                || !url.equals(PREFIX + "quest-preview-v" + version + "/" + filename)) fail("Unexpected update location");
        if (bytes <= 0 || bytes > 768L*1024*1024 || versionCode <= 0
                || !sha256.matches("[a-f0-9]{64}") || !signer.matches("[a-f0-9]{64}")) fail("Invalid APK metadata");
    }
    public static UpdateManifest verify(byte[] body, byte[] signature, String trustedPem) throws Exception {
        if (body.length == 0 || body.length > 65536 || signature.length != 384) fail("Invalid signed update size");
        String encoded = trustedPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key); verifier.update(body);
        if (key.getModulus().bitLength() != 3072 || !verifier.verify(signature)) fail("Update signature verification failed");
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false); scan(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) fail("Trailing update metadata");
        }
        return new UpdateManifest(new JSONObject(json));
    }
    private static void scan(JsonReader r, int depth) throws Exception {
        if (depth > 12) fail("Metadata nesting too deep");
        switch (r.peek()) {
            case BEGIN_OBJECT:
                r.beginObject(); Set<String> names = new HashSet<>();
                while (r.hasNext()) { if (!names.add(r.nextName())) fail("Duplicate update field"); scan(r, depth+1); }
                r.endObject(); break;
            case BEGIN_ARRAY:
                r.beginArray(); while (r.hasNext()) scan(r, depth+1); r.endArray(); break;
            case NUMBER:
                // All numeric fields in schema 1 are exact non-fractional integers.
                Long.parseLong(r.nextString()); break;
            case STRING: r.nextString(); break;
            case BOOLEAN: r.nextBoolean(); break;
            case NULL: r.nextNull(); break;
            default: fail("Malformed update metadata");
        }
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static void fail(String message) throws IOException { throw new IOException(message); }
}
