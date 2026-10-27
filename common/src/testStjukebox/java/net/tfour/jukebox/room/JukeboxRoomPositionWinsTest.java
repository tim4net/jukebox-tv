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
import org.robolectric.annotation.LooperMode;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #24: warm return and cold resume were seeking to the player's own
 * last tick (Nina 2486: posMs=44266, the last heartbeat, while the room was
 * ~53s). The room position — serverNow − startedAt, frozen at pausedAt while
 * paused, plus elapsed time since the snapshot — must win whenever it is
 * known for that videoId. The player tick is only the fallback.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxRoomPositionWinsTest {
    static final String V1 = "dQw4w9WgXcQ";
    static final String V2 = "9bZkp7q19f0";
    /** Player's own last tick. The bug seeks here on a warm return. */
    static final long PLAYER_POS_MS = 44_266L;
    /** Where the room song is in the snapshot (serverNow − startedAt). */
    static final long ROOM_POS_MS = 120_000L;
    static final long DURATION_MS = 300_000L;
    static final long PAUSED_POS_MS = 65_000L;
    /** Elapsed since the snapshot; a playing room must move by this much. */
    static final long ELAPSED_MS = 10_000L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController wired;
    /** Strong ref: the presenter only keeps a WeakReference, and a GC drops getPositionMs. */
    PlaybackView playerView;
    final boolean[] engineInit = {true};
    final long[] positionMs = {PLAYER_POS_MS};
    final List<String> opened = new ArrayList<>();
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(true);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");

        playerView = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("isEngineInitialized".equals(name)) {
                            return engineInit[0];
                        }
                        if ("getPositionMs".equals(name)) {
                            return positionMs[0];
                        }
                        if ("setPlayWhenReady".equals(name)) {
                            return null;
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
        presenter.setView(playerView);
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
        assertNotNull(wired);
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
        playerView = null;
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    /** A "state" snapshot. {@code startedAtMs == null} omits the position fields. */
    private void snapshot(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                          boolean paused, Long pausedAtMs) {
        StringBuilder sb = new StringBuilder("{\"t\":\"state\",\"room\":\"room1\",\"now\":").append(serverNowMs);
        if (songId == null) {
            sb.append(",\"nowPlaying\":null");
        } else {
            sb.append(",\"nowPlaying\":{\"songId\":\"").append(songId)
                    .append("\",\"videoId\":\"").append(videoId)
                    .append("\",\"title\":\"T ").append(songId).append("\"");
            if (startedAtMs != null) {
                sb.append(",\"startedAt\":").append(startedAtMs);
            }
            if (paused) {
                sb.append(",\"paused\":true");
            }
            if (pausedAtMs != null) {
                sb.append(",\"pausedAt\":").append(pausedAtMs);
            }
            sb.append(",\"durationMs\":").append(DURATION_MS).append("}");
        }
        sb.append(",\"queue\":[],\"listeners\":1}");
        t.msg(sb.toString());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Play V1, remember the player tick, and keep the engine live so a snapshot does not resume yet. */
    private void playAndRememberPlayerTick() {
        t.msg(JukeboxRoomClientTest.play("s1", V1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        opened.clear();
        wired.onVideoLoaded(Video.from(V1));
        assertEquals(V1, room.getCurrentVideoId());
    }

    private void releaseAndResume() {
        engineInit[0] = false;
        opened.clear();
        wired.onEngineReleased();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private long openedPosMs() {
        assertNotNull("the resume/reopen must have opened a video", presenter.getVideo());
        return presenter.getVideo().pendingPosMs;
    }

    @Test
    @LooperMode(LooperMode.Mode.LEGACY)
    public void warmReturnAndColdResumeSeekToTheRoomPositionNotThePlayerTick() {
        playAndRememberPlayerTick();
        long serverNow = 1_700_000_000_000L;
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null);
        // A playing room keeps moving after the snapshot. The player tick does not.
        RuntimeEnvironment.getMasterScheduler().advanceBy(ELAPSED_MS, TimeUnit.MILLISECONDS);
        long roomNow = room.getRoomPositionMs(V1);
        assertTrue("room position must include elapsed time since the snapshot (got " + roomNow + ")",
                roomNow >= ROOM_POS_MS + ELAPSED_MS && roomNow <= ROOM_POS_MS + ELAPSED_MS + 1_000L);

        releaseAndResume();

        long posMs = openedPosMs();
        assertEquals("the room song must reopen", java.util.Arrays.asList(V1), opened);
        assertTrue("warm return / cold resume must seek to the room position " + roomNow
                        + "ms, not the player tick " + PLAYER_POS_MS + " (got " + posMs + ")",
                posMs >= ROOM_POS_MS + ELAPSED_MS && posMs <= ROOM_POS_MS + ELAPSED_MS + 1_000L);
        assertTrue("player tick + elapsed must not win (got " + posMs + ")",
                posMs != PLAYER_POS_MS && posMs != PLAYER_POS_MS + ELAPSED_MS);
    }

    @Test
    public void pausedWarmReturnFreezesAtPausedAtNotThePlayerTick() {
        playAndRememberPlayerTick();
        long serverNow = 1_700_000_000_000L;
        long startedAt = serverNow - ROOM_POS_MS;
        snapshot("s1", V1, startedAt, serverNow, true, startedAt + PAUSED_POS_MS);

        releaseAndResume();

        assertEquals("a paused room freezes at pausedAt − startedAt, not the player tick",
                PAUSED_POS_MS, openedPosMs());
    }

    @Test
    public void mismatchReopenSeeksToTheRoomPosition() {
        playAndRememberPlayerTick();
        long serverNow = 1_700_000_000_000L;
        snapshot("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null);
        opened.clear();

        wired.onVideoLoaded(Video.from(V2)); // non-room video: reopen the room song

        long posMs = openedPosMs();
        assertEquals(java.util.Arrays.asList(V1), opened);
        assertTrue("a mismatch reopen must seek to the room position, not -1 and not the player tick "
                        + PLAYER_POS_MS + " (got " + posMs + ")",
                posMs >= ROOM_POS_MS && posMs <= ROOM_POS_MS + 5_000L);
    }

    @Test
    public void resumeFallsBackToThePlayerTickWhenTheRoomPositionIsUnknown() {
        playAndRememberPlayerTick();
        // Snapshot names the song but carries no startedAt: room position is unknown.
        snapshot("s1", V1, null, 1_700_000_000_000L, false, null);
        assertEquals(-1L, room.getRoomPositionMs(V1));

        releaseAndResume();

        assertEquals("unknown room position keeps the player tick",
                PLAYER_POS_MS, openedPosMs());
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
