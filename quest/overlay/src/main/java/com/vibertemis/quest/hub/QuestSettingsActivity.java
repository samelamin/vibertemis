package com.vibertemis.quest.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.vibertemis.quest.pcvr.HostPairing;
import com.vibertemis.quest.pcvr.PairingStore;
import com.vibertemis.quest.pcvr.PcvrSettingsActivity;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Vibertemis settings ("Quest settings" artboard): a section rail on the
 * left, the selected section on the right. Big Screen has preset cards and
 * native pickers for the four values people actually change; the other
 * sections hold the common toggles. Every value is the upstream
 * preference key PreferenceConfiguration already reads, so streaming
 * behaviour is unchanged, and "All settings" opens the full upstream
 * StreamSettings list for everything else.
 */
public class QuestSettingsActivity extends Activity {

    static final String[] SECTIONS = {
            "Big Screen", "Full VR", "Controls", "Display & 3D", "Updates", "About"};

    /** Codec segments: label and upstream video_format value. */
    private static final String[][] CODECS = {
            {ProfileApplier.CODEC_LABEL_AUTO, ProfileApplier.VIDEO_AUTO},
            {ProfileApplier.CODEC_LABEL_HEVC, ProfileApplier.VIDEO_FORCE_HEVC},
            {ProfileApplier.CODEC_LABEL_AV1, ProfileApplier.VIDEO_FORCE_AV1},
            {ProfileApplier.CODEC_LABEL_H264, ProfileApplier.VIDEO_NEVER_HEVC}};

    /** Presets: HubPrefs id, title. Specs are rendered from ProfileApplier defaults. */
    private static final String[][] PRESETS = {
            {HubPrefs.PROFILE_HOME, "Home"},
            {HubPrefs.PROFILE_TRAVEL, "Travel"},
            {HubPrefs.PROFILE_HQ, "High quality"},
            {HubPrefs.PROFILE_CUSTOM, "Custom"}};

    private static final int BITRATE_MIN_KBPS = 500;
    private static final int BITRATE_MAX_KBPS = 200000;
    private static final int BITRATE_STEP_KBPS = 500;

