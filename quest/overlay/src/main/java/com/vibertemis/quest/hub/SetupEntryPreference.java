package com.vibertemis.quest.hub;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.R;

/**
 * Preference that opens the offline {@link SetupActivity} when tapped.
 * Used as the entry point in {@code preferences.xml} so setup is
 * reachable from Streaming settings as well as the hub.
 */
public class SetupEntryPreference extends Preference {
    public SetupEntryPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SetupEntryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public SetupEntryPreference(Context context, AttributeSet attrs,
                                int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onClick() {
        Context ctx = getContext();
        Intent i = new Intent(ctx, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}