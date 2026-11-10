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
import org.robolectric.shadows.ShadowLog;

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
 * Issue #13 (Nina 2488 gazebo): the cold open seeks to the room position at
 * {@code openVideo} time, and the first frame is 11-12s later, so playback
 * starts 4.3-4.9s behind the room and never catches up.
 *
 * <p>The first {@code onPlay} after the room song is loaded recomputes the
 * room position and seeks once when the playhead is more than 1s off. A later
 * {@code onPlay} (pause/resume) does not seek. Drift of half a second, an
 * unknown room position ({@code -1}), and a paused room do not seek. A
 * playhead more than 1s ahead seeks back. The next song's load gets its own
 * one-shot seek. A room song that changes before the first play does not. A
 * first {@code onPlay} before any song is loaded does not seek and does not throw.
 *
 * <p>The player position stands in for the position captured at open time.
 * {@code setPositionMs} is recorded and does not move the playhead, so a
 * second {@code onPlay} still sees the same drift.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxColdStartDriftCatchUpTest {
    static final String V1 = "dQw4w9WgXcQ";
    static final String V2 = "9bZkp7q19f0";
    static final long ROOM_POS_MS = 120_000L;
    static final long DURATION_MS = 300_000L;
    static final long SERVER_EPOCH_MS = 1_700_000_000_000L;
    /** Nina 2488: playback lands about 4.5s behind the room. */
    static final long DRIFT_BEHIND_MS = 4_500L;
    /** Inside the 1s band: leave the playhead where it is. */
    static final long DRIFT_WITHIN_MS = 500L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController wired;
    final List<Long> seeks = new ArrayList<>();
    final long[] playerPos = {0L};
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getPositionMs".equals(method.getName())) {
                            return playerPos[0];
                        }
                        if ("setPositionMs".equals(method.getName()) && args != null && args.length == 1) {
                            seeks.add((Long) args[0]);
                            return null;
                        }
                        if ("getDurationMs".equals(method.getName())) {
                            return DURATION_MS;
                        }
                        if ("isEngineInitialized".equals(method.getName())) {
                            return true;
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

        wired = presenter.getController(JukeboxRoomController.class);
        assertTrue("the controller must be reflectively registered", wired != null);
        wired.onInit();

        room.start();
        t.open();
        seeks.clear();
        playerPos[0] = 0L;
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

    /** A playing snapshot whose position is {@link #ROOM_POS_MS} plus elapsed time. */
    private void playingSnapshot() {
        long startedAt = SERVER_EPOCH_MS;
        snapshot("s1", V1, startedAt, startedAt + ROOM_POS_MS, false, null, DURATION_MS);
    }

    private void snapshot(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                          boolean paused, Long pausedAtMs, Long durationMs) {
        postState(songId, videoId, startedAtMs, serverNowMs, paused, pausedAtMs, durationMs);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Delivers a state snapshot without draining the main looper. The room's
     * current song and position update on this thread; listener work stays queued.
     */
    private void postState(String songId, String videoId, Long startedAtMs, Long serverNowMs,
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
        t.msg(sb.toString());
    }

    /** Load the room song so {@code mLoadedVideoId} is the room's current video. */
    private void loadRoomSong() throws Exception {
        assertEquals(V1, room.getCurrentVideoId());
        wired.onVideoLoaded(Video.from(V1));
        assertEquals("the room song must be loaded before the first onPlay", V1, loadedVideoId());
        seeks.clear();
        ShadowLog.clear();
    }

    /** Park the playhead {@code driftMs} behind the live room position. */
    private long parkBehind(long driftMs) {
        long roomPos = room.getRoomPositionMs(V1);
        assertTrue("room position must be known (got " + roomPos + ")", roomPos >= driftMs);
        playerPos[0] = roomPos - driftMs;
        return roomPos;
    }

    private String loadedVideoId() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mLoadedVideoId");
        field.setAccessible(true);
        return (String) field.get(wired);
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static int countLogs(String tag, String fragment) {
        int n = 0;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                n++;
            }
        }
        return n;
    }

    /** First logged throwable, or null. A swallowed NPE still shows up here. */
    private static Throwable loggedThrowable() {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.throwable != null) {
                return item.throwable;
            }
        }
        return null;
    }

    @Test
    public void driftOfFourAndAHalfSecondsSeeksOnceToTheRoomPosition() throws Exception {
        playingSnapshot();
        loadRoomSong();
        long roomBefore = parkBehind(DRIFT_BEHIND_MS);
        assertTrue(Math.abs(roomBefore - playerPos[0]) > 1_000L);

        wired.onPlay();

        assertEquals("drift of 4.5s must seek exactly once: " + seeks, 1, seeks.size());
        long seek = seeks.get(0);
        long roomAfter = room.getRoomPositionMs(V1);
        assertEquals("the seek target is the room position recomputed on first play",
                roomAfter, seek);
        assertTrue("room position must cover the open-time position (" + roomBefore
                        + " -> " + roomAfter + ")",
                seek >= roomBefore && seek <= roomAfter);
        assertTrue("the one-shot seek must be logged",
                logged("JukeboxPopup", "seeking once to room position " + seek + "ms"));
        assertEquals("one seek, one log line", 1,
                countLogs("JukeboxPopup", "seeking once to room position "));
    }

    @Test
    public void driftOfHalfASecondDoesNotSeek() throws Exception {
        playingSnapshot();
        loadRoomSong();
        long roomBefore = parkBehind(DRIFT_WITHIN_MS);
        assertTrue(Math.abs(roomBefore - playerPos[0]) <= 1_000L);

        wired.onPlay();

        assertEquals("drift of 0.5s must not seek: " + seeks, 0, seeks.size());
        assertEquals("the room clock must stay put across the check",
                roomBefore, room.getRoomPositionMs(V1));
        assertEquals(0, countLogs("JukeboxPopup", "seeking once to room position "));
    }

    @Test
    public void secondOnPlayAfterSeekDoesNotSeekAgain() throws Exception {
        playingSnapshot();
        loadRoomSong();
        parkBehind(DRIFT_BEHIND_MS);

        wired.onPlay();
        assertEquals("the first play seeks once: " + seeks, 1, seeks.size());
        long firstSeek = seeks.get(0);
        assertEquals(room.getRoomPositionMs(V1), firstSeek);

        // Pause/resume fires onPlay again. The playhead is still 4.5s behind
        // (the recorded seek does not move it), and this call must not seek.
        parkBehind(DRIFT_BEHIND_MS);
        wired.onPlay();

        assertEquals("a later onPlay must not seek again: " + seeks, 1, seeks.size());
        assertEquals(firstSeek, (long) seeks.get(0));
        assertEquals("the one-shot seek is logged once", 1,
                countLogs("JukeboxPopup", "seeking once to room position "));
    }

    @Test
    public void unknownRoomPositionDoesNotSeek() throws Exception {
        long serverNow = SERVER_EPOCH_MS + ROOM_POS_MS;
        // No startedAt: the room position is unknown (-1). The open stays at 0
        // and the first frame must not invent a seek.
        snapshot("s1", V1, null, serverNow, false, null, DURATION_MS);
        assertEquals(-1L, room.getRoomPositionMs(V1));
        loadRoomSong();
        playerPos[0] = 0L;

        wired.onPlay();

        assertEquals("roomPos -1 must not seek: " + seeks, 0, seeks.size());
        assertEquals(-1L, room.getRoomPositionMs(V1));
        assertEquals(0, countLogs("JukeboxPopup", "seeking once to room position "));
    }

    /**
     * No {@code startedAt}: the room position is {@code -1}. The playhead sits
     * more than 1s away from {@code -1}, so dropping the {@code roomPos < 0}
     * guard would seek to {@code -1}. The first play must not seek.
     */
    @Test
    public void unknownRoomPositionFarFromThePlayheadDoesNotSeek() throws Exception {
        long serverNow = SERVER_EPOCH_MS + ROOM_POS_MS;
        snapshot("s1", V1, null, serverNow, false, null, DURATION_MS);
        assertEquals(-1L, room.getRoomPositionMs(V1));
        loadRoomSong();
        playerPos[0] = ROOM_POS_MS;
        assertTrue("the playhead must be more than 1s from -1",
                Math.abs(-1L - playerPos[0]) > 1_000L);

        wired.onPlay();

        assertEquals("roomPos -1 must not seek to -1: " + seeks, 0, seeks.size());
        assertEquals(-1L, room.getRoomPositionMs(V1));
        assertEquals(0, countLogs("JukeboxPopup", "seeking once to room position "));
    }

    /**
     * Song A loads and its first play consumes the one-shot. Song B then loads
     * with more than 1s of drift. B's first play seeks once, to B's room
     * position. Removing the reset in {@code setLoadedVideoId} would leave A's
     * one-shot set and B would not seek.
     */
    @Test
    public void nextSongAfterConsumedCatchUpSeeksOnceToTheNewRoomPosition() throws Exception {
        playingSnapshot();
        loadRoomSong();
        parkBehind(DRIFT_BEHIND_MS);

        wired.onPlay();
        assertEquals("song A consumes its one-shot seek: " + seeks, 1, seeks.size());
        long seekA = seeks.get(0);
        seeks.clear();
        ShadowLog.clear();

        // A different room position from A's, so a leftover seek to A cannot pass.
        // Do not idle: A's heartbeat is already posted, and the resume check is
        // not what re-arms the one-shot. Loading B is.
        final long roomPosB = 50_000L;
        long startedAt = SERVER_EPOCH_MS;
        postState("s2", V2, startedAt, startedAt + roomPosB, false, null, DURATION_MS);
        assertEquals(V2, room.getCurrentVideoId());
        wired.onVideoLoaded(Video.from(V2));
        assertEquals("song B must be the loaded room song", V2, loadedVideoId());
        long roomBefore = room.getRoomPositionMs(V2);
        assertTrue("song B's room position must be known (got " + roomBefore + ")",
                roomBefore >= DRIFT_BEHIND_MS);
        playerPos[0] = roomBefore - DRIFT_BEHIND_MS;
        assertTrue(Math.abs(roomBefore - playerPos[0]) > 1_000L);

        wired.onPlay();

        assertEquals("song B's first play must seek exactly once: " + seeks, 1, seeks.size());
        long seekB = seeks.get(0);
        long roomAfter = room.getRoomPositionMs(V2);
        assertEquals("the seek target is B's room position", roomAfter, seekB);
        assertTrue("B's seek (" + seekB + ") is not A's leftover seek (" + seekA + ")",
                seekB != seekA);
        assertTrue(seekB >= roomBefore && seekB <= roomAfter);
        assertEquals("one seek, one log line", 1,
                countLogs("JukeboxPopup", "seeking once to room position "));
    }

    /**
     * The room song is loaded, then the room's current song changes before the
     * first play. That play must not seek. Replacing the loaded-equals-room
     * check with {@code if (false)} would seek to the loaded song's position.
     */
    @Test
    public void roomSongChangeBetweenLoadAndFirstPlayDoesNotSeek() throws Exception {
        playingSnapshot();
        loadRoomSong();
        long roomPosA = parkBehind(DRIFT_BEHIND_MS);
        assertTrue(Math.abs(roomPosA - playerPos[0]) > 1_000L);
        assertEquals(V1, loadedVideoId());

        // A "play" for B changes the room's current song and does not replace
        // A's position snapshot. Leave the posted open/resume queued: idling
        // it would clear the loaded marker before this first play.
        t.msg("{\"t\":\"play\",\"song\":{\"songId\":\"s2\",\"videoId\":\"" + V2
                + "\",\"title\":\"T s2\",\"durationMs\":null}}");
        assertEquals(V2, room.getCurrentVideoId());
        assertEquals("the loaded song is still A", V1, loadedVideoId());
        long stillA = room.getRoomPositionMs(V1);
        assertTrue("A's room position must still be known (got " + stillA + ")", stillA >= 0L);
        assertEquals(-1L, room.getRoomPositionMs(V2));

        wired.onPlay();

        assertEquals("loaded A while the room says B must not seek: " + seeks, 0, seeks.size());
        assertEquals(V1, loadedVideoId());
        assertEquals(0, countLogs("JukeboxPopup", "seeking once to room position "));
    }

    /**
     * The playhead is more than 1s ahead of the room. The first play seeks
     * back to the room position. Dropping {@code Math.abs} would see a
     * negative delta and not seek.
     */
    @Test
    public void playheadAheadOfTheRoomSeeksBackOnce() throws Exception {
        playingSnapshot();
        loadRoomSong();
        long roomBefore = room.getRoomPositionMs(V1);
        assertTrue("room position must be known (got " + roomBefore + ")", roomBefore >= 0L);
        playerPos[0] = roomBefore + DRIFT_BEHIND_MS;
        assertTrue("the playhead must be more than 1s ahead",
                playerPos[0] - roomBefore > 1_000L);

        wired.onPlay();

        assertEquals("a playhead 4.5s ahead must seek exactly once: " + seeks, 1, seeks.size());
        long seek = seeks.get(0);
        long roomAfter = room.getRoomPositionMs(V1);
        assertEquals("the seek target is the room position recomputed on first play",
                roomAfter, seek);
        assertTrue("the seek must move the playhead back (" + playerPos[0] + " -> " + seek + ")",
                seek < playerPos[0]);
        assertTrue(seek >= roomBefore && seek <= roomAfter);
        assertEquals("one seek, one log line", 1,
                countLogs("JukeboxPopup", "seeking once to room position "));
    }

    /**
     * No song has been loaded ({@code mLoadedVideoId} is null). A player is
     * present and the room position is more than 1s from the playhead. The
     * first {@code onPlay} runs {@code catchUpColdStartDriftOnce} and must not
     * seek and must not throw. Removing {@code || mLoadedVideoId == null}
     * NPEs on {@code mLoadedVideoId.equals} (or, if that NPE is caught, seeks
     * or logs the throwable). Either way an assertion here fails.
     */
    @Test
    public void driftCheckBeforeAnySongIsLoadedDoesNotSeekOrThrow() throws Exception {
        playingSnapshot();
        assertNull("no song is loaded yet", loadedVideoId());
        assertTrue("a player must be present for this check", wired.getPlayer() != null);
        long roomPos = room.getRoomPositionMs(V1);
        playerPos[0] = 0L;
        assertTrue("room position must be known and more than 1s from the playhead (got "
                        + roomPos + ")",
                roomPos > 1_000L);
        seeks.clear();
        ShadowLog.clear();

        Throwable thrown = null;
        try {
            wired.onPlay();
        } catch (Throwable t) {
            thrown = t;
        }

        assertEquals("a drift check before any song is loaded must not seek: " + seeks,
                0, seeks.size());
        assertNull("a drift check before any song is loaded must not throw: " + thrown, thrown);
        assertNull("a drift check before any song is loaded must not log a throwable: "
                        + loggedThrowable(),
                loggedThrowable());
        assertEquals(0, countLogs("JukeboxPopup", "seeking once to room position "));
        assertNull("the loaded id stays null", loadedVideoId());
    }

    @Test
    public void pausedRoomDoesNotSeekEvenWhenDriftExceedsOneSecond() throws Exception {
        long startedAt = SERVER_EPOCH_MS;
        long pausedAt = startedAt + 65_000L;
        snapshot("s1", V1, startedAt, startedAt + 200_000L, true, pausedAt, DURATION_MS);
        assertEquals(65_000L, room.getRoomPositionMs(V1));
        assertTrue(room.isSnapshotPaused(V1));
        loadRoomSong();
        playerPos[0] = 65_000L - DRIFT_BEHIND_MS;

        wired.onPlay();

        assertEquals("a paused room must not seek: " + seeks, 0, seeks.size());
        assertEquals(65_000L, room.getRoomPositionMs(V1));
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        setSingleton(clazz, fieldName, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