    private SharedPreferences prefs;
    private float density;
    private int section;
    private LinearLayout rail;
    private LinearLayout content;
    private ScrollView contentScroll;
    private boolean launchPending;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        density = getResources().getDisplayMetrics().density;
        section = saved == null ? 0 : saved.getInt("section", 0);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.hub_panel_bg));

        // ---- Section rail ----
        ScrollView railScroll = new ScrollView(this);
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(20), dp(24), dp(20), dp(24));
        railScroll.addView(rail);
        root.addView(railScroll, wide
                ? new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        if (wide) {
            View divider = new View(this);
            divider.setBackgroundColor(color(R.color.hub_panel_card_stroke));
            root.addView(divider, new LinearLayout.LayoutParams(Math.max(1, dp(1) / 2 + 1),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // ---- Section content ----
        contentScroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(wide ? 48 : 20), dp(32), dp(wide ? 48 : 20), dp(32));
        contentScroll.addView(content);
        root.addView(contentScroll, wide
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchPending = false;
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("section", section);
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    private void render() {
        renderRail();
        content.removeAllViews();
        switch (section) {
            case 0: renderBigScreen(); break;
            case 1: renderFullVr(); break;
            case 2: renderControls(); break;
            case 3: renderDisplay(); break;
            case 4: renderUpdates(); break;
            default: renderAbout(); break;
        }
    }

    private void renderRail() {
        rail.removeAllViews();
        Button back = button("Back", R.drawable.hub_btn_ghost_bg, R.color.hub_panel_subtle, 15, false);
        back.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.upd_ic_back, 0, 0, 0);
        back.setCompoundDrawablePadding(dp(6));
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        back.setOnClickListener(v -> finish());
        rail.addView(back, wrap(0, 0, 0, dp(12)));

        TextView title = text("Settings", 26, R.color.hub_panel_fg, true);
        title.setPadding(dp(14), 0, 0, dp(16));
        rail.addView(title);

        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            boolean on = i == section;
            Button b = button(SECTIONS[i], on ? R.drawable.qs_nav_on : R.drawable.hub_btn_ghost_bg,
                    on ? R.color.hub_panel_fg : R.color.hub_panel_subtle, 16, false);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(16), 0, dp(16), 0);
            b.setOnClickListener(v -> { section = index; contentScroll.scrollTo(0, 0); render(); });
            rail.addView(b, full(0, 0, 0, dp(4)));
        }
    }

    private void renderBigScreen() {
        heading("Big Screen streaming", "Pick a preset. Fine-tune below if you need to.");

        ProfileApplier.CurrentSettings now = ProfileApplier.currentSettings(this);
        String active = now.profile != null ? now.profile : HubPrefs.PROFILE_CUSTOM;

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(presets, full(0, 0, 0, dp(28)));
        for (int i = 0; i < PRESETS.length; i++) {
            final String id = PRESETS[i][0];
            boolean on = id.equals(active);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(18), dp(14), dp(18), dp(14));
            card.setMinimumHeight(dp(104));
            card.setBackgroundResource(on ? R.drawable.qs_preset_on : R.drawable.qs_preset_off);
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription(PRESETS[i][1] + (on ? ", selected" : ""));
            card.addView(text(PRESETS[i][1], 17, R.color.hub_panel_fg, true));
            TextView spec = text(presetSpec(id), 14, R.color.hub_panel_subtle, false);
            spec.setPadding(0, dp(4), 0, 0);
            card.addView(spec);
            card.setOnClickListener(v -> applyPreset(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(12));
            presets.addView(card, lp);
        }

        LinearLayout group = group();

        // Resolution
        LinearLayout resRow = row(group, "Resolution", null);
        Button res = button(resolutionLabel(now.resolution) + "  ▾",
                R.drawable.hub_btn_secondary_bg, R.color.hub_panel_fg, 15, false);
        res.setPadding(dp(16), 0, dp(16), 0);
        res.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            String[] values = getResources().getStringArray(R.array.resolution_values);
            for (int i = 0; i < values.length; i++) menu.getMenu().add(0, i, i, resolutionLabel(values[i]));
            menu.setOnMenuItemClickListener(item -> {
                writeCustom(ProfileApplier.K_RES, values[item.getItemId()]);
                return true;
            });
            menu.show();
        });
        resRow.addView(res, wrap(0, 0, 0, 0));
        divider(group);

        // Frame rate
        LinearLayout fpsRow = row(group, "Frame rate", null);
        String[] fpsValues = getResources().getStringArray(R.array.fps_values);
        String[][] fpsSegments = new String[fpsValues.length][];
        for (int i = 0; i < fpsValues.length; i++) fpsSegments[i] = new String[]{fpsValues[i], fpsValues[i]};
        fpsRow.addView(segmented(fpsSegments, now.fps, v -> writeCustom(ProfileApplier.K_FPS, v)));
        divider(group);

        // Bitrate
        LinearLayout bitRow = row(group, "Bitrate", null);
        // A fixed label column so the slider gets the rest of the row.
        bitRow.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(dp(180),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView bitLabel = text(mbps(now.bitrateKbps) + " Mbps", 16,
                R.color.hub_panel_fg, true);
        bitLabel.setGravity(Gravity.END);
        SeekBar seek = new SeekBar(this);
        seek.setMax((BITRATE_MAX_KBPS - BITRATE_MIN_KBPS) / BITRATE_STEP_KBPS);
        seek.setProgress(Math.max(0, (now.bitrateKbps - BITRATE_MIN_KBPS) / BITRATE_STEP_KBPS));
        seek.setContentDescription("Bitrate");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                bitLabel.setText(mbps(BITRATE_MIN_KBPS + p * BITRATE_STEP_KBPS) + " Mbps");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(ProfileApplier.K_BITRATE_KBPS,
                        BITRATE_MIN_KBPS + s.getProgress() * BITRATE_STEP_KBPS).apply();
                prefs.edit().remove(ProfileApplier.K_LEGACY_RES_FPS).apply();
                render();
            }
        });
        bitRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bitRow.addView(bitLabel, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        divider(group);

        // Codec
        LinearLayout codecRow = row(group, "Video codec", "Auto picks the best codec your PC supports");
        codecRow.addView(segmented(CODECS, now.videoFormatRaw,
                v -> writeCustom(ProfileApplier.K_VIDEO_FORMAT, v)));

        linkRow("Advanced: HDR, frame pacing, audio, 3D depth", this::openAllSettings);
    }

    private void renderFullVr() {
        heading("Full VR", "SteamVR streaming through VR Host Manager on your PC.");
        LinearLayout group = group();
        String pc = pairedAddress();
        LinearLayout pcRow = row(group, "Paired PC", pc == null
                ? "No PC paired for VR yet. Use Set up PC on the home screen."
                : pc + " · checked each time you start VR");
        Button open = button("PC pairing and codec", R.drawable.hub_btn_secondary_bg,
                R.color.hub_panel_fg, 15, false);
        open.setPadding(dp(16), 0, dp(16), 0);
        open.setOnClickListener(v -> launch(new Intent(this, PcvrSettingsActivity.class)));
        pcRow.addView(open, wrap(0, 0, 0, 0));
        divider(group);
        toggle(group, "checkbox_vr_hand_tracking", true, "Hand tracking",
                "Use your hands when the controllers are down");
        divider(group);
        toggle(group, "checkbox_vr_passthrough", false, "Passthrough",
                "See your room around the stream");
    }

    private void renderControls() {
        heading("Controls", "How Touch controllers and gamepads behave.");
        LinearLayout group = group();
        toggle(group, "checkbox_vr_pointer", true, "Controllers as a mouse",
                "Point and click on the virtual screen");
        divider(group);
        toggle(group, "checkbox_vr_gaze", true, "Look to point", "Aim the pointer with your head");
        divider(group);
        toggle(group, "checkbox_mouse_emulation", true, "Mouse with a gamepad",
                "Hold Start to move the mouse with the stick");
        divider(group);
        toggle(group, "checkbox_flip_face_buttons", false, "Flip face buttons",
                "Swap A/B and X/Y");
        divider(group);
        toggle(group, "checkbox_vibrate_fallback", false, "Rumble fallback",
                "Vibrate the controller when a game uses rumble");
        linkRow("All controller settings", this::openAllSettings);
    }

    private void renderDisplay() {
        heading("Display & 3D", "How the stream looks in the headset.");
        LinearLayout group = group();
        toggle(group, "checkbox_enable_vr_mode", true, "Stream in VR",
                "Show the PC on a virtual screen instead of a flat window");
        divider(group);
        toggle(group, "checkbox_enable_hdr", false, "HDR",
                "Experimental. Needs an HDR-capable PC and game");
        divider(group);
        toggle(group, "checkbox_stretch_video", false, "Stretch to fill",
                "Fill the screen instead of keeping the aspect ratio");
        divider(group);
        toggle(group, "checkbox_enable_perf_overlay", false, "Performance stats",
                "Show frame rate and latency while streaming");
        linkRow("3D depth and environment", this::openAllSettings);
    }

    private void renderUpdates() {
        heading("Updates", "The app checks for updates when you open it, at most every 30 minutes.");
        LinearLayout group = group();
        LinearLayout r = row(group, "App version", installedVersion());
        Button open = button("Check for updates", R.drawable.hub_btn_primary_bg,
                R.color.hub_panel_accent_on, 15, true);
        open.setPadding(dp(18), 0, dp(18), 0);
        open.setOnClickListener(v -> launch(new Intent(this,
                com.vibertemis.quest.update.UpdatesActivity.class)
                .putExtra(com.vibertemis.quest.update.UpdatesActivity.EXTRA_REQUEST_UPDATE, true)));
        r.addView(open, wrap(0, 0, 0, 0));
        divider(group);
        row(group, "Windows VR host", "Updates separately, from VR Host Manager on your PC");
    }

    private void renderAbout() {
        heading("About", null);
        LinearLayout group = group();
        row(group, "Vibertemis", installedVersion());
        divider(group);
        LinearLayout help = row(group, "Setup help", "How to connect your PC for each mode");
        Button open = button("Open", R.drawable.hub_btn_secondary_bg, R.color.hub_panel_fg, 15, false);
        open.setPadding(dp(18), 0, dp(18), 0);
        open.setOnClickListener(v -> launch(new Intent(this, SetupActivity.class)));
        help.addView(open, wrap(0, 0, 0, 0));
        linkRow("All settings", this::openAllSettings);
    }

    // ------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------

    private void applyPreset(String id) {
        if (HubPrefs.PROFILE_CUSTOM.equals(id)) {
            // Custom keeps the current values; the pickers below are its editor.
            new HubPrefs(this).rememberProfile(id);
            Toast.makeText(this, "Custom: change the values below", Toast.LENGTH_SHORT).show();
            return;
        }
        ProfileApplier.AllowedValues allowed = new ProfileApplier.AllowedValues(
                new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.resolution_values))),
                new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.fps_values))));
        ProfileApplier.applyProfile(this, id, allowed, (result, profile, r, f, kbps) -> runOnUiThread(() -> {
            if (result == ProfileApplier.Result.UNSUPPORTED) {
                Toast.makeText(this, "This headset can't use that preset", Toast.LENGTH_LONG).show();
            }
            render();
        }));
    }

    /** A hand-edited value: write it and drop the legacy combined key. */
    private void writeCustom(String key, String value) {
        prefs.edit().putString(key, value).remove(ProfileApplier.K_LEGACY_RES_FPS).apply();
        new HubPrefs(this).rememberProfile(HubPrefs.PROFILE_CUSTOM);
        render();
    }

    private void openAllSettings() {
        launch(new Intent(this, StreamSettings.class));
    }

    private void launch(Intent i) {
        if (launchPending) return;
        try {
            launchPending = true;
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            launchPending = false;
            Toast.makeText(this, "That screen is unavailable", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    //  Values
    // ------------------------------------------------------------------

    private static String presetSpec(String id) {
        if (HubPrefs.PROFILE_CUSTOM.equals(id)) return "Your own values";
        return resolutionShort(ProfileApplier.defaultResolutionFor(id)) + " · "
                + ProfileApplier.defaultFpsFor(id) + " fps · "
                + mbps(ProfileApplier.defaultBitrateKbpsFor(id)) + " Mbps";
    }

    /** "40" rather than "40.0"; keeps a decimal only when there is one. */
    private static String mbps(int kbps) {
        String f = ProfileApplier.formatMbps(kbps);
        return f.endsWith(".0") ? f.substring(0, f.length() - 2) : f;
    }

    private static String resolutionShort(String res) {
        if (ProfileApplier.RES_4K.equals(res)) return "4K";
        int x = res == null ? -1 : res.indexOf('x');
        return x > 0 ? res.substring(x + 1) + "p" : String.valueOf(res);
    }

    private static String resolutionLabel(String res) {
        return res == null ? "" : res.replace("x", " × ");
    }

    private String pairedAddress() {
        try {
            HostPairing saved = new PairingStore(getApplicationContext()).load();
            return saved == null ? null : saved.address;
        } catch (Exception e) {
            return null;
        }
    }

    private String installedVersion() {
        try {
            String n = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int dash = n == null ? -1 : n.indexOf('-');
            return dash > 0 ? n.substring(0, dash) : n;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "Unknown";
        }
    }

    // ------------------------------------------------------------------
    //  Building blocks
    // ------------------------------------------------------------------

    private interface Pick { void on(String value); }

    private void heading(String title, String subtitle) {
        content.addView(text(title, 28, R.color.hub_panel_fg, true), full(0, 0, 0, dp(6)));
        if (subtitle != null) {
            content.addView(text(subtitle, 15, R.color.hub_panel_subtle, false), full(0, 0, 0, dp(24)));
        } else {
            content.addView(new View(this), full(0, 0, 0, dp(18)));
        }
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundResource(R.drawable.qs_group_bg);
        g.setClipToOutline(true);
        content.addView(g, full(0, 0, 0, dp(16)));
        return g;
    }

    private LinearLayout row(LinearLayout group, String title, String summary) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(72));
        r.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, R.color.hub_panel_fg, true));
        if (summary != null) {
            TextView s = text(summary, 13, R.color.hub_panel_faint, false);
            s.setPadding(0, dp(2), dp(16), 0);
            labels.addView(s);
        }
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        group.addView(r, full(0, 0, 0, 0));
        return r;
    }

    private void toggle(LinearLayout group, String key, boolean def, String title, String summary) {
        LinearLayout r = row(group, title, summary);
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        r.addView(sw, wrap(0, 0, 0, 0));
        r.setOnClickListener(v -> sw.toggle());
    }

    private View segmented(String[][] segments, String selected, Pick pick) {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackgroundResource(R.drawable.qs_segment_track);
        for (String[] s : segments) {
            boolean on = s[1].equals(selected);
            Button b = button(s[0], on ? R.drawable.qs_segment_on : R.drawable.qs_segment_off,
                    on ? R.color.hub_panel_accent_on : R.color.hub_panel_subtle, 14, on);
            b.setMinWidth(dp(60));
            b.setMinimumWidth(dp(60));
            b.setMinHeight(dp(44));
            b.setMinimumHeight(dp(44));
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setContentDescription(s[0] + (on ? ", selected" : ""));
            b.setOnClickListener(v -> pick.on(s[1]));
            seg.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        return seg;
    }

    private void divider(LinearLayout group) {
        View d = new View(this);
        d.setBackgroundColor(color(R.color.hub_panel_card_stroke));
        group.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
    }

    private void linkRow(String label, Runnable action) {
        Button b = button(label, R.drawable.hub_btn_ghost_bg, R.color.hub_panel_subtle, 15, false);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.qs_ic_chevron, 0);
        b.setCompoundDrawablePadding(dp(8));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(8), 0, dp(12), 0);
        b.setOnClickListener(v -> action.run());
        content.addView(b, wrap(dp(8), 0, 0, 0));
    }

    private Button button(String label, int bg, int fg, int sizeSp, boolean bold) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(sizeSp);
        b.setTextColor(color(fg));
        if (bold) b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(bg);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private TextView text(String s, int sizeSp, int colorRes, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color(colorRes));
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams full(int t, int s, int e, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = t; p.setMarginStart(s); p.setMarginEnd(e); p.bottomMargin = b;
        return p;
    }

    private LinearLayout.LayoutParams wrap(int t, int s, int e, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = t; p.setMarginStart(s); p.setMarginEnd(e); p.bottomMargin = b;
        return p;
    }

    private int dp(int v) { return Math.round(v * density); }

    private int color(int res) { return getResources().getColor(res, getTheme()); }

    // Test hooks
    int sectionForTest() { return section; }
    void selectSectionForTest(int i) { section = i; render(); }
}
