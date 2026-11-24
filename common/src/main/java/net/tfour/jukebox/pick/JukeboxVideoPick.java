package net.tfour.jukebox.pick;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shorts filtering + official-music-video ranking for the jukebox.
 * Lives in src/main so every flavor compiles (SearchPresenter and
 * SuggestionsController call it from main); call sites are gated on
 * {@code JukeboxMode.isShortsFilterEnabled()}, so other flavors are untouched.
 *
 * Pure logic over {@link Candidate} (no Android deps), so it can be unit-tested
 * on the plain JVM. The same rules drive all three pick points:
 *
 * <ul>
 *   <li>Guest requests: the room server sends only title + durationMs, so the
 *       room gate ({@link #isRoomSongShort(String, Long)}) uses those two signals.</li>
 *   <li>Search results: {@link #applyToGroup(VideoGroup)} drops Shorts and ranks
 *       official / VEVO / topic videos first, before the first result is played.</li>
 *   <li>Autoplay picks: suggestion groups go through {@link #applyToGroup(VideoGroup)}
 *       and {@link SuggestionsController#getNext()} skips a Short pick (see the
 *       one guarded hunk there).</li>
 * </ul>
 *
 * Signals (ticket: duration under about 60-70s, /shorts/ URLs, vertical aspect):
 * a video is a Short when the service already flagged it ({@code isShorts}), its
 * URL is a /shorts/ link, its title carries #shorts, its duration is at most
 * {@link #SHORTS_MAX_DURATION_MS}, or it is vertical and at most
 * {@link #SHORTS_VERTICAL_MAX_DURATION_MS} (YouTube raised Shorts to 3 minutes in
 * 2024, so a vertical clip up to 3 minutes is still a Short). An unknown duration
 * with no other signal is NOT a Short - the pick fails open and the video plays.
 *
 * Ranking (after Shorts are dropped): official / VEVO / topic channels first,
 * lyric videos last, vertical videos demoted. Equal scores keep YouTube's own
 * order (stable sort).
 */
public final class JukeboxVideoPick {
    /** Classic Shorts cap: the ticket's "under about 60-70s" range, midpoint. */
    public static final long SHORTS_MAX_DURATION_MS = 65_000L;
    /** Vertical clips up to YouTube's 3-minute Shorts cap still count as Shorts. */
    public static final long SHORTS_VERTICAL_MAX_DURATION_MS = 180_000L;

    private static final int SCORE_OFFICIAL = 100;
    private static final int SCORE_TOPIC = 60;
    private static final int SCORE_LYRIC = -100;
    private static final int SCORE_VERTICAL = -50;

    private JukeboxVideoPick() {
    }

    /** One video candidate, with only the signals a pick point actually has. */
    public static final class Candidate {
        public final String videoId;
        public final String title;
        public final String author;
        /** Duration in ms, or null when unknown. */
        public final Long durationMs;
        /** Service flag (YouTube/Innertube marks Shorts). */
        public final boolean isShorts;
        /** Watch URL, or null when unknown (may be a /shorts/ link). */
        public final String url;
        /** Thumbnail/player width/height in px, or null when unknown. */
        public final Integer width;
        public final Integer height;

        public Candidate(String videoId, String title, String author, Long durationMs,
                         boolean isShorts, String url, Integer width, Integer height) {
            this.videoId = videoId;
            this.title = title;
            this.author = author;
            this.durationMs = durationMs;
            this.isShorts = isShorts;
            this.url = url;
            this.width = width;
            this.height = height;
        }

        public static Candidate of(String videoId, String title, Long durationMs) {
            return new Candidate(videoId, title, null, durationMs, false, null, null, null);
        }
    }

    /** True when the candidate is a YouTube Short by any of the signals above. */
    public static boolean isShort(Candidate c) {
        if (c == null) {
            return false;
        }
        if (c.isShorts) {
            return true;
        }
        if (hasShortsUrl(c.url) || hasShortsTitle(c.title)) {
            return true;
        }
        if (c.durationMs != null && c.durationMs >= 0 && c.durationMs <= SHORTS_MAX_DURATION_MS) {
            return true;
        }
        if (isVertical(c) && c.durationMs != null && c.durationMs >= 0
                && c.durationMs <= SHORTS_VERTICAL_MAX_DURATION_MS) {
            return true;
        }
        return false;
    }

    /**
     * Room-gate entry point: the server's {@code play} message carries only a title
     * and a duration, so those are the only signals available for a guest request.
     * Unknown duration with no #shorts marker fails open (the song plays).
     */
    public static boolean isRoomSongShort(String title, Long durationMs) {
        return isShort(Candidate.of(null, title, durationMs));
    }

    /** True for a vertical (portrait) candidate. */
    public static boolean isVertical(Candidate c) {
        return c != null && c.width != null && c.height != null && c.height > c.width;
    }

    /**
     * Ranking score: official / VEVO / topic music videos first, lyric videos last,
     * vertical videos demoted. Shorts are filtered out before ranking, so this never
     * has to rank one.
     */
    public static int score(Candidate c) {
        if (c == null) {
            return 0;
        }
        int score = 0;
        String title = lower(c.title);
        String author = lower(c.author);
        if (title.contains("official music video") || title.contains("official video")
                || author.contains("vevo")) {
            score += SCORE_OFFICIAL;
        } else if (author.endsWith(" - topic")) {
            score += SCORE_TOPIC;
        }
        if (title.contains("lyric")) {
            score += SCORE_LYRIC;
        }
        if (isVertical(c)) {
            score += SCORE_VERTICAL;
        }
        return score;
    }

    /** Drops Shorts and ranks the rest (stable: equal scores keep input order). */
    public static List<Candidate> filterAndRank(List<Candidate> candidates) {
        List<Candidate> kept = new ArrayList<>();
        if (candidates == null) {
            return kept;
        }
        for (Candidate c : candidates) {
            if (!isShort(c)) {
                kept.add(c);
            }
        }
        Collections.sort(kept, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(score(b), score(a));
            }
        });
        return kept;
    }

    /** First full official-ish video after filtering + ranking, or null. */
    public static Candidate pickNext(List<Candidate> candidates) {
        List<Candidate> ranked = filterAndRank(candidates);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Converts an upstream {@link Video} to a candidate (url/aspect unknown). */
    public static Candidate fromVideo(Video video) {
        if (video == null) {
            return null;
        }
        Long duration = video.getDurationMs() >= 0 ? video.getDurationMs() : null;
        return new Candidate(video.videoId, video.title, video.getAuthor(),
                duration, video.isShorts, null, null, null);
    }

    /** True when the upstream video is a Short by the same rules. */
    public static boolean isShortVideo(Video video) {
        return video != null && isShort(fromVideo(video));
    }

    /**
     * Drops Shorts from the group in place and re-orders the rest by score.
     * The group's media group / pagination state is untouched.
     * @return how many videos were removed.
     */
    public static int applyToGroup(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return 0;
        }
        List<Video> videos = new ArrayList<>(group.getVideos());
        List<Candidate> candidates = new ArrayList<>();
        for (Video v : videos) {
            candidates.add(fromVideo(v));
        }
        List<Candidate> ranked = filterAndRank(candidates);
        int removed = videos.size() - ranked.size();
        group.clear();
        for (Candidate c : ranked) {
            Video video = findVideo(videos, c);
            if (video != null) {
                group.add(video);
            }
        }
        return removed;
    }

    /** Drops Shorts from the group in place, keeping the remaining order. */
    public static int filterShortsOnly(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return 0;
        }
        List<Video> videos = new ArrayList<>(group.getVideos());
        int removed = 0;
        for (Video v : videos) {
            if (isShortVideo(v)) {
                group.remove(v);
                removed++;
            }
        }
        return removed;
    }

    private static Video findVideo(List<Video> videos, Candidate c) {
        for (Video v : videos) {
            if (v == null && c == null) {
                return null;
            }
            if (v != null && c != null && v.videoId != null && v.videoId.equals(c.videoId)) {
                return v;
            }
        }
        return null;
    }

    private static boolean hasShortsUrl(String url) {
        return url != null && lower(url).contains("/shorts/");
    }

    private static boolean hasShortsTitle(String title) {
        return title != null && lower(title).contains("#shorts");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
