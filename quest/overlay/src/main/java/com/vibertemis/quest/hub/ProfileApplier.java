package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Bridges hub-defined Home / Travel / Custom screen-streaming presets into
 * the existing Moonlight upstream preference keys. The upstream code reads
 * <code>list_resolution</code>, <code>list_fps</code>,
 * <code>seekbar_bitrate_kbps</code>, and <code>video_format</code> through
 * {@code PreferenceConfiguration} which uses
 * {@link android.preference.PreferenceManager#getDefaultSharedPreferences}.
 * Writing here uses the SAME shared preferences object — no separate file,
 * no second namespace, no chance of a write being ignored.
 *
 * <p>Applying a preset is the ONLY path that writes upstream keys. Opening
 * the preferences screen NEVER writes anything through this class. (Upstream
 * code may legitimately perform its own initial-default / migration writes
 * on screen inflation; those are not this class's responsibility.)
 *
 * <p><b>Custom</b> is a no-op. It does NOT write any upstream key. It is
 * defined as "preserve the user's current settings, unchanged". A Custom
 * tap records the selection in the hub's {@link HubPrefs} only.
 *
 * <p>Home and Travel presets write their canonical values — resolution,
 * fps, bitrate, and codec — and drop the legacy combined
 * {@code list_resolution_fps} key so the upstream migration cannot
 * overwrite the just-applied res/fps. The Home preset writes
 * 40 Mbps, the Travel preset 17 Mbps.
 *
 * <p>Preset codec is always {@code video_format=auto}. The user is free
 * to switch to {@code forceav1} / {@code forceh265} / {@code neverh265}
 * from the existing streaming preferences screen. We never claim a codec
 * negotiation order.
 *
 * <p>Unknown profile names are rejected with no writes at all.
 */
public final class ProfileApplier {
    private static final String TAG = "ProfileApplier";

    /** Upstream preference keys (see PreferenceConfiguration.java). */
    public static final String K_RES = "list_resolution";
    public static final String K_FPS = "list_fps";
    public static final String K_BITRATE_KBPS = "seekbar_bitrate_kbps";
    public static final String K_VIDEO_FORMAT = "video_format";
    public static final String K_LEGACY_RES_FPS = "list_resolution_fps";

    /** Upstream video_format values (see PreferenceConfiguration#getVideoFormatValue). */
    public static final String VIDEO_AUTO = "auto";
    public static final String VIDEO_FORCE_AV1 = "forceav1";
    public static final String VIDEO_FORCE_HEVC = "forceh265";
    public static final String VIDEO_NEVER_HEVC = "neverh265";

    /** Conservative Home preset resolution + fps strings (upstream list values). */
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";

    public static final String FPS_60 = "60";
    public static final String FPS_72 = "72";

    private ProfileApplier() {}

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String defaultResolutionFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return RES_1080P;
        return RES_1440P; // home & custom default if user has none set
    }

    public static String defaultFpsFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return FPS_60;
        return FPS_72;
    }

    public static int defaultBitrateKbpsFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return 17000;
        return 40000;
    }

    /**
     * Apply a named preset.
     *
     * <ul>
     *   <li>{@code home} / {@code travel}: writes canonical res, fps,
     *       bitrate, and {@code video_format=auto} into the upstream
     *       preference keys, and drops the legacy combined key so the
     *       upstream migration cannot overwrite the just-applied values.
     *       Home writes 2560x1440 / 72 / 40 Mbps / auto; Travel writes
     *       1920x1080 / 60 / 17 Mbps / auto. The Custom preset preserves
     *       whatever is currently set.</li>
     *   <li>{@code custom}: writes nothing. Records the selection in
     *       {@link HubPrefs}. The user's current values stay where they are.</li>
     *   <li>anything else: rejected. No writes anywhere.</li>
     * </ul>
     */
    public static void applyProfile(Context ctx, String name) {
        if (name == null) {
            Log.w(TAG, "applyProfile called with null name; no writes");
            return;
        }

        if (HubPrefs.PROFILE_CUSTOM.equals(name)) {
            HubPrefs hp = new HubPrefs(ctx);
            hp.rememberProfile(name);
            Log.i(TAG, "Custom selected — no upstream writes performed");
            return;
        }

        if (HubPrefs.PROFILE_HOME.equals(name)
                || HubPrefs.PROFILE_TRAVEL.equals(name)) {
            SharedPreferences p = prefs(ctx);
            SharedPreferences.Editor e = p.edit();

            e.putString(K_RES, defaultResolutionFor(name));
            e.putString(K_FPS, defaultFpsFor(name));
            e.putInt(K_BITRATE_KBPS, defaultBitrateKbpsFor(name));
            e.putString(K_VIDEO_FORMAT, VIDEO_AUTO);
            // Drop the legacy combined key only on explicit Apply so the
            // upstream migration (which would otherwise repopulate
            // list_resolution/list_fps from list_resolution_fps) cannot
            // overwrite what we just wrote.
            e.remove(K_LEGACY_RES_FPS);
            e.apply();

            HubPrefs hp = new HubPrefs(ctx);
            hp.rememberProfile(name);

            Log.i(TAG, "Applied preset " + name
                    + " res=" + defaultResolutionFor(name)
                    + " fps=" + defaultFpsFor(name)
                    + " bitrateKbps=" + defaultBitrateKbpsFor(name)
                    + " video=" + VIDEO_AUTO
                    + " (legacy cleared)");
            return;
        }

        Log.w(TAG, "Unknown profile name '" + name + "'; no writes performed");
    }
}
