package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import net.tfour.jukebox.room.JukeboxForeground;
import net.tfour.jukebox.room.JukeboxLog;

import java.util.HashSet;
import java.util.Set;

/**
 * Self-update check driver for the stjukebox flavor (tag {@code JukeboxUpdate}).
 *
 * Build 2471, issue #5: the check is hooked to APP-LEVEL foreground
 * transitions (any activity), not just the join screen. {@link #init(Application)}
 * registers an {@link Application.ActivityLifecycleCallbacks} on the app (from
 * JukeboxApp.init, i.e. the very start of Application.onCreate), and the
 * started-activities set going from empty to non-empty = the app was opened or
 * returned to the foreground -> one manifest check. The old gates are gone:
 * no join-screen-only requirement, no hourly throttle and no "never during
 * playback" skip; a 60s in-memory debounce is the only throttle. Nothing is
 * ever checked, shown or launched while the app is backgrounded (issue #3):
 * the gate runs at trigger time AND again at run time (the app may have gone
 * to the background between the two).
 *
 * Every decision is logged under {@code JukeboxUpdate}: foreground trigger,
 * debounce skip (seconds left), background skip, and the URL used / result
 * are logged by {@link JukeboxUpdatePresenter} once the check runs.
 */
public class JukeboxUpdate {
    public static final String LOG_TAG = "JukeboxUpdate";
    /** At most one manifest check per 60s, however foreground transitions arrive. */
    static final long MIN_CHECK_INTERVAL_MS = 60_000;

    private static volatile JukeboxUpdate sInstance;

    private final Context mAppContext;
    /** The app's STARTED activities, by identity (same shape as JukeboxForeground). */
    private final Set<Activity> mStartedActivities = new HashSet<>();
    private volatile Activity mResumedActivity;
    private volatile long mLastCheckMs;
    private Application mTrackingApp;
    private Application.ActivityLifecycleCallbacks mCallbacks;
    private JukeboxUpdatePresenter mPresenter;

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

    /**
     * Registers the app-level foreground-transition hook (stjukebox only, called
     * from JukeboxApp.init at the very start of Application.onCreate so no
     * activity of ours can ever be missed). Idempotent per app instance.
     */
    public static void init(Application app) {
        if (app == null) {
            return;
        }
        JukeboxUpdate update = instance(app);
        synchronized (update) {
            if (update.mTrackingApp == app) {
                return; // already hooked to this app instance
            }
            unregister(update);
            update.mStartedActivities.clear();
            update.mResumedActivity = null;
            update.mLastCheckMs = 0;
            update.mTrackingApp = app;
            update.mCallbacks = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity activity) {
                    boolean wasBackgrounded;
                    synchronized (update) {
                        wasBackgrounded = update.mStartedActivities.isEmpty();
                        update.mStartedActivities.add(activity);
                    }
                    if (wasBackgrounded) {
                        // App opened or returned to the foreground (ANY activity):
                        // this is the build 2471 update-check trigger. Android pauses
                        // the old activity BEFORE the new one starts, but only STOPs
                        // it after the new one is started, so the STARTED set never
                        // empties mid-hop and this only fires on real transitions.
                        update.onForegroundTransition("activity started");
                    }
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    // Remove by identity, never a count (see JukeboxForeground).
                    synchronized (update) {
                        update.mStartedActivities.remove(activity);
                    }
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    update.mResumedActivity = activity;
                    // Build 2473, issue #5 (bug 1): the app-wide key routing for
                    // the update card — OK = install, BACK = dismiss while the
                    // card is visible, byte-for-byte pass-through otherwise.
                    JukeboxUpdateKeyRouter.attach(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    // NOP: a pause must never read as backgrounded (see JukeboxForeground).
                    // The key router of the paused activity is unwrapped HERE, on its
                    // pause (detach only unwraps when the router is the outermost
                    // callback — see JukeboxUpdateKeyRouter.detach).
                    JukeboxUpdateKeyRouter.detach(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    // NOP
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    // NOP
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    JukeboxUpdateKeyRouter.detach(activity);
                    synchronized (update) {
                        update.mStartedActivities.remove(activity);
                    }
                    if (activity == update.mResumedActivity) {
                        update.mResumedActivity = null;
                    }
                }
            };
            app.registerActivityLifecycleCallbacks(update.mCallbacks);
            JukeboxLog.i(LOG_TAG, "init: app-level foreground transitions now trigger the update check");
        }
    }

