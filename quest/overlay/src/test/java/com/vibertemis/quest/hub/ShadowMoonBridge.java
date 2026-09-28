package com.vibertemis.quest.hub;

import com.limelight.nvstream.jni.MoonBridge;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Robolectric shadow for {@link MoonBridge}.
 *
 * <p>The upstream {@link MoonBridge} static initializer calls
 * {@code System.loadLibrary("moonlight-core")} and {@code init()} — neither of
 * which is available in a JVM unit test. Robolectric invokes this shadow's
 * {@code __staticInitializer__} in place of the original static block, which
 * lets the real {@code PreferenceConfiguration.readPreferences} code path
 * run end-to-end.
 *
 * <p><b>Boundary:</b> the three {@code AUDIO_CONFIGURATION_*} fields and the
 * audio rendering pipeline remain {@code null}. The video preference tests
 * ({@link ProfilePrefsTest}) never exercise audio config; the real
 * assertions on width/height/fps/bitrate/videoFormat still hold because
 * those reads happen before the audio branch returns and the audio field
 * stays null until something writes to it.
 *
 * <p>This is a TEST-ONLY shadow. {@code MoonBridge} itself is untouched and
 * production builds continue to link the real native library.
 */
@Implements(value = MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridge {

    @Implementation
    public static void __staticInitializer__() {
        // Intentionally empty: skip System.loadLibrary("moonlight-core") and
        // init(). The video preference tests do not need native audio/video
        // rendering; they only need the audio config switch in
        // PreferenceConfiguration.readPreferences to compile and run.
    }
}
