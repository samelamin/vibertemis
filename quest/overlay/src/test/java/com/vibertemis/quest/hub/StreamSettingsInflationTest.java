package com.vibertemis.quest.hub;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.vibertemis.quest.pcvr.PcvrSettingsActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    private ActivityController<MainHubActivity> host;

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
        host = ac;
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

    /** Tap a row the way the framework does: a user tap is
     *  {@code PreferenceScreen} calling {@code performClick(PreferenceScreen)},
     *  which is the entry point that runs the default {@code onClick()}
     *  behaviour — the checkbox toggle, the list dialog — and not just an
     *  {@code OnPreferenceClickListener}. That overload is public but
     *  {@code @hide} on {@link Preference}, so it is not on the
     *  {@code android.preference} stub we compile against; call the real
     *  framework method reflectively. {@code Method.invoke} dispatches
     *  virtually, so {@code CheckBoxPreference}'s override still runs. */
    private void tap(Preference pref, PreferenceScreen screen) {
        assertNotNull("row under test must exist", pref);
        ReflectionHelpers.callInstanceMethod(Preference.class, pref, "performClick",
                ClassParameter.from(PreferenceScreen.class, screen));
    }

    /** Tap a row through the click shadow — which dispatches the production
     *  {@code OnPreferenceClickListener} — and return what it launched. */
    private Intent tapAndCapture(Preference pref) {
        assertNotNull("row under test must exist", pref);
        assertTrue("tap on " + pref.getKey() + " must be consumed",
                Shadows.shadowOf(pref).click());
        Intent started = Shadows.shadowOf(host.get()).getNextStartedActivity();
        assertNotNull("tap on " + pref.getKey() + " must launch an activity", started);
        return started;
    }

    private void assertTargetsPcvrActivity(Intent intent) {
        assertEquals("the row must name the PCVR activity explicitly",
                PcvrSettingsActivity.class.getName(), intent.getComponent().getClassName());
    }

    /** Codec and bitrate limits are the two rows the headset really owns:
     *  tappable, and opening the same screen with the focus token that
     *  scrolls to the control the user tapped. */
    @Test
    public void pcvrCodecAndBitrateRows_openPcvrActivityFocusedOnTheTappedControl() {
        setHeadset(true);
        PreferenceScreen screen = root(inflate());
        Preference codec = screen.findPreference("vq_pcvr_codec");
        Preference bitrate = screen.findPreference("vq_pcvr_adaptive_bitrate");
        assertTrue("codec row is headset-owned, so it must be tappable", codec.isSelectable());
        assertTrue(codec.isEnabled());
        assertTrue("bitrate row is headset-owned, so it must be tappable", bitrate.isSelectable());
        assertTrue(bitrate.isEnabled());
        Intent codecIntent = tapAndCapture(codec);
        assertTargetsPcvrActivity(codecIntent);
        assertEquals("codec row must focus the codec chooser", PcvrSettingsActivity.FOCUS_CODEC,
                codecIntent.getStringExtra(PcvrSettingsActivity.EXTRA_FOCUS));
        Intent bitrateIntent = tapAndCapture(bitrate);
        assertTargetsPcvrActivity(bitrateIntent);
        assertEquals("bitrate row must focus the bitrate limits", PcvrSettingsActivity.FOCUS_BITRATE,
                bitrateIntent.getStringExtra(PcvrSettingsActivity.EXTRA_FOCUS));
    }

    /** The pairing row is the screen's front door: same activity, no focus token. */
    @Test
    public void pcvrConnectionRow_opensPcvrActivityWithoutFocusToken() {
        setHeadset(true);
        Preference connection = root(inflate()).findPreference("vq_pcvr_connection");
        Intent intent = tapAndCapture(connection);
        assertTargetsPcvrActivity(intent);
        assertFalse("nothing to focus on yet, so no focus token may be sent",
                intent.hasExtra(PcvrSettingsActivity.EXTRA_FOCUS));
    }

    /** Foveation, refresh, resolution and eye tracking are decided on the PC,
     *  so those rows are informational: not tappable, and filed under the
     *  Advanced category instead of next to the rows we can change. */
    @Test
    public void pcvrAdvancedRows_areInformationalAndLiveUnderAdvanced() {
        setHeadset(true);
        PreferenceScreen screen = root(inflate());
        PreferenceCategory advanced = (PreferenceCategory) screen.findPreference("vq_pcvr_advanced");
        PreferenceCategory editable = (PreferenceCategory) screen.findPreference("vq_pcvr_host");
        for (String key : new String[]{"vq_pcvr_fixed_foveation", "vq_pcvr_refresh",
                "vq_pcvr_resolution", "vq_pcvr_eye_tracking"}) {
            Preference row = screen.findPreference(key);
            assertNotNull(key + " must exist on a headset", row);
            assertFalse(key + " is owned by the PC, so it must not be tappable", row.isSelectable());
            assertNotNull(key + " belongs to Advanced: PC settings", advanced.findPreference(key));
            assertNull(key + " must not sit with the editable rows", editable.findPreference(key));
        }
    }

    /** The nested VR depth row builds the real single-choice dialog (only
     *  off/model survive the release filter) and dismissing it writes nothing. */
    @Test
    public void nestedVrDepthRow_opensRealListDialog_andDismissKeepsValue() {
        setHeadset(true);
        PreferenceScreen screen = root(inflate());
        ListPreference depth = (ListPreference) screen.findPreference("list_vr_depth_source");
        assertEquals("only off/model survive the release filter", 2, depth.getEntryValues().length);
        assertEquals("off", depth.getValue());
        tap(depth, screen);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("tapping VR depth must open the real list dialog", dialog);
        assertTrue(dialog.isShowing());
        dialog.dismiss();
        assertEquals("dismissing must not change the stored depth source", "off", depth.getValue());
    }

    /** The renderer row is an ordinary checkbox: a tap toggles and persists it. */
    @Test
    public void rendererRow_tapTogglesAndPersistsState() {
        setHeadset(true);
        PreferenceScreen screen = root(inflate());
        CheckBoxPreference renderer = (CheckBoxPreference)
                screen.findPreference("checkbox_enable_gl_render_path");
        assertFalse("upstream default is off", renderer.isChecked());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        tap(renderer, screen);
        assertTrue("a tap must toggle the row on", renderer.isChecked());
        assertTrue("the toggle must reach the upstream key",
                prefs.getBoolean("checkbox_enable_gl_render_path", false));
        tap(renderer, screen);
        assertFalse("a second tap must toggle it back off", renderer.isChecked());
        assertFalse(prefs.getBoolean("checkbox_enable_gl_render_path", true));
    }

    /** analog_scrolling is gated on checkbox_mouse_emulation, so the parent
     *  has to be back on before the child opens its own list dialog. */
    @Test
    public void mouseEmulationParent_unlocksDependentListRow() {
        setHeadset(true);
        PreferenceScreen screen = root(inflate());
        CheckBoxPreference parent =
                (CheckBoxPreference) screen.findPreference("checkbox_mouse_emulation");
        ListPreference child = (ListPreference) screen.findPreference("analog_scrolling");
        assertTrue("upstream default has mouse emulation on", parent.isChecked());
        assertTrue("the child row must be enabled while the parent is on", child.isEnabled());
        tap(parent, screen);
        assertFalse(parent.isChecked());
        assertFalse("turning the parent off must disable the dependent row", child.isEnabled());
        tap(parent, screen);
        assertTrue(parent.isChecked());
        assertTrue("the parent must be back on before the child is used", child.isEnabled());
        tap(child, screen);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the unlocked dependent row opens its own dialog", dialog);
        assertTrue(dialog.isShowing());
        dialog.dismiss();
        assertEquals("dismissing must not pick a new scrolling mode", "right", child.getValue());
    }
}
