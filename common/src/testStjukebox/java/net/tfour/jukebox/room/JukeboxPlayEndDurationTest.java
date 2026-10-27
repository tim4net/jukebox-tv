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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * onPlayEnd when the player duration is missing (0 or unset): "ended" must
 * still be decided, from the room snapshot's duration, and when both are
 * unknown the end is accepted. The heartbeat stops on every path. The end
 * margin is one constant and one {@code isAtEnd} helper, also applied to the
 * {@code pendingPosMs} fallback. A null player is logged. A video that is
 * not the room's current song sends nothing, even inside the end window.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxPlayEndDurationTest {
    static final String V1 = "dQw4w9WgXcQ";
    /** A browse pick. Not the room song. */
    static final String V2 = "9bZkp7q19f0";
    static final long DURATION_MS = 293_000L;
    /** Inside the last 2s: a real end of {@link #DURATION_MS}. */
    static final long END_POS_MS = 292_000L;
    /** Exactly duration − 2s. Still the end. */
    static final long MARGIN_POS_MS = 291_000L;
    /** Tennessee Whiskey playhead. Not the end. */
    static final long MID_POS_MS = 61_152L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    JukeboxRoomController controller;
    PlaybackView playerView;
    final long[] positionMs = {MID_POS_MS};
    final long[] durationMs = {DURATION_MS};
    final Video[] playerVideo = {null};
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

        playerView = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
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
        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(playerView);

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
        playerView = null;
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    @Test
    public void knownPlayerDurationAtEndSendsEndedAndStopsHeartbeat() throws Exception {
        armHeartbeat(END_POS_MS, DURATION_MS);
        controller.onPlayEnd();

        assertEquals("a playhead inside the end margin of a known player duration must send ended",
                1, t.count("ended"));
        assertEquals(V1, t.only("ended").getString("videoId"));
        assertFalse("heartbeat must stop when the room song ends", heartbeatRunning());
    }

    @Test
    public void zeroPlayerDurationAtSnapshotEndSendsEndedAndStopsHeartbeat() throws Exception {
        snapshotDuration(DURATION_MS);
        armHeartbeat(END_POS_MS, 0L);
        controller.onPlayEnd();

        assertEquals("player duration 0 must fall back to the snapshot duration and send ended",
                1, t.count("ended"));
        assertFalse("heartbeat must stop on the snapshot-duration end", heartbeatRunning());
    }

    @Test
    public void zeroPlayerDurationBeforeSnapshotEndDoesNotSendEndedButStopsHeartbeat() throws Exception {
        snapshotDuration(DURATION_MS);
        armHeartbeat(MID_POS_MS, 0L);
        controller.onPlayEnd();

        assertEquals("player duration 0 and a mid-song snapshot must not send ended",
                0, t.count("ended"));
        assertFalse("heartbeat must stop even when the end is rejected", heartbeatRunning());
    }

    @Test
    public void bothDurationsUnknownSendsEndedAndStopsHeartbeat() throws Exception {
        armHeartbeat(MID_POS_MS, -1L);
        controller.onPlayEnd();

        assertEquals("both durations unknown must accept the end", 1, t.count("ended"));
        assertFalse("heartbeat must stop when an unknown duration is accepted as the end",
                heartbeatRunning());
    }

    @Test
    public void midSongWithAKnownDurationStopsHeartbeatWithoutSendingEnded() throws Exception {
        armHeartbeat(MID_POS_MS, DURATION_MS);
        controller.onPlayEnd();

        assertEquals(0, t.count("ended"));
        assertFalse("heartbeat must stop on the ignored mid-song path too", heartbeatRunning());
    }

    @Test
    public void nonRoomVideoEndingInsideTheWindowSendsNothing() throws Exception {
        // Room song V1 is loaded and its heartbeat is running. A different
        // video then reaches its own end (playhead inside the 2s window).
        // That end must not be reported as the room song's.
        armHeartbeat(END_POS_MS, DURATION_MS);
        Video other = Video.from(V2);
        playerVideo[0] = other;
        setPresenterVideo(other);
        int sentBefore = t.sent.size();

        controller.onPlayEnd();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("a non-room video ending inside the window must send nothing",
                sentBefore, t.sent.size());
        assertEquals(0, t.count("ended"));
        assertEquals("the room song must stay current", V1, room.getCurrentVideoId());
        assertNull("a non-room end must not arm the ended guard", endedVideoId());
        assertFalse("heartbeat must stop on the non-room end path too", heartbeatRunning());
    }

    @Test
    public void nullPlayerIsLogged() throws Exception {
        armHeartbeat(MID_POS_MS, -1L);
        presenter.setView(null);

        controller.onPlayEnd();

        assertTrue("a null player on play-end must be logged",
                logged("JukeboxPopup", "player==null"));
        assertEquals("a null player with both durations unknown still accepts the end",
                1, t.count("ended"));
        assertFalse(heartbeatRunning());
    }

    @Test
    public void shouldReportEndedAndIsAtEndShareOneMargin() throws Exception {
        try {
            JukeboxRoomController.class.getDeclaredField("END_REACHED_MARGIN_MS");
            fail("the end margin must be one constant; drop END_REACHED_MARGIN_MS");
        } catch (NoSuchFieldException expected) {
            // JukeboxRoomClient.END_MARGIN_MS is the only one.
        }
        Field margin = JukeboxRoomClient.class.getDeclaredField("END_MARGIN_MS");
        margin.setAccessible(true);
        long endMarginMs = margin.getLong(null);
        assertEquals(2_000L, endMarginMs);

        Method decide = JukeboxRoomController.class.getDeclaredMethod(
                "shouldReportEnded", boolean.class, long.class, long.class, long.class);
        decide.setAccessible(true);
        Method atEnd = JukeboxRoomClient.class.getDeclaredMethod("isAtEnd", long.class, long.class);
        atEnd.setAccessible(true);

        assertTrue("player duration known and at the end",
                (Boolean) decide.invoke(null, true, END_POS_MS, DURATION_MS, 0L));
        assertTrue("exactly the margin is the end",
                (Boolean) atEnd.invoke(null, DURATION_MS - endMarginMs, DURATION_MS));
        assertFalse("one millisecond earlier is not the end",
                (Boolean) atEnd.invoke(null, DURATION_MS - endMarginMs - 1, DURATION_MS));
        assertFalse("an unknown duration is not isAtEnd",
                (Boolean) atEnd.invoke(null, END_POS_MS, 0L));
        assertFalse("player duration known and mid-song wins over a short snapshot",
                (Boolean) decide.invoke(null, true, MID_POS_MS, DURATION_MS, MID_POS_MS));
        assertTrue("player duration 0 falls back to a snapshot duration at the end",
                (Boolean) decide.invoke(null, true, END_POS_MS, 0L, DURATION_MS));
        assertFalse("player duration 0 and a snapshot that is not at the end",
                (Boolean) decide.invoke(null, true, MID_POS_MS, 0L, DURATION_MS));
        assertTrue("player duration unset falls back to the snapshot",
                (Boolean) decide.invoke(null, true, END_POS_MS, -1L, DURATION_MS));
        assertTrue("both durations unknown accepts the end",
                (Boolean) decide.invoke(null, true, MID_POS_MS, 0L, -1L));
        assertTrue("both unset accepts the end",
                (Boolean) decide.invoke(null, true, -1L, -1L, -1L));
        assertFalse("a non-room video never reports",
                (Boolean) decide.invoke(null, false, END_POS_MS, 0L, 0L));
        assertFalse("a non-room video at a known end still does not report",
                (Boolean) decide.invoke(null, false, END_POS_MS, DURATION_MS, DURATION_MS));
    }

    @Test
    public void pendingFallbackInsideTheEndMarginIsNotSeeked() throws Exception {
        armHeartbeat(MARGIN_POS_MS, DURATION_MS);
        reopenFromTheFallback();

        assertTrue("a last tick inside the end margin must not be the seek target",
                logged("JukeboxPopup", "posMs=-1"));
    }

    @Test
    public void pendingFallbackBeforeTheEndMarginKeepsThePlayerTick() throws Exception {
        armHeartbeat(MID_POS_MS, DURATION_MS);
        reopenFromTheFallback();

        assertTrue("a mid-song tick stays the fallback when the room position is unknown",
                logged("JukeboxPopup", "posMs=" + MID_POS_MS));
    }

    @Test
    public void pendingFallbackUsesSnapshotDurationWhenThePlayerDurationIsUnknown() throws Exception {
        snapshotWithoutServerNow(DURATION_MS);
        armHeartbeat(MARGIN_POS_MS, 0L);
        assertEquals("the room estimate is unknown without server now", -1L, room.getRoomPositionMs(V1));
        reopenFromTheFallback();

        assertTrue("snapshot duration must reject an end-margin fallback when the player duration is 0",
                logged("JukeboxPopup", "posMs=-1"));
    }

    /** Loads the room song so the heartbeat is running and the last tick is remembered. */
    private void armHeartbeat(long pos, long dur) throws Exception {
        positionMs[0] = pos;
        durationMs[0] = dur;
        Video video = Video.from(V1);
        playerVideo[0] = video;
        setPresenterVideo(video);
        controller.onVideoLoaded(video);
        assertTrue("heartbeat must be running before play-end", heartbeatRunning());
    }

    /**
     * Room position is unknown (no server {@code now}). The resume open then
     * uses the local tick, unless that tick is at the end of a known duration.
     */
    private void reopenFromTheFallback() {
        controller.onEngineReleased();
        ShadowLog.clear();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Full position snapshot so the client keeps {@code durationMs}. */
    private void snapshotDuration(long durationMs) {
        long startedAt = 1_700_000_000_000L;
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"now\":" + (startedAt + 1_000L)
                + ",\"nowPlaying\":{\"songId\":\"s1\",\"videoId\":\"" + V1
                + "\",\"title\":\"T\",\"startedAt\":" + startedAt
                + ",\"paused\":false,\"durationMs\":" + durationMs
                + "},\"queue\":[],\"listeners\":1}");
    }

    /** Duration is stored, but no {@code now} makes the room position unknown. */
    private void snapshotWithoutServerNow(long durationMs) {
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"songId\":\"s1\",\"videoId\":\""
                + V1 + "\",\"title\":\"T\",\"startedAt\":1700000000000,\"durationMs\":" + durationMs
                + "},\"queue\":[],\"listeners\":1}");
    }

    private boolean heartbeatRunning() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mHeartbeatRunning");
        field.setAccessible(true);
        return field.getBoolean(controller);
    }

    private String endedVideoId() throws Exception {
        Field field = JukeboxRoomController.class.getDeclaredField("mEndedVideoId");
        field.setAccessible(true);
        return (String) field.get(controller);
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
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
