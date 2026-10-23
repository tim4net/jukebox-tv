package net.tfour.jukebox.room;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

/**
 * Bridges playback events into the jukebox room (stjukebox flavor only; other flavors
 * never contain this class — PlaybackPresenter loads it reflectively). All callbacks are
 * no-ops unless the room is active. The room client filters reports that don't match the
 * current room song, so normal (non-jukebox) playback is never reported.
 *
 * While the room is active this controller forces the loaded song to play, heartbeats
 * position every 5s as "progress", and — only while a room song is set — reopens that song if a
 * different video loads. SmartTube autoplay-next is gated the same way via {@code JukeboxMode}.
 */
public class JukeboxRoomController extends BasePlayerController implements JukeboxRoom.Listener {
    private static final long HEARTBEAT_MS = 5_000;
    private static final int MAX_REOPEN_ATTEMPTS = 2;

    private Handler mHandler;
    private boolean mHeartbeatRunning;
    private String mLoadedVideoId;
    private boolean mListening;
    private boolean mSuppressReports;
    /** Room videoId of the openVideo call currently on the stack. */
    private String mRequestedVideoId;
    /** Room videoId the reopen counter applies to. */
    private String mReopenVideoId;
    private int mReopenAttempts;

    private final Runnable mHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (!mHeartbeatRunning) {
                return;
            }
            PlaybackView player = getPlayer();
            Context ctx = getContext();
            if (!JukeboxRoom.isRoomActive() || mLoadedVideoId == null || player == null || ctx == null) {
                stopHeartbeat();
                return;
            }
            JukeboxRoom.instance(ctx).reportProgress(
                    mLoadedVideoId,
                    toSec(player.getDurationMs()),
                    toSec(player.getPositionMs()));
            if (mHeartbeatRunning) {
                handler().postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    public JukeboxRoomController() {
        // Public no-arg constructor required for reflective registration.
    }

    @Override
    public void onInit() {
        ensureJoinBadge();
    }

    @Override
    public void onViewResumed() {
        ensureJoinBadge();
    }

    @Override
    public void onVideoLoaded(Video item) {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        ensureJoinBadge();
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        PlaybackView player = getPlayer();
        String loadedId = loadedVideoId(item, player);
        String roomVideoId = room.getCurrentVideoId();
        if (roomVideoId == null) {
            // Idle room: a Browse pick (or anything else) plays as usual.
            resetReopen();
        } else if (roomVideoId.equals(loadedId)) {
            resetReopen();
        } else if (beginReopen(roomVideoId)) {
            // Mismatch, and this room video is not the one we're already opening.
            stopHeartbeat();
            mLoadedVideoId = null;
            mSuppressReports = true;
            try {
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
                getPlaybackPresenter().openVideo(roomVideoId);
            } finally {
                mSuppressReports = false;
                // Drop the in-flight id so a later mismatch can use a remaining
                // attempt. A re-entrant onVideoLoaded still sees mRequestedVideoId.
                if (roomVideoId.equals(mRequestedVideoId)) {
                    mRequestedVideoId = null;
                }
            }
            return;
        } else {
            // Already requested this room video, or the 2-attempt cap is hit.
            stopHeartbeat();
            mLoadedVideoId = null;
            return;
        }
        if (loadedId == null) {
            return;
        }
        mLoadedVideoId = loadedId;
        if (player != null) {
            player.setPlayWhenReady(true);
        }
        Integer durationSec = player != null ? toSec(player.getDurationMs()) : null;
        if (durationSec == null && item != null && item.getDurationMs() > 0) {
            durationSec = (int) (item.getDurationMs() / 1000);
        }
        Integer positionSec = player != null ? toSec(player.getPositionMs()) : null;
        room.reportPlaying(loadedId, durationSec, positionSec, "playing");
        startHeartbeat();
    }

    @Override
    public void onPlay() {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        if (mLoadedVideoId != null) {
            startHeartbeat();
        }
        reportState("playing");
    }

    @Override
    public void onPause() {
        stopHeartbeat();
        reportState("paused");
    }

    @Override
    public void onPlayEnd() {
        stopHeartbeat();
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        // Only tell the room. It sends "ended"; the following "play" opens the next song.
        Video video = getPlayer() != null && getPlayer().getVideo() != null ? getPlayer().getVideo() : getVideo();
        if (video != null && video.videoId != null && getContext() != null) {
            JukeboxRoom.instance(getContext()).songEnded(video.videoId);
        }
        mLoadedVideoId = null;
    }

    @Override
    public void onEngineReleased() {
        stopHeartbeat();
        mLoadedVideoId = null;
        resetReopen();
    }

    @Override
    public void onRoomUpdated() {
        ensureJoinBadge();
    }

    @Override
    public void onStatusChanged(String status) {
        // Badge shows code, QR and host, not connection status.
    }

    @Override
    public void onStateChanged(String nowPlaying) {
        // Badge does not show the now-playing title.
    }

    private void reportState(String state) {
        if (mSuppressReports || !JukeboxRoom.isRoomActive()) {
            return;
        }
        Context ctx = getContext();
        String videoId = mLoadedVideoId;
        if (ctx == null || videoId == null) {
            return;
        }
        PlaybackView player = getPlayer();
        Integer durationSec = player != null ? toSec(player.getDurationMs()) : null;
        Integer positionSec = player != null ? toSec(player.getPositionMs()) : null;
        JukeboxRoom.instance(ctx).reportPlaying(videoId, durationSec, positionSec, state);
    }

    private void startHeartbeat() {
        if (mLoadedVideoId == null || mHeartbeatRunning) {
            return;
        }
        mHeartbeatRunning = true;
        handler().postDelayed(mHeartbeat, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        mHeartbeatRunning = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mHeartbeat);
        }
    }

