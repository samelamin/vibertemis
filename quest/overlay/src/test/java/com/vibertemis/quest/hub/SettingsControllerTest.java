package com.vibertemis.quest.hub;

import android.content.Context;
import android.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-instance {@link SettingsController}. The controller
 * owns the cached "current streaming settings" snapshot for one hub
 * owner and listens to upstream preference changes so the snapshot
 * tracks manual edits and preset applies without a static / global
 * reference.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class SettingsControllerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    @Test
    public void constructor_readsFreshSnapshotFromUpstream() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 17500)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "auto")
                .commit();

        SettingsController c = new SettingsController(ctx);
        ProfileApplier.CurrentSettings s = c.current();
        assertEquals("1920x1080", s.resolution);
        assertEquals("60", s.fps);
        assertEquals(17500, s.bitrateKbps);
        assertEquals("Auto", s.videoFormat);
    }

    @Test
    public void manualEdit_thenRefresh_picksUpNewValue() {
        SettingsController c = new SettingsController(ctx);
        assertEquals("2560x1440", c.current().resolution);
        assertEquals("90", c.current().fps);

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .putString(ProfileApplier.K_FPS, "60")
                .commit();

        c.refresh();
        assertEquals("1920x1080", c.current().resolution);
        assertEquals("60", c.current().fps);
    }

    @Test
    public void registerThenListenerFiresOnRelevantKey_change() {
        SettingsController c = new SettingsController(ctx);
        c.register();
        assertTrue(c.isRegistered());

        // Apply a preset via the upstream preference keys, the way
        // ProfileApplier does after a confirmed Apply tap.
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "3840x2160")
                .putString(ProfileApplier.K_FPS, "60")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 80000)
                .putString(ProfileApplier.K_VIDEO_FORMAT, "auto")
                .commit();

        assertEquals("3840x2160", c.current().resolution);
        assertEquals(80000, c.current().bitrateKbps);
    }

    @Test
    public void unregisterStopsListener() {
        SettingsController c = new SettingsController(ctx);
        c.register();
        c.unregister();
        assertFalse(c.isRegistered());

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .commit();

        // Cached snapshot is unchanged; an explicit refresh picks up
        // the new value.
        assertEquals("2560x1440", c.current().resolution);
        c.refresh();
        assertEquals("1920x1080", c.current().resolution);
    }

    @Test
    public void idempotent_registerAndUnregister() {
        SettingsController c = new SettingsController(ctx);
        c.register();
        c.register();
        assertTrue(c.isRegistered());
        c.unregister();
        c.unregister();
        assertFalse(c.isRegistered());
    }

    @Test
    public void noStaticRetentionBetweenInstances() {
        SettingsController a = new SettingsController(ctx);
        a.register();
        a.refresh();
        // Drop the reference and construct a second controller from
        // scratch — it must not see any leaked snapshot from the first.
        a = null;

        SettingsController b = new SettingsController(ctx);
        b.refresh();
        assertNotNull(b.current());
        assertEquals(ProfileApplier.CODEC_LABEL_AUTO, b.current().videoFormat);
    }

    @Test
    public void observerFiresOnRelevantKeyChange_withSnapshot() {
        final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<ProfileApplier.CurrentSettings>
                last = new java.util.concurrent.atomic.AtomicReference<>();
        SettingsController c = new SettingsController(ctx);
        c.addObserver(new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
                count.incrementAndGet();
                last.set(snapshot);
            }
        });
        c.register();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "3840x2160")
                .putInt(ProfileApplier.K_BITRATE_KBPS, 80000)
                .commit();
        assertEquals(2, count.get());
        assertNotNull(last.get());
        assertEquals("3840x2160", last.get().resolution);
        assertEquals(80000, last.get().bitrateKbps);
    }

    @Test
    public void observerIgnoresIrrelevantKeyChange() {
        final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();
        SettingsController c = new SettingsController(ctx);
        c.addObserver(new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
                count.incrementAndGet();
            }
        });
        c.register();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean("checkbox_enable_vr_mode", true)
                .commit();
        assertEquals("Irrelevant keys must not fire observers", 0, count.get());
    }

    @Test
    public void unregisterRetainsObservers() {
        // Pause / resume must keep observer references so the
        // fragment's SettingsController keeps the live UI in sync
        // after the first onResume.
        final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();
        SettingsController c = new SettingsController(ctx);
        c.addObserver(new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
                count.incrementAndGet();
            }
        });
        c.register();
        c.unregister();
        assertEquals("Observers must survive unregister", 1, c.observerCount());

        c.register();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(ProfileApplier.K_RES, "1920x1080")
                .commit();
        assertEquals("Re-registered observer must fire", 1, count.get());
    }

    @Test
    public void disposeDropsObservers() {
        SettingsController c = new SettingsController(ctx);
        c.addObserver(new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
            }
        });
        c.register();
        c.unregister();
        c.dispose();
        assertEquals(0, c.observerCount());
    }

    @Test
    public void addObserverIsIdempotent() {
        SettingsController.Observer o = new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
            }
        };
        SettingsController c = new SettingsController(ctx);
        c.addObserver(o);
        c.addObserver(o);
        c.addObserver(o);
        assertEquals(1, c.observerCount());
    }
}