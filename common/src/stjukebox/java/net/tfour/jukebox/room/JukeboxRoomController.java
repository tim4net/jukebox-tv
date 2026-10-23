package net.tfour.jukebox.room;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;

/**
 * Bridges playback events into the jukebox room (stjukebox flavor only; other flavors
 * never contain this class — PlaybackPresenter loads it reflectively). All callbacks are
 * no-ops unless the room is active. The room client filters reports that don't match the
 * current room song, so normal (non-jukebox) playback is never reported.
 */
public class JukeboxRoomController extends BasePlayerController {
    public JukeboxRoomController() {
        // Public no-arg constructor required for reflective registration.
    }

    @Override
    public void onVideoLoaded(Video item) {
        if (item == null || !JukeboxRoom.isRoomActive()) {
            return;
        }
        Integer durationSec = null;
        if (item.getDurationMs() > 0) {
            durationSec = (int) (item.getDurationMs() / 1000);
        }
        JukeboxRoom.instance(getContext()).reportPlaying(item.videoId, durationSec, null, "playing");
    }

    @Override
    public void onPlay() {
        reportState("playing");
    }

    @Override
    public void onPause() {
        reportState("paused");
    }

    @Override
    public void onPlayEnd() {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        Video video = getVideo();
        if (video != null) {
            JukeboxRoom.instance(getContext()).songEnded(video.videoId);
        }
    }

    private void reportState(String state) {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        Video video = getVideo();
        if (video != null) {
            JukeboxRoom.instance(getContext()).reportPlaying(video.videoId, null, null, state);
        }
    }
}
