package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the line 798 fix in
 * {@code PreferenceConfiguration.readPreferences}: the in-memory
 * {@code config.enableVrMode} is ANDed with
 * {@link VrCapabilities#isHeadset(Context)} so the stored preference
 * value is honoured on a headset but ignored on a phone.
 *
 * <p>Upstream defaults {@code checkbox_enable_vr_mode} to {@code true}.
 * Without this AND, every phone-side install would default to launching
 * {@code com.limelight.GameXR} (and either crash the missing immersive
 * container or hand the headset shell a 2D panel). The user's stored
 * "VR off" choice must still be respected on a headset.
 *
 * <p>The stored SharedPreferences value is never overwritten by these
 * tests or by the production code path — the AND happens in memory at
 * read time, so opening the preferences screen on a phone does not flip
 * the underlying checkbox.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class PreferenceConfigurationVrModeTest {

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

    private void setStoredVrMode(boolean v) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean("checkbox_enable_vr_mode", v)
                .commit();
    }

    /**
     * The P1 regression: a phone with the upstream default (no stored
     * value, default true) must NOT start GameXR. Without the AND on
     * line 798 this test fails with {@code enableVrMode == true}.
     */
    @Test
    public void phone_defaultStoredTrue_routesFalse() {
        setHeadset(false);
        setStoredVrMode(true);
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertFalse("phone with default pref must NOT enable VR mode",
                cfg.enableVrMode);
        // The stored value is preserved — the AND only changes the
        // in-memory field, not the user's checkbox.
        assertTrue("stored preference must remain untouched",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getBoolean("checkbox_enable_vr_mode", false));
    }

    /**
     * A headset with the upstream default must keep VR mode on.
     */
    @Test
    public void headset_defaultStoredTrue_routesTrue() {
        setHeadset(true);
        setStoredVrMode(true);
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertTrue(cfg.enableVrMode);
    }

    /**
     * An explicit "VR off" choice on a headset must be honoured — the
     * user disabled it, so we route to flat Game.
     */
    @Test
    public void headset_explicitOff_storedFalse_routesFalse() {
        setHeadset(true);
        setStoredVrMode(false);
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertFalse(cfg.enableVrMode);
    }

    /**
     * Belt-and-braces: opening the preferences screen on a phone must
     * not write to the stored checkbox. The user's stored default-true
     * stays default-true; only the in-memory read is false.
     */
    @Test
    public void openingPreferences_doesNotMutateStoredCheckbox() {
        setHeadset(false);
        setStoredVrMode(true);
        // First read writes nothing back (the read path is read-only).
        PreferenceConfiguration.readPreferences(ctx);
        // Second read produces the same in-memory value.
        assertFalse(PreferenceConfiguration.readPreferences(ctx).enableVrMode);
        // And the stored checkbox is still true.
        assertTrue(PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("checkbox_enable_vr_mode", false));
    }
}
