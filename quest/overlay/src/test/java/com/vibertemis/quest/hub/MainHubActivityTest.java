package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.view.View;

import com.limelight.PcView;
import com.limelight.R;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Main hub lifecycle tests. We inflate {@link MainHubActivity} via
 * Robolectric so the on-screen button handlers are bound, then drive
 * the user actions and assert what
 * {@link ShadowApplication#getNextStartedActivity()} returns.
 *
 * <p>The hub must:
 * <ul>
 *   <li>never launch PCVR when the device is not a headset,</li>
 *   <li>on a headset with the mic permission denied, surface the
 *       "PCVR not started" toast and never start {@link SteamVrActivity},</li>
 *   <li>on a headset with the mic permission granted, start
 *       {@link SteamVrActivity} via the explicit {@code ComponentName}
 *       with the immersive VR categories and {@code FLAG_ACTIVITY_NEW_TASK}.</li>
 * </ul>
 *
 * <p>The denied-mic test drives the real permission flow: the click
 * dispatches a permission request, we drain that intent, deliver a
 * denied result via {@link Activity#onRequestPermissionsResult}, then
 * assert no SteamVrActivity launch and no follow-up native intent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class MainHubActivityTest {

    private static final int REQ_MIC_FOR_STEAMVR = 0x5356;

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
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

    /**
     * On a phone (no headtracking), tapping the SteamVR row must NOT
     * launch SteamVrActivity.
     */
    @Test
    public void phone_tapSteamVr_doesNotLaunchPcvr() {
        setHeadset(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

        // Walk every started activity Robolectric captured; none must
        // target SteamVrActivity.
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;
        boolean sawPcvr = false;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) sawPcvr = true;
        }
        assertFalse("Phone must not launch SteamVrActivity", sawPcvr);
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

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

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
     * Headset, mic granted at click time. The hub launches
     * SteamVrActivity via the explicit ComponentName with the
     * immersive VR categories and FLAG_ACTIVITY_NEW_TASK.
     */
    @Test
    public void headset_micGranted_launchesExplicitSteamVrActivity() {
        setHeadset(true);
        grantMic(true);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

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
     * Screen gaming row launches the upstream PcView regardless of
     * headset / mic state.
     */
    @Test
    public void screenGaming_tap_launchesPcView() {
        setHeadset(false);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_screen);
        root.performClick();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent pc = null;
        Intent i;
        while ((i = app.getNextStartedActivity()) != null) {
            if (i.getComponent() != null
                    && PcView.class.getName().equals(
                            i.getComponent().getClassName())) {
                pc = i;
                break;
            }
        }
        assertNotNull(pc);
        assertEquals(PcView.class.getName(), pc.getComponent().getClassName());
    }
}
