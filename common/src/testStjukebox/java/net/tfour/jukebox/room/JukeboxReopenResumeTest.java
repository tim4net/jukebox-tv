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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2465 (a): when a room song is active, reopening the app goes straight to
 * the room's exact song, playing. In particular the 2464 finding is fixed where
 * the corrected (reopened) song sat paused and needed a DPAD_CENTER press: a
 * loaded-but-paused room song is forced to play (playWhenReady true) whenever
 * the player view resumes, while non-room playback (idle room) is untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxReopenResumeTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController controller;
    List<String> playerCalls;
    /** Stateful playWhenReady, so a paused room song is distinguishable. */
    boolean[] playWhenReady = {false};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null); // Robolectric defaults to foreground
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        playerCalls = new ArrayList<>();
        playWhenReady[0] = false;
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        playerCalls.add(args == null || args.length == 0 ? method.getName()
                                : method.getName() + ":" + java.util.Arrays.toString(args));
                        String name = method.getName();
                        if ("getPlayWhenReady".equals(name)) {
                            return playWhenReady[0];
                        }
                        if ("setPlayWhenReady".equals(name) && args != null && args.length == 1) {
                            playWhenReady[0] = (Boolean) args[0];
                        }
                        if ("isEngineInitialized".equals(name)) {
                            return true; // a load in this player implies a live engine
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
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        assertEquals(V1, room.getCurrentVideoId());
        setPresenterVideo(null);
        playerCalls.clear();
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null);
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
    public void pausedRoomSongIsPlayingAgainOnViewResume() {
        // The room song is loaded (match branch ran) but sits paused — e.g. the
        // state restore applied a saved playWhenReady=false. Reopening the app
        // (player view resume) must play it, without a DPAD_CENTER press.
        controller.onVideoLoaded(Video.from(V1));
        assertTrue(playWhenReady[0]); // the load itself already enforces playing

        PlaybackView player = presenter.getPlayer();
        player.setPlayWhenReady(false); // something paused it afterwards
        playerCalls.clear();

        controller.onViewResumed();

        assertTrue("reopening must leave the room song playing: " + playerCalls,
                playerCalls.contains("setPlayWhenReady:[true]"));
        assertTrue(playWhenReady[0]);
    }

    @Test
    public void alreadyPlayingRoomSongIsNotTouchedOnViewResume() {
        controller.onVideoLoaded(Video.from(V1));
        playerCalls.clear();

        controller.onViewResumed();

        assertFalse("no redundant play calls when already playing: " + playerCalls,
                playerCalls.contains("setPlayWhenReady:[true]"));
    }

    @Test
    public void idleRoomPlaybackIsNotForcedToPlayOnViewResume() {
        // Room went idle (state snapshot without now-playing) and the user plays
        // something of their own: the view resume must not force-play it.
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        assertNull(room.getCurrentVideoId());
        controller.onVideoLoaded(Video.from(V2)); // idle room: plays unsupervised
        playWhenReady[0] = false;
        playerCalls.clear();

        controller.onViewResumed();

        assertFalse("non-room playback is not jukebox-controlled: " + playerCalls,
                playerCalls.contains("setPlayWhenReady:[true]"));
    }
}
