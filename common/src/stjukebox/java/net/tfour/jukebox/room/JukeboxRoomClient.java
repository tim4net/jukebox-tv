package net.tfour.jukebox.room;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Jukebox room state machine, independent of any concrete networking stack (see
 * {@link Transport}, {@link Http}) or timing facility (see {@link Scheduler}), so it can
 * be driven synchronously from unit tests.
 *
 * Room order is strict: the TV opens each song itself whenever the server sends "play";
 * a "play" for the songId that is already current never re-opens the video.
 */
public class JukeboxRoomClient {
    private static final String LOG_TAG = "JukeboxRoom";

    public interface Transport {
        void connect(String url, Callbacks cb);
        boolean send(String text);
        void close();

        interface Callbacks {
            void onOpen();
            void onMessage(String text);
            void onClosed();
            void onFailure(Throwable error);
        }
    }

    public interface Http {
        String postJson(String url, String body) throws IOException;
        String get(String url) throws IOException;
    }

    public interface Scheduler {
        void schedule(Runnable r, long delayMs);
        void cancelAll();
    }

    /** Tiny key/value persistence. A null value removes the key. */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public interface Listener {
        void onRoomReady(String room, String code, String joinUrl);
        void onPlay(String songId, String videoId, String title);
        void onStatus(String status);
        void onState(String nowPlayingTitle, int listeners);

        /** Host popups setting carried by the latest "state" message (strict boolean, default off). */
        default void onPopups(boolean enabled) {
        }
    }

    /** Thrown by {@link Http} implementations for non-2xx HTTP responses. */
    public static class HttpStatusException extends IOException {
        public final int code;

        public HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final String KEY_ROOM = "room";
    private static final String KEY_CODE = "code";
    private static final long PING_INTERVAL_MS = 25_000;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final int MAX_REMEMBERED_SONGS = 64;
    /**
     * A position this close to {@code durationMs} (or past it) is unknown.
     * Callers must not seek to the end. Same 2s as the web listen-along
     * drift tolerance.
     */
    private static final long END_MARGIN_MS = 2_000L;

    private final String mOrigin;
    private final Transport mTransport;
    private final Http mHttp;
    private final Scheduler mScheduler;
    private final Store mStore;
    private final Listener mListener;
    private final String mToken;

