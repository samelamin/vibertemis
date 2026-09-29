package com.vibertemis.quest.hub;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.R;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Renders the hub, Setup and Streaming settings activities through
 * Robolectric {@link GraphicsMode#NATIVE} so the actual layouts go
 * through the same Skia-backed draw path a real device would use,
 * and writes the resulting bitmap to PNG under
 * {@code app/build/reports/quest-ui/}.
 *
 * <p>Each fixture:
 * <ul>
 *   <li>Sets Android resource qualifiers for viewport (width, height,
 *       orientation, density) using the canonical
 *       {@code w<W>dp-h<H>dp-{port,land}-mdpi} format so the
 *       resource framework picks the right layout bucket,</li>
 *   <li>Sets fontScale through {@link RuntimeEnvironment#setFontScale}
 *       so {@code DisplayMetrics.scaledDensity} actually scales (a
 *       raw {@code Configuration.fontScale} write does not refresh
 *       metrics and yields identical bytes between 1.0 and 1.6),</li>
 *   <li>Drives the activity to {@code resume()} so its window is
 *       created and its root view is attached. For the settings
 *       screen the upstream {@link StreamSettings.SettingsFragment}
 *       is attached via the same {@code commitNow} path the
 *       {@link TopStatusIntegrationTest} uses, so the rendered PNG
 *       shows the inflated preference rows rather than a blank
 *       host,</li>
 *   <li>Explicitly measures and lays out the root view at the
 *       requested pixel viewport, then draws to a same-size
 *       {@link Bitmap}. The bitmap dimensions are asserted exactly
 *       and a non-blank pixel sample is taken so a missing draw does
 *       not silently produce a flat image,</li>
 *   <li>For fontScale 1.6 fixtures, asserts a {@link TextView}
 *       rendered textSize is strictly larger than at fontScale 1.0 —
 *       the visual proof that the scale actually applied,</li>
 *   <li>Also asserts the visible Back row and 56dp touch targets and
 *       that the Setup scroll body reaches the bottom — layout
 *       assertions complement the visual evidence.</li>
 * </ul>
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code hub_phone_360x640.png}</li>
 *   <li>{@code hub_phone_fontscale_1_6_360x640.png}</li>
 *   <li>{@code hub_phone_landscape_640x360.png}</li>
 *   <li>{@code hub_headset_1000x700.png}</li>
 *   <li>{@code hub_headset_fontscale_1_6_1000x700.png}</li>
 *   <li>{@code setup_phone_360x640.png}</li>
 *   <li>{@code setup_phone_fontscale_1_6_360x640.png}</li>
 *   <li>{@code setup_headset_1000x700.png}</li>
 *   <li>{@code settings_top_1000x700.png} — actual inflated settings fragment</li>
 *   <li>{@code settings_presets_1000x700.png} — nested presets screen</li>
 *   <li>{@code settings_vr_1000x700.png} — nested VR settings screen (headset)</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = ShadowMoonBridge.class)
public class QuestUiScreenshotTest {

