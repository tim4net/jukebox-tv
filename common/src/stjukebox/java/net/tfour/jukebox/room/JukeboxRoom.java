package net.tfour.jukebox.room;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.R;
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
    public interface Listener {
        /** room/code/joinUrl changed (or became known). */
        void onRoomUpdated();
        void onStatusChanged(String status);
        void onStateChanged(String nowPlaying);
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
            mMainHandler.post(() -> {
                mStatus = status;
                for (Listener listener : mListeners) {
                    listener.onStatusChanged(status);
                }
            });
        }

        @Override
        public void onState(final String nowPlayingTitle, final int listeners) {
            mMainHandler.post(() -> {
                mNowPlaying = nowPlayingTitle;
                for (Listener listener : mListeners) {
                    listener.onStateChanged(nowPlayingTitle);
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
        final JukeboxRoomClient client = mClient;
        // Room setup does blocking HTTP: keep it off the main thread.
        mThreadHandler.post(client::start);
    }

    public synchronized void stop() {
        mActive = false;
        final JukeboxRoomClient client = mClient;
        if (client != null) {
            mThreadHandler.post(client::stop);
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
}
