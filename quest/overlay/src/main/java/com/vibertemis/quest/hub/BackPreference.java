package com.vibertemis.quest.hub;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.AttributeSet;

/**
 * Top row in every nested {@link PreferenceScreen}. {@link
 * android.preference.PreferenceScreen} is final so we cannot subclass
 * it; this row is inserted as the first child instead. Tapping it
 * dismisses the nested dialog (the system back gesture already works
 * the same way, but Quest controllers do not always have a system
 * back button accessible).
 */
public class BackPreference extends Preference {
    public BackPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BackPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public BackPreference(Context context, AttributeSet attrs,
                          int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onClick() {
        Preference p = this;
        while (p != null && !(p instanceof PreferenceScreen)) {
            p = p.getParent();
        }
        if (p == null) return;
        PreferenceScreen screen = (PreferenceScreen) p;
        if (screen.getDialog() != null) {
            screen.getDialog().dismiss();
        }
    }
}
