package com.vibertemis.quest.hub;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.R;

/**
 * Read-only preference that shows the current upstream streaming
 * settings (resolution, fps, Mbps, codec) and the matched preset name
 * (or "Custom" when the live values do not match a preset).
 *
 * <p>The preference is inflated at the top of {@code preferences.xml}
 * so the user can see what they are currently streaming at without
 * scrolling through the upstream settings list. The summary is
 * computed from the same {@link ProfileApplier#currentSettings}
 * reader the hub status line uses, so there is one source of truth.
 */
public class TopStatusPreference extends Preference {
    public TopStatusPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        refreshSummary();
    }

    public TopStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        refreshSummary();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public TopStatusPreference(Context context, AttributeSet attrs,
                               int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        refreshSummary();
    }

    /**
     * Recompute the summary from the live upstream prefs. Call after
     * any change to the streaming settings (preset Apply, manual edit)
     * so the displayed value tracks reality.
     */
    public void refreshSummary() {
        try {
            ProfileApplier.CurrentSettings s = ProfileApplier.currentSettings(getContext());
            String formatted = getContext().getString(
                    R.string.top_config_status_value,
                    s.resolution, s.fps,
                    ProfileApplier.formatMbps(s.bitrateKbps),
                    s.videoFormat);
            if (s.profile != null) {
                String profileLabel;
                if (HubPrefs.PROFILE_HOME.equals(s.profile)) {
                    profileLabel = getContext().getString(R.string.profile_label_home);
                } else if (HubPrefs.PROFILE_TRAVEL.equals(s.profile)) {
                    profileLabel = getContext().getString(R.string.profile_label_travel);
                } else if (HubPrefs.PROFILE_HQ.equals(s.profile)) {
                    profileLabel = getContext().getString(R.string.profile_label_hq);
                } else {
                    profileLabel = s.profile;
                }
                formatted = formatted + "\n"
                        + getContext().getString(R.string.top_config_status_matched_profile,
                                profileLabel);
            }
            setSummary(formatted);
        } catch (Throwable t) {
            setSummary(R.string.top_config_status_empty);
        }
    }
}