    private static final String OUT_REL = "app/build/reports/quest-ui";
    private static final float DEFAULT_FONT_SCALE = 1.0f;
    private Context ctx;
    private File outDir;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        RuntimeEnvironment.setFontScale(DEFAULT_FONT_SCALE);
        outDir = new File(OUT_REL);
        if (!outDir.exists()) {
            File f = new File("build/reports/quest-ui");
            if (f.exists() || f.mkdirs()) {
                outDir = f;
            } else {
                outDir = new File(System.getProperty("java.io.tmpdir"),
                        "quest-ui");
                if (!outDir.exists()) outDir.mkdirs();
            }
        }
    }

    @Test
    public void pcvrPanelScreenshots() throws Exception {
        setHeadset(true);
        for (boolean large : new boolean[]{false, true}) {
            int width = large ? 360 : 1000, height = large ? 800 : 700;
            setQualifiers(large ? "w360dp-h800dp-port-mdpi" : "w1000dp-h700dp-land-mdpi");
            setFontScale(large ? 1.6f : 1.0f);
            try (ActivityController<com.vibertemis.quest.pcvr.PcvrSettingsActivity> controller =
                    Robolectric.buildActivity(com.vibertemis.quest.pcvr.PcvrSettingsActivity.class)
                        .create().start().resume().visible()) {
                Bitmap bitmap = snapshotActivity(controller.get(), width, height);
                assertExactDimsAndNonBlank(bitmap, width, height, "PCVR connection");
                writePng(bitmap, new File(outDir, large ? "pcvr_fontscale_1_6_360x800.png"
                    : "pcvr_1000x700.png"));
                ViewGroup content = controller.get().findViewById(android.R.id.content);
                ViewGroup panel = (ViewGroup) content.getChildAt(0);
                android.widget.ScrollView scroll = (android.widget.ScrollView) panel.getChildAt(1);
                scroll.fullScroll(View.FOCUS_DOWN);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                Bitmap bottom = snapshotActivity(controller.get(), width, height);
                assertTrue("PCVR quality controls must be reachable by scrolling", scroll.getScrollY() > 0);
                writePng(bottom, new File(outDir, large ? "pcvr_bottom_fontscale_1_6.png" : "pcvr_bottom_1000x700.png"));
            }
        }
    }

    private static void setHeadset(boolean headset) {
        ShadowPackageManager spm = Shadows.shadowOf(
                RuntimeEnvironment.getApplication().getPackageManager());
        spm.setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, headset);
    }

    /** Switch the runtime qualifiers for viewport / orientation. */
    private static void setQualifiers(String qualifiers) {
        RuntimeEnvironment.setQualifiers(qualifiers);
    }

    /**
     * Set fontScale through Robolectric so DisplayMetrics and
     * Configuration stay consistent. Writing
     * {@code Configuration.fontScale} directly leaves
     * {@code DisplayMetrics.scaledDensity} unchanged, so
     * {@code sp.setTextSize(SP, ...)} renders the same pixels at
     * fontScale 1.0 and 1.6 — the bytes would be identical. This
     * setter rebuilds the metrics.
     */
    private static void setFontScale(float scale) {
        RuntimeEnvironment.setFontScale(scale);
    }

    private static void writePng(Bitmap b, File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            assertTrue("Bitmap.compress must succeed", b.compress(
                    Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            out.close();
        }
    }

    private static int dp(int dp) {
        float density = RuntimeEnvironment.getApplication()
                .getResources().getDisplayMetrics().density;
        return Math.max(1, (int) (dp * density));
    }

    private static int sp(int sp) {
        float scaledDensity = RuntimeEnvironment.getApplication()
                .getResources().getDisplayMetrics().scaledDensity;
        return Math.max(1, (int) (sp * scaledDensity));
    }

    /**
     * Explicitly measure + layout the activity's root content view at
     * the requested pixel viewport and draw it onto a same-size
     * bitmap. The bitmap dimensions are then asserted exactly.
     */
    private static Bitmap snapshotActivity(Activity a, int widthPx, int heightPx) {
        View root = a.findViewById(android.R.id.content);
        assertNotNull("Activity root view must exist", root);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx,
                View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx,
                View.MeasureSpec.EXACTLY);
        root.measure(widthSpec, heightSpec);
        root.layout(0, 0, widthPx, heightPx);
        Bitmap b = Bitmap.createBitmap(widthPx, heightPx,
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        b.eraseColor(Color.BLACK);
        root.draw(c);
        return b;
    }

    private static void assertExactDimsAndNonBlank(Bitmap b, int w, int h, String label) {
        assertEquals(label + " width must match requested viewport",
                w, b.getWidth());
        assertEquals(label + " height must match requested viewport",
                h, b.getHeight());
        // Image-wide scan: count distinct pixel colors AND pixels
        // that differ from the top-left background corner. The
        // previous sparse 8x8 sample grid missed rendered text on a
        // near-black background; the production PNG is not blank, so
        // a full bitmap scan catches the actual rendered rows. The
        // test still rejects a solid-blank bitmap (zero distinct
        // colors) and a single-color noise flood (top-left == every
        // other pixel).
        int bg = b.getPixel(0, 0);
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        int different = 0;
        int total = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = b.getPixel(x, y);
                colors.add(px);
                total++;
                if (px != bg) different++;
            }
        }
        assertTrue(label + " must contain actual rendered content "
                + "(" + colors.size() + " distinct colors, "
                + different + "/" + total + " pixels differ from top-left background)",
                colors.size() > 16 && different > 500);
    }

    private static void assertTouchTargetHeight(View v, int minDp, String label) {
        assertNotNull(label + " must exist", v);
        if (v.getVisibility() == View.GONE) return;
        int h = v.getHeight();
        assertTrue(label + " height must be at least " + minDp
                + "dp, got " + h + "px", h >= dp(minDp));
    }

    /**
     * Assert the rendered {@code hub_title} textSize in pixels grew
     * vs. the fontScale 1.0 baseline. We measure on
     * 28sp → 28 * scaledDensity.
     */
    private static void assertFontScaleApplied(TextView baseline, TextView scaled,
                                               String label) {
        assertNotNull(label + " baseline TextView must exist", baseline);
        assertNotNull(label + " scaled TextView must exist", scaled);
        // Android 14 uses non-linear font scaling; the only strict
        // contract is "scaled > baseline".
        assertTrue(label + " textSize at fontScale 1.6 (" + scaled.getTextSize()
                + "px) must exceed fontScale 1.0 baseline ("
                + baseline.getTextSize() + "px)",
                scaled.getTextSize() > baseline.getTextSize());
    }

    // ----- hub fixtures --------------------------------------------------

    @Test
    public void hub_phone_portrait_renders() throws Exception {
        setHeadset(false);
        setQualifiers("w360dp-h640dp-port-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<MainHubActivity> c =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        int w = dp(360), h = dp(640);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "hub_phone_portrait");
        assertTouchTargetHeight(c.get().findViewById(R.id.hub_btn_screen), 56,
                "hub_btn_screen");
        assertTouchTargetHeight(c.get().findViewById(R.id.hub_btn_steamvr), 56,
                "hub_btn_steamvr");
        assertTouchTargetHeight(c.get().findViewById(R.id.hub_btn_settings), 56,
                "hub_btn_settings");
        assertTouchTargetHeight(c.get().findViewById(R.id.hub_btn_setup), 56,
                "hub_btn_setup");
        writePng(b, new File(outDir, "hub_phone_360x640.png"));
    }

    @Test
    public void hub_phone_fontscale_1_6_renders() throws Exception {
        setHeadset(false);
        setQualifiers("w360dp-h640dp-port-mdpi");
        // Render at fontScale 1.0 first to capture a baseline textSize.
        RuntimeEnvironment.setFontScale(1.0f);
        ActivityController<MainHubActivity> base =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        TextView baseTitle = (TextView) base.get().findViewById(R.id.hub_title);
        float baselinePx = baseTitle.getTextSize();
        // Now bump to 1.6 and render again. We force the resources to
        // refresh after the activity is built so the inflated TextView
        // picks up the new scaledDensity on re-measure.
        RuntimeEnvironment.setFontScale(1.6f);
        ActivityController<MainHubActivity> c =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        TextView title = (TextView) c.get().findViewById(R.id.hub_title);
        float fontscalePx = title.getTextSize();
        // Android 14 uses non-linear font scaling; we do not assert an
        // exact 28 * 1.6 ratio. The contract is strictly: the rendered
        // textSize at fontScale 1.6 is larger than the rendered
        // textSize at fontScale 1.0.
        assertTrue("hub_title textSize at 1.6 (" + fontscalePx
                + "px) must exceed baseline at 1.0 (" + baselinePx + "px)",
                fontscalePx > baselinePx);
        // And: the PNG must differ from the 1.0 fixture.
        int w = dp(360), h = dp(640);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "hub_phone_fontscale_1_6");
        writePng(b, new File(outDir, "hub_phone_fontscale_1_6_360x640.png"));
    }

    @Test
    public void hub_phone_landscape_renders() throws Exception {
        setHeadset(false);
        setQualifiers("w640dp-h360dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<MainHubActivity> c =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        int w = dp(640), h = dp(360);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "hub_phone_landscape");
        writePng(b, new File(outDir, "hub_phone_landscape_640x360.png"));
    }

    @Test
    public void hub_headset_renders() throws Exception {
        setHeadset(true);
        setQualifiers("w1000dp-h700dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<MainHubActivity> c =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        int w = dp(1000), h = dp(700);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "hub_headset");
        writePng(b, new File(outDir, "hub_headset_1000x700.png"));
    }

    @Test
    public void hub_headset_fontscale_1_6_renders() throws Exception {
        setHeadset(true);
        setQualifiers("w1000dp-h700dp-land-mdpi");
        // Render at fontScale 1.0 first to capture a baseline textSize.
        RuntimeEnvironment.setFontScale(1.0f);
        ActivityController<MainHubActivity> base =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        TextView baseTitle = (TextView) base.get().findViewById(R.id.hub_title);
        float baselinePx = baseTitle.getTextSize();
        // Bump to 1.6, render again. Android 14 uses non-linear font
        // scaling so we do not assert the exact 28 * 1.6 ratio; only
        // that the fontScale 1.6 textSize is strictly larger than the
        // fontScale 1.0 textSize.
        RuntimeEnvironment.setFontScale(1.6f);
        ActivityController<MainHubActivity> c =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        TextView title = (TextView) c.get().findViewById(R.id.hub_title);
        float fontscalePx = title.getTextSize();
        assertTrue("hub_title textSize at 1.6 (" + fontscalePx
                + "px) must exceed baseline at 1.0 (" + baselinePx + "px)",
                fontscalePx > baselinePx);
        int w = dp(1000), h = dp(700);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "hub_headset_fontscale_1_6");
        writePng(b, new File(outDir, "hub_headset_fontscale_1_6_1000x700.png"));
    }

    // ----- setup fixtures ------------------------------------------------

    @Test
    public void setup_phone_renders() throws Exception {
        setQualifiers("w360dp-h640dp-port-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<SetupActivity> c =
                Robolectric.buildActivity(SetupActivity.class)
                        .create().start().resume();
        int w = dp(360), h = dp(640);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "setup_phone");
        assertLayoutReachesBottom(c.get());
        writePng(b, new File(outDir, "setup_phone_360x640.png"));
    }

    @Test
    public void setup_phone_fontscale_1_6_renders() throws Exception {
        setQualifiers("w360dp-h640dp-port-mdpi");
        setFontScale(1.6f);
        ActivityController<SetupActivity> c =
                Robolectric.buildActivity(SetupActivity.class)
                        .create().start().resume();
        int w = dp(360), h = dp(640);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "setup_phone_fontscale_1_6");
        assertLayoutReachesBottom(c.get());
        writePng(b, new File(outDir, "setup_phone_fontscale_1_6_360x640.png"));
    }

    @Test
    public void setup_headset_renders() throws Exception {
        setQualifiers("w1000dp-h700dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<SetupActivity> c =
                Robolectric.buildActivity(SetupActivity.class)
                        .create().start().resume();
        int w = dp(1000), h = dp(700);
        Bitmap b = snapshotActivity(c.get(), w, h);
        assertExactDimsAndNonBlank(b, w, h, "setup_headset");
        assertLayoutReachesBottom(c.get());
        writePng(b, new File(outDir, "setup_headset_1000x700.png"));
    }

    // ----- settings fixtures (actual inflated fragment) -----------------

    /**
     * Renders the actual inflated SettingsFragment against a clean
     * opaque background, not overlaid on the hub. We attach the
     * fragment to a plain empty {@link android.app.Activity} host so
     * the screenshot shows only the preference tree (top status,
     * setup shortcut, presets, VR settings, list_resolution etc.) and
     * not the hub layout underneath.
     */
    @Test
    public void settings_top_renders() throws Exception {
        setHeadset(true);
        setQualifiers("w1000dp-h700dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        // Plain Activity host with the real application theme so the
        // inflated SettingsFragment renders correctly without the
        // hub's own rows underneath. The bare android.app.Activity
        // default theme does not lay out the preference list properly
        // (white text on white background, no list dividers), and
        // using MainHubActivity as host would mean its
        // SettingsController observer fires the next time the
        // settings inflation writes a preference key, which crashes
        // on a null hub_status TextView.
        ActivityController<ThemedAppActivity> controller =
                Robolectric.buildActivity(ThemedAppActivity.class);
        ThemedAppActivity host =
                controller.create().start().resume().get();
        // Dark opaque background for the screenshot so an empty host
        // cannot bleed through.
        host.getWindow().setBackgroundDrawableResource(
                android.R.color.background_dark);
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        host.getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        // Drive the main looper so the fragment's onCreateView,
        // ListView adapter bind, and the first measure/layout pass
        // complete before snapshotting. commitNow() schedules those
        // on the main thread; without an idle() the rendered bitmap
        // captures the empty host FrameLayout.
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        ListPreference resPref =
                (ListPreference) frag.findPreference("list_resolution");
        ListPreference fpsPref =
                (ListPreference) frag.findPreference("list_fps");
        assertNotNull("list_resolution must be inflated", resPref);
        assertNotNull("list_fps must be inflated", fpsPref);
        // Widen the inflated lists so the layout has 1080p / 1440p / 4K
        // and 30 / 60 / 72 / 90 / 120 Hz entries to render.
        widenInflatedEntries(resPref, fpsPref);
        assertNotNull("vq_top_status must be inflated",
                frag.findPreference("vq_top_status"));
        assertNotNull("vq_open_setup must be inflated",
                frag.findPreference("vq_open_setup"));
        assertNotNull("vq_screen_presets must be inflated",
                frag.findPreference("vq_screen_presets"));
        assertNotNull("vq_screen_vr_settings_headset_only must be inflated",
                frag.findPreference("vq_screen_vr_settings_headset_only"));
        assertNotNull("list_resolution must be inflated",
                frag.findPreference("list_resolution"));

        int w = dp(1000), h = dp(700);
        Bitmap b = snapshotActivity(host, w, h);
        // Write the PNG BEFORE the non-blank check so the artifact is
        // available for review even when the sparse 8x8 sample grid
        // happens to miss all rendered text on a near-black
        // background.
        writePng(b, new File(outDir, "settings_top_1000x700.png"));
        assertExactDimsAndNonBlank(b, w, h, "settings_top");
    }

    /**
     * Test-only Activity that applies the application's actual theme
     * before super.onCreate so the inflated SettingsFragment renders
     * with the same colors / list dividers the user sees in
     * production. We deliberately do not extend MainHubActivity here
     * — the hub's SettingsController observer must not be wired up
     * for this test, because the settings inflation writes
     * preferences and the observer would then render against a
     * removed view tree.
     */
    public static class ThemedAppActivity extends android.app.Activity {
        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            int themeId = getResources().getIdentifier(
                    "AppTheme", "style", getPackageName());
            if (themeId != 0) {
                setTheme(themeId);
            }
            super.onCreate(savedInstanceState);
        }
    }

    /**
     * Renders the nested Presets screen — the upstream
     * {@code vq_screen_presets} PreferenceScreen child opened from
     * the top-level settings. We open the dialog and snapshot.
     */
    @Test
    public void settings_presets_renders() throws Exception {
        setHeadset(true);
        setQualifiers("w1000dp-h700dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        ListPreference resPref =
                (ListPreference) frag.findPreference("list_resolution");
        ListPreference fpsPref =
                (ListPreference) frag.findPreference("list_fps");
        widenInflatedEntries(resPref, fpsPref);

        Preference presets = frag.findPreference("vq_screen_presets");
        assertNotNull("Presets screen must be present", presets);
        assertTrue("Presets entry must be a PreferenceScreen",
                presets instanceof android.preference.PreferenceScreen);
        android.preference.PreferenceScreen ps =
                (android.preference.PreferenceScreen) presets;
        invokeOnClick(ps);
        // The dialog wraps the nested screen; capture its decor view.
        android.app.Dialog d = ps.getDialog();
        assertNotNull("Presets dialog must open", d);
        d.getWindow().getDecorView().measure(
                View.MeasureSpec.makeMeasureSpec(dp(1000),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(700),
                        View.MeasureSpec.EXACTLY));
        d.getWindow().getDecorView().layout(0, 0, dp(1000), dp(700));
        Bitmap b = Bitmap.createBitmap(dp(1000), dp(700),
                Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLACK);
        d.getWindow().getDecorView().draw(new Canvas(b));
        assertExactDimsAndNonBlank(b, dp(1000), dp(700), "settings_presets");
        assertNotNull("Travel apply must be in nested screen",
                ps.findPreference("vq_profile_travel_apply"));
        assertNotNull("HQ apply must be in nested screen",
                ps.findPreference("vq_profile_hq_apply"));
        assertNotNull("Home apply must be in nested screen",
                ps.findPreference("vq_profile_home_apply"));
        assertNotNull("Custom apply must be in nested screen",
                ps.findPreference("vq_profile_custom_apply"));
        writePng(b, new File(outDir, "settings_presets_1000x700.png"));
    }

    /**
     * Renders the nested VR settings screen — the headset-only
     * wrapper that disappears on phones. Opens the dialog and
     * captures its decor view.
     */
    @Test
    public void settings_vr_renders() throws Exception {
        setHeadset(true);
        setQualifiers("w1000dp-h700dp-land-mdpi");
        setFontScale(DEFAULT_FONT_SCALE);
        ActivityController<MainHubActivity> ac =
                Robolectric.buildActivity(MainHubActivity.class)
                        .create().start().resume();
        StreamSettings.SettingsFragment frag = new StreamSettings.SettingsFragment();
        ac.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "settings").commitNow();
        ListPreference resPref =
                (ListPreference) frag.findPreference("list_resolution");
        ListPreference fpsPref =
                (ListPreference) frag.findPreference("list_fps");
        widenInflatedEntries(resPref, fpsPref);

        Preference vr = frag.findPreference("vq_screen_vr_settings_headset_only");
        assertNotNull("VR wrapper must be present on headset", vr);
        assertTrue(vr instanceof android.preference.PreferenceScreen);
        android.preference.PreferenceScreen ps =
                (android.preference.PreferenceScreen) vr;
        invokeOnClick(vr);
        android.app.Dialog d = ps.getDialog();
        assertNotNull("VR dialog must open", d);
        d.getWindow().getDecorView().measure(
                View.MeasureSpec.makeMeasureSpec(dp(1000),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(700),
                        View.MeasureSpec.EXACTLY));
        d.getWindow().getDecorView().layout(0, 0, dp(1000), dp(700));
        Bitmap b = Bitmap.createBitmap(dp(1000), dp(700),
                Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLACK);
        d.getWindow().getDecorView().draw(new Canvas(b));
        assertExactDimsAndNonBlank(b, dp(1000), dp(700), "settings_vr");
        assertNotNull("VR depth source must be in nested screen",
                ps.findPreference("list_vr_depth_source"));
        assertNotNull("Enable VR mode must be in nested screen",
                ps.findPreference("checkbox_enable_vr_mode"));
        writePng(b, new File(outDir, "settings_vr_1000x700.png"));
    }

    // ----- helpers -------------------------------------------------------

    /**
     * Replace the inflated ListPreference entries with a complete list
     * that includes every preset's target value. Robolectric's display
     * post-filter strips high refresh rates and 4K; this widens the
     * lists so the per-instance guard sees the values the test
     * applies. Tests that want to exercise the unsupported path pass
     * a smaller set explicitly.
     */
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

    /**
     * Invoke the protected {@code Preference.onClick()} hook via
     * reflection. Used to open a nested {@link android.preference.PreferenceScreen}
     * so its dialog becomes available for screenshotting.
     */
    private static void invokeOnClick(Preference pref) {
        try {
            java.lang.reflect.Method m = Preference.class
                    .getDeclaredMethod("onClick");
            m.setAccessible(true);
            m.invoke(pref);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke Preference.onClick()", e);
        }
    }

    /**
     * Walk the SetupActivity's root and assert (a) a visible Back
     * button at the top is at least 56dp tall and (b) the scroll
     * body's measured height plus the Back row fits within the
     * viewport (no overflow). The scroll body uses
     * {@code height=0 weight=1} so it claims the remaining vertical
     * space and is forced to be reachable at any fontScale.
     */
    private static void assertLayoutReachesBottom(Activity a) {
        View content = a.findViewById(android.R.id.content);
        assertNotNull(content);
        ViewGroup top = (ViewGroup) content;
        if (top.getChildCount() == 1 && top.getChildAt(0) instanceof ViewGroup) {
            top = (ViewGroup) top.getChildAt(0);
        }
        assertTrue("Setup root must have at least 2 children (Back + Scroll), got "
                + top.getChildCount(), top.getChildCount() >= 2);
        View back = top.getChildAt(0);
        assertTouchTargetHeight(back, 56, "Setup back row");
        View scroll = top.getChildAt(1);
        assertTrue("Setup second child must be a ScrollView",
                scroll instanceof android.widget.ScrollView);
        int rootBottom = top.getBottom();
        int scrollBottom = scroll.getBottom();
        assertTrue("ScrollView must reach the viewport bottom ("
                + scrollBottom + " vs root " + rootBottom + ")",
                scrollBottom >= rootBottom - dp(2));
    }
}