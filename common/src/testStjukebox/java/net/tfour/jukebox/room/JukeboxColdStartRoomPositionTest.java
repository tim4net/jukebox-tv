package net.tfour.jukebox.room;

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
import static org.junit.Assert.assertTrue;

/**
 * Issue #6 (build 2478, Nina 2477 bug (2)): after restart/reinstall the cold
 * app opened the CURRENT room song from 0 (pos=4027ms on the first heartbeat
 * while the room was ~2 minutes in) — "play" opened
 * {@code openVideo(videoId)} with no position and the 2470 resume hint had no
 * local memory on a fresh process. The room "state" snapshot carries the song
 * position though (startedAt + the server clock, like the phone/TV web apps
 * compute it), so the cold start must open/seek to the room's position — and
 * keep the plain start-at-0 open whenever the position is unknown.
 *
 * Covered here (real JukeboxRoom + real controller over the fake
 * transport/http/scheduler, foreground via the JukeboxForeground override):
 * <ul>
 * <li>cold start "play" opens the room song seeking to the snapshot position
 * (startedAt + server time elapsed), logged;</li>
 * <li>a paused snapshot freezes at pausedAt - startedAt (no drift);</li>
 * <li>the estimate is capped at the song's durationMs;</li>
 * <li>the resume/reopen path (no local position memory) seeks to the room
 * estimate too, not just the play path;</li>
 * <li>unknown position (snapshot without startedAt), a snapshot naming a
 * different song, and an idle (nowPlaying null) snapshot all keep the plain
 * open: pendingPosMs stays -1 (unchanged behavior).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxColdStartRoomPositionTest {
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";
    /** Where the room song is when the cold app joins (Nina: ~2 min in). */
    static final long ROOM_POS_MS = 120_000L;
    static final long DURATION_MS = 300_000L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController wired;
    final List<String> opened = new ArrayList<>();
    final List<HandlerThread> threads = new ArrayList<>();

    /** A real "state" snapshot with the song position fields the server sends. */
    private static String statePos(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                                   boolean paused, Long pausedAtMs, Long durationMs) {
        StringBuilder sb = new StringBuilder("{\"t\":\"state\",\"room\":\"room1\"");
        if (serverNowMs != null) {
            sb.append(",\"now\":").append(serverNowMs);
        }
        if (songId == null) {
            sb.append(",\"nowPlaying\":null");
        } else {
            sb.append(",\"nowPlaying\":{\"songId\":\"").append(songId)
                    .append("\",\"videoId\":\"").append(videoId).append("\",\"title\":\"T ").append(songId)
                    .append("\"");
            if (startedAtMs != null) {
                sb.append(",\"startedAt\":").append(startedAtMs);
            }
            if (paused) {
                sb.append(",\"paused\":true");
            }
            if (pausedAtMs != null) {
                sb.append(",\"pausedAt\":").append(pausedAtMs);
            }
            if (durationMs != null) {
                sb.append(",\"durationMs\":").append(durationMs);
            }
            sb.append("}");
        }
        sb.append(",\"queue\":[],\"listeners\":1}");
        return sb.toString();
    }

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(true); // foreground: "play" opens instead of deferring
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

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
        assertNotNull("the controller must be reflectively registered", wired);
        wired.onInit();

        room.start();
        t.open();
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

    private void coldPlay(String songId, String videoId) {
        t.msg(JukeboxRoomClientTest.play(songId, videoId));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // run the posted openVideo
    }

    private void snapshot(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                          boolean paused, Long pausedAtMs, Long durationMs) {
        t.msg(statePos(songId, videoId, startedAtMs, serverNowMs, paused, pausedAtMs, durationMs));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
    public void coldStartPlaySeeksToTheSnapshotRoomPosition() {
        // The room song started 2 minutes ago (server clock == local clock here):
        // a cold app joining now must open it AT the room's position, not at 0.
        long serverNow = System.currentTimeMillis();
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null, DURATION_MS);
        assertEquals("the snapshot song must be adopted (play still awaited)", V1, room.getCurrentVideoId());
        assertTrue(room.isSongFromState());

        coldPlay("s1", V1);
        assertEquals("the room song must open exactly once", java.util.Arrays.asList(V1), opened);
        long posMs = presenter.getVideo().pendingPosMs;
        assertTrue("must seek to the room position (got " + posMs + "ms)",
                posMs >= ROOM_POS_MS && posMs <= ROOM_POS_MS + 5_000);
        assertTrue("the room-position open must be logged",
                logged("JukeboxRoom", "opening at the room position " + posMs + "ms"));
    }

    @Test
    public void pausedSnapshotFreezesAtPausedAtExactly() {
        long serverNow = System.currentTimeMillis();
        long startedAt = serverNow - ROOM_POS_MS;
        long pausedAt = startedAt + 65_000L;
        // Host paused the song 55s into it: the room position is FROZEN there,
        // no elapsed drift while the app sits cold.
        snapshot("s1", V1, startedAt, serverNow, true, pausedAt, DURATION_MS);

        coldPlay("s1", V1);
        assertEquals("a paused room freezes at pausedAt - startedAt",
                65_000L, presenter.getVideo().pendingPosMs);
    }

    @Test
    public void snapshotPositionIsCappedAtTheSongDuration() {
        long serverNow = System.currentTimeMillis();
        // The song is 5 minutes in but only 300s long: never seek past the end.
        snapshot("s1", V1, serverNow - 400_000L, serverNow, false, null, DURATION_MS);

        coldPlay("s1", V1);
        assertEquals("the estimate must cap at durationMs", DURATION_MS, presenter.getVideo().pendingPosMs);
    }

    @Test
    public void snapshotWithoutPositionKeepsThePlainStartAtZeroOpen() {
        // The snapshot carries no startedAt (e.g. an older/other server state):
        // position unknown -> keep the current behavior, no seek.
        long serverNow = System.currentTimeMillis();
        snapshot("s1", V1, null, serverNow, false, null, null);

        coldPlay("s1", V1);
        assertEquals("unknown position must not seek (pendingPosMs stays -1)",
                -1L, presenter.getVideo().pendingPosMs);
    }

    @Test
    public void playForADifferentSongThanTheSnapshotIsNotSeekedToItsStalePosition() {
        long serverNow = System.currentTimeMillis();
        // Snapshot names s1 (2 min in); the play is for the NEXT song s2: its
        // position is unknown (its own snapshot hasn't arrived) -> start at 0.
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null, DURATION_MS);

        coldPlay("s2", V2);
        assertEquals("the next song must open (position unknown)", java.util.Arrays.asList(V2), opened);
        assertEquals("a stale position of another song must never be applied",
                -1L, presenter.getVideo().pendingPosMs);
    }

    @Test
    public void idleSnapshotClearsTheRecordedPosition() {
        long serverNow = System.currentTimeMillis();
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null, DURATION_MS);
        // The queue emptied (nowPlaying null) and a NEW song started: the old
        // recorded position must not survive into the new song's open.
        snapshot(null, null, null, serverNow, false, null, null);
        snapshot("s1", V1, null, serverNow, false, null, DURATION_MS); // new song, no position

        coldPlay("s1", V1);
        assertEquals("a cleared position must not seek", -1L, presenter.getVideo().pendingPosMs);
    }

    @Test
    public void resumePathWithoutLocalMemorySeeksToTheRoomPosition() throws Exception {
        // The other cold-start shape (Nina 2478 12:10): the app comes back with
        // the room song only in snapshots (its own play was consumed/deduped)
        // and the engine released -> the resume/reopen path must seek to the
        // room estimate too, not open at 0 (pendingPosMs was posMs=-1 in
        // "opening exact room videoId" on 2478).
        long serverNow = System.currentTimeMillis();
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null, DURATION_MS);

        wired.onEngineReleased(); // cold app: no live player, resume pending
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the posted resume check
        boolean keepAsking = wired.resumeRoomSongIfNeeded("join:onResume:tick1");

        assertEquals("the open dispatched: the retry loop must stop asking", false, keepAsking);
        assertEquals("the room's exact song must reopen exactly once", java.util.Arrays.asList(V1), opened);
        long posMs = presenter.getVideo().pendingPosMs;
        assertTrue("the reopen must seek to the room estimate (got " + posMs + "ms)",
                posMs >= ROOM_POS_MS && posMs <= ROOM_POS_MS + 5_000);
        assertTrue("the exact-videoId open must be logged",
                logged("JukeboxPopup", "opening exact room videoId " + V1));
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
}