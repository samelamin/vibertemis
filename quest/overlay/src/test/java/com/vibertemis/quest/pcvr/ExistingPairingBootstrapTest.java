package com.vibertemis.quest.pcvr;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class ExistingPairingBootstrapTest {
    private static final String UUID="aabbccdd-1122-3344-5566-778899aabbcc";
    private static String hex(char c,int size) { return new String(new char[size]).replace('\0',c); }
    private JSONObject grant() throws Exception {
        return new JSONObject().put("schema",1).put("grant",hex('a',64))
            .put("client_nonce",hex('b',64)).put("host_cert_sha256",hex('c',64))
            .put("companion_cert_sha256",hex('d',64)).put("client_uuid",UUID)
            .put("port",28540).put("expires_unix",1800000000L);
    }
    @Test public void canonicalSignatureHasExactOrderAndNoTrailingNewline() {
        assertEquals("VIBERTEMIS-VR-ENROLL-1\nnonce\ngrant\nhost\ncompanion\nuuid",
            new String(ExistingPairingBootstrap.canonical("nonce","grant","host","companion","uuid"),StandardCharsets.UTF_8));
    }
    @Test public void grantRequiresNonceAndBothPinnedIdentities() throws Exception {
        ExistingPairingBootstrap.validateGrant(grant(),hex('b',64),hex('c',64));
        for(String field:new String[]{"client_nonce","host_cert_sha256","grant","companion_cert_sha256","client_uuid"}) {
            JSONObject g=grant().put(field,"wrong");
            assertThrows(Exception.class,()->ExistingPairingBootstrap.validateGrant(g,hex('b',64),hex('c',64)));
        }
        for(int port:new int[]{0,-1,65536}) {
            JSONObject g=grant().put("port",port);
            assertThrows(Exception.class,()->ExistingPairingBootstrap.validateGrant(g,hex('b',64),hex('c',64)));
        }
    }
    @Test public void redemptionMustMatchTheIssuedGrant() throws Exception {
        JSONObject g=grant();
        JSONObject response=new JSONObject().put("schema",1).put("device_id",hex('e',32))
            .put("host_cert_sha256",hex('c',64)).put("client_uuid",UUID).put("certpin",hex('d',64));
        ExistingPairingBootstrap.validateRedemption(response,g);
        for(String field:new String[]{"device_id","host_cert_sha256","client_uuid","certpin"}) {
            JSONObject changed=new JSONObject(response.toString()).put(field,"wrong");
            assertThrows(Exception.class,()->ExistingPairingBootstrap.validateRedemption(changed,g));
        }
    }
    @Test public void inheritedIdentitySurvivesAddressChangesAndRejectsPartialMetadata() throws Exception {
        HostPairing legacy=HostClientTest.pairing("host",28540);
        assertEquals("",HostPairing.parse(legacy.serialize()).deviceId);
        JSONObject data=new JSONObject(legacy.serialize()).put("device_id",hex('e',32));
        assertThrows(Exception.class,()->HostPairing.parse(data.toString()));
        data.put("host_cert_sha256",hex('c',64)).put("client_uuid",UUID);
        HostPairing inherited=HostPairing.parse(data.toString()).withAddress("other:28540");
        assertEquals(hex('e',32),inherited.deviceId);
        assertEquals(UUID,inherited.clientUuid);
        assertEquals(hex('c',64),inherited.hostCertificatePin);
    }
}
