package net.tfour.jukebox.room;

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
                if (mRunning) {
                    setupRoom();
                }
            }
        }
    };

    private boolean mRunning;
    private boolean mConnected;
    private boolean mSocketDone;
    private int mGeneration;
    private long mBackoffMs = INITIAL_BACKOFF_MS;
    private String mRoom;
    private String mCode;
    private String mLastStatus;
    private String mCurrentSongId;
    private String mCurrentVideoId;
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
     * as is; a dead socket is reconnected immediately (no backoff wait) so that
     * re-entering the join screen heals a stalled client. Network calls run on the
     * caller's thread; do not call this on the Android main thread.
     */
    public synchronized void start() {
        if (mRunning) {
            if (mConnected) {
                JukeboxLog.i(LOG_TAG, "start: reusing live connection room=" + mRoom);
            } else {
                JukeboxLog.i(LOG_TAG, "start: socket not live, reconnecting now room=" + mRoom);
                mBackoffMs = INITIAL_BACKOFF_MS;
                mScheduler.cancelAll();
                connectSocket();
                return;
            }
            if (mRoom != null) {
                notifyRoomReadyLocked();
            }
            notifyStatusLocked(mConnected ? "Connected" : mLastStatus);
            return;
        }
        mRunning = true;
        mBackoffMs = INITIAL_BACKOFF_MS;
        mLastStatus = "Connecting…";
        mScheduler.cancelAll();
        setupRoom();
    }

    /** Closes everything; no reconnects are scheduled afterwards. */
    public synchronized void stop() {
        mRunning = false;
        mGeneration++;
        mSocketDone = true;
        mConnected = false;
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

    public synchronized boolean isConnected() {
        return mConnected;
    }

    /** True between {@link #start()} and {@link #stop()} (the socket may still be down). */
    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Video id of the song the room currently wants playing, or null. */
    public synchronized String getCurrentVideoId() {
        return mCurrentVideoId;
    }

    // Internal machinery. Everything below expects the instance lock to be held.

    private void setupRoom() {
        if (!mRunning) {
            return;
        }
        String room = mStore.get(KEY_ROOM);
        String code = mStore.get(KEY_CODE);
        if (room != null && room.trim().isEmpty()) {
            room = null;
        }
        if (room != null) {
            try {
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
                scheduleRetryLocked(mSetupRetryRunnable);
                return;
            }
        }
        mRoom = room;
        mCode = code;
        notifyRoomReadyLocked();
        connectSocket();
    }

    private void connectSocket() {
        if (!mRunning || mRoom == null) {
            return;
        }
        final int generation = ++mGeneration;
        mSocketDone = false;
        mConnected = false;
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
            if (message.isNowPlayingNull()) {
                clearSongLocked();
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
            return; // deduplicate: never re-open the song that is already current
        }
        mCurrentSongId = songId;
        mCurrentVideoId = message.getVideoId();
        mListener.onPlay(songId, message.getVideoId(), message.getTitle());
    }

    private void handleClosedLocked() {
        mGeneration++; // ignore any further callbacks of the dying socket
        mSocketDone = true;
        mConnected = false;
        mScheduler.cancelAll();
        mTransport.close();
        mStore.put(KEY_ROOM, null);
        mStore.put(KEY_CODE, null);
        clearSongLocked();
        mPendingEndSongId = null;
        mAwaitPlayAfterReconnect = false;
        if (mRunning) {
            notifyStatusLocked("Reconnecting…");
            mScheduler.cancelAll();
            // We are on the WebSocket thread holding the lock; setupRoom() does blocking
            // HTTP, so hop to the scheduler thread instead of running it inline.
            mScheduler.schedule(mSetupRetryRunnable, 0);
        }
    }

    private void clearSongLocked() {
        mCurrentSongId = null;
        mCurrentVideoId = null;
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
}
