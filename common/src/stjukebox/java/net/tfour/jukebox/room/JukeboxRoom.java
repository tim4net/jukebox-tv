package net.tfour.jukebox.room;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.OkHttpClient;

/**
 * Process-wide jukebox room (stjukebox flavor only). Owns the {@link JukeboxRoomClient}
 * wired to OkHttp, a background {@link HandlerThread} for room setup/reconnect work and
 * SharedPreferences persistence. "play" messages open the video through the regular
 * {@link PlaybackPresenter} on the main thread. The room keeps running when activities
 * come and go.
 *
 * {@link #start()} is idempotent and self-healing: a live connection is reused, a dead
 * client/thread/socket is rebuilt/reconnected, so re-entering the join screen can never
 * leave the room stalled. The {@link ClientFactory}/{@link Executor} seams exist for
 * unit tests only.
 */
public final class JukeboxRoom {
    private static final String LOG_TAG = "JukeboxRoom";

    public interface Listener {
        /** room/code/joinUrl changed (or became known). */
        void onRoomUpdated();
        void onStatusChanged(String status);
        void onStateChanged(String nowPlaying);

        /** Host popups setting changed (last "state" message). */
        default void onPopupsChanged(boolean enabled) {
        }
    }

    /** Builds the room client (transport stack included) running on {@code thread}. */
    interface ClientFactory {
        JukeboxRoomClient build(HandlerThread thread, JukeboxRoomClient.Listener listener);
    }

    /** Runs room setup/reconnect work off the main thread. */
    interface Executor {
        void post(Runnable r);
    }

    private static volatile JukeboxRoom sInstance;

    private final Context mAppContext;
    private final String mOrigin;
    private final String mToken;
    private final ClientFactory mClientFactory;
    private final Executor mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final JukeboxRoomClient.Listener mClientListener = new JukeboxRoomClient.Listener() {
        @Override
        public void onRoomReady(final String room, final String code, final String joinUrl) {
            mMainHandler.post(() -> {
                mRoom = room;
                mCode = code;
                mJoinUrl = joinUrl;
                for (Listener listener : mListeners) {
                    listener.onRoomUpdated();
                }
            });
        }

        @Override
        public void onPlay(String songId, final String videoId, final String title) {
            // Play means a room song is set. Do this before openVideo so autoplay
            // suppression is already on when the player loads that song.
            JukeboxMode.setSongSet(songId != null && videoId != null);
            mMainHandler.post(() -> {
                mNowPlaying = title;
                for (Listener listener : mListeners) {
                    listener.onStateChanged(title);
                }
                PlaybackPresenter.instance(mAppContext).openVideo(videoId);
            });
        }

        @Override
        public void onStatus(final String status) {
            // Closed/stopped/idle paths clear the client song before this runs.
            syncSongFlag();
            mMainHandler.post(() -> {
                mStatus = status;
                for (Listener listener : mListeners) {
                    listener.onStatusChanged(status);
                }
            });
        }

        @Override
        public void onState(final String nowPlayingTitle, final int listeners) {
            // A state with no current song (nowPlaying null) clears the client song first.
            syncSongFlag();
            mMainHandler.post(() -> {
                mNowPlaying = nowPlayingTitle;
                for (Listener listener : mListeners) {
                    listener.onStateChanged(nowPlayingTitle);
                }
            });
        }

        @Override
        public void onPopups(final boolean enabled) {
            mMainHandler.post(() -> {
                mPopupsEnabled = enabled;
                for (Listener listener : mListeners) {
                    listener.onPopupsChanged(enabled);
                }
            });
        }
    };

    private HandlerThread mThread;
    private Handler mThreadHandler;
    private JukeboxRoomClient mClient;
    private volatile boolean mActive;
    private volatile String mRoom;
    private volatile String mCode;
    private volatile String mJoinUrl;
    private volatile String mStatus;
    private volatile String mNowPlaying;
    private volatile boolean mPopupsEnabled;

    public static JukeboxRoom instance(Context context) {
        JukeboxRoom room = sInstance;
        if (room == null) {
            synchronized (JukeboxRoom.class) {
                room = sInstance;
                if (room == null) {
                    room = new JukeboxRoom(context.getApplicationContext(), null, null);
                    sInstance = room;
                }
            }
        }
        return room;
    }

    /** Inert check that never instantiates the room (used by the playback controller). */
    public static boolean isRoomActive() {
        JukeboxRoom room = sInstance;
        return room != null && room.mActive;
    }

    JukeboxRoom(Context appContext, ClientFactory clientFactory, Executor executor) {
        mAppContext = appContext;
        mOrigin = appContext.getString(R.string.jukebox_origin);
        String token = appContext.getString(R.string.jukebox_bridge_token);
        mToken = token == null ? "" : token;
        mClientFactory = clientFactory != null ? clientFactory : this::buildRealClient;
        mExecutor = executor != null ? executor : this::postToThreadHandler;
    }

    private JukeboxRoomClient buildRealClient(HandlerThread thread, JukeboxRoomClient.Listener listener) {
        OkHttpClient httpClient = new OkHttpClient.Builder().build();
        return new JukeboxRoomClient(
                mOrigin,
                new OkHttpTransport(httpClient),
                new OkHttpHttp(httpClient),
                new HandlerScheduler(thread.getLooper()),
                new PrefsStore(mAppContext),
                listener,
                mToken);
    }