    private final Runnable mPingRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (JukeboxRoomClient.this) {
                if (mRunning && mConnected && mTransport.send(JukeboxProtocol.ping(System.currentTimeMillis()))) {
                    mScheduler.schedule(this, PING_INTERVAL_MS);
                }
            }
        }
    };
    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (JukeboxRoomClient.this) {
                if (mRunning) {
                    connectSocket();
                }
            }
        }
    };
    private final Runnable mSetupRetryRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (JukeboxRoomClient.this) {
                if (!mRunning || mSetupInProgress) {
                    return;
                }
            }
            // setupRoom() does blocking HTTP: run it without holding the lock.
            setupRoom();
        }
    };

    // mRunning/mConnected/mCurrentVideoId/mSongFromState are volatile and read
    // without the lock so main-thread getters never block on the setup HTTP done
    // under the lock below.
    private volatile boolean mRunning;
    private volatile boolean mConnected;
    private volatile String mCurrentVideoId;
    private boolean mConnecting;
    private boolean mSetupInProgress;
    private boolean mSocketDone;
    private int mGeneration;
    private long mBackoffMs = INITIAL_BACKOFF_MS;
    private String mRoom;
    private String mCode;
    private String mLastStatus;
    private String mCurrentSongId;
    /** True while the current song id came from a "state" snapshot, not a "play". */
    private volatile boolean mSongFromState;
    /**
     * Room position of the song the latest "state" snapshot names (issue #6).
     * One immutable record behind one volatile: a reader on the Android main
     * thread never sees one snapshot's video id with another's startedAt or
     * pausedAt. {@link RoomPosition#NONE} means unknown/idle.
     */
    private volatile RoomPosition mPosition = RoomPosition.NONE;
    /**
     * Test-only. Runs while a position record is being published, before that
     * record is stored. Null in production. A reader here still sees the
     * previous complete snapshot (the new video id is not yet visible). Not
     * invoked on the clear path. Reset from the test's {@code @After}.
     */
    private static volatile Runnable sDuringPositionPublishForTests;
    /** Song ids for which "ended" was already accepted by the socket. */
    private final Set<String> mEndedSentSongIds = new LinkedHashSet<>();
    private String mPendingEndSongId;
    private boolean mAwaitPlayAfterReconnect;

    public JukeboxRoomClient(String origin, Transport transport, Http http, Scheduler scheduler,
                             Store store, Listener listener, String token) {
        mOrigin = origin;
        mTransport = transport;
        mHttp = http;
        mScheduler = scheduler;
        mStore = store;
        mListener = listener;
        mToken = token;
    }

    /**
     * Idempotent and self-healing. Reuses the saved room when its state check
     * succeeds, otherwise creates (and saves) a new room, then connects the
     * WebSocket. When called again while already running, a live connection is kept
     * as is; an in-flight room setup or WebSocket handshake is never interrupted; a
     * dead socket (room known) is reconnected immediately (no backoff wait); a
     * missing room (initial setup failed or the room was closed) is set up again
     * right away so the client can never end up wedged with nothing scheduled.
     * Network calls run on the caller's thread; do not call this on the Android
     * main thread.
     */
    public void start() {
        boolean setupNow = false;
        synchronized (this) {
            if (mRunning) {
                if (mConnected) {
                    JukeboxLog.i(LOG_TAG, "start: reusing live connection room=" + mRoom);
                    if (mRoom != null) {
                        notifyRoomReadyLocked();
                    }
                    notifyStatusLocked("Connected");
                    return;
                }
                if (mSetupInProgress) {
                    // Room setup (blocking HTTP) is already running; let it finish.
                    JukeboxLog.i(LOG_TAG, "start: room setup already in progress, waiting");
                    return;
                }
                if (mConnecting) {
                    // A WebSocket handshake is in flight; don't cancel and restart it.
                    JukeboxLog.i(LOG_TAG, "start: websocket handshake in flight, waiting room=" + mRoom);
                    return;
                }
                mBackoffMs = INITIAL_BACKOFF_MS;
                mScheduler.cancelAll();
                if (mRoom == null) {
                    // Initial setup failed (retry pending or nothing scheduled) or the
                    // room was closed: connectSocket() would be a no-op, so run the
                    // room setup again instead of wedging the client.
                    JukeboxLog.i(LOG_TAG, "start: no room, (re)running setup now");
                    setupNow = true;
                } else {
                    JukeboxLog.i(LOG_TAG, "start: socket not live, reconnecting now room=" + mRoom);
                    connectSocket();
                    return;
                }
            } else {
                mRunning = true;
                mBackoffMs = INITIAL_BACKOFF_MS;
                mLastStatus = "Connecting…";
                mScheduler.cancelAll();
                setupNow = true;
            }
        }
        // setupRoom() does blocking HTTP: never hold the lock across it, or
        // main-thread getters would block on network timeouts.
        if (setupNow) {
            setupRoom();
        }
    }

    /** Closes everything; no reconnects are scheduled afterwards. */
    public synchronized void stop() {
        mRunning = false;
        mGeneration++;
        mSocketDone = true;
        mConnected = false;
        mConnecting = false;
        mScheduler.cancelAll();
        mTransport.close();
        clearSongLocked();
        mPendingEndSongId = null;
        mAwaitPlayAfterReconnect = false;
        notifyStatusLocked("Stopped");
    }

    /** Forgets the current song without tearing the socket down. */
    public synchronized void clearCurrentSong() {
        clearSongLocked();
    }

    /**
     * Reports that the current song ended. Only acts when videoId matches the current
     * song's videoId and "ended" hasn't been sent for it yet. If the socket is down, the
     * ended songId is remembered and "ended" is sent after reconnect when the server's
     * post-ack "play" is still for that same songId. Never sends "ended" twice per songId.
     */
    public synchronized void songEnded(String videoId) {
        if (!mRunning || mCurrentSongId == null) {
            return;
        }
        if (videoId == null || !videoId.equals(mCurrentVideoId)) {
            return;
        }
        if (mEndedSentSongIds.contains(mCurrentSongId)) {
            return;
        }
        if (!sendEndedLocked(mCurrentSongId, mCurrentVideoId)) {
            mPendingEndSongId = mCurrentSongId;
        }
    }

    /** Reports playback state on load/play/pause; ignored unless videoId is the current song. */
    public synchronized void reportPlaying(String videoId, Integer durationSec, Integer positionSec, String state) {
        if (!isCurrentSongLocked(videoId)) {
            return;
        }
        mTransport.send(JukeboxProtocol.playing(
                mToken, mCurrentSongId, mCurrentVideoId, durationSec, positionSec, state));
    }

    /** ~5s position heartbeat; ignored unless videoId is the current song. */
    public synchronized void reportProgress(String videoId, Integer durationSec, Integer positionSec) {
        if (!isCurrentSongLocked(videoId)) {
            return;
        }
        mTransport.send(JukeboxProtocol.progress(
                mToken, mCurrentSongId, mCurrentVideoId, durationSec, positionSec));
    }

    // Lock-free volatile reads: these are called from the Android main thread while
    // the room thread may be setting up the room (blocking HTTP) or connecting.
    public boolean isConnected() {
        return mConnected;
    }

    /** True between {@link #start()} and {@link #stop()} (the socket may still be down). */
    public boolean isRunning() {
        return mRunning;
    }

    /** Video id of the song the room currently wants playing, or null. */
    public String getCurrentVideoId() {
        return mCurrentVideoId;
    }

    /**
     * Test-only hook for {@code sDuringPositionPublishForTests}. Pass null
     * when the test is done (and from {@code @After}) so later tests see a
     * normal publish.
     */
    public static void setDuringPositionPublishForTests(Runnable hook) {
        sDuringPositionPublishForTests = hook;
    }

    /** True while the current song id came from a "state" snapshot, not a "play" (lock-free). */
    public boolean isSongFromState() {
        return mSongFromState;
    }

    /**
     * Estimated room playback position of {@code videoId} — the song the latest
     * "state" snapshot names. {@code (paused && pausedAt ? pausedAt : serverNow)
     * - startedAt}. The server clock advances by {@link SystemClock#elapsedRealtime()}
     * since the snapshot arrived, so a wall-clock step does not move the song.
     * A paused room stays frozen at pausedAt - startedAt.
     *
     * <p>{@code -1} (unknown; callers keep their usual behavior) when there is
     * no snapshot, the room is idle, the video id does not match, the snapshot
     * omitted {@code now}, the estimate is negative, or it is at or past
     * {@code durationMs} minus {@link #END_MARGIN_MS}. Lock-free: one volatile
     * read, safe from the Android main thread.
     */
    public long roomPositionMs(String videoId) {
        RoomPosition snap = mPosition;
        if (snap.videoId == null || videoId == null || !snap.videoId.equals(videoId)) {
            return -1L;
        }
        if (snap.startedAtMs <= 0L || snap.serverNowMs <= 0L) {
            return -1L; // no startedAt, or the server sent no "now"
        }
        long elapsedMs = SystemClock.elapsedRealtime() - snap.receivedAtElapsedMs;
        if (elapsedMs < 0L) {
            elapsedMs = 0L;
        }
        long serverNowMs = snap.serverNowMs + elapsedMs;
        long endMs = snap.paused && snap.pausedAtMs > 0L ? snap.pausedAtMs : serverNowMs;
        long posMs = endMs - snap.startedAtMs;
        if (posMs < 0L) {
            return -1L;
        }
        if (snap.durationMs > 0L && posMs >= snap.durationMs - END_MARGIN_MS) {
            return -1L; // at or past the end: do not seek there
        }
        return posMs;
    }

    /**
     * True when the latest position snapshot names {@code videoId} and that
     * song is paused. A later snapshot that is not paused answers false, so a
     * view-resume can unpause. Lock-free, same record as {@link #roomPositionMs}.
     */
    public boolean isSnapshotPaused(String videoId) {
        RoomPosition snap = mPosition;
        return videoId != null && videoId.equals(snap.videoId) && snap.paused;
    }

    // Internal machinery. The *Locked helpers below expect the instance lock to be
    // held; setupRoom() must run WITHOUT the lock (it does blocking HTTP).

    private void setupRoom() {
        String room;
        String code;
        synchronized (this) {
            if (!mRunning || mSetupInProgress) {
                return;
            }
            mSetupInProgress = true;
            room = mStore.get(KEY_ROOM);
            code = mStore.get(KEY_CODE);
        }
        if (room != null && room.trim().isEmpty()) {
            room = null;
        }
        if (room != null) {
            try {
                // Blocking HTTP: no lock held, so main-thread getters never block.
                String state = mHttp.get(mOrigin + "/api/r/" + room + "/state");
                if (JukeboxProtocol.isClosedState(state)) {
                    room = null;
                }
            } catch (HttpStatusException e) {
                if (e.code == 404 || e.code == 410) {
                    room = null; // the server proved the room is gone
                }
            } catch (IOException e) {
                // Network failure without a verdict: keep the saved room and let the
                // WebSocket connection (and its backoff reconnects) sort it out.
            }
        }
        if (room == null) {
            try {
                // Blocking HTTP: no lock held, so main-thread getters never block.
                String created = mHttp.postJson(mOrigin + "/api/rooms", "{}");
                String[] roomAndCode = JukeboxProtocol.parseRoomCreated(created);
                if (roomAndCode == null) {
                    throw new IOException("Malformed create-room response");
                }
                room = roomAndCode[0];
                code = roomAndCode[1];
                mStore.put(KEY_ROOM, room);
                mStore.put(KEY_CODE, code);
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    mSetupInProgress = false;
                    scheduleRetryLocked(mSetupRetryRunnable);
                }
                return;
            }
        }
        synchronized (this) {
            mSetupInProgress = false;
            if (!mRunning) {
                return; // stop() raced the setup: discard its result
            }
            mRoom = room;
            mCode = code;
            notifyRoomReadyLocked();
            connectSocket();
        }
    }

    private void connectSocket() {
        if (!mRunning || mRoom == null) {
            return;
        }
        if (mConnected || mConnecting) {
            return; // the socket is already live or a handshake is already in flight
        }
        final int generation = ++mGeneration;
        mSocketDone = false;
        mConnected = false;
        mConnecting = true;
        notifyStatusLocked("Connecting…");
        String url = webSocketUrlLocked();
        JukeboxLog.i(LOG_TAG, "ws connect url=" + url + " room=" + mRoom);
        mTransport.connect(url, new Transport.Callbacks() {
            @Override
            public void onOpen() {
                synchronized (JukeboxRoomClient.this) {
                    if (generation == mGeneration) {
                        socketOpenedLocked();
                    }
                }
            }

            @Override
            public void onMessage(String text) {
                synchronized (JukeboxRoomClient.this) {
                    if (generation == mGeneration) {
                        handleMessageLocked(text);
                    }
                }
            }

            @Override
            public void onClosed() {
                socketClosed(generation);
            }

            @Override
            public void onFailure(Throwable error) {
                socketClosed(generation);
            }
        });
    }

    private void socketOpenedLocked() {
        mConnecting = false;
        mConnected = true;
        mBackoffMs = INITIAL_BACKOFF_MS;
        notifyStatusLocked("Connected");
        mTransport.send(JukeboxProtocol.bridgeHello(mToken));
        mScheduler.cancelAll();
        mScheduler.schedule(mPingRunnable, PING_INTERVAL_MS);
        if (mPendingEndSongId != null) {
            mAwaitPlayAfterReconnect = true;
        }
    }

    private void socketClosed(int generation) {
        synchronized (JukeboxRoomClient.this) {
            if (generation != mGeneration || mSocketDone) {
                return;
            }
            mSocketDone = true;
            mConnected = false;
            mConnecting = false;
            scheduleRetryLocked(mReconnectRunnable);
        }
    }

    private void scheduleRetryLocked(Runnable retry) {
        if (!mRunning) {
            return;
        }
        notifyStatusLocked("Reconnecting…");
        JukeboxLog.i(LOG_TAG, "reconnect backoff=" + mBackoffMs + "ms");
        mScheduler.cancelAll();
        mScheduler.schedule(retry, mBackoffMs);
        mBackoffMs = Math.min(mBackoffMs * 2, MAX_BACKOFF_MS);
    }

    private void handleMessageLocked(String text) {
        JukeboxProtocol.Message message = JukeboxProtocol.parse(text);
        String type = message.getType();
        if (JukeboxProtocol.TYPE_PLAY.equals(type)) {
            JukeboxLog.i(LOG_TAG, "play vid=" + message.getVideoId() + " song=" + message.getSongId());
            handlePlayLocked(message);
        } else if (JukeboxProtocol.TYPE_STATE.equals(type)) {
            logStateMessage(text, message);
            captureRoomPositionLocked(message);
            if (message.isNowPlayingNull()) {
                clearSongLocked();
            } else {
                adoptStateSongLocked(message);
            }
            mListener.onState(message.getNowPlayingTitle(), message.getListeners());
            mListener.onPopups(message.isPopupsEnabled());
        } else if (JukeboxProtocol.TYPE_BRIDGE_ACK.equals(type)) {
            JukeboxLog.i(LOG_TAG, "bridge-ack");
        } else if (JukeboxProtocol.TYPE_CLOSED.equals(type)) {
            handleClosedLocked();
        }
        // pong, error and unknown messages need no action.
    }

    /**
     * Logs a "state" message: popups flag, displayMode when present (parsed here for
     * logging only) and the nowPlaying videoId. Never logs the bridge token.
     */
    private static void logStateMessage(String text, JukeboxProtocol.Message message) {
        String nowPlaying = nowPlayingVideoId(text);
        StringBuilder line = new StringBuilder("state: popups=").append(message.isPopupsEnabled());
        String displayMode = optStateString(text, "displayMode");
        if (displayMode != null) {
            line.append(" displayMode=").append(displayMode);
        }
        line.append(" nowPlaying=").append(nowPlaying != null ? nowPlaying : "none");
        JukeboxLog.i(LOG_TAG, line.toString());
    }

    /** Top-level or settings.{key} string value of a "state" message, or null (logging only). */
    private static String optStateString(String text, String key) {
        try {
            JSONObject json = new JSONObject(text);
            Object value = json.opt(key);
            JSONObject settings = json.optJSONObject("settings");
            if (value == null && settings != null) {
                value = settings.opt(key);
            }
            return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String nowPlayingVideoId(String text) {
        try {
            JSONObject nowPlaying = new JSONObject(text).optJSONObject("nowPlaying");
            if (nowPlaying == null) {
                return null;
            }
            Object videoId = nowPlaying.opt("videoId");
            return videoId == null || videoId == JSONObject.NULL ? null : String.valueOf(videoId);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Records the room position of the song a "state" snapshot names (issue #6):
     * nowPlaying startedAt/paused/pausedAt/durationMs plus the snapshot's own
     * server clock. A snapshot without position data (or an idle room) clears
     * the record — {@link #roomPositionMs(String)} then answers -1 and callers
     * keep their usual behavior. The test hook runs before the new record is
     * visible and is not used on this clear path.
     */
    private void captureRoomPositionLocked(JukeboxProtocol.Message message) {
        if (message.isNowPlayingNull()
                || !JukeboxProtocol.isValidVideoId(message.getNowPlayingVideoId())
                || message.getNowPlayingStartedAtMs() == null) {
            clearRoomPositionLocked();
            return;
        }
        RoomPosition next = new RoomPosition(
                message.getNowPlayingVideoId(),
                message.isNowPlayingPaused(),
                message.getNowPlayingStartedAtMs(),
                message.getNowPlayingPausedAtMs() != null ? message.getNowPlayingPausedAtMs() : 0L,
                message.getNowPlayingDurationMs() != null ? message.getNowPlayingDurationMs() : 0L,
                message.getServerNowMs() != null ? message.getServerNowMs() : 0L,
                SystemClock.elapsedRealtime());
        Runnable duringPublish = sDuringPositionPublishForTests;
        if (duringPublish != null) {
            duringPublish.run();
        }
        mPosition = next;
    }

    /** Drops the position record in one store. No test hook. */
    private void clearRoomPositionLocked() {
        mPosition = RoomPosition.NONE;
    }

    private void handlePlayLocked(JukeboxProtocol.Message message) {
        String songId = message.getSongId();
        if (songId == null || !JukeboxProtocol.isValidVideoId(message.getVideoId())) {
            return; // ignore malformed play messages
        }
        if (mAwaitPlayAfterReconnect) {
            mAwaitPlayAfterReconnect = false;
            String endedSongId = mPendingEndSongId;
            mPendingEndSongId = null;
            if (songId.equals(endedSongId)) {
                // The server never saw our "ended" (socket was down): advance now.
                sendEndedLocked(songId, message.getVideoId());
                return;
            }
            // Otherwise the server already advanced on its own; drop the pending end.
        }
        if (songId.equals(mCurrentSongId)) {
            if (!mSongFromState) {
                return; // deduplicate: never re-open the song that is already current
            }
            // The id was adopted from a "state" snapshot, not from a "play":
            // let this play through so the TV actually opens the song.
        }
        mSongFromState = false; // an accepted "play" always makes the song play-sourced
        mCurrentSongId = songId;
        mCurrentVideoId = message.getVideoId();
        mListener.onPlay(songId, message.getVideoId(), message.getTitle());
    }

    /**
     * A "state" snapshot can arrive before "play" (or the "play" can be deduplicated
     * on reconnect): while no song is current — or the current song already ended and
     * was reported — record nowPlaying's videoId/songId so popups/overlay wiring sees
     * the live videoId right away. A "play" message stays the only instruction to
     * open a video: while a play-sourced, un-ended song is current the snapshot is
     * ignored (play is authoritative), and the first play for a state-sourced song
     * still opens the video. A snapshot for a song that already ended (and was
     * reported) is never adopted, so it can never be replayed.
     */
    private void adoptStateSongLocked(JukeboxProtocol.Message message) {
        if (mCurrentSongId != null && !mSongFromState
                && !mEndedSentSongIds.contains(mCurrentSongId)) {
            return; // "play" is authoritative while a play-sourced song hasn't ended
        }
        String songId = message.getNowPlayingSongId();
        if (songId == null || songId.trim().isEmpty()) {
            return; // without a songId there is nothing to adopt
        }
        if (mEndedSentSongIds.contains(songId)) {
            return; // never re-adopt a song that already ended and was reported
        }
        String videoId = message.getNowPlayingVideoId();
        if (!JukeboxProtocol.isValidVideoId(videoId)) {
            return; // without the room's exact videoId there is nothing to adopt
        }
        mCurrentVideoId = videoId;
        mCurrentSongId = songId;
        mSongFromState = true;
    }

    private void handleClosedLocked() {
        mGeneration++; // ignore any further callbacks of the dying socket
        mSocketDone = true;
        mConnected = false;
        mConnecting = false;
        mScheduler.cancelAll();
        mTransport.close();
        mStore.put(KEY_ROOM, null);
        mStore.put(KEY_CODE, null);
        // Forget the in-memory ids too: start()/retry must set up a NEW room,
        // not reconnect the stale closed one (connectSocket() with a dead mRoom).
        mRoom = null;
        mCode = null;
        clearSongLocked();
        mPendingEndSongId = null;
        mAwaitPlayAfterReconnect = false;
        if (mRunning) {
            notifyStatusLocked("Reconnecting…");
            // We are on the WebSocket thread holding the lock; setupRoom() does blocking
            // HTTP, so hop to the scheduler thread instead of running it inline.
            mScheduler.schedule(mSetupRetryRunnable, 0);
        }
    }

    private void clearSongLocked() {
        mCurrentSongId = null;
        mCurrentVideoId = null;
        mSongFromState = false;
        // The song (and with it any recorded room position) is gone: a later
        // snapshot re-records it. Covers stop()/closed/song-clear paths.
        clearRoomPositionLocked();
    }

    private boolean isCurrentSongLocked(String videoId) {
        return mRunning && mCurrentSongId != null && videoId != null && videoId.equals(mCurrentVideoId);
    }

    /** @return false when the socket did not accept the message (caller may retry). */
    private boolean sendEndedLocked(String songId, String videoId) {
        if (mEndedSentSongIds.contains(songId)) {
            return true; // never send ended twice for one songId
        }
        if (!mTransport.send(JukeboxProtocol.ended(mToken, songId, videoId))) {
            return false;
        }
        mEndedSentSongIds.add(songId);
        while (mEndedSentSongIds.size() > MAX_REMEMBERED_SONGS) {
            Iterator<String> it = mEndedSentSongIds.iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    private String webSocketUrlLocked() {
        String url = mOrigin;
        if (url.startsWith("https:")) {
            url = "wss:" + url.substring("https:".length());
        } else if (url.startsWith("http:")) {
            url = "ws:" + url.substring("http:".length());
        }
        return url + "/api/r/" + mRoom + "/ws";
    }

    private void notifyRoomReadyLocked() {
        mListener.onRoomReady(mRoom, mCode, mOrigin + "/r/" + mRoom);
    }

    private void notifyStatusLocked(String status) {
        mLastStatus = status;
        mListener.onStatus(status);
    }

    /**
     * One "state" snapshot's position fields. Immutable; published by a single
     * volatile write so readers cannot mix two snapshots.
     */
    private static final class RoomPosition {
        static final RoomPosition NONE = new RoomPosition(null, false, 0L, 0L, 0L, 0L, 0L);

        final String videoId;
        final boolean paused;
        final long startedAtMs;
        final long pausedAtMs;
        final long durationMs;
        /** Epoch ms from the snapshot's {@code now}. 0 when the server omitted it. */
        final long serverNowMs;
        /** {@link SystemClock#elapsedRealtime()} when this snapshot was received. */
        final long receivedAtElapsedMs;

        RoomPosition(String videoId, boolean paused, long startedAtMs, long pausedAtMs,
                     long durationMs, long serverNowMs, long receivedAtElapsedMs) {
            this.videoId = videoId;
            this.paused = paused;
            this.startedAtMs = startedAtMs;
            this.pausedAtMs = pausedAtMs;
            this.durationMs = durationMs;
            this.serverNowMs = serverNowMs;
            this.receivedAtElapsedMs = receivedAtElapsedMs;
        }
    }
}
