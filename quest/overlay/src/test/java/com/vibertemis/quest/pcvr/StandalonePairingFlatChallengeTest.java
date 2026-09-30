package com.vibertemis.quest.pcvr;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Tests for the bounded flat challenge parser, TTL semantics, and
 * the new owner-controlled messages on
 * {@link StandalonePairingClient}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class StandalonePairingFlatChallengeTest {

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void ttlIsHonoredAndClamped() throws Exception {
        // Valid TTL 1..180 accepted.
        for (long t : new long[] { 1L, 60L, 180L }) {
            String json = "{\"schema\":1,\"session_id\":\""
                    + "a".repeat(64) + "\",\"server_nonce\":\""
                    + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                    + "\"ttl_seconds\":" + t + "}";
            StandalonePairingClient.ParsedChallenge p =
                    StandalonePairingClient.parseChallenge(bytes(json));
            assertEquals(t, p.ttlSeconds);
        }
        // Out-of-range TTL rejected.
        for (long t : new long[] { 0L, 181L, 1000L }) {
            String json = "{\"schema\":1,\"session_id\":\""
                    + "a".repeat(64) + "\",\"server_nonce\":\""
                    + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                    + "\"ttl_seconds\":" + t + "}";
            try {
                StandalonePairingClient.parseChallenge(bytes(json));
                fail("ttl " + t + " must be rejected");
            } catch (Exception expected) {}
        }
    }

    @Test public void missingBothTtlAndExpiresIsRejected() throws Exception {
        // No ttl_seconds, no expires_unix: cannot grant a lifetime.
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\"}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("missing lifetime must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void legacyExpiredExpiresUnixIsRejected() throws Exception {
        // expires_unix already in the past: must NOT silently
        // grant 1..180 seconds; the challenge is expired.
        long pastUnix = (System.currentTimeMillis() / 1000L) - 60L;
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"expires_unix\":" + pastUnix + "}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("expired challenge must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void legacyFreshExpiresUnixUsesDiff() throws Exception {
        // expires_unix 30s in the future: TTL = 30 (clamped to 1..180).
        long futureUnix = (System.currentTimeMillis() / 1000L) + 30L;
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"expires_unix\":" + futureUnix + "}";
        StandalonePairingClient.ParsedChallenge p =
                StandalonePairingClient.parseChallenge(bytes(json));
        assertTrue("ttl must be in 1..180: " + p.ttlSeconds,
                p.ttlSeconds >= 1L && p.ttlSeconds <= 180L);
    }

    @Test public void deepNestingIsRejected() throws Exception {
        // Deep nesting must NOT parse. The flat parser is strict
        // at depth=1.
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":{\"value\":60}}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("nested ttl_seconds must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void duplicateKnownKeysAreRejected() throws Exception {
        String json = "{\"schema\":1,\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("duplicate schema must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void trailingTokensAreRejected() throws Exception {
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}garbage";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("trailing tokens must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void stringSchemaisRejected() throws Exception {
        // schema must be numeric; string schema is rejected.
        String json = "{\"schema\":\"1\",\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("string schema must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void unknownWireCodeIsMappedToUnknown() {
        // The wire envelope parser is tested in
        // StandalonePairingErrorEnvelopeTest; here we verify the
        // message lookup is bounded.
        String m = StandalonePairingClient.messageForCode("BOGUS");
        assertEquals(StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_UNKNOWN), m);
    }

    @Test public void closedMessagePointsToSetupVr() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_CLOSED);
        assertTrue("CLOSED must reference Setup VR: " + m,
                m.toLowerCase().contains("setup vr"));
    }

    @Test public void rateLimitedMessageDoesNotReferencePcDialogReopen() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_RATE_LIMITED);
        assertFalse("must not say reopen: " + m, m.toLowerCase().contains("reopen"));
    }

    @Test public void deniedMessageIsRetryHereNotClosed() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_DENIED);
        assertTrue("DENIED must say retry here: " + m, m.toLowerCase().contains("retry here"));
    }

    /** Unknown flat scalar fields accept string, number, boolean,
     *  AND null — adding null to the accepted scalar list keeps
     *  forward compatibility with future additions to the wire
     *  envelope. The known fields (schema, session_id, ...) still
     *  reject null. */
    @Test public void unknownFlatBooleanScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60,\"future_flag\":true}";
        StandalonePairingClient.ParsedChallenge p =
                StandalonePairingClient.parseChallenge(bytes(json));
        assertEquals(60L, p.ttlSeconds);
    }

    @Test public void unknownFlatNullScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60,\"future_flag\":null}";
        StandalonePairingClient.ParsedChallenge p =
                StandalonePairingClient.parseChallenge(bytes(json));
        assertEquals(60L, p.ttlSeconds);
    }

    /** Known fields are strict: BOOLEAN and NULL are rejected for
     *  every typed field. A schema=42 vs schema=true vs schema=null
     *  must all surface CODE_INVALID. */
    @Test public void knownFieldBooleanSchemaRejected() throws Exception {
        String json = "{\"schema\":true,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("boolean schema must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void knownFieldNullSchemaRejected() throws Exception {
        String json = "{\"schema\":null,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("null schema must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void knownFieldBooleanSessionIdRejected() throws Exception {
        String json = "{\"schema\":1,\"session_id\":true,\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":\"PEM\","
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("boolean session_id must be rejected");
        } catch (Exception expected) {}
    }

    @Test public void knownFieldNullCertPemRejected() throws Exception {
        String json = "{\"schema\":1,\"session_id\":\""
                + "a".repeat(64) + "\",\"server_nonce\":\""
                + "b".repeat(64) + "\",\"cert_pem\":null,"
                + "\"ttl_seconds\":60}";
        try {
            StandalonePairingClient.parseChallenge(bytes(json));
            fail("null cert_pem must be rejected");
        } catch (Exception expected) {}
    }
}