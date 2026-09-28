package com.vibertemis.quest.hub;

import android.content.Context;
import android.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Regression tests for the synthetic-depth default.
 *
 * <p>Upstream {@code PreferenceConfiguration.DEFAULT_VR_DEPTH_SOURCE}
 * was {@code "model"} and the XML {@code defaultValue} on
 * {@code list_vr_depth_source} was {@code "model"} — so a fresh install
 * ran the depth model by default. The runtime cost is significant; this
 * preview ships with both defaults flipped to {@code "off"}. The user
 * can still opt in to {@code "model"} explicitly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class PreferenceConfigurationDepthDefaultTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    /** Fresh install: no stored value, default falls through to "off" -> mode 0. */
    @Test
    public void freshDefault_isOff_mode0() {
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals("default depth mode must be off (0)",
                0, cfg.vrDepthMode);
    }

    /** Explicit user choice of "model" is preserved as mode 6. */
    @Test
    public void explicitModelChoice_preserved_mode6() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_vr_depth_source", "model")
                .commit();
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals("explicit model choice must be preserved",
                6, cfg.vrDepthMode);
    }

    /** Other synthetic depth choices still work after the default flip. */
    @Test
    public void explicitSyntheticFlat_preserved_mode1() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_vr_depth_source", "flat")
                .commit();
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(1, cfg.vrDepthMode);
    }
}
