package com.liskovsoft.smartyoutubetv2.common.misc;

import com.google.android.exoplayer2.DefaultControlDispatcher;
import com.google.android.exoplayer2.Player;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

/**
 * Media-session {@link com.google.android.exoplayer2.ControlDispatcher} for the
 * jukebox flavor (stjukebox only; {@code JukeboxMode} is never set in other
 * flavors, so this dispatcher behaves exactly like its default there):
 * while a jukebox room song is active the TV is a listener — the room owns
 * play/pause and seeking — so media-button play/pause (and any media-session
 * seek) dispatched at the player is swallowed and logged ("JukeboxKeys:
 * swallowed ..."), keeping the room's song playing (build 2469, gazebo c4:
 * KEYCODE_MEDIA_PLAY_PAUSE paused the player through the media session).
 */
public class JukeboxControlDispatcher extends DefaultControlDispatcher {
    @Override
    public boolean dispatchSetPlayWhenReady(Player player, boolean playWhenReady) {
        if (JukeboxMode.swallowPlayPause("media-session " + (playWhenReady ? "play" : "pause"))) {
            return false; // suppressed: the room owns play/pause
        }
        return super.dispatchSetPlayWhenReady(player, playWhenReady);
    }

    @Override
    public boolean dispatchSeekTo(Player player, int windowIndex, long positionMs) {
        if (JukeboxMode.swallowSeek("media-session seek")) {
            return false; // suppressed: the room's song can never be seeked
        }
        return super.dispatchSeekTo(player, windowIndex, positionMs);
    }
}