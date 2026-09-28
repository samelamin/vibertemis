package com.vibertemis.quest.hub;

import android.content.Context;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;

import com.limelight.preferences.SeekBarPreference;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration tests for the upstream
 * {@link StreamSettings.SettingsFragment} bound to a real
 * {@link SettingsController} observer. These tests prove the visible
 * UI (top status preference + cached ListPreference /
 * SeekBarPreference widgets) tracks live upstream edits inside the
 * same screen instance — no reopen, no manual refresh.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Apply Travel preset → TopStatus summary shows
 *       1920x1080 @ 60 fps · 17.0 Mbps · codec Auto and the cached
 *       res / fps / codec ListPreferences show the new values.</li>
 *   <li>Apply HQ preset → TopStatus summary shows 4K / 60 / 80 Mbps
 *       and the cached widgets match.</li>
 *   <li>Unsupported preset (4K not in inflated list) → no writes,
 *       TopStatus stays at the prior values, no exception.</li>
 *   <li>Manual edit of the bitrate to 17500 Kbps → TopStatus summary
 *       reads {@code 17.5 Mbps} (decimal resolution, not integer
 *       division).</li>
 *   <li>Pause / resume the fragment → observer reattaches, summary
 *       refreshes.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class TopStatusIntegrationTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    private static void widenInflatedEntries(ListPreference resPref,
                                             ListPreference fpsPref) {
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

    private static StreamSettings.SettingsFragment inflateFragment() {
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        // Robolectric's default display only reports 60 Hz, so widen
        // the inflated entry lists so per-instance guards accept
        // 1440p72 / 4K60.
        widenInflatedEntries(
                (ListPreference) frag.findPreference("list_resolution"),
                (ListPreference) frag.findPreference("list_fps"));
        return frag;
    }

    private static void tapApply(Preference pref) {
        try {
            Method m = Preference.class.getDeclaredMethod("onClick");
            m.setAccessible(true);
            m.invoke(pref);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("onClick failed", e);
        }
    }

    private static String summaryOf(StreamSettings.SettingsFragment frag,
                                    String key) {
        PreferenceScreen screen = frag.getPreferenceScreen();
        assertNotNull("PreferenceScreen must inflate", screen);
        Preference p = screen.findPreference(key);
        assertNotNull("Missing " + key, p);
        CharSequence s = p.getSummary();
        return s == null ? "" : s.toString();
    }

    private static String valueOf(StreamSettings.SettingsFragment frag,
                                  String key) {
        PreferenceScreen screen = frag.getPreferenceScreen();
        assertNotNull(screen);
        Preference p = screen.findPreference(key);
        if (p instanceof ListPreference) {
            return ((ListPreference) p).getValue();
        }
        if (p instanceof SeekBarPreference) {
            return String.valueOf(((SeekBarPreference) p).getProgress());
        }
        return "";
    }

    /**
     * Apply the Travel preset inside the inflated screen and assert
     * the TopStatus summary reflects the new values without
     * dismissing the fragment.
     */
    @Test
    public void applyTravelPreset_topStatusAndCachedWidgetsUpdate() {
        StreamSettings.SettingsFragment frag = inflateFragment();
        Preference pref = frag.findPreference("vq_profile_travel_apply");
        assertNotNull("Travel apply preference must be in XML", pref);
        tapApply(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("Apply must show confirmation dialog", d);
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        String top = summaryOf(frag, "vq_top_status");
        assertTrue("TopStatus must show 1080p: " + top, top.contains("1920x1080"));
        assertTrue("TopStatus must show 60 fps: " + top, top.contains("60"));
        assertTrue("TopStatus must show 17 Mbps: " + top, top.contains("17.0 Mbps"));
        assertTrue("TopStatus must show Auto codec: " + top, top.contains("Auto"));

        assertEquals("1920x1080", valueOf(frag, "list_resolution"));
        assertEquals("60", valueOf(frag, "list_fps"));
        assertEquals(17000, Integer.parseInt(valueOf(frag, "seekbar_bitrate_kbps")));
    }

    /**
     * Apply the HQ preset inside the inflated screen and assert the
     * TopStatus summary + cached widgets track the new 4K / 60 /
     * 80 Mbps values.
     */
    @Test
    public void applyHqPreset_topStatusAndCachedWidgetsUpdate() {
        StreamSettings.SettingsFragment frag = inflateFragment();
        Preference pref = frag.findPreference("vq_profile_hq_apply");
        assertNotNull("HQ apply preference must be in XML", pref);
        tapApply(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        String top = summaryOf(frag, "vq_top_status");
        assertTrue("TopStatus must show 4K: " + top, top.contains("3840x2160"));
        assertTrue("TopStatus must show 80 Mbps: " + top, top.contains("80.0 Mbps"));

        assertEquals("3840x2160", valueOf(frag, "list_resolution"));
        assertEquals(80000, Integer.parseInt(valueOf(frag, "seekbar_bitrate_kbps")));
    }

    /**
     * A preset whose target value is not in the inflated list (e.g.
     * 4K on a phone that filtered it out) must NOT update either
     * the TopStatus or the cached widgets. We simulate this by
     * emptying the inflated res list back to a phone-only set.
     */
    @Test
    public void applyRejectedPreset_topStatusStaysUnchanged() {
        StreamSettings.SettingsFragment frag = inflateFragment();
        // Drop 4K and 1440p from the inflated res list to simulate
        // a phone whose post-filter dropped them.
        ListPreference resPref =
                (ListPreference) frag.findPreference("list_resolution");
        resPref.setEntries(new CharSequence[]{"720p", "1080p"});
        resPref.setEntryValues(new CharSequence[]{"1280x720", "1920x1080"});

        // Seed a known upstream state so we can prove it survives.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_resolution", "1920x1080")
                .putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 17000)
                .putString("video_format", "auto")
                .commit();
        // Recompute the summary so the test starts from a known
        // baseline. The fragment's onResume would do this; we
        // invoke refresh directly through the public path.
        TopStatusPreference topPref =
                (TopStatusPreference) frag.findPreference("vq_top_status");
        assertNotNull(topPref);
        topPref.refreshSummary();
        String baseline = topPref.getSummary().toString();

        // Tap HQ — 4K is not in the list, so the guard must reject.
        Preference pref = frag.findPreference("vq_profile_hq_apply");
        tapApply(pref);
        android.app.AlertDialog d = (android.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        // The unsupported dialog is shown; close it.
        android.app.AlertDialog unsupported =
                (android.app.AlertDialog)
                        org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(unsupported);
        unsupported.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();

        // The seeded upstream values must still be 1080p60/17Mbps.
        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString("list_resolution", ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt("seekbar_bitrate_kbps", 0));

        // TopStatus summary is unchanged from the baseline.
        topPref.refreshSummary();
        assertEquals("TopStatus must not change on a rejected preset",
                baseline, topPref.getSummary().toString());
    }

    /**
     * Manual edit of the upstream bitrate to 17500 Kbps must be
     * reflected in the TopStatus summary as {@code 17.5 Mbps} —
     * decimal Mbps, not integer division 17.
     */
    @Test
    public void manualEditBitrate_topStatusShowsDecimalMbps() {
        StreamSettings.SettingsFragment frag = inflateFragment();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_resolution", "1920x1080")
                .putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 17500)
                .putString("video_format", "auto")
                .commit();
        // The SettingsController listener fires on commit and refreshes
        // TopStatus via the observer wired in onCreate.
        ShadowLooper.idleMainLooper();
        String top = summaryOf(frag, "vq_top_status");
        assertTrue("TopStatus must show 17.5 Mbps (decimal), got: " + top,
                top.contains("17.5 Mbps"));
    }

    /**
     * Pause + resume the activity; the observer reattaches and the
     * summary refreshes from the live prefs.
     */
    @Test
    public void pauseThenResume_topStatusRefreshes() {
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        widenInflatedEntries(
                (ListPreference) frag.findPreference("list_resolution"),
                (ListPreference) frag.findPreference("list_fps"));

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("list_resolution", "3840x2160")
                .putString("list_fps", "60")
                .putInt("seekbar_bitrate_kbps", 80000)
                .putString("video_format", "auto")
                .commit();
        // Force one refresh so the listener captures the snapshot.
        TopStatusPreference topPref =
                (TopStatusPreference) frag.findPreference("vq_top_status");
        topPref.refreshSummary();

        ac.pause().stop();
        // Mutate the prefs while paused — the listener is detached.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putInt("seekbar_bitrate_kbps", 17500)
                .commit();
        ac.start().resume();
        ShadowLooper.idleMainLooper();
        // After resume the SettingsController listener reattaches,
        // but the prefs change happened while paused. The activity's
        // onResume triggers settingsController.refresh() which reads
        // the new value.
        String top = summaryOf(frag, "vq_top_status");
        assertTrue("After resume TopStatus must show 17.5 Mbps, got: " + top,
                top.contains("17.5 Mbps"));
    }
}