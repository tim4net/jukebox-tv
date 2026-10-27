package net.tfour.jukebox.room;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
    /**
     * How long an in-flight load marker ({@link #mLoadingVideoId}) may still count
     * as "a load of the room video is on its way". The engine load of an openVideo
     * settles within seconds; a marker older than this is leftover state (e.g. the
     * engine was released mid-load while the app sat backgrounded) and must never
     * silence the room forever. 15s, not shorter: a normal pending load
     * ({@code onNewVideo} before the player activity has attached) is young and
     * must not be treated as stale.
     */
    private static final long LOAD_IN_FLIGHT_TIMEOUT_MS = 15_000;
    /**
     * First {@link #onPlay()} after a load seeks when the playhead is farther
     * than this from the room position. Nina 2488 cold start lands 4.3-4.9s
     * behind because the open seek is computed at openVideo time and the first
     * frame is 11-12s later.
     */
    private static final long COLD_START_DRIFT_MS = 1_000L;

    private Handler mHandler;
    private boolean mHeartbeatRunning;
    private String mLoadedVideoId;
    /**
     * True after the first {@link #onPlay()} catch-up check for the current
     * {@link #mLoadedVideoId}. Cleared wherever that id is set or cleared, so
     * each load gets one check. A later onPlay (pause/resume) does not seek.
     */
    private boolean mColdStartDriftCatchUpDone;
    private boolean mListening;
    private boolean mSuppressReports;
    /** Room videoId of the openVideo call currently on the stack. */
    private String mRequestedVideoId;
    /** Room videoId the reopen counter applies to. */
    private String mReopenVideoId;
    private int mReopenAttempts;
    /** Room videoId whose exhausted reopen cap was already logged (log spam guard). */
    private String mCapLoggedVideoId;
    /** Last resume-decision log line (dedup guard so repeated identical decisions don't spam). */
    private String mLastResumeLog;
    /** Room videoId whose "ended" was already reported; never auto-reopened. */
    private String mEndedVideoId;
    /** VideoId whose load is in flight (seen in onNewVideo, not yet onVideoLoaded). */
    private String mLoadingVideoId;
    /** {@link SystemClock#elapsedRealtime()} when {@link #mLoadingVideoId} was set; 0 while none. */
    private long mLoadingSinceMs;
    /**
     * True while a room-song resume was deferred because the app went away (a
     * resume check found the app backgrounded, or the engine was released under
     * Home/another app). The next foreground-return resume is then allowed to
     * open even a snapshot-sourced song id (build 2470): the "wait for its own
     * play" rule only covers a fresh snapshot arriving while the app is in the
     * foreground — it must never keep a returned app stuck on the join screen
     * with a silent room (build 2469 gazebo steps 2/3).
     */
    private boolean mResumeAfterReturn;
    /** VideoId of the last playback position this controller reported/observed. */
    private String mLastPosVideoId;
    /** Last observed playback position of {@link #mLastPosVideoId} in ms (cheap resume seek hint). */
    private long mLastPosMs;
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
                if (player == null) {
                    JukeboxLog.i(LOG_TAG, "heartbeat stopped: player==null");
                }
                stopHeartbeat();
                return;
            }
            JukeboxRoom room = JukeboxRoom.instance(ctx);
            long posMs = player.getPositionMs();
            JukeboxLog.i(LOG_TAG, "pos=" + posMs + "ms player=" + mLoadedVideoId
                    + " room=" + room.getRoom() + " roomVid=" + room.getCurrentVideoId());
            rememberProgress(mLoadedVideoId, posMs);
            room.reportProgress(
                    mLoadedVideoId,
                    toSec(player.getDurationMs()),
                    toSec(posMs));
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
        mLoadingSinceMs = mLoadingVideoId != null ? SystemClock.elapsedRealtime() : 0L;
        handler().removeCallbacks(mResumeCheck);
    }

    @Override
    public void onVideoLoaded(Video item) {
        // The in-flight load finished (mLoadingVideoId must not survive into the
        // next idle period), and a posted resume check is moot now.
        mLoadingVideoId = null;
        mLoadingSinceMs = 0L;
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
            mResumeAfterReturn = false; // the room song really plays: no return resume pending
            rememberProgress(loadedId, player != null ? player.getPositionMs() : -1L);
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
            setLoadedVideoId(null);
            return;
        } else if (beginReopen(roomVideoId)) {
            // Mismatch, and this room video is not the one we're already opening.
            // The player substituted/kept a non-room video: re-open the exact room id
            // at the room position when one is known (issue #24).
            openRoomVideo(roomVideoId, "non-room video loaded: player=" + loadedId
                    + " roomVid=" + roomVideoId + " song=" + room.getRoom(),
                    pendingPosMs(roomVideoId));
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
            setLoadedVideoId(null);
            return;
        }
        if (loadedId == null) {
            return;
        }
        setLoadedVideoId(loadedId);
        // A paused snapshot stays paused: do not start playback on load.
        // ensureRoomSongPlaying is what unpauses, and only once a later
        // snapshot says the song is playing.
        boolean holdPaused = room.isSnapshotPaused(loadedId);
        if (player != null) {
            player.setPlayWhenReady(!holdPaused);
        }
        Integer durationSec = player != null ? toSec(player.getDurationMs()) : null;
        if (durationSec == null && item != null && item.getDurationMs() > 0) {
            durationSec = (int) (item.getDurationMs() / 1000);
        }
        Integer positionSec = player != null ? toSec(player.getPositionMs()) : null;
        room.reportPlaying(loadedId, durationSec, positionSec, holdPaused ? "paused" : "playing");
        if (holdPaused) {
            stopHeartbeat();
        } else {
            startHeartbeat();
        }
    }

    @Override
    public void onPlay() {
        if (!JukeboxRoom.isRoomActive()) {
            return;
        }
        catchUpColdStartDriftOnce();
        if (mLoadedVideoId != null) {
            startHeartbeat();
        }
        reportState("playing");
    }

    /**
     * First {@link #onPlay()} after this load of the room song. Recompute the
     * room position (it has moved since openVideo) and seek once when the
     * playhead is more than {@link #COLD_START_DRIFT_MS} off. A paused room and
     * an unknown position ({@code -1}) do not seek. The one-shot flag blocks a
     * later onPlay from seeking again.
     */
    private void catchUpColdStartDriftOnce() {
        if (mColdStartDriftCatchUpDone || mLoadedVideoId == null) {
            return;
        }
        Context ctx = getContext();
        PlaybackView player = getPlayer();
        if (player == null) {
            JukeboxLog.i(LOG_TAG, "cold-start drift skipped: player==null");
            return;
        }
        if (ctx == null) {
            return;
        }
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        if (!mLoadedVideoId.equals(room.getCurrentVideoId())) {
            return;
        }
        mColdStartDriftCatchUpDone = true;
        if (room.isSnapshotPaused(mLoadedVideoId)) {
            return;
        }
        long roomPos = room.getRoomPositionMs(mLoadedVideoId);
        if (roomPos < 0) {
            return;
        }
        long playerPos = player.getPositionMs();
        if (Math.abs(roomPos - playerPos) > COLD_START_DRIFT_MS) {
            JukeboxLog.i(LOG_TAG, "cold-start drift " + (roomPos - playerPos) + "ms on " + mLoadedVideoId
                    + ": seeking once to room position " + roomPos + "ms");
            player.setPositionMs(roomPos);
        }
    }

    @Override
    public void onPause() {
        stopHeartbeat();
        reportState("paused");
    }

    @Override
    public void onPlayEnd() {
        // The engine has stopped, including a rejected period-transition end.
        // Progress must not keep ticking on any of these paths.
        stopHeartbeat();
        if (!JukeboxRoom.isRoomActive()) {
            dismissPopupOverlay();
            return;
        }
        // ExoPlayer reports onPlayEnd for a period transition during reopen/reload
        // while the playhead is still mid-song (issue #25). Sending "ended" then
        // makes the server drop the song. Only a real end of the current room
        // song's video id may be reported, and a rejected end must not clear the
        // in-flight load or the ended-guard. A player duration of 0 (or unset)
        // falls back to the room snapshot's duration; when both are unknown the
        // end is accepted.
        PlaybackView player = getPlayer();
        Video video = player != null && player.getVideo() != null ? player.getVideo() : getVideo();
        String videoId = video != null ? video.videoId : null;
        if (player == null) {
            JukeboxLog.i(LOG_TAG, "play end: player==null");
        }
        long positionMs = player != null ? player.getPositionMs() : -1L;
        long playerDurMs = player != null ? player.getDurationMs() : -1L;
        Context ctx = getContext();
        JukeboxRoom room = ctx != null ? JukeboxRoom.instance(ctx) : null;
        String roomVideoId = room != null ? room.getCurrentVideoId() : null;
        boolean isRoomVideo = JukeboxProtocol.isValidVideoId(videoId) && videoId.equals(roomVideoId);
        long snapDurMs = room != null ? room.getSnapshotDurationMs(videoId) : -1L;
        if (!shouldReportEnded(isRoomVideo, positionMs, playerDurMs, snapDurMs)) {
            JukeboxLog.i(LOG_TAG, "play end ignored: videoId=" + videoId
                    + " positionMs=" + positionMs + " durationMs=" + playerDurMs);
            return;
        }
        // Playback of the room song is over: drop any pop-up bubbles.
        dismissPopupOverlay();
        mEndedVideoId = videoId;
        if (room != null) {
            room.songEnded(videoId, positionMs);
        }
        setLoadedVideoId(null);
        mLoadingVideoId = null;
        mLoadingSinceMs = 0L;
    }

    /**
     * Whether {@link #onPlayEnd()} should send "ended". A positive player
     * duration wins. Zero or negative falls back to the room snapshot's
     * duration. When both are unknown, a room video accepts the end. A
     * video that is not the room's current song never reports.
     */
    static boolean shouldReportEnded(boolean isRoomVideo, long posMs, long playerDurMs, long snapDurMs) {
        if (!isRoomVideo) {
            return false;
        }
        long durMs = playerDurMs > 0L ? playerDurMs : snapDurMs;
        if (durMs <= 0L) {
            return true;
        }
        return JukeboxRoomClient.isAtEnd(posMs, durMs);
    }

    @Override
    public void onEngineReleased() {
        stopHeartbeat();
        setLoadedVideoId(null);
        mLoadingVideoId = null;
        mLoadingSinceMs = 0L;
        resetReopen();
        mResumeAfterReturn = true; // the app went away mid-song: the return resume opens the song
        mLastResumeLog = null; // the next decision may repeat a line: log it fresh
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

    /**
     * Sets or clears the loaded room video and arms a fresh one-shot catch-up.
     * Every assignment of {@link #mLoadedVideoId} goes through here.
     */
    private void setLoadedVideoId(String videoId) {
        mLoadedVideoId = videoId;
        mColdStartDriftCatchUpDone = false;
    }

    /**
     * Pauses whatever is loaded and (re)opens the exact room videoId (bounded by
     * {@link #beginReopen}). {@code posMs} (>= 0) becomes the opened video's
     * pending position — the room position when it is known, otherwise the last
     * player tick (-1: neither).
     */
    private void openRoomVideo(String roomVideoId, String reason, long posMs) {
        JukeboxLog.i(LOG_TAG, reason + ": opening exact room videoId " + roomVideoId
                + " (attempt " + mReopenAttempts + ", posMs=" + posMs + ")");
        mLastResumeLog = null; // the next decision may repeat a line: log it fresh
        stopHeartbeat();
        setLoadedVideoId(null);
        mSuppressReports = true;
        try {
            PlaybackView player = getPlayer();
            if (player != null) {
                player.setPlayWhenReady(false);
            }
            getPlaybackPresenter().openVideo(roomVideoId, false, posMs, false);
        } finally {
            mSuppressReports = false;
            // Drop the in-flight id so a later mismatch can use a remaining
            // attempt. A re-entrant onVideoLoaded still sees mRequestedVideoId.
            if (roomVideoId.equals(mRequestedVideoId)) {
                mRequestedVideoId = null;
            }
            if (!JukeboxForeground.isAppInForeground()) {
                // The launch gate blocked ViewManager.startView (issue #3: the
                // app backgrounded between the foreground check above and the
                // launch inside openVideo). With the player activity never
                // started, no engine load — and so no onVideoLoaded — will ever
                // clear the in-flight marker. Clear it here, and refund the
                // consumed reopen attempt (2469: stale counters from before the
                // return must never block the relaunch), or every retry sees
                // "load already on its way" / "cap reached" and the room stays
                // silent forever.
                JukeboxLog.i(LOG_TAG, "launch of " + roomVideoId
                        + " blocked (app backgrounded): in-flight marker cleared and reopen"
                        + " attempt refunded, retried on return");
                mLoadingVideoId = null;
                mLoadingSinceMs = 0L;
                resetReopen();
                // 2470: the launch was held back, so the room song still is not
                // playing — a later retry must stay allowed to open it.
                mResumeAfterReturn = true;
            }
        }
    }

    /**
     * Re-opens the room's current song when nothing actually plays it while a
     * song is active — e.g. the engine was released by re-entering the join
     * screen mid-song, or no player exists at all (join screen in the
     * foreground: openVideo starts PlaybackActivity through the gated
     * ViewManager.startView). Without this the client keeps the (deduplicated)
     * song id, nothing plays, heartbeats stop and "ended" is never sent: the
     * room queue stalls until a phone skips the song. Bounded by the same
     * {@link #beginReopen} cap as mismatch reopens, never reopens a video whose
     * "ended" was already reported, never duplicates a load that is still in
     * flight, and — except for a resume after the app's return (see
     * {@link #mResumeAfterReturn}) — never opens a song id that only a "state"
     * snapshot provided while the app is in the foreground (its "play" message
     * stays the only instruction to open a video).
     *
     * Build 2467 failure this must survive: the in-memory markers
     * ({@link #mLoadedVideoId}/{@link #mLoadingVideoId}) and the presenter's
     * own player view/video keep the released song's id long after the engine
     * was released (Home), so a guard on the marker alone reads "already
     * loaded/loading" and silently returns — and the room stays dead on every
     * re-entry. A released engine / no live player is ALWAYS "not playing",
     * regardless of stale markers: only a live engine with the room's current
     * song loaded counts as playing, and a stale marker is cleared (logged)
     * before the resume proceeds.
     *
     * Build 2469 (the 2468 gazebo failure this must also survive): every
     * branch of the decision is logged — a silent return is impossible — and
     * stale reopen counters/markers from before the engine release can never
     * block the relaunch (a dead engine's counters describe a player that no
     * longer exists, so they are reset before the reopen decision).
     *
     * Deferred while the app is backgrounded (issue #3): opening the video would
     * launch/foreground the player over whatever the user is watching. The check
     * re-runs (in the foreground) when the user returns: re-entering the join
     * screen notifies room listeners, resuming the player view calls it again,
     * and the join screen's bounded retry (see JukeboxJoinActivity) keeps asking
     * for a while so a single mistimed check can never leave the room silent.
     *
     * @return true while a later retry could still succeed on its own (deferred:
     *         app backgrounded, no context yet); false when retrying cannot help
     *         and the join screen's retry loop should stop asking (song loaded
     *         in a live player, reopen dispatched, idle room, song ended,
     *         snapshot-sourced song, or the reopen cap reached — logged once per
     *         song).
     */
    private boolean maybeResumeRoomSong() {
        return maybeResumeRoomSong(null);
    }

    /** @param via caller context for the decision logs (e.g. the join screen retry), or null. */
    private boolean maybeResumeRoomSong(String via) {
        if (!JukeboxRoom.isRoomActive()) {
            logResume(via, "resume skipped: room inactive (no room to resume)");
            return false;
        }
        if (mSuppressReports) {
            logResume(via, "resume deferred: an openVideo dispatch is on the stack right now");
            return true; // transient, ask again later
        }
        Context ctx = getContext();
        if (ctx == null) {
            logResume(via, "resume deferred: no context yet");
            return true;
        }
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        String roomVideoId = room.getCurrentVideoId();
        if (mLoadedVideoId != null) {
            if (hasLiveEngine() && mLoadedVideoId.equals(roomVideoId)) {
                // The room's current song is really loaded in a live player: the
                // release never happened (or a new engine loaded it again).
                mResumeAfterReturn = false;
                logResume(via, "resume skipped: room song "
                        + mLoadedVideoId + " loaded in a live player");
                return false;
            }
            // Stale marker: the engine was released (Home) or the room moved on to
            // another song while the app sat backgrounded — the player view and
            // the presenter's video keep the old song's id long after the release,
            // so the marker alone must never read as "already playing" (build
            // 2467: every re-entry check silently returned here, no player ever
            // opened and the room stayed silent).
            logResume(via, "resume: loaded marker " + mLoadedVideoId + " is stale ("
                    + (hasLiveEngine() ? "room song changed" : "engine released/no live player")
                    + "): resuming the room song");
            setLoadedVideoId(null);
            mLoadingVideoId = null;
            mLoadingSinceMs = 0L;
            resetReopen();
        }
        if (!JukeboxForeground.isAppInForeground()) {
            // The song opens when the user returns; only state has been updated.
            mResumeAfterReturn = true; // 2470: the return resume may open a snapshot-sourced id, too
            logResume(via, "resume deferred: app backgrounded (song opens on the user's return)");
            return true;
        }
        if (!hasLiveEngine()) {
            // No live engine: every reopen counter/marker left over from the
            // previous engine generation describes a player that no longer
            // exists. Reset them (2469) so stale counters from before the
            // release can never block the relaunch below.
            resetReopen();
        }
        if (roomVideoId == null) {
            logResume(via, "resume skipped: idle room (no current song)");
            return false;
        }
        if (roomVideoId.equals(mEndedVideoId)) {
            logResume(via, "resume skipped: room song " + roomVideoId
                    + " already ended and was reported");
            return false;
        }
        if (roomVideoId.equals(mLoadingVideoId)) {
            // A young marker is a normal pending load. onNewVideo sets it from
            // inside openVideo, before the player activity has attached
            // (JukeboxRoom opens the video before it notifies listeners, and
            // that resume must already see the load). "No player yet" is that
            // window, not a failed open — treating it as stale opens the same
            // video a second time and cancels the load. A launch that never
            // started a player clears the marker itself (see
            // clearLoadThatNeverStarted). Only age past the timeout is stale.
            if (isLoadInFlight()) {
                logResume(via, "resume skipped: load of " + roomVideoId + " in flight");
                return false;
            }
            logResume(via, "resume: in-flight marker " + roomVideoId + " is stale (no load finished within "
                    + LOAD_IN_FLIGHT_TIMEOUT_MS + "ms): resuming the room song");
            mLoadingVideoId = null;
            mLoadingSinceMs = 0L;
        }
        if (room.isSongFromState()) {
            if (via == null && !mResumeAfterReturn) {
                // Fresh foreground snapshot (initial join / live player): the
                // song's own "play" message stays the only instruction to open
                // its video (2466 rule, unchanged).
                logResume(via, "resume deferred: song id came from a state snapshot,"
                        + " waiting for its own play");
                return false;
            }
            // 2470 resume-after-return (join screen re-entry/retry ticks, or a
            // resume that was deferred while the app was backgrounded or over an
            // engine release): the app is in the foreground now, a room song is
            // active and nothing live plays it — open it directly instead of
            // keeping the returned app stuck on the join screen (2469 steps 2/3).
            logResume(via, "resume after return: opening snapshot-sourced room song " + roomVideoId);
            mResumeAfterReturn = false;
        } else {
            mResumeAfterReturn = false; // play-sourced song: no return resume pending
        }
        if (!beginReopen(roomVideoId)) {
            // Already opening it, or the reopen cap for this song is exhausted.
            // Log once per song: the join screen retry stops on this answer, but
            // room events keep re-running the check.
            if (!roomVideoId.equals(mCapLoggedVideoId)) {
                logResume(via, "resume deferred: reopen cap reached for " + roomVideoId);
                mCapLoggedVideoId = roomVideoId;
            }
            return false;
        }
        // Cheap seek hint (2470): the last position observed/reported for this
        // exact videoId, so a resumed room song continues where it left off.
        openRoomVideo(roomVideoId, "room song active but player idle" + viaSuffix(via),
                pendingPosMs(roomVideoId));
        return false;
    }

    /**
     * True while a player view exists with its engine initialized — a load can
     * actually be playing right now. The presenter's player view (and its
     * video) outlives the engine: Home releases the engine while the activity
     * objects remain, so only this — never a stale id — can tell "loaded and
     * able to play" from "marker left over after the engine was released".
     */
    private boolean hasLiveEngine() {
        PlaybackView player = getPlayer();
        return player != null && player.isEngineInitialized();
    }

    /** True while the in-flight load marker is young enough to still be a real load. */
    private boolean isLoadInFlight() {
        long since = mLoadingSinceMs;
        return since != 0L && SystemClock.elapsedRealtime() - since <= LOAD_IN_FLIGHT_TIMEOUT_MS;
    }

    /**
     * Drops the in-flight marker when {@code openVideo} did not start a player
     * (the launch gate blocked it). {@code onNewVideo} has already set the
     * marker, and no {@code onVideoLoaded} will ever clear it. A young marker
     * must not be cleared just because the player activity has not attached
     * yet — that is a normal pending load.
     */
    void clearLoadThatNeverStarted() {
        mLoadingVideoId = null;
        mLoadingSinceMs = 0L;
    }

    /**
     * Remembers the last observed playback position of {@code videoId} (>= 0),
     * so a resume after return can seek the reopened song back to the room's
     * position (cheap: no protocol round trip).
     */
    private void rememberProgress(String videoId, long posMs) {
        if (videoId != null && posMs >= 0) {
            mLastPosVideoId = videoId;
            mLastPosMs = posMs;
        }
    }

    /**
     * Pending seek position for a resume of {@code videoId}, or -1 when none is
     * known. The room-state estimate wins whenever it names this videoId
     * (issue #24): serverNow − startedAt, frozen at pausedAt while paused,
     * plus elapsed time since the snapshot. The player's own last tick is only
     * the fallback when that estimate is unknown (no snapshot, idle, or no
     * startedAt) — and that tick is dropped when it is inside the end margin
     * of a known duration (player duration, else the snapshot's), the same
     * {@link JukeboxRoomClient#isAtEnd} rule as the room estimate. A cold
     * start with no local memory still seeks via the play-path open, which
     * uses the same estimate.
     */
    private long pendingPosMs(String roomVideoId) {
        if (roomVideoId == null) {
            return -1L;
        }
        Context ctx = getContext();
        JukeboxRoom room = ctx != null && JukeboxRoom.isRoomActive() ? JukeboxRoom.instance(ctx) : null;
        if (room != null) {
            long roomPos = room.getRoomPositionMs(roomVideoId);
            if (roomPos >= 0) {
                return roomPos;
            }
        }
        if (roomVideoId.equals(mLastPosVideoId) && mLastPosMs >= 0) {
            long durMs = -1L;
            PlaybackView player = getPlayer();
            if (player != null && player.getDurationMs() > 0L) {
                durMs = player.getDurationMs();
            } else if (room != null) {
                durMs = room.getSnapshotDurationMs(roomVideoId);
            }
            if (JukeboxRoomClient.isAtEnd(mLastPosMs, durMs)) {
                return -1L;
            }
            return mLastPosMs;
        }
        return -1L;
    }

    /**
     * Logs a resume decision. Every branch of the resume check goes through
     * here (build 2469: a silent return must be impossible — the 2468 gazebo
     * failure showed decision paths that left no trace). An immediately
     * repeated identical line is skipped so room-event re-runs don't spam, but
     * the first occurrence of every decision is always logged.
     */
    private void logResume(String via, String msg) {
        String line = msg + viaSuffix(via);
        if (line.equals(mLastResumeLog)) {
            return;
        }
        mLastResumeLog = line;
        JukeboxLog.i(LOG_TAG, line);
    }

    private static String viaSuffix(String via) {
        return via != null && !via.isEmpty() ? " (via " + via + ")" : "";
    }

    /**
     * Public entry for the join screen's bounded return-retry (and anything else
     * that knows the user just came back): runs the idle resume check with a
     * caller tag so the decision is verifiable in logcat. Fully logged (2469):
     * the room-inactive answer is logged too, so a missing room can never be a
     * silent no-op.
     *
     * @return whether the retry loop should keep asking (see
     *         {@link #maybeResumeRoomSong(String)}); false stops it — e.g. once the
     *         reopen cap is reached, so the cap deferral is logged exactly once
     *         instead of on every remaining tick.
     */
    public boolean resumeRoomSongIfNeeded(String via) {
        return maybeResumeRoomSong(via);
    }

    /**
     * Reopening the app (or otherwise resuming the player view) with the room's
     * song loaded must leave that song actually playing: SmartTube's own state
     * restore can load it paused (e.g. a saved playWhenReady=false applied on
     * load), which used to require a DPAD_CENTER press. While a room song is
     * active the TV is a listener — the room's song plays.
     *
     * <p>A snapshot that is still paused is the host's pause. Leave the player
     * paused at {@code pausedAt - startedAt}. This method is the unpause: it
     * runs again on the next view resume, after a snapshot that is not paused.
     */
    private void ensureRoomSongPlaying() {
        if (!JukeboxRoom.isRoomActive() || mSuppressReports) {
            return;
        }
        PlaybackView player = getPlayer();
        Context ctx = getContext();
        if (player == null) {
            JukeboxLog.i(LOG_TAG, "room song play skipped: player==null");
            return;
        }
        if (ctx == null) {
            return;
        }
        JukeboxRoom room = JukeboxRoom.instance(ctx);
        String roomVideoId = room.getCurrentVideoId();
        if (roomVideoId == null || !roomVideoId.equals(mLoadedVideoId)) {
            return; // nothing loaded, or not the room's song
        }
        if (room.isSnapshotPaused(roomVideoId)) {
            return; // host paused this song; view resume must not unpause it
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
