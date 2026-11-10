package net.tfour.jukebox.room;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import net.tfour.jukebox.JukeboxApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2501, issue #32 (Nina, gazebo Fire TV): Feathered Indians reached a real
 * end while the app sat backgrounded, the room moved on to Lady May via a
 * "state" snapshot only, and the self-heal answered build 2466's "waiting for
 * its own play" (or the backgrounded deferral) — so the next song never started
 * and the queue stalled for ~3.5 minutes until a join-screen retry took over
 * the TV (which issue #3 must never need again).
 *
 * Covered here:
 * <ul>
 * <li>the Nina case: after a REAL room-song end, a snapshot of the NEXT song
 * opens it — both into a live engine while backgrounded (no activity started,
 * no TV takeover) and with a live idle player in the foreground (the 19:29:45
 * path); the decision log is the new "after ended"/"live engine" line, never
 * "waiting for its own play" and never a final "resume deferred: app
 * backgrounded";</li>
 * <li>a missed "play"/state event cannot stall the queue: the cheap periodic
 * idle-check scheduled by the real end opens the next song on its own;</li>
 * <li>build 2466 still holds on initial join: a snapshot-sourced song while a
 * live player plays something else, with no just-ended flag and no return
 * resume, still waits for the song's own play;</li>
 * <li>issue #3 still holds without a live engine: a room advance or join retry
 * while backgrounded and engine-less only updates state ("resume deferred: app
 * backgrounded"), nothing opens, no activity starts, and the user's return
 * opens the song;</li>
 * <li>exactly once: when both the "play" message and the resume check would
 * open the next song, it opens once.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxBackgroundAdvanceTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";
    /** ~293s song; a playhead inside the last 2s is a real end. */
    static final long DURATION_MS = 293_000L;
    static final long END_POS_MS = 292_000L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController wired;
    ViewManager viewManager;
    /** Every openVideo the presenter dispatches (exactly-once guard). */
    final List<String> opened = new ArrayList<>();
    /** Stateful proxy player: engine liveness and playhead are test-controlled. */
    final boolean[] engineInit = {true};
    final boolean[] playWhenReady = {false};
    final long[] positionMs = {61_152L};
    final long[] durationMs = {DURATION_MS};
    final Video[] playerVideo = {null};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null); // the REAL tracker answers
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("isEngineInitialized".equals(name)) {
                            return engineInit[0];
                        }
                        if ("getPlayWhenReady".equals(name)) {
                            return playWhenReady[0];
                        }
                        if ("setPlayWhenReady".equals(name) && args != null && args.length == 1) {
                            playWhenReady[0] = (Boolean) args[0];
                        }
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
        presenter = PlaybackPresenter.instance(app());
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
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient(JukeboxRoomClientTest.ORIGIN, t, http, sch, store, listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        // Production wiring: lifecycle tracker + caller-aware launch gate.
        JukeboxApp.init(app());
        viewManager = ViewManager.instance(app());
        viewManager.register(PlaybackView.class, Activity.class);
        viewManager.setRoot(Activity.class);

        wired = presenter.getController(JukeboxRoomController.class);
        assertNotNull("the controller must be reflectively registered", wired);
        wired.onInit(); // attach to the room like the real playback flow

        room.start();
        t.open();
        // The room song plays (play message -> foreground open -> loaded, live).
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        idle();
        assertEquals(V1, room.getCurrentVideoId());
        assertFalse(room.isSongFromState());
        wired.onVideoLoaded(Video.from(V1)); // loaded + playing in the live engine
        assertTrue(playWhenReady[0]);
        playerVideo[0] = Video.from(V1);
        // The setUp play dispatched a legitimate foreground open: drain it so
        // every test below asserts only what IT opens itself.
        drainStarted();
        opened.clear();
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
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    // ---- helpers ----

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void drainStarted() {
        while (Shadows.shadowOf(app()).getNextStartedActivity() != null) { }
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

    /** The current room song V1 really ends (playhead inside the end margin). */
    private void endCurrentSong() throws Exception {
        positionMs[0] = END_POS_MS;
        wired.onPlayEnd();
        assertEquals("the real end must be reported", 1, t.count("ended"));
    }

    /** The room's current song becomes videoId via a state snapshot ONLY. */
    private void snapshotAdvancesTo(String songId, String videoId) {
        t.msg(JukeboxRoomClientTest.state(songId, videoId));
        idle();
        assertEquals(videoId, room.getCurrentVideoId());
        assertTrue("the song id must be snapshot-sourced now", room.isSongFromState());
    }

    // ---- A: the Nina case (Lady May) ----

    @Test
    public void endedThenSnapshotOpensNextSongIntoLiveEngineWhileBackgrounded() throws Exception {
        // Nina 19:29: the launcher is on top, but the song played to its real
        // end and the engine is still initialized. The next room song arrives
        // via a state snapshot only — it must load into that engine, and the
        // app must NOT come to the foreground (no activity started).
        JukeboxForeground.setTestOverride(false);

        endCurrentSong();
        snapshotAdvancesTo("s2", V2);

        assertEquals("the exact next room videoId must be loaded", V2, presenter.getVideo().videoId);
        assertEquals("exactly one open", Arrays.asList(V2), opened);
        assertNull("no activity may be started over the launcher (issue #3)",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the decision must be the new after-ended line",
                logged("JukeboxPopup", "after ended"));
        assertTrue("the live-engine open must say so",
                logged("JukeboxPopup", "live engine"));
        assertFalse("the 2466 snapshot wait must not apply after a real end",
                logged("JukeboxPopup", "waiting for its own play"));
        assertFalse("a live engine must not defer to the user's return",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));
    }

    @Test
    public void endedThenSnapshotOpensNextSongWithAForegroundIdlePlayer() throws Exception {
        // The 19:29:45 path: the app still reads foreground, the player is live
        // but idle (the song just ended). The snapshot of the next song is
        // enough to open it — 2466's "waiting for its own play" must not stall
        // the queue here either.
        JukeboxForeground.setTestOverride(true);

        endCurrentSong();
        snapshotAdvancesTo("s2", V2);

        assertEquals("the exact next room videoId must be loaded", V2, presenter.getVideo().videoId);
        assertEquals("exactly one open", Arrays.asList(V2), opened);
        assertTrue(logged("JukeboxPopup", "after ended"));
        assertFalse("the 2466 snapshot wait must not apply after a real end",
                logged("JukeboxPopup", "waiting for its own play"));
    }

    // ---- the periodic idle-check (a missed event cannot stall the queue) ----

    @Test
    public void missedStateEventStillAdvancesViaThePeriodicIdleCheck() throws Exception {
        // The state event never reaches the controller (dropped listener /
        // mistimed notification): without the periodic idle-check scheduled by
        // the real end, the room sits silent until the user returns (issue #32).
        JukeboxForeground.setTestOverride(false);
        room.removeListener(wired); // the state broadcast is missed

        endCurrentSong();
        t.msg(JukeboxRoomClientTest.state("s2", V2)); // adoption without notification
        idle();
        assertTrue("the missed event alone must not open anything: " + opened, opened.isEmpty());

        ShadowSystemClock.advanceBy(Duration.ofMillis(6_000)); // one 5s poll tick
        idle();

        assertEquals("the periodic idle-check must open the next room song",
                V2, presenter.getVideo().videoId);
        assertEquals("exactly one open", Arrays.asList(V2), opened);
        assertNull("backgrounded with a live engine: still no activity (issue #3)",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue(logged("JukeboxPopup", "after ended"));
        assertFalse(logged("JukeboxPopup", "waiting for its own play"));
    }

    // ---- B: 2466 still holds on initial join ----

    @Test
    public void livePlayerSnapshotWithoutAnEndStillWaitsForItsOwnPlay() throws Exception {
        // A fresh snapshot while a live player already plays V1, with no
        // just-ended flag and no return resume: the song's own "play" stays the
        // only instruction to open its video (build 2466 rule, unchanged).
        JukeboxForeground.setTestOverride(true);

        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        idle();
        snapshotAdvancesTo("s2", V2);

        assertTrue("the snapshot alone must not open the video: " + opened, opened.isEmpty());
        assertTrue("the wait must be logged",
                logged("JukeboxPopup", "waiting for its own play"));
        assertFalse(logged("JukeboxPopup", "after ended"));

        // The song's own "play" opens it — exactly once.
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        idle();
        assertEquals("the song's own play must open it", V2, presenter.getVideo().videoId);
        assertEquals(Arrays.asList(V2), opened);
    }

    // ---- C: issue #3 still holds without a live engine ----

    @Test
    public void backgroundedWithNoLiveEngineStillDefersUntilTheUserReturns() throws Exception {
        engineInit[0] = false; // Home released the engine
        JukeboxForeground.setTestOverride(false);
        wired.onEngineReleased(); // posts the release-time resume check
        idle();
        assertTrue("the deferral must still be logged",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));

        // The room advances while backgrounded and engine-less: state only.
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        idle();
        assertEquals("state is still tracked", V2, room.getCurrentVideoId());
        assertTrue("nothing may open without a live engine: " + opened, opened.isEmpty());
        assertNull("no activity may be started from the background",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue(logged("JukeboxRoom", "deferred"));

        // The user returns: the song opens then (and only then).
        JukeboxForeground.setTestOverride(true);
        assertFalse(wired.resumeRoomSongIfNeeded("join:onResume:tick1"));
        assertEquals("the exact song must open on return", V2, presenter.getVideo().videoId);
    }

    // ---- D: exactly once when onPlay and the resume check would both fire ----

    @Test
    public void playAfterAnEndOpensIntoTheLiveEngineExactlyOnceWhileBackgrounded() throws Exception {
        JukeboxForeground.setTestOverride(false);

        endCurrentSong();
        t.msg(JukeboxRoomClientTest.play("s2", V2)); // the "play" DOES arrive
        idle();

        assertEquals("the play must open the next song into the live engine",
                V2, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per trigger", Arrays.asList(V2), opened);
        assertNull("backgrounded: no activity may start (issue #3)",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue(logged("JukeboxRoom", "opening into live engine"));
        assertFalse("a live engine must not defer the play",
                logged("JukeboxRoom", "deferred"));
    }
}
