package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.AlertDialog;
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
     * callback has been delivered. Used only where the assertion is
     * about work that must NOT have been queued, where there is no
     * positive completion signal to await.
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

    @Test public void runningHostRequiresRestartConsent() throws Exception {
        host.status.put("vrserver", true);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> ShadowAlertDialog.getLatestAlertDialog() != null
                    && ShadowAlertDialog.getLatestAlertDialog().isShowing());
            assertEquals(0, host.starts.get());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Restart VR", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            await(() -> host.starts.get() == 1);
        }
    }

    /**
     * A rapid double tap on the consent dialog's Restart VR button must
     * not queue a second start request. The first accepted start
     * consumes the generation the dialog captured, so the second tap's
     * stale generation is rejected by its own guard: exactly one
     * {@code HostClient.start} and exactly one activity dispatch.
     */
    @Test public void rapidRestartTapsIssueOneStartAndOneLaunch() throws Exception {
        host.status.put("vrserver", true);
        host.release = new CountDownLatch(1);
        try (var c = Robolectric.buildActivity(TestHub.class).setup()) {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            await(() -> ShadowAlertDialog.getLatestAlertDialog() != null
                    && ShadowAlertDialog.getLatestAlertDialog().isShowing());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            android.widget.Button restart = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals("Restart VR", restart.getText().toString());
            // First tap is accepted; the latch holds its start request
            // open so the second tap lands on the same button while the
            // same attempt is still in flight.
            restart.performClick();
            await(() -> host.starts.get() == 1);
            // Second tap on the same button, still before the held
            // request completes.
            restart.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            host.release.countDown();
            final Intent[] launch = new Intent[1];
            await(() -> (launch[0] = Shadows.shadowOf(c.get()).getNextStartedActivity()) != null);
            settle();
            assertEquals("second tap must not queue a second start request",
                    1, host.starts.get());
            assertNotNull("consent must dispatch SteamVrActivity", launch[0]);
            assertNull("one consent must dispatch SteamVrActivity exactly once",
                    Shadows.shadowOf(c.get()).getNextStartedActivity());
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
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
            c.resume();
            assertNotNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
            c.pause().resume();
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
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
