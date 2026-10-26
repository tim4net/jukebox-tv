package net.tfour.jukebox.room;

import com.google.android.exoplayer2.Player;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.misc.JukeboxControlDispatcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build 2469, gazebo c4: KEYCODE_MEDIA_PLAY_PAUSE reached the player through
 * the media session (PlaybackState state=2) and paused the room's song. The
 * media session's {@link com.google.android.exoplayer2.ControlDispatcher} must
 * swallow play/pause (and any seek) while a room song is active — logged —
 * and dispatch unchanged without one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxControlDispatcherTest {
    private RecordingPlayer player;
    private JukeboxControlDispatcher dispatcher;

    /** Records every playWhenReady/seek dispatched at the fake player. */
    static class RecordingPlayer {
        final List<Boolean> playWhenReadyCalls = new ArrayList<>();
        final List<long[]> seekCalls = new ArrayList<>();
        int repeatModeCalls;
        int shuffleCalls;
        int stopCalls;

        Player proxy() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[]{Player.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("setPlayWhenReady".equals(name)) {
                                playWhenReadyCalls.add((Boolean) args[0]);
                            } else if ("seekTo".equals(name)) {
                                seekCalls.add(new long[]{(Integer) args[0], (Long) args[1]});
                            } else if ("setRepeatMode".equals(name)) {
                                repeatModeCalls++;
                            } else if ("setShuffleModeEnabled".equals(name)) {
                                shuffleCalls++;
                            } else if ("stop".equals(name)) {
                                stopCalls++;
                            }
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                        }
                    });
        }
    }

    @Before
    public void setUp() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        ShadowLog.clear();
        player = new RecordingPlayer();
        dispatcher = new JukeboxControlDispatcher();
    }

    @After
    public void tearDown() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
    }

    private static boolean swallowed(String what) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ("JukeboxKeys".equals(item.tag) && item.msg != null && item.msg.contains(what)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void mediaSessionPauseIsSwallowedWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        boolean dispatched = dispatcher.dispatchSetPlayWhenReady(player.proxy(), false);

        assertFalse("the media-session pause must be suppressed", dispatched);
        assertTrue("the player must never see the pause: " + player.playWhenReadyCalls,
                player.playWhenReadyCalls.isEmpty());
        assertTrue("the swallow must be logged", swallowed("swallowed media-session pause"));
    }

    @Test
    public void mediaSessionPlayIsSwallowedWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertFalse(dispatcher.dispatchSetPlayWhenReady(player.proxy(), true));
        assertTrue(player.playWhenReadyCalls.isEmpty());
        assertTrue(swallowed("swallowed media-session play"));
    }

    @Test
    public void mediaSessionPlayPauseDispatchesWithoutARoomSong() {
        assertTrue(dispatcher.dispatchSetPlayWhenReady(player.proxy(), false));
        assertEquals("the player must see the pause without a room song",
                java.util.Arrays.asList(Boolean.FALSE), player.playWhenReadyCalls);
        assertFalse("nothing may be logged without a room song", swallowed("swallowed"));
    }

    @Test
    public void mediaSessionSeekIsSwallowedWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertFalse(dispatcher.dispatchSeekTo(player.proxy(), 0, 30_000L));
        assertTrue("the room's song must never be seeked: " + player.seekCalls,
                player.seekCalls.isEmpty());
        assertTrue(swallowed("swallowed media-session seek"));
    }

    @Test
    public void mediaSessionSeekDispatchesWithoutARoomSong() {
        assertTrue(dispatcher.dispatchSeekTo(player.proxy(), 0, 30_000L));
        assertEquals(1, player.seekCalls.size());
    }

    @Test
    public void unrelatedMediaSessionControlsStillDispatchWithARoomSong() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertTrue(dispatcher.dispatchSetRepeatMode(player.proxy(), Player.REPEAT_MODE_ALL));
        assertTrue(dispatcher.dispatchSetShuffleModeEnabled(player.proxy(), true));
        assertTrue(dispatcher.dispatchStop(player.proxy(), true));
        assertEquals(1, player.repeatModeCalls);
        assertEquals(1, player.shuffleCalls);
        assertEquals(1, player.stopCalls);
    }
}