package net.tfour.jukebox.room;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether the app may start/foreground its own activities for room playback right
 * now (stjukebox flavor only). The app must never take over the TV (issue #3):
 * room "play" messages, resume checks and mismatch reopens that arrive while
 * another app is in use only update state — the song opens when the user returns
 * (join screen re-entry or player resume re-run the checks in the foreground).
 *
 * The foreground answer is tracked from the app's own activity lifecycle (see
 * {@link #init(Application)}), registered at the very start of
 * Application.onCreate so no activity of ours can ever be missed:
 * <ul>
 * <li>a process-wide set of the STARTED activities, tracked by identity
 * ({@code onActivityStarted} adds, {@code onActivityStopped}/{@code onActivityDestroyed}
 * remove — a set, not a count, so a spurious/duplicate stop can never over-
 * decrement a later start, and a destroyed started activity is always
 * dropped). Android pauses the old activity BEFORE the new one resumes
 * ({@code A.onPause -> B.onCreate -> B.onStart -> B.onResume -> A.onStop}),
 * so build 2466's resumed-activity counter hit zero inside every same-task hop
 * and the deep-link open running in SplashActivity.onCreate read
 * "backgrounded" while JukeboxJoinActivity was still on screen underneath.
 * The old activity only STOPs after the new one resumes, so the STARTED set
 * never empties mid-hop (Splash -> Join -> Playback transitions always read
 * foregrounded);</li>
 * <li>the currently resumed activity, kept until THAT activity stops — not
 * pauses. A pause only means another activity of ours (or a dialog) is coming
 * up; the paused-but-visible activity still proves the app is on screen. No
 * stale reference can be stranded either: the resumed slot is cleared on the
 * activity's own stop (and destroy).</li>
 * </ul>
 *
 * All of the app's activities are counted (JukeboxJoinActivity included — it is
 * not registered with ViewManager, but the Application callbacks see it like any
 * other). A launch asked for by one of our own activities (see
 * {@link #isCallerInForeground(Context)}) is a foreground launch even while the
 * counters are momentarily in transition.
 *
 * Until the first lifecycle event is seen (process start), the upstream
 * importance poll ({@link Utils#isAppInForegroundFixed()}) is used.
 *
 * The override seam exists for unit tests: Robolectric cannot reliably simulate a
 * backgrounded process (the importance query defaults to foreground there).
 */
public final class JukeboxForeground {
    private static volatile Boolean sTestOverride;
    /** The app's currently STARTED activities, by identity (never a count). */
    private static volatile Set<Activity> sStartedActivities = new HashSet<>();
    /** The currently resumed activity; kept until it stops or is destroyed. */
    private static volatile Activity sResumedActivity;
    private static volatile boolean sLifecycleSeen;
    private static volatile Application sTrackingApp;
    private static volatile Application.ActivityLifecycleCallbacks sCallbacks;

    private JukeboxForeground() {
    }

    /**
     * Registers the started/resumed trackers on the given app (idempotent; called
     * once at the very start of Application.onCreate, before any activity can
     * exist). A defensive re-init with a different {@link Application} unregisters
     * the previous callbacks first, so a second registration can never double-count
     * (or leak) the first.
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
            sStartedActivities = new HashSet<>();
            sResumedActivity = null;
            sLifecycleSeen = false;
            Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity activity) {
                    synchronized (JukeboxForeground.class) {
                        sStartedActivities.add(activity);
                    }
                    sLifecycleSeen = true;
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    // Remove by identity, never decrement a count: a spurious
                    // duplicate stop (activity recreation edge case, multi-window)
                    // must not eat the next start's entry — a corrupted count
                    // would defer room resumes forever.
                    synchronized (JukeboxForeground.class) {
                        sStartedActivities.remove(activity);
                    }
                    sLifecycleSeen = true;
                    // The resumed activity is dropped on ITS stop, not its pause:
                    // pausing is exactly the same-task hop window (old paused,
                    // new one not yet resumed) that must keep reading foreground.
                    if (activity == sResumedActivity) {
                        sResumedActivity = null;
                    }
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    sResumedActivity = activity;
                    sLifecycleSeen = true;
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    // NOP on purpose (see onActivityStopped): a pause must never
                    // read as backgrounded while the activity is still visible.
                }

                @Override
                public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
                    // NOP
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {
                    // NOP
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    // An activity destroyed while STARTed (its paired stop can be
                    // spurious/ignored, e.g. edge-case teardown) must be dropped
                    // here too — otherwise it would keep the app "foreground"
                    // forever and room playback would take over from HOME.
                    synchronized (JukeboxForeground.class) {
                        sStartedActivities.remove(activity);
                    }
                    if (activity == sResumedActivity) {
                        sResumedActivity = null;
                    }
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

    /** True when one of the app's activities is started (visible) or resumed. */
    public static boolean isAppInForeground() {
        Boolean override = sTestOverride;
        if (override != null) {
            return override;
        }
        if (!sStartedActivities.isEmpty() || sResumedActivity != null) {
            // Lifecycle-tracked answer: instant and race-free during activity
            // transitions (no AMS importance lag, no zero window mid-hop).
            return true;
        }
        if (sLifecycleSeen) {
            // Lifecycle callbacks are live and none of our activities is
            // started/resumed: HOME, another app on top or PIP — backgrounded.
            return false;
        }
        try {
            return Utils.isAppInForegroundFixed();
        } catch (RuntimeException e) {
            // Broken importance query: assume foreground so room playback never
            // stalls forever (a wrong "background" would silently drop songs).
            return true;
        }
    }

    /**
     * Caller-aware answer for the launch gate: a launch asked for by an Activity
     * of ours that is alive and resumed (or holds window focus) is a foreground
     * launch — e.g. ViewManager.startView invoked with an activity context, or
     * the join screen forwarding an intent while it is the resumed activity. Any
     * other caller (application/service context, dead or not-yet-started
     * activity) falls back to the process-wide answer.
     */
    public static boolean isCallerInForeground(Context caller) {
        Boolean override = sTestOverride;
        if (override != null) {
            return override;
        }
        Activity activity = unwrapActivity(caller);
        if (activity != null && isAlive(activity)
                && (activity == sResumedActivity || hasWindowFocus(activity))) {
            return true;
        }
        return isAppInForeground();
    }

    private static boolean isAlive(Activity activity) {
        return !activity.isFinishing() && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static boolean hasWindowFocus(Activity activity) {
        try {
            return activity.hasWindowFocus();
        } catch (RuntimeException ignored) {
            // Window torn down mid-query: not focused.
            return false;
        }
    }

    /** Unwraps {@link ContextWrapper}s (e.g. LocaleContextWrapper) to the base Activity. */
    private static Activity unwrapActivity(Context context) {
        Context current = context;
        for (int i = 0; i < 10 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
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
            sStartedActivities = new HashSet<>();
            sResumedActivity = null;
            sLifecycleSeen = false;
        }
    }
}
