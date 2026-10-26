package net.tfour.jukebox.room;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import net.tfour.jukebox.update.JukeboxUpdate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Build 2466, issue (2): the upstream self-update check only ever ran from the
 * splash flow, which the stjukebox flavor (launcher = JukeboxJoinActivity)
 * never enters — no check, no offer, no log line. {@link JukeboxUpdate} drives
 * the existing AppUpdatePresenter machinery from the join screen (idle, in the
 * foreground), throttled, never during playback or from the background, and
 * logs every decision under the {@code JukeboxUpdate} tag so it is verifiable.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class JukeboxUpdateTest {
    static final String V1 = "dQw4w9WgXcQ";

    /** Records checks instead of touching AppUpdatePresenter/the network. */
    static class Recording extends JukeboxUpdate {
        int started;

        Recording(Context ctx) {
            super(ctx);
        }

        @Override
        protected void startCheck() {
            started++;
        }
    }

    // Minimal fake room wiring (same shape as JukeboxRoomClientTest's fakes).
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
        final java.util.Map<String, String> m = new java.util.HashMap<>();

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
    Recording update;
    final List<HandlerThread> threads = new ArrayList<>();

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.setTestOverride(null); // Robolectric defaults to foreground
        ShadowLog.clear();
        JukeboxUpdate.resetForTests();

        t = new FakeTransport();
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new FakeHttp(), new FakeScheduler(), new MapStore(), listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        update = new Recording(app());
        JukeboxUpdate.overrideForTests(update);
    }

    @After
    public void tearDown() throws Exception {
        JukeboxUpdate.resetForTests();
        JukeboxForeground.setTestOverride(null);
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
        if (room != null) {
            room.stop();
        }
        for (HandlerThread thread : threads) {
            thread.quitSafely();
        }
        setSingleton(JukeboxRoom.class, "sInstance", null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void activeRoomWithSong() {
        room.start();
        t.open();
        t.msg("{\"t\":\"play\",\"song\":{\"songId\":\"s1\",\"videoId\":\"" + V1
                + "\",\"title\":\"T\",\"durationMs\":null}}");
    }

    @Test
    public void skipsWhileBackgrounded() {
        JukeboxForeground.setTestOverride(false);

        update.scheduleCheck(0);

        assertEquals("no check may run from the background", 0, update.started);
        assertTrue(logged("skip: app is backgrounded"));
    }

    @Test
    public void skipsWithoutAnActiveRoom() {
        // Join screen only: no room (never joined/stopped) -> no check.
        update.scheduleCheck(0);

        assertEquals(0, update.started);
        assertTrue(logged("skip: no active room"));
    }

    @Test
    public void skipsWhileARoomSongIsActive() {
        activeRoomWithSong();

        update.scheduleCheck(0);

        assertEquals("never during playback", 0, update.started);
        assertTrue(logged("skip: room song active"));
    }

    @Test
    public void checksWhenIdleOnTheJoinScreenAndThrottlesAfterwards() {
        activeRoomWithSong();
        // The song ends / room goes idle: now the join screen is idle-in-foreground.
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");

        update.scheduleCheck(0);
        assertEquals("idle foreground must run the check", 1, update.started);
        assertTrue(logged("check: querying update manifest now"));

        update.scheduleCheck(0); // throttled: one attempt per hour
        assertEquals("the throttle must prevent an immediate re-check", 1, update.started);
        assertTrue(logged("skip: throttled"));
    }

    @Test
    public void throttledSkipStillHappensWhileBackgroundedTakesPrecedence() {
        activeRoomWithSong();
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        update.scheduleCheck(0); // marks the throttle timestamp
        assertEquals(1, update.started);

        JukeboxForeground.setTestOverride(false);
        update.scheduleCheck(0); // backgrounded wins over throttled in the log
        assertEquals(1, update.started);
        assertTrue(logged("skip: app is backgrounded"));
    }
}
