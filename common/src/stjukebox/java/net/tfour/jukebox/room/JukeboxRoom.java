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

    private static volatile JukeboxRoom sInstance;

    private final Context mAppContext;
    private final String mOrigin;
    private final String mToken;
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
                    room = new JukeboxRoom(context.getApplicationContext());
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

    private JukeboxRoom(Context appContext) {
        mAppContext = appContext;
        mOrigin = appContext.getString(R.string.jukebox_origin);
        String token = appContext.getString(R.string.jukebox_bridge_token);
        mToken = token == null ? "" : token;
    }

    /** Starts (or re-attaches to) the room. Idempotent. */
    public synchronized void start() {
        JukeboxLog.i(LOG_TAG, "start origin=" + mOrigin);
        if (mClient == null) {
            mThread = new HandlerThread("JukeboxRoom");
            mThread.start();
            mThreadHandler = new Handler(mThread.getLooper());
            OkHttpClient httpClient = new OkHttpClient.Builder().build();
            mClient = new JukeboxRoomClient(
                    mOrigin,
                    new OkHttpTransport(httpClient),
                    new OkHttpHttp(httpClient),
                    new HandlerScheduler(mThread.getLooper()),
                    new PrefsStore(mAppContext),
                    mClientListener,
                    mToken);
        }
        mActive = true;
        // Flag tracks the current song, not the socket. A fresh room is idle until "play".
        syncSongFlag();
        final JukeboxRoomClient client = mClient;
        // Room setup does blocking HTTP: keep it off the main thread.
        mThreadHandler.post(client::start);
    }

    public synchronized void stop() {
        JukeboxLog.i(LOG_TAG, "stop");
        mActive = false;
        final JukeboxRoomClient client = mClient;
        if (client != null) {
            // Drop the song before the async stop, so a restart doesn't look "now playing".
            client.clearCurrentSong();
            mThreadHandler.post(client::stop);
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
