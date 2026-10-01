package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.vibertemis.quest.pcvr.HostClient;
import com.vibertemis.quest.pcvr.HostClientTest;
import com.vibertemis.quest.pcvr.HostPairing;
import com.vibertemis.quest.pcvr.PcvrTestActions;
import com.vibertemis.quest.pcvr.PcvrTestActions.StartedIntentLog;
import com.vibertemis.quest.pcvr.VrSetupDiscovery;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Main hub lifecycle tests. We inflate {@link MainHubActivity} via
 * Robolectric so the on-screen button handlers are bound, then drive
 * the user actions and assert on the intents Robolectric recorded.
 *
 * <p><b>Fixtures.</b> Two hub shapes are needed because the hub routes
 * differently once a PC is paired:
 * <ul>
 *   <li><b>Unpaired</b> ({@link FakeNsdHub} over the real
 *       {@link com.vibertemis.quest.pcvr.PairingStore}, so
 *       {@code hasPairing()} is false). The primary action reads "Set
 *       up PC" and opens the VR-setup picker. The setup flow
 *       deliberately discovers and pairs the PC <i>before</i> anything
 *       asks for the microphone, so these tests assert the picker
 *       surfaces and that <b>no</b> microphone request is dispatched
 *       until the user explicitly picks a VR action.</li>
 *   <li><b>Paired</b> ({@link PairedHub}, reusing the
 *       {@link ConnectJourneyTest} fake-host pattern): the real
 *       {@code onRequestPermissionsResult} seam, the real
 *       {@link #REQ_MIC_FOR_STEAMVR} request code, and the real
 *       {@code loadNativeHeadsetIdentity} seam, but a deterministic
 *       {@link FakeHost} returning an authenticated
 *       {@code vrserver:false} probe. Permission, lifecycle,
 *       duplication, denial and launch-guard behaviour is only
 *       reachable through this fixture, because Connect no longer
 *       asks for the microphone on the unpaired setup path.</li>
 * </ul>
 *
 * <p><b>Harness rules.</b>
 * <ul>
 *   <li>The connect lifecycle runs on the hub's single-thread worker,
 *       so every asynchronous assertion waits on a real signal (a fake
 *       host counter, a surfaced dialog) instead of a fixed sleep.</li>
 *   <li>{@link StartedIntentLog} accumulates every recorded intent, so
 *       draining the destructive Robolectric started-activity queue
 *       before and after an asynchronous launch never loses an intent
 *       and never makes an early drain swallow a later one.</li>
 *   <li>Every test dismisses its dialogs and destroys its controller,
 *       so no modal dialog or background worker leaks into the next
 *       test.</li>
 * </ul>
 *
 * <p>The hub must:
 * <ul>
 *   <li>never launch PCVR when the device is not a headset,</li>
 *   <li>on a headset with the mic permission denied, surface the
 *       "PCVR not started" toast and never start {@link SteamVrActivity}
 *       or reach the host,</li>
 *   <li>on a headset with the mic permission granted, probe the paired
 *       host and start {@link SteamVrActivity} via the explicit
 *       {@code ComponentName} with the immersive VR categories and
 *       {@code FLAG_ACTIVITY_NEW_TASK},</li>
 *   <li>on a phone, route the primary Connect to {@link PcView}
 *       without asking for mic permission,</li>
 *   <li>on an unpaired headset, discover and pair before asking for the
 *       microphone,</li>
 *   <li>guard against in-flight permission requests and pending
 *       launches so rapid taps and duplicate callbacks cannot start two
 *       activities at once,</li>
 *   <li>never auto-launch from {@code onResume} after returning from
 *       Streaming settings, a permission dialog, or a recreation — but
 *       must continue exactly the action a paused grant belongs to,</li>
 *   <li>validate the permission result against the actual requested
 *       permission name, current headset status, and current grant
 *       state before launching PCVR,</li>
 *   <li>request the microphone for Manual VR when it is missing, and
 *       still require the explicit legacy restart consent afterwards.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class MainHubActivityTest {

    private static final int REQ_MIC_FOR_STEAMVR = MainHubActivity.REQ_MIC_FOR_STEAMVR_FOR_TEST;
    private static final String STATE_REQUEST_PENDING = "vq_hub_request_pending";

    private Context ctx;

    /** Fake host for the paired fixture, rebuilt per test. */
    static FakeHost host;
    static HostPairing pairing;

    /**
     * Deterministic stand-in for the authenticated host. It answers the
     * probe with {@code vrserver:false} (a cold PC: no consent dialog,
     * the probe itself is the consent) and counts probes and start
     * requests so a test can prove how many attempts reached the host.
     * {@link #release} can be held open to keep an attempt in flight
     * while the test taps again.
     */
    static class FakeHost extends HostClient {
        final JSONObject status = new JSONObject();
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        volatile CountDownLatch release = new CountDownLatch(0);
        FakeHost() throws org.json.JSONException { status.put("vrserver", false); }
        @Override public JSONObject request(HostPairing p, String method, String path, byte[] body) {
            assertEquals("GET", method);
            assertEquals("/status", path);
            probes.incrementAndGet();
            return status;
        }
        @Override public void start(HostPairing p, String codec) throws Exception {
            starts.incrementAndGet();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new Exception("fake host start timed out");
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        FakeNsdHub.sharedFactory = null;
        host = new FakeHost();
        pairing = HostClientTest.pairing("host", 28540);
    }

    private static ShadowApplication app() { return ShadowApplication.getInstance(); }

    private void setHeadset(boolean headset) {
        ShadowPackageManager spm = Shadows.shadowOf(ctx.getPackageManager());
        spm.setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, headset);
    }

    private void grantMic(boolean granted) {
        ShadowApplication app = ShadowApplication.getInstance();
        if (granted) {
            app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        } else {
            app.denyPermissions(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Test hub that injects a deterministic fake NSD driver via
     *  the {@link VrSetupDiscovery.Factory} seam so the discovery
     *  completes immediately with the supplied services list
     *  (or empty when {@code services} is null/empty). Real NSD
     *  is a Robolectric no-op and would otherwise pin tests for
     *  the 8-second browse budget. It keeps the real
     *  {@code loadNativeHeadsetIdentity} seam so the native SNI path
     *  is exercised where the runtime asks for it. */
    public static class FakeNsdHub extends MainHubActivity {
        static volatile VrSetupDiscovery.Factory sharedFactory;
        @Override protected VrSetupDiscovery createVrSetupDiscovery() {
            VrSetupDiscovery.Factory f = sharedFactory;
            if (f != null) return new VrSetupDiscovery(f);
            return super.createVrSetupDiscovery();
        }
        @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
    }

    /** Paired headset hub: the primary Connect runs the authenticated
     *  probe + start against the {@link FakeHost}. The native headset
     *  identity seam is preserved so a native runtime still gets its
     *  SNI name, and the fake NSD driver keeps any setup discovery in
     *  these tests deterministic. */
    public static class PairedHub extends FakeNsdHub {
        @Override protected boolean hasPairedHost() { return true; }
        @Override protected HostPairing loadHostPairing() { return pairing; }
        @Override protected HostClient createHostClient() { return host; }
    }

    /** Paired hub on the native runtime build, where the legacy
     *  explicit restart consent is mandatory before a manual start. */
    public static class ManualVrHub extends PairedHub {
        @Override protected boolean usesNativeRuntime() { return true; }
    }

    /** Driver that fires {@code onDiscoveryStopped} immediately
     *  with no services. {@link VrSetupDiscovery#browse()} exits on
     *  that callback (the polite shutdown path) so the picker /
     *  empty-state dialog renders deterministically. */
    static class EmptyDriver implements VrSetupDiscovery.BrowseDriver {
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        @Override public void start(NsdManager.DiscoveryListener listener) {
            startCalls.incrementAndGet();
            listener.onDiscoveryStopped(VrSetupDiscovery.SERVICE);
        }
        @Override public void stop(NsdManager.DiscoveryListener listener) {
            stopCalls.incrementAndGet();
        }
        @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {
            // no services to resolve
        }
    }

    /** Inject the empty NSD driver for the next
     *  {@link #startHubWithFakeNsd()} / {@link #startManualHub()}
     *  call. Must be cleared in the {@code finally} of the test. */
    private static void useEmptyNsd() {
        FakeNsdHub.sharedFactory = () -> new EmptyDriver();
    }

    private ActivityController<MainHubActivity> startHub() {
        return Robolectric.buildActivity(MainHubActivity.class)
                .create().start().resume();
    }

    /** Variant of {@link #startHub()} that uses {@link FakeNsdHub}. */
    private ActivityController<FakeNsdHub> startHubWithFakeNsd() {
        useEmptyNsd();
        return Robolectric.buildActivity(FakeNsdHub.class)
                .create().start().resume();
    }

    /** Paired-headset fixture. The fake NSD driver is installed so any
     *  setup flow reached from these tests is deterministic. */
    private ActivityController<PairedHub> startPairedHub() {
        useEmptyNsd();
        return Robolectric.buildActivity(PairedHub.class)
                .create().start().resume();
    }

    /** Paired headset on the native runtime, where the legacy explicit
     *  restart consent is mandatory. */
    private ActivityController<ManualVrHub> startManualHub() {
        useEmptyNsd();
        return Robolectric.buildActivity(ManualVrHub.class)
                .create().start().resume();
    }

    /**
     * Bounded UI wait. Drives the main looper until {@code condition}
     * is true or {@code maxMillis} elapses, so an assertion can wait
     * on the hub's connect worker (and its UI callback) instead of a
     * fixed sleep. The condition is evaluated once more after a final
     * looper pass.
     */
    private static boolean await(BooleanSupplier condition, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(5L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return condition.getAsBoolean();
    }

    /** Bounded UI drain used only where the assertion is about work
     *  that must NOT have been queued, so there is no positive
     *  completion signal to await. */
    private static void settle(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try { Thread.sleep(5L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** Dismiss the live dialog and tear the activity down so neither a
     *  modal dialog nor the connect worker survives the test. */
    private static void close(ActivityController<?> controller) {
        PcvrTestActions.dismissLatestDialog();
        try { controller.pause().stop().destroy(); } catch (Throwable ignored) { }
    }

    /** Read the private microphone-permission target so a test can
     *  prove which action a request belongs to and that the target is
     *  cleared once the request is consumed, rejected, cancelled or
     *  destroyed. Zero means "no request this instance started". */
    private static int micTarget(MainHubActivity hub) {
        try {
            java.lang.reflect.Field f =
                    MainHubActivity.class.getDeclaredField("micPermissionTarget");
            f.setAccessible(true);
            return f.getInt(hub);
        } catch (Exception e) {
            throw new AssertionError("cannot read micPermissionTarget", e);
        }
    }

    private static String text(View v) { return ((TextView) v).getText().toString(); }

    private static boolean isSteamVrIntent(Intent i) {
        if (i == null || i.getComponent() == null) return false;
        return "com.vibertemis.quest.hub.SteamVrActivity"
                .equals(i.getComponent().getClassName());
    }

    /** Bounded UI wait that drives the launch journey through
     *  the picker / Advanced / restart confirmation. The hub first
     *  surfaces a transient "Set up VR" (Searching) dialog while
     *  the discovery worker is on the single-thread executor; the
     *  fake NSD driver fires {@code onDiscoveryStopped}
     *  synchronously inside start() so the picker / empty-state
     *  dialog lands on the same looper pass. We must wait for the
     *  picker / empty-state title specifically — NOT the transient
     *  "Searching…" title — otherwise {@link
     *  PcvrTestActions#confirmRestartIfShown} runs against the
     *  wrong dialog and is a silent no-op. */
    private static boolean stepLaunchJourney(long maxMillis) {
        return com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                "No VR PC found", maxMillis)
                || com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                        "Choose your PC for VR", maxMillis);
    }

    /**
     * On a phone (no headtracking), tapping the primary Connect must
     * route to the flat PcView, NOT to SteamVrActivity. The hub must
     * also hide the explicit flat-screen override because it would be
     * redundant with the primary Connect on a non-headset.
     */
    @Test
    public void phone_tapConnect_routesToPcView_notPcvr() {
        setHeadset(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // The explicit flat override is hidden on phones — the
            // primary Connect already routes to flat.
            View screen = c.get().findViewById(R.id.hub_btn_screen);
            assertEquals("Flat override must be hidden on phones",
                    View.GONE, screen.getVisibility());

            c.get().findViewById(R.id.hub_btn_connect).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Phone must not launch SteamVrActivity", 0, log.countSteamVr());
            assertEquals("Phone Connect must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * Headset, paired, mic denied. The click dispatches the microphone
     * request through the real permission seam; we assert exactly that
     * one request was dispatched, deliver a denied result through the
     * real {@code onRequestPermissionsResult} callback, then assert no
     * SteamVrActivity launch, no host start request, the explicit
     * "PCVR not started" toast, and that the permission target was
     * cleared so nothing can be continued by a later result.
     */
    @Test
    public void headset_micDenied_doesNotLaunchPcvr_andShowsDeniedToast() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("Paired Connect without mic must dispatch one permission request",
                    1, log.countPermissionRequests());
            assertEquals("Permission pending must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Permission pending must not reach the host",
                    0, host.starts.get());
            assertTrue("the request must remember the action it belongs to",
                    micTarget(c.get()) != 0);

            // Deliver the denial through the real permission callback path.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED});
            settle(250);
            log.drain(app());

            assertEquals("Mic denied must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Mic denied must not start the host",
                    0, host.starts.get());
            assertEquals("a denied result must clear the permission target",
                    0, micTarget(c.get()));
            assertEquals(1, ShadowToast.shownToastCount());
            CharSequence msg = ShadowToast.getTextOfLatestToast();
            assertNotNull(msg);
            assertTrue("Denied toast must say PCVR not started: " + msg,
                    msg.toString().toLowerCase().contains("pcvr not started"));
            assertNotNull("Denial must offer the mic recovery dialog",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * Headset, mic granted at click time, but UNPAIRED. The primary
     * action is "Set up PC", so the hub opens the VR-setup picker /
     * empty state and the test drives it through Advanced → Manual
     * VR → Connect restart confirmation. SteamVrActivity launches with
     * the explicit ComponentName, the immersive VR categories and
     * FLAG_ACTIVITY_NEW_TASK. No microphone request may be dispatched:
     * the permission was already granted.
     */
    @Test
    public void headset_micGranted_launchesExplicitSteamVrActivity() {
        setHeadset(true);
        grantMic(true);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // The explicit flat override is visible on a headset because
            // it differs from the primary Connect target there.
            View screen = c.get().findViewById(R.id.hub_btn_screen);
            assertEquals("Flat override must be visible on headsets",
                    View.VISIBLE, screen.getVisibility());
            assertEquals("an unpaired headset reads Set up PC on the primary action",
                    c.get().getString(R.string.hub_btn_setup_pc),
                    text(c.get().findViewById(R.id.hub_btn_connect)));

            c.get().findViewById(R.id.hub_btn_connect).performClick();

            // Bounded UI wait for the picker / empty-state dialog to
            // surface, then drive the journey to the restart-consent
            // confirmation. Production dialog order is preserved exactly:
            // picker empty-state → Advanced VR pairing → Connect to PCVR.
            assertTrue("picker must surface for unpaired Connect",
                    stepLaunchJourney(4000L));
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            assertTrue("the manual VR journey must dispatch SteamVrActivity",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            Intent pcvr = log.all().stream().filter(MainHubActivityTest::isSteamVrIntent)
                    .findFirst().orElse(null);
            assertNotNull("Headset + granted mic must launch SteamVrActivity",
                    pcvr);
            assertEquals("com.vibertemis.quest.hub.SteamVrActivity",
                    pcvr.getComponent().getClassName());
            assertTrue(pcvr.hasCategory("com.oculus.intent.category.VR"));
            assertTrue(pcvr.hasCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD"));
            assertTrue((pcvr.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            assertEquals("Manual VR must not ask for an already granted mic",
                    0, log.countPermissionRequests());
        } finally {
            close(c);
        }
    }

    /**
     * Explicit flat override on a headset: tapping Screen gaming
     * launches the flat PcView, NOT SteamVrActivity. The flat path
     * must not require mic permission because it does not start
     * SteamVR.
     */
    @Test
    public void headset_tapFlatOverride_launchesPcView() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Explicit flat override on a headset must NOT launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Explicit flat override on a headset must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * The unpaired primary action discovers and pairs BEFORE anything
     * asks for the microphone. The setup flow itself must therefore
     * dispatch no permission request, must not launch PCVR, and must
     * not touch the host; the microphone is only requested once the
     * user explicitly picks a VR action out of that flow (Manual VR).
     */
    @Test
    public void unpairedHeadset_setUpPc_pairsBeforeAnyMicRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertEquals("an unpaired headset reads Set up PC on the primary action",
                    c.get().getString(R.string.hub_btn_setup_pc),
                    text(c.get().findViewById(R.id.hub_btn_connect)));

            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("Set up PC must surface the VR-setup picker",
                    stepLaunchJourney(4000L));
            log.drain(app());
            assertEquals("Set up PC must not request the microphone",
                    0, log.countPermissionRequests());
            assertEquals("Set up PC must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Set up PC must not probe a host before pairing",
                    0, host.probes.get());

            // The setup surfaces stay live, so the flow is reachable and
            // the explicit VR action below really is an explicit choice.
            assertTrue("the empty setup state must offer Advanced",
                    PcvrTestActions.stepAdvancedIfPicker());
            assertTrue("Advanced must offer Manual VR",
                    PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals("an explicit Manual VR must request the microphone once",
                    1, log.countPermissionRequests());
            assertEquals("nothing may launch before the mic is granted",
                    0, log.countSteamVr());
        } finally {
            close(c);
        }
    }

    /**
     * Rapid repeated taps on Connect with mic already granted must not
     * start two attempts. The taps land while the first attempt is
     * still in flight on the host worker (held open by the fake host's
     * latch), so the assertion measures the guard itself: exactly one
     * authenticated probe, one host start request and one activity
     * dispatch.
     */
    @Test
    public void rapidTapConnect_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(true);
        host.release = new CountDownLatch(1);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            assertTrue("the first tap must reach the host start request",
                    await(() -> host.starts.get() == 1, 4000L));

            // Rapid taps land while the same attempt is still in flight.
            connect.performClick();
            connect.performClick();
            connect.performClick();
            settle(200);
            log.drain(app());
            assertEquals("no PCVR launch may happen before the in-flight start returns",
                    0, log.countSteamVr());
            assertEquals("rapid taps must not queue a second host start",
                    1, host.starts.get());

            host.release.countDown();
            assertTrue("the accepted attempt must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(300);
            log.drain(app());

            assertEquals("Rapid taps must yield exactly one PCVR launch",
                    1, log.countSteamVr());
            assertEquals("Rapid taps must yield exactly one host start request",
                    1, host.starts.get());
            assertEquals("Rapid taps must yield exactly one authenticated probe",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Rapid taps when the mic is not yet granted must still only
     * dispatch one permission request — the request guard blocks the
     * following taps until the result returns, and nothing may reach
     * the host meanwhile.
     */
    @Test
    public void rapidTapConnect_micNotGranted_dispatchesOneRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            connect.performClick();
            connect.performClick();
            settle(250);
            log.drain(app());

            assertEquals("Rapid taps must dispatch exactly one permission request",
                    1, log.countPermissionRequests());
            assertEquals("Permission pending must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Permission pending must not reach the host",
                    0, host.probes.get());
            assertTrue("the in-flight request must remember its action",
                    micTarget(c.get()) != 0);
        } finally {
            close(c);
        }
    }

    /**
     * A permission result for a different permission name must be
     * ignored, even if the grant is positive, and it must consume the
     * pending request: a later, correctly named result belongs to no
     * target and must not start PCVR.
     */
    @Test
    public void mismatchedPermissionResult_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Dispatch a Connect tap so a permission request is in flight.
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            // Deliver a result whose permission name is not RECORD_AUDIO.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.CAMERA},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("Mismatched permission must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Mismatched permission must not reach the host",
                    0, host.probes.get());
            assertEquals("a mismatched result must clear the permission target",
                    0, micTarget(c.get()));

            // The request is consumed, so even a correctly named grant
            // cannot continue the dropped action.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("A result with no captured target must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("A result with no captured target must not reach the host",
                    0, host.probes.get());

            // A fresh explicit tap works normally again.
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a fresh tap after a dropped request must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            assertEquals(1, host.starts.get());
        } finally {
            close(c);
        }
    }

    /**
     * Empty grant results array (the system killed the activity before
     * delivering) must surface the recovery dialog, never launch PCVR,
     * and clear the captured action.
     */
    @Test
    public void emptyGrantResults_doesNotLaunch_showsRecovery() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{});
            settle(250);
            log.drain(app());

            assertEquals("Empty grant results must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Empty grant results must not reach the host",
                    0, host.probes.get());
            assertEquals("An empty result must clear the permission target",
                    0, micTarget(c.get()));
            assertNotNull("Empty grant results must surface the recovery dialog",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * Duplicate permission results for the same request code must be
     * ignored. The first result consumes the in-flight request AND its
     * captured action, so the duplicate delivery is a no-op: exactly
     * one probe, one host start and one activity dispatch.
     */
    @Test
    public void duplicatePermissionResult_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            // First result — granted, drives one probe and one launch.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the granted result must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Second delivery of the same grant — must be a no-op.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());

            assertEquals("Duplicate permission results must yield one launch",
                    1, log.countSteamVr());
            assertEquals("Duplicate permission results must yield one host start",
                    1, host.starts.get());
            assertEquals("Duplicate permission results must yield one probe",
                    1, host.probes.get());
            assertEquals("The consumed result must leave no captured target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * Granting phone-style mic (CAMERA, not RECORD_AUDIO) for the
     * request code path that goes through the Connect button must not
     * launch PCVR. The hub keys the result on the permission NAME, not
     * the request code alone.
     */
    @Test
    public void grantCameraInsteadOfMic_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA},
                    new int[]{PackageManager.PERMISSION_DENIED,
                            PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());

            assertEquals("Recording denied must not launch PCVR even if camera granted",
                    0, log.countSteamVr());
            assertEquals("Recording denied must not reach the host",
                    0, host.probes.get());
            assertEquals("A denied microphone must clear the permission target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * Returning from Streaming settings must NOT auto-launch anything.
     * Only an explicit user tap can dispatch a target activity. The hub
     * never auto-launches from {@code onResume}.
     */
    @Test
    public void returningFromSettings_doesNotAutoLaunch() {
        setHeadset(true);
        grantMic(true);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap settings -> simulate the user going there and coming back.
            c.get().findViewById(R.id.hub_btn_settings).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());
            assertEquals("Settings tap must launch StreamSettings",
                    1, log.countComponent(StreamSettings.class.getName()));

            // Simulate returning from the settings screen.
            c.pause();
            c.resume();
            settle(250);
            log.drain(app());

            assertEquals("Returning from settings must not auto-launch any activity",
                    1, log.size());
            assertEquals("Returning from settings must not reach the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * The request guard survives activity recreation (configuration
     * change). When the activity is recreated mid-permission-dialog,
     * the saved state restores {@code requestPending} so a second
     * dispatch of the same request code is suppressed. The captured
     * ACTION is deliberately not restored: a stray grant that lands on
     * the recreated hub must not replay the intent of the destroyed
     * instance. Only a fresh explicit tap may launch.
     */
    @Test
    public void savedState_restoresRequestGuardAcrossRecreation() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        ActivityController<PairedHub> reborn = null;
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("the request must be dispatched before recreation",
                    1, log.countPermissionRequests());

            // Save the state mid-request.
            Bundle state = new Bundle();
            c.get().onSaveInstanceState(state);
            assertTrue("Request flag must be persisted",
                    state.getBoolean(STATE_REQUEST_PENDING));
            close(c);
            c = null;

            reborn = Robolectric.buildActivity(PairedHub.class)
                    .create(state).start().resume();

            // The guard is restored, so a tap cannot dispatch a second
            // request while the system dialog is still up.
            reborn.get().findViewById(R.id.hub_btn_connect).performClick();
            settle(250);
            log.drain(app());
            assertEquals("the restored request guard must suppress a second request",
                    1, log.countPermissionRequests());
            assertEquals("The recreated hub must not inherit a captured action",
                    0, micTarget(reborn.get()));

            // A stray grant on the recreated hub has no target to
            // continue, so it must never launch PCVR — even though the
            // microphone really is granted at this point.
            grantMic(true);
            reborn.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());
            assertEquals("A stale result after recreation must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("A stale result after recreation must not reach the host",
                    0, host.probes.get());

            // A fresh explicit tap on the recreated hub works end to end.
            reborn.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a fresh tap on the recreated hub must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(300);
            log.drain(app());
            assertEquals("New tap on recreated hub with mic granted must launch once",
                    1, log.countSteamVr());
            assertEquals("New tap on recreated hub must reach the host once",
                    1, host.starts.get());
        } finally {
            if (reborn != null) close(reborn);
            if (c != null) close(c);
        }
    }

    /**
     * Permission lifecycle race regression. The dialog MAY pause the
     * hub on some platform versions before {@code launchPending} is
     * set; we simulate that ordering by calling pause() BEFORE the
     * grant callback. The permission-return onResume must therefore
     * NOT release the guard (no actual launched-activity pause ever
     * happened), and a follow-up Screen or PCVR tap during the
     * permission round-trip must be a no-op. After an actual leave-
     * and-return pause/resume cycle, the hub must accept a fresh tap.
     *
     * <p>We observe the started intents (no mirrored boolean asserts)
     * so a regression in either the guard OR the onResume ordering
     * surfaces as the wrong intent count.
     */
    @Test
    public void permissionDialogDoesNotClearLaunchGuard() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap Connect — dispatches a permission request and sets
            // requestPending. No SteamVrActivity launch yet (mic denied).
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            log.drain(app());
            assertEquals("PCVR tap before grant must dispatch one permission request",
                    1, log.countPermissionRequests());
            assertEquals("PCVR tap before grant must not launch",
                    0, log.countSteamVr());

            // Simulate the dialog pausing the hub BEFORE the grant
            // callback returns. Some platform versions do pause the
            // hosting activity here; launchLeftHub is still false
            // (launchPending was never set) so onResume must keep the
            // guard set.
            c.pause();
            c.resume();
            connect.performClick();
            settle(250);
            log.drain(app());
            assertEquals("the permission round-trip must survive a pause/resume",
                    1, log.countPermissionRequests());
            assertEquals("the permission pause must not launch PCVR",
                    0, log.countSteamVr());

            // Grant mic AND deliver the result through the real callback.
            // The paired hub then probes the host and dispatches once.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the grant callback must reach the host start",
                    await(() -> host.starts.get() == 1, 4000L));
            assertTrue("Grant callback must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Second tap during the (still set) launch guard window must
            // NOT launch anything — the hub never observed a real
            // launched-activity pause for the FIRST tap, so launchLeftHub
            // is still false and launchPending is still true.
            connect.performClick();
            settle(250);
            log.drain(app());
            assertEquals("Second PCVR tap while guard is still set must not launch",
                    1, log.countSteamVr());
            assertEquals("Second PCVR tap must not reach the host again",
                    1, host.starts.get());

            // Other buttons are also guarded during the in-flight launch.
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Screen tap during PCVR launch must be blocked",
                    0, log.countComponent(PcView.class.getName()));

            // Now simulate the user actually leaving and returning from
            // the launched PCVR activity. pause() with launchPending=true
            // sets launchLeftHub=true; resume() then clears launchPending.
            c.pause();
            c.resume();

            // A fresh tap on the explicit flat override now launches the
            // flat PcView (the paired Connect still routes to
            // SteamVrActivity, not Screen gaming), and the return must
            // not replay the PCVR launch.
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Screen tap after actual leave-and-return must launch",
                    1, log.countComponent(PcView.class.getName()));
            assertEquals("returning from PCVR must not relaunch PCVR",
                    1, log.countSteamVr());
        } finally {
            close(c);
        }
    }

    /**
     * A grant that arrives while the hub is paused must be queued and
     * continued exactly once on the next resume, into the action that
     * asked for it. While the round-trip is pending nothing may launch
     * and no setup / restart prompt may appear, and a later resume must
     * not replay the queued continuation.
     */
    @Test
    public void permissionGrantWhilePausedContinuesOnceOnResume() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("paired Connect without mic must request the mic once",
                    1, log.countPermissionRequests());

            // No VR-setup picker and no restart prompt may appear while
            // the permission round-trip is still pending.
            assertFalse("no VR-setup picker may appear while the mic is requested",
                    PcvrTestActions.awaitDialogTitle("No VR PC found", 300L));
            assertFalse("no restart consent may appear before the mic is granted",
                    PcvrTestActions.awaitDialogTitle("Restart VR", 100L));

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("a grant while paused must not launch before resume",
                    0, log.countSteamVr());
            assertEquals("a grant while paused must not reach the host yet",
                    0, host.probes.get());

            c.resume();
            assertTrue("the resumed hub must continue the connect",
                    await(() -> host.starts.get() == 1, 4000L));
            assertTrue("the resumed hub must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // A later resume must not replay the queued continuation.
            c.pause();
            c.resume();
            settle(250);
            log.drain(app());
            assertEquals("a later resume must not relaunch PCVR",
                    1, log.countSteamVr());
            assertEquals("a later resume must not queue a second host start",
                    1, host.starts.get());
            assertEquals("a later resume must not probe the host again",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Manual VR with no microphone permission must request it instead
     * of silently returning, and the grant must still leave the
     * explicit legacy restart consent between the user and VR.
     */
    @Test
    public void manualVr_withoutMic_requestsPermissionThenConsentThenLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue("Advanced must be reachable", PcvrTestActions.stepAdvancedIfPicker());
            assertTrue("Manual VR must be reachable", PcvrTestActions.stepManualVr());
            log.drain(app());

            assertEquals("Manual VR without mic must request the mic once",
                    1, log.countPermissionRequests());
            assertEquals("Manual VR must not fabricate consent",
                    0, log.countSteamVr());
            assertEquals("Manual VR must not reach the host before the grant",
                    0, host.probes.get());
            assertTrue("the mic request must be tagged as the manual VR action",
                    micTarget(c.get()) != 0);
            assertFalse("Manual VR must not skip the legacy consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));

            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the granted mic must surface the legacy restart consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 4000L));
            log.drain(app());
            assertEquals("the mic grant alone must not launch PCVR",
                    0, log.countSteamVr());

            AlertDialog consent = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the legacy restart consent must be showing", consent);
            Button connect = consent.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals("Connect", connect.getText().toString());
            connect.performClick();
            assertTrue("the confirmed manual start must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(250);
            log.drain(app());
            assertEquals("Manual VR must launch PCVR exactly once",
                    1, log.countSteamVr());
            assertEquals("Manual VR must consume the permission target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * A denied microphone on the Manual VR path must surface the
     * "PCVR not started" toast, never launch PCVR, and clear the
     * captured action so nothing is continued later.
     */
    @Test
    public void manualVr_micDenied_doesNotLaunch_andClearsTarget() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED});
            settle(300);
            log.drain(app());

            assertEquals("a denied mic must not launch PCVR", 0, log.countSteamVr());
            assertEquals("a denied mic must not reach the host", 0, host.probes.get());
            assertEquals("a denied mic must clear the captured action",
                    0, micTarget(c.get()));
            assertEquals(1, ShadowToast.shownToastCount());
            CharSequence msg = ShadowToast.getTextOfLatestToast();
            assertNotNull(msg);
            assertTrue("Denied toast must say PCVR not started: " + msg,
                    msg.toString().toLowerCase().contains("pcvr not started"));
            assertFalse("a denied mic must not surface the restart consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));
        } finally {
            close(c);
        }
    }

    /**
     * An empty grant result on the Manual VR path is treated as a
     * cancelled round-trip: recovery is offered, nothing launches, and
     * the captured action is dropped.
     */
    @Test
    public void manualVr_emptyGrantResult_showsRecovery_andNeverLaunches() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{});
            settle(300);
            log.drain(app());

            assertEquals("a cancelled mic round-trip must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("a cancelled mic round-trip must not reach the host",
                    0, host.probes.get());
            assertEquals("a cancelled mic round-trip must clear the captured action",
                    0, micTarget(c.get()));
            assertNotNull("a cancelled mic round-trip must offer recovery",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * A Manual VR grant that lands while the hub is paused is queued
     * and continued once on resume — into the legacy restart consent,
     * not straight into a launch.
     */
    @Test
    public void manualVr_grantWhilePaused_consentsOnceOnResume() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());
            assertEquals("a paused manual grant must not launch before resume",
                    0, log.countSteamVr());
            assertFalse("a paused manual grant must not surface consent while paused",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));

            c.resume();
            assertTrue("the resumed hub must surface the legacy consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 4000L));
            log.drain(app());
            assertEquals("the resumed manual grant must still require consent",
                    0, log.countSteamVr());
            AlertDialog consent = ShadowAlertDialog.getLatestAlertDialog();
            consent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("the confirmed manual start must launch PCVR once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // A later resume must not replay the queued continuation or
            // re-open the consent prompt.
            c.pause();
            c.resume();
            settle(300);
            log.drain(app());
            assertEquals("a later resume must not relaunch manual VR",
                    1, log.countSteamVr());
            assertEquals("a later resume must not probe the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * An explicit Cancel abandons a Manual VR permission continuation
     * that was queued while the hub was paused: the queued action is
     * dropped, so the resume surfaces neither the consent prompt nor a
     * launch.
     */
    @Test
    public void manualVr_pausedGrantCancelled_neverConsentsOrLaunches() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);

            // The user cancels the queued action before returning.
            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            c.resume();
            settle(300);
            log.drain(app());

            assertFalse("a cancelled continuation must not surface the consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));
            assertEquals("a cancelled continuation must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("a cancelled continuation must not reach the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Setup is also guarded by launchPending. A tap on Setup during
     * an in-flight launch must be a no-op. Once the user actually
     * returns from Setup, the guard releases.
     */
    @Test
    public void setupAndSettingsGuardedDuringInflightLaunch() {
        setHeadset(true);
        grantMic(true);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap Connect — one probe, one start, one launch; the launch
            // sets launchPending.
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            assertTrue("the accepted attempt must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Setup tap during in-flight launch must NOT launch SetupActivity.
            c.get().findViewById(R.id.hub_btn_setup).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Setup during PCVR launch must be blocked", 0,
                    log.countComponent("com.vibertemis.quest.hub.SetupActivity"));
            assertFalse("Setup during PCVR launch must not open the setup flow",
                    PcvrTestActions.awaitDialogTitle("Set up VR", 200L));

            // Settings tap during in-flight launch must NOT launch
            // StreamSettings.
            c.get().findViewById(R.id.hub_btn_settings).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Settings during PCVR launch must be blocked", 0,
                    log.countComponent(StreamSettings.class.getName()));

            // After actual leave-and-return, Setup opens the VR-setup
            // flow. The hub must surface a visible dialog, not
            // auto-launch anything.
            c.pause();
            c.resume();
            c.get().findViewById(R.id.hub_btn_setup).performClick();
            // The picker / searching dialog must surface because the hub
            // runs a discovery pass on its worker; the fake NSD driver
            // makes it resolve to the empty state immediately.
            assertTrue("Setup must surface a visible dialog after return",
                    PcvrTestActions.awaitDialogTitle("Set up VR", 2000L)
                    || PcvrTestActions.awaitDialogTitle("No VR PC found", 2000L)
                    || PcvrTestActions.awaitDialogTitle("Choose your PC for VR", 2000L));
        } finally {
            close(c);
        }
    }

    /**
     * Phone Connect must never request mic permission. The flat
     * PcView path does not need RECORD_AUDIO; asking for it would
     * surface an unrelated permission dialog.
     */
    @Test
    public void phone_connect_doesNotRequestMicPermission() {
        setHeadset(false);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Phone Connect must not dispatch a permission request",
                    0, log.countPermissionRequests());
            assertEquals("Phone Connect must not dispatch any component-less intent",
                    0, log.countComponentlessIntents());
            assertEquals("Phone Connect must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * Tap the paired hub's "Change PC" link and wait for the VR-setup
     * picker / empty state. The Manual VR fixtures need the Advanced
     * entry point, which only the setup flow surfaces.
     */
    private boolean stepLaunchJourneyViaSetupLink(
            ActivityController<? extends MainHubActivity> controller) {
        controller.get().findViewById(R.id.hub_btn_setup).performClick();
        return stepLaunchJourney(4000L);
    }
}