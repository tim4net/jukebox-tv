package net.tfour.jukebox.room;

import android.content.Intent;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import net.tfour.jukebox.JukeboxJoinActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2466, issue (1) regression tests: reopening the app (Home, then the
 * launcher icon again) with an active room song must land straight back in that
 * exact song playing — the join screen's resume re-runs the controller's resume
 * check, and its bounded retry covers the 2465 failure where the single check
 * raced the foreground transition, deferred silently and the room stayed dead
 * for minutes. The launch itself must go through the JukeboxLaunch gate
 * (logged) and open the room's exact videoId exactly once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxJoinReturnResumeTest {
    static final String V1 = "dQw4w9WgXcQ";

    // ---- minimal room fakes (same shape as the common module's tests) ----

    static class FakeTransport implements JukeboxRoomClient.Transport {
        JukeboxRoomClient.Transport.Callbacks cb;
        boolean up;

        public void connect(String url, JukeboxRoomClient.Transport.Callbacks c) {
            cb = c;
        }

        public boolean send(String text) {
            return up;
        }

        public void close() {
            up = false;
        }

        void open() {
            up = true;
            cb.onOpen();
        }

        void msg(String s) {
            cb.onMessage(s);
        }
    }

    static class FakeHttp implements JukeboxRoomClient.Http {
        public String postJson(String url, String body) {
            return "{\"room\":\"room1\",\"code\":\"CODE1\"}";
        }

        public String get(String url) {
            return "{\"room\":{\"id\":\"room1\"},\"nowPlaying\":null}";
        }
    }

    static class FakeScheduler implements JukeboxRoomClient.Scheduler {
        public void schedule(Runnable r, long d) {
        }

        public void cancelAll() {
        }
    }

    static class MapStore implements JukeboxRoomClient.Store {
        final Map<String, String> m = new HashMap<>();

        public String get(String k) {
            return m.get(k);
        }

        public void put(String k, String v) {
            if (v == null) {
                m.remove(k);
            } else {
                m.put(k, v);
            }
        }
    }

    FakeTransport t;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    /** Strong reference: the presenter holds the player view only weakly. */
    PlaybackView playerView;
    final List<String> opened = new ArrayList<>();
    final List<HandlerThread> threads = new ArrayList<>();

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null);
        JukeboxLaunch.resetForTests();
        // The full production wiring: the join screen's launches go through the
        // logged JukeboxLaunch gate driven by JukeboxForeground (overridden per
        // test to simulate foreground/background).
        JukeboxLaunch.init(true, JukeboxForeground::isAppInForeground);
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
        resetSingleton(JukeboxRoom.class, "sInstance");

        ViewManager viewManager = ViewManager.instance(app());
        viewManager.register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);

        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0f;
                        return null;
                    }
                });
        playerView = player;
        presenter = PlaybackPresenter.instance(app());
        presenter.setView(player);

        // Record every openVideo() the presenter dispatches (exactly-once guard).
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController recorder =
                new com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController() {
                    @Override
                    public void onNewVideo(Video item) {
                        if (item != null) {
                            opened.add(item.videoId);
                        }
                    }
                };
        Field listenersField = PlaybackPresenter.class.getDeclaredField("mEventListeners");
        listenersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> listeners = (List<Object>) listenersField.get(presenter);
        listeners.add(recorder);

        t = new FakeTransport();
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new FakeHttp(), new FakeScheduler(), new MapStore(), listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        // No manual controller: the join screen drives the controller the
        // presenter registered reflectively (exactly like production).
        room.start();
        t.open();
        t.msg("{\"t\":\"play\",\"song\":{\"songId\":\"s1\",\"videoId\":\"" + V1
                + "\",\"title\":\"T\",\"durationMs\":null}}");
        assertEquals(V1, room.getCurrentVideoId());
        // Run the play message's main-thread post NOW while the app looks
        // backgrounded (the HOME-before-reopen scenario): it only updates state
        // ("play deferred", no openVideo), so each test starts with an idle
        // player and an active room song — the exact 2465 situation. (A
        // Handler.removeCallbacksAndMessages(null) purge cannot be used here:
        // it only drops posts of THAT handler instance, so the room's own post
        // would survive and open the video mid-launch, racing the retry.)
        JukeboxForeground.setTestOverride(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        JukeboxForeground.setTestOverride(null);
        setPresenterVideo(null);
        opened.clear();
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null);
        JukeboxLaunch.resetForTests();
        playerView = null;
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

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private ActivityController<JukeboxJoinActivity> launchJoinScreen() {
        return Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
    }

    @Test
    public void relaunchWithActiveSongOpensTheExactRoomSongPlaying() throws Exception {
        JukeboxForeground.setTestOverride(true); // the user is back in the app

        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // room re-bind + first retry tick

        assertEquals("reopening must open the room's exact song",
                V1, presenter.getVideo().videoId);
        assertEquals("the song must be opened exactly once", java.util.Arrays.asList(V1), opened);

        Intent started = Shadows.shadowOf(app()).getNextStartedActivity();
        assertNotNull("the player activity must be launched", started);
        assertEquals(PlaybackActivity.class.getName(),
                started.getComponent() != null ? started.getComponent().getClassName() : null);
        assertTrue("the launch must be logged under JukeboxLaunch",
                logged("JukeboxLaunch", "start PlaybackActivity (ViewManager.startView)"));
        assertTrue("the resume decision must be logged",
                logged("JukeboxPopup", "room song active but player idle"));

        ac.pause().stop().destroy();
    }

    @Test
    public void midTransitionRaceIsLoggedDeferredAndRecoveredByTheRetry() throws Exception {
        // The 2465 failure mode: every early check still sees "background" (the
        // process importance lags the relaunch), so nothing opens — build 2466's
        // bounded retry on the join screen must recover a moment later.
        JukeboxForeground.setTestOverride(false);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // re-bind + tick0: all defer

        assertNull("nothing may open while the app looks backgrounded", presenter.getVideo());
        assertTrue("the deferral must be logged with its reason",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));
        assertTrue("no activity may be started from the background",
                Shadows.shadowOf(app()).getNextStartedActivity() == null);

        JukeboxForeground.setTestOverride(true); // foreground state settles ~1.5s later
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));

        assertEquals("the retry must open the exact room song", V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick", java.util.Arrays.asList(V1), opened);
        assertTrue(logged("JukeboxLaunch", "start PlaybackActivity"));

        ac.pause().stop().destroy();
    }
}
