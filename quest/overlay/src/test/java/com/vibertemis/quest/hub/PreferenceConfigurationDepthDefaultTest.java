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
 * <p>From Moonlight XR v0.4 the default depth source is upstream's
 * {@code "zipdepth"}, a model light enough for every Quest, and a Big
 * Screen session starts in 3D with a 3D button on its bar to switch it
 * off for the session. {@code "off"} stays an explicit choice.
 *
 * <p>The synthetic test patterns (flat, ramp, blob, eyetest, shifttest)
 * are filtered out of the inflated list at SettingsFragment.onCreate in
 * both builds, and any stale or unknown value still on disk maps to 0
 * rather than to a pattern.
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

    /** Fresh install: no stored value, default is upstream's ZipDepth model -> mode 6,
     *  so 3D is available in a Big Screen session and switched from its 3D button. */
    @Test
    public void freshDefault_isZipDepth_mode6() {
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals("default depth mode must be the depth model (6)",
                6, cfg.vrDepthMode);
        assertEquals("zipdepth", cfg.vrDepthModel);
    }

    /** An explicit "off" is preserved as mode 0. */
    @Test
    public void explicitOff_preserved_mode0() {
        putDepthSource("off");
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(0, cfg.vrDepthMode);
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