    private Handler handler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    /**
     * @return true if openVideo(roomVideoId) should run. At most two attempts per
     * room videoId. A nested onVideoLoaded during that open sees {@link #mRequestedVideoId}
     * and does not open again. A matching load resets the counter.
     */
    private boolean beginReopen(String roomVideoId) {
        if (!roomVideoId.equals(mReopenVideoId)) {
            mReopenVideoId = roomVideoId;
            mReopenAttempts = 0;
            mRequestedVideoId = null;
        }
        if (roomVideoId.equals(mRequestedVideoId) || mReopenAttempts >= MAX_REOPEN_ATTEMPTS) {
            return false;
        }
        mRequestedVideoId = roomVideoId;
        mReopenAttempts++;
        return true;
    }

    private void resetReopen() {
        mRequestedVideoId = null;
        mReopenVideoId = null;
        mReopenAttempts = 0;
    }

    /** Video id actually loaded in the player, not a previously queued id. */
    private static String loadedVideoId(Video item, PlaybackView player) {
        if (item != null && item.videoId != null) {
            return item.videoId;
        }
        if (player != null && player.getVideo() != null) {
            return player.getVideo().videoId;
        }
        return null;
    }

    private static Integer toSec(long ms) {
        if (ms < 0) {
            return null;
        }
        return (int) (ms / 1000);
    }

    private void ensureJoinBadge() {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        Activity activity = getActivity();
        PlaybackView player = getPlayer();
        // R8 renames PlaybackActivity, so a class-name check (and Class.forName) cannot
        // find it. The badge belongs on the activity that hosts the player view, and
        // only when that activity is the one this controller is bound to.
        Activity host = player != null ? ViewManager.getMotherActivity(player) : null;
        if (activity == null || host == null || activity != host) {
            return;
        }
        if (!mListening) {
            JukeboxRoom.instance(activity).addListener(this);
            mListening = true;
        }
        JukeboxRoom room = JukeboxRoom.instance(activity);
        JukeboxJoinBadge.show(activity, room.getCode(), room.getJoinUrl());
    }
}
