package net.tfour.jukebox.room;

import android.app.Application;
import android.os.HandlerThread;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void songFromStateFlagMirrorsClient() {
        room.start();
        t.open();
        assertFalse(room.isSongFromState());
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"songId\":\"s1\",\"videoId\":\"dQw4w9WgXcQ\",\"title\":\"Hey\"},\"queue\":[],\"listeners\":1}");
        assertTrue(room.isSongFromState()); // snapshot id adopted, play still awaited
        t.msg(JukeboxRoomClientTest.play("s1", "dQw4w9WgXcQ"));
        assertFalse(room.isSongFromState()); // the play went through
    }

    @Test
    public void backToBackStartWithAsyncExecutorReusesClientAndSingleStart() {
        // Async executor: posted work runs only when flushed, like the real
        // HandlerThread (onCreate/onStart race their client::start posts).
        final List<Runnable> queued = new ArrayList<>();
        final int[] built = {0};
        JukeboxRoom asyncRoom = new JukeboxRoom(RuntimeEnvironment.getApplication(),
                (thread, listener) -> {
                    threads.add(thread);
                    built[0]++;
                    return new JukeboxRoomClient(ORIGIN, t, http, sch, store, listener, "");
                },
                queued::add);

        asyncRoom.start(); // cold start: client built, start queued (not yet run)
        asyncRoom.start(); // re-entry before the queued start ran
        assertEquals(1, built[0]); // must NOT tear down and rebuild the client
        assertEquals(1, queued.size()); // and must NOT post a duplicate start

        runAll(queued); // the room thread finally runs client.start()
        assertEquals(1, http.posts.size()); // exactly one room was created
        assertEquals(1, t.urls.size()); // exactly one socket attempt (handshake in flight)

        asyncRoom.start(); // re-entry while the handshake is still in flight
        runAll(queued);
        assertEquals(1, t.urls.size()); // the in-flight handshake was not cancelled

        t.open();
        assertTrue(asyncRoom.isActive());
        assertTrue(JukeboxMode.isRoomActive());
        asyncRoom.stop();
    }

    @Test
    public void joinByCodeSwitchesRoomAndCallsBack() {
        room.start();
        t.open();
        http.stateResponse = "{\"room\":\"joinedroomid1234567890\",\"queue\":[]}";
        final List<String> joined = new ArrayList<>();
        final List<JukeboxRoomClient.JoinError> failed = new ArrayList<>();
        room.joinByCode("taught", new JukeboxRoom.JoinCallback() {
            @Override
            public void onJoined() {
                joined.add("yes");
            }

            @Override
            public void onJoinFailed(JukeboxRoomClient.JoinError error) {
                failed.add(error);
            }
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle(); // the callback posts to the main handler
        assertEquals(Arrays.asList("yes"), joined);
        assertTrue(failed.isEmpty());
        assertEquals("joinedroomid1234567890", store.get("room"));
        assertEquals("TAUGHT", store.get("code"));
        assertEquals("room1", store.get("own_room"));
        assertTrue(room.isJoinedRoom());
        assertEquals("joinedroomid1234567890", room.getRoom());
    }

    @Test
    public void joinByCodeUnknownCodeCallsBackWithNotFound() {
        room.start();
        t.open();
        http.joinFails404 = true;
        final List<JukeboxRoomClient.JoinError> failed = new ArrayList<>();
        room.joinByCode("TAUGHT", new JukeboxRoom.JoinCallback() {
            @Override
            public void onJoined() {
                fail("unknown code must fail");
            }

            @Override
            public void onJoinFailed(JukeboxRoomClient.JoinError error) {
                failed.add(error);
            }
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(JukeboxRoomClient.JoinError.NOT_FOUND), failed);
        assertEquals("room1", store.get("room"));
        assertFalse(room.isJoinedRoom());
    }

    @Test
    public void backToOwnRoomSwitchesBack() {
        room.start();
        t.open();
        http.stateResponse = "{\"room\":\"joinedroomid1234567890\",\"queue\":[]}";
        room.joinByCode("TAUGHT", new JukeboxRoom.JoinCallback() {
            @Override
            public void onJoined() {
            }

            @Override
            public void onJoinFailed(JukeboxRoomClient.JoinError error) {
                fail("join must succeed: " + error);
            }
        });
        assertTrue(room.isJoinedRoom());
        http.stateResponse = "{\"room\":\"room1\",\"queue\":[]}";
        room.backToOwnRoom();
        assertEquals("room1", store.get("room"));
        assertFalse(room.isJoinedRoom());
    }

    private static void runAll(List<Runnable> queue) {
        List<Runnable> copy = new ArrayList<>(queue);
        queue.clear();
        for (Runnable r : copy) {
            r.run();
        }
    }
}
