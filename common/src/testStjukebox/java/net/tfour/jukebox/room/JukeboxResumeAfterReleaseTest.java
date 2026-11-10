package net.tfour.jukebox.room;

import android.app.Activity;
import android.content.Intent;
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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2468, gazebo step 2 (the 2467 failure): Home mid-song releases the
 * engine, and relaunching the app lands on the join screen — but NO resume
 * attempt ran at all (no tick logs, no JukeboxLaunch line, no player). The
 * resume check's in-memory markers ({@code mLoadedVideoId}/{@code mLoadingVideoId})
 * and the presenter's own player view/video keep the released song's id long
 * after the engine was released, so the guard "a song is already loaded:
 * nothing to resume" silently returned on every re-entry check.
 *
 * Covered here, through player and room events (opens are the activities
 * {@code openVideo} starts):
 * <ul>
 * <li>a released engine is not "already playing": a load callback that
 * arrives after the release still re-opens the room song, once;</li>
 * <li>a clean release defers while the app is backgrounded — no launch over
 * whatever app is in use (issue #3 kept) — and opens on the return to the
 * foreground;</li>
 * <li>an in-flight load that never settles cannot block the resume forever;</li>
 * <li>a song change held back while the app was backgrounded opens the
 * LATEST song on return, with no other video loaded first.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxResumeAfterReleaseTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController wired;
    /** Stateful proxy player: engine liveness flips when the engine is released. */
    boolean[] engineInit = {true};
    boolean[] playWhenReady = {false};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null); // Robolectric defaults to foreground
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        engineInit[0] = true;
        playWhenReady[0] = false;
        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getPlayWhenReady".equals(name)) {
                            return playWhenReady[0];
                        }
                        if ("setPlayWhenReady".equals(name) && args != null && args.length == 1) {
                            playWhenReady[0] = (Boolean) args[0];
                        }
                        if ("isEngineInitialized".equals(name)) {
                            return engineInit[0];
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
        ViewManager.instance(RuntimeEnvironment.getApplication())
                .register(PlaybackView.class, Activity.class);

        t = new JukeboxRoomClientTest.FakeTransport();
        http = new JukeboxRoomClientTest.FakeHttp();
        sch = new JukeboxRoomClientTest.FakeScheduler();
        store = new JukeboxRoomClientTest.MapStore();
        room = new JukeboxRoom(RuntimeEnvironment.getApplication(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient(JukeboxRoomClientTest.ORIGIN, t, http, sch, store, listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        wired = presenter.getController(JukeboxRoomController.class);
        assertNotNull("the presenter must have registered the jukebox controller", wired);

        room.start();
        t.open();
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        assertEquals(V1, room.getCurrentVideoId());

        // The room song plays for a while (loaded + live engine + playing).
        wired.onVideoLoaded(Video.from(V1));
        assertTrue(playWhenReady[0]);
        assertTrue("the loaded song must count as playing while the engine is live",
                engineInit[0]);
        startedActivities(); // drop anything the setup load itself launched
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
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

    // ---- helpers ----

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        setSingleton(clazz, fieldName, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Activities {@code openVideo} actually started since the last drain. */
    private static List<String> startedActivities() {
        List<String> names = new ArrayList<>();
        Intent intent;
        while ((intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity()) != null) {
            names.add(intent.getComponent() != null
                    ? intent.getComponent().getClassName() : String.valueOf(intent.getAction()));
        }
        return names;
    }

    /** HOME: all our activities stopped, engine released by the stopped player. */
    private void goHomeAndReleaseEngine() {
        JukeboxForeground.setTestOverride(false); // another app (Home) is in use
        engineInit[0] = false; // the player released its engine
        wired.onEngineReleased(); // ...and told the controller so
    }

    /** Relaunch of the join screen: the user is back. */
    private void relaunchIntoJoinScreen() {
        JukeboxForeground.setTestOverride(true);
    }

    // ---- requirement (1): released engine + stale markers ----

    @Test
    public void staleLoadedMarkersAfterEngineReleaseDoNotBlockTheReentryResume() {
        goHomeAndReleaseEngine();
        // Flush the setup play and the release resume while backgrounded.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // A load callback after the engine is already dead must not read as
        // "already playing". The resume re-opens the room song.
        wired.onVideoLoaded(Video.from(V1));
        assertTrue("the late load did not open anything while the engine is dead",
                startedActivities().isEmpty());
        relaunchIntoJoinScreen();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("the exact room song must be reopened", V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick",
                Arrays.asList(Activity.class.getName()), startedActivities());
    }

    @Test
    public void cleanEngineReleaseDefersWhileBackgroundedAndReopensOnReturn() {
        goHomeAndReleaseEngine();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the posted resume check runs

        // Deferred while the app is backgrounded: no launch over whatever app
        // is in use (issue #3 kept), nothing opened.
        assertTrue("no open while backgrounded", startedActivities().isEmpty());

        // Relaunching the join screen while STILL backgrounded stays blocked.
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");
        assertTrue("still backgrounded: the retry must keep deferring", keepAsking);
        assertTrue("no open while backgrounded", startedActivities().isEmpty());

        // The user is back (join screen resumed): the exact song opens, once.
        relaunchIntoJoinScreen();
        keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick2");
        assertFalse(keepAsking);
        assertEquals("the room's exact song must reopen on the re-entry",
                V1, presenter.getVideo().videoId);
        assertEquals(Arrays.asList(Activity.class.getName()), startedActivities());
    }

    @Test
    public void staleInFlightMarkerAloneCannotSilenceTheRoomForever() {
        goHomeAndReleaseEngine();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // A load that never settles (engine released mid-load): onNewVideo set
        // the in-flight marker and no onVideoLoaded will clear it.
        wired.onNewVideo(Video.from(V1));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(60_000));
        relaunchIntoJoinScreen();

        wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertEquals("a stale in-flight marker must not block the resume",
                V1, presenter.getVideo().videoId);
        assertEquals(Arrays.asList(Activity.class.getName()), startedActivities());
    }

    // ---- in-flight marker: a normal pending load is not a second open ----

    /**
     * Home clears the loaded marker (engine release). Come back to the
     * foreground with a player that is live again, then start a load the
     * public way: {@code onNewVideo}.
     */
    private void releaseThenStartPendingLoad() {
        goHomeAndReleaseEngine();
        // Flush the setUp "play" post and the release's resume check while
        // backgrounded, so neither opens the video before the pending load.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        wired.onNewVideo(Video.from(V1));
        JukeboxForeground.setTestOverride(true);
        engineInit[0] = true;
        startedActivities();
    }

    @Test
    public void youngPendingLoadWithoutAnAttachedPlayerIsNotReopened() {
        // onNewVideo has run and the player activity has not attached. That
        // is a normal pending load, not a stale marker: the resume must not
        // open the room video again.
        goHomeAndReleaseEngine();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        wired.onNewVideo(Video.from(V1));
        presenter.setView(null);
        JukeboxForeground.setTestOverride(true);
        startedActivities();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("a pending load is not a reason to keep asking", keepAsking);
        assertNull("a normal pending load must not be opened again", presenter.getVideo());
        List<String> opens = startedActivities();
        assertTrue("nothing may open: " + opens, opens.isEmpty());
    }

    @Test
    public void livePlayerKeepsAPendingLoadUntilFifteenSecondsThenOpensOnce() {
        releaseThenStartPendingLoad();

        // 9s is past the old 8s window and still inside 15s.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(9_000));
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("a live player's pending load must defer inside 15s", keepAsking);
        assertNull("nothing is opened at 9s", presenter.getVideo());
        List<String> opens = startedActivities();
        assertTrue("nothing may open while the load is in flight: " + opens, opens.isEmpty());

        // 16s is past 15s: the load did not settle, so the room video opens once.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(7_000));
        keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick2");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("past 15s the room video opens", V1, presenter.getVideo().videoId);
        assertEquals("past 15s the room video opens exactly once",
                Arrays.asList(Activity.class.getName()), startedActivities());
    }

    // ---- requirement (2): song change held back while backgrounded ----

    @Test
    public void songChangeWhileBackgroundedIsBlockedThenPlaysTheLatestOnReturn() throws Exception {
        goHomeAndReleaseEngine();

        // The host skips to the next song while the app is backgrounded: the
        // takeover block holds the open back (state updated only).
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // let the "play" side effects run

        assertEquals("the latest song must be the room's current one",
                V2, room.getCurrentVideoId());
        List<String> held = startedActivities();
        assertTrue("nothing may open while backgrounded: " + held, held.isEmpty());

        // The user returns to the foreground: the held-back song change plays —
        // the latest nowPlaying wins, and no other video is loaded first.
        relaunchIntoJoinScreen();
        wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertEquals("the latest room song must open on return", V2, presenter.getVideo().videoId);
        assertEquals("exactly the latest song, nothing else loaded first",
                Arrays.asList(Activity.class.getName()), startedActivities());
    }

    @Test
    public void songChangeWhileBackgroundedKeepsDeferringUntilTheForegroundReturn() throws Exception {
        goHomeAndReleaseEngine();
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // Every re-entry retry while still backgrounded keeps deferring.
        assertTrue(wired.resumeRoomSongIfNeeded("join:onResume:tick1"));
        assertTrue(wired.resumeRoomSongIfNeeded("join:onResume:tick2"));
        assertTrue("still nothing opened", startedActivities().isEmpty());

        relaunchIntoJoinScreen();
        assertFalse(wired.resumeRoomSongIfNeeded("join:onResume:tick3"));
        assertEquals(V2, presenter.getVideo().videoId);
        assertEquals(Arrays.asList(Activity.class.getName()), startedActivities());
    }

    // ---- build 2469: stale reopen cap must not survive the engine release ----

    @Test
    public void staleReopenCountersAfterEngineReleaseDoNotBlockTheReentryResume() {
        // Two mismatched loads exhaust the reopen cap while the engine is live.
        JukeboxForeground.setTestOverride(true);
        wired.onVideoLoaded(Video.from(V2));
        wired.onVideoLoaded(Video.from(V2));
        assertEquals("the cap allows two reopens of the room song",
                Arrays.asList(Activity.class.getName(), Activity.class.getName()),
                startedActivities());
        wired.onVideoLoaded(Video.from(V2));
        assertTrue("a third mismatch does not open again", startedActivities().isEmpty());

        // Home releases the engine. The cap belongs to that engine and must
        // not block the relaunch.
        goHomeAndReleaseEngine();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(startedActivities().isEmpty());
        relaunchIntoJoinScreen();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("the exact room song must be reopened despite the old cap",
                V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick",
                Arrays.asList(Activity.class.getName()), startedActivities());
    }

    @Test
    public void resumeAskWithoutAnActiveRoomLogsTheDecisionInsteadOfReturningSilently() {
        room.stop(); // the room is gone: nothing can be resumed

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("an inactive room is terminal: the retry loop must stop asking", keepAsking);
        assertTrue("nothing may open without a room", startedActivities().isEmpty());
    }

    @Test
    public void everyReentryAskDecisionIsLoggedWithItsVia() {
        relaunchIntoJoinScreen();

        // The song is loaded in a live player (setUp): asking again must not
        // open it a second time.
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse(keepAsking);
        assertTrue("nothing re-opened while the song plays", startedActivities().isEmpty());
    }
}