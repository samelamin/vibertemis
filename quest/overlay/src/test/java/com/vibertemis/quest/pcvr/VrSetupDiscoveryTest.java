package com.vibertemis.quest.pcvr;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link VrSetupDiscovery}. The driver is injected so
 * the tests run without the Android system service.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VrSetupDiscoveryTest {

    /** Test driver that hands resolve callbacks synchronously. */
    static class FakeDriver implements VrSetupDiscovery.BrowseDriver {
        final List<NsdServiceInfo> services = new ArrayList<>();
        final AtomicInteger resolveCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        boolean startFailed;
        /**
         * When true (default), {@link #start(NsdManager.DiscoveryListener)}
         * fires {@code onDiscoveryStopped} after delivering the queued
         * services so the production browse loop exits via the polite
         * shutdown path instead of waiting on the 8 s budget. The real
         * {@link android.net.nsd.NsdManager} fires this callback when
         * discovery tears down; a fake driver that delivers results but
         * never fires onDiscoveryStopped is misbehaving — the bounded
         * 8 s budget is the safety net for misbehaving drivers, not the
         * primary exit path. Tests that intentionally drive the budget
         * (cancel races, malicious bursts) set this to false and pair
         * it with a real-time {@link com.vibertemis.quest.pcvr.VrSetupDiscovery.Clock}.
         */
        boolean selfClose = true;
        FakeDriver() {}
        @Override public void start(NsdManager.DiscoveryListener listener) {
            if (startFailed) {
                listener.onStartDiscoveryFailed("_vibertemis-vr._tcp.", 0);
                return;
            }
            for (NsdServiceInfo s : services) listener.onServiceFound(s);
            if (selfClose) {
                listener.onDiscoveryStopped(VrSetupDiscovery.SERVICE);
            }
        }
        @Override public void stop(NsdManager.DiscoveryListener listener) { stopCalls.incrementAndGet(); }
        @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {
            resolveCalls.incrementAndGet();
            rl.onServiceResolved(info);
        }
    }

    static NsdServiceInfo fakeService(String name, InetAddress host, int port) throws Exception {
        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceName(name);
        s.setHost(host);
        s.setPort(port);
        s.setAttribute("protocol", VrSetupDiscovery.PROTOCOL);
        return s;
    }

    /** A normal PC hostname like "My Gaming PC" must survive the
     *  bidi / control sanitiser EXACTLY. Spaces, letters, digits,
     *  and dashes are preserved verbatim. */
    @Test public void sanitizePreservesNormalPcNameExactly() {
        assertEquals("My Gaming PC", VrSetupDiscovery.sanitizeName("My Gaming PC"));
        assertEquals("Workstation-01", VrSetupDiscovery.sanitizeName("Workstation-01"));
        assertEquals("Steves-PC", VrSetupDiscovery.sanitizeName("Steves-PC"));
        assertEquals("Батарея 01", VrSetupDiscovery.sanitizeName("Батарея 01"));
    }

    /** Bidi override / format / control characters are replaced
     *  with {@code _}; the rest of the name survives. */
    @Test public void sanitizeStripsBidiAndControlCharacters() {
        assertEquals("PC____name", VrSetupDiscovery.sanitizeName("PC\u202E\u202D\u200B\u200Fname"));
        // \u0007 (BEL) and \u0008 (BS) are both ISO control characters;
        // each is independently replaced with one underscore. Two
        // prohibited characters in the input produce two underscores
        // in the output.
        assertEquals("host__01", VrSetupDiscovery.sanitizeName("host\u0007\u000801"));
        // \uFEFF (BOM) and \u200E (LRM) are both FORMAT category
        // characters; each independently yields one underscore.
        // Two prohibited characters → two underscores.
        assertEquals("_begin_", VrSetupDiscovery.sanitizeName("\uFEFFbegin\u200E"));
    }

    /** The sanitiser caps the output length at {@link
     *  VrSetupDiscovery#MAX_NAME_LENGTH}. */
    @Test public void sanitizeBoundsLength() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 256; i++) raw.append('A');
        String out = VrSetupDiscovery.sanitizeName(raw.toString());
        assertEquals(VrSetupDiscovery.MAX_NAME_LENGTH, out.length());
    }

    /** IPv4 RFC1918 addresses are accepted. Loopback, multicast,
     *  any-local and IPv6 are rejected. */
    @Test public void privateAddressFilterIsIpv4Only() throws Exception {
        assertTrue(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("192.168.1.42")));
        assertTrue(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("10.0.0.5")));
        assertTrue(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("172.16.0.1")));
        assertTrue(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("169.254.10.20")));
        assertFalse(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("0.0.0.0")));
        assertFalse(VrSetupDiscovery.isPrivateAddress(InetAddress.getByName("8.8.8.8")));
    }

    /** sanitize filters out loopback, multicast, public, and
     *  IPv6 hosts. */
    @Test public void sanitizeRejectsInvalidCandidates() throws Exception {
        NsdServiceInfo loopback = fakeService("loopback", InetAddress.getByName("127.0.0.1"), 28540);
        NsdServiceInfo publicAddr = fakeService("public", InetAddress.getByName("8.8.8.8"), 28540);
        NsdServiceInfo v6 = fakeService("v6", InetAddress.getByName("fe80::1"), 28540);
        NsdServiceInfo ok = fakeService("ok", InetAddress.getByName("192.168.1.42"), 28540);
        assertNull(VrSetupDiscovery.sanitize(loopback));
        assertNull(VrSetupDiscovery.sanitize(publicAddr));
        // IPv6 services hit the port check too; the host cast to
        // Inet4Address fails first.
        assertNull(VrSetupDiscovery.sanitize(v6));
        VrSetupDiscovery.Candidate c = VrSetupDiscovery.sanitize(ok);
        assertNotNull(c);
        assertEquals("192.168.1.42", c.address);
        assertEquals(28540, c.port);
    }

    /** Protocol mismatch is silently filtered; the TXT pin never
     *  shortcuts trust. */
    @Test public void sanitizeRejectsProtocolMismatchAndIgnoresTxtPin() throws Exception {
        NsdServiceInfo wrongProto = fakeService("wrong", InetAddress.getByName("192.168.1.42"), 28540);
        wrongProto.setAttribute("protocol", "attacker-protocol");
        wrongProto.setAttribute("certpin", "0123456789abcdef".repeat(4));
        assertNull("protocol mismatch must filter", VrSetupDiscovery.sanitize(wrongProto));
    }

    /** Cancel after the poll is already waiting must stop discovery
     *  within the cancel poll window, not after the full 8-second
     *  browse budget. The poll uses a short bounded wait
     *  unconditionally so cancellation never pins the caller. */
    @Test public void cancelDuringBrowseReturnsPromptly() throws Exception {
        FakeDriver driver = new FakeDriver() {
            @Override public void start(NsdManager.DiscoveryListener listener) {
                // Do nothing: drive the poll timeout path.
            }
            @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {
                // never called
            }
        };
        VrSetupDiscovery discovery = new VrSetupDiscovery(() -> driver);
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                long start = SystemClock.elapsedRealtime();
                discovery.browse();
                long elapsed = SystemClock.elapsedRealtime() - start;
                assertTrue("cancel must return promptly (was " + elapsed + "ms)",
                        elapsed < 2000L);
            } catch (Exception e) { fail(); }
            cancelled.countDown();
        });
        worker.start();
        Thread.sleep(150L);
        discovery.cancel();
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        assertTrue("stop must be called", driver.stopCalls.get() >= 1);
    }

    /** A malicious burst of uniquely-named invalid services is
     *  bounded by admission at ingress, so a hostile NSD feed
     *  cannot grow the pending / seen sets unbounded. */
    @Test public void maliciousInvalidBurstIsBounded() throws Exception {
        FakeDriver driver = new FakeDriver();
        for (int i = 0; i < 200; i++) {
            driver.services.add(fakeService("malicious-" + i,
                    InetAddress.getByName("127.0.0.1"), 28540));
        }
        VrSetupDiscovery discovery = new VrSetupDiscovery(() -> driver);
        long start = SystemClock.elapsedRealtime();
        List<VrSetupDiscovery.Candidate> found = discovery.browse();
        long elapsed = SystemClock.elapsedRealtime() - start;
        // Admission is capped at MAX_SERVICES (16). The test
        // exercises that the bound holds even with 200 unique
        // bogus service names.
        assertTrue("malicious burst must return promptly: " + elapsed + "ms",
                elapsed < 4000L);
        // Every candidate is null (loopback is invalid); the size
        // is bounded by MAX_SERVICES at the admit counter.
        assertTrue("invalid candidates must be bounded",
                found.size() <= VrSetupDiscovery.MAX_SERVICES);
        // The resolve attempt counter must not exceed the cap.
        assertTrue("resolve attempts must be bounded",
                driver.resolveCalls.get() <= VrSetupDiscovery.MAX_SERVICES);
    }

    /** Manual entry: default port is 28540 when no port supplied;
     *  explicit ports are validated; URI components, IPv6,
     *  brackets, credentials, and out-of-range ports are rejected. */
    @Test public void parseManualEndpointValidatesShape() {
        VrSetupDiscovery.ParsedEndpoint def =
                VrSetupDiscovery.parseManualEndpoint("192.168.1.10", VrSetupDiscovery.DEFAULT_VR_PORT);
        assertEquals("192.168.1.10", def.host);
        assertEquals(VrSetupDiscovery.DEFAULT_VR_PORT, def.port);
        VrSetupDiscovery.ParsedEndpoint custom =
                VrSetupDiscovery.parseManualEndpoint("192.168.1.10:30000", VrSetupDiscovery.DEFAULT_VR_PORT);
        assertEquals("192.168.1.10", custom.host);
        assertEquals(30000, custom.port);
        // Hostname is also accepted (RFC-952 letters, digits, dot, hyphen).
        VrSetupDiscovery.ParsedEndpoint name =
                VrSetupDiscovery.parseManualEndpoint("my-pc.local:28540", VrSetupDiscovery.DEFAULT_VR_PORT);
        assertEquals("my-pc.local", name.host);
        assertEquals(28540, name.port);
        // Reject: credentials, URI scheme, path, query, fragment,
        // port out of range, brackets, IPv6 literals (multiple
        // colons), leading/trailing colon.
        for (String bad : new String[]{
                "evil@127.0.0.1:28540",
                "https://host:28540",
                "127.0.0.1:28540/path",
                "127.0.0.1:28540?x=1",
                "127.0.0.1:28540#frag",
                "127.0.0.1:0",
                "127.0.0.1:65536",
                "[::1]:28540",
                "::1:28540",
                "127.0.0.1:28540:1",
                ":28540",
                "host:",
                "-leading:28540",
                "trailing-:28540"}) {
            try {
                VrSetupDiscovery.parseManualEndpoint(bad, 28540);
                fail("must reject: " + bad);
            } catch (IllegalArgumentException expected) {}
        }
    }
}