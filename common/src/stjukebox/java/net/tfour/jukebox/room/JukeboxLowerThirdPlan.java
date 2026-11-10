package net.tfour.jukebox.room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure planner (no Android types) for the lower-third cards: turns a now-playing
 * song's dedication ("For &lt;to&gt;" title + message second line) and trivia note
 * ("Now playing" + note) into the ordered cards to show. The dedication card comes
 * first ({@link #DEDICATION_MS}), then the trivia card ({@link #NOTE_MS}); either
 * may be absent, neither yields an empty plan. The songId only keys the
 * {@link Guard}'s exactly-once behaviour; the cards themselves don't depend on it.
 */
public final class JukeboxLowerThirdPlan {
    public static final long DEDICATION_MS = 10_000L;
    public static final long NOTE_MS = 8_000L;
    public static final String NOTE_LABEL = "Now playing";

    private JukeboxLowerThirdPlan() {
    }

    /** Ordered cards for the given song, empty when there is nothing to show. */
    public static List<Card> plan(String songId, String dedicationTo, String dedicationMessage, String note) {
        List<Card> cards = new ArrayList<>();
        String to = trimmed(dedicationTo);
        if (to != null) {
            cards.add(new Card("For " + to, trimmed(dedicationMessage), DEDICATION_MS));
        }
        String trivia = trimmed(note);
        if (trivia != null) {
            cards.add(new Card(NOTE_LABEL, trivia, NOTE_MS));
        }
        return Collections.unmodifiableList(cards);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /** One lower-third card: title line, optional second line, on-screen duration. */
    public static final class Card {
        public final String title;
        /** Second line, or null for a title-only card. */
        public final String text;
        public final long durationMs;

        Card(String title, String text, long durationMs) {
            this.title = title;
            this.text = text;
            this.durationMs = durationMs;
        }
    }

    /**
     * Exactly-once guard: remembers the last songId it accepted, so one song's
     * repeated "state" messages never re-show the cards while a new songId does.
     */
    public static final class Guard {
        private String mLastSongId;

        /** True exactly once per non-blank songId; null/blank ids are ignored, not remembered. */
        public boolean isNewSong(String songId) {
            if (songId == null || songId.trim().isEmpty()) {
                return false;
            }
            if (songId.equals(mLastSongId)) {
                return false;
            }
            mLastSongId = songId;
            return true;
        }

        public void reset() {
            mLastSongId = null;
        }
    }
}
