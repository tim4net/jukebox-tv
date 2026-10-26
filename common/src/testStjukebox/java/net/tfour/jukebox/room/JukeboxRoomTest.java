package net.tfour.jukebox.room;

import android.app.Application;
import android.os.HandlerThread;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link JukeboxRoom#start()} self-healing: a live room is reused, a dead client or
   socket is rebuilt/reconnected (issue tim4net/jukebox-tv#2). Uses the package-private
 * client-factory/executor seams with fake transport/http/scheduler so no network is
 * involved and everything runs synchronously.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxRoomTest {
    static final String ORIGIN = "https://jukebox.example";

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoom room;
    final List<HandlerThread> threads = new ArrayList<>();
    int clientsBuilt;

    @Before
    public void setUp() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        t = new JukeboxRoomClientTest.FakeTransport();
        http = new JukeboxRoomClientTest.FakeHttp();
        sch = new JukeboxRoomClientTest.FakeScheduler();
        store = new JukeboxRoomClientTest.MapStore();
        clientsBuilt = 0;
        Application app = RuntimeEnvironment.getApplication();
        room = new JukeboxRoom(app, (thread, listener) -> {
            threads.add(thread);
            clientsBuilt++;
            return new JukeboxRoomClient(ORIGIN, t, http, sch, store, listener, "");
        }, Runnable::run);
    }

    @After
    public void tearDown() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        if (room != null) {
            room.stop();
        }
        for (HandlerThread thread : threads) {
            thread.quitSafely();
        }
    }

    @Test
    public void startTwiceWithLiveConnectionReusesIt() {
        room.start();
        assertEquals(1, clientsBuilt);
        assertEquals(1, http.posts.size());
        t.open();
        assertTrue(room.isActive());

        room.start(); // re-entry: keep the live socket and client
        assertEquals(1, clientsBuilt); // no rebuild
        assertEquals(1, t.urls.size()); // no reconnect
        assertEquals(1, http.posts.size()); // no new room created
        assertTrue(JukeboxMode.isRoomActive());
    }

    @Test
    public void startAfterSocketDiedReconnectsImmediately() {
        room.start();
        t.open();
        t.drop(); // socket dead
        assertEquals(1, t.urls.size());

        room.start(); // re-entry heals: same client, immediate reconnect
        assertEquals(1, clientsBuilt);
        assertEquals(2, t.urls.size());
    }

    @Test
    public void startAfterStopRebuildsClientAndReconnects() {
        room.start();
        t.open();
        assertEquals(1, clientsBuilt);

        room.stop();
        assertFalse(room.isActive());
        assertFalse(JukeboxMode.isRoomActive());
        room.start(); // dead client: rebuild it and connect again
        assertEquals(2, clientsBuilt);
        assertEquals(2, t.urls.size());
        assertTrue(room.isActive());
        assertTrue(JukeboxMode.isRoomActive());
    }

    @Test
    public void flagMirrorsCurrentSongAcrossStart() {
        assertFalse(JukeboxMode.isSongSet());
        room.start();
        t.open();
        t.msg(JukeboxRoomClientTest.play("s1", "dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", room.getCurrentVideoId());
        room.start(); // re-entry must not clear the current song
        assertEquals("dQw4w9WgXcQ", room.getCurrentVideoId());
    }
}
