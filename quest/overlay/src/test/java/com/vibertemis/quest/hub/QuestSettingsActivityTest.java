package com.vibertemis.quest.hub;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Vibertemis settings screen ("Quest settings" artboard): presets and
 * pickers write the same upstream keys PreferenceConfiguration reads, and
 * every section renders. Screenshots land in app/build/reports/quest-ui.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = ShadowMoonBridge.class, qualifiers = "w1000dp-h700dp-land-mdpi")
public class QuestSettingsActivityTest {

    private SharedPreferences prefs;
    private File outDir;

    @Before public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
        outDir = new File("app/build/reports/quest-ui");
        if (!outDir.exists()) outDir = new File("build/reports/quest-ui");
        outDir.mkdirs();
    }

    private ActivityController<QuestSettingsActivity> open() {
        return Robolectric.buildActivity(QuestSettingsActivity.class).create().start().resume().visible();
    }

    @Test public void travelPresetWritesItsUpstreamValues() {
        QuestSettingsActivity a = open().get();
        View travel = findByDescription(a, "Travel");
        assertNotNull("Travel preset card", travel);
        travel.performClick();
        assertEquals(ProfileApplier.RES_1080P, prefs.getString(ProfileApplier.K_RES, null));
        assertEquals(ProfileApplier.FPS_60, prefs.getString(ProfileApplier.K_FPS, null));
        assertEquals(ProfileApplier.BITRATE_TRAVEL_KBPS, prefs.getInt(ProfileApplier.K_BITRATE_KBPS, -1));
        assertNotNull("Travel now reads as selected", findByDescription(a, "Travel, selected"));
    }

    @Test public void codecSegmentWritesVideoFormat() {
        QuestSettingsActivity a = open().get();
        View av1 = findByDescription(a, "AV1");
        assertNotNull("AV1 segment", av1);
        av1.performClick();
        assertEquals(ProfileApplier.VIDEO_FORCE_AV1, prefs.getString(ProfileApplier.K_VIDEO_FORMAT, null));
        assertNotNull(findByDescription(a, "AV1, selected"));
    }

    @Test public void frameRateSegmentWritesFps() {
        QuestSettingsActivity a = open().get();
        findByDescription(a, "120").performClick();
        assertEquals("120", prefs.getString(ProfileApplier.K_FPS, null));
    }

    @Test public void controlsToggleWritesItsKey() {
        QuestSettingsActivity a = open().get();
        a.selectSectionForTest(2);
        Switch flip = (Switch) findByDescription(a, "Flip face buttons");
        assertNotNull(flip);
        flip.performClick();
        assertTrue(prefs.getBoolean("checkbox_flip_face_buttons", false));
    }

    @Test public void everySectionRenders() throws Exception {
        QuestSettingsActivity a = open().get();
        for (int i = 0; i < QuestSettingsActivity.SECTIONS.length; i++) {
            a.selectSectionForTest(i);
            Bitmap b = snapshot(a, 1000, 700);
            String name = QuestSettingsActivity.SECTIONS[i].toLowerCase()
                    .replace(" & ", "_").replace(' ', '_');
            try (FileOutputStream out = new FileOutputStream(new File(outDir, "settings_" + name + "_1000x700.png"))) {
                assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        }
    }

    private static Bitmap snapshot(QuestSettingsActivity a, int wDp, int hDp) {
        float d = a.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * d), h = Math.round(hDp * d);
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLACK);
        root.draw(new Canvas(b));
        return b;
    }

    private static View findByDescription(QuestSettingsActivity a, String description) {
        List<View> out = new ArrayList<>();
        collect(a.findViewById(android.R.id.content), out);
        for (View v : out) {
            CharSequence cd = v.getContentDescription();
            if (cd != null && description.contentEquals(cd)) return v;
        }
        return null;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
