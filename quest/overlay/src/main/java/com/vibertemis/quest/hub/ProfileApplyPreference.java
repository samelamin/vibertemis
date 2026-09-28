package com.vibertemis.quest.hub;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * Preference that applies a named preset when tapped.
 *
 * <p>Reads the profile name from the {@code profile} attribute, e.g.
 * <pre>{@code
 * <com.vibertemis.quest.hub.ProfileApplyPreference
 *     android:key="vq_profile_home_apply"
 *     android:title="@string/profile_apply_btn"
 *     android:summary="@string/profile_home_summary"
 *     profile="home"/>
 * }</pre>
 *
 * <p>Applying a preset invokes
 * {@link ProfileApplier#applyProfile(Context, String)}, which writes the
 * upstream Moonlight preference keys through
 * {@code PreferenceManager.getDefaultSharedPreferences}. Opening the
 * preference screen NEVER applies a preset; only tapping this preference
 * does. Repeated taps mark the summary with "[Applied]" exactly once.
 */
public class ProfileApplyPreference extends Preference {
    private static final String APPLIED_PREFIX = "[Applied] ";

    private String profile;

    public ProfileApplyPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        readProfile(attrs);
    }

    public ProfileApplyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        readProfile(attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public ProfileApplyPreference(Context context, AttributeSet attrs,
                                  int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        readProfile(attrs);
    }

    private void readProfile(AttributeSet attrs) {
        if (attrs == null) return;
        profile = attrs.getAttributeValue(null, "profile");
    }

    @Override
    public void onClick() {
        if (profile == null) return;
        ProfileApplier.applyProfile(getContext(), profile);
        CharSequence summary = getSummary();
        if (summary == null || summary.toString().startsWith(APPLIED_PREFIX)) {
            return;
        }
        setSummary(APPLIED_PREFIX + summary);
    }
}
