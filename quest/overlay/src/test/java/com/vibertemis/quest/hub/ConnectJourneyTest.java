package com.vibertemis.quest.hub;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import com.limelight.R;
import com.vibertemis.quest.pcvr.HostClient;
import com.vibertemis.quest.pcvr.HostClientTest;
import com.vibertemis.quest.pcvr.HostPairing;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class ConnectJourneyTest {
    static FakeHost host;
    static HostPairing pairing;

    static class FakeHost extends HostClient {
        JSONObject status = new JSONObject();
        final AtomicInteger starts = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(0);
        @Override public JSONObject request(HostPairing p, String method, String path, byte[] body) {
            assertEquals("GET", method);
            assertEquals("/status", path);
            return status;
        }
        @Override public void start(HostPairing p, String codec) throws Exception {
            starts.incrementAndGet();
            if (!release.await(3, TimeUnit.SECONDS)) throw new Exception("test host timed out");
        }
    }

    public static class TestHub extends MainHubActivity {
        @Override protected boolean hasPairedHost() { return true; }
        @Override protected HostPairing loadHostPairing() { return pairing; }
        @Override protected HostClient createHostClient() { return host; }
        @Override protected boolean usesNativeRuntime() { return true; }
        @Override protected String loadNativeHeadsetIdentity() { return "quest-test"; }
    }

    @Before public void setup() throws Exception {
        host = new FakeHost();
        host.status.put("vrserver", false);
        pairing = HostClientTest.pairing("host", 28540);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("connection callback did not complete");
    }

    /**
     * Drain the main looper and the single-thread connect worker for a
     * bounded window so every queued worker task has run and its UI
     * callback has been delivered. Used where the assertion is about a
     * bounded count — that nothing extra was queued, or that a queued
     * continuation is not replayed — so there is no second positive
     * completion signal to await.
     */
    private void settle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private boolean pendingLaunch(TestHub hub) {
        try {
            java.lang.reflect.Field f = MainHubActivity.class.getDeclaredField("pendingContinuation");
            f.setAccessible(true);
            return f.getBoolean(hub);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void coldHostNeedsOneConnectAndOneLaunch() throws Exception {
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            final Intent[] launch = new Intent[1];
            await(() -> (launch[0] = Shadows.shadowOf(c.get()).getNextStartedActivity()) != null);
            assertEquals(1, host.starts.get());
            assertTrue(launch[0].getBooleanExtra("vq_pcvr_allow_restart", false));
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
        }
    }

    /**
     * A WARM {@code vrserver=true} host is a normal, idempotent
     * connection, so one click is the whole journey: exactly one
     * {@code HostClient.start} and exactly one SteamVrActivity
     * dispatch, with no dialog anywhere.
     *
     * <p>That single launch must carry
     * {@code vq_pcvr_allow_restart=false} and a ZERO restart deadline,
     * so the native side can never restart a healthy VR session it was
     * not asked to touch. A warm server is not a discrepancy, so it
     * never prompts: the Restart VR / Cancel dialog belongs to an
     * authenticated native mismatch callback, which
     * {@link MainHubNativeReturnTest} and {@link MainHubActivityTest}
     * cover.
     */
    @Test public void warmHostConnectsInOneTapWithoutRestartPermission() throws Exception {
        host.status.put("vrserver", true);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            final Intent[] launch = new Intent[1];
            await(() -> (launch[0] = Shadows.shadowOf(c.get()).getNextStartedActivity()) != null);
            assertEquals("one warm connection is one host start", 1, host.starts.get());
            assertEquals("the warm journey dispatches SteamVrActivity",
                    "com.vibertemis.quest.hub.SteamVrActivity",
                    launch[0].getComponent().getClassName());
            assertFalse("a warm connection must not grant restart permission",
                    launch[0].getBooleanExtra("vq_pcvr_allow_restart", true));
            assertEquals("a warm connection must carry a zero restart deadline",
                    0L, launch[0].getLongExtra("vq_pcvr_restart_until_ms", -1L));
            assertNull("one warm connection must dispatch SteamVrActivity exactly once",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
            assertNull("a warm host must never prompt the user",
                    ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /**
     * A rapid duplicate tap on Connect while the first attempt is still
     * in flight on the host worker must not queue a second attempt. The
     * fake host's latch holds the accepted start request open, so the
     * extra taps really do land on the same button while that attempt
     * is unfinished; the in-flight guard rejects them, so the whole
     * warm journey still yields exactly one {@code HostClient.start}
     * and exactly one SteamVrActivity dispatch — and that one launch
     * still carries no restart permission, with no dialog anywhere.
     */
    @Test public void rapidDuplicateConnectTapsIssueOneStartAndOneLaunch() throws Exception {
        host.status.put("vrserver", true);
        host.release = new CountDownLatch(1);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> host.starts.get() == 1);
            // Duplicate taps land on the same button while the same
            // start request is still held open by the fake host.
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("a duplicate tap must not queue a second start request",
                    1, host.starts.get());
            assertNull("nothing may be dispatched while the start is in flight",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
            host.release.countDown();
            final Intent[] launch = new Intent[1];
            await(() -> (launch[0] = Shadows.shadowOf(c.get()).getNextStartedActivity()) != null);
            settle();
            assertEquals("a duplicate tap must not queue a second start request",
                    1, host.starts.get());
            assertEquals("the warm journey dispatches SteamVrActivity",
                    "com.vibertemis.quest.hub.SteamVrActivity",
                    launch[0].getComponent().getClassName());
            assertFalse("a warm connection must not grant restart permission",
                    launch[0].getBooleanExtra("vq_pcvr_allow_restart", true));
            assertEquals("a warm connection must carry a zero restart deadline",
                    0L, launch[0].getLongExtra("vq_pcvr_restart_until_ms", -1L));
            assertNull("one connection must dispatch SteamVrActivity exactly once",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
            assertNull("a warm host must never prompt the user",
                    ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void unknownHostStateNeverStartsVr() throws Exception {
        host.status = new JSONObject();
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> c.get().findViewById(R.id.hub_card_error_row).getVisibility() == android.view.View.VISIBLE);
            assertEquals(0, host.starts.get());
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
        }
    }

    @Test public void pauseDefersSuccessfulExplicitLaunchExactlyOnce() throws Exception {
        host.release = new CountDownLatch(1);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> host.starts.get() == 1);
            c.pause();
            host.release.countDown();
            await(() -> pendingLaunch(c.get()));
            assertNull("a hub paused mid-attempt must not dispatch VR",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
            c.resume();
            // The resumed dispatch is gated on the real process-liveness
            // proof, which answers on the connect worker, so the launch
            // is awaited rather than assumed to be synchronous. The
            // awaiting condition is itself the assertion, so the intent
            // is captured in the same pass that proves it exists.
            final Intent[] launch = new Intent[1];
            await(() -> (launch[0] = Shadows.shadowOf(c.get()).getNextStartedActivity()) != null);
            assertEquals("the deferred launch must be SteamVrActivity",
                    "com.vibertemis.quest.hub.SteamVrActivity",
                    launch[0].getComponent().getClassName());
            c.pause().resume();
            // Drain the worker and the main looper before claiming the
            // queued continuation was not replayed.
            settle();
            assertNull("the deferred launch must not be replayed",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
        }
    }

    @Test public void cancelInvalidatesCompletedPausedAttempt() throws Exception {
        host.release = new CountDownLatch(1);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> host.starts.get() == 1);
            c.pause();
            host.release.countDown();
            await(() -> pendingLaunch(c.get()));
            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            c.resume();
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
            assertTrue(host.isCancelled());
        }
    }

    @Test public void revokedPermissionDropsPausedAttempt() throws Exception {
        host.release = new CountDownLatch(1);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> host.starts.get() == 1);
            c.pause();
            host.release.countDown();
            await(() -> pendingLaunch(c.get()));
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
            c.resume();
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
        }
    }
}
