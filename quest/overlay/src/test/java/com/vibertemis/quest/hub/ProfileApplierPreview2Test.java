package com.vibertemis.quest.hub;

import android.content.Context;
import android.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the new Preview 2 additions to {@link ProfileApplier}:
 * <ul>
 *   <li>HQ preset (4K @ 60 fps, 80 Mbps, auto codec)</li>
 *   <li>Per-instance capability gate driven by the inflated upstream
 *       ListPreference entry lists, not raw Display.Mode matching</li>
 *   <li>{@link ProfileApplier#currentSettings} reads through the real
 *       {@link PreferenceConfiguration#readPreferences} reader so the
 *       hub status line cannot fake 72 fps / 40 Mbps</li>
 *   <li>Decimal Mbps formatting preserves the 500 Kbps resolution of
 *       the upstream SeekBar step ({@code 17500 -> "17.5 Mbps"}, not
 *       integer {@code bitrateKbps / 1000 -> 17})</li>
 *   <li>Codec labels read {@code Auto} / {@code H.264} / {@code HEVC} /
 *       {@code AV1}, not the internal enum name</li>
 *   <li>Profile matching against the live upstream settings reports
 *       which preset the user is currently on (or {@code Custom} for
 *       any non-preset value combination)</li>
 * </ul>
 *
 * <p>Existing tests in {@link ProfilePrefsTest} continue to cover the
 * Home / Travel / Custom / Unknown paths and the upstream-key
 * round-trip.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class ProfileApplierPreview2Test {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    private static ProfileApplier.AllowedValues allowedWith(
            String[] res, String[] fps) {
        Set<String> r = new HashSet<>();
        for (String s : res) r.add(s);
        Set<String> f = new HashSet<>();
        for (String s : fps) f.add(s);
        return new ProfileApplier.AllowedValues(r, f);
    }

    @Test
    public void applyHq_writesUpstreamKeys_whenAllowedAllows() {
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HQ,
                allowedWith(
                        new String[]{"1920x1080", "2560x1440", "3840x2160"},
                        new String[]{"30", "60", "72", "90"}),
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.APPLIED, result.get());
        assertEquals("3840x2160",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(80000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("auto",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    @Test
    public void applyHq_unsupported_whenAllowedRejects4k() {
        // Simulates a phone whose inflated list drops 4K.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "auto")
                .commit();
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HQ,
                allowedWith(
                        new String[]{"1920x1080", "2560x1440"},
                        new String[]{"30", "60", "72", "90"}),
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.UNSUPPORTED, result.get());
        // No writes performed.
        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
    }

    @Test
    public void applyTravel_writes_whenAllowedAllows() {
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_TRAVEL,
                allowedWith(
                        new String[]{"1920x1080", "2560x1440"},
                        new String[]{"30", "60", "72"}),
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.APPLIED, result.get());
        assertEquals("1920x1080",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("60",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(17000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
    }

    @Test
    public void applyHome_unsupported_when120hzOnly() {
        // Quest panel that reports only 120 Hz modes — 72 not in list.
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HOME,
                allowedWith(
                        new String[]{"2560x1440"},
                        new String[]{"30", "60", "90", "120"}),
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.UNSUPPORTED, result.get());
    }

    @Test
    public void applyHome_unsupported_whenNullAllowed() {
        // Null allowed snapshot is treated as not-confirmed; the call
        // must fail-closed on the UI path.
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_HOME,
                null,
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.UNSUPPORTED, result.get());
        // No writes performed — the guard failed closed.
        assertEquals("", PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(ProfileApplier.K_RES, ""));
    }

    @Test
    public void applyPureTransaction_writesCanonicalValues() {
        // The pure-transaction path is the test-only entry point; it
        // skips the capability guard and writes the canonical values
        // directly so the upstream-key round-trip tests do not have to
        // inflate the upstream ListPreferences.
        ProfileApplier.applyPureTransaction(ctx, HubPrefs.PROFILE_HOME);
        assertEquals("2560x1440",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("72",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(40000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
        assertEquals("auto",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_VIDEO_FORMAT, ""));
    }

    @Test
    public void currentSettings_usesRealUpstreamReader() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "auto")
                .commit();
        ProfileApplier.CurrentSettings s = ProfileApplier.currentSettings(ctx);
        assertEquals("1920x1080", s.resolution);
        assertEquals("60", s.fps);
        assertEquals(17000, s.bitrateKbps);
        assertEquals(ProfileApplier.CODEC_LABEL_AUTO, s.videoFormat);
    }

    @Test
    public void currentSettings_freshPrefs_returnsRealDefaults_notHome() {
        // Critical: on fresh prefs, currentSettings must NOT fake
        // 1440p72/40000. The real PreferenceConfiguration default is
        // 1440p90 with formula-derived bitrate.
        ProfileApplier.CurrentSettings s = ProfileApplier.currentSettings(ctx);
        assertEquals("2560x1440", s.resolution);
        assertEquals("90", s.fps);
        assertTrue("Bitrate must be a real formula value, not a fake 40000; got "
                        + s.bitrateKbps,
                s.bitrateKbps != 40000 && s.bitrateKbps > 0);
        assertEquals(ProfileApplier.CODEC_LABEL_AUTO, s.videoFormat);
    }

    @Test
    public void currentSettings_codecLabel_isUserFacingNotInternal() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();
        ProfileApplier.CurrentSettings s = ProfileApplier.currentSettings(ctx);
        assertEquals("HEVC", s.videoFormat);
        assertEquals("forceh265", s.videoFormatRaw);
    }

    @Test
    public void currentSettings_codecLabels_coverAllUpstreamOptions() {
        assertEquals("Auto", ProfileApplier.codecLabel("auto"));
        assertEquals("HEVC", ProfileApplier.codecLabel("forceh265"));
        assertEquals("AV1", ProfileApplier.codecLabel("forceav1"));
        assertEquals("H.264", ProfileApplier.codecLabel("neverh265"));
    }

    @Test
    public void currentSettings_forceH264CodecEnum_mapsToCanonicalNeverh265() {
        // The enum FORCE_H264 lives in upstream PreferenceConfiguration
        // and corresponds to the stored preference value "neverh265".
        // ProfileApplier.codecString(FORCE_H264) must return that
        // canonical key so widget sync writes the same value the user
        // selected — never the bogus "forceh264" that no upstream
        // list ever held.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_VIDEO_FORMAT, "neverh265")
                .commit();
        ProfileApplier.CurrentSettings s = ProfileApplier.currentSettings(ctx);
        assertEquals("neverh265", s.videoFormatRaw);
        assertEquals("H.264", s.videoFormat);
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_H264,
                s.raw.videoFormat);
    }

    @Test
    public void formatMbps_preservesSeekBarStepResolution() {
        // The upstream SeekBar steps in 500 Kbps increments; the hub
        // status must show those as a one-decimal Mbps value. 17 Mbps
        // was the previous wrong integer answer for a 17000 Kbps value;
        // 17500 must read 17.5 Mbps.
        assertEquals("17.5", ProfileApplier.formatMbps(17500));
        assertEquals("17.0", ProfileApplier.formatMbps(17000));
        assertEquals("40.0", ProfileApplier.formatMbps(40000));
        assertEquals("80.0", ProfileApplier.formatMbps(80000));
        assertEquals("1.5", ProfileApplier.formatMbps(1500));
        assertEquals("0.5", ProfileApplier.formatMbps(500));
        assertEquals("0.0", ProfileApplier.formatMbps(0));
    }

    @Test
    public void matchProfile_reportsHomeTravelHqCustom() {
        assertEquals(HubPrefs.PROFILE_TRAVEL, ProfileApplier.matchProfile(
                "1920x1080", "60", 17000, "auto"));
        assertEquals(HubPrefs.PROFILE_HOME, ProfileApplier.matchProfile(
                "2560x1440", "72", 40000, "auto"));
        assertEquals(HubPrefs.PROFILE_HQ, ProfileApplier.matchProfile(
                "3840x2160", "60", 80000, "auto"));
        // Any deviation falls into Custom / null — there is no durable
        // "Applied" success tag.
        assertEquals(null, ProfileApplier.matchProfile(
                "1920x1080", "60", 17500, "auto"));
        assertEquals(null, ProfileApplier.matchProfile(
                "3840x2160", "60", 80000, "forceh265"));
        assertEquals(null, ProfileApplier.matchProfile(
                "1280x720", "30", 5000, "auto"));
    }

    @Test
    public void applyCustom_firesCustomResult_doesNotTouchUpstream() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 9000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "forceh265")
                .commit();
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, HubPrefs.PROFILE_CUSTOM, null,
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.APPLIED_CUSTOM, result.get());
        assertEquals("1280x720",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("30",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
        assertEquals(9000,
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getInt(ProfileApplier.K_BITRATE_KBPS, 0));
    }

    @Test
    public void applyUnknown_firesUnknownResult_doesNotTouchUpstream() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1280x720")
                .putString(ProfileApplier.K_FPS, "30")
                .commit();
        final AtomicReference<ProfileApplier.Result> result =
                new AtomicReference<>();
        ProfileApplier.applyProfile(ctx, "nonsense", null,
                new ProfileApplier.ApplyCallback() {
                    @Override
                    public void onResult(ProfileApplier.Result r, String p,
                                         String res, String fps, int br) {
                        result.set(r);
                    }
                });
        assertEquals(ProfileApplier.Result.UNKNOWN, result.get());
        assertEquals("1280x720",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_RES, ""));
        assertEquals("30",
                PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(ProfileApplier.K_FPS, ""));
    }

    @Test
    public void allowedValues_allows_onlyWhenBothListsContain() {
        ProfileApplier.AllowedValues v = allowedWith(
                new String[]{"1920x1080", "2560x1440"},
                new String[]{"30", "60", "72"});
        assertTrue(v.allows("2560x1440", "72"));
        assertNotEquals(true, v.allows("3840x2160", "60"));
        assertNotEquals(true, v.allows("2560x1440", "120"));
    }
}