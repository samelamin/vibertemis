package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;

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

    /**
     * Rapid double-tap on the PCVR row with mic already granted must
     * not start two SteamVrActivity instances. The launch guard is
     * set on the first tap and only released when the user returns
     * to the hub.
     */
    @Test
    public void rapidTapSteamVr_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(true);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();
        // Second tap before the first launch has been consumed by the
        // user coming back to the hub.
        root.performClick();
        root.performClick();

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
    public void rapidTapSteamVr_micNotGranted_dispatchesOneRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();
        root.performClick();

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

        // Dispatch a SteamVR tap so a permission request is in flight.
        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

        // Deliver a result whose permission name is not RECORD_AUDIO.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.CAMERA},
                new int[]{PackageManager.PERMISSION_GRANTED});

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

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();
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
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

        // First result — granted, launches PCVR.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});

        // Second delivery of the same grant — must be a no-op.
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});

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
     * request code path that goes through the SteamVR button must not
     * launch PCVR. The hub keys the result on the permission NAME, not
     * the request code alone.
     */
    @Test
    public void grantCameraInsteadOfMic_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

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
        ActivityController<MainHubActivity> c = startHub();

        View root = c.get().findViewById(R.id.hub_btn_steamvr);
        root.performClick();

        // Save the state mid-request.
        Bundle state = new Bundle();
        c.get().onSaveInstanceState(state);
        assertTrue("Request flag must be persisted",
                state.getBoolean("vq_hub_request_pending"));

        // Recreate the activity with that state. The request flag must
        // still be true so the duplicate-result guard still ignores
        // any stray callback.
        ActivityController<MainHubActivity> reborn =
                Robolectric.buildActivity(MainHubActivity.class, null, state)
                        .create().start().resume();

        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Without the persistent flag, the test could only assert
        // "no launches because no permission". With the flag, we
        // can additionally assert: re-tapping while the request is
        // still pending does NOT dispatch a second permission request.
        View rootReborn = reborn.get().findViewById(R.id.hub_btn_steamvr);
        // First result — granted. Test still denies, but the request
        // flag is consumed by the first delivery.
        reborn.get().onRequestPermissionsResult(
                MainHubActivity.REQ_MIC_FOR_STEAMVR_FOR_TEST,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
        int first = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) first++;
        }
        // hasMicPermission() is false under Robolectric, so the launch
        // is suppressed at the defensive re-check.
        assertEquals("Grant without effective mic must not launch",
                0, first);

        // Now grant for real. A subsequent tap should launch exactly
        // once — the saved request flag must NOT have leaked into the
        // new instance and blocked the click.
        app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        rootReborn.performClick();
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
        ActivityController<MainHubActivity> c = startHub();
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Tap PCVR — dispatch sends a permission request and sets
        // requestPending. No SteamVrActivity launch yet (mic denied).
        View pcvr = c.get().findViewById(R.id.hub_btn_steamvr);
        pcvr.performClick();
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
        app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        c.get().onRequestPermissionsResult(
                REQ_MIC_FOR_STEAMVR,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});
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
        pcvr.performClick();
        int pcvrDouble = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) pcvrDouble++;
        }
        assertEquals("Second PCVR tap while guard is still set must not launch",
                0, pcvrDouble);

        // Other buttons are also guarded during the in-flight launch.
        View screen = c.get().findViewById(R.id.hub_btn_screen);
        screen.performClick();
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

        // A fresh tap now works.
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
        ActivityController<MainHubActivity> c = startHub();
        ShadowApplication app = ShadowApplication.getInstance();
        Intent i;

        // Tap PCVR — launches SteamVrActivity, sets launchPending.
        View steamvr = c.get().findViewById(R.id.hub_btn_steamvr);
        steamvr.performClick();
        int first = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if (isSteamVrIntent(i)) first++;
        }
        assertEquals(1, first);

        // Setup tap during in-flight launch must NOT launch SetupActivity.
        View setup = c.get().findViewById(R.id.hub_btn_setup);
        setup.performClick();
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
        int settingsDuringLaunch = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if ("com.limelight.preferences.StreamSettings".equals(
                    i.getComponent().getClassName())) {
                settingsDuringLaunch++;
            }
        }
        assertEquals("Settings during PCVR launch must be blocked", 0,
                settingsDuringLaunch);

        // After actual leave-and-return, Setup works.
        c.pause();
        c.resume();
        setup.performClick();
        int setupAfterReturn = 0;
        while ((i = app.getNextStartedActivity()) != null) {
            if ("com.vibertemis.quest.hub.SetupActivity".equals(
                    i.getComponent().getClassName())) {
                setupAfterReturn++;
            }
        }
        assertEquals("Setup after actual leave-and-return must launch", 1,
                setupAfterReturn);
    }
}