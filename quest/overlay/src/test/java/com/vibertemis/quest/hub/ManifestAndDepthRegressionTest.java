package com.vibertemis.quest.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.preference.ListPreference;
import android.preference.PreferenceManager;

import com.limelight.PcView;
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
import org.robolectric.shadows.ShadowPackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Manifest + depth-source regression tests driven through the
 * PackageManager (the same query that resolves the launcher icon
 * the user actually taps) and the real inflated
 * {@code SettingsFragment} (the same UI the user sees).
 *
 * <p>Each launcher category we put on MainHubActivity must resolve
 * back to {@code com.vibertemis.quest.hub.MainHubActivity}; the same
 * categories must NOT resolve to {@code .PcView}. The depth-source
 * list the user actually sees in the inflated UI must contain
 * exactly {@code zipdepth}, {@code model} and {@code off}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class ManifestAndDepthRegressionTest {

    private static final String HUB_NAME = "com.vibertemis.quest.hub.MainHubActivity";
    private static final String PCVIEW_NAME = "com.limelight.PcView";
    private static final String PKG =
            RuntimeEnvironment.getApplication().getPackageName();

    private static final List<String> LAUNCHER_CATEGORIES = Arrays.asList(
            "android.intent.category.LAUNCHER",
            "android.intent.category.MULTIWINDOW_LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
            "tv.ouya.intent.category.APP");

    private Context ctx;
    private PackageManager pm;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        pm = ctx.getPackageManager();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    /**
     * Build a MAIN + category intent and ask PackageManager which
     * activities resolve. Returns the resolved component class names
     * (excluding MainHubActivity itself if asked).
     */
    private Set<String> resolveMainWith(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        intent.setPackage(PKG);
        List<android.content.pm.ResolveInfo> resolved =
                pm.queryIntentActivities(intent, 0);
        Set<String> classes = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : resolved) {
            if (ri.activityInfo != null
                    && PKG.equals(ri.activityInfo.packageName)) {
                classes.add(ri.activityInfo.name);
            }
        }
        return classes;
    }

    /**
     * Each launcher category we attached to MainHubActivity must
     * resolve to MainHubActivity (the user's actual launcher entry).
     * PcView must NOT resolve to any of those categories because the
     * hub takes over the launcher exposure and PcView is only reached
     * via explicit ComponentName from MainHubActivity.
     */
    @Test
    public void launcherCategories_resolveToHub_notPcView() {
        for (String category : LAUNCHER_CATEGORIES) {
            Set<String> resolved = resolveMainWith(category);
            assertTrue("MAIN + " + category + " must resolve MainHubActivity; got "
                    + resolved, resolved.contains(HUB_NAME));
            assertTrue("MAIN + " + category + " must NOT resolve PcView; got "
                    + resolved, !resolved.contains(PCVIEW_NAME));
        }
    }

    /**
     * Total cross-check: every launcher-class activity exposed by
     * the merged manifest is exactly MainHubActivity (plus
     * ShortcutTrampoline, which is android:exported for the launcher
     * shortcut intent). PcView is reached via explicit ComponentName
     * only and must not appear in any launcher-class query.
     */
    @Test
    public void launcher_resolvesOnlyHubAndTrampoline() {
        Set<String> resolved = resolveMainWith(Intent.CATEGORY_LAUNCHER);
        // Strip the trampoline alias, which is part of upstream's
        // shortcut pathway and not part of the visible launcher
        // surface.
        resolved.remove(".ShortcutTrampoline");
        resolved.remove("com.limelight.ShortcutTrampoline");
        assertEquals("LAUNCHER must resolve exactly MainHubActivity; got "
                + resolved, 1, resolved.size());
        assertTrue(resolved.contains(HUB_NAME));
    }

    /**
     * Drive the real inflated SettingsFragment the same way the
     * production activity does. After
     * {@code commitNow}, the {@code list_vr_depth_source} entry values
     * must be exactly {@code ["zipdepth", "model", "off"]} — the synthetic test
     * patterns are filtered out at SettingsFragment.onCreate so they
     * never reach the user-visible list, in BOTH debug and release
     * builds.
     */
    @Test
    public void inflatedDepthSourceList_isExactlyTheRealChoices() {
        Shadows.shadowOf(pm).setSystemFeature(
                PackageManager.FEATURE_VR_HEADTRACKING, true);
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        ListPreference depthPref = (ListPreference)
                frag.findPreference("list_vr_depth_source");
        assertNotNull("list_vr_depth_source must be inflated", depthPref);
        CharSequence[] values = depthPref.getEntryValues();
        assertNotNull(values);
        // Exactly three entries, in the production-declared order.
        assertEquals("Inflated depth list must contain exactly 3 entries; got "
                + Arrays.toString(values), 3, values.length);
        assertEquals("zipdepth", values[0].toString());
        assertEquals("model", values[1].toString());
        assertEquals("off", values[2].toString());
        // Round-trip the entries too: names map 1:1 to values.
        CharSequence[] entries = depthPref.getEntries();
        assertNotNull(entries);
        assertEquals(3, entries.length);
        assertEquals("zipdepth", depthPref.getValue());
    }

    /**
     * Even when the upstream {@code vr_depth_source_values} array
     * still carries the synthetic test patterns (flat, ramp, blob,
     * eyetest, shifttest), the filter at SettingsFragment.onCreate
     * must strip them down to the real choices. The
     * inflated list is the user-visible surface; the upstream array
     * is internal. We assert the FILTER (the inflated list) rather
     * than the array — the array is allowed to grow in upstream
     * without breaking the UI as long as the filter keeps up.
     */
    @Test
    public void inflatedDepthList_isIndependentOfUpstreamArray() {
        Shadows.shadowOf(pm).setSystemFeature(
                PackageManager.FEATURE_VR_HEADTRACKING, true);
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        ListPreference depthPref = (ListPreference)
                frag.findPreference("list_vr_depth_source");
        assertNotNull("list_vr_depth_source must be inflated", depthPref);
        CharSequence[] values = depthPref.getEntryValues();
        assertNotNull(values);
        Set<String> inflated = new HashSet<>();
        for (CharSequence v : values) inflated.add(v.toString());
        assertEquals("Inflated depth list must be exactly {zipdepth, model, off}; got "
                + inflated, new HashSet<>(Arrays.asList("zipdepth", "model", "off")),
                inflated);
    }

    /**
     * Convenience check that headset gating still works for the
     * non-headset case: on a phone, the headset-only screens must be
     * absent from the inflated tree. Pairs with
     * {@code StreamSettingsInflationTest.phone_inflatedTree_hidesVrWrapper_andPcvrHost}
     * which is the comprehensive check.
     */
    @Test
    public void phone_hidesHeadsetOnlyScreens() {
        ShadowPackageManager spm = Shadows.shadowOf(pm);
        spm.setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, false);
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        assertEquals(null, frag.findPreference("vq_screen_vr_settings_headset_only"));
        assertEquals(null, frag.findPreference("vq_screen_pcvr_host"));
    }

    /**
     * PcView must still be reachable via explicit ComponentName —
     * the hub launches it that way. We assert via
     * {@code PackageManager.getActivityInfo} on the fully-qualified
     * component name {@code com.limelight.PcView}; the lookup must
     * resolve and report the activity as exported so the hub's
     * explicit ComponentName launch succeeds.
     */
    @Test
    public void pcView_isReachableViaExplicitComponent() {
        ComponentName pc = new ComponentName(ctx, PcView.class);
        android.content.pm.ActivityInfo info = null;
        try {
            info = pm.getActivityInfo(pc, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new AssertionError(
                    "PcView activity element must remain in the manifest "
                    + "(reachable via explicit ComponentName from MainHubActivity)",
                    e);
        }
        assertNotNull("PcView ActivityInfo must resolve", info);
        assertEquals(PCVIEW_NAME, info.name);
        assertTrue("PcView must remain exported=true", info.exported);
    }
}
