package com.vibertemis.quest.hub;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for the native PCVR return contract.
 *
 * <p>These run on a plain JVM because every rule the Java side has to
 * honour is pure: {@link PcvrReturnGate} decides, from a nonce, a public
 * pin and a process snapshot alone, whether a callback from
 * {@link SteamVrActivity#onPcvrConnectionIssue(String)} may prompt the
 * user and whether the previous immersive process is provably gone. That
 * keeps the JNI-dependent class out of the test: no native runtime, no
 * device, no {@code libalvr_client_openxr.so} needed to prove the
 * identity and liveness rules.
 *
 * <p>The hub-side wiring (probe decisions, prompting, the re-probe behind
 * a Restart tap) is covered by {@link MainHubActivityTest}; this class
 * pins the pure decisions those tests rely on.
 */
public class MainHubNativeReturnTest {

    private static final String PIN = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String OTHER_PIN = "2222222222222222222222222222222222222222222222222222222222222222";

    private PcvrReturnGate gate;
    private String nonce;

    @Before
    public void setUp() {
        gate = new PcvrReturnGate();
        assertFalse("a fresh gate expects nothing", gate.awaitingCallback());
        nonce = gate.beginDispatch(PIN);
        assertTrue("a dispatch arms exactly one expectation", gate.awaitingCallback());
        assertEquals("the pin is stored as given", PIN, gate.pendingPin());
    }

    /* ---- identity ---- */

    @Test
    public void knownRestartRequiredIsAcceptedAndSpentOnce() {
        assertEquals(PcvrReturnGate.ACCEPT_RESTART_REQUIRED,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
        assertFalse("an accepted callback must spend the expectation",
                gate.awaitingCallback());
        assertEquals("a duplicate delivery must be inert",
                PcvrReturnGate.REJECT_NO_PENDING,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
    }

    @Test
    public void knownRestartFailedIsAcceptedAndSpentOnce() {
        assertEquals(PcvrReturnGate.ACCEPT_RESTART_FAILED,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_FAILED, nonce, PIN, PIN));
        assertEquals("a duplicate delivery must be inert",
                PcvrReturnGate.REJECT_NO_PENDING,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_FAILED, nonce, PIN, PIN));
    }

    @Test
    public void unknownIssueIsRejectedWithoutSpendingTheExpectation() {
        assertEquals(PcvrReturnGate.REJECT_UNKNOWN_ISSUE,
                gate.accept("restart_maybe", nonce, PIN, PIN));
        assertEquals("an unknown issue is rejected before the identity is even read",
                PcvrReturnGate.REJECT_UNKNOWN_ISSUE,
                gate.accept(null, "forged", "forged", "forged"));
        assertTrue("a rejected issue must not spend the expectation",
                gate.awaitingCallback());
    }

    @Test
    public void missingOrForgedNonceIsRejected() {
        assertEquals(PcvrReturnGate.REJECT_NONCE,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, null, PIN, PIN));
        assertEquals(PcvrReturnGate.REJECT_NONCE,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, "", PIN, PIN));
        assertEquals(PcvrReturnGate.REJECT_NONCE,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED,
                        "00000000-0000-4000-8000-000000000000", PIN, PIN));
        assertEquals(PcvrReturnGate.REJECT_NONCE,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce + "x", PIN, PIN));
        assertTrue("a forged nonce must not spend the expectation",
                gate.awaitingCallback());
    }

    @Test
    public void nonceFromAnOlderDispatchIsRejected() {
        String stale = nonce;
        String fresh = gate.beginDispatch(PIN);
        assertNotEquals("each dispatch mints its own nonce", stale, fresh);
        assertEquals(PcvrReturnGate.REJECT_NONCE,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, stale, PIN, PIN));
        assertEquals(PcvrReturnGate.ACCEPT_RESTART_REQUIRED,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, fresh, PIN, PIN));
    }

    @Test
    public void forgedPinIsRejected() {
        assertEquals(PcvrReturnGate.REJECT_IDENTITY,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, OTHER_PIN, PIN));
        assertEquals(PcvrReturnGate.REJECT_IDENTITY,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, null, PIN));
        assertTrue("a forged identity must not spend the expectation",
                gate.awaitingCallback());
    }

    @Test
    public void changedHostIsRejectedEvenWithTheRightNonce() {
        // The pin travelled unchanged but the hub is no longer paired with
        // that PC (re-paired, or the pairing store is unreadable).
        assertEquals(PcvrReturnGate.REJECT_IDENTITY,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, OTHER_PIN));
        assertEquals("an unpaired hub cannot honour a callback",
                PcvrReturnGate.REJECT_IDENTITY,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, null));
        assertTrue("a changed host must not spend the expectation",
                gate.awaitingCallback());
    }

    @Test
    public void unpairedDispatchAcceptsAnEmptyPin() {
        PcvrReturnGate unpaired = new PcvrReturnGate();
        String n = unpaired.beginDispatch(null);
        assertEquals("", unpaired.pendingPin());
        assertEquals(PcvrReturnGate.ACCEPT_RESTART_FAILED,
                unpaired.accept(PcvrReturnGate.ISSUE_RESTART_FAILED, n, "", ""));
    }

    /**
     * A {@code restart_required} report is asymmetric on purpose. It
     * unlocks a consent that is spent on a specific PC, so it has to name
     * that PC: all three pins — the one the dispatch carried, the one the
     * returning activity echoed, and the one the hub holds now — must be a
     * real 64-hex public pin. An empty or malformed value on any side is
     * rejected, and the expectation survives so a well-formed delivery of
     * the same callback still works.
     *
     * <p>A legacy unpaired manual runtime can still report
     * {@code restart_failed} with no pin, as
     * {@link #unpairedDispatchAcceptsAnEmptyPin()} pins: that path grants
     * nothing, so it has no host to name.
     */
    @Test
    public void restartRequiredNeedsAUsablePinOnEverySide() {
        PcvrReturnGate unpaired = new PcvrReturnGate();
        String n = unpaired.beginDispatch(null);
        assertEquals("", unpaired.pendingPin());
        assertEquals("all three pins empty is not a nameable host",
                PcvrReturnGate.REJECT_IDENTITY,
                unpaired.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, n, "", ""));
        assertEquals("a missing echoed pin is not a nameable host",
                PcvrReturnGate.REJECT_IDENTITY,
                unpaired.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, n, null, ""));
        assertEquals("a malformed pin is not a nameable host",
                PcvrReturnGate.REJECT_IDENTITY,
                unpaired.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, n,
                        "not-a-pin", "not-a-pin"));
        assertTrue("a rejected report must not spend the expectation",
                unpaired.awaitingCallback());

        PcvrReturnGate paired = new PcvrReturnGate();
        String m = paired.beginDispatch(PIN);
        assertEquals("the very same callback is accepted with a real pin",
                PcvrReturnGate.ACCEPT_RESTART_REQUIRED,
                paired.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, m, PIN, PIN));
    }

    @Test
    public void publicPinIsExactlySixtyFourLowercaseHexCharacters() {
        // A real pin is hex, so it mixes digits and letters; the digits-only
        // PIN above could not tell an uppercase form from a valid one.
        String mixed = "0123456789abcdef0123456789abcdef"
                + "0123456789abcdef0123456789abcdef";
        assertEquals(64, mixed.length());
        assertTrue(PcvrReturnGate.isPublicPin(PIN));
        assertTrue(PcvrReturnGate.isPublicPin(OTHER_PIN));
        assertTrue(PcvrReturnGate.isPublicPin(mixed));
        assertFalse(PcvrReturnGate.isPublicPin(null));
        assertFalse(PcvrReturnGate.isPublicPin(""));
        assertFalse("63 characters is not a SHA-256 pin",
                PcvrReturnGate.isPublicPin(mixed.substring(1)));
        assertFalse("65 characters is not a SHA-256 pin",
                PcvrReturnGate.isPublicPin(mixed + "0"));
        assertFalse("uppercase is not the stored form",
                PcvrReturnGate.isPublicPin(mixed.toUpperCase(java.util.Locale.US)));
        assertFalse("a non-hex character is not a pin",
                PcvrReturnGate.isPublicPin("z" + mixed.substring(1)));
    }

    @Test
    public void normalizationOnlyPassesTheTwoKnownValues() {
        assertEquals(PcvrReturnGate.ISSUE_RESTART_REQUIRED,
                PcvrReturnGate.normalizeIssue("  restart_required "));
        assertEquals(PcvrReturnGate.ISSUE_RESTART_FAILED,
                PcvrReturnGate.normalizeIssue(PcvrReturnGate.ISSUE_RESTART_FAILED));
        assertNull(PcvrReturnGate.normalizeIssue(null));
        assertNull(PcvrReturnGate.normalizeIssue(""));
        assertNull(PcvrReturnGate.normalizeIssue("RESTART_REQUIRED"));
        assertNull(PcvrReturnGate.normalizeIssue("restart_required; drop table"));
        assertFalse(PcvrReturnGate.isKnownIssue("anything_else"));
    }

    /* ---- recreation ---- */

    @Test
    public void restorationReArmsTheExpectationButNotASpentOne() {
        PcvrReturnGate restored = new PcvrReturnGate();
        restored.restorePending(nonce, PIN);
        assertTrue("the correlation identity survives recreation",
                restored.awaitingCallback());
        assertEquals(PcvrReturnGate.ACCEPT_RESTART_REQUIRED,
                restored.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
        assertEquals("and it is still one-shot after restoration",
                PcvrReturnGate.REJECT_NO_PENDING,
                restored.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
    }

    @Test
    public void restorationOfNothingExpectsNothing() {
        PcvrReturnGate restored = new PcvrReturnGate();
        restored.restorePending(null, PIN);
        assertFalse(restored.awaitingCallback());
        restored.restorePending("", PIN);
        assertFalse(restored.awaitingCallback());
        assertEquals(PcvrReturnGate.REJECT_NO_PENDING,
                restored.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
    }

    @Test
    public void clearPendingDropsTheExpectation() {
        gate.clearPending();
        assertFalse(gate.awaitingCallback());
        assertEquals(PcvrReturnGate.REJECT_NO_PENDING,
                gate.accept(PcvrReturnGate.ISSUE_RESTART_REQUIRED, nonce, PIN, PIN));
    }

    /* ---- process liveness ---- */

    private static final int UID = 10123;
    private static final String PCVR = "com.example.app:pcvr";

    private static PcvrReturnGate.ProcRow row(int uid, String name, int pid) {
        return new PcvrReturnGate.ProcRow(uid, name, pid);
    }

    private static List<PcvrReturnGate.ProcRow> rows(PcvrReturnGate.ProcRow... r) {
        return Arrays.asList(r);
    }

    @Test
    public void noSnapshotIsUnknownAndNeverGone() {
        assertEquals(PcvrReturnGate.LIVENESS_UNKNOWN,
                PcvrReturnGate.liveness(null, UID, PCVR, 4242));
        assertEquals("an unusable expected name is unknown too",
                PcvrReturnGate.LIVENESS_UNKNOWN,
                PcvrReturnGate.liveness(rows(), UID, "", 4242));
    }

    @Test
    public void exactUidNameAndPidIsAlive() {
        assertEquals(PcvrReturnGate.LIVENESS_ALIVE,
                PcvrReturnGate.liveness(rows(row(UID, PCVR, 4242)), UID, PCVR, 4242));
    }

    @Test
    public void anotherUidOrAnotherNameIsNotOurProcess() {
        assertEquals(PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(rows(row(UID + 1, PCVR, 4242)), UID, PCVR, 4242));
        assertEquals("a prefix match is not the :pcvr process",
                PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(rows(row(UID, "com.example.app", 4242)), UID, PCVR, 4242));
        assertEquals("a longer name is not the :pcvr process",
                PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(rows(row(UID, PCVR + ":helper", 4242)), UID, PCVR, 4242));
    }

    @Test
    public void absentNameIsGone() {
        assertEquals(PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(Collections.<PcvrReturnGate.ProcRow>emptyList(),
                        UID, PCVR, 4242));
        assertEquals(PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(rows(row(UID, "com.example.app", 1)), UID, PCVR, 4242));
    }

    @Test
    public void liveNameUnderAnotherPidCannotProveACleanHandover() {
        assertEquals(PcvrReturnGate.LIVENESS_UNKNOWN,
                PcvrReturnGate.liveness(rows(row(UID, PCVR, 5151)), UID, PCVR, 4242));
    }

    @Test
    public void anyLivePcvrProcessBlocksWhenNoSpecificPidIsKnown() {
        assertEquals(PcvrReturnGate.LIVENESS_ALIVE,
                PcvrReturnGate.liveness(rows(row(UID, PCVR, 5151)), UID, PCVR, 0));
        assertEquals(PcvrReturnGate.LIVENESS_GONE,
                PcvrReturnGate.liveness(rows(row(UID, "com.example.app", 1)), UID, PCVR, 0));
    }
}
