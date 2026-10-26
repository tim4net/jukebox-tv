package net.tfour.jukebox.room;

import android.app.Activity;
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
import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
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
import static org.junit.Assert.assertNotNull;
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
        JukeboxLaunch.resetForTests(); // ViewManager gate inert unless a test opts in
        ShadowLog.clear();
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
        JukeboxLaunch.resetForTests();
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

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void viewManagerBlocksInternalLaunchesWhileBackgrounded() {
        // The 2465 gazebo finding (steps 3/5): a skip while Jukebox was
        // backgrounded cold-started SplashActivity in a new task and brought
        // PlaybackActivity to the front ~5-7s later. Every internal launch
        // funnels through ViewManager.startView, which must be a no-op (logged
        // under JukeboxLaunch) while another app is in use.
        JukeboxLaunch.init(true, JukeboxForeground::isAppInForeground);
        ViewManager viewManager = ViewManager.instance(app());
        viewManager.register(PlaybackView.class, Activity.class);
        viewManager.setRoot(Activity.class);

        JukeboxForeground.setTestOverride(false); // e.g. Fire TV Settings on top
        viewManager.startView(PlaybackView.class);
        viewManager.startDefaultView();
        assertNull("no activity may be started while backgrounded",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the blocked launch must be verifiable",
                logged("JukeboxLaunch", "blocked Activity"));

        JukeboxForeground.setTestOverride(true); // the user is back in the app
        viewManager.startView(PlaybackView.class);
        assertNotNull("foreground launches proceed as before",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue(logged("JukeboxLaunch", "start Activity (ViewManager.startView)"));
    }

    @Test
    public void resumeHookLogsTheDeferralAndOpensOnReturn() throws Exception {
        // The 2465 gazebo finding (step 2): the join screen's resume check ran
        // while the process still looked backgrounded, deferred silently and
        // nothing ever retried. The hook must log WHY it defers (verifiable),
        // and a later call once the user is back must open the exact room song.
        setPresenterVideo(null);
        JukeboxForeground.setTestOverride(false);

        controller.resumeRoomSongIfNeeded("join:onResume:tick1");
        assertNull("no open while backgrounded", presenter.getVideo());
        assertTrue("the deferral must be logged with its reason",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));

        JukeboxForeground.setTestOverride(true); // retry tick after the user returned
        controller.resumeRoomSongIfNeeded("join:onResume:tick2");
        assertEquals("the exact room song must open on return", V1, presenter.getVideo().videoId);
    }

    @Test
    public void blockedReopenLaunchDoesNotStrandTheInFlightMarker() throws Exception {
        // Review critical #2 / test gap 9: if the app backgrounds between the
        // resume check and the launch inside openVideo, JukeboxLaunch blocks the
        // ViewManager.startView — but the openVideo dispatch already marked the
        // room video "in flight" (onNewVideo). That marker must not survive the
        // blocked launch: every later retry would see "load already on its way"
        // and the room would stay silent forever. The controller under test is
        // the presenter's own reflectively-registered one (exactly production),
        // so its onNewVideo runs inside the openVideo dispatch.
        JukeboxLaunch.init(true, JukeboxForeground::isAppInForeground);
        ViewManager viewManager = ViewManager.instance(app());
        viewManager.register(PlaybackView.class, Activity.class);
        viewManager.setRoot(Activity.class);
        JukeboxRoomController wired = presenter.getController(JukeboxRoomController.class);
        assertNotNull(wired);

        // Lands the moment the openVideo dispatch starts: the launch (startView)
        // follows right after it, so the gate blocks that launch.
        final boolean[] backgroundedMidOpen = {false};
        BasePlayerController midOpenRacer = new BasePlayerController() {
            @Override
            public void onNewVideo(Video item) {
                if (!backgroundedMidOpen[0]) {
                    backgroundedMidOpen[0] = true;
                    JukeboxForeground.setTestOverride(false); // HOME/other app lands mid-open
                }
            }
        };
        Field listenersField = PlaybackPresenter.class.getDeclaredField("mEventListeners");
        listenersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> listeners = (List<Object>) listenersField.get(presenter);
        listeners.add(midOpenRacer);

        setPresenterVideo(null);
        wired.onStateChanged("Hey"); // resume check passes (foreground) -> open -> blocked launch
        assertEquals("the open itself dispatched before the gate", V1, presenter.getVideo().videoId);
        assertNull("the blocked launch must not start any activity",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the blocked launch must be logged",
                logged("JukeboxLaunch", "blocked Activity"));

        JukeboxForeground.setTestOverride(true); // the user returns
        wired.resumeRoomSongIfNeeded("join:onResume:tick1");
        assertEquals("the retry must reopen the exact room song", V1, presenter.getVideo().videoId);
        assertNotNull("the retry's launch is allowed in the foreground (marker was not stranded)",
                Shadows.shadowOf(app()).getNextStartedActivity());
    }
}
