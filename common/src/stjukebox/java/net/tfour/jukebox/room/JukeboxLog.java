package net.tfour.jukebox.room;

import android.util.Log;

/**
 * Logging for the jukebox room that never crashes the caller: android.util.Log
 * throws RuntimeException("not mocked") in plain JVM unit tests, so swallow it.
 * Public: also used by the join screen in the app package.
 */
public final class JukeboxLog {
    private JukeboxLog() {
    }

    public static void i(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests: Log is not mocked there.
        }
    }

    public static void e(String tag, String msg, Throwable tr) {
        try {
            Log.e(tag, msg, tr);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests: Log is not mocked there.
        }
    }
}
