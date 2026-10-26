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
    private static final String LOG_TAG = "JukeboxPopup";
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
    /** Room videoId whose exhausted reopen cap was already logged (log spam guard). */
    private String mCapLoggedVideoId;
    /** Room videoId whose "ended" was already reported; never auto-reopened. */
    private String mEndedVideoId;
    /** VideoId whose load is in flight (seen in onNewVideo, not yet onVideoLoaded). */
    private String mLoadingVideoId;
    /**
     * Resume check posted by {@link #onEngineReleased()}: the release can be the
     * last event to reach this controller (see onEngineReleased), so the check
     * must also run afterwards. Cancelled by onNewVideo/onVideoLoaded when a new
     * load makes it moot.
     */
    private final Runnable mResumeCheck = this::maybeResumeRoomSong;

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
            JukeboxRoom room = JukeboxRoom.instance(ctx);
            JukeboxLog.i(LOG_TAG, "pos=" + player.getPositionMs() + "ms player=" + mLoadedVideoId
                    + " room=" + room.getRoom() + " roomVid=" + room.getCurrentVideoId());
            room.reportProgress(
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
        ensureListening();
        ensureJoinBadge();
        updatePopupOverlay();
    }

    @Override
    public void onViewResumed() {
        ensureListening();
        ensureJoinBadge();
        updatePopupOverlay();
        maybeResumeRoomSong();
        ensureRoomSongPlaying();
    }

    @Override
    public void onNewVideo(Video item) {
        // openVideo() started a load: track it as in flight. player.getVideo()/
        // getVideo() keep the previous song's id long after its engine was
        // released, so they cannot tell "already loading the room video" from
        // "stale id of the released song" — only this can. Also drop a resume
        // check posted by onEngineReleased(): a new load is on its way.
        mLoadingVideoId = item != null ? item.videoId : null;
        handler().removeCallbacks(mResumeCheck);
    }

    @Override
    public void onVideoLoaded(Video item) {
        // The in-flight load finished (mLoadingVideoId must not survive into the
        // next idle period), and a posted resume check is moot now.
        mLoadingVideoId = null;
        handler().removeCallbacks(mResumeCheck);
        updatePopupOverlay();
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
            if (loadedId != null) {
                JukeboxLog.i(LOG_TAG, "idle room: player=" + loadedId + " plays unsupervised (no current song)");
            }
            resetReopen();
        } else if (roomVideoId.equals(loadedId)) {
            resetReopen();
            mEndedVideoId = null; // the room video is (again) the loaded one
        } else if (!JukeboxForeground.isAppInForeground()) {
            // Never take over the TV (issue #3): while another app is in use, a
            // mismatched (non-room) video is only paused — enforcement without
            // launching anything. The room's exact song opens when the user
            // returns (the resume check re-runs in the foreground).
            JukeboxLog.i(LOG_TAG, "non-room video loaded while backgrounded: player=" + loadedId
                    + " roomVid=" + roomVideoId + ": pausing, reopen deferred until foreground");
            if (player != null) {
                player.setPlayWhenReady(false);
            }
            stopHeartbeat();
            mLoadedVideoId = null;
            return;
        } else if (beginReopen(roomVideoId)) {
            // Mismatch, and this room video is not the one we're already opening.
            // The player substituted/kept a non-room video: re-open the exact room id.
            openRoomVideo(roomVideoId, "non-room video loaded: player=" + loadedId
                    + " roomVid=" + roomVideoId + " song=" + room.getRoom());
            return;
        } else {
            // Already requested this room video, or the 2-attempt cap is hit. Never
            // let the non-room video keep playing: pause it and keep enforcing the
            // room id (nothing is reported for a non-room video).
            JukeboxLog.i(LOG_TAG, "non-room video loaded: player=" + loadedId + " roomVid=" + roomVideoId
                    + ": reopen attempts exhausted, pausing non-room playback");
            if (player != null) {
                player.setPlayWhenReady(false);
            }
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
        // Playback of the room song is over: drop any pop-up bubbles.
        dismissPopupOverlay();
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        // Only tell the room. It sends "ended"; the following "play" opens the next song.
        Video video = getPlayer() != null && getPlayer().getVideo() != null ? getPlayer().getVideo() : getVideo();
        if (video != null && video.videoId != null && getContext() != null) {
            mEndedVideoId = video.videoId;
            JukeboxRoom.instance(getContext()).songEnded(video.videoId);
        }
        mLoadedVideoId = null;
        mLoadingVideoId = null;
    }

    @Override
    public void onEngineReleased() {
        stopHeartbeat();
        mLoadedVideoId = null;
        mLoadingVideoId = null;
        resetReopen();
        dismissPopupOverlay();
        // Entering JukeboxJoinActivity mid-song runs its onStart() -> start() ->
        // notifyRebind() BEFORE ActivityThread dispatches PlaybackActivity.onStop()
        // -> this callback, so the earlier resume checks all saw a non-null
        // mLoadedVideoId and did nothing. Re-run the check once the release has
        // settled; onNewVideo/onVideoLoaded cancel it when a load makes it moot.
        handler().removeCallbacks(mResumeCheck);
        handler().post(mResumeCheck);
    }

    @Override
    public void onRoomUpdated() {
        ensureJoinBadge();
        updatePopupOverlay();
        maybeResumeRoomSong();
    }

    @Override
    public void onStatusChanged(String status) {
        // Badge shows code, QR and host, not connection status. The overlay check is
        // cheap: song/settings may have been cleared on the closed/stopped/idle paths.
        updatePopupOverlay();
        maybeResumeRoomSong();
    }

    @Override
    public void onStateChanged(String nowPlaying) {
        // Badge does not show the now-playing title.
        updatePopupOverlay();
        maybeResumeRoomSong();
    }

    @Override
    public void onPopupsChanged(boolean enabled) {
        updatePopupOverlay();
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
            mCapLoggedVideoId = null; // a new song's cap (if any) must be loggable again
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
        mCapLoggedVideoId = null;
    }

    /** Pauses whatever is loaded and (re)opens the exact room videoId (bounded by {@link #beginReopen}). */
    private void openRoomVideo(String roomVideoId, String reason) {
        JukeboxLog.i(LOG_TAG, reason + ": opening exact room videoId " + roomVideoId
                + " (attempt " + mReopenAttempts + ")");
        stopHeartbeat();
        mLoadedVideoId = null;
        mSuppressReports = true;
        try {
            PlaybackView player = getPlayer();
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
            if (!JukeboxForeground.isAppInForeground() && roomVideoId.equals(mLoadingVideoId)) {
                // The launch gate just blocked ViewManager.startView (issue #3:
                // the app backgrounded between the foreground check above and
                // the launch inside openVideo). With the player activity never
                // started, no engine load — and so no onVideoLoaded — will ever
                // clear the in-flight marker. Clear it here, or every retry sees
                // "load already on its way" and the room stays silent forever.
                JukeboxLog.i(LOG_TAG, "launch of " + roomVideoId
                        + " blocked (app backgrounded): in-flight marker cleared, reopen retried on return");
                mLoadingVideoId = null;
            }
        }
    }

    /**
     * Re-opens the room's current song when nothing plays it while a song is
     * active — e.g. the engine was released by re-entering the join screen
     * mid-song, or no player exists at all (join screen in the foreground:
     * openVideo starts PlaybackActivity through the gated ViewManager.startView).
     * Without this the client keeps the (deduplicated) song id, nothing plays,
     * heartbeats stop and "ended" is never sent: the room queue stalls until a
     * phone skips the song. Bounded by the same {@link #beginReopen} cap as
     * mismatch reopens, never reopens a video whose "ended" was already
     * reported, never opens a song id that only a "state" snapshot provided (its
     * "play" message stays the only instruction to open a video), and never
     * duplicates a load that is still in flight.
     *
     * Deferred while the app is backgrounded (issue #3): opening the video would
     * launch/foreground the player over whatever the user is watching. The check
     * re-runs (in the foreground) when the user returns: re-entering the join
     * screen notifies room listeners, resuming the player view calls it again,
     * and the join screen's bounded retry (see JukeboxJoinActivity) keeps asking
     * for a while so a single mistimed check can never leave the room silent.
     * Every deferral is logged so gazebo runs can see why nothing played.
     *
     * @return true while a later retry could still succeed on its own (deferred:
     *         app backgrounded, no context yet); false when retrying cannot help
     *         and the join screen's retry loop should stop asking (song loaded,
     *         reopen dispatched, idle room, song ended, snapshot-sourced song, or
     *         the reopen cap reached — logged once per song).
     */
    private boolean maybeResumeRoomSong() {
        return maybeResumeRoomSong(null);
    }

    /** @param via caller context for the deferral logs (e.g. the join screen retry), or null. */
    private boolean maybeResumeRoomSong(String via) {
        if (!JukeboxRoom.isRoomActive() || mLoadedVideoId != null) {
            return false; // room gone, or a song is already loaded: nothing to resume
        }
        if (mSuppressReports) {
            return true; // a reopen is on the stack right now: transient, ask again later
        }
        if (!JukeboxForeground.isAppInForeground()) {
            // The song opens when the user returns; only state has been updated.
            JukeboxLog.i(LOG_TAG, "resume deferred: app backgrounded" + viaSuffix(via)
                    + " (song opens on the user's return)");
            return true;
        }
        Context ctx = getContext();
        if (ctx == null) {
            JukeboxLog.i(LOG_TAG, "resume deferred: no context yet" + viaSuffix(via));
            return true;
        }
        // No player yet (e.g. the user sits on the join screen) is NOT a deferral
        // anymore (build 2466 repeated "no player yet" forever and the room stayed
        // silent): openVideo below starts PlaybackActivity through the gated
        // ViewManager.startView, which creates the player.
        PlaybackView player = getPlayer();
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        String roomVideoId = room.getCurrentVideoId();
        if (roomVideoId == null || roomVideoId.equals(mEndedVideoId)) {
            return false; // idle room, or the song already ended and was reported
        }
        if (roomVideoId.equals(mLoadingVideoId)) {
            return false; // the engine (re)load of the room video is already on its way
        }
        if (room.isSongFromState()) {
            JukeboxLog.i(LOG_TAG, "resume deferred: song id came from a state snapshot,"
                    + " waiting for its own play" + viaSuffix(via));
            return false; // snapshot-sourced song id: wait for its own "play" message
        }
        if (!beginReopen(roomVideoId)) {
            // Already opening it, or the reopen cap for this song is exhausted.
            // Log once per song: the join screen retry stops on this answer, but
            // room events keep re-running the check.
            if (!roomVideoId.equals(mCapLoggedVideoId)) {
                JukeboxLog.i(LOG_TAG, "resume deferred: reopen cap reached for " + roomVideoId + viaSuffix(via));
                mCapLoggedVideoId = roomVideoId;
            }
            return false;
        }
        openRoomVideo(roomVideoId, "room song active but player idle" + viaSuffix(via));
        return false;
    }

    private static String viaSuffix(String via) {
        return via != null && !via.isEmpty() ? " (via " + via + ")" : "";
    }

    /**
     * Public entry for the join screen's bounded return-retry (and anything else
     * that knows the user just came back): runs the idle resume check with a
     * caller tag so the decision is verifiable in logcat.
     *
     * @return whether the retry loop should keep asking (see
     *         {@link #maybeResumeRoomSong()}); false stops it — e.g. once the
     *         reopen cap is reached, so the cap deferral is logged exactly once
     *         instead of on every remaining tick.
     */
    public boolean resumeRoomSongIfNeeded(String via) {
        if (!JukeboxRoom.isRoomActive()) {
            return false; // no room: the retry has nothing to ask for
        }
        if (mSuppressReports) {
            return true; // a reopen is on the stack right now: transient
        }
        return maybeResumeRoomSong(via);
    }

    /**
     * Reopening the app (or otherwise resuming the player view) with the room's
     * song loaded must leave that song actually playing: SmartTube's own state
     * restore can load it paused (e.g. a saved playWhenReady=false applied on
     * load), which used to require a DPAD_CENTER press. While a room song is
     * active the TV is a listener — the room's song plays.
     */
    private void ensureRoomSongPlaying() {
        if (!JukeboxRoom.isRoomActive() || mSuppressReports) {
            return;
        }
        PlaybackView player = getPlayer();
        Context ctx = getContext();
        if (player == null || ctx == null) {
            return;
        }
        String roomVideoId = JukeboxRoom.instance(ctx).getCurrentVideoId();
        if (roomVideoId == null || !roomVideoId.equals(mLoadedVideoId)) {
            return; // nothing loaded, or not the room's song
        }
        if (!player.getPlayWhenReady()) {
            JukeboxLog.i(LOG_TAG, "room song " + roomVideoId + " loaded but paused on view resume: playing");
            player.setPlayWhenReady(true);
        }
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
        JukeboxRoom room = JukeboxRoom.instance(activity);
        JukeboxJoinBadge.show(activity, room.getCode(), room.getJoinUrl());
    }

    /**
     * Registers as a {@link JukeboxRoom} listener as soon as any context exists, so
     * room ready/reconnect/play events reach the badge and the pop-up overlay even
     * when the room was started (or reconnected) after the player came up. Without
     * this, the pop-up code never sees the room id and logs room=null forever.
     */
    private void ensureListening() {
        if (mListening) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        JukeboxRoom.instance(ctx).addListener(this);
        mListening = true;
    }

    /** Shows/refreshes (or removes) the pop-up bubble overlay, mirroring {@link #ensureJoinBadge()}. */
    private void updatePopupOverlay() {
        Activity activity = getActivity();
        PlaybackView player = getPlayer();
        Activity host = player != null ? ViewManager.getMotherActivity(player) : null;
        if (activity == null || host == null || activity != host) {
            JukeboxLog.i(LOG_TAG, "skip: host mismatch");
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            JukeboxLog.i(LOG_TAG, "skip: no context");
            return;
        }
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        if (!JukeboxRoom.isRoomActive()) {
            JukeboxLog.i(LOG_TAG, "skip: room inactive");
            JukeboxPopupOverlay.dismiss(activity);
            return;
        }
        if (!room.isPopupsEnabled()) {
            JukeboxLog.i(LOG_TAG, "skip: popups=false");
            JukeboxPopupOverlay.dismiss(activity);
            return;
        }
        String videoId = room.getCurrentVideoId();
        if (!JukeboxProtocol.isValidVideoId(videoId)) {
            JukeboxLog.i(LOG_TAG, "skip: vid=null room=" + room.getRoom());
            JukeboxPopupOverlay.dismiss(activity);
            return;
        }
        final String roomVideoId = videoId;
        JukeboxPopupOverlay.show(activity, room.getOrigin(), roomVideoId, () -> {
            // Only bubble along the room song itself; a negative position hides/idles.
            PlaybackView p = getPlayer();
            Video v = p != null ? p.getVideo() : null;
            return v != null && roomVideoId.equals(v.videoId) ? p.getPositionMs() : -1L;
        });
    }

    private void dismissPopupOverlay() {
        Activity activity = getActivity();
        if (activity != null) {
            JukeboxPopupOverlay.dismiss(activity);
        }
    }
}
