package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

/**
 * Process-wide flag set by the stjukebox flavor when the room has a current song
 * ({@code JukeboxRoomClient} songId and videoId both non-null). common/main cannot
 * reference that class. A connected but idle room leaves this false, so videos opened
 * from Browse keep normal SmartTube autoplay. Other flavors never set it.
 */
public final class JukeboxMode {
    private static volatile boolean sSongSet;

    private JukeboxMode() {
    }

    /** @param songSet true while a room song is current, false when the room is idle or stopped. */
    public static void setSongSet(boolean songSet) {
        sSongSet = songSet;
    }

    /** True while a room song is set. Not the same as the room socket being connected. */
    public static boolean isSongSet() {
        return sSongSet;
    }
}
