package com.vibertemis.quest.hub;

import android.content.Intent;

import com.limelight.Game;
import com.limelight.GameXR;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Opening the app from the headset's library while a Screen stream is still
 * up behind the Quest home must go back to that stream instead of showing the
 * hub, so the user does not have to reconnect to the PC.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class HubLiveStreamReturnTest {

    /** Hub whose view of the running stream the test decides. */
    public static class LiveStreamHub extends MainHubActivityTest.FakeNsdHub {
        static volatile Class<? extends Game> live;
        static volatile int liveTask = -1;

        @Override protected Class<? extends Game> liveStreamActivity() { return live; }
        @Override protected int liveStreamTaskId() { return liveTask; }
    }

    @org.junit.Before
    public void quietNsd() {
        MainHubActivityTest.FakeNsdHub.sharedFactory = () -> new MainHubActivityTest.EmptyDriver();
    }

    @After
    public void reset() {
        MainHubActivityTest.FakeNsdHub.sharedFactory = null;
        LiveStreamHub.live = null;
        LiveStreamHub.liveTask = -1;
    }

    private static Intent launcherIntent() {
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LiveStreamHub.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        return i;
    }

    @Test
    public void launcherStartWithLiveImmersiveStreamReturnsToIt() {
        LiveStreamHub.live = GameXR.class;
        LiveStreamHub.liveTask = 4242;

        ActivityController<LiveStreamHub> c = Robolectric
                .buildActivity(LiveStreamHub.class, launcherIntent()).create().start().resume();

        ShadowActivity shadow = Shadows.shadowOf(c.get());
        Intent started = shadow.getNextStartedActivity();
        assertNotNull("hub must start the running stream", started);
        assertEquals(GameXR.class.getName(), started.getComponent().getClassName());
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0);
        assertTrue(started.hasCategory("com.oculus.intent.category.VR"));
        // No extras: this is a way back to the running instance, not a new launch
        assertNull(started.getExtras());
        assertTrue("hub must get out of the way", c.get().isFinishing());
    }

    @Test
    public void launcherStartWithLiveFlatStreamReturnsWithoutVrCategory() {
        LiveStreamHub.live = Game.class;
        LiveStreamHub.liveTask = 4242;

        ActivityController<LiveStreamHub> c = Robolectric
                .buildActivity(LiveStreamHub.class, launcherIntent()).create().start().resume();

        Intent started = Shadows.shadowOf(c.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Game.class.getName(), started.getComponent().getClassName());
        assertFalse(started.hasCategory("com.oculus.intent.category.VR"));
        assertTrue(c.get().isFinishing());
    }

    @Test
    public void launcherStartWithoutLiveStreamShowsHub() {
        ActivityController<LiveStreamHub> c = Robolectric
                .buildActivity(LiveStreamHub.class, launcherIntent()).create().start().resume();

        assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
        assertFalse(c.get().isFinishing());
    }

    @Test
    public void nonLauncherIntentIsLeftToTheHub() {
        LiveStreamHub.live = GameXR.class;
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LiveStreamHub.class);

        ActivityController<LiveStreamHub> c = Robolectric
                .buildActivity(LiveStreamHub.class, i).create().start().resume();

        assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
        assertFalse(c.get().isFinishing());
    }
}
