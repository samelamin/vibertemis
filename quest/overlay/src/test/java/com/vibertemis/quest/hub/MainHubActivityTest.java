package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.vibertemis.quest.pcvr.VrSetupDiscovery;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Main hub lifecycle tests. We inflate {@link MainHubActivity} via
 * Robolectric so the on-screen button handlers are bound, then drive
 * the user actions and assert what
 * {@link ShadowApplication#getNextStartedActivity()} returns.
 *
 * <p>The hub exposes a single primary <b>Connect</b> button that
 * auto-detects device class:
 * <ul>
 *   <li>real headset (FEATURE_VR_HEADTRACKING true) + mic granted:
 *       Connect routes to {@link SteamVrActivity} via the explicit
 *       {@code ComponentName} with the immersive VR categories and
 *       {@code FLAG_ACTIVITY_NEW_TASK}.</li>
 *   <li>real headset + mic denied: Connect dispatches a permission
 *       request first, and never starts {@link SteamVrActivity} on
 *       denial.</li>
 *   <li>phone / non-headset: Connect routes to the flat
 *       {@link PcView} start. Mic permission is never requested.</li>
 * </ul>
 *
 * <p>The hub must:
 * <ul>
 *   <li>never launch PCVR when the device is not a headset,</li>
 *   <li>on a headset with the mic permission denied, surface the
 *       "PCVR not started" toast and never start {@link SteamVrActivity},</li>
 *   <li>on a headset with the mic permission granted, start
 *       {@link SteamVrActivity} via the explicit {@code ComponentName}
 *       with the immersive VR categories and {@code FLAG_ACTIVITY_NEW_TASK}.</li>
 *   <li>on a phone, route the primary Connect to {@link PcView}
 *       without asking for mic permission,</li>
 *   <li>guard against in-flight permission requests and pending
 *       launches so rapid taps and duplicate callbacks cannot start two
 *       activities at once,</li>
 *   <li>never auto-launch from {@code onResume} after returning from
 *       Streaming settings or the permission dialog,</li>
 *   <li>validate the permission result against the actual requested
 *       permission name, current headset status, and current grant
 *       state before launching PCVR.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class MainHubActivityTest {

    private static final int REQ_MIC_FOR_STEAMVR = MainHubActivity.REQ_MIC_FOR_STEAMVR_FOR_TEST;

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        FakeNsdHub.sharedFactory = null;
    }

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

    private ActivityController<MainHubActivity> startHub() {
        return Robolectric.buildActivity(MainHubActivity.class)
                .create().start().resume();
    }

    private static boolean isSteamVrIntent(Intent i) {
        if (i == null || i.getComponent() == null) return false;
        return "com.vibertemis.quest.hub.SteamVrActivity"
                .equals(i.getComponent().getClassName());
    }

    private static boolean isPcViewIntent(Intent i) {
        if (i == null || i.getComponent() == null) return false;
        return PcView.class.getName().equals(i.getComponent().getClassName());
    }

    /** Test hub that injects a deterministic fake NSD driver via
     *  the {@link VrSetupDiscovery.Factory} seam so the discovery
     *  completes immediately with the supplied services list
     *  (or empty when {@code services} is null/empty). Real NSD
     *  is a Robolectric no-op and would otherwise pin tests for
     *  the 8-second browse budget. */
    public static class FakeNsdHub extends MainHubActivity {
        static volatile VrSetupDiscovery.Factory sharedFactory;
        @Override protected VrSetupDiscovery createVrSetupDiscovery() {
            VrSetupDiscovery.Factory f = sharedFactory;
            if (f != null) return new VrSetupDiscovery(f);
            return super.createVrSetupDiscovery();
        }
        @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
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

    /** Inject the empty NSD driver for the next {@link #startHubWithFakeNsd()}
     *  call. Must be cleared in the {@code finally} of the test. */
    private static void useEmptyNsd() {
        FakeNsdHub.sharedFactory = () -> new EmptyDriver();
    }

    /** Variant of {@link #startHub()} that uses {@link FakeNsdHub}. */
    private ActivityController<FakeNsdHub> startHubWithFakeNsd() {
        useEmptyNsd();
        return Robolectric.buildActivity(FakeNsdHub.class)
                .create().start().resume();
    }

    /** Bounded UI wait. Drives the main looper + ShadowSystemClock
     *  until {@code condition} is true or {@code maxMillis} elapses. */
    private static boolean awaitCondition(
            java.util.function.BooleanSupplier condition, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try { Thread.sleep(20L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
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

        // The explicit flat override is hidden on phones — the
        // primary Connect already routes to flat.
        View screen = c.get().findViewById(R.id.hub_btn_screen);
        assertEquals("Flat override must be hidden on phones",
                View.GONE, screen.getVisibility());

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Walk every started activity Robolectric captured; one must
        // target PcView and none must target SteamVrActivity.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        boolean sawPcView = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
            if (isPcViewIntent(i)) sawPcView = true;
        }
        assertFalse("Phone must not launch SteamVrActivity", sawPcvr);
        assertTrue("Phone Connect must launch PcView", sawPcView);
    }

    /**
     * Headset, mic denied. The click first dispatches a permission
     * request; we drain that, deliver a denied result through the
     * real {@code onRequestPermissionsResult} callback, then assert no
     * SteamVrActivity launch and the explicit "PCVR not started" toast.
     */
    @Test
    public void headset_micDenied_doesNotLaunchPcvr_andShowsDeniedToast() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Drain the permission-request side effect. We don't assert its
        // exact type — only that the PCVR launch is not among the
        // captured started activities.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Mic denied must not launch SteamVrActivity at click time",
                sawPcvr);

        // Deliver the denial through the real permission callback path.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_DENIED});

        // Drain again — denial must not start any follow-up native intent.
        boolean sawPcvrAfter = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvrAfter = true;
        }
        assertFalse("Mic denied must not launch SteamVrActivity after denial",
                sawPcvrAfter);

        assertEquals(1, ShadowToast.shownToastCount());
        CharSequence msg = ShadowToast.getTextOfLatestToast();
        assertNotNull(msg);
        assertTrue("Denied toast must say PCVR not started: " + msg,
                msg.toString().toLowerCase().contains("pcvr not started"));
    }

    /**
     * Headset, mic granted at click time. With the new unpaired-headset
     * journey the hub opens the VR-setup picker / empty state, the
     * test drives the empty-state dialog through Advanced → Manual
     * VR → Connect restart confirmation, and SteamVrActivity
     * launches with the explicit ComponentName, immersive VR
     * categories, and FLAG_ACTIVITY_NEW_TASK.
     */
    @Test
    public void headset_micGranted_launchesExplicitSteamVrActivity() {
        setHeadset(true);
        grantMic(true);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();

        // The explicit flat override is visible on a headset because
        // it differs from the primary Connect target there.
        View screen = c.get().findViewById(R.id.hub_btn_screen);
        assertEquals("Flat override must be visible on headsets",
                View.VISIBLE, screen.getVisibility());

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();

        // Bounded UI wait for the picker / empty-state dialog to
        // surface, then drive the journey to the restart-consent
        // confirmation. Production dialog order is preserved exactly:
        // picker empty-state → Advanced VR pairing → Connect to PCVR.
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Walk the captured started activities; find the PCVR one.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent pcvr = null;
        Intent i;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) {
                pcvr = i;
                break;
            }
        }
        assertNotNull("Headset + granted mic must launch SteamVrActivity",
                pcvr);
        assertEquals("com.vibertemis.quest.hub.SteamVrActivity",
                pcvr.getComponent().getClassName());
        assertTrue(pcvr.hasCategory("com.oculus.intent.category.VR"));
        assertTrue(pcvr.hasCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD"));
        assertTrue((pcvr.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
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

        View screen = c.get().findViewById(R.id.hub_btn_screen);
        screen.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        boolean sawPcView = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
            if (isPcViewIntent(i)) sawPcView = true;
        }
        assertFalse("Explicit flat override on a headset must NOT launch PCVR",
                sawPcvr);
        assertTrue("Explicit flat override on a headset must launch PcView",
                sawPcView);
    }

    /**
     * Rapid double-tap on Connect with mic already granted must not
     * start two SteamVrActivity instances. The launch guard is set on
     * the first tap and only released when the user returns to the
     * hub.
     */
    @Test
    public void rapidTapConnect_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(true);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        // Drive the unpaired-headset journey: picker → Advanced
        // → Manual VR → Connect. The connectPending guard must
        // survive the rapid taps while the launch dialog is in
        // flight.
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        // Second tap before the first launch has been consumed by the
        // user coming back to the hub.
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        int pcvrCount = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrCount++;
        }
        assertEquals("Rapid taps must yield exactly one PCVR launch",
                1, pcvrCount);
    }

    /**
     * Rapid double-tap when mic is not yet granted must still only
     * dispatch one permission request — the request guard blocks the
     * second tap until the first result returns.
     */
    @Test
    public void rapidTapConnect_micNotGranted_dispatchesOneRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // No SteamVrActivity launch should happen while permission is
        // pending — the second tap is ignored by the request guard.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Permission pending must not launch SteamVrActivity",
                sawPcvr);
    }

    /**
     * A permission result for a different permission name must be
     * ignored, even if the grant is positive. The hub only acts on
     * RECORD_AUDIO results.
     */
    @Test
    public void mismatchedPermissionResult_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        // Dispatch a Connect tap so a permission request is in flight.
        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Deliver a result whose permission name is not RECORD_AUDIO.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.CAMERA},
                new int[]{PackageManager.PERMISSION_GRANTED});
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Mismatched permission must not launch PCVR", sawPcvr);
    }

    /**
     * Empty grant results array (the system killed the activity before
     * delivering) must surface the recovery dialog and never launch
     * PCVR.
     */
    @Test
    public void emptyGrantResults_doesNotLaunch_showsRecovery() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{});

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Empty grant results must not launch PCVR", sawPcvr);
    }

    /**
     * Duplicate permission results for the same request code must be
     * ignored. The first result consumes the in-flight request flag;
     * any subsequent delivery is a no-op.
     */
    @Test
    public void duplicatePermissionResult_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(true);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));

        // First result — granted, drives picker → Advanced → Manual
        // VR → Connect. The launch fires SteamVrActivity once.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Second delivery of the same grant — must be a no-op.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        int pcvrCount = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrCount++;
        }
        assertEquals("Duplicate permission results must yield one launch",
                1, pcvrCount);
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
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA},
                new int[]{PackageManager.PERMISSION_DENIED,
                        PackageManager.PERMISSION_GRANTED});

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Recording denied must not launch PCVR even if camera granted",
                sawPcvr);
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
        ActivityController<MainHubActivity> c = startHub();

        // Tap settings -> simulate the user going there and coming back.
        View settings = c.get().findViewById(R.id.hub_btn_settings);
        settings.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Capture and clear so we can isolate the onResume-side starts.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        while ((i = app.getNextStartedActivity()) != null) {
            assertEquals("Settings tap must launch StreamSettings",
                    StreamSettings.class.getName(),
                    i.getComponent().getClassName());
        }

        // Simulate returning from the settings screen.
        c.get().onResume();

        // Nothing else should have started after the resume call.
        int extra = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            extra++;
        }
        assertEquals("Returning from settings must not auto-launch any activity",
                0, extra);
    }

    /**
     * The request guard survives activity recreation (configuration
     * change). When the activity is recreated mid-permission-dialog,
     * the saved state restores {@code requestPending} so a second
     * dispatch of the same request code is suppressed.
     */
    @Test
    public void savedState_restoresRequestGuardAcrossRecreation() {
        setHeadset(true);
        grantMic(false);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();

        View root = c.get().findViewById(R.id.hub_btn_connect);
        root.performClick();
        // No picker dialog yet — the permission request dialog is
        // showing, so confirmRestartIfShown is a no-op until mic
        // is granted.
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        // Save the state mid-request.
        Bundle state = new Bundle();
        c.get().onSaveInstanceState(state);
        assertTrue("Request flag must be persisted",
                state.getBoolean("vq_hub_request_pending"));

        // Recreate the activity with that state. The request flag must
        // still be true so the duplicate-result guard still ignores
        // any stray callback.
        ActivityController<FakeNsdHub> reborn =
                Robolectric.buildActivity(FakeNsdHub.class, null, state)
                        .create().start().resume();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Without the persistent flag, the test could only assert
        // "no launches because no permission". With the flag, we
        // can additionally assert: re-tapping while the request is
        // still pending does NOT dispatch a second permission request.
        View rootReborn = reborn.get().findViewById(R.id.hub_btn_connect);
        // First result — granted. Test still denies, but the request
        // flag is consumed by the first delivery.
        reborn.get().onRequestPermissionsResult(
                MainHubActivity.REQ_MIC_FOR_STEAMVR_FOR_TEST,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int first = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) first++;
        }
        // hasMicPermission() is false under Robolectric, so the launch
        // is suppressed at the defensive re-check.
        assertEquals("Grant without effective mic must not launch",
                0, first);

        // Now grant for real. A subsequent tap drives picker →
        // Advanced → Manual VR → Connect → SteamVrActivity.
        app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        rootReborn.performClick();
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int second = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) second++;
        }
        assertEquals("New tap on recreated hub with mic granted must launch",
                1, second);
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
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Tap Connect — dispatch sends a permission request and sets
        // requestPending. No SteamVrActivity launch yet (mic denied).
        View connect = c.get().findViewById(R.id.hub_btn_connect);
        connect.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int pcvrBeforeGrant = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrBeforeGrant++;
        }
        assertEquals("PCVR tap before grant must not launch",
                0, pcvrBeforeGrant);

        // Simulate the dialog pausing the hub BEFORE the grant
        // callback returns. Some platform versions do pause the
        // hosting activity here; launchLeftHub is still false
        // (launchPending was never set) so onResume must keep the
        // guard set.
        c.pause();
        c.resume();

        // Grant mic AND deliver the result through the real callback.
        // The unpaired journey must then run picker → Advanced →
        // Manual VR → Connect restart confirmation before
        // SteamVrActivity fires.
        app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int pcvrAfterGrant = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrAfterGrant++;
        }
        assertEquals("Grant callback must launch PCVR exactly once",
                1, pcvrAfterGrant);

        // Second tap during the (still set) launch guard window must
        // NOT launch anything — the hub never observed a real
        // launched-activity pause for the FIRST tap, so launchLeftHub
        // is still false and launchPending is still true.
        connect.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int pcvrDouble = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrDouble++;
        }
        assertEquals("Second PCVR tap while guard is still set must not launch",
                0, pcvrDouble);

        // Other buttons are also guarded during the in-flight launch.
        View screen = c.get().findViewById(R.id.hub_btn_screen);
        screen.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int screenBlocked = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (i.getComponent() != null
                    && PcView.class.getName().equals(
                            i.getComponent().getClassName())) {
                screenBlocked++;
            }
        }
        assertEquals("Screen tap during PCVR launch must be blocked",
                0, screenBlocked);

        // Now simulate the user actually leaving and returning from
        // the launched PCVR activity. pause() with launchPending=true
        // sets launchLeftHub=true; resume() then clears launchPending.
        c.pause();
        c.resume();

        // A fresh tap on the explicit flat override now launches the
        // flat PcView (the unpaired-headset journey still routes
        // through Connect → SteamVrActivity, not Screen gaming).
        screen.performClick();
        int screenAfterReturn = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (i.getComponent() != null
                    && PcView.class.getName().equals(
                            i.getComponent().getClassName())) {
                screenAfterReturn++;
            }
        }
        assertEquals("Screen tap after actual leave-and-return must launch",
                1, screenAfterReturn);
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
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Tap Connect — drives picker → Advanced → Manual VR →
        // Connect restart confirmation; the launch sets launchPending.
        View connect = c.get().findViewById(R.id.hub_btn_connect);
        connect.performClick();
        assertTrue("picker must surface for unpaired Connect",
                stepLaunchJourney(4000L));
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int first = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) first++;
        }
        assertEquals(1, first);

        // Setup tap during in-flight launch must NOT launch SetupActivity.
        View setup = c.get().findViewById(R.id.hub_btn_setup);
        setup.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int setupDuringLaunch = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if ("com.vibertemis.quest.hub.SetupActivity".equals(
                    i.getComponent().getClassName())) {
                setupDuringLaunch++;
            }
        }
        assertEquals("Setup during PCVR launch must be blocked", 0,
                setupDuringLaunch);

        // Settings tap during in-flight launch must NOT launch
        // StreamSettings.
        View settings = c.get().findViewById(R.id.hub_btn_settings);
        settings.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
        int settingsDuringLaunch = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if ("com.limelight.preferences.StreamSettings".equals(
                    i.getComponent().getClassName())) {
                settingsDuringLaunch++;
            }
        }
        assertEquals("Settings during PCVR launch must be blocked", 0,
                settingsDuringLaunch);

        // After actual leave-and-return, Setup opens the VR-setup
        // flow (Searching / NSD discovery). The hub must surface a
        // visible dialog, not auto-launch anything.
        c.pause();
        c.resume();
        setup.performClick();
        // The picker / empty-state dialog must surface because the
        // fake NSD driver fires onDiscoveryStopped synchronously.
        assertTrue("Setup must surface a visible dialog after return",
                com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                        "No VR PC found", 2000L)
                || com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                        "Set up VR", 2000L));
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

        View connect = c.get().findViewById(R.id.hub_btn_connect);
        connect.performClick();
        com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPermissionRequest = false;
        boolean sawPcView = false;
        while ((i = app.getNextStartedActivity()) != null) {
            // Robolectric records permission requests via the
            // ShadowApplication too — any non-null component means
            // the hub tried to start a permission flow.
            if (i.getComponent() == null) {
                sawPermissionRequest = true;
            }
            if (isPcViewIntent(i)) {
                sawPcView = true;
            }
        }
        assertFalse("Phone Connect must not dispatch a permission request",
                sawPermissionRequest);
        assertTrue("Phone Connect must launch PcView", sawPcView);
    }
    @Test public void permissionGrantWhilePausedContinuesOnceOnResume() {
        setHeadset(true);
        grantMic(false);
        ActivityController<FakeNsdHub> controller = startHubWithFakeNsd();
        try {
            MainHubActivity hub = controller.get();
            hub.findViewById(R.id.hub_btn_connect).performClick();
            Intent permission = Shadows.shadowOf(hub).getNextStartedActivity();
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permission.getAction());
            controller.pause();
            grantMic(true);
            hub.onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            controller.resume();
            assertTrue(com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle("No VR PC found", 4000));
            android.app.AlertDialog first = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            controller.pause().resume();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertSame(first, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            assertFalse(first.isShowing());
            assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
        } finally { controller.pause().stop().destroy(); }
    }
}
