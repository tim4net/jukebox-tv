package net.tfour.jukebox.room;

import android.app.Activity;
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

import net.tfour.jukebox.JukeboxApp;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2470, the 2469 gazebo failures (steps 2/3), driven through the REAL
 * tracker + REAL gate (JukeboxApp.init, exactly like production): while the
 * app sat on HOME the room's song id kept coming from "state" snapshots, and
 * the resume check's blanket {@code isSongFromState()} wait kept every
 * relaunch/foreground-return resume deferred forever — nothing ever opened
 * (no "opening exact room videoId", no PlaybackActivity) and the user stayed
 * on the join screen.
 *
 * Covered here:
 * <ul>
 * <li>step 2: play -> HOME/engine release -> state snapshots re-adopt the same
 * song while backgrounded ("resume deferred: app backgrounded") -> relaunch
 * (join onNewIntent/onResume retry ticks, foreground) -> the snapshot-sourced
 * room song OPENS ("opening exact room videoId", PlaybackActivity started,
 * "resume after return: opening snapshot-sourced room song", NO snapshot-wait
 * line), exactly once, seeking back to the last reported position;</li>
 * <li>step 3: the snapshot switches to the next song while backgrounded, an
 * external VIEW for it is blocked, a stranded in-flight marker for it is older
 * than 15s -> relaunch -> stale-marker line, then the LATEST song opens
 * exactly once;</li>
 * <li>while backgrounded nothing ever launches;</li>
 * <li>the snapshot wait keeps its original purpose: a fresh foreground
 * snapshot (no return, no via) still never opens before the song's own
 * "play" — and that play opens it exactly once.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxResumeAfterReturnTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";
    /** Where the heartbeat/last progress of V1 left off (the cheap seek hint). */
    static final long V1_POS_MS = 42_000L;

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
    /** Stateful proxy player: engine liveness flips when the engine is released. */
    final boolean[] engineInit = {true};
    final boolean[] playWhenReady = {false};
    final long[] positionMs = {V1_POS_MS};
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
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(V1, room.getCurrentVideoId());
        assertFalse(room.isSongFromState());
        wired.onVideoLoaded(Video.from(V1)); // loaded + playing, last progress remembered
        assertTrue(playWhenReady[0]);
        // The setUp play dispatched legitimate foreground opens (the room rebind
        // resume AND the play's own open each start the player): drain them all
        // so every test below asserts only what IT launches itself.
        while (Shadows.shadowOf(app()).getNextStartedActivity() != null) { }
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

    /** An own activity of ours in the started+resumed state (the join screen). */
    private static ActivityController<Activity> joinScreenOnTop() {
        return Robolectric.buildActivity(Activity.class).setup();
    }

    /** HOME: all our activities stopped, the player released its engine. */
    private void goHome(ActivityController<Activity> join) {
        join.pause().stop();
        assertFalse("HOME means backgrounded", JukeboxForeground.isAppInForeground());
        engineInit[0] = false; // the player released its engine
        wired.onEngineReleased(); // ...and told the controller so
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the posted resume check runs
    }

    /** A state snapshot while backgrounded: re-adopts the song as snapshot-sourced. */
    private void snapshotAdopts(String songId, String videoId) {
        // The queue empties first (clears the current song), then the snapshot
        // re-adopts it — exactly how a backgrounded app ends up with a
        // snapshot-sourced song id (a "play" never re-arrives for it).
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        t.msg(JukeboxRoomClientTest.state(songId, videoId));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(videoId, room.getCurrentVideoId());
        assertTrue("the song id must be snapshot-sourced now", room.isSongFromState());
    }

    // ---- step 2: same song, snapshots while backgrounded, relaunch ----

    @Test
    public void homeThenSnapshotsThenRelaunchOpensTheSnapshotRoomSongOnce() throws Exception {
        ActivityController<Activity> join = joinScreenOnTop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // HOME mid-song: the release-time resume check defers (logged), and the
        // state snapshots keep re-adopting the very same song while we're gone.
        goHome(join);
        assertTrue("the release-time deferral must be logged",
                logged("JukeboxPopup", "resume deferred: app backgrounded"));
        snapshotAdopts("s1", V1);

        // Nothing opened or launched while backgrounded (issue #3 kept): even
        // the join retry ticks only keep deferring.
        assertTrue("a join retry tick while backgrounded must keep deferring",
                wired.resumeRoomSongIfNeeded("join:onResume:tick1"));
        assertTrue("nothing may open while backgrounded: " + opened, opened.isEmpty());
        assertNull("no activity may be started while backgrounded",
                Shadows.shadowOf(app()).getNextStartedActivity());

        // Relaunch: the join screen is back on top, its onNewIntent/onResume
        // retry ticks run in the foreground — and the snapshot-sourced song
        // OPENS now instead of waiting for a "play" that never comes.
        ActivityController<Activity> relaunched = joinScreenOnTop();
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertEquals("the room's exact song must open on the return", V1, presenter.getVideo().videoId);
        assertEquals("exactly one open, not one per tick", java.util.Arrays.asList(V1), opened);
        assertNotNull("PlaybackActivity must be started for the room song",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the exact-videoId open must be logged",
                logged("JukeboxPopup", "opening exact room videoId " + V1));
        assertTrue("the snapshot-sourced bypass must say so",
                logged("JukeboxPopup", "resume after return: opening snapshot-sourced room song " + V1));
        assertFalse("no snapshot-wait line may keep the room silent",
                logged("JukeboxPopup", "waiting for its own play"));
        assertEquals("the resume must seek back to the last reported position",
                V1_POS_MS, presenter.getVideo().pendingPosMs);

        relaunched.pause().stop().destroy();
    }

    // ---- step 3: song change via snapshots while backgrounded + stale marker ----

    @Test
    public void snapshotSongChangeAndStaleInFlightMarkerOpenTheLatestSongOnceOnReturn() throws Exception {
        ActivityController<Activity> join = joinScreenOnTop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // HOME, then the room moves on to the next song via state snapshots.
        goHome(join);
        snapshotAdopts("s2", V2);

        // An external VIEW for the new song stays blocked while backgrounded.
        viewManager.startView(PlaybackView.class);
        assertNull("no external VIEW may take over the TV while backgrounded",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the block must be verifiable", logged("JukeboxLaunch", "blocked"));

        // A load of V2 that never settled (e.g. released mid-load) left an
        // in-flight marker behind — older than the load timeout.
        Field loading = JukeboxRoomController.class.getDeclaredField("mLoadingVideoId");
        loading.setAccessible(true);
        loading.set(wired, V2);
        Field loadingSince = JukeboxRoomController.class.getDeclaredField("mLoadingSinceMs");
        loadingSince.setAccessible(true);
        loadingSince.setLong(wired, SystemClock.uptimeMillis() - 60_000);

        // Relaunch: the stale marker is logged and cleared, then the LATEST
        // (snapshot-sourced) room song opens exactly once.
        ActivityController<Activity> relaunched = joinScreenOnTop();
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertFalse("the open dispatched: the retry loop must stop asking", keepAsking);
        assertTrue("the stale in-flight marker must be logged",
                logged("JukeboxPopup", "in-flight marker " + V2 + " is stale"));
        assertEquals("the latest room song must open on the return", V2, presenter.getVideo().videoId);
        assertEquals("exactly one open", java.util.Arrays.asList(V2), opened);
        assertNotNull("PlaybackActivity must be started for the new song",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the snapshot-sourced bypass must say so",
                logged("JukeboxPopup", "resume after return: opening snapshot-sourced room song " + V2));

        // Nothing opens it a second time on the next tick.
        wired.resumeRoomSongIfNeeded("join:onResume:tick2");
        assertEquals("still exactly one open", java.util.Arrays.asList(V2), opened);

        relaunched.pause().stop().destroy();
    }

    // ---- original snapshot purpose kept ----

    @Test
    public void freshForegroundSnapshotStillWaitsForItsOwnPlay() throws Exception {
        ActivityController<Activity> join = joinScreenOnTop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // A fresh snapshot arriving in the FOREGROUND (no return, no via, no
        // deferred resume) must not open anything before the song's own play.
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        t.msg(JukeboxRoomClientTest.state("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the snapshot must be snapshot-sourced", room.isSongFromState());
        assertTrue("the snapshot alone must not open the video: " + opened, opened.isEmpty());
        assertNull("nothing may be launched by a snapshot alone",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertTrue("the wait must be logged",
                logged("JukeboxPopup", "waiting for its own play"));

        // The song's own "play" message opens it — exactly once.
        t.msg(JukeboxRoomClientTest.play("s2", V2));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the song's own play must open it", V2, presenter.getVideo().videoId);
        assertEquals(java.util.Arrays.asList(V2), opened);

        join.pause().stop().destroy();
    }
}
