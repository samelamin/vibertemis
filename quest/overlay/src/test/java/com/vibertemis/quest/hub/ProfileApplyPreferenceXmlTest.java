package com.vibertemis.quest.hub;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.limelight.preferences.StreamSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Real XML inflation of {@code R.xml.preferences} through the upstream
 * {@link StreamSettings.SettingsFragment}, then a real
 * {@link ProfileApplyPreference#onClick()} on the inflated preference
 * instance.
 *
 * <p>{@link ProfileApplyPreference} overrides the framework hook
 * {@link Preference#onClick()}, not {@code OnPreferenceClickListener}.
 * Calling that hook through the public reflection entry point
 * simulates a real user tap; we never attach a listener, never rebuild
 * the XML.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class ProfileApplyPreferenceXmlTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    private ProfileApplyPreference inflatePreference(String key) {
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class).create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        Preference pref = frag.findPreference(key);
        assertNotNull("Missing preference " + key, pref);
        assertTrue(key + " must be a ProfileApplyPreference",
                pref instanceof ProfileApplyPreference);
        return (ProfileApplyPreference) pref;
    }

    /** Invoke the real framework {@code onClick()} hook. */
    private static void tap(Preference pref) {
        try {
            Method m = Preference.class.getDeclaredMethod("onClick");
            m.setAccessible(true);
            m.invoke(pref);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke Preference.onClick()", e);
        }
    }

    @Test
    public void xmlTapHome_writesCanonical_andMarksAppliedOnce() {
        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");

        tap(pref);
        // Tap again — must NOT stack "[Applied]" prefix.
        tap(pref);
        tap(pref);

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

        CharSequence summary = pref.getSummary();
        assertNotNull(summary);
        assertTrue("Summary must start with [Applied]: " + summary,
                summary.toString().startsWith("[Applied] "));
        // Exactly one "[Applied]" prefix, not stacked.
        assertEquals(1, countOccurrences(summary.toString(), "[Applied]"));
    }

    @Test
    public void xmlTapTravel_writesCanonical() {
        ProfileApplyPreference pref = inflatePreference("vq_profile_travel_apply");
        tap(pref);

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
    public void xmlTapCustom_writesNothing() {
        // Seed a sentinel; Custom must not touch it.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 9000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();

        ProfileApplyPreference pref = inflatePreference("vq_profile_custom_apply");
        tap(pref);

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

    @Test
    public void xmlTapHome_clearsLegacyCombinedKey() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1280x720x30")
                .commit();
        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");
        tap(pref);
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx)
                .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            n++;
            idx += sub.length();
        }
        return n;
    }
}

