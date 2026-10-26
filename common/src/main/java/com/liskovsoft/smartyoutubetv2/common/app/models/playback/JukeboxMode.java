package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import android.view.KeyEvent;

/**
 * Process-wide flags set by the stjukebox flavor (common/main cannot reference the
 * flavor classes, so this is the only guarded hook): {@code sRoomActive} is true while
 * a jukebox room exists (started and not stopped, connected or reconnecting), and
 * {@code sSongSet} is true while the room has a current song. While either is set,
 * SmartTube's own auto-advance (autoplay, next/suggested video) must never play a
 * non-room video on its own. Other flavors never set them.
 */
public final class JukeboxMode {
    private static volatile boolean sSongSet;
    private static volatile boolean sRoomActive;

    private JukeboxMode() {
    }

    /** @param songSet true while a room song is current, false when the room is idle or stopped. */
    public static void setSongSet(boolean songSet) {
        sSongSet = songSet;
    }

    /** True while a room song is set. Not the same as the room socket being connected. */
    public static boolean isSongSet() {
        return sSongSet;
    }

    /** @param roomActive true while a jukebox room exists (started, not stopped). */
    public static void setRoomActive(boolean roomActive) {
        sRoomActive = roomActive;
    }

    /** True while a jukebox room exists, even when it is connected but idle (no song). */
    public static boolean isRoomActive() {
        return sRoomActive;
    }

    /**
     * True while SmartTube auto-advance (autoplay of next/suggested/related videos,
     * end-of-video advance) must be blocked so the player never plays a non-room
     * video on its own. This holds for the whole time a room is active, not only
     * while a room song is set. Logs (never crashes) when it blocks.
     */
    public static boolean isAutoAdvanceBlocked() {
        boolean blocked = sRoomActive || sSongSet;
        if (blocked) {
            logBlocked();
        }
        return blocked;
    }

    /**
     * True for key codes that seek the current video when handled by the player
     * (seek bar / glue): DPAD left/right seeking, FF/REW and their +/- aliases.
     * While {@link #isSongSet()} is true, callers consume these keys so the room's
     * song can never be seeked; previous/next media keys are ignored the same way
     * (the room owns the queue, the TV is a listener).
     */
    public static boolean isSeekKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MINUS
                || keyCode == KeyEvent.KEYCODE_PLUS;
    }

    /**
     * True for the media play/pause key family: PLAY_PAUSE, PLAY and PAUSE.
     * While a room song is active these toggle/play/pause the room's song,
     * which the room owns — the TV is a listener (build 2469, gazebo c4:
     * MEDIA_PLAY_PAUSE paused the player via the media session).
     */
    public static boolean isPlayPauseKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    /**
     * Logs "JukeboxKeys: swallowed &lt;key&gt;" and returns true while a room song is
     * active and the given key is a play/pause media key — the caller must then
     * consume the event so the room's song keeps playing. Other flavors (and an
     * inactive room) always return false: normal SmartTube behavior unchanged.
     */
    public static boolean swallowPlayPauseKey(int keyCode) {
        if (!sSongSet || !isPlayPauseKeyCode(keyCode)) {
            return false;
        }
        logSwallowed(KeyEvent.keyCodeToString(keyCode));
        return true;
    }

    /**
     * Same swallow for seek/skip keys (DPAD left/right, FF/REW, +/-): logs
     * "JukeboxKeys: swallowed &lt;key&gt;" and returns true so the room's song can
     * never be seeked. Inactive room: false, nothing logged.
     */
    public static boolean swallowSeekKey(int keyCode) {
        if (!sSongSet || !isSeekKeyCode(keyCode)) {
            return false;
        }
        logSwallowed(KeyEvent.keyCodeToString(keyCode));
        return true;
    }

    /**
     * Same swallow for non-keycode play/pause call sites (glue actions, media
     * session play/pause callbacks): {@code what} names the swallowed action in
     * the "JukeboxKeys: swallowed &lt;what&gt;" log line.
     */
    public static boolean swallowPlayPause(String what) {
        if (!sSongSet) {
            return false;
        }
        logSwallowed(what);
        return true;
    }

    /**
     * Same swallow for non-keycode seek call sites (glue rewind/fast-forward):
     * {@code what} names the swallowed seek in the log line.
     */
    public static boolean swallowSeek(String what) {
        if (!sSongSet) {
            return false;
        }
        logSwallowed(what);
        return true;
    }

    private static void logSwallowed(String what) {
        try {
            android.util.Log.i("JukeboxKeys", "swallowed " + what);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests: android.util.Log is not mocked there.
        }
    }

    private static void logBlocked() {
        try {
            android.util.Log.i("JukeboxMode", "auto-advance blocked: roomActive=" + sRoomActive
                    + " songSet=" + sSongSet);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests: android.util.Log is not mocked there.
        }
    }
}
