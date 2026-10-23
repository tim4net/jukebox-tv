package net.tfour.jukebox.room;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Wire format must match partyq src/room.ts (the jukebox.tfour.net server). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxProtocolTest {
    @Test
    public void bridgeHelloAndNextCarryTokenOnlyWhenSet() throws Exception {
        JSONObject h = new JSONObject(JukeboxProtocol.bridgeHello("tok"));
        assertEquals("bridge-hello", h.getString("t"));
        assertEquals("tok", h.getString("token"));
        assertFalse(new JSONObject(JukeboxProtocol.bridgeHello("")).has("token"));
        assertFalse(new JSONObject(JukeboxProtocol.next(null)).has("token"));
        assertEquals("next", new JSONObject(JukeboxProtocol.next("tok")).getString("t"));
    }

    @Test
    public void playingOmitsUnknownNumbers() throws Exception {
        JSONObject p = new JSONObject(JukeboxProtocol.playing(null, "s1", 215, null, "paused"));
        assertEquals("playing", p.getString("t"));
        assertEquals("s1", p.getString("songId"));
        assertEquals(215, p.getInt("durationSec"));
        assertFalse(p.has("positionSec"));
        assertEquals("paused", p.getString("state"));
    }

    @Test
    public void pingHasClientClock() throws Exception {
        JSONObject p = new JSONObject(JukeboxProtocol.ping(42L));
        assertEquals("ping", p.getString("t"));
        assertEquals(42L, p.getLong("c"));
    }

    @Test
    public void parsesPlay() {
        JukeboxProtocol.Message m = JukeboxProtocol.parse(
                "{\"t\":\"play\",\"song\":{\"songId\":\"s1\",\"videoId\":\"dQw4w9WgXcQ\",\"title\":\"Rick\",\"durationMs\":213000}}");
        assertEquals("play", m.getType());
        assertEquals("s1", m.getSongId());
        assertEquals("dQw4w9WgXcQ", m.getVideoId());
        assertEquals("Rick", m.getTitle());
        assertEquals(Long.valueOf(213000), m.getDurationMs());
    }

    @Test
    public void parsesPlayWithNullDuration() {
        JukeboxProtocol.Message m = JukeboxProtocol.parse(
                "{\"t\":\"play\",\"song\":{\"songId\":\"s1\",\"videoId\":\"dQw4w9WgXcQ\",\"title\":\"x\",\"durationMs\":null}}");
        assertNull(m.getDurationMs());
    }

    @Test
    public void parsesStateClosedErrorAndGarbage() {
        JukeboxProtocol.Message s = JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"nowPlaying\":{\"title\":\"Song A\"},\"queue\":[],\"listeners\":3}");
        assertEquals("state", s.getType());
        assertEquals(3, s.getListeners());
        assertEquals("Song A", s.getNowPlayingTitle());
        assertEquals("closed", JukeboxProtocol.parse("{\"t\":\"closed\",\"room\":\"r\"}").getType());
        assertEquals("unauthorized", JukeboxProtocol.parse("{\"t\":\"error\",\"code\":\"unauthorized\"}").getErrorCode());
        assertEquals("unknown", JukeboxProtocol.parse("not json").getType());
        assertEquals("unknown", JukeboxProtocol.parse(null).getType());
    }

    @Test
    public void validatesVideoIds() {
        assertTrue(JukeboxProtocol.isValidVideoId("dQw4w9WgXcQ"));
        assertTrue(JukeboxProtocol.isValidVideoId("a-b_c-d_e-f"));
        assertFalse(JukeboxProtocol.isValidVideoId("short"));
        assertFalse(JukeboxProtocol.isValidVideoId("dQw4w9WgXcQ1"));
        assertFalse(JukeboxProtocol.isValidVideoId("dQw4w9WgX<Q"));
        assertFalse(JukeboxProtocol.isValidVideoId(null));
    }

    @Test
    public void parsesRoomCreated() {
        String[] rc = JukeboxProtocol.parseRoomCreated("{\"room\":\"abcdefghijklmnopqrstuv\",\"code\":\"K7QPZX\"}");
        assertArrayEquals(new String[]{"abcdefghijklmnopqrstuv", "K7QPZX"}, rc);
        assertNull(JukeboxProtocol.parseRoomCreated("{\"error\":\"registry_unavailable\"}"));
    }
}
