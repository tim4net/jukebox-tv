package net.tfour.jukebox.room;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2465 (c), issue #3 (the app must never take over the TV): room "play"
 * messages, the controller's idle resume check and mismatch reopens that fire
 * while the app is backgrounded (another app in use) must only update state —
 * never openVideo (which launches/foregrounds PlaybackActivity) and never start
 * any activity. The song opens when the user returns (the checks re-run in the
 * foreground, e.g. the join screen's room re-bind or the player view resuming).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxBackgroundLaunchGateTest {
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
        JukeboxForeground.setTestOverride(null); // Robolectric defaults to foreground
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

    private Application app() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void playWhileBackgroundedUpdatesStateButNeverOpensOrLaunches() {
        JukeboxForeground.setTestOverride(false); // another app is in use

        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // let the "play" side effects run

        // State is updated so the song can open when the user returns...
        assertEquals("the room's current song must be tracked", V2, room.getCurrentVideoId());
        assertTrue("autoplay suppression must already be on", JukeboxMode.isSongSet());
        // ...but nothing was opened or launched: no takeover.
        assertNull("openVideo must not run while backgrounded", presenter.getVideo());
        assertNull("no activity may be started from the background",
                Shadows.shadowOf(app()).getNextStartedActivity());
    }

    @Test
    public void deferredPlayOpensTheExactSongWhenTheUserReturns() {
        JukeboxForeground.setTestOverride(false);
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(presenter.getVideo());

        // The user returns (join screen re-entered / app foregrounded): the room
        // re-binds listeners, which re-runs the resume check in the foreground.
        JukeboxForeground.setTestOverride(true);
        controller.onStateChanged("T s2");
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the room's exact song must open on return", V2, presenter.getVideo().videoId);
    }

    @Test
    public void resumeCheckIsDeferredWhileBackgrounded() throws Exception {
        // Like a mid-song engine release while the user is in another app.
        setPresenterVideo(Video.from(V1));
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null); // drop setUp postings
        JukeboxForeground.setTestOverride(false);
        controller.onEngineReleased();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // run the posted resume check
        assertTrue("no reopen while backgrounded: " + playerCalls,
                !playerCalls.contains("setPlayWhenReady:[false]"));

        JukeboxForeground.setTestOverride(true); // user is back
        controller.onStateChanged("Hey");
        assertEquals("the room song reopens in the foreground", V1, presenter.getVideo().videoId);
    }

    @Test
    public void mismatchWhileBackgroundedPausesWithoutReopeningOrLaunching() {
        JukeboxForeground.setTestOverride(false);

        controller.onVideoLoaded(Video.from(V2)); // non-room video loaded (mismatch)

        assertTrue("the non-room video must still be paused (enforcement without launch): "
                + playerCalls, playerCalls.contains("setPlayWhenReady:[false]"));
        assertNull("no reopen while backgrounded", presenter.getVideo());
        assertNull("no activity may be started from the background",
                Shadows.shadowOf(app()).getNextStartedActivity());

        // When the user returns, the room's exact song takes over again.
        JukeboxForeground.setTestOverride(true);
        controller.onStateChanged("Hey");
        assertEquals(V1, presenter.getVideo().videoId);
    }
}
