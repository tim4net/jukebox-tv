package net.tfour.jukebox.room;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Whether the app may start/foreground its own activities for room playback right
 * now (stjukebox flavor only). The app must never take over the TV (issue #3):
 * room "play" messages, resume checks and mismatch reopens that arrive while
 * another app is in use only update state — the song opens when the user returns
 * (join screen re-entry or player resume re-run the checks in the foreground).
 *
 * The override seam exists for unit tests: Robolectric cannot reliably simulate a
 * backgrounded process (the importance query defaults to foreground there).
 */
public final class JukeboxForeground {
    private static volatile Boolean sTestOverride;

    private JukeboxForeground() {
    }

    /** True when one of the app's activities is the foreground/top activity. */
    public static boolean isAppInForeground() {
        Boolean override = sTestOverride;
        if (override != null) {
            return override;
        }
        try {
            return Utils.isAppInForegroundFixed();
        } catch (RuntimeException e) {
            // Broken importance query: assume foreground so room playback never
            // stalls forever (a wrong "background" would silently drop songs).
            return true;
        }
    }

    /** @param override fixed foreground answer for unit tests, or null to use the real check. */
    public static void setTestOverride(Boolean override) {
        sTestOverride = override;
    }
}
