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
 * Regression tests for the synthetic-depth default and the collapsed
 * depth-source mapping.
 *
 * <p>Upstream {@code PreferenceConfiguration.DEFAULT_VR_DEPTH_SOURCE}
 * was {@code "model"} and the XML {@code defaultValue} on
 * {@code list_vr_depth_source} was {@code "model"} — so a fresh
 * install ran the depth model by default. The runtime cost is
 * significant; this preview ships with both defaults flipped to
 * {@code "off"}.
 *
 * <p>The depth-source switch is collapsed: synthetic test patterns
 * (flat, ramp, blob, eyetest, shifttest) are filtered out of the
 * inflated list at SettingsFragment.onCreate, so the only values
 * that reach {@code readPreferences} are {@code "off"} (default)
 * and {@code "model"} (explicit opt-in). {@code "model"} maps to 6;
 * everything else (including any stale flat/ramp/blob/eyetest/
 * shifttest value still on disk) maps to 0.
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

    private void putDepthSource(String value) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_vr_depth_source", value)
                .commit();
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
        putDepthSource("model");
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals("explicit model choice must be preserved",
                6, cfg.vrDepthMode);
    }

    /**
     * Every synthetic test pattern still in the upstream enum maps
     * to mode 0 now — the inflated UI no longer offers them, but a
     * stale value on disk must not silently enable the depth model.
     */
    @Test
    public void syntheticPatterns_mapToMode0() {
        for (String synthetic : new String[]{
                "flat", "ramp", "blob", "eyetest", "shifttest"}) {
            putDepthSource(synthetic);
            PreferenceConfiguration cfg =
                    PreferenceConfiguration.readPreferences(ctx);
            assertEquals(synthetic + " must map to off (0)",
                    0, cfg.vrDepthMode);
        }
    }

    /** Unknown / corrupted values also map to 0. */
    @Test
    public void unknownValue_mapsToMode0() {
        putDepthSource("not-a-real-depth-source");
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals("unknown depth source must map to off (0)",
                0, cfg.vrDepthMode);
    }
}