    /** The app's currently resumed activity (where a prompt would be attached), or null. */
    public Activity currentActivity() {
        return mResumedActivity;
    }

    /**
     * The app came to the foreground: log the trigger, complete a deferred
     * install (a download that finished while the app was backgrounded,
     * issue #5), then run the gated, debounced manifest check. Public so
     * tests (in the room fixture package) can drive it directly; production
     * enters via the lifecycle callbacks.
     */
    public void onForegroundTransition(String via) {
        JukeboxLog.i(LOG_TAG, "foreground trigger (" + via + ")");
        JukeboxUpdatePresenter presenter = mPresenter;
        if (presenter != null) {
            // Build 2473, issue #5: keep the apk downloaded while backgrounded
            // and offer the install again now that the app is foreground.
            presenter.onAppForeground();
        }
        maybeCheck("foreground transition (" + via + ")");
    }

    /** Gates + 60s debounce + logged decision, then the (re-gated) check. */
    public void maybeCheck(String via) {
        String skip = skipReason();
        if (skip != null) {
            JukeboxLog.i(LOG_TAG, "skip: " + skip);
            return;
        }
        // Stamp the debounce BEFORE running: an in-flight failure below must
        // not be retried on the very next transition.
        mLastCheckMs = nowMs();
        runCheck(via);
    }

    private void runCheck(String via) {
        // Re-gated at run time: the app may have gone to the background since
        // the trigger. Never check, show or launch anything from the background.
        if (!JukeboxForeground.isAppInForeground()) {
            JukeboxLog.i(LOG_TAG, "skip at run time: app is backgrounded (issue #3)");
            return;
        }
        try {
            JukeboxLog.i(LOG_TAG, "check: querying update manifest (" + via + ")");
            startCheck();
        } catch (Throwable error) {
            JukeboxLog.i(LOG_TAG, "check failed: " + error);
        }
    }

    /** Starts the real manifest check. Overridden by tests (records instead). */
    protected void startCheck() {
        if (mPresenter == null) {
            mPresenter = new JukeboxUpdatePresenter(mAppContext, this::currentActivity);
        }
        mPresenter.startCheck();
    }

    /**
     * @return why the check must not run right now (logged), or null when it may run.
     */
    private String skipReason() {
        if (!JukeboxForeground.isAppInForeground()) {
            return "app is backgrounded (never check, show or launch from the background, issue #3)";
        }
        long last = mLastCheckMs;
        if (last > 0) {
            long left = MIN_CHECK_INTERVAL_MS - (nowMs() - last);
            if (left > 0) {
                return "debounced (" + ((left + 999) / 1000) + "s left of the "
                        + (MIN_CHECK_INTERVAL_MS / 1000) + "s check interval)";
            }
        }
        return null;
    }

    /** Clock seam for tests. */
    protected long nowMs() {
        return System.currentTimeMillis();
    }

    private static void unregister(JukeboxUpdate update) {
        Application trackingApp = update.mTrackingApp;
        Application.ActivityLifecycleCallbacks callbacks = update.mCallbacks;
        if (trackingApp != null && callbacks != null) {
            try {
                trackingApp.unregisterActivityLifecycleCallbacks(callbacks);
            } catch (RuntimeException ignored) {
                // App already gone: nothing to unregister.
            }
        }
        update.mTrackingApp = null;
        update.mCallbacks = null;
    }

    /** Test seam: fixed driver instance (records instead of hitting the network). Public for tests. */
    public static void overrideForTests(JukeboxUpdate update) {
        sInstance = update;
    }

    /** Test seam: forget the instance and unhook its lifecycle callbacks. Public for tests. */
    public static void resetForTests() {
        JukeboxUpdate update = sInstance;
        if (update != null) {
            synchronized (update) {
                unregister(update);
                update.mStartedActivities.clear();
                update.mResumedActivity = null;
                update.mLastCheckMs = 0;
            }
        }
        sInstance = null;
    }
}
