package net.tfour.jukebox.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;

import net.tfour.jukebox.room.JukeboxForeground;
import net.tfour.jukebox.room.JukeboxLog;
import net.tfour.jukebox.room.JukeboxRoom;

/**
 * Self-update check driver for the stjukebox flavor (tag {@code JukeboxUpdate}).
 *
 * The upstream check only ever ran from the splash flow (SplashPresenter ->
 * BootDialogPresenter -> AppUpdatePresenter), which the jukebox flavor never
 * enters: its launcher is JukeboxJoinActivity, so no check — and no update
 * offer — ever happened. This driver runs the existing {@link AppUpdatePresenter}
 * machinery when the user is in the app on the join screen (idle), throttled to
 * one attempt per hour (plus the checker's own staleness), never while the app
 * is backgrounded and never while a room song is active (during playback).
 * Every decision (check / skip reason / offer) is logged so it is verifiable.
 */
public class JukeboxUpdate {
    public static final String LOG_TAG = "JukeboxUpdate";
    private static final String PREFS = "jukebox_update";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    /** Same settle delay the upstream splash flow uses before its boot check. */
    static final long CHECK_DELAY_MS = 10_000;
    static final long MIN_CHECK_INTERVAL_MS = 60 * 60 * 1000;

    private static volatile JukeboxUpdate sInstance;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private AppUpdatePresenter mPresenter;

    protected JukeboxUpdate(Context appContext) {
        mAppContext = appContext.getApplicationContext();
    }

    public static JukeboxUpdate instance(Context context) {
        JukeboxUpdate instance = sInstance;
        if (instance == null) {
            synchronized (JukeboxUpdate.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new JukeboxUpdate(context);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /** Called when the user is in the app on the join screen (e.g. activity resume). */
    public static void maybeCheck(Context context) {
        instance(context).scheduleCheck(CHECK_DELAY_MS);
    }

    /**
     * Gates + throttle + logged decision, then the (re-gated) delayed check.
     * Public so tests (in the room fixture package) can drive it with a custom
     * (0 = inline) delay; production enters via {@link #maybeCheck(Context)}.
     */
    public void scheduleCheck(long delayMs) {
        String skip = skipReason(true);
        if (skip != null) {
            JukeboxLog.i(LOG_TAG, "skip: " + skip);
            return;
        }
        JukeboxLog.i(LOG_TAG, "check: update manifest check scheduled in " + delayMs + "ms"
                + " (url=latest.json, min interval " + (MIN_CHECK_INTERVAL_MS / 60000) + "min)");
        if (delayMs <= 0) {
            runCheck();
        } else {
            mHandler.postDelayed(this::runCheck, delayMs);
        }
    }

    private void runCheck() {
        // Re-gated, but WITHOUT the throttle: it gates scheduling only (the
        // stamp below is written when a check really runs). Applying it here
        // too would make every check skip itself ("checked 0s ago").
        String skip = skipReason(false);
        if (skip != null) {
            JukeboxLog.i(LOG_TAG, "skip at run time: " + skip);
            return;
        }
        try {
            JukeboxLog.i(LOG_TAG, "check: querying update manifest now");
            startCheck();
        } finally {
            // Stamp the check even if it blows up mid-flight: otherwise every
            // later schedule (e.g. each join-screen resume) would immediately
            // re-run a check that just crashed.
            markChecked();
        }
    }

    /** Starts the real AppUpdatePresenter check. Overridden by tests. */
    protected void startCheck() {
        if (mPresenter == null) {
            mPresenter = new JukeboxUpdatePresenter(mAppContext);
        }
        mPresenter.start(false);
    }

    /**
     * @param includeThrottle the hourly throttle gates scheduling only: the
     *                        run-time re-check (state may have changed during
     *                        the settle delay) must not skip itself.
     * @return why the check must not run right now, or null when it may run.
     */
    private String skipReason(boolean includeThrottle) {
        if (!JukeboxForeground.isAppInForeground()) {
            return "app is backgrounded (offers are never shown from the background)";
        }
        if (!JukeboxRoom.isRoomActive()) {
            return "no active room (join screen only)";
        }
        if (JukeboxRoom.instance(mAppContext).getCurrentVideoId() != null) {
            return "room song active (never during playback)";
        }
        if (includeThrottle) {
            long last = mAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_CHECK_MS, 0);
            long since = System.currentTimeMillis() - last;
            if (since < MIN_CHECK_INTERVAL_MS) {
                return "throttled (checked " + (since / 1000) + "s ago)";
            }
        }
        return null;
    }

    private void markChecked() {
        mAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply();
    }

    /** Test seam: fixed driver instance (records instead of hitting the network). Public for tests. */
    public static void overrideForTests(JukeboxUpdate update) {
        sInstance = update;
    }

    /** Test seam. Public for tests. */
    public static void resetForTests() {
        sInstance = null;
    }
}
