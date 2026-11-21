package net.tfour.jukebox.pick;

import net.tfour.jukebox.pick.JukeboxVideoPick.Candidate;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Shorts filter + official-video ranking: the rules that drive search results,
 * autoplay picks and guest requests. Pure logic over {@link Candidate}, so every
 * test below hits the real {@link JukeboxVideoPick} code paths, not copies.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxVideoPickTest {
    private static Candidate video(String id, String title, String author, Long durationMs) {
        return new Candidate(id, title, author, durationMs, false, null, null, null);
    }

    private static Candidate shortsFlag(String id) {
        return new Candidate(id, "Some Song", "Artist", 210_000L, true, null, null, null);
    }

    @Test
    public void shortByDuration() {
        assertTrue(JukeboxVideoPick.isShort(video("a", "Song", "Artist", 45_000L)));
        assertTrue(JukeboxVideoPick.isShort(video("a", "Song", "Artist", 65_000L)));
        assertFalse(JukeboxVideoPick.isShort(video("a", "Song", "Artist", 65_001L)));
        assertFalse(JukeboxVideoPick.isShort(video("a", "Song", "Artist", 210_000L)));
    }

    @Test
    public void shortByServiceFlagEvenWhenFullLength() {
        assertTrue(JukeboxVideoPick.isShort(shortsFlag("a")));
    }

    @Test
    public void shortByShortsUrlAndTitleMarker() {
        Candidate url = new Candidate("a", "Song", "Artist", 210_000L, false,
                "https://www.youtube.com/shorts/dQw4w9WgXcQ", null, null);
        assertTrue(JukeboxVideoPick.isShort(url));
        assertTrue(JukeboxVideoPick.isShort(video("a", "Song #Shorts", "Artist", null)));
        assertTrue(JukeboxVideoPick.isShort(video("a", "Song #SHORTS", "Artist", 210_000L)));
    }

    @Test
    public void verticalClipUpToThreeMinutesIsShort() {
        Candidate verticalShort = new Candidate("a", "Song", "Artist", 150_000L,
                false, null, 720, 1280);
        assertTrue(JukeboxVideoPick.isShort(verticalShort));
        Candidate verticalFull = new Candidate("a", "Song", "Artist", 210_000L,
                false, null, 720, 1280);
        assertFalse(JukeboxVideoPick.isShort(verticalFull));
        Candidate horizontal = new Candidate("a", "Song", "Artist", 150_000L,
                false, null, 1280, 720);
        assertFalse(JukeboxVideoPick.isShort(horizontal));
    }

    @Test
    public void unknownDurationFailsOpen() {
        assertFalse(JukeboxVideoPick.isShort(video("a", "Song", "Artist", null)));
        assertFalse(JukeboxVideoPick.isShort(video("a", "Song", "Artist", -1L)));
    }

    @Test
    public void roomGateUsesTitleAndDurationOnly() {
        assertTrue(JukeboxVideoPick.isRoomSongShort("Blinding Lights", 45_000L));
        assertTrue(JukeboxVideoPick.isRoomSongShort("Blinding Lights #Shorts", null));
        assertFalse(JukeboxVideoPick.isRoomSongShort("Blinding Lights", 200_000L));
        assertFalse(JukeboxVideoPick.isRoomSongShort("Blinding Lights", null));
    }

    @Test
    public void officialVevoAndTopicRankAboveLyric() {
        Candidate lyric = video("lyr", "Song (Lyrics)", "Some Channel", 200_000L);
        Candidate plain = video("pln", "Song", "Some Channel", 200_000L);
        Candidate topic = video("top", "Song", "Artist - Topic", 200_000L);
        Candidate vevo = video("vev", "Song (Official Video)", "ArtistVEVO", 200_000L);
        assertTrue(JukeboxVideoPick.score(vevo) > JukeboxVideoPick.score(topic));
        assertTrue(JukeboxVideoPick.score(topic) > JukeboxVideoPick.score(plain));
        assertTrue(JukeboxVideoPick.score(plain) > JukeboxVideoPick.score(lyric));
    }

    @Test
    public void verticalIsDemotedBelowHorizontalAtEqualOfficialness() {
        Candidate horizontal = video("h", "Song (Official Video)", "ArtistVEVO", 200_000L);
        Candidate vertical = new Candidate("v", "Song (Official Video)", "ArtistVEVO",
                200_000L, false, null, 720, 1280);
        assertTrue(JukeboxVideoPick.score(horizontal) > JukeboxVideoPick.score(vertical));
    }

    @Test
    public void filterAndRankDropsShortsAndRanksOfficialFirst() {
        List<Candidate> in = Arrays.asList(
                video("short", "Song", "Artist", 30_000L),
                video("lyr", "Song (Lyrics)", "Some Channel", 200_000L),
                video("off", "Song (Official Video)", "ArtistVEVO", 200_000L),
                video("pln", "Song", "Some Channel", 200_000L));
        List<Candidate> out = JukeboxVideoPick.filterAndRank(in);
        assertEquals(Arrays.asList("off", "pln", "lyr"), ids(out));
    }

    @Test
    public void filterAndRankKeepsInputOrderForEqualScores() {
        List<Candidate> in = Arrays.asList(
                video("one", "Song A", "Some Channel", 200_000L),
                video("two", "Song B", "Some Channel", 200_000L));
        assertEquals(Arrays.asList("one", "two"), ids(JukeboxVideoPick.filterAndRank(in)));
    }

    @Test
    public void pickNextReturnsFirstRankedOrNull() {
        List<Candidate> in = Arrays.asList(
                video("short", "Song", "Artist", 30_000L),
                video("off", "Song (Official Video)", "ArtistVEVO", 200_000L));
        assertEquals("off", JukeboxVideoPick.pickNext(in).videoId);
        assertNull(JukeboxVideoPick.pickNext(
                Arrays.asList(video("short", "Song", "Artist", 30_000L))));
        assertNull(JukeboxVideoPick.pickNext(null));
    }

    private static List<String> ids(List<Candidate> candidates) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (Candidate c : candidates) {
            ids.add(c.videoId);
        }
        return ids;
    }
}
