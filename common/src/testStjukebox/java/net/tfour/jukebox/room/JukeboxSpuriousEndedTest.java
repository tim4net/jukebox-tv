package net.tfour.jukebox.room;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #25 (Nina 2486 19:18): a warm return opened Tennessee Whiskey at
 * posMs=61152, and ~2.6s later the room was idle. The TV logged JukeboxPopup
 * "skip: vid=null" — the popup attach line, meaning the room's state had no
 * song. The TV had sent "ended".
 *
 * <p>Root cause this pins: reopen/reload calls {@code openVideo}, and
 * ExoPlayerController turns {@code DISCONTINUITY_REASON_PERIOD_TRANSITION}
 * (and any other early end) into {@code onPlayEnd} while the playhead is
 * still the current room song, mid-video. {@code onPlayEnd} then
 * {@code songEnded}s that id. "ended" must be sent only when that real video
 * id actually reached the end, never for a null id, and the send must be logged
 * with the video id and position.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxSpuriousEndedTest {
    static final String V1 = "a9DgP3AXNjs";
    /** Tennessee Whiskey playhead at the bad warm return. Not the end of the song. */
    static final long MID_POS_MS = 61_152L;
    static final long DURATION_MS = 293_000L;
    /** A real end: inside the last 2s. */
    static final long END_POS_MS = 292_000L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController controller;
    /** Strong ref: the presenter only keeps a WeakReference to the player. */
    PlaybackView playerView;
    final long[] positionMs = {MID_POS_MS};
    final long[] durationMs = {DURATION_MS};
    final Video[] playerVideo = {null};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        playerView = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getPositionMs".equals(name)) {
                            return positionMs[0];
                        }
                        if ("getDurationMs".equals(name)) {
                            return durationMs[0];
                        }
                        if ("getVideo".equals(name)) {
                            return playerVideo[0];
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0f;
                        return null;
                    }
                });
        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(playerView);

        t = new JukeboxRoomClientTest.FakeTransport();
        http = new JukeboxRoomClientTest.FakeHttp();
        sch = new JukeboxRoomClientTest.FakeScheduler();
        store = new JukeboxRoomClientTest.MapStore();
        room = new JukeboxRoom(RuntimeEnvironment.getApplication(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient(JukeboxRoomClientTest.ORIGIN, t, http, sch, store, listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        controller = new JukeboxRoomController();
        controller.setMainController(presenter);
        room.start();
        t.open();
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        assertEquals(V1, room.getCurrentVideoId());
        setPresenterVideo(Video.from(V1));
        playerVideo[0] = Video.from(V1);
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
        if (room != null) {
            room.stop();
        }
        for (HandlerThread thread : threads) {
            thread.quitSafely();
        }
        playerView = null;
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    @Test
    public void playEndDuringReopenAtMidSongDoesNotSendEnded() throws Exception {
        // Warm return: openVideo is in flight (onNewVideo), then the engine
        // reports play-end from a period transition / reload. The playhead is
        // still 61s into a ~293s song. That must not end the room song.
        controller.onNewVideo(Video.from(V1));
        controller.onPlayEnd();

        assertEquals("a mid-song onPlayEnd during reopen must not send ended",
                0, t.count("ended"));
        assertEquals("the room song must still be current", V1, room.getCurrentVideoId());
        assertTrue("the ignored play-end must be logged with the video id and position",
                logged("JukeboxPopup", "play end ignored")
                        && logged("JukeboxPopup", V1)
                        && logged("JukeboxPopup", "positionMs=" + MID_POS_MS));
    }

    @Test
    public void playEndWithANullVideoIdDoesNotSendEnded() throws Exception {
        Video blank = new Video();
        blank.videoId = null;
        playerVideo[0] = blank;
        setPresenterVideo(blank);

        controller.onPlayEnd();

        assertEquals("ended must never be sent for a null video id", 0, t.count("ended"));
    }

    @Test
    public void playEndAtTheEndOfTheRoomSongSendsEndedAndLogsIt() throws Exception {
        positionMs[0] = END_POS_MS;
        controller.onPlayEnd();

        assertEquals("a real end of the room song must send ended", 1, t.count("ended"));
        assertEquals(V1, t.only("ended").getString("videoId"));
        assertTrue("the send must be logged with the video id and position",
                logged("JukeboxRoom", "ended sent videoId=" + V1 + " positionMs=" + END_POS_MS));
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        setSingleton(clazz, fieldName, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void setPresenterVideo(Video video) throws Exception {
        Field field = PlaybackPresenter.class.getDeclaredField("mVideo");
        field.setAccessible(true);
        field.set(presenter, new WeakReference<>(video));
    }
}
