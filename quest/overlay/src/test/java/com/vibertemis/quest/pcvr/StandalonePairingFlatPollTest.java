package com.vibertemis.quest.pcvr;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Tests for the bounded flat {@link
 * StandalonePairingClient#parsePoll(byte[])} parser. Mirrors the
 * {@link StandalonePairingFlatChallengeTest} contract: known
 * scalar fields are strict (STRING / NUMBER only as documented),
 * unknown flat scalars (STRING / NUMBER / BOOLEAN / NULL) are
 * silently accepted so a future wire addition cannot turn an
 * otherwise valid poll into INVALID, and any nested object /
 * array at depth=1 is rejected.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class StandalonePairingFlatPollTest {

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** Unknown flat boolean scalar is accepted. */
    @Test public void unknownFlatBooleanScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"future_flag\":true}";
        StandalonePairingClient.ParsedPoll p =
                StandalonePairingClient.parsePoll(bytes(json));
        assertEquals("pending", p.state);
    }

    /** Unknown flat null scalar is accepted. */
    @Test public void unknownFlatNullScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"future_flag\":null}";
        StandalonePairingClient.ParsedPoll p =
                StandalonePairingClient.parsePoll(bytes(json));
        assertEquals("pending", p.state);
    }

    /** Unknown flat numeric scalar is accepted. */
    @Test public void unknownFlatNumberScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"future_flag\":42}";
        StandalonePairingClient.ParsedPoll p =
                StandalonePairingClient.parsePoll(bytes(json));
        assertEquals("pending", p.state);
    }

    /** Unknown flat string scalar is accepted. */
    @Test public void unknownFlatStringScalarIsAccepted() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"future_flag\":\"v\"}";
        StandalonePairingClient.ParsedPoll p =
                StandalonePairingClient.parsePoll(bytes(json));
        assertEquals("pending", p.state);
    }

    /** Nested object on an unknown field is rejected (depth=1). */
    @Test public void unknownNestedObjectIsRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"future_flag\":{\"k\":\"v\"}}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("nested object on unknown field must be rejected");
        } catch (Exception expected) {}
    }

    /** Known field strict: BOOLEAN schema is rejected. */
    @Test public void knownFieldBooleanSchemaRejected() throws Exception {
        String json = "{\"schema\":true,\"state\":\"pending\"}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("boolean schema must be rejected");
        } catch (Exception expected) {}
    }

    /** Known field strict: NULL schema is rejected. */
    @Test public void knownFieldNullSchemaRejected() throws Exception {
        String json = "{\"schema\":null,\"state\":\"pending\"}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("null schema must be rejected");
        } catch (Exception expected) {}
    }

    /** Known field strict: BOOLEAN state is rejected. */
    @Test public void knownFieldBooleanStateRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":true}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("boolean state must be rejected");
        } catch (Exception expected) {}
    }

    /** Known field strict: NULL state is rejected. */
    @Test public void knownFieldNullStateRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":null}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("null state must be rejected");
        } catch (Exception expected) {}
    }

    /** Known field strict: NULL device_id is rejected. */
    @Test public void knownFieldNullDeviceIdRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":\"approved\",\"device_id\":null}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("null device_id must be rejected");
        } catch (Exception expected) {}
    }

    /** Duplicate known keys are still rejected. */
    @Test public void duplicateKnownKeyIsRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\",\"state\":\"approved\"}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("duplicate state must be rejected");
        } catch (Exception expected) {}
    }

    /** Trailing tokens after the object are rejected. */
    @Test public void trailingTokensAreRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":\"pending\"}garbage";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("trailing tokens must be rejected");
        } catch (Exception expected) {}
    }

    /** Nested state value (object instead of string) is rejected. */
    @Test public void nestedStateObjectIsRejected() throws Exception {
        String json = "{\"schema\":1,\"state\":{\"v\":\"pending\"}}";
        try {
            StandalonePairingClient.parsePoll(bytes(json));
            fail("nested state must be rejected");
        } catch (Exception expected) {}
    }

    /** Empty body maps to INVALID. */
    @Test public void emptyBodyMapsToInvalid() throws Exception {
        try {
            StandalonePairingClient.parsePoll(new byte[0]);
            fail("empty body must be rejected");
        } catch (Exception expected) {}
    }

    /** Oversize body (> 16 KiB) is rejected without consuming a
     *  truncated prefix. */
    @Test public void oversizeBodyMapsToInvalid() throws Exception {
        byte[] big = new byte[StandalonePairingClient.MAX_SUCCESS_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        byte[] prefix = bytes("{\"schema\":1,\"state\":\"pending\"}");
        System.arraycopy(prefix, 0, big, 0, prefix.length);
        try {
            StandalonePairingClient.parsePoll(big);
            fail("oversize body must be rejected");
        } catch (Exception expected) {}
    }
}
