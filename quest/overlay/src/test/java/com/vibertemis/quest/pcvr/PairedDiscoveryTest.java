package com.vibertemis.quest.pcvr;
import android.net.nsd.NsdServiceInfo;
import java.net.InetAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PairedDiscoveryTest {
    @Test public void endpointCanChangeButIdentityCannot() throws Exception {
        HostPairing paired=HostClientTest.pairing("host",28540);
        NsdServiceInfo service=new NsdServiceInfo();service.setHost(InetAddress.getByName("192.168.1.42"));service.setPort(28540);
        service.setAttribute("certpin",paired.pin);service.setAttribute("protocol","20.14.1-vibertemis-pyro.1");
        HostPairing candidate=PairedDiscovery.candidate(paired,service);
        assertNotNull(candidate);assertEquals("192.168.1.42:28540",candidate.address);
        assertEquals(paired.pin,candidate.pin);assertEquals(paired.token,candidate.token);assertEquals(paired.certificate,candidate.certificate);
        service.setAttribute("certpin","wrong");assertNull(PairedDiscovery.candidate(paired,service));
        service.setAttribute("certpin",paired.pin);service.setAttribute("protocol","wrong");assertNull(PairedDiscovery.candidate(paired,service));
    }
    @Test public void loopbackAndInvalidPortHintsRejected() throws Exception {
        HostPairing paired=HostClientTest.pairing("host",28540);NsdServiceInfo service=new NsdServiceInfo();
        service.setAttribute("certpin",paired.pin);service.setAttribute("protocol","20.14.1-vibertemis-pyro.1");
        service.setHost(InetAddress.getByName("127.0.0.1"));service.setPort(28540);assertNull(PairedDiscovery.candidate(paired,service));
        service.setHost(InetAddress.getByName("192.168.1.42"));service.setPort(0);
        assertThrows(IllegalArgumentException.class,()->PairedDiscovery.candidate(paired,service));
    }
}
