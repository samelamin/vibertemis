package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Hub-side preference store. Holds the *hub* state (profile selection,
 * companion version reminder flag) — NOT the Moonlight upstream
 * preferences. The latter are written via the existing upstream
 * PreferenceConfiguration keys (list_resolution, list_fps,
 * seekbar_bitrate_kbps, video_format).
 *
 * <p>This store is intentionally namespaced under "vibertemis_quest_hub_"
 * so it can never accidentally collide with an upstream key.
 *
 * <p>Reading a preference never writes one. Applying a profile is an
 * explicit user action routed through
 * {@link ProfileApplier#applyProfile(Context, String, ProfileApplier.ApplyCallback)}
 * with a confirmation step.
 */
public final class HubPrefs {
    private static final String FILE = "vibertemis_quest_hub";
    private static final String K_LAST_PROFILE = "vibertemis_quest_hub_last_profile";
    private static final String K_COMPANION_REMINDER_DISMISSED =
            "vibertemis_quest_hub_companion_reminder_dismissed";

    /** Profile identifiers. The strings are stable on disk. */
    public static final String PROFILE_HOME = "home";
    public static final String PROFILE_TRAVEL = "travel";
    public static final String PROFILE_HQ = "hq";
    public static final String PROFILE_CUSTOM = "custom";

    private final SharedPreferences sp;

    public HubPrefs(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getLastProfile() {
        return sp.getString(K_LAST_PROFILE, PROFILE_HOME);
    }

    public void rememberProfile(String name) {
        sp.edit().putString(K_LAST_PROFILE, name).apply();
    }

    public boolean isCompanionReminderDismissed() {
        return sp.getBoolean(K_COMPANION_REMINDER_DISMISSED, false);
    }

    public void dismissCompanionReminder() {
        sp.edit().putBoolean(K_COMPANION_REMINDER_DISMISSED, true).apply();
    }

    /**
     * Reset all hub-side state. Test-only and never wired to user-facing
     * UI. Production code never calls this.
     */
    public void resetHubState() {
        sp.edit().clear().apply();
    }
}
