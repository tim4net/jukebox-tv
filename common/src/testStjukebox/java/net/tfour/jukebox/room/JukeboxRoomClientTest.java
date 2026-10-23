package net.tfour.jukebox.room;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxRoomClientTest {
    static final String ORIGIN = "https://jukebox.example";
    static final String V1 = "dQw4w9WgXcQ", V2 = "9bZkp7q19f0";

    static class FakeTransport implements JukeboxRoomClient.Transport {
        final List<String> sent = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        Callbacks cb;
        boolean up;
        public void connect(String url, Callbacks c) { urls.add(url); cb = c; }
        public boolean send(String text) { if (!up) return false; sent.add(text); return true; }
        public void close() { up = false; }
        void open() { up = true; cb.onOpen(); }
        void drop() { up = false; cb.onFailure(new IOException("x")); }
        void msg(String s) { cb.onMessage(s); }
        int count(String type) throws Exception {
            int n = 0;
            for (String s : sent) if (type.equals(new JSONObject(s).getString("t"))) n++;
            return n;
        }
        JSONObject only(String type) throws Exception {
            JSONObject found = null;
            int n = 0;
            for (String s : sent) {
                JSONObject o = new JSONObject(s);
                if (type.equals(o.getString("t"))) { found = o; n++; }
            }
            assertEquals(1, n);
            return found;
        }
    }

    static class FakeHttp implements JukeboxRoomClient.Http {
        final List<String> posts = new ArrayList<>();
        String stateResponse; boolean stateFails; boolean stateTransient;
        int created;
        public String postJson(String url, String body) {
            posts.add(url);
            created++;
            return "{\"room\":\"room" + created + "\",\"code\":\"CODE" + created + "\"}";
        }
        public String get(String url) throws IOException {
            if (stateFails) throw new JukeboxRoomClient.HttpStatusException(404);
            if (stateTransient) throw new IOException("offline");
            return stateResponse;
        }
    }

    static class FakeScheduler implements JukeboxRoomClient.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        public void schedule(Runnable r, long d) { tasks.add(r); delays.add(d); }
        public void cancelAll() { tasks.clear(); }
        void runAll() { List<Runnable> t = new ArrayList<>(tasks); tasks.clear(); for (Runnable r : t) r.run(); }
    }

    static class MapStore implements JukeboxRoomClient.Store {
        final Map<String, String> m = new HashMap<>();
        public String get(String k) { return m.get(k); }
        public void put(String k, String v) { if (v == null) m.remove(k); else m.put(k, v); }
    }

    static class Rec implements JukeboxRoomClient.Listener {
        final List<String> plays = new ArrayList<>();
        String room, code, joinUrl, status, np; int listeners;
        public void onRoomReady(String r, String c, String j) { room = r; code = c; joinUrl = j; }
        public void onPlay(String s, String v, String t) { plays.add(v); }
        public void onStatus(String s) { status = s; }
        public void onState(String n, int l) { np = n; listeners = l; }
    }

    FakeTransport t; FakeHttp http; FakeScheduler sch; MapStore store; Rec rec; JukeboxRoomClient c;

    static String play(String songId, String videoId) {
        return "{\"t\":\"play\",\"song\":{\"songId\":\"" + songId + "\",\"videoId\":\"" + videoId + "\",\"title\":\"T " + songId + "\",\"durationMs\":null}}";
    }

    @Before
    public void setUp() {
        t = new FakeTransport(); http = new FakeHttp(); sch = new FakeScheduler(); store = new MapStore(); rec = new Rec();
        c = new JukeboxRoomClient(ORIGIN, t, http, sch, store, rec, "");
    }

    @Test
    public void createsRoomConnectsAndSaysHello() throws Exception {
        c.start();
        assertEquals(ORIGIN + "/api/rooms", http.posts.get(0));
        assertEquals("room1", rec.room);
        assertEquals("CODE1", rec.code);
        assertEquals(ORIGIN + "/r/room1", rec.joinUrl);
        assertEquals("wss://jukebox.example/api/r/room1/ws", t.urls.get(0));
        assertEquals("room1", store.get("room"));
        t.open();
        assertEquals(1, t.count("bridge-hello"));
        assertEquals("Connected", rec.status);
    }

    @Test
    public void reusesLiveSavedRoomAndReplacesDeadOne() {
        store.put("room", "saved"); store.put("code", "SAVED1");
        http.stateResponse = "{\"room\":\"saved\",\"queue\":[]}";
        c.start();
        assertTrue(http.posts.isEmpty());
        assertEquals("SAVED1", rec.code);

        setUp();
        store.put("room", "saved"); store.put("code", "SAVED1");
        http.stateFails = true;
        c.start();
        assertEquals(1, http.posts.size());
        assertEquals("CODE1", rec.code);
    }

    @Test
    public void transientStateCheckErrorKeepsSavedRoom() {
        store.put("room", "saved"); store.put("code", "SAVED1");
        http.stateTransient = true;
        c.start();
        assertTrue(http.posts.isEmpty());
        assertEquals("SAVED1", rec.code);
        assertEquals("wss://jukebox.example/api/r/saved/ws", t.urls.get(0));
    }

    @Test
    public void opensEachSongInRoomOrderAndDedupes() {
        c.start(); t.open();
        t.msg(play("s1", V1));
        t.msg(play("s1", V1)); // repeat (e.g. after state churn) must not reopen
        t.msg(play("s2", V2));
        assertEquals(java.util.Arrays.asList(V1, V2), rec.plays);
    }

    @Test
    public void ignoresInvalidVideoId() {
        c.start(); t.open();
        t.msg(play("s1", "bad<id>"));
        assertTrue(rec.plays.isEmpty());
    }

    @Test
    public void songEndSendsEndedExactlyOnceAndOnlyForCurrentSong() throws Exception {
        c.start(); t.open();
        t.msg(play("s1", V1));
        c.songEnded(V2); // some other (non-room) video ended
        assertEquals(0, t.count("ended"));
        c.songEnded(V1);
        c.songEnded(V1);
        JSONObject ended = t.only("ended");
        assertEquals("s1", ended.getString("songId"));
        assertEquals(V1, ended.getString("videoId"));
    }

    @Test
    public void offlineEndIsSentAfterReconnectIfServerStillOnThatSong() throws Exception {
        c.start(); t.open();
        t.msg(play("s1", V1));
        t.drop();
        c.songEnded(V1);
        assertEquals(0, t.count("ended"));
        sch.runAll(); // reconnect
        t.open();
        t.msg(play("s1", V1)); // server's post-ack play: still s1
        JSONObject ended = t.only("ended");
        assertEquals("s1", ended.getString("songId"));
        assertEquals(V1, ended.getString("videoId"));
        assertEquals(1, rec.plays.size()); // not reopened
    }

    @Test
    public void offlineEndIsDroppedIfServerAlreadyAdvanced() throws Exception {
        c.start(); t.open();
        t.msg(play("s1", V1));
        t.drop();
        c.songEnded(V1);
        sch.runAll(); t.open();
        t.msg(play("s2", V2)); // server auto-advanced while we were away
        assertEquals(0, t.count("ended"));
        assertEquals(java.util.Arrays.asList(V1, V2), rec.plays);
    }

    @Test
    public void reconnectBacksOffExponentiallyCappedAt30s() {
        c.start();
        for (int i = 0; i < 8; i++) { t.drop(); sch.runAll(); }
        assertEquals(Long.valueOf(1000), sch.delays.get(0));
        assertEquals(Long.valueOf(2000), sch.delays.get(1));
        assertEquals(Long.valueOf(4000), sch.delays.get(2));
        assertEquals(Long.valueOf(30000), sch.delays.get(7));
    }

    @Test
    public void reportPlayingOnlyForCurrentSong() throws Exception {
        c.start(); t.open();
        t.msg(play("s1", V1));
        c.reportPlaying(V2, 100, null, "playing");
        assertEquals(0, t.count("playing"));
        c.reportPlaying(V1, 213, null, "paused");
        JSONObject p = t.only("playing");
        assertEquals("s1", p.getString("songId"));
        assertEquals(V1, p.getString("videoId"));
        assertEquals(213, p.getInt("durationSec"));
        assertFalse(p.has("positionSec"));
        assertEquals("paused", p.getString("state"));
    }

    @Test
    public void reportProgressOnlyForCurrentSong() throws Exception {
        c.start(); t.open();
        t.msg(play("s1", V1));
        c.reportProgress(V2, 100, 5);
        assertEquals(0, t.count("progress"));
        c.reportProgress(V1, 213, 40);
        JSONObject p = t.only("progress");
        assertEquals("s1", p.getString("songId"));
        assertEquals(V1, p.getString("videoId"));
        assertEquals(213, p.getInt("durationSec"));
        assertEquals(40, p.getInt("positionSec"));
        assertFalse(p.has("state"));
    }

    @Test
    public void bridgeTokenIsAttachedToPlaybackMessages() throws Exception {
        c = new JukeboxRoomClient(ORIGIN, t, http, sch, store, rec, "bridge-tok");
        c.start(); t.open();
        t.msg(play("s1", V1));
        c.reportPlaying(V1, 10, 1, "playing");
        c.reportProgress(V1, 10, 2);
        c.songEnded(V1);
        for (String type : new String[]{"bridge-hello", "playing", "progress", "ended"}) {
            JSONObject m = t.only(type);
            assertEquals("bridge-tok", m.getString("token"));
            if (!"bridge-hello".equals(type)) {
                assertEquals("s1", m.getString("songId"));
                assertEquals(V1, m.getString("videoId"));
            }
        }
    }

    @Test
    public void closedRoomIsForgottenAndReplaced() {
        c.start(); t.open();
        t.msg("{\"t\":\"closed\",\"room\":\"room1\"}");
        assertNull(store.get("room")); // forgotten immediately, no HTTP on the socket thread
        assertEquals(1, http.posts.size());
        sch.runAll(); // scheduled setup creates the new room
        assertEquals("room2", store.get("room"));
        assertEquals("CODE2", rec.code);
    }

    @Test
    public void stopPreventsReconnect() {
        c.start(); t.open();
        c.stop();
        assertTrue(sch.tasks.isEmpty());
        assertEquals("Stopped", rec.status);
    }

    @Test
    public void stateUpdatesListener() {
        c.start(); t.open();
        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"title\":\"Hey\"},\"queue\":[],\"listeners\":4}");
        assertEquals("Hey", rec.np);
        assertEquals(4, rec.listeners);
    }
}
