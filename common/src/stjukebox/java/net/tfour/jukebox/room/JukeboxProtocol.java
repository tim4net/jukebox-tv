package net.tfour.jukebox.room;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure (no Android deps besides org.json) encoder/decoder for the jukebox room protocol.
 *
 * Client -> server (field "t" is the type):
 *   {"t":"bridge-hello","token":T}
 *   {"t":"next","token":T}
 *   {"t":"playing","token":T,"songId":S,"durationSec":N,"positionSec":N,"state":"playing"|"paused"|"error"}
 *   {"t":"ping","c":<client millis>}
 *
 * Server -> client:
 *   {"t":"bridge-ack"}
 *   {"t":"play","song":{"songId":S,"videoId":V,"title":T,"durationMs":N|null}}
 *   {"t":"state","room":R,"nowPlaying":{...}|null,"queue":[...],"listeners":N}
 *   {"t":"pong","c":N,"s":N}
 *   {"t":"error","code":C}
 *   {"t":"closed","room":R}
 */
public final class JukeboxProtocol {
    public static final String TYPE_BRIDGE_HELLO = "bridge-hello";
    public static final String TYPE_BRIDGE_ACK = "bridge-ack";
    public static final String TYPE_NEXT = "next";
    public static final String TYPE_PLAYING = "playing";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_PLAY = "play";
    public static final String TYPE_STATE = "state";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_CLOSED = "closed";
    public static final String TYPE_UNKNOWN = "unknown";

    private static final String VIDEO_ID_PATTERN = "^[A-Za-z0-9_-]{11}$";

    private JukeboxProtocol() {
    }

    public static String bridgeHello(String token) {
        return simpleMessage(TYPE_BRIDGE_HELLO, token);
    }

    public static String next(String token) {
        return simpleMessage(TYPE_NEXT, token);
    }

    public static String playing(String token, String songId, Integer durationSec, Integer positionSec, String state) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", TYPE_PLAYING);
            appendToken(json, token);
            if (songId != null) {
                json.put("songId", songId);
            }
            if (durationSec != null) {
                json.put("durationSec", durationSec);
            }
            if (positionSec != null) {
                json.put("positionSec", positionSec);
            }
            if (state != null) {
                json.put("state", state);
            }
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
            // Explicit null means the room is idle. An absent field is not that signal.
            boolean nowPlayingNull = json.has("nowPlaying") && json.isNull("nowPlaying");
            JSONObject nowPlaying = json.optJSONObject("nowPlaying");
            if (nowPlaying != null) {
                nowPlayingTitle = stringify(nowPlaying, "title");
            }
            String errorCode = stringify(json, "code");
            return new Message(type, songId, videoId, title, durationMs, listeners, nowPlayingTitle, nowPlayingNull, errorCode);
        } catch (JSONException e) {
            return unknown();
        }
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

    private static String stringify(JSONObject json, String key) {
        Object value = json.opt(key);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    private static Message unknown() {
        return new Message(TYPE_UNKNOWN, null, null, null, null, -1, null, false, null);
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
        private final boolean mNowPlayingNull;
        private final String mErrorCode;

        private Message(String type, String songId, String videoId, String title, Long durationMs,
                        int listeners, String nowPlayingTitle, boolean nowPlayingNull, String errorCode) {
            mType = type;
            mSongId = songId;
            mVideoId = videoId;
            mTitle = title;
            mDurationMs = durationMs;
            mListeners = listeners;
            mNowPlayingTitle = nowPlayingTitle;
            mNowPlayingNull = nowPlayingNull;
            mErrorCode = errorCode;
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

        /** True when a state message has {@code "nowPlaying": null} (room idle, no song). */
        public boolean isNowPlayingNull() {
            return mNowPlayingNull;
        }

        public String getErrorCode() {
            return mErrorCode;
        }
    }
}
