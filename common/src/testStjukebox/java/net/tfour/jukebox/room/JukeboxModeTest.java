package net.tfour.jukebox.room;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * While a jukebox room is active (even idle, no current song), SmartTube's own
 * auto-advance must be blocked so the player never plays a non-room video on its
 * own (room "play" is the only thing that opens the next song).
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
}