    private void postToThreadHandler(Runnable r) {
        Handler handler = mThreadHandler;
        if (handler != null) {
            handler.post(r);
        }
    }

    /**
     * Starts (or re-attaches to) the room. Idempotent and self-healing: keeps a live
     * client/connection, rebuilds the client (and its thread) when it is dead, and
     * reconnects a dead socket immediately (see {@link JukeboxRoomClient#start()}).
     * Listeners are re-notified with the current room state afterwards.
     */
    public synchronized void start() {
        String reason = rebuildReason();
        if (reason != null) {
            JukeboxLog.i(LOG_TAG, "start origin=" + mOrigin + ": " + reason + ", rebuilding room client");
            final JukeboxRoomClient old = mClient;
            if (old != null) {
                old.clearCurrentSong();
                old.stop();
            }
            if (mThread != null) {
                mThread.quitSafely();
                mThread = null;
            }
            mThreadHandler = null;
            mClient = null;
            mThread = new HandlerThread("JukeboxRoom");
            mThread.start();
            mThreadHandler = new Handler(mThread.getLooper());
            mClient = mClientFactory.build(mThread, mClientListener);
        } else {
            JukeboxLog.i(LOG_TAG, "start origin=" + mOrigin
                    + ": client alive (connected=" + mClient.isConnected() + "), reusing"
                    + (mClient.isConnected() ? " live connection" : " (reconnect if socket died)"));
        }
        mActive = true;
        JukeboxMode.setRoomActive(true);
        // Flag tracks the current song, not the socket. A fresh room is idle until "play".
        syncSongFlag();
        final JukeboxRoomClient client = mClient;
        // Room setup does blocking HTTP: keep it off the main thread.
        mExecutor.post(client::start);
        // Rebind listeners/UI to the current room state (join screen, badge, overlay).
        mMainHandler.post(this::notifyRebind);
    }

    /** @return why the client/thread must be rebuilt, or null when it is alive. */
    private String rebuildReason() {
        if (mThread == null || !mThread.isAlive()) {
            return "room thread dead";
        }
        if (mClient == null) {
            return "no client";
        }
        if (!mClient.isRunning()) {
            return "client stopped";
        }
        return null;
    }

    private void notifyRebind() {
        String room = mRoom;
        String code = mCode;
        String joinUrl = mJoinUrl;
        String status = mStatus;
        String nowPlaying = mNowPlaying;
        boolean popups = mPopupsEnabled;
        for (Listener listener : mListeners) {
            listener.onRoomUpdated();
            listener.onStatusChanged(status);
            listener.onStateChanged(nowPlaying);
            listener.onPopupsChanged(popups);
        }
    }

    public synchronized void stop() {
        JukeboxLog.i(LOG_TAG, "stop");
        mActive = false;
        JukeboxMode.setRoomActive(false);
        final JukeboxRoomClient client = mClient;
        if (client != null) {
            // Drop the song before the async stop, so a restart doesn't look "now playing".
            client.clearCurrentSong();
            mExecutor.post(client::stop);
        }
        JukeboxMode.setSongSet(false);
        if (mPopupsEnabled) {
            mPopupsEnabled = false;
            mMainHandler.post(() -> {
                for (Listener listener : mListeners) {
                    listener.onPopupsChanged(false);
                }
            });
        }
    }

    public boolean isActive() {
        return mActive;
    }

    public void addListener(Listener listener) {
        mListeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public String getOrigin() {
        return mOrigin;
    }

    public String getRoom() {
        return mRoom;
    }

    public String getCode() {
        return mCode;
    }

    public String getJoinUrl() {
        return mJoinUrl;
    }

    public String getStatus() {
        return mStatus;
    }

    public String getNowPlaying() {
        return mNowPlaying;
    }

    /** True while the host enabled pop-ups in the latest room "state" (default off). */
    public boolean isPopupsEnabled() {
        return mPopupsEnabled;
    }

    /** Mirrors {@link JukeboxRoomClient}'s current song onto {@link JukeboxMode}. */
    private void syncSongFlag() {
        JukeboxMode.setSongSet(getCurrentVideoId() != null);
    }

    /** Video id of the song the room currently wants playing, or null when none. */
    public String getCurrentVideoId() {
        JukeboxRoomClient client = mClient;
        if (client == null || !mActive) {
            return null;
        }
        return client.getCurrentVideoId();
    }

    /** See {@link JukeboxRoomClient#songEnded(String)}. */
    public void songEnded(String videoId) {
        JukeboxRoomClient client = mClient;
        if (client == null || !mActive) {
            return;
        }
        client.songEnded(videoId);
    }

    /** See {@link JukeboxRoomClient#reportPlaying(String, Integer, Integer, String)}. */
    public void reportPlaying(String videoId, Integer durationSec, Integer positionSec, String state) {
        JukeboxRoomClient client = mClient;
        if (client == null || !mActive) {
            return;
        }
        client.reportPlaying(videoId, durationSec, positionSec, state);
    }

    /** See {@link JukeboxRoomClient#reportProgress(String, Integer, Integer)}. */
    public void reportProgress(String videoId, Integer durationSec, Integer positionSec) {
        JukeboxRoomClient client = mClient;
        if (client == null || !mActive) {
            return;
        }
        client.reportProgress(videoId, durationSec, positionSec);
    }
}
