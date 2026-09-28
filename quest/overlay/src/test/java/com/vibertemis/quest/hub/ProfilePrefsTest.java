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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Real preference round-trip via the actual
 * {@link PreferenceManager#getDefaultSharedPreferences} API. Robolectric
 * drives the full Android preference stack end to end, so a green test
 * means the values land where {@code PreferenceConfiguration.readPreferences}
 * will see them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class ProfilePrefsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    @Test
    public void applyHome_writesUpstreamKeys() {
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HOME);
        assertEquals("2560x1440",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("72",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(40000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("auto",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    @Test
    public void applyTravel_writesUpstreamKeys() {
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_TRAVEL);
        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("auto",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    @Test
    public void applyHome_dropsLegacyCombinedKey() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1280x720x30")
                .commit();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HOME);
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx)
                .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    @Test
    public void applyTravel_dropsLegacyCombinedKey() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1920x1080x60")
                .commit();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_TRAVEL);
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx)
                .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    /**
     * Custom is a no-op against upstream keys. It records the selection
     * in the hub prefs only.
     */
    @Test
    public void applyCustom_preservesUserSettings() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 9000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();

        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_CUSTOM);

        assertEquals("1280x720",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("30",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(9000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("forceh265",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    /**
     * Custom does not remove the legacy combined key either — "preserve
     * user settings, unchanged" means leave everything upstream alone.
     */
    @Test
    public void applyCustom_preservesLegacyCombinedKey() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1280x720x30")
                .commit();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_CUSTOM);
        assertTrue("Custom must NOT remove legacy key",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    /**
     * Unknown profile names must not write anywhere — no upstream keys,
     * no legacy key, no hub-prefs selection.
     */
    @Test
    public void applyUnknown_writesNothing() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 9000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1280x720x30")
                .commit();

        ProfileApplier.applyProfile(ctx, "nonsense");

        assertEquals("1280x720",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("30",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(9000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("forceh265",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
        assertTrue("Unknown profile must NOT remove legacy key",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    /**
     * ProfileApplier reads/writes via PreferenceManager.getDefaultSharedPreferences.
     * The upstream consumer is PreferenceConfiguration.readPreferences — we
     * assert the round-trip actually reaches it, including the format mapping.
     */
    @Test
    public void applyHome_thenReadByUpstreamPreferenceConfiguration() {
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HOME);
        PreferenceConfiguration s = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(2560, s.width);
        assertEquals(1440, s.height);
        assertEquals(72, s.fps);
        assertEquals(40000, s.bitrate);
        assertEquals(PreferenceConfiguration.FormatOption.AUTO, s.videoFormat);
    }

    @Test
    public void applyTravel_thenReadByUpstreamPreferenceConfiguration() {
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_TRAVEL);
        PreferenceConfiguration s = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(1920, s.width);
        assertEquals(1080, s.height);
        assertEquals(60, s.fps);
        assertEquals(17000, s.bitrate);
        assertEquals(PreferenceConfiguration.FormatOption.AUTO, s.videoFormat);
    }

    /**
     * Critical no-write assertion: reading/inflating the preferences must
     * NOT mutate the upstream keys we own. We snapshot the prefs, drive
     * the upstream consumer (which reads the prefs), then compare. Nothing
     * in the read path should rewrite {@code K_RES}, {@code K_FPS}, or
     * {@code K_VIDEO_FORMAT}.
     */
    @Test
    public void openingPreferencesDoesNotWrite_upstreamKeysUnchanged() {
        // Seed a known sentinel state and snapshot it.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();

        PreferenceConfiguration s1 = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(1920, s1.width);
        assertEquals(1080, s1.height);
        assertEquals(60, s1.fps);
        assertEquals(17000, s1.bitrate);
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, s1.videoFormat);

        PreferenceConfiguration s2 = PreferenceConfiguration.readPreferences(ctx);
        // All four keys unchanged.
        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("forceh265",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }
}
