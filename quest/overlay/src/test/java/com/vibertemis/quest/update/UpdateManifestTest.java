package com.vibertemis.quest.update;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UpdateManifestTest {
    private KeyPair key;
    @Before public void setup() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);key=generator.generateKeyPair();
    }
    private String pem() { return "-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----"; }
    private byte[] sign(byte[] body) throws Exception { Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(body);return s.sign(); }
    private byte[] body() throws Exception {
        JSONObject apk=new JSONObject().put("filename","vibertemis-quest-preview-0.1.0.5.apk")
            .put("url",UpdateManifest.PREFIX+"quest-preview-v0.1.0.5/vibertemis-quest-preview-0.1.0.5.apk")
            .put("bytes",8).put("sha256",UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest("original".getBytes(StandardCharsets.UTF_8))))
            .put("package","com.vibertemis.quest.preview.debug").put("version_code",5).put("signer_sha256",String.format("%064d",0));
        return new JSONObject().put("schema",1).put("channel","quest-preview").put("sequence",5).put("version","0.1.0.5")
            .put("native_protocol",UpdateManifest.PROTOCOL).put("assets",new JSONObject().put("android",apk)).toString().getBytes(StandardCharsets.UTF_8);
    }
    @Test public void validSignedManifestAccepted() throws Exception {
        byte[] b=body();assertEquals(5,UpdateManifest.verify(b,sign(b),pem()).versionCode);
    }
    @Test public void forgedAndModifiedSignatureRejected() throws Exception {
        byte[] b=body();assertThrows(IOException.class,()->UpdateManifest.verify(b,new byte[384],pem()));
        byte[] signature=sign(b);b[4]^=1;assertThrows(IOException.class,()->UpdateManifest.verify(b,signature,pem()));
    }
    @Test public void duplicateFieldsRejectedEvenWithValidSignature() throws Exception {
        String json=new String(body(),StandardCharsets.UTF_8).replace("\"schema\":1","\"schema\":1,\"schema\":1");
        byte[] b=json.getBytes(StandardCharsets.UTF_8);assertThrows(IOException.class,()->UpdateManifest.verify(b,sign(b),pem()));
    }
    @Test public void untrustedUrlRejectedEvenWithValidSignature() throws Exception {
        byte[] b=new String(body(),StandardCharsets.UTF_8).replace("github.com","evil.example").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->UpdateManifest.verify(b,sign(b),pem()));
    }
    @Test public void tamperedCacheRejected() throws Exception {
        byte[] b=body();UpdateManifest m=UpdateManifest.verify(b,sign(b),pem());File f=File.createTempFile("update","apk");
        try {
            java.nio.file.Files.write(f.toPath(),"original".getBytes(StandardCharsets.UTF_8));UpdateTransport.verifyFile(f,m);
            java.nio.file.Files.write(f.toPath(),"tampered".getBytes(StandardCharsets.UTF_8));assertThrows(IOException.class,()->UpdateTransport.verifyFile(f,m));
        } finally {f.delete();}
    }
}
