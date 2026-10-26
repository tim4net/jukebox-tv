package net.tfour.jukebox.room;

import android.view.KeyEvent;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build 2469, gazebo c4: while a jukebox room song is active the play/pause
 * media keys (PLAY_PAUSE/PLAY/PAUSE — including the media session route) and
 * the seek/skip keys (DPAD left/right, FF/REW, +/-) must be swallowed with a
 * "JukeboxKeys: swallowed <key>" log so the TV keeps playing the room's song.
 * Without an active room song nothing is swallowed or logged: normal SmartTube
 * behavior unchanged (other flavors never even set the flags).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxModeKeySwallowTest {
    @Before
    public void setUp() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        ShadowLog.clear();
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

    // ---- play/pause media keys ----

    @Test
    public void playPauseKeysAreSwallowedAndLoggedWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        for (int keyCode : new int[]{KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE}) {
            ShadowLog.clear();
            assertTrue("the play/pause key " + keyCode + " must be swallowed",
                    JukeboxMode.swallowPlayPauseKey(keyCode));
            assertTrue("the swallow must be logged",
                    swallowed("swallowed " + KeyEvent.keyCodeToString(keyCode)));
        }
    }

    @Test
    public void playPauseKeysAreNotSwallowedWithoutARoomSong() {
        ShadowLog.clear();
        assertFalse("no room song: nothing may be swallowed",
                JukeboxMode.swallowPlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertFalse("no room song: nothing may be logged", swallowed("swallowed"));
    }

    @Test
    public void nonMediaKeysAreNeverSwallowedAsPlayPause() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);
        assertFalse("DPAD_CENTER is not a play/pause media key",
                JukeboxMode.swallowPlayPauseKey(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse("DPAD_LEFT is not a play/pause media key",
                JukeboxMode.swallowPlayPauseKey(KeyEvent.KEYCODE_DPAD_LEFT));
    }

    // ---- seek/skip keys ----

    @Test
    public void seekKeysAreSwallowedAndLoggedWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        for (int keyCode : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS}) {
            ShadowLog.clear();
            assertTrue("the seek key " + keyCode + " must be swallowed",
                    JukeboxMode.swallowSeekKey(keyCode));
            assertTrue("the swallow must be logged",
                    swallowed("swallowed " + KeyEvent.keyCodeToString(keyCode)));
        }
    }

    @Test
    public void seekKeysAreNotSwallowedWithoutARoomSong() {
        ShadowLog.clear();
        assertFalse("no room song: nothing may be swallowed",
                JukeboxMode.swallowSeekKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertFalse("no room song: nothing may be logged", swallowed("swallowed"));
    }

    // ---- non-keycode call sites (glue actions, media session) ----

    @Test
    public void glueAndMediaSessionPlayPauseSeekAreSwallowedWithRoomSong() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertTrue(JukeboxMode.swallowPlayPause("glue pause"));
        assertTrue(swallowed("swallowed glue pause"));
        assertTrue(JukeboxMode.swallowSeek("rewind"));
        assertTrue(swallowed("swallowed rewind"));
    }

    @Test
    public void glueAndMediaSessionPlayPauseSeekPassThroughWithoutRoomSong() {
        ShadowLog.clear();
        assertFalse(JukeboxMode.swallowPlayPause("glue pause"));
        assertFalse(JukeboxMode.swallowSeek("rewind"));
        assertFalse("nothing may be logged without a room song", swallowed("swallowed"));
    }
}