package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;

import com.limelight.preferences.PreferenceConfiguration;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Real XML inflation via the upstream {@link StreamSettings.SettingsFragment}.
 *
 * <p>Drives {@code addPreferencesFromResource(R.xml.preferences)} and
 * the same per-setting {@code findPreference} calls the production
 * {@code onCreate} runs. The only overlay addition is the post-init
 * headset gate that removes the VR wrapper and the PCVR host screen on
 * a phone.
 *
 * <p>These tests prove:
 * <ul>
 *   <li>headset: the VR wrapper and the PCVR host screen are present
 *       in the inflated tree,</li>
 *   <li>phone: the VR wrapper and the PCVR host screen are removed,
 *       and the upstream per-setting {@code findPreference} calls
 *       still resolved before the gate ran,</li>
 *   <li>inflating the screen on a phone does not write a Home / Travel
 *       preset into the upstream preference keys (Custom / unknown
 *       share the same no-op contract; only an explicit Apply writes).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class StreamSettingsInflationTest {

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

    private StreamSettings.SettingsFragment inflate() {
        // SettingsFragment needs a host Activity. We build MainHubActivity
        // for the activity host; the fragment never references it for any
        // call beyond getActivity().getPackageManager() and getSystemService.
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class).create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        // Attach the fragment to the host activity manually.
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        return frag;
    }

    private PreferenceScreen root(StreamSettings.SettingsFragment frag) {
        PreferenceScreen screen = frag.getPreferenceScreen();
        assertNotNull("PreferenceScreen must inflate", screen);
        return screen;
    }

    @Test
    public void headset_inflatedTree_keepsVrWrapper_andPcvrHost() {
        setHeadset(true);
        StreamSettings.SettingsFragment frag = inflate();
        PreferenceScreen screen = root(frag);

        assertNotNull("Headset must keep vq_screen_vr_settings_headset_only",
                screen.findPreference("vq_screen_vr_settings_headset_only"));
        assertNotNull("Headset must keep vq_screen_pcvr_host",
                screen.findPreference("vq_screen_pcvr_host"));
        // The upstream VR settings category is reachable via the wrapper.
        assertNotNull(screen.findPreference("category_vr_settings"));
        assertNotNull(screen.findPreference("category_vr_debug"));
    }

    @Test
    public void phone_inflatedTree_hidesVrWrapper_andPcvrHost() {
        setHeadset(false);
        StreamSettings.SettingsFragment frag = inflate();
        PreferenceScreen screen = root(frag);

        assertNull("Phone must hide vq_screen_vr_settings_headset_only",
                screen.findPreference("vq_screen_vr_settings_headset_only"));
        assertNull("Phone must hide vq_screen_pcvr_host",
                screen.findPreference("vq_screen_pcvr_host"));
        // Upstream non-VR preferences remain visible. Use real child
        // keys (category_audio_settings has no android:key in upstream,
        // it is referenced by its audio child key).
        assertNotNull(screen.findPreference("list_audio_config"));
        assertNotNull(screen.findPreference("category_basic_settings"));
        // Presets screen stays on phones — presets are for screen
        // streaming, not for VR.
        assertNotNull("Presets screen must remain on phones",
                screen.findPreference("vq_screen_presets"));
    }

    /**
     * Inflating the preferences screen must not apply any preset. We
     * seed the upstream keys with a sentinel, inflate, and assert the
     * sentinel is unchanged. Upstream code may legitimately perform its
     * own initial-default / migration writes on inflation; the hub's
     * {@link ProfileApplier} must NOT be one of them.
     */
    @Test
    public void openingInflatedUI_doesNotApplyPreset() {
        setHeadset(true);
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();

        StreamSettings.SettingsFragment frag = inflate();
        // The consumer reads through the real PreferenceConfiguration
        // code path; an Apply of Home would put 2560x1440 / 72 / 40000
        // / auto. We assert none of those landed.
        PreferenceConfiguration s = PreferenceConfiguration.readPreferences(ctx);
        assertEquals(1920, s.width);
        assertEquals(1080, s.height);
        assertEquals(60, s.fps);
        assertEquals(17000, s.bitrate);
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, s.videoFormat);
    }

    /**
     * Inflating the screen must not throw on either device kind — that
     * itself proves the upstream per-setting findPreference calls ran
     * before the gate (they did not crash on a missing wrapper child).
     * Then assert the nested-VR keys are reachable exactly where the
     * production layout puts them: nested inside the headset-only
     * wrapper on a headset, gone on a phone.
     */
    @Test
    public void perSettingFinds_nestedVrKeyMatchesHeadset() {
        // Phone: wrapper is removed, nested VR keys are NOT reachable.
        setHeadset(false);
        StreamSettings.SettingsFragment phoneFrag = inflate();
        PreferenceScreen phoneScreen = root(phoneFrag);
        assertNull("Phone: nested VR key must be gone with its wrapper",
                phoneScreen.findPreference("checkbox_enable_vr_mode"));
        assertNull(phoneScreen.findPreference("list_vr_depth_source"));

        // Headset: wrapper stays, nested VR keys are reachable.
        setHeadset(true);
        StreamSettings.SettingsFragment headsetFrag = inflate();
        PreferenceScreen headsetScreen = root(headsetFrag);
        assertNotNull("Headset: nested VR key must be reachable",
                headsetScreen.findPreference("checkbox_enable_vr_mode"));
        assertNotNull(headsetScreen.findPreference("list_vr_depth_source"));
    }
}
