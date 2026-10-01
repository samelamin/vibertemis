package com.vibertemis.quest.hub;

import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Drives the real {@link Game#onVrPointerMove} and {@link Game#onVrButton}
 * with the host side replaced by a recording {@link NvConnection}. Every
 * pixel the pointer reaches has to go out as an absolute position, button
 * held or not, so a drag and a text selection behave like a real mouse.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowMoonBridge.class, FlatPointerTest.RecordingConnection.class})
public class FlatPointerTest {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    private Game game;

    @Before
    public void setUp() {
        RecordingConnection.reset();

        // onCreate() starts decoders, dialogs and a real network connection,
        // none of which the pointer path touches, so the activity is built
        // without create() and given only the state the pointer methods read.
        game = Robolectric.buildActivity(Game.class).get();

        PreferenceConfiguration prefConfig = new PreferenceConfiguration();
        prefConfig.width = WIDTH;
        prefConfig.height = HEIGHT;

        ReflectionHelpers.setField(game, "prefConfig", prefConfig);
        ReflectionHelpers.setField(game, "conn", Shadow.newInstanceOf(NvConnection.class));
        ReflectionHelpers.setField(game, "connected", true);
    }

    /** Normalized coordinate landing on the center of the given host pixel. */
    private static float u(int pixel) {
        return (pixel + 0.5f) / WIDTH;
    }

    @Test
    public void everyPixelReachedIsSentImmediatelyInOrder() {
        game.onVrPointerMove(0.5f, 0.5f);
        game.onVrButton(0, true);
        game.onVrPointerMove(u(961), 0.5f);
        game.onVrButton(0, false);
        game.onVrPointerMove(u(962), 0.5f);

        // A deadzone here would swallow one of the two one-pixel moves while
        // the button is held, so nothing on the clock is advanced here.
        String down = "btn down " + MouseButtonPacket.BUTTON_LEFT;
        String up = "btn up " + MouseButtonPacket.BUTTON_LEFT;
        assertEquals("[pos 960,540/1920x1080, " + down
                        + ", pos 961,540/1920x1080, " + up
                        + ", pos 962,540/1920x1080]",
                RecordingConnection.events.toString());

        List<int[]> positions = RecordingConnection.positions;
        assertEquals(3, positions.size());
        assertEquals(960, positions.get(0)[0]);
        assertEquals(540, positions.get(0)[1]);
        assertEquals(961, positions.get(1)[0]);
        assertEquals(540, positions.get(1)[1]);
        assertEquals(962, positions.get(2)[0]);
        assertEquals(540, positions.get(2)[1]);
        assertEquals(WIDTH, positions.get(2)[2]);
        assertEquals(HEIGHT, positions.get(2)[3]);
    }

    @Test
    public void rejectsBadCoordsClampsEdgesDedupesAndIgnoresDisconnected() {
        game.onVrPointerMove(Float.NaN, 0.5f);
        game.onVrPointerMove(0.5f, Float.NaN);
        game.onVrPointerMove(Float.POSITIVE_INFINITY, 0.5f);
        game.onVrPointerMove(0.5f, Float.NEGATIVE_INFINITY);
        assertEquals(0, RecordingConnection.positions.size());

        game.onVrPointerMove(-0.25f, -0.25f);
        game.onVrPointerMove(1.5f, 1.5f);
        assertEquals(2, RecordingConnection.positions.size());
        assertEquals(0, RecordingConnection.positions.get(0)[0]);
        assertEquals(0, RecordingConnection.positions.get(0)[1]);
        assertEquals(1919, RecordingConnection.positions.get(1)[0]);
        assertEquals(1079, RecordingConnection.positions.get(1)[1]);

        game.onVrPointerMove(0.25f, 0.25f);
        game.onVrPointerMove(0.25f, 0.25f);
        assertEquals(3, RecordingConnection.positions.size());
        assertEquals(480, RecordingConnection.positions.get(2)[0]);
        assertEquals(270, RecordingConnection.positions.get(2)[1]);

        ReflectionHelpers.setField(game, "connected", false);
        game.onVrPointerMove(0.75f, 0.75f);
        game.onVrButton(0, true);

        // The last two calls added nothing at all: no position, no button.
        assertEquals("[pos 0,0/1920x1080, pos 1919,1079/1920x1080, pos 480,270/1920x1080]",
                RecordingConnection.events.toString());
    }

    @Implements(value = NvConnection.class, isInAndroidSdk = false)
    public static class RecordingConnection {

        static final List<int[]> positions = new ArrayList<>();
        static final List<String> events = new ArrayList<>();

        static void reset() {
            positions.clear();
            events.clear();
        }

        @Implementation
        public void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight) {
            positions.add(new int[] {x, y, referenceWidth, referenceHeight});
            events.add("pos " + x + "," + y + "/" + referenceWidth + "x" + referenceHeight);
        }

        @Implementation
        public void sendMouseButtonDown(byte mouseButton) {
            events.add("btn down " + mouseButton);
        }

        @Implementation
        public void sendMouseButtonUp(byte mouseButton) {
            events.add("btn up " + mouseButton);
        }
    }
}
