package com.vibertemis.quest.pcvr;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class StandalonePairingTest {
    private JSONObject vector() throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/standalone-pairing-vector.json")) {
            assertNotNull(in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private byte[] decode(String value){return android.util.Base64.decode(value,android.util.Base64.NO_WRAP);}
    @Test public void androidMatchesGoComparisonAndSignature() throws Exception {
        JSONObject v=vector(),c=v.getJSONObject("challenge");
        String id=c.getString("session_id"),nonce=v.getString("client_nonce"),server=c.getString("server_nonce"),pin=v.getString("pin");
        assertEquals(v.getString("code"),StandalonePairingClient.comparisonCode(pin,v.getString("key_sha256"),nonce,server,id));
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(v.getString("client_key"))));
        Signature signature=Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));
        signature.initVerify(key);signature.update(StandalonePairingClient.transcript("POLL",id,nonce,server,pin));
        assertTrue(signature.verify(decode(v.getJSONObject("proof").getString("signature"))));
        signature.initVerify(key);signature.update(StandalonePairingClient.transcript("CANCEL",id,nonce,server,pin));
        assertFalse(signature.verify(decode(v.getJSONObject("proof").getString("signature"))));
    }
    @Test public void androidDecryptsGoOaepWithExplicitMgfAndLabel() throws Exception {
        JSONObject v=vector();String id=v.getJSONObject("challenge").getString("session_id");
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(v.getString("private_key"))));
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,
            new PSource.PSpecified((StandalonePairingClient.DOMAIN+"-TOKEN\n1\n"+id).getBytes(StandardCharsets.UTF_8))));
        byte[] encrypted=decode(v.getJSONObject("result").getString("encrypted_token"));
        assertEquals(v.getString("token"),new String(cipher.doFinal(encrypted),StandardCharsets.US_ASCII));
        cipher.init(Cipher.DECRYPT_MODE,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,new PSource.PSpecified("wrong".getBytes(StandardCharsets.UTF_8))));
        assertThrows(Exception.class,()->cipher.doFinal(encrypted));
    }
    @Test public void standaloneIdentitySurvivesSaveAndAddressChangeWithoutHostIdentity() throws Exception {
        JSONObject json=new JSONObject(HostClientTest.pairing("host",28540).serialize());
        String id="0123456789abcdef0123456789abcdef";
        json.put("device_id",id).put("pairing_kind","standalone");
        HostPairing pairing=HostPairing.parse(json.toString()).withAddress("vpn-host:28540");
        assertEquals(id,pairing.deviceId);assertEquals("",pairing.hostCertificatePin);assertEquals("",pairing.clientUuid);
        assertEquals("standalone",new JSONObject(pairing.serialize()).getString("pairing_kind"));
        json.put("host_cert_sha256","a".repeat(64));
        assertThrows(Exception.class,()->HostPairing.parse(json.toString()));
        json.remove("host_cert_sha256");json.put("pairing_kind","unknown");
        assertThrows(Exception.class,()->HostPairing.parse(json.toString()));
    }
}
