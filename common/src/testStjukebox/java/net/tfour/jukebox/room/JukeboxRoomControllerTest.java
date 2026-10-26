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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exact-videoId enforcement of {@link JukeboxRoomController} against a real
 * {@link JukeboxRoom} driven by the fake transport/http/scheduler (no network):
 * a mismatched video is reopened as the room's exact id (bounded), a mismatch is
 * never allowed to keep playing once the cap is reached (it is paused), and an
 * idle player while a song is active reopens the song — except after "ended",
 * which must never replay. The player view is a recording dynamic proxy.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxRoomControllerTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController controller;
    List<String> playerCalls;
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        playerCalls = new ArrayList<>();
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        playerCalls.add(args == null || args.length == 0 ? method.getName()
                                : method.getName() + ":" + java.util.Arrays.toString(args));
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0f;
                        return null;
                    }
                });

        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(player);

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
        // The client listener posts the "play" side effects to the (paused) main
        // looper; the current-song state is what the controller reads directly.
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        assertEquals(V1, room.getCurrentVideoId());
        setPresenterVideo(null);
        playerCalls.clear();
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
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
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

    @Test
    public void mismatchedVideoReopensExactRoomIdTwiceThenPausesAtCap() throws Exception {
        controller.onVideoLoaded(Video.from(V2)); // mismatch -> reopen attempt 1
        assertEquals(V1, presenter.getVideo().videoId);
        controller.onVideoLoaded(Video.from(V2)); // mismatch -> reopen attempt 2
        assertEquals(V1, presenter.getVideo().videoId);

        setPresenterVideo(Video.from(V2)); // a stale non-room video is "current"
        playerCalls.clear();
        controller.onVideoLoaded(Video.from(V2)); // cap reached: no reopen, must pause
        assertEquals("cap must not reopen the video", V2, presenter.getVideo().videoId);
        assertTrue("non-room playback must be paused: " + playerCalls,
                playerCalls.contains("setPlayWhenReady:[false]"));
    }

    @Test
    public void matchingLoadReportsPlayingAndClearsEndedGuard() throws Exception {
        setPresenterVideo(Video.from(V1));
        controller.onPlayEnd();
        assertEquals(1, t.count("ended"));
        assertEquals(0, t.count("playing"));

        controller.onVideoLoaded(Video.from(V1)); // the room video (re)loads
        assertEquals(1, t.count("playing"));       // reported to the room again
        assertTrue(playerCalls.contains("setPlayWhenReady:[true]"));
    }

    @Test
    public void idlePlayerWhileSongActiveReopensRoomSong() throws Exception {
        controller.onEngineReleased(); // player released mid-song (e.g. join screen)
        setPresenterVideo(Video.from(V2)); // the released view still holds a stale video
        controller.onStateChanged("Hey"); // any room event triggers the resume check
        assertEquals("the exact room videoId must be reopened", V1, presenter.getVideo().videoId);
    }

    @Test
    public void endedSongIsNeverReopenedByTheResumeCheck() throws Exception {
        setPresenterVideo(Video.from(V1));
        controller.onPlayEnd(); // "ended" sent for V1
        assertEquals(1, t.count("ended"));
        setPresenterVideo(null);
        controller.onStateChanged("Hey"); // resume check must not replay V1
        assertNull("an ended song must not be reopened", presenter.getVideo());
    }

    @Test
    public void onInitRegistersAsRoomListener() throws Exception {
        // onInit()/onViewResumed() must attach the controller to the room even when
        // the presenter was constructed before JukeboxRoom.start() ran.
        controller.onInit();
        Field field = JukeboxRoom.class.getDeclaredField("mListeners");
        field.setAccessible(true);
        java.util.concurrent.CopyOnWriteArrayList<?> listeners =
                (java.util.concurrent.CopyOnWriteArrayList<?>) field.get(room);
        assertTrue("controller must listen to the room", listeners.contains(controller));
    }
}
