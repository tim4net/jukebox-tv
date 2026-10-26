package net.tfour.jukebox.room;

import android.view.KeyEvent;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * While a jukebox room is active (even idle, no current song), SmartTube's own
 * auto-advance must be blocked so the player never plays a non-room video on its
 * own (room "play" is the only thing that opens the next song). While a room song
 * is active, seek keys (DPAD left/right seeking, FF/REW, +/-) must classify as
 * seek keys so the player consumes them without seeking the room's song.
 */
public class JukeboxModeTest {
    @After
    public void tearDown() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
    }

    @Test
    public void autoAdvanceBlockedWhileRoomActiveEvenWithoutSong() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(false);
        assertTrue(JukeboxMode.isAutoAdvanceBlocked());
        assertTrue(JukeboxMode.isRoomActive());
    }

    @Test
    public void autoAdvanceBlockedWhileSongSet() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(true);
        assertTrue(JukeboxMode.isAutoAdvanceBlocked());
    }

    @Test
    public void autoAdvanceAllowedWhenNoRoom() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        assertFalse(JukeboxMode.isAutoAdvanceBlocked());
    }

    @Test
    public void stoppingTheRoomUnblocksAutoAdvance() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        assertFalse(JukeboxMode.isAutoAdvanceBlocked());
    }

    @Test
    public void seekKeysClassifyAsSeekKeys() {
        // The exact keys the seek bar/glue act on (nina 2464 step 7: DPAD_RIGHT
        // seeked ~15s via the seek bar interval).
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_PLUS));
        assertTrue(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MINUS));
    }

    @Test
    public void nonSeekKeysDoNotClassifyAsSeekKeys() {
        // Media prev/next are gated separately (previous/next handlers), and
        // navigation/confirm keys must keep working in the player.
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_DPAD_UP));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_BACK));
        assertFalse(JukeboxMode.isSeekKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
    }
}
