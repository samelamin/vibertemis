package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Capability gating for the two launch modes.
 *
 * <p>PCVR mode is only advertised on real headsets. The single check we
 * perform is the optional system feature
 * {@code FEATURE_VR_HEADTRACKING} that the upstream Moonlight XR v0.3
 * manifest already declares. There is no upstream
 * {@code PreferenceConfiguration.isHeadset(...)} helper in v0.3 — this
 * class is the canonical place.
 *
 * <p>The Meta Horizon OS OpenXR runtime broker
 * ({@code com.oculus.vrshell} / {@code OpenXRRuntimeService}) resolves
 * the system runtime on first use; the hub never probes it directly.
 *
 * <p>The PCVR engine (ALVR v20.14.1) ships embedded inside this APK as
 * {@code lib/arm64-v8a/libalvr_client_openxr.so}; no separate headset
 * ALVR client APK is required. Reachability of the PC ALVR server is the
 * host's concern, not ours.
 */
public final class VrCapabilities {
    private static final String TAG = "VrCapabilities";

    private VrCapabilities() {}

    /**
     * True if the device declares VR headtracking (Quest family, Pico, etc.).
     * On phones and TVs this is false and the PCVR row stays hidden.
     */
    public static boolean isHeadset(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        boolean has = pm.hasSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING);
        Log.d(TAG, "FEATURE_VR_HEADTRACKING=" + has);
        return has;
    }

    /**
     * Same check, kept under the older name for any caller that still
     * uses {@code canShowSteamVr}.
     */
    public static boolean canShowSteamVr(Context ctx) {
        return isHeadset(ctx);
    }
}
