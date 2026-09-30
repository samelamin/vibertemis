package com.vibertemis.quest.pcvr;

/**
 * Sealed abstraction over the standalone TLS pairing driver so
 * the hub can inject a fake implementation in tests without
 * subclassing the final {@link StandalonePairingClient}. The
 * production driver implements this interface; tests provide a
 * deterministic fake via the {@code createStandalonePairingClient}
 * seam.
 *
 * <p>The interface intentionally exposes only the methods the
 * hub calls; it is NOT a generic pairing API.
 */
public interface PairingSession {
    /**
     * Run the enrollment handshake against {@code host:port} and
     * deliver the comparison code to {@code progress.comparing()}
     * EXACTLY once before polling. The monotonic deadline is set
     * BEFORE the callback so the countdown UI never observes a
     * zero remaining on the first tick.
     */
    HostPairing enroll(String host, int port,
                       StandalonePairingClient.Progress progress) throws Exception;

    /** Cancel an in-flight enrollment. Idempotent. */
    void cancel();

    /** Remaining monotonic time (ms) on the active enrollment
     *  deadline. Returns zero when no enrollment is active. */
    long remainingDeadlineMs();
}