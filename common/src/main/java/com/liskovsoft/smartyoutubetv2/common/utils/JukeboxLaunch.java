package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

/**
 * Central choke point for activity launches and launch diagnostics in the
 * stjukebox flavor (common/main cannot reference the flavor classes, so — like
 * {@code JukeboxMode} — this is the only guarded hook; other flavors never
 * initialize it and every call is a no-op that allows the launch).
 *
 * The stjukebox flavor registers a {@link Gate} at process start
 * (see {@code net.tfour.jukebox.JukeboxApp}): the app must never take over the
 * TV while another app is in use (issue #3), so every app-initiated activity
 * start (room socket events, player engine callbacks, remote-control commands,
 * services, receivers, handlers or timers) funnels through
 * {@link #checkLaunch(String, String)}, which blocks (and logs) while the app
 * is backgrounded. Every launch decision is logged under the {@code JukeboxLaunch}
 * tag so gazebo runs can verify who launched what and why. Incoming intents of
 * {@code SplashActivity}/{@code PlaybackActivity} are logged via
 * {@link #noteIntent(Activity, String, Intent)} so external launches (adb
 * {@code am start}, deep links) can be told apart from internal ones.
 */
public final class JukeboxLaunch {
    public static final String TAG = "JukeboxLaunch";

    /** Flavor-provided foreground answer (stjukebox only). */
    public interface Gate {
        boolean isAppInForeground();
    }

    private static volatile boolean sJukebox;
    private static volatile Gate sGate;

    private JukeboxLaunch() {
    }

    /** Called once at process start by the stjukebox flavor; never called by other flavors. */
    public static void init(boolean jukebox, Gate gate) {
        sJukebox = jukebox;
        sGate = gate;
    }

    public static boolean isJukeboxFlavor() {
        return sJukebox;
    }

    /**
     * Logs the launch decision. Returns false only in the stjukebox flavor while
     * the app is backgrounded: the caller must then NOT start the activity.
     *
     * @param activity simple name of the activity that is about to start
     * @param reason   caller context (e.g. "ViewManager.startView")
     */
    public static boolean checkLaunch(String activity, String reason) {
        Gate gate = sGate;
        if (!sJukebox || gate == null) {
            return true; // other flavors (or not initialized yet): old behavior
        }
        boolean foreground = gate.isAppInForeground();
        if (foreground) {
            log("start " + activity + " (" + reason + ")");
            return true;
        }
        log("blocked " + activity + " (" + reason + "): app is backgrounded, no TV takeover");
        return false;
    }

    /** Logs a user/key-driven activity start (never gated, still verifiable). */
    public static void noteLaunch(String activity, String reason) {
        if (sJukebox) {
            log("start " + activity + " (" + reason + ")");
        }
    }

    /**
     * Logs the incoming intent of a started activity (action/data/flags/referrer)
     * so external launches can be told apart from internal ones. The referrer
     * comes from the started {@link Activity} itself ({@link Activity#getReferrer()},
     * API 22+): {@link Intent} has no getReferrer(). No extras are logged (they
     * can carry tokens).
     */
    public static void noteIntent(Activity activity, String where, Intent intent) {
        if (!sJukebox || intent == null) {
            return;
        }
        String referrer = activity != null && Build.VERSION.SDK_INT >= 22
                ? String.valueOf(activity.getReferrer()) : "n/a";
        log(where + ": action=" + intent.getAction()
                + " data=" + intent.getData()
                + " flags=0x" + Integer.toHexString(intent.getFlags())
                + " referrer=" + referrer);
    }

    /** Test seam: restore the pristine (non-jukebox) state. */
    public static void resetForTests() {
        sJukebox = false;
        sGate = null;
    }

    private static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests: android.util.Log is not mocked there.
        }
    }
}
