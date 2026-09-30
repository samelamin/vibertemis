package com.vibertemis.quest.pcvr;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Bounded, flat error-envelope parser tests for
 * {@link StandalonePairingClient}.
 *
 * <p>The parser must accept the documented shapes:
 * <ul>
 *   <li>{@code {"error": "...", "code": "<KNOWN>"}}</li>
 *   <li>{@code {"error": "..."}} (legacy preview8 without code)</li>
 * </ul>
 * and reject anything else by mapping to
 * {@link StandalonePairingClient#CODE_UNKNOWN} with the
 * first-time-neutral fallback message. The 16 KiB cap is enforced by
 * {@code errorFromBody}; an oversize body must NOT silently parse a
 * truncated prefix.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class StandalonePairingErrorEnvelopeTest {

    private static String codeOf(byte[] body) {
        return StandalonePairingClient.errorFromBody(body).code;
    }
    private static String msgOf(byte[] body) {
        return StandalonePairingClient.errorFromBody(body).getMessage();
    }
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
    private static String unknown() {
        return StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_UNKNOWN);
    }

    /** Known code + error pair: the wire code round-trips through
     *  the parser and the surfaced message is the local whitelist
     *  (NOT the server's `error` text). */
    @Test public void knownCodeAndErrorPairYieldsWhitelistedMessage() {
        byte[] body = bytes("{\"error\":\"server text we must not surface\",\"code\":\"BUSY\"}");
        StandalonePairingClient.SetupFailure f = StandalonePairingClient.errorFromBody(body);
        assertEquals(StandalonePairingClient.CODE_BUSY, f.code);
        assertEquals(StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_BUSY), f.getMessage());
        assertNotEquals("server-supplied text must never be surfaced", "server text we must not surface", f.getMessage());
    }

    /** Legacy preview8 envelope: no `code`. The code path must NOT
     *  echo the server's text and must NOT pretend the user has a
     *  prior pairing (the first-time-neutral fallback). */
    @Test public void legacyEnvelopeWithoutCodeMapsToUnknown() {
        byte[] body = bytes("{\"error\":\"legacy preview8 text\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
        assertEquals(unknown(), msgOf(body));
    }

    /** Empty body: UNKNOWN, fallback. */
    @Test public void emptyBodyMapsToUnknown() {
        byte[] body = bytes("");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
        assertEquals(unknown(), msgOf(body));
    }

    /** Null body: UNKNOWN, fallback. */
    @Test public void nullBodyMapsToUnknown() {
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(null));
    }

    /** Malformed JSON (not an object): UNKNOWN, fallback. */
    @Test public void malformedJsonMapsToUnknown() {
        byte[] body = bytes("not json at all");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Top-level array: UNKNOWN, fallback (only top-level objects
     *  are accepted). */
    @Test public void topLevelArrayMapsToUnknown() {
        byte[] body = bytes("[{\"code\":\"BUSY\"}]");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Top-level string: UNKNOWN, fallback. */
    @Test public void topLevelStringMapsToUnknown() {
        byte[] body = bytes("\"BUSY\"");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Nested `code` value (object instead of string): UNKNOWN.
     *  Any non-string value at depth=1 is rejected. */
    @Test public void nestedCodeObjectMapsToUnknown() {
        byte[] body = bytes("{\"code\":{\"value\":\"BUSY\"}}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Deeply-nested `code` (an array of arrays): UNKNOWN.
     *  Even if the wire shape did pass the depth=1 limit, the
     *  JsonReader's strict nesting forces a parse error. */
    @Test public void deeplyNestedCodeMapsToUnknown() {
        byte[] body = bytes("{\"code\":[[\"BUSY\"]]}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Unknown `code` value outside the whitelist: UNKNOWN.
     *  The whitelist maps anything that is not one of the seven
     *  stable codes to UNKNOWN. */
    @Test public void unknownCodeValueMapsToUnknown() {
        byte[] body = bytes("{\"error\":\"x\",\"code\":\"NOT_A_REAL_CODE\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Missing `code` field entirely (no `code` key, only `error`):
     *  UNKNOWN, fallback. The legacy preview8 envelope is
     *  intentionally not given a special path; it must fall
     *  through to UNKNOWN. */
    @Test public void missingCodeFieldMapsToUnknown() {
        byte[] body = bytes("{\"error\":\"some server text\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
        assertEquals(unknown(), msgOf(body));
    }

    /** Duplicate `code` keys: UNKNOWN. A duplicate-key attempt is
     *  a tampering signal; reject the entire envelope. */
    @Test public void duplicateCodeKeyMapsToUnknown() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"code\":\"RATE_LIMITED\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Duplicate `error` keys: UNKNOWN. */
    @Test public void duplicateErrorKeyMapsToUnknown() {
        byte[] body = bytes("{\"error\":\"x\",\"error\":\"y\",\"code\":\"BUSY\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Duplicate known fields in different order: UNKNOWN. */
    @Test public void duplicateKnownFieldsInvertedOrderMapsToUnknown() {
        byte[] body = bytes("{\"error\":\"x\",\"code\":\"BUSY\",\"error\":\"y\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Duplicate UNKNOWN keys are also rejected: a duplicate-key
     *  attempt against any flat key is a tampering signal. */
    @Test public void duplicateUnknownKeyMapsToUnknown() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":\"a\",\"extra\":\"b\"}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** The bounded-set cap rejects more than
     *  {@link StandalonePairingClient#MAX_ERROR_KEYS} unique
     *  flat keys in a single envelope. A hostile peer cannot
     *  inflate the set inside the 16 KiB body budget. */
    @Test public void tooManyUniqueKeysMapsToUnknown() {
        StringBuilder sb = new StringBuilder("{\"code\":\"BUSY\"");
        // MAX_ERROR_KEYS is the documented bound; adding one
        // more unique key past it must trip the rejection.
        for (int i = 0; i <= StandalonePairingClient.MAX_ERROR_KEYS; i++) {
            sb.append(",\"k").append(i).append("\":1");
        }
        sb.append('}');
        byte[] body = bytes(sb.toString());
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Exactly at the cap is accepted: the boundary is INCLUSIVE
     *  at {@link StandalonePairingClient#MAX_ERROR_KEYS} unique
     *  keys (the cap counts the documented `code` key plus
     *  {@code MAX_ERROR_KEYS - 1} additional unique keys). */
    @Test public void exactlyAtCapIsAccepted() {
        StringBuilder sb = new StringBuilder("{\"code\":\"BUSY\"");
        for (int i = 0; i < StandalonePairingClient.MAX_ERROR_KEYS - 1; i++) {
            sb.append(",\"k").append(i).append("\":1");
        }
        sb.append('}');
        byte[] body = bytes(sb.toString());
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Numeric `code` value: UNKNOWN. Only string values are
     *  accepted. */
    @Test public void numericCodeMapsToUnknown() {
        byte[] body = bytes("{\"code\":1}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Boolean `code` value: UNKNOWN. */
    @Test public void booleanCodeMapsToUnknown() {
        byte[] body = bytes("{\"code\":true}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Null `code` value: UNKNOWN. */
    @Test public void nullCodeMapsToUnknown() {
        byte[] body = bytes("{\"code\":null}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Trailing tokens after the object: UNKNOWN. The parser is
     *  strict and must reject any non-empty suffix. */
    @Test public void trailingTokensMapToUnknown() {
        byte[] body = bytes("{\"code\":\"BUSY\"}garbage");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Leading whitespace is accepted because JsonReader strips
     *  whitespace before the document root. Trailing whitespace is
     *  also accepted because JsonReader strips it after the closing
     *  brace. The parser contract is therefore: whitespace at the
     *  document boundary is OK, but non-whitespace trailing tokens
     *  are rejected. */
    @Test public void leadingAndTrailingWhitespaceAccepted() {
        byte[] leading = bytes("   {\"code\":\"BUSY\"}");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(leading));
        byte[] trailing = bytes("{\"code\":\"BUSY\"}   ");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(trailing));
    }

    /** Oversize body (greater than 16 KiB): the parser MUST return
     *  UNKNOWN without consuming the truncated prefix as a valid
     *  envelope. The {@code errorFromBody} contract is that an
     *  oversize body is treated as unknown so a hostile server
     *  cannot deliver a valid `code` prefix with a junk suffix. */
    @Test public void oversizeBodyMapsToUnknown() {
        byte[] big = new byte[StandalonePairingClient.MAX_ERROR_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        // Place a valid envelope at the start; the cap must still
        // reject the body so a hostile server cannot slip a known
        // code past the size limit.
        byte[] prefix = bytes("{\"code\":\"BUSY\"}");
        System.arraycopy(prefix, 0, big, 0, prefix.length);
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(big));
    }

    /** Exactly-at-the-cap body (16 KiB) is accepted: the parser
     *  boundary is INCLUSIVE at 16 KiB. */
    @Test public void bodyAtExactlyCapIsAccepted() {
        byte[] envelope = bytes("{\"error\":\"silent\",\"code\":\"BUSY\"}");
        byte[] padding = new byte[StandalonePairingClient.MAX_ERROR_BODY_BYTES - envelope.length];
        java.util.Arrays.fill(padding, (byte) ' ');
        byte[] body = new byte[StandalonePairingClient.MAX_ERROR_BODY_BYTES];
        System.arraycopy(envelope, 0, body, 0, envelope.length);
        System.arraycopy(padding, 0, body, envelope.length, padding.length);
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Extra unknown flat string fields are silently accepted.
     *  Only nested structures and duplicate known keys are
     *  rejected. */
    @Test public void extraFlatStringFieldIsAccepted() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":\"ignored\"}");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Unknown flat scalar fields accept string, number, boolean,
     *  AND null — adding null to the accepted scalar list keeps
     *  forward compatibility with future additions to the wire
     *  envelope. The known fields (code, error) still reject null;
     *  see {@link #nullCodeMapsToUnknown}. */
    @Test public void extraFlatNullScalarIsAccepted() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":null}");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Unknown flat boolean scalar is accepted. */
    @Test public void extraFlatBooleanScalarIsAccepted() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":true}");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Unknown flat numeric scalar is accepted. */
    @Test public void extraFlatNumberScalarIsAccepted() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":42}");
        assertEquals(StandalonePairingClient.CODE_BUSY, codeOf(body));
    }

    /** Extra non-string field at depth=1 (an object value): UNKNOWN.
     *  The strict flat parser rejects any nested shape even when
     *  the known `code` field is present. */
    @Test public void extraNestedFieldMapsToUnknown() {
        byte[] body = bytes("{\"code\":\"BUSY\",\"extra\":{\"k\":\"v\"}}");
        assertEquals(StandalonePairingClient.CODE_UNKNOWN, codeOf(body));
    }

    /** Every stable code maps to a non-empty whitelisted message.
     *  The user must never see a blank or generic message in
     *  production. */
    @Test public void everyKnownCodeHasAMessage() {
        String[] codes = {
                StandalonePairingClient.CODE_CLOSED,
                StandalonePairingClient.CODE_EXPIRED,
                StandalonePairingClient.CODE_BUSY,
                StandalonePairingClient.CODE_RATE_LIMITED,
                StandalonePairingClient.CODE_INVALID,
                StandalonePairingClient.CODE_CAPACITY,
                StandalonePairingClient.CODE_STORAGE,
        };
        for (String c : codes) {
            String m = StandalonePairingClient.messageForCode(c);
            assertNotNull("missing message for " + c, m);
            assertFalse("empty message for " + c, m.isEmpty());
            assertFalse("first-time-neutral must not claim a prior pairing for " + c + ": " + m,
                    m.contains("previous") || m.contains("previously paired"));
        }
    }

    /** The RATE_LIMITED message must instruct the user to wait,
     *  retry from the headset, and must NOT tell the user to
     *  reopen / reset the PC Pair headset dialog: that would
     *  invite the user to chase a dialog that does not need to
     *  be touched while the throttle ticks down. */
    @Test public void rateLimitedMessageTellsUserToWaitThenRetryHere() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_RATE_LIMITED);
        assertTrue("RATE_LIMITED must mention wait: " + m,
                m.toLowerCase().contains("wait"));
        assertTrue("RATE_LIMITED must say retry here: " + m,
                m.toLowerCase().contains("retry here"));
        assertFalse("RATE_LIMITED must NOT tell the user to reopen the PC dialog: " + m,
                m.toLowerCase().contains("reopen"));
    }

    /** The CLOSED message must reference Setup VR (the switch the
     *  user has to flip on the PC), not the legacy "Pair headset"
     *  dialog, because in the seamless standalone flow the open
     *  state is owned by Setup VR. */
    @Test public void closedMessagePointsToSetupVr() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_CLOSED);
        assertTrue("CLOSED must reference Setup VR: " + m,
                m.toLowerCase().contains("setup vr"));
    }

    /** The EXPIRED message must keep the action on the headset
     *  side ("Retry here"), not point at a PC dialog. */
    @Test public void expiredMessagePointsAtRetryHere() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_EXPIRED);
        assertTrue("EXPIRED must say retry here: " + m,
                m.toLowerCase().contains("retry here"));
    }

    /** The CAPACITY message must reference the bulk "Forget paired
     *  headsets" control, NOT a single-headset forget that the
     *  PC manager does not expose. */
    @Test public void capacityMessageReferencesBulkForget() {
        String m = StandalonePairingClient.messageForCode(StandalonePairingClient.CODE_CAPACITY);
        assertTrue("CAPACITY must reference bulk forget: " + m,
                m.toLowerCase().contains("forget paired headsets"));
        assertFalse("CAPACITY must not suggest forget-a-single-headset: " + m,
                m.toLowerCase().contains("forget a paired") || m.toLowerCase().contains("forget one"));
    }
}