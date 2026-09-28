package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Bridges hub-defined Home / Travel / High quality LAN / Custom
 * screen-streaming presets into the existing Moonlight upstream preference
 * keys. The upstream code reads <code>list_resolution</code>,
 * <code>list_fps</code>, <code>seekbar_bitrate_kbps</code>, and
 * <code>video_format</code> through {@code PreferenceConfiguration}, which
 * uses {@link PreferenceManager#getDefaultSharedPreferences}. Writing here
 * uses the SAME shared preferences object — no separate file, no second
 * namespace, no chance of a write being ignored.
 *
 * <p>Applying a preset is the ONLY path that writes upstream keys.
 * Opening the preferences screen NEVER writes anything through this
 * class. (Upstream code may legitimately perform its own initial-default /
 * migration writes on screen inflation; those are not this class's
 * responsibility.)
 *
 * <p><b>Custom</b> is a no-op. It does NOT write any upstream key. It is
 * defined as "preserve the user's current settings, unchanged". A Custom
 * tap records the selection in the hub's {@link HubPrefs} only.
 *
 * <p>Home / Travel / HQ presets write their canonical values —
 * resolution, fps, bitrate, and codec — and drop the legacy combined
 * {@code list_resolution_fps} key so the upstream migration cannot
 * overwrite the just-applied res/fps. The Home preset writes
 * 40 Mbps, the Travel preset 17 Mbps, the HQ preset 80 Mbps. The codec
 * is always {@code video_format=auto}; the user is free to switch to
 * {@code forceav1} / {@code forceh265} / {@code neverh265} from the
 * streaming preferences screen.
 *
 * <p>Capability decision (whether the preset is supported on the current
 * device) is delegated to the inflated upstream {@code ListPreference}
 * widget, which has already been filtered by the upstream decoder and
 * display checks in {@code StreamSettings}. This class does NOT inspect
 * the device's raw {@code Display.Mode} list — decoder support, not
 * panel resolution, is what determines whether a resolution is viable.
 *
 * <p>If the preset's resolution or fps is not in the live, inflated
 * upstream entry list, the apply call surfaces an unsupported-result
 * code and writes nothing. The hub shows the explanation dialog and the
 * upstream keys are untouched.
 *
 * <p>Unknown profile names are rejected with no writes at all.
 *
 * <p>Per-instance guard: callers that need capability gating pass an
 * {@link AllowedValues} snapshot at confirm time. There is no static /
 * global provider retained between calls. Pure-transaction callers (the
 * JUnit suite) call {@link #applyPureTransaction(Context, String)} which
 * performs no guard at all and writes the canonical values directly —
 * this is the path the unit tests exercise.
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

    /** User-facing codec labels. Stable on disk as the upstream string. */
    public static final String CODEC_LABEL_AUTO = "Auto";
    public static final String CODEC_LABEL_H264 = "H.264";
    public static final String CODEC_LABEL_HEVC = "HEVC";
    public static final String CODEC_LABEL_AV1 = "AV1";

    /** Preset canonical resolution / fps / bitrate values. */
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";

    public static final String FPS_60 = "60";
    public static final String FPS_72 = "72";
    public static final String FPS_90 = "90";
    public static final String FPS_120 = "120";

    /** Preset canonical bitrate in Kbps (matches SeekBar step 500). */
    public static final int BITRATE_HOME_KBPS = 40000;
    public static final int BITRATE_TRAVEL_KBPS = 17000;
    public static final int BITRATE_HQ_KBPS = 80000;

    /** Result code returned through the apply callback. */
    public enum Result {
        APPLIED,
        APPLIED_CUSTOM,
        UNSUPPORTED,
        UNKNOWN,
    }

    /** Callback invoked once the apply attempt completes. */
    public interface ApplyCallback {
        void onResult(Result result, String profile,
                      String res, String fps, int bitrateKbps);
    }

    /**
     * Per-call snapshot of the inflated upstream
     * {@code list_resolution} / {@code list_fps} entry lists. Snapshot
     * is taken at confirm time; callers do not retain a static /
     * global provider across calls.
     */
    public static final class AllowedValues {
        public final Set<String> resolutions;
        public final Set<String> fps;

        public AllowedValues(Collection<String> resolutions,
                             Collection<String> fps) {
            this.resolutions = resolutions == null
                    ? Collections.<String>emptySet()
                    : new HashSet<>(resolutions);
            this.fps = fps == null
                    ? Collections.<String>emptySet()
                    : new HashSet<>(fps);
        }

        public boolean allows(String res, String fpsValue) {
            return resolutions.contains(res) && this.fps.contains(fpsValue);
        }
    }

    private ProfileApplier() {}

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String defaultResolutionFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return RES_1080P;
        if (HubPrefs.PROFILE_HQ.equals(profile)) return RES_4K;
        return RES_1440P;
    }

    public static String defaultFpsFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return FPS_60;
        if (HubPrefs.PROFILE_HQ.equals(profile)) return FPS_60;
        return FPS_72;
    }

    public static int defaultBitrateKbpsFor(String profile) {
        if (HubPrefs.PROFILE_TRAVEL.equals(profile)) return BITRATE_TRAVEL_KBPS;
        if (HubPrefs.PROFILE_HQ.equals(profile)) return BITRATE_HQ_KBPS;
        return BITRATE_HOME_KBPS;
    }

    /**
     * User-facing codec label. Maps the upstream enum / string to
     * "Auto" / "H.264" / "HEVC" / "AV1" so the hub status line and
     * the profile confirmation dialog match the rest of the app.
     */
    public static String codecLabel(String videoFormat) {
        if (videoFormat == null) return CODEC_LABEL_AUTO;
        if (VIDEO_AUTO.equals(videoFormat)) return CODEC_LABEL_AUTO;
        if (VIDEO_FORCE_AV1.equals(videoFormat)) return CODEC_LABEL_AV1;
        if (VIDEO_FORCE_HEVC.equals(videoFormat)) return CODEC_LABEL_HEVC;
        if (VIDEO_NEVER_HEVC.equals(videoFormat)) return CODEC_LABEL_H264;
        return videoFormat;
    }

    /**
     * Format a bitrate in Kbps as a one-decimal Mbps string suitable
     * for the hub status line and the preset confirmation dialog. The
     * upstream SeekBar steps in 500 Kbps increments; integer division
     * {@code bitrateKbps / 1000} loses that resolution. {@code 17500}
     * must read as {@code 17.5 Mbps}, not {@code 17 Mbps}.
     */
    public static String formatMbps(int bitrateKbps) {
        if (bitrateKbps <= 0) return "0.0";
        double mbps = bitrateKbps / 1000.0;
        return String.format(java.util.Locale.US, "%.1f", mbps);
    }

    /**
     * Match the profile name against the current upstream settings.
     * Used by the hub status line to label which preset (if any) the
     * current values correspond to. Returns null for Custom / unknown
     * (no durable "applied" success tag).
     */
    public static String matchProfile(String res, String fps, int bitrateKbps,
                                      String videoFormat) {
        if (res == null || fps == null) return null;
        if (!VIDEO_AUTO.equals(videoFormat)) return null;
        if (RES_1080P.equals(res) && FPS_60.equals(fps)
                && bitrateKbps == BITRATE_TRAVEL_KBPS) {
            return HubPrefs.PROFILE_TRAVEL;
        }
        if (RES_1440P.equals(res) && FPS_72.equals(fps)
                && bitrateKbps == BITRATE_HOME_KBPS) {
            return HubPrefs.PROFILE_HOME;
        }
        if (RES_4K.equals(res) && FPS_60.equals(fps)
                && bitrateKbps == BITRATE_HQ_KBPS) {
            return HubPrefs.PROFILE_HQ;
        }
        return null;
    }

    /**
     * Pure-transaction variant used by the JUnit suite. No callback, no
     * guard, no hub-state mutation other than remembering the profile.
     * UI callers must use {@link #applyProfile(Context, String,
     * AllowedValues, ApplyCallback)} instead.
     */
    public static void applyPureTransaction(Context ctx, String name) {
        applyProfileInternal(ctx, name, null, null, true);
    }

    /**
     * Apply a named preset. Confirms the preset's resolution and fps
     * are both present in the supplied {@code allowed} snapshot before
     * touching any preference; if the snapshot is null or its sets are
     * empty, the call is treated as not-confirmed and fires
     * {@link Result#UNSUPPORTED} (or {@link Result#UNKNOWN} for an
     * unknown profile name).
     *
     * <ul>
     *   <li>{@code home} / {@code travel} / {@code hq}: writes canonical
     *       res, fps, bitrate, and {@code video_format=auto} into the
     *       upstream preference keys via {@code apply()} (the in-memory
     *       cache is updated synchronously; the disk write is async).
     *       Drops the legacy combined key so the upstream migration
     *       cannot overwrite the just-applied res/fps.</li>
     *   <li>{@code custom}: writes nothing. Records the selection in
     *       {@link HubPrefs}.</li>
     *   <li>anything else: rejected. No writes anywhere.</li>
     *   <li>resolution or fps not in the supplied snapshot: the callback
     *       fires with {@link Result#UNSUPPORTED} and nothing is
     *       written.</li>
     * </ul>
     */
    public static void applyProfile(Context ctx, String name,
                                    AllowedValues allowed,
                                    ApplyCallback callback) {
        applyProfileInternal(ctx, name, allowed, callback, false);
    }

    /** Convenience wrapper for callers without a guard or callback. */
    public static void applyProfile(Context ctx, String name) {
        applyProfileInternal(ctx, name, null, null, false);
    }

    private static void applyProfileInternal(Context ctx, String name,
                                             AllowedValues allowed,
                                             ApplyCallback callback,
                                             boolean pureTransaction) {
        if (name == null) {
            Log.w(TAG, "applyProfile called with null name; no writes");
            if (callback != null) callback.onResult(Result.UNKNOWN, null, null, null, 0);
            return;
        }

        if (HubPrefs.PROFILE_CUSTOM.equals(name)) {
            HubPrefs hp = new HubPrefs(ctx);
            hp.rememberProfile(name);
            Log.i(TAG, "Custom selected — no upstream writes performed");
            if (callback != null) {
                callback.onResult(Result.APPLIED_CUSTOM, name, null, null, 0);
            }
            return;
        }

        if (HubPrefs.PROFILE_HOME.equals(name)
                || HubPrefs.PROFILE_TRAVEL.equals(name)
                || HubPrefs.PROFILE_HQ.equals(name)) {
            String res = defaultResolutionFor(name);
            String fps = defaultFpsFor(name);
            int bitrate = defaultBitrateKbpsFor(name);

            // Guard against applying values that are not in the live,
            // inflated upstream entry list. A null snapshot (typical
            // when the upstream SettingsFragment is not in scope) is
            // a fail-closed guard for UI callers; the pure-transaction
            // path explicitly opts out so the unit tests can write
            // canonical values without inflating the fragment.
            if (!pureTransaction) {
                if (allowed == null
                        || !allowed.allows(res, fps)) {
                    Log.w(TAG, "Preset " + name
                            + " not in inflated entry lists ("
                            + res + " @ " + fps + "); no writes");
                    if (callback != null) {
                        callback.onResult(Result.UNSUPPORTED,
                                name, res, fps, bitrate);
                    }
                    return;
                }
            }

            SharedPreferences p = prefs(ctx);
            SharedPreferences.Editor e = p.edit();

            e.putString(K_RES, res);
            e.putString(K_FPS, fps);
            e.putInt(K_BITRATE_KBPS, bitrate);
            e.putString(K_VIDEO_FORMAT, VIDEO_AUTO);
            e.remove(K_LEGACY_RES_FPS);
            // apply() updates the in-memory cache synchronously and
            // writes to disk asynchronously. commit() would block the
            // UI thread on disk I/O for a user-initiated write that
            // does not need to be durable before the next read.
            e.apply();

            HubPrefs hp = new HubPrefs(ctx);
            hp.rememberProfile(name);

            Log.i(TAG, "Applied preset " + name
                    + " res=" + res + " fps=" + fps
                    + " bitrateKbps=" + bitrate
                    + " video=" + VIDEO_AUTO
                    + " (legacy cleared)");
            if (callback != null) {
                callback.onResult(Result.APPLIED, name, res, fps, bitrate);
            }
            return;
        }

        Log.w(TAG, "Unknown profile name '" + name + "'; no writes performed");
        if (callback != null) callback.onResult(Result.UNKNOWN, name, null, null, 0);
    }

    /**
     * Read the current upstream streaming settings through the same
     * reader the rest of the app uses. We deliberately go through
     * {@link PreferenceConfiguration#readPreferences(Context)} so the
     * effective values come from the real upstream migration path (no
     * invented defaults here). The reader may itself perform migration
     * writes the first time it runs on a fresh install; those are
     * upstream's responsibility, not this class's.
     *
     * <p>{@code bitrateKbps} is the raw upstream value; format it via
     * {@link #formatMbps(int)} for display. {@code codecLabel} is the
     * user-facing codec string ({@code Auto} / {@code H.264} /
     * {@code HEVC} / {@code AV1}), not the internal enum name.
     */
    public static CurrentSettings currentSettings(Context ctx) {
        PreferenceConfiguration cfg = PreferenceConfiguration.readPreferences(ctx);
        String res = cfg.width + "x" + cfg.height;
        String fps = String.valueOf(cfg.fps);
        String codec = codecLabel(codecString(cfg.videoFormat));
        String profile = matchProfile(res, fps, cfg.bitrate, codecString(cfg.videoFormat));
        return new CurrentSettings(res, fps, cfg.bitrate, codec,
                codecString(cfg.videoFormat), profile, cfg);
    }

    private static String codecString(PreferenceConfiguration.FormatOption v) {
        if (v == null) return VIDEO_AUTO;
        switch (v) {
            case FORCE_AV1: return VIDEO_FORCE_AV1;
            case FORCE_HEVC: return VIDEO_FORCE_HEVC;
            case FORCE_H264: return VIDEO_NEVER_HEVC;
            case AUTO:
            default: return VIDEO_AUTO;
        }
    }

    /** Snapshot of the upstream streaming keys. */
    public static final class CurrentSettings {
        public final String resolution;
        public final String fps;
        public final int bitrateKbps;
        public final String videoFormat;
        public final String videoFormatRaw;
        public final String profile;
        public final PreferenceConfiguration raw;

        public CurrentSettings(String resolution, String fps,
                               int bitrateKbps, String videoFormat,
                               String videoFormatRaw,
                               String profile,
                               PreferenceConfiguration raw) {
            this.resolution = resolution;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.videoFormat = videoFormat;
            this.videoFormatRaw = videoFormatRaw;
            this.profile = profile;
            this.raw = raw;
        }
    }
}