package net.tfour.jukebox.room;

import android.app.Activity;
import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Whether the app may start/foreground its own activities for room playback right
 * now (stjukebox flavor only). The app must never take over the TV (issue #3):
 * room "play" messages, resume checks and mismatch reopens that arrive while
 * another app is in use only update state — the song opens when the user returns
 * (join screen re-entry or player resume re-run the checks in the foreground).
 *
 * The foreground answer is tracked from the app's own activity lifecycle (see
 * {@link #init(Application)}): one of the app's activities being RESUMED means
 * foreground, everything else (HOME, another app like Fire TV Settings on top,
 * PIP) means backgrounded. Unlike the upstream importance poll
 * ({@link Utils#isAppInForegroundFixed()}), which lags the activity transition —
 * build 2465 stayed silent after HOME+relaunch because the one-shot resume check
 * ran while AMS still reported the old importance — the lifecycle counter flips
 * inside the resume transaction itself, before any posted check can run. Until
 * the first lifecycle event is seen (process start), the upstream check is used.
 *
 * The override seam exists for unit tests: Robolectric cannot reliably simulate a
 * backgrounded process (the importance query defaults to foreground there).
 */
public final class JukeboxForeground {
    private static volatile Boolean sTestOverride;
    private static volatile int sResumedActivities;
    private static volatile boolean sLifecycleSeen;
    private static volatile Application sTrackingApp;
    private static volatile Application.ActivityLifecycleCallbacks sCallbacks;

    private JukeboxForeground() {
    }

    /**
     * Registers the resumed-activity counter on the given app (idempotent; called
     * once at process start, before any activity exists). A defensive re-init
     * with a different {@link Application} unregisters the previous callbacks
     * first, so a second registration can never double-count (or leak) the first.
     */
    public static void init(Application app) {
        if (app == null) {
            return;
        }
        synchronized (JukeboxForeground.class) {
            if (sTrackingApp == app) {
                return; // already tracking this app instance
            }
            unregisterCallbacks();
            sTrackingApp = app;
            sResumedActivities = 0;
            sLifecycleSeen = false;
            Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    sResumedActivities++;
                    sLifecycleSeen = true;
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    // Clamp at zero: an unmatched pause (activity recreation edge
                    // case, multi-window) must never drive the counter negative —
                    // isAppInForeground() would stay false after the next resume
                    // and defer room resumes forever.
                    sResumedActivities = Math.max(0, sResumedActivities - 1);
                }

                @Override
                public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
                    // NOP
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    // NOP
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    // NOP
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {
                    // NOP
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    // NOP
                }
            };
            sCallbacks = callbacks;
            app.registerActivityLifecycleCallbacks(callbacks);
        }
    }

    /** Removes the tracker from the app it was registered on (if any). */
    private static void unregisterCallbacks() {
        Application trackingApp = sTrackingApp;
        Application.ActivityLifecycleCallbacks callbacks = sCallbacks;
        if (trackingApp != null && callbacks != null) {
            try {
                trackingApp.unregisterActivityLifecycleCallbacks(callbacks);
            } catch (RuntimeException ignored) {
                // App already gone: nothing to unregister.
            }
        }
    }

    /** True when one of the app's activities is the foreground/top activity. */
    public static boolean isAppInForeground() {
        Boolean override = sTestOverride;
        if (override != null) {
            return override;
        }
        if (sLifecycleSeen || sResumedActivities > 0) {
            // Lifecycle-tracked answer: instant and race-free during activity
            // transitions (no AMS importance lag).
            return sResumedActivities > 0;
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

    /** Test seam: forget the tracker so a fresh init can register a new app. */
    public static void resetForTests() {
        synchronized (JukeboxForeground.class) {
            unregisterCallbacks();
            sTrackingApp = null;
            sCallbacks = null;
            sResumedActivities = 0;
            sLifecycleSeen = false;
        }
    }
}
