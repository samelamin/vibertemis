package com.vibertemis.quest.hub;

import android.content.Context;
import android.os.Looper;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

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
 *
 * <p>Preview 2: tapping a preference opens a confirmation dialog. The
 * AlertDialog positive button posts its click listener onto the main
 * looper Handler, so we drain that looper after {@code performClick()}
 * to fire the real callback.
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

        // Simulate a Quest3-class display that supports 1440p / 4K and
        // 72 / 90 / 120 Hz refresh rates by widening the inflated
        // ListPreference entry lists. Robolectric's default display
        // only reports 60 Hz, so the upstream post-filter strips
        // 72 / 90 / 120 from the list. Without this shim, the
        // per-instance capability guard fails closed and the Apply
        // test would only exercise the unsupported path.
        android.preference.ListPreference resPref =
                (android.preference.ListPreference) frag.findPreference("list_resolution");
        android.preference.ListPreference fpsPref =
                (android.preference.ListPreference) frag.findPreference("list_fps");
        assertNotNull("list_resolution must be inflated", resPref);
        assertNotNull("list_fps must be inflated", fpsPref);
        widenInflatedEntries(resPref, fpsPref);

        Preference pref = frag.findPreference(key);
        assertNotNull("Missing preference " + key, pref);
        assertTrue(key + " must be a ProfileApplyPreference",
                pref instanceof ProfileApplyPreference);
        return (ProfileApplyPreference) pref;
    }

    /**
     * Replace the inflated ListPreference entries with a complete list
     * that includes every preset's target value. Robolectric's display
     * post-filter strips high refresh rates and 4K; this widens the
     * lists so the per-instance guard sees the values the test
     * applies. Tests that want to exercise the unsupported path pass
     * a smaller set explicitly.
     */
    private static void widenInflatedEntries(android.preference.ListPreference resPref,
                                             android.preference.ListPreference fpsPref) {
        String[] wantedRes = new String[]{
                "1280x720", "1920x1080", "2560x1440", "3840x2160"};
        String[] wantedFps = new String[]{"30", "60", "72", "90", "120"};

        CharSequence[] newResEntries = new CharSequence[wantedRes.length];
        CharSequence[] newResValues = new CharSequence[wantedRes.length];
        for (int i = 0; i < wantedRes.length; i++) {
            newResEntries[i] = wantedRes[i];
            newResValues[i] = wantedRes[i];
        }
        resPref.setEntries(newResEntries);
        resPref.setEntryValues(newResValues);

        CharSequence[] newFpsEntries = new CharSequence[wantedFps.length];
        CharSequence[] newFpsValues = new CharSequence[wantedFps.length];
        for (int i = 0; i < wantedFps.length; i++) {
            newFpsEntries[i] = wantedFps[i] + " fps";
            newFpsValues[i] = wantedFps[i];
        }
        fpsPref.setEntries(newFpsEntries);
        fpsPref.setEntryValues(newFpsValues);
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
    public void tapHome_confirmed_writesCanonical_andSummaryUnchanged() {
        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");
        tap(pref);
        // Replace button is the positive button on the dialog.
        android.app.AlertDialog d = (android.app.AlertDialog)
                ShadowDialog.getLatestDialog();
        assertNotNull("Apply tap must open a confirmation dialog", d);
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        // The dialog dispatches the listener via Handler.post on the
        // main looper. Drain the looper so the callback runs before
        // we read the prefs.
        ShadowLooper.idleMainLooper();

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

        // No persistent [Applied] prefix — the summary stays at the
        // declared string from XML.
        CharSequence summary = pref.getSummary();
        assertNotNull(summary);
        assertFalse("Summary must NOT carry [Applied]: " + summary,
                summary.toString().contains("[Applied]"));
    }

    @Test
    public void tapHome_cancelled_writesNothing() {
        // Seed a sentinel; a cancelled tap must leave it intact.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "auto")
                .commit();

        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");
        tap(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                ShadowDialog.getLatestDialog();
        assertNotNull(d);
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
    }

    @Test
    public void tapHq_confirmed_writes4k60_80Mbps() {
        ProfileApplyPreference pref = inflatePreference("vq_profile_hq_apply");
        assertNotNull("HQ preference must exist in XML", pref);
        tap(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                ShadowDialog.getLatestDialog();
        assertNotNull(d);
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("3840x2160",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(80000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("auto",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    @Test
    public void tapCustom_confirmed_writesNothing() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 9000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();

        ProfileApplyPreference pref = inflatePreference("vq_profile_custom_apply");
        tap(pref);
        ShadowLooper.idleMainLooper();

        // Custom does not show the confirm dialog — it goes straight to
        // applyProfile. Either way: no writes.
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
    public void tapHome_confirmed_clearsLegacyCombinedKey() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_LEGACY_RES_FPS, "1280x720x30")
                .commit();
        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");
        tap(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                ShadowDialog.getLatestDialog();
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx)
                .contains(ProfileApplier.K_LEGACY_RES_FPS));
    }

    @Test
    public void noPersistentAppliedPrefix_evenAfterRepeatedTaps() {
        ProfileApplyPreference pref = inflatePreference("vq_profile_home_apply");
        for (int i = 0; i < 3; i++) {
            tap(pref);
            android.app.AlertDialog d = (android.app.AlertDialog)
                    ShadowDialog.getLatestDialog();
            assertNotNull(d);
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
        }
        CharSequence summary = pref.getSummary();
        assertNotNull(summary);
        assertFalse("Summary must NEVER carry [Applied] in Preview 2: " + summary,
                summary.toString().contains("[Applied]"));
    }
}