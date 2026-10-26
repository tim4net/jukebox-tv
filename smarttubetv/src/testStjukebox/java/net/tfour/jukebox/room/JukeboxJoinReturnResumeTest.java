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
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";

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

    // ---- build 2468: the 2467 gazebo step-2 failure ----
    // Home mid-song releases the engine; relaunching the join screen logged
    // "re-enter join screen (onNewIntent)" and the popup attach, but NO resume
    // attempt ran (no tick lines, no JukeboxLaunch line) and no player opened.

    /** Plays the room song (loaded marker set), like a real playing session. */
    private JukeboxRoomController playingRoomSong() throws Exception {
        JukeboxRoomController controller = presenter.getController(JukeboxRoomController.class);
        assertNotNull("the presenter must have registered the jukebox controller", controller);
        controller.onVideoLoaded(Video.from(V1)); // loaded + playing
        // Drain the (legitimate, foreground) launch the join screen's first
        // resume dispatched for V1: below, only the HELD-BACK launch is asserted.
        Shadows.shadowOf(app()).getNextStartedActivity();
        return controller;
    }

    private void assertRoomSongReopenedExactlyOnce(String via) {
        assertEquals("the re-entry (" + via + ") must reopen the room's exact song",
                V1, presenter.getVideo().videoId);
        assertEquals("exactly one open on the re-entry (" + via + ")",
                java.util.Arrays.asList(V1), opened);
        assertNotNull("the player activity must be launched (" + via + ")",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the resume decision must be logged (" + via + ")",
                logged("JukeboxPopup", "room song active but player idle"));
        assertTrue("the launch must be logged (" + via + ")",
                logged("JukeboxLaunch", "start PlaybackActivity"));
    }

    @Test
    public void relaunchAfterEngineReleaseViaOnNewIntentOpensTheExactRoomSong() throws Exception {
        // The 2467 flow: the room song plays, HOME stops the join screen and the
        // engine is released while the app is backgrounded, then the launcher
        // relaunch delivers onNewIntent to the existing activity.
        JukeboxForeground.setTestOverride(true);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the first entry opens V1
        JukeboxRoomController controller = playingRoomSong();
        ShadowLog.clear();
        opened.clear();

        // HOME: the join screen stops, the engine is released (app backgrounded).
        ac.pause().stop();
        JukeboxForeground.setTestOverride(false);
        controller.onEngineReleased();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the release-time check defers
        assertTrue("nothing may open while backgrounded: " + opened, opened.isEmpty());
        assertTrue("the release-time deferral must be logged",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));

        // Relaunch: the launcher delivers onNewIntent to the stopped activity,
        // then onStart/onResume follow (the production warm-relaunch order).
        JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        ac.newIntent(new Intent(app(), JukeboxJoinActivity.class));
        assertTrue("the onNewIntent re-entry must be logged",
                logged("JukeboxJoin", "re-enter join screen (onNewIntent)"));
        ac.start();
        ac.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // re-bind + resume retry ticks

        assertRoomSongReopenedExactlyOnce("onNewIntent");

        ac.pause().stop().destroy();
    }

    @Test
    public void relaunchAfterEngineReleaseViaOnResumeOpensTheExactRoomSong() throws Exception {
        // Same failure, cold-restart variant: the stopped activity is brought
        // back with no onNewIntent — onStart/onResume alone must resume the song.
        JukeboxForeground.setTestOverride(true);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        JukeboxRoomController controller = playingRoomSong();
        ShadowLog.clear();
        opened.clear();

        ac.pause().stop();
        JukeboxForeground.setTestOverride(false);
        controller.onEngineReleased();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("nothing may open while backgrounded", opened.isEmpty());

        // Relaunch without a new intent: onStart + onResume (the retry ticks are
        // (re-)started on both re-entry paths).
        JukeboxForeground.setTestOverride(true);
        ac.start();
        ac.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertRoomSongReopenedExactlyOnce("onResume");
        assertTrue("the onStart re-entry must be logged",
                logged("JukeboxJoin", "re-enter join screen (onStart)"));

        ac.pause().stop().destroy();
    }

    @Test
    public void songChangeWhileBackgroundedIsHeldBackThenPlaysOnForegroundReturn() throws Exception {
        // Requirement (2): the host skips to another song while the app is
        // backgrounded (the takeover block holds the open back); relaunching the
        // join screen must play that held-back song — the latest nowPlaying
        // wins, with no other video loaded first.
        JukeboxForeground.setTestOverride(true);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the first entry opens V1
        playingRoomSong();
        ShadowLog.clear();
        opened.clear();

        ac.pause().stop();
        JukeboxForeground.setTestOverride(false);
        t.msg("{\"t\":\"play\",\"song\":{\"songId\":\"s2\",\"videoId\":\"" + V2
                + "\",\"title\":\"T2\",\"durationMs\":null}}"); // the host skips while backgrounded
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the held-back open must be verifiable: " + opened, opened.isEmpty());
        assertEquals("the latest song must be the room's current one", V2, room.getCurrentVideoId());
        assertNull("no launch while backgrounded",
                Shadows.shadowOf(app()).getNextStartedActivity());

        // The user relaunches: the held-back song change plays.
        JukeboxForeground.setTestOverride(true);
        ac.newIntent(new Intent(app(), JukeboxJoinActivity.class));
        ac.start();
        ac.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the held-back song change must play on return",
                V2, presenter.getVideo().videoId);
        assertEquals("the latest song only, nothing else loaded first",
                java.util.Arrays.asList(V2), opened);

        ac.pause().stop().destroy();
    }

    // ---- build 2469: the 2468 gazebo c1/c2 failures ----
    // Home mid-song releases the engine; relaunching logged the popup attach
    // (c1: nothing else) or a single stale in-flight marker line (c2), and no
    // PlaybackActivity ever started. Stale reopen counters/markers from before
    // the release must never block the relaunch, and a completely gone player
    // view must not either.

    /** Restores the observed pre-2469 stale reopen-counter state via reflection. */
    private void makeReopenCountersStale(String videoId) throws Exception {
        JukeboxRoomController controller = presenter.getController(JukeboxRoomController.class);
        assertNotNull(controller);
        Field reopenVideoId = JukeboxRoomController.class.getDeclaredField("mReopenVideoId");
        reopenVideoId.setAccessible(true);
        reopenVideoId.set(controller, videoId);
        Field attempts = JukeboxRoomController.class.getDeclaredField("mReopenAttempts");
        attempts.setAccessible(true);
        attempts.setInt(controller, 2); // MAX_REOPEN_ATTEMPTS: the cap
        Field requested = JukeboxRoomController.class.getDeclaredField("mRequestedVideoId");
        requested.setAccessible(true);
        requested.set(controller, videoId);
        Field capLogged = JukeboxRoomController.class.getDeclaredField("mCapLoggedVideoId");
        capLogged.setAccessible(true);
        capLogged.set(controller, videoId); // the cap log is suppressed for this song, too
        Field loading = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        loading.setAccessible(true);
        loading.set(controller, videoId);
        Field loadingSince = JukeboxRoomController.class.getDeclaredField("mLoadingSinceMs");
        loadingSince.setAccessible(true);
        loadingSince.setLong(controller, android.os.SystemClock.elapsedRealtime() - 60_000); // stale by age
    }

    @Test
    public void relaunchAfterEngineReleaseWithStaleCountersOpensTheExactRoomSong() throws Exception {
        // The full 2468 c1 flow: playing -> HOME (engine released under us) ->
        // relaunch onNewIntent. The release and the relaunch race on a real
        // device, so the counters may describe the dead engine generation.
        JukeboxForeground.setTestOverride(true);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the first entry opens V1
        JukeboxRoomController controller = playingRoomSong();
        ShadowLog.clear();
        opened.clear();

        ac.pause().stop();
        JukeboxForeground.setTestOverride(false);
        controller.onEngineReleased();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // release-time check defers (logged)
        assertTrue("nothing may open while backgrounded: " + opened, opened.isEmpty());

        // Stale reopen counters/markers left over from before the release.
        makeReopenCountersStale(V1);

        JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        ac.newIntent(new Intent(app(), JukeboxJoinActivity.class));
        ac.start();
        ac.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // re-bind + resume retry ticks

        assertRoomSongReopenedExactlyOnce("stale counters");
        assertTrue("the stale in-flight marker decision must be logged",
                logged("JukeboxPopup", "in-flight marker " + V1 + " is stale"));

        ac.pause().stop().destroy();
    }

    @Test
    public void relaunchAfterEngineReleaseWithoutAnyPlayerViewOpensTheExactRoomSong() throws Exception {
        // The 2468 c1 variant: the player view itself is gone (activity
        // destroyed), only the presenter (and its weak refs) remain.
        JukeboxForeground.setTestOverride(true);
        ActivityController<JukeboxJoinActivity> ac = launchJoinScreen();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        JukeboxRoomController controller = playingRoomSong();
        ShadowLog.clear();
        opened.clear();

        ac.pause().stop();
        JukeboxForeground.setTestOverride(false);
        controller.onEngineReleased();
        // The whole player view (and the presenter's refs to it) is gone.
        clearPresenterPlayerView();
        setPresenterVideo(null);
        playerView = null;
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("nothing may open while backgrounded: " + opened, opened.isEmpty());

        JukeboxForeground.setTestOverride(true);
        ac.newIntent(new Intent(app(), JukeboxJoinActivity.class));
        ac.start();
        ac.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertRoomSongReopenedExactlyOnce("no player view");

        ac.pause().stop().destroy();
    }

    /** Drops the presenter's (weak) refs to the player view, as if it was destroyed. */
    private void clearPresenterPlayerView() throws Exception {
        Field playerField = PlaybackPresenter.class.getDeclaredField("mPlayer");
        playerField.setAccessible(true);
        playerField.set(presenter, new WeakReference<>(null));
        Field viewField = com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter.class
                .getDeclaredField("mView");
        viewField.setAccessible(true);
        viewField.set(presenter, new WeakReference<>(null));
    }
}
