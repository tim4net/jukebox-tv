package net.tfour.jukebox.room;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Pure planner tests for the lower-third cards: dedication first, then the trivia
 * note; either may be absent; neither yields an empty plan. The guard shows each
 * song's cards exactly once.
 */
public class JukeboxLowerThirdPlanTest {
    @Test
    public void dedicationCardComesFirstThenTheNoteCard() {
        List<JukeboxLowerThirdPlan.Card> cards =
                JukeboxLowerThirdPlan.plan("s1", "Sarah", "happy birthday!", "Recorded in one take.");
        assertEquals(2, cards.size());
        assertEquals("For Sarah", cards.get(0).title);
        assertEquals("happy birthday!", cards.get(0).text);
        assertEquals(10_000L, cards.get(0).durationMs);
        assertEquals("Now playing", cards.get(1).title);
        assertEquals("Recorded in one take.", cards.get(1).text);
        assertEquals(8_000L, cards.get(1).durationMs);
    }

    @Test
    public void dedicationWithoutMessageKeepsATitleOnlyCard() {
        List<JukeboxLowerThirdPlan.Card> cards =
                JukeboxLowerThirdPlan.plan("s1", "Sarah", null, null);
        assertEquals(1, cards.size());
        assertEquals("For Sarah", cards.get(0).title);
        assertNull(cards.get(0).text);
        assertEquals(10_000L, cards.get(0).durationMs);
    }

    @Test
    public void noteAlonePlansOnlyTheTriviaCard() {
        List<JukeboxLowerThirdPlan.Card> cards =
                JukeboxLowerThirdPlan.plan("s1", null, null, "Recorded in one take.");
        assertEquals(1, cards.size());
        assertEquals("Now playing", cards.get(0).title);
        assertEquals("Recorded in one take.", cards.get(0).text);
        assertEquals(8_000L, cards.get(0).durationMs);
    }

    @Test
    public void neitherPiecePlansNoCards() {
        assertTrue(JukeboxLowerThirdPlan.plan("s1", null, null, null).isEmpty());
        // A message without a to is not a dedication; only the note card remains.
        List<JukeboxLowerThirdPlan.Card> msgOnly = JukeboxLowerThirdPlan.plan("s1", null, "hi", "x");
        assertEquals(1, msgOnly.size());
        assertEquals("Now playing", msgOnly.get(0).title);
    }

    @Test
    public void blankPiecesAreTreatedAsAbsent() {
        List<JukeboxLowerThirdPlan.Card> cards =
                JukeboxLowerThirdPlan.plan("s1", "   ", "  ", "   ");
        assertTrue(cards.isEmpty());
        // Blank message falls back to a title-only dedication card.
        List<JukeboxLowerThirdPlan.Card> trimmed =
                JukeboxLowerThirdPlan.plan("s1", " Sarah ", null, null);
        assertEquals("For Sarah", trimmed.get(0).title);
    }

    @Test
    public void guardShowsEachSongExactlyOnceAndNewSongsAgain() {
        JukeboxLowerThirdPlan.Guard guard = new JukeboxLowerThirdPlan.Guard();
        assertTrue("first sighting of s1 shows", guard.isNewSong("s1"));
        assertFalse("repeated state messages for s1 never re-show", guard.isNewSong("s1"));
        assertFalse(guard.isNewSong("s1"));
        assertTrue("a new songId shows again", guard.isNewSong("s2"));
        assertFalse(guard.isNewSong("s2"));
        assertTrue("returning to an older id is a new song again", guard.isNewSong("s1"));
    }

    @Test
    public void guardIgnoresNullAndBlankSongIds() {
        JukeboxLowerThirdPlan.Guard guard = new JukeboxLowerThirdPlan.Guard();
        assertFalse(guard.isNewSong(null));
        assertFalse(guard.isNewSong("   "));
        assertTrue("ignored ids must not consume the slot", guard.isNewSong("s1"));
        assertFalse(guard.isNewSong(null));
        assertTrue(guard.isNewSong("s2"));
    }

    @Test
    public void guardResetAllowsTheSameSongAgain() {
        JukeboxLowerThirdPlan.Guard guard = new JukeboxLowerThirdPlan.Guard();
        assertTrue(guard.isNewSong("s1"));
        assertFalse(guard.isNewSong("s1"));
        guard.reset();
        assertTrue(guard.isNewSong("s1"));
    }
}
