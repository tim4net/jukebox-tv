package net.tfour.jukebox.room;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Pure (no Android deps besides org.json) encoder/decoder for the jukebox room protocol.
 *
 * Client -> server (field "t" is the type):
 *   {"t":"bridge-hello","token":T}
 *   {"t":"playing","token":T,"songId":S,"videoId":V,"durationSec":N,"positionSec":N,"state":"playing"|"paused"|"error"}
 *   {"t":"progress","token":T,"songId":S,"videoId":V,"durationSec":N,"positionSec":N}
 *   {"t":"ended","token":T,"songId":S,"videoId":V}
 *   {"t":"ping","c":<client millis>}
 *
 * Server -> client:
 *   {"t":"bridge-ack"}
 *   {"t":"play","song":{"songId":S,"videoId":V,"title":T,"durationMs":N|null}}
 *   {"t":"state","room":R,"now":NOW,"nowPlaying":{"songId":S,"videoId":V,"title":T,"startedAt":N,"paused":B,"pausedAt":N,"durationMs":N}|null,"queue":[...],"listeners":N[,"popups":B|"settings":{"popups":B}]}
 *   {"t":"pong","c":N,"s":N}
 *   {"t":"error","code":C}
 *   {"t":"closed","room":R}
 */
public final class JukeboxProtocol {
    public static final String TYPE_BRIDGE_HELLO = "bridge-hello";
    public static final String TYPE_BRIDGE_ACK = "bridge-ack";
    public static final String TYPE_PLAYING = "playing";
    public static final String TYPE_PROGRESS = "progress";
    public static final String TYPE_ENDED = "ended";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_PLAY = "play";
    public static final String TYPE_STATE = "state";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_CLOSED = "closed";
    public static final String TYPE_UNKNOWN = "unknown";

    /** How long a pop-up bubble stays on screen: position window [atSec, atSec + 6s). */
    public static final long POPUP_WINDOW_MS = 6_000L;

    private static final String VIDEO_ID_PATTERN = "^[A-Za-z0-9_-]{11}$";

    private JukeboxProtocol() {
    }

    public static String bridgeHello(String token) {
        return simpleMessage(TYPE_BRIDGE_HELLO, token);
    }

    /** Load / play / pause. Unknown numbers and a null videoId are omitted. */
    public static String playing(String token, String songId, String videoId,
                                 Integer durationSec, Integer positionSec, String state) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", TYPE_PLAYING);
            appendToken(json, token);
            appendSong(json, songId, videoId);
            appendTiming(json, durationSec, positionSec);
            if (state != null) {
                json.put("state", state);
            }
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** ~5s position heartbeat. No playback state. */
    public static String progress(String token, String songId, String videoId,
                                  Integer durationSec, Integer positionSec) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", TYPE_PROGRESS);
            appendToken(json, token);
            appendSong(json, songId, videoId);
            appendTiming(json, durationSec, positionSec);
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** Song finished. */
    public static String ended(String token, String songId, String videoId) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", TYPE_ENDED);
            appendToken(json, token);
            appendSong(json, songId, videoId);
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public static String ping(long c) {
        try {
            return new JSONObject().put("t", TYPE_PING).put("c", c).toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * True for well-formed YouTube video ids ("^[A-Za-z0-9_-]{11}$").
     */
    public static boolean isValidVideoId(String videoId) {
        return videoId != null && videoId.matches(VIDEO_ID_PATTERN);
    }

    /**
     * Parses an arbitrary server message. Never throws: malformed input yields a Message
     * of type {@link #TYPE_UNKNOWN}.
     */
    public static Message parse(String text) {
        if (text == null) {
            return unknown();
        }
        try {
            JSONObject json = new JSONObject(text);
            String type = stringify(json, "t");
            if (type == null || type.isEmpty()) {
                type = TYPE_UNKNOWN;
            }
            String songId = null;
            String videoId = null;
            String title = null;
            Long durationMs = null;
            JSONObject song = json.optJSONObject("song");
            if (song != null) {
                songId = stringify(song, "songId");
                videoId = stringify(song, "videoId");
                title = stringify(song, "title");
                Object duration = song.opt("durationMs");
                if (duration instanceof Number) {
                    durationMs = ((Number) duration).longValue();
                }
            }
            int listeners = -1;
            Object listenersValue = json.opt("listeners");
            if (listenersValue instanceof Number) {
                listeners = ((Number) listenersValue).intValue();
            }
            String nowPlayingTitle = null;
            String nowPlayingSongId = null;
            String nowPlayingVideoId = null;
            Long nowPlayingStartedAtMs = null;
            Long nowPlayingPausedAtMs = null;
            Long nowPlayingDurationMs = null;
            boolean nowPlayingPaused = false;
            // Explicit null means the room is idle. An absent field is not that signal.
            boolean nowPlayingNull = json.has("nowPlaying") && json.isNull("nowPlaying");
            JSONObject nowPlaying = json.optJSONObject("nowPlaying");
            if (nowPlaying != null) {
                nowPlayingTitle = stringify(nowPlaying, "title");
                nowPlayingSongId = stringify(nowPlaying, "songId");
                nowPlayingVideoId = stringify(nowPlaying, "videoId");
                nowPlayingStartedAtMs = millis(nowPlaying, "startedAt");
                nowPlayingPausedAtMs = millis(nowPlaying, "pausedAt");
                nowPlayingDurationMs = millis(nowPlaying, "durationMs");
                nowPlayingPaused = Boolean.TRUE.equals(nowPlaying.opt("paused"));
            }
            // Server clock carried by every "state" broadcast (null when absent).
            Long serverNowMs = millis(json, "now");
            String errorCode = stringify(json, "code");
            boolean popupsEnabled = Boolean.TRUE.equals(json.opt("popups"));
            JSONObject settings = json.optJSONObject("settings");
            if (!popupsEnabled && settings != null) {
                popupsEnabled = Boolean.TRUE.equals(settings.opt("popups"));
            }
            return new Message(type, songId, videoId, title, durationMs, listeners,
                    nowPlayingTitle, nowPlayingSongId, nowPlayingVideoId, nowPlayingNull,
                    errorCode, popupsEnabled, serverNowMs, nowPlayingStartedAtMs,
                    nowPlayingPaused, nowPlayingPausedAtMs, nowPlayingDurationMs);
        } catch (JSONException e) {
            return unknown();
        }
    }

    /**
     * Parses a GET /api/popups/&lt;videoId&gt; response: {"videoId":V,"bubbles":[{"atSec":N,"text":S}]}.
     * Returns the bubbles sorted by atSec, or null when the payload is malformed. An empty
     * array yields an empty list (valid, nothing to show).
     */
    public static List<Bubble> parsePopups(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            Object bubblesValue = obj.opt("bubbles");
            if (!(bubblesValue instanceof JSONArray)) {
                return null;
            }
            JSONArray arr = (JSONArray) bubblesValue;
            List<Bubble> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object entry = arr.opt(i);
                if (!(entry instanceof JSONObject)) {
                    continue;
                }
                JSONObject bubble = (JSONObject) entry;
                Object at = bubble.opt("atSec");
                Object text = bubble.opt("text");
                if (!(at instanceof Number) || !(text instanceof String)) {
                    continue;
                }
                double atSec = ((Number) at).doubleValue();
                String value = ((String) text).trim();
                if (Double.isNaN(atSec) || Double.isInfinite(atSec) || atSec < 0 || value.isEmpty()) {
                    continue;
                }
                result.add(new Bubble(atSec, value));
            }
            Collections.sort(result, new Comparator<Bubble>() {
                @Override
                public int compare(Bubble a, Bubble b) {
                    return Double.compare(a.atSec, b.atSec);
                }
            });
            return result;
        } catch (JSONException e) {
            return null;
        }
    }

