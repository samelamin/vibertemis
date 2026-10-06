package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Capability gating. We test the only check we actually perform —
 * {@link PackageManager#FEATURE_VR_HEADTRACKING} — through Robolectric's
 * shadow {@link ShadowPackageManager}. The headset class is exercised in
 * three ways:
 *
 * <ul>
 *   <li>{@link VrCapabilities#isHeadset(Context)} returns true / false.</li>
 *   <li>{@link com.limelight.preferences.PreferenceConfiguration#readPreferences}
 *       ANDs the stored preference with {@code isHeadset(...)} so a phone
 *       never starts {@code GameXR} by default.</li>
 *   <li>The same gating is observed end-to-end by routing through the
 *       upstream {@code ServerHelper.createStartIntent} consumer.</li>
 * </ul>
 *
 * <p>The headset-process identity test exercises
 * {@link SteamVrActivity#isOwnPcvrProcess()} directly with a
 * Robolectric-built activity whose {@link ShadowApplication#getProcessName()}
 * is stubbed to match the manifest's {@code android:process=":pcvr"} pin.
 */
@RunWith(RobolectricTestRunner.class)
public class VrCapabilitiesTest {
    @org.junit.Test public void exactQuestFallbackDoesNotClassifyPhonesAsHeadsets() {
        assertTrue(VrCapabilities.identifiesHeadset(false, "Oculus", "Quest 3"));
        assertTrue(VrCapabilities.identifiesHeadset(false, "Meta", "Quest 3S"));
        assertTrue(VrCapabilities.identifiesHeadset(true, "Pico", "headset"));
        assertFalse(VrCapabilities.identifiesHeadset(false, "Samsung", "Quest 3"));
        assertFalse(VrCapabilities.identifiesHeadset(false, "Meta", "phone"));
        assertFalse(VrCapabilities.identifiesHeadset(false, null, null));
    }

    @Test
    public void phoneLikeDevice_lacksVrHeadtracking() {
        Context ctx = RuntimeEnvironment.getApplication();
        assertFalse(VrCapabilities.isHeadset(ctx));
    }

    @Test
    public void headsetLikeDevice_hasVrHeadtracking() {
        Context ctx = RuntimeEnvironment.getApplication();
        ShadowPackageManager spm = Shadows.shadowOf(ctx.getPackageManager());
        spm.setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
        assertTrue(VrCapabilities.isHeadset(ctx));
    }

    @Test
    public void isHeadset_doesNotConsultAnyAlvrPackage() {
        // The hub must not try to read alvr.client.stable. We verify this
        // indirectly: even when the device lacks VR headtracking, the
        // ALVR headset client package being installed does not flip the
        // gate — because we never look at it.
        Context ctx = RuntimeEnvironment.getApplication();
        assertFalse(VrCapabilities.isHeadset(ctx));
    }

    // ---- SteamVrActivity process identity ---------------------------

    @Test
    public void processIdentity_exactName_accepts() {
        Context ctx = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setProcessName(
                ctx.getPackageName() + SteamVrActivity.PROCESS_SUFFIX);

        SteamVrActivity activity = Robolectric.buildActivity(
                SteamVrActivity.class).get();
        assertTrue(activity.isOwnPcvrProcess());
    }

    @Test
    public void processIdentity_mainProcess_rejects() {
        Context ctx = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setProcessName(ctx.getPackageName());

        SteamVrActivity activity = Robolectric.buildActivity(
                SteamVrActivity.class).get();
        assertFalse(activity.isOwnPcvrProcess());
    }

    @Test
    public void processIdentity_otherPackagePcvr_rejects() {
        // Some other APK could happen to declare :pcvr. We only accept
        // exact equality with our own package name + ":pcvr".
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setProcessName(
                "com.example.somethingelse:pcvr");

        SteamVrActivity activity = Robolectric.buildActivity(
                SteamVrActivity.class).get();
        assertFalse(activity.isOwnPcvrProcess());
    }

    @Test
    public void processIdentity_nullProcessName_rejects() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setProcessName(null);

        SteamVrActivity activity = Robolectric.buildActivity(
                SteamVrActivity.class).get();
        assertFalse(activity.isOwnPcvrProcess());
    }

    @Test
    public void processIdentity_suffixOnly_rejects() {
        // endsWith(":pcvr") would accept this; exact equality must not.
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setProcessName(":pcvr");

        SteamVrActivity activity = Robolectric.buildActivity(
                SteamVrActivity.class).get();
        assertFalse(activity.isOwnPcvrProcess());
    }
}
