package net.tfour.jukebox.update;

import android.app.Activity;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import net.tfour.jukebox.room.JukeboxLog;

import java.util.List;

/**
 * Build 2473, issue #5 (bug 1): app-wide key routing for the stjukebox update
 * card. A {@link Window.Callback} wrapper, swapped in as each activity's
 * window callback by {@link JukeboxUpdate}'s ActivityLifecycleCallbacks
 * ({@link #attach(Activity)} on resume, {@link #detach(Activity)} on pause),
 * so it works on ANY screen — including PlaybackActivity, where DPAD
 * UP/MENU open the player controls and OK was swallowed as
 * "swallowed glue pause" before ever reaching the card
 * (roux2471.s3.log).
 *
 * While the card is VISIBLE on the activity's decor, this wrapper consumes
 * the card's keys at the very top of the dispatch — BEFORE the view
 * hierarchy, the player glue or JukeboxMode can see them:
 * <ul>
 * <li>OK (DPAD_CENTER / ENTER) = install — consumed on DOWN and UP, the
 *     action runs on UP (click semantics, so key repeat cannot re-run
 *     it);</li>
 * <li>BACK = dismiss (hidden until the next foreground transition).</li>
 * </ul>
 *
 * While the card is NOT visible — and for every key that is not the card's
 * own — the wrapper is a pure pass-through: it delegates byte-for-byte
 * unchanged to the wrapped callback, so the 2470 JukeboxMode swallowing
 * behavior (and all normal key handling) is completely unaffected. The
 * card never steals focus: it only ever reacts to its own two keys.
 */
public class JukeboxUpdateKeyRouter implements Window.Callback {

    private final Activity mActivity;
    private final Window.Callback mDelegate;
    /** True after THIS router consumed the install key's ACTION_DOWN (build 2475 item 5). */
    private boolean mConsumedInstallDown;

    private JukeboxUpdateKeyRouter(Activity activity, Window.Callback delegate) {
        mActivity = activity;
        mDelegate = delegate;
    }

    /**
     * Wraps the activity's window callback with the key router (idempotent:
     * a wrapped window is left as is).
     */
    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null || window.getCallback() == null
                || window.getCallback() instanceof JukeboxUpdateKeyRouter) {
            return; // nothing to wrap, or already wrapped
        }
        window.setCallback(new JukeboxUpdateKeyRouter(activity, window.getCallback()));
    }

    /**
     * Restores the wrapped callback (NOP when the window is not wrapped).
     */
    public static void detach(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        Window window = activity.getWindow();
        Window.Callback current = window.getCallback();
        if (current instanceof JukeboxUpdateKeyRouter) {
            window.setCallback(((JukeboxUpdateKeyRouter) current).mDelegate);
        }
    }

    /** The card currently visible on the activity's decor, or null. */
    private static JukeboxUpdateCard visibleCard(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return null;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return null;
        }
        // Presence on the decor == visible: show() adds the card there and
        // dismiss()/auto-hide remove it, so "is shown" needs no extra checks.
        View card = ((ViewGroup) decor).findViewWithTag(JukeboxUpdateCard.VIEW_TAG);
        if (card == null || !(card instanceof JukeboxUpdateCard) || card.getParent() != decor) {
            return null;
        }
        return (JukeboxUpdateCard) card;
    }

    /** True for the OK key family the card answers to (the remote OK is DPAD_CENTER). */
    static boolean isInstallKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    /**
     * @return true when the card consumed the event (caller must not dispatch
     *         it further). Both DOWN and UP of the card's keys are consumed;
     *         every other key (or no visible card) delegates unchanged.
     */
    private boolean route(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        JukeboxUpdateCard card = visibleCard(mActivity);
        if (card == null) {
            return false; // not visible: byte-for-byte unchanged behavior
        }
        int keyCode = event.getKeyCode();
        if (isInstallKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                mConsumedInstallDown = true;
                return true; // consume DOWN
            }
            if (!mConsumedInstallDown) {
                return false; // UP whose DOWN this router never consumed: pass through
            }
            mConsumedInstallDown = false;
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "key routed to the update card: "
                    + KeyEvent.keyCodeToString(keyCode) + " = install");
            card.invokeInstall();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "key routed to the update card: BACK = dismiss");
                JukeboxUpdateCard.dismiss(mActivity, "dismissed (BACK while the update card was shown)");
            }
            return true; // consume DOWN and UP
        }
        return false; // not the card's key: the player keeps it
    }

    // ---- Window.Callback: route first, then delegate everything unchanged ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (route(event)) {
            return true;
        }
        return mDelegate.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return mDelegate.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return mDelegate.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return mDelegate.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return mDelegate.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return mDelegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return mDelegate.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return mDelegate.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return mDelegate.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return mDelegate.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return mDelegate.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        mDelegate.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        mDelegate.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        mDelegate.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        mDelegate.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        mDelegate.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        mDelegate.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return mDelegate.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return mDelegate.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return mDelegate.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return mDelegate.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        mDelegate.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        mDelegate.onActionModeFinished(mode);
    }

    // Default Window.Callback methods (onProvideKeyboardShortcuts /
    // onPointerCaptureChanged) are inherited: they never dispatch keys.
}