    /** True while positionMs falls in the bubble's [atSec, atSec + {@link #POPUP_WINDOW_MS}) window. */
    public static boolean isBubbleActive(Bubble bubble, long positionMs) {
        if (bubble == null || positionMs < 0) {
            return false;
        }
        long startMs = bubble.startMs();
        return positionMs >= startMs && positionMs < startMs + POPUP_WINDOW_MS;
    }

    /**
     * Index of the first bubble (in atSec order) whose window contains positionMs and that has
     * not been shown yet (its index is not in shownIndexes), or -1. Pure function.
     */
    public static int selectBubble(List<Bubble> bubbles, long positionMs, Set<Integer> shownIndexes) {
        if (bubbles == null || positionMs < 0) {
            return -1;
        }
        for (int i = 0; i < bubbles.size(); i++) {
            if (shownIndexes != null && shownIndexes.contains(i)) {
                continue;
            }
            if (isBubbleActive(bubbles.get(i), positionMs)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses a POST /api/rooms response: {"room":"<22-char id>","code":"<6-char code>"}.
     * Returns {room, code} or null when malformed.
     */
    public static String[] parseRoomCreated(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            String room = stringify(obj, "room");
            String code = stringify(obj, "code");
            if (room == null || room.trim().isEmpty() || code == null || code.trim().isEmpty()) {
                return null;
            }
            return new String[]{room, code};
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Parses a GET /api/r/{room}/state response. A null/unparseable body or one containing
     * a "closed" field means the room is gone.
     */
    public static boolean isClosedState(String json) {
        if (json == null) {
            return true;
        }
        try {
            JSONObject obj = new JSONObject(json);
            return obj.has("closed");
        } catch (JSONException e) {
            return true;
        }
    }

    private static String simpleMessage(String type, String token) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", type);
            appendToken(json, token);
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static void appendToken(JSONObject json, String token) throws JSONException {
        if (token != null && !token.isEmpty()) {
            json.put("token", token);
        }
    }

    private static void appendSong(JSONObject json, String songId, String videoId) throws JSONException {
        if (songId != null) {
            json.put("songId", songId);
        }
        if (videoId != null) {
            json.put("videoId", videoId);
        }
    }

    private static void appendTiming(JSONObject json, Integer durationSec, Integer positionSec) throws JSONException {
        if (durationSec != null) {
            json.put("durationSec", durationSec);
        }
        if (positionSec != null) {
            json.put("positionSec", positionSec);
        }
    }

    private static String stringify(JSONObject json, String key) {
        Object value = json.opt(key);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    /** Numeric field {@code key} of {@code json} in ms, or null when absent/not a number. */
    private static Long millis(JSONObject json, String key) {
        Object value = json.opt(key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static Message unknown() {
        return new Message(TYPE_UNKNOWN, null, null, null, null, -1, null, null, null, false, null, false,
                null, null, false, null, null);
    }

    /**
     * One "Pop-Up Video" bubble from GET /api/popups/&lt;videoId&gt;.
     */
    public static final class Bubble {
        /** When to pop the bubble, in seconds from the start of the video. */
        public final double atSec;
        public final String text;

        Bubble(double atSec, String text) {
            this.atSec = atSec;
            this.text = text;
        }

        long startMs() {
            return Math.round(atSec * 1000.0d);
        }
    }

    /**
     * Immutable decoded server message. Fields only carry data relevant to their type.
     */
    public static final class Message {
        private final String mType;
        private final String mSongId;
        private final String mVideoId;
        private final String mTitle;
        private final Long mDurationMs;
        private final int mListeners;
        private final String mNowPlayingTitle;
        private final String mNowPlayingSongId;
        private final String mNowPlayingVideoId;
        private final boolean mNowPlayingNull;
        private final String mErrorCode;
        private final boolean mPopupsEnabled;
        private final Long mServerNowMs;
        private final Long mNowPlayingStartedAtMs;
        private final boolean mNowPlayingPaused;
        private final Long mNowPlayingPausedAtMs;
        private final Long mNowPlayingDurationMs;

        private Message(String type, String songId, String videoId, String title, Long durationMs,
                        int listeners, String nowPlayingTitle, String nowPlayingSongId,
                        String nowPlayingVideoId, boolean nowPlayingNull, String errorCode,
                        boolean popupsEnabled, Long serverNowMs, Long nowPlayingStartedAtMs,
                        boolean nowPlayingPaused, Long nowPlayingPausedAtMs, Long nowPlayingDurationMs) {
            mType = type;
            mSongId = songId;
            mVideoId = videoId;
            mTitle = title;
            mDurationMs = durationMs;
            mListeners = listeners;
            mNowPlayingTitle = nowPlayingTitle;
            mNowPlayingSongId = nowPlayingSongId;
            mNowPlayingVideoId = nowPlayingVideoId;
            mNowPlayingNull = nowPlayingNull;
            mErrorCode = errorCode;
            mPopupsEnabled = popupsEnabled;
            mServerNowMs = serverNowMs;
            mNowPlayingStartedAtMs = nowPlayingStartedAtMs;
            mNowPlayingPaused = nowPlayingPaused;
            mNowPlayingPausedAtMs = nowPlayingPausedAtMs;
            mNowPlayingDurationMs = nowPlayingDurationMs;
        }

        public String getType() {
            return mType;
        }

        public String getSongId() {
            return mSongId;
        }

        public String getVideoId() {
            return mVideoId;
        }

        public String getTitle() {
            return mTitle;
        }

        /** Song duration in ms, null when absent. */
        public Long getDurationMs() {
            return mDurationMs;
        }

        /** Listener count from "state" messages, -1 when absent. */
        public int getListeners() {
            return mListeners;
        }

        public String getNowPlayingTitle() {
            return mNowPlayingTitle;
        }

        /** Song id inside a "state" message's nowPlaying object, null when absent. */
        public String getNowPlayingSongId() {
            return mNowPlayingSongId;
        }

        /** Video id inside a "state" message's nowPlaying object, null when absent. */
        public String getNowPlayingVideoId() {
            return mNowPlayingVideoId;
        }

        /** True when a state message has {@code "nowPlaying": null} (room idle, no song). */
        public boolean isNowPlayingNull() {
            return mNowPlayingNull;
        }

        public String getErrorCode() {
            return mErrorCode;
        }

        /**
         * True only when a "state" message explicitly carries boolean {@code popups}==true or
         * {@code settings.popups}==true. Missing or other values mean OFF (default off).
         */
        public boolean isPopupsEnabled() {
            return mPopupsEnabled;
        }

        /** Server clock (epoch ms) carried by a "state" message, null when absent. */
        public Long getServerNowMs() {
            return mServerNowMs;
        }

        /** nowPlaying song start (epoch ms), null when absent. */
        public Long getNowPlayingStartedAtMs() {
            return mNowPlayingStartedAtMs;
        }

        /** True while the nowPlaying song is paused (frozen at {@link #getNowPlayingPausedAtMs()}). */
        public boolean isNowPlayingPaused() {
            return mNowPlayingPaused;
        }

        /** When the nowPlaying song was paused (epoch ms), null when absent/not paused. */
        public Long getNowPlayingPausedAtMs() {
            return mNowPlayingPausedAtMs;
        }

        /** nowPlaying song duration in ms, null when absent. */
        public Long getNowPlayingDurationMs() {
            return mNowPlayingDurationMs;
        }
    }
}
