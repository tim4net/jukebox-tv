package net.tfour.jukebox.room;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

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
import org.robolectric.shadows.ShadowLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
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
 * Covered here:
 * <ul>
 * <li>a released engine / no live player is ALWAYS "not playing", regardless of
 * stale loaded-video markers (exactly the observed 2467 state, restored via
 * reflection): the relaunch resume re-opens the room's exact song, once, and
 * the decision is logged;</li>
 * <li>a clean release defers (logged) while the app is backgrounded — no
 * launch over whatever app is in use (issue #3 kept) — and opens on the
 * return to the foreground;</li>
 * <li>a stale in-flight marker (load that never settled, e.g. released
 * mid-load) cannot block the resume forever;</li>
 * <li>a song change the takeover block held back while the app was
 * backgrounded opens the LATEST song on return, with no other video loaded
 * first.</li>
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
    /** Every openVideo the presenter dispatches (exactly-once guard). */
    final List<String> opened = new ArrayList<>();
    /** Stateful proxy player: engine liveness flips when the engine is released. */
    boolean[] engineInit = {true};
    boolean[] playWhenReady = {false};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null); // Robolectric defaults to foreground
        ShadowLog.clear();
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
        setPresenterVideo(null);

        opened.clear();
        BasePlayerController recorder = new BasePlayerController() {
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
                markersSayLoadedAndLive());
        opened.clear();
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

    // ---- helpers ----

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

    /** The controller's own loaded marker, read via reflection (private state). */
    private String loadedMarker() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mLoadedVideoId");
        field.setAccessible(true);
        return (String) field.get(wired);
    }

    private String loadingMarker() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        field.setAccessible(true);
        return (String) field.get(wired);
    }

    private void setLoadingSince(long uptimeMs) throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mLoadingSinceMs");
        field.setAccessible(true);
        field.setLong(wired, uptimeMs);
    }

    private boolean markersSayLoadedAndLive() throws Exception {
        return V1.equals(loadedMarker()) && engineInit[0];
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** HOME: all our activities stopped, engine released by the stopped player. */
    private void goHomeAndReleaseEngine() {
        JukeboxForeground.setTestOverride(false); // another app (Home) is in use
        engineInit[0] = false; // the player released its engine
        wired.onEngineReleased(); // ...and told the controller so
    }

    /** Relaunch of the join screen: the user is back, nothing plays yet. */
    private void relaunchIntoJoinScreen() throws Exception {
        JukeboxForeground.setTestOverride(true);
        setPresenterVideo(null); // stale released-view ids must not matter either
    }

    // ---- requirement (1): released engine + stale markers ----

    @Test
    public void staleLoadedMarkersAfterEngineReleaseDoNotBlockTheReentryResume() throws Exception {
        goHomeAndReleaseEngine();
        // The observed 2467 on-device state: the release path left the loaded and
        // in-flight markers behind (the player view and the presenter's video
        // still carry the released song's id), so every re-entry check silently
        // believed "already loaded/loading". Restored here via reflection — the
        // fix must not trust them without a live engine.
        Field loaded = JukeboxRoomController.class.getDeclaredField("mLoadedVideoId");
        loaded.setAccessible(true);
        loaded.set(wired, V1);
        Field loading = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        loading.setAccessible(true);
        loading.set(wired, V1);
        setLoadingSince(SystemClock.uptimeMillis() - 60_000); // stale by age, too
        relaunchIntoJoinScreen();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("the exact room song must be reopened", V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick", java.util.Arrays.asList(V1), opened);
        assertTrue("the stale-marker decision must be logged",
                logged("JukeboxPopup", "loaded marker " + V1 + " is stale"));
        assertTrue("the reopen must be logged",
                logged("JukeboxPopup", "room song active but player idle"));
        assertNull("the stale loaded markers must be cleared", loadedMarker());
        // (The in-flight marker may be V1 again: the reopen dispatched a fresh load.)
    }

    @Test
    public void cleanEngineReleaseDefersWhileBackgroundedAndReopensOnReturn() throws Exception {
        goHomeAndReleaseEngine();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the posted resume check runs

        // Deferred (logged) while the app is backgrounded: no launch over
        // whatever app is in use (issue #3 kept), nothing opened.
        assertTrue("the release-time check must defer (nothing plays yet)",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));
        assertTrue("no open while backgrounded: " + opened, opened.isEmpty());
        assertNull("no launch while backgrounded",
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());

        // Relaunching the join screen while STILL backgrounded stays blocked.
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");
        assertTrue("still backgrounded: the retry must keep deferring", keepAsking);
        assertTrue("no open while backgrounded", opened.isEmpty());

        // The user is back (join screen resumed): the exact song opens, once.
        relaunchIntoJoinScreen();
        keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick2");
        assertFalse(keepAsking);
        assertEquals("the room's exact song must reopen on the re-entry",
                V1, presenter.getVideo().videoId);
        assertEquals(java.util.Arrays.asList(V1), opened);
    }

    @Test
    public void staleInFlightMarkerAloneCannotSilenceTheRoomForever() throws Exception {
        goHomeAndReleaseEngine();
        // A load that never settled (e.g. the engine was released mid-load) and
        // the clearing path never ran: only the in-flight marker is stranded.
        Field loaded = JukeboxRoomController.class.getDeclaredField("mLoadedVideoId");
        loaded.setAccessible(true);
        loaded.set(wired, null);
        Field loading = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        loading.setAccessible(true);
        loading.set(wired, V1);
        setLoadingSince(SystemClock.uptimeMillis() - 60_000); // older than the load timeout
        relaunchIntoJoinScreen();

        wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertEquals("a stale in-flight marker must not block the resume",
                V1, presenter.getVideo().videoId);
        assertEquals(java.util.Arrays.asList(V1), opened);
        assertTrue(logged("JukeboxPopup", "in-flight marker " + V1 + " is stale"));
    }

    // ---- issue #8 (2477 bug 3): the in-flight marker vs. cold launch / timeout ----

    /** Restores ONLY a young in-flight marker (no loaded marker) via reflection. */
    private void makeYoungInFlightMarker() throws Exception {
        Field loaded = JukeboxRoomController.class.getDeclaredField("mLoadedVideoId");
        loaded.setAccessible(true);
        loaded.set(wired, null);
        Field loading = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        loading.setAccessible(true);
        loading.set(wired, V1);
        setLoadingSince(SystemClock.uptimeMillis()); // young: well within the load timeout
    }

    @Test
    public void youngInFlightMarkerWithoutAPlayerClearsAndResumesTheColdLaunch() throws Exception {
        // Issue #8 (2477): a cold launch has NO player activity at all, so no
        // onVideoLoaded can ever clear a stranded marker. The resume must not
        // skip on it — no matter how young it is.
        presenter.setView(null); // cold launch: no player view/activity exists
        relaunchIntoJoinScreen();
        makeYoungInFlightMarker();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("a stranded young marker must not block the cold-launch resume",
                V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick", java.util.Arrays.asList(V1), opened);
        assertTrue("the stale-marker decision must be logged",
                logged("JukeboxPopup", "in-flight marker " + V1 + " is stale"));
        assertTrue("the no-holder reason must be logged",
                logged("JukeboxPopup", "the player/activity that started it is gone"));
    }

    @Test
    public void youngInFlightMarkerWithALivePlayerDefersUntilTheLoadTimeoutClearsIt() throws Exception {
        // A young in-flight load started by a LIVE player may still settle
        // (onVideoLoaded): the resume must defer to it and open nothing...
        relaunchIntoJoinScreen();
        makeYoungInFlightMarker(); // setUp's player view is alive, engine live

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("a young in-flight load in a live player must defer the resume", keepAsking);
        assertTrue("nothing may open while the load is in flight: " + opened, opened.isEmpty());
        assertTrue(logged("JukeboxPopup", "load of " + V1 + " in flight"));

        // ...but only until the load timeout: a load that never settles (e.g. a
        // very slow network) is a stale marker afterwards, and the room song
        // must open (timeout boundary: older than LOAD_IN_FLIGHT_TIMEOUT_MS).
        setLoadingSince(SystemClock.uptimeMillis() - 9_000);
        keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick2");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("a load older than the timeout must not block the resume",
                V1, presenter.getVideo().videoId);
        assertEquals(java.util.Arrays.asList(V1), opened);
        assertTrue("the timeout reason must be logged",
                logged("JukeboxPopup", "no load finished within 8000ms"));
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
        assertTrue("the held-back open must be logged",
                logged("JukeboxRoom", "play " + V2 + " while backgrounded: deferred"));
        assertTrue("nothing may open while backgrounded: " + opened, opened.isEmpty());
        assertNull("no launch while backgrounded",
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());

        // The user returns to the foreground: the held-back song change plays —
        // the latest nowPlaying wins, and no other video is loaded first.
        relaunchIntoJoinScreen();
        wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertEquals("the latest room song must open on return", V2, presenter.getVideo().videoId);
        assertEquals("exactly the latest song, nothing else loaded first",
                java.util.Arrays.asList(V2), opened);
    }

    @Test
    public void songChangeWhileBackgroundedKeepsDeferringUntilTheForegroundReturn() throws Exception {
        goHomeAndReleaseEngine();
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // Every re-entry retry while still backgrounded keeps deferring (logged).
        assertTrue(wired.resumeRoomSongIfNeeded("join:onResume:tick1"));
        assertTrue(wired.resumeRoomSongIfNeeded("join:onResume:tick2"));
        assertTrue("still nothing opened", opened.isEmpty());

        relaunchIntoJoinScreen();
        assertFalse(wired.resumeRoomSongIfNeeded("join:onResume:tick3"));
        assertEquals(V2, presenter.getVideo().videoId);
        assertEquals(java.util.Arrays.asList(V2), opened);
    }

    // ---- build 2469: the 2468 gazebo c1/c2 failures ----
    // Relaunching the join screen logged nothing at all (c1) or a single stale
    // in-flight marker line (c2) and no player ever started: silent decision
    // branches and stale reopen counters from before the engine release.

    /** Restores the observed pre-2469 stale reopen-counter state via reflection. */
    private void makeReopenCountersStale() throws Exception {
        Field reopenVideoId = JukeboxRoomController.class.getDeclaredField("mReopenVideoId");
        reopenVideoId.setAccessible(true);
        reopenVideoId.set(wired, V1);
        Field attempts = JukeboxRoomController.class.getDeclaredField("mReopenAttempts");
        attempts.setAccessible(true);
        attempts.setInt(wired, 2); // MAX_REOPEN_ATTEMPTS: the cap
        Field requested = JukeboxRoomController.class.getDeclaredField("mRequestedVideoId");
        requested.setAccessible(true);
        requested.set(wired, V1);
        Field capLogged = JukeboxRoomController.class.getDeclaredField("mCapLoggedVideoId");
        capLogged.setAccessible(true);
        capLogged.set(wired, V1); // the cap log is suppressed for this song, too
    }

    @Test
    public void staleReopenCountersAfterEngineReleaseDoNotBlockTheReentryResume() throws Exception {
        goHomeAndReleaseEngine();
        makeReopenCountersStale();
        relaunchIntoJoinScreen();

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("the exact room song must be reopened despite stale counters",
                V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick", java.util.Arrays.asList(V1), opened);
        assertTrue("the reopen must be logged",
                logged("JukeboxPopup", "room song active but player idle"));
        assertEquals("the stale cap must be gone: this was a fresh first attempt",
                1, reopenAttempts());
    }

    @Test
    public void resumeAskWithoutAnActiveRoomLogsTheDecisionInsteadOfReturningSilently() throws Exception {
        room.stop(); // the room is gone: nothing can be resumed

        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("an inactive room is terminal: the retry loop must stop asking", keepAsking);
        assertTrue("the decision must be logged (no silent returns)",
                logged("JukeboxPopup", "resume skipped: room inactive"));
        assertTrue("nothing may open without a room", opened.isEmpty());
    }

    @Test
    public void everyReentryAskDecisionIsLoggedWithItsVia() throws Exception {
        relaunchIntoJoinScreen();

        // The song is loaded in a live player (setUp): the ask must log the
        // skip WITH its via tag — never silently.
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse(keepAsking);
        assertTrue("the skip decision must carry the via tag",
                logged("JukeboxPopup", "(via join:onResume:tick1)"));
        assertTrue("nothing re-opened while the song plays",
                opened.isEmpty());
    }

    /** The controller's reopen-attempt counter, read via reflection (private state). */
    private int reopenAttempts() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mReopenAttempts");
        field.setAccessible(true);
        return field.getInt(wired);
    }
}