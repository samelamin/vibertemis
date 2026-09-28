package com.vibertemis.quest.hub;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.preferences.SeekBarPreference;

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
 * <p>Opening the preference screen NEVER applies a preset; only the
 * user-driven click path does. The click shows a confirmation dialog
 * that lists the exact replacement values, and an unsupported-preset
 * dialog if the device cannot host the preset's resolution or refresh
 * rate.
 *
 * <p>After a successful Apply, the parent preference fragment's cached
 * ListPreference and SeekBarPreference values are refreshed in place so
 * the user sees the new state without dismissing the nested screen.
 */
public class ProfileApplyPreference extends Preference {
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
        if (HubPrefs.PROFILE_CUSTOM.equals(profile)) {
            // Custom is a no-op against upstream keys; just record the
            // selection in hub prefs and toast.
            ProfileApplier.applyProfile(getContext(), profile);
            Toast.makeText(getContext(),
                    R.string.profile_apply_toast_custom,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String res = ProfileApplier.defaultResolutionFor(profile);
        String fps = ProfileApplier.defaultFpsFor(profile);
        int bitrate = ProfileApplier.defaultBitrateKbpsFor(profile);

        final Context ctx = getContext();
        // Confirm before writing.
        String confirmMsg = ctx.getString(
                R.string.profile_apply_confirm_msg,
                formatReplacement(profile, res, fps, bitrate));
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.profile_apply_confirm_title)
                .setMessage(confirmMsg)
                .setPositiveButton(
                        R.string.profile_apply_confirm_replace,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                doApply(profile);
                            }
                        })
                .setNegativeButton(
                        R.string.profile_apply_confirm_cancel,
                        null)
                .show();
    }

    private void doApply(final String name) {
        final Context ctx = getContext();
        // Snapshot the inflated upstream entry lists at confirm time so
        // we apply only values the user can actually see in the
        // streaming settings list. This is per-instance — no static /
        // global provider is retained between calls.
        ProfileApplier.AllowedValues allowed = snapshotAllowedValues();
        ProfileApplier.applyProfile(ctx, name, allowed,
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result result,
                                         String profile, String res, String fps,
                                         int bitrateKbps) {
                        switch (result) {
                            case APPLIED:
                                String label = profileLabel(profile);
                                Toast.makeText(ctx,
                                        ctx.getString(
                                                R.string.profile_apply_toast_replaced,
                                                label),
                                        Toast.LENGTH_SHORT).show();
                                refreshCachedWidgets();
                                break;
                            case APPLIED_CUSTOM:
                                Toast.makeText(ctx,
                                        R.string.profile_apply_toast_custom,
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case UNSUPPORTED:
                                showUnsupportedDialog(profile, res, fps);
                                break;
                            case UNKNOWN:
                            default:
                                // No writes, no toast.
                                break;
                        }
                    }
                });
    }

    /**
     * Walk up the preference tree to find the inflated
     * {@code list_resolution} / {@code list_fps} ListPreferences and
     * snapshot their current entry lists. Returning null here means
     * "I could not find the inflated entries" — ProfileApplier treats
     * a null snapshot as a fail-closed guard and surfaces UNSUPPORTED.
     */
    private ProfileApplier.AllowedValues snapshotAllowedValues() {
        ListPreference resPref = findListPreference("list_resolution");
        ListPreference fpsPref = findListPreference("list_fps");
        if (resPref == null || fpsPref == null) return null;
        CharSequence[] resValues = resPref.getEntryValues();
        CharSequence[] fpsValues = fpsPref.getEntryValues();
        if (resValues == null || fpsValues == null) return null;
        java.util.Set<String> res = new java.util.HashSet<>();
        for (CharSequence v : resValues) res.add(v.toString());
        java.util.Set<String> fps = new java.util.HashSet<>();
        for (CharSequence v : fpsValues) fps.add(v.toString());
        return new ProfileApplier.AllowedValues(res, fps);
    }

    private ListPreference findListPreference(String key) {
        Preference p = findRootPreference(this, key);
        return (p instanceof ListPreference) ? (ListPreference) p : null;
    }

    private void showUnsupportedDialog(String name, String res, String fps) {
        String label = profileLabel(name);
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.profile_apply_unsupported_title)
                .setMessage(getContext().getString(
                        R.string.profile_apply_unsupported_msg,
                        label, res, fps))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        Toast.makeText(getContext(),
                R.string.profile_apply_toast_unsupported,
                Toast.LENGTH_LONG).show();
    }

    private void refreshCachedWidgets() {
        // Find the parent PreferenceFragment and refresh its cached
        // ListPreference and SeekBarPreference widgets from the
        // SharedPreferences. We can't reach the fragment directly from
        // here, so we use the PreferenceManager to re-read each
        // preference. The standard onSharedPreferenceChanged hook is
        // installed by the framework when the fragment inflates
        // ListPreferences and SeekBarPreferences; refreshing by
        // re-querying the keys triggers that callback.
        Context ctx = getContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);

        refreshListPreference("list_resolution",
                p.getString(ProfileApplier.K_RES, ProfileApplier.RES_1440P));
        refreshListPreference("list_fps",
                p.getString(ProfileApplier.K_FPS, ProfileApplier.FPS_72));
        refreshListPreference("video_format",
                p.getString(ProfileApplier.K_VIDEO_FORMAT, ProfileApplier.VIDEO_AUTO));
        refreshSeekBarPreference("seekbar_bitrate_kbps",
                p.getInt(ProfileApplier.K_BITRATE_KBPS, ProfileApplier.BITRATE_HOME_KBPS));
    }

    private void refreshListPreference(String key, String value) {
        ListPreference pref = findListPreference(key);
        if (pref != null) {
            pref.setValue(value);
        }
    }

    private void refreshSeekBarPreference(String key, int value) {
        Preference pref = findRootPreference(this, key);
        if (pref instanceof SeekBarPreference) {
            ((SeekBarPreference) pref).setProgress(value);
        }
    }

    /** Walk up the preference tree until we find one with the given key. */
    private static Preference findRootPreference(Preference self, String key) {
        Preference p = self;
        while (p != null) {
            Preference parent = p.getParent();
            if (parent == null) {
                return null;
            }
            Preference found = findInTree(parent, key);
            if (found != null) return found;
            p = parent;
        }
        return null;
    }

    private static Preference findInTree(Preference root, String key) {
        if (key == null) return null;
        if (key.equals(root.getKey())) return root;
        if (root instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) root;
            int count = group.getPreferenceCount();
            for (int i = 0; i < count; i++) {
                Preference child = group.getPreference(i);
                Preference found = findInTree(child, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String formatReplacement(String profile, String res,
                                            String fps, int bitrateKbps) {
        return res + " @ " + fps + " fps, "
                + ProfileApplier.formatMbps(bitrateKbps)
                + " Mbps, codec " + ProfileApplier.CODEC_LABEL_AUTO;
    }

    /**
     * Map a profile name to the user-facing label shown in the
     * apply-confirmation and replaced-toast. Falls back to the raw
     * name for unknown profiles.
     */
    private String profileLabel(String name) {
        Context ctx = getContext();
        if (HubPrefs.PROFILE_HOME.equals(name)) {
            return ctx.getString(R.string.profile_label_home);
        }
        if (HubPrefs.PROFILE_TRAVEL.equals(name)) {
            return ctx.getString(R.string.profile_label_travel);
        }
        if (HubPrefs.PROFILE_HQ.equals(name)) {
            return ctx.getString(R.string.profile_label_hq);
        }
        if (HubPrefs.PROFILE_CUSTOM.equals(name)) {
            return ctx.getString(R.string.profile_label_custom);
        }
        return name;
    }
}