package com.vibertemis.quest.hub;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure coordinator for the native PCVR return contract.
 *
 * <p>It owns three decisions and nothing else:
 * <ol>
 *   <li><b>Launch nonce.</b> Every immersive dispatch mints a random
 *       one-shot nonce ({@link #beginDispatch}). Only the callback that
 *       echoes that exact nonce back can be acted on, so a forged,
 *       stale or duplicated callback is inert.</li>
 *   <li><b>Host identity.</b> The pairing certificate pin is public
 *       identity, not a credential: it travels with the dispatch and
 *       must come back unchanged AND must still match the pairing the
 *       hub holds now. A callback that names a different host (a
 *       re-paired or address-rediscovered PC) is rejected rather than
 *       prompting a restart on the wrong machine. A
 *       {@code restart_required} needs a real 64-hex pin on all three
 *       sides, because the consent it unlocks is spent on one host.</li>
 *   <li><b>Old-process liveness.</b> {@link #liveness} decides, from a
 *       process snapshot only, whether the previous {@code :pcvr}
 *       process is provably gone. There is no timing heuristic and no
 *       dependence on how fast a human reacts: the answer is a process
 *       identity fact, and anything else fails closed.</li>
 * </ol>
 *
 * <p>No Android framework, no Activity and no JNI is referenced here on
 * purpose: the contract is exercised by plain JVM tests, so the
 * {@link SteamVrActivity} callback path never needs a real native
 * runtime.
 */
final class PcvrReturnGate {

    /** Native found a real config mismatch: SteamVR must be restarted. */
    static final String ISSUE_RESTART_REQUIRED = "restart_required";
    /** Native tried the restart helper and it failed. */
    static final String ISSUE_RESTART_FAILED = "restart_failed";

    /** No dispatch is outstanding (or the callback was already used). */
    static final int REJECT_NO_PENDING = 0;
    /** The issue string is not one of the two known values. */
    static final int REJECT_UNKNOWN_ISSUE = 1;
    /** The nonce is missing, forged or from an older dispatch. */
    static final int REJECT_NONCE = 2;
    /** The pin is missing/forged, or the paired host changed. */
    static final int REJECT_IDENTITY = 3;
    /** Accepted: a real mismatch needs a restart. */
    static final int ACCEPT_RESTART_REQUIRED = 4;
    /** Accepted: a restart was attempted by native and failed. */
    static final int ACCEPT_RESTART_FAILED = 5;

    /** The old process is provably gone (or was never running). */
    static final int LIVENESS_GONE = 0;
    /** The old process is still running under that exact identity. */
    static final int LIVENESS_ALIVE = 1;
    /** The process state could not be established — never launch. */
    static final int LIVENESS_UNKNOWN = 2;

    /** One row of the process snapshot: own-UID identity only. */
    static final class ProcRow {
        final int uid;
        final String name;
        final int pid;

        ProcRow(int uid, String name, int pid) {
            this.uid = uid;
            this.name = name;
            this.pid = pid;
        }
    }

    /**
     * True only for a real public pairing pin: the lowercase hex
     * SHA-256 of the host certificate, exactly as
     * {@code HostPairing.parse} validates it on the way in.
     *
     * <p>A {@code restart_required} report has to carry one, because the
     * hub will later hand the user a "Restart VR" consent and act on the
     * answer against whichever host the pin names. An empty (or
     * malformed) pin cannot name a host, so it must never reach that
     * prompt. A legacy unpaired manual runtime can still report
     * {@code restart_failed}, which never grants anything.
     */
    static boolean isPublicPin(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    private String nonce = "";
    private String pin = "";
    /** Nonces already spent, so a duplicate delivery is inert even if a
     *  recreation restored the same pending value. */
    private final Set<String> consumed = new LinkedHashSet<>();

    /** True while a dispatch is outstanding a callback. */
    synchronized boolean awaitingCallback() {
        return !nonce.isEmpty();
    }

    synchronized String pendingNonce() {
        return nonce;
    }

    synchronized String pendingPin() {
        return pin;
    }

    /**
     * Mint the one-shot nonce for one immersive dispatch. Any previous
     * expectation is dropped: an earlier session's callback is stale by
     * definition.
     *
     * @param pairingPin public pairing certificate pin currently held by
     *                   the hub, or {@code null}/"" when unpaired
     * @return the nonce the hub must expect back
     */
    synchronized String beginDispatch(String pairingPin) {
        String fresh = UUID.randomUUID().toString();
        consumed.clear();
        nonce = fresh;
        pin = pairingPin == null ? "" : pairingPin;
        return fresh;
    }

    /**
     * Forget any outstanding callback expectation. Called on a new
     * connect, on Cancel and when the host pairing changes.
     */
    synchronized void clearPending() {
        nonce = "";
        pin = "";
        consumed.clear();
    }

    /**
     * Re-arm the expectation after activity recreation. Only the nonce
     * and the public pin cross the boundary; a positive restart consent
     * is deliberately NOT restorable, so a recreated hub can still
     * recognise the callback but can never act on a forgotten yes.
     */
    synchronized void restorePending(String savedNonce, String savedPin) {
        if (savedNonce == null || savedNonce.isEmpty()) {
            clearPending();
            return;
        }
        if (consumed.contains(savedNonce)) {
            clearPending();
            return;
        }
        nonce = savedNonce;
        pin = savedPin == null ? "" : savedPin;
    }

    /**
     * Validate and consume one native return callback.
     *
     * <p>Order matters: an unknown issue is rejected before the identity
     * is inspected so a bogus string can never be reported as a known
     * state, and a consumed nonce is rejected before anything else so a
     * duplicate delivery can never reach the prompt.
     *
     * <p>A {@code restart_required} additionally requires a real public
     * pin on <b>all three</b> sides (armed, echoed, live): the consent
     * this unlocks is spent on a specific host, so a report that cannot
     * name that host is rejected outright instead of prompting against
     * whatever the store happens to hold.
     *
     * @param issue        raw issue string reported by native
     * @param returnedNonce nonce echoed back by the returning activity
     * @param returnedPin   pairing pin echoed back by that activity
     * @param livePin       pin the hub holds RIGHT NOW from the pairing
     *                      store (may be {@code null} when unpaired)
     * @return one of the {@code REJECT_*} / {@code ACCEPT_*} codes
     */
    synchronized int accept(String issue, String returnedNonce, String returnedPin, String livePin) {
        if (nonce.isEmpty()) return REJECT_NO_PENDING;
        if (returnedNonce == null || returnedNonce.isEmpty()) return REJECT_NONCE;
        if (consumed.contains(returnedNonce)) return REJECT_NO_PENDING;
        String normalized = normalizeIssue(issue);
        if (normalized == null) return REJECT_UNKNOWN_ISSUE;
        if (!nonce.equals(returnedNonce)) return REJECT_NONCE;
        String got = returnedPin == null ? "" : returnedPin;
        if (!pin.equals(got)) return REJECT_IDENTITY;
        // The host we are talking to must still be the host we are
        // paired with, so a callback produced against a since-changed
        // pairing cannot drive a restart.
        String live = livePin == null ? "" : livePin;
        if (!live.equals(got)) return REJECT_IDENTITY;
        boolean restartRequired = ISSUE_RESTART_REQUIRED.equals(normalized);
        if (restartRequired && !(isPublicPin(pin) && isPublicPin(got) && isPublicPin(live))) {
            // A real mismatch needs a nameable host. An empty or
            // malformed pin on any side is a broken/legacy report, and
            // it is rejected rather than turned into a restart consent.
            return REJECT_IDENTITY;
        }
        consumed.add(nonce);
        nonce = "";
        pin = "";
        return restartRequired ? ACCEPT_RESTART_REQUIRED : ACCEPT_RESTART_FAILED;
    }

    /** The two known issue values, or {@code null} for anything else. */
    static String normalizeIssue(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return isKnownIssue(value) ? value : null;
    }

    static boolean isKnownIssue(String raw) {
        return ISSUE_RESTART_REQUIRED.equals(raw) || ISSUE_RESTART_FAILED.equals(raw);
    }

    /**
     * Decide whether the old immersive process is gone, from a process
     * snapshot alone.
     *
     * <ul>
     *   <li>{@code rows == null} — the platform could not answer:
     *       {@link #LIVENESS_UNKNOWN}, so the caller fails clearly and
     *       never launches.</li>
     *   <li>A row with <b>our own UID</b>, the <b>exact</b>
     *       {@code <package>:pcvr} name and the target pid — still
     *       alive.</li>
     *   <li>{@code targetPid > 0} and the name is live under a
     *       <i>different</i> pid — the old pid is gone but something
     *       else owns the immersive process, so a clean handover cannot
     *       be proven: unknown.</li>
     *   <li>No row with that name for our UID — gone.</li>
     * </ul>
     *
     * @param targetPid the pid to prove gone, or {@code <= 0} to prove
     *                  that no {@code :pcvr} process of ours runs at all
     */
    static int liveness(List<ProcRow> rows, int ownUid, String expectedName, int targetPid) {
        if (rows == null) return LIVENESS_UNKNOWN;
        if (expectedName == null || expectedName.isEmpty()) return LIVENESS_UNKNOWN;
        boolean nameLive = false;
        for (ProcRow row : rows) {
            if (row == null || row.uid != ownUid) continue;
            if (!expectedName.equals(row.name)) continue;
            nameLive = true;
            if (targetPid <= 0 || row.pid == targetPid) return LIVENESS_ALIVE;
        }
        if (nameLive) return LIVENESS_UNKNOWN;
        return LIVENESS_GONE;
    }
}
