package net.tfour.jukebox.update;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Build 2473, issue #5 (bug 1): while the update card is VISIBLE on an
 * activity's decor, the OK key (DPAD_CENTER / ENTER) must install and BACK
 * must dismiss — routed at the very top of that activity's key dispatch
 * (a {@link Window.Callback} wrapper), BEFORE the player controls or the
 * JukeboxMode swallowing can see the key (roux2471.s3: UP/MENU went to the
 * player controls and OK logged "swallowed glue pause"). Both DOWN and UP
 * of the routed key are consumed.
 *
 * While the card is NOT visible every key is delegated to the wrapped
 * callback byte-for-byte unchanged: the 2470 swallowing behavior must still
 * work exactly as before (the activity — and through it the player — sees
 * the identical event).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateKeyRouterTest {

    /** Counts calls instead of installing. */
    private static class RecordingInstall implements JukeboxUpdateCard.Install {
        int calls;

        @Override
        public void install() {
            calls++;
        }
    }

    /** Records the keys the wrapped (original) callback gets to see. */
    public static class RecordingActivity extends Activity {
        KeyEvent lastKeyEvent;

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            lastKeyEvent = event;
            return super.dispatchKeyEvent(event);
        }
    }

    @Before
    public void setUp() {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
    }

    private ActivityController<RecordingActivity> newActivity() {
        ActivityController<RecordingActivity> controller =
                Robolectric.buildActivity(RecordingActivity.class);
        RecordingActivity activity = controller.setup().get();
        JukeboxUpdateKeyRouter.attach(activity);
        return controller;
    }

    private static boolean dispatch(Activity activity, int action, int keyCode) {
        Window.Callback callback = activity.getWindow().getCallback();
        assertTrue("the router must wrap the window callback after attach",
                callback instanceof JukeboxUpdateKeyRouter);
        return callback.dispatchKeyEvent(new KeyEvent(action, keyCode));
    }

    private static View cardOf(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup
                ? ((ViewGroup) decor).findViewWithTag(JukeboxUpdateCard.VIEW_TAG) : null;
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void okWhileCardIsVisibleInstallsAndConsumesDownAndUp() {
        RecordingActivity activity = newActivity().get();
        RecordingInstall install = new RecordingInstall();
        JukeboxUpdateCard.show(activity, "32.68", 2473, install);
        assertNotNull(cardOf(activity));

        // DPAD_CENTER (the remote's OK): DOWN is consumed, UP is consumed,
        // install runs exactly once, and the activity (player) never sees the key.
        assertTrue("DOWN OK must be consumed while the card is visible",
                dispatch(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("install must wait for the UP (click semantics)", 0, install.calls);
        assertTrue("UP OK must be consumed while the card is visible",
                dispatch(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("OK must install exactly once", 1, install.calls);
        assertNull("the player must never see the routed OK key",
                activity.lastKeyEvent);
        assertTrue(logged("key routed to the update card"));
    }

    @Test
    public void enterKeyWhileCardIsVisibleAlsoInstalls() {
        RecordingActivity activity = newActivity().get();
        RecordingInstall install = new RecordingInstall();
        JukeboxUpdateCard.show(activity, "32.68", 2473, install);

        assertTrue(dispatch(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        assertTrue(dispatch(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        assertEquals(1, install.calls);
    }

    @Test
    public void backWhileCardIsVisibleDismissesTheCard() {
        RecordingActivity activity = newActivity().get();
        RecordingInstall install = new RecordingInstall();
        JukeboxUpdateCard.show(activity, "32.68", 2473, install);
        assertNotNull(cardOf(activity));

        assertTrue("DOWN BACK must be consumed while the card is visible",
                dispatch(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        assertTrue("UP BACK must be consumed while the card is visible",
                dispatch(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertNull("BACK must dismiss (hide until the next foreground)",
                cardOf(activity));
        assertEquals("BACK must never install", 0, install.calls);
        assertNull("the player must never see the routed BACK key",
                activity.lastKeyEvent);
        assertTrue(logged("dismissed (BACK"));
    }

    @Test
    public void keysDelegateUnchangedToThePlayerWhileCardIsNotVisible() {
        RecordingActivity activity = newActivity().get();
        RecordingInstall install = new RecordingInstall();
        // No card shown. The OK key must reach the activity (and through it the
        // player / JukeboxMode) exactly as before — byte-for-byte unchanged.
        KeyEvent okDown = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);

        Window.Callback callback = activity.getWindow().getCallback();
        assertTrue(callback instanceof JukeboxUpdateKeyRouter);
        boolean consumed = callback.dispatchKeyEvent(okDown);

        assertFalse("the wrapper itself must never consume while the card is hidden",
                consumed);
        assertSame("the activity must see the identical event", okDown, activity.lastKeyEvent);
        assertEquals("no install without the card", 0, install.calls);
        assertFalse("no routing log without the card", logged("key routed to the update card"));
    }

    @Test
    public void navigationKeysDelegateUnchangedEvenWhileCardIsVisible() {
        // UP/MENU navigation goes to the player controls as before: only the
        // card's own keys (OK/BACK) are routed, everything else passes through.
        RecordingActivity activity = newActivity().get();
        JukeboxUpdateCard.show(activity, "32.68", 2473, new RecordingInstall());

        assertFalse(dispatch(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
        assertSame(KeyEvent.KEYCODE_DPAD_UP, activity.lastKeyEvent.getKeyCode());
    }

    // ---- build 2475, review item 5 (phase 1: failing test only) ----

    @Test
    public void okUpWithoutARoutedDownPassesThroughToThePlayer() {
        RecordingActivity activity = newActivity().get();
        RecordingInstall install = new RecordingInstall();
        JukeboxUpdateCard.show(activity, "32.68", 2473, install);
        assertNotNull(cardOf(activity));

        // The router must act on an OK ACTION_UP only if IT consumed that same
        // key's ACTION_DOWN. A bare UP (its DOWN never reached/never routed
        // through this router) must pass through, never install.
        boolean consumed = dispatch(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);

        assertFalse("an unrouted UP must not be consumed by the router", consumed);
        assertEquals("an unrouted UP must never install", 0, install.calls);
        assertNotNull("the player must see the unrouted UP", activity.lastKeyEvent);
        assertEquals("the activity must receive exactly the unrouted UP",
                KeyEvent.KEYCODE_DPAD_CENTER, activity.lastKeyEvent.getKeyCode());
        assertFalse("no routing log for an unrouted UP",
                logged("key routed to the update card"));
    }

    // ---- Nina's 2473 gazebo item (b): BACK in a second foreground activity ----

    @Test
    public void backInASecondForegroundActivityWhileTheCardIsVisibleDismissesAndIsConsumed() {
        // 2473 gazebo: the card was attached to the OLD activity's decor just
        // before the player launched; BACK in the player was NOT consumed by
        // the player's router (the card was not on ITS decor) and recreated
        // the player instead of dismissing the card.
        Activity old = Robolectric.buildActivity(Activity.class).setup().get();
        RecordingInstall install = new RecordingInstall();
        JukeboxUpdateCard.show(old, "32.68", 2473, install);
        assertNotNull("the card must be visible (attached to the old activity's decor)", cardOf(old));

        // A second activity becomes the foreground one; its window gets the
        // key router (the router of the CURRENT foreground activity).
        RecordingActivity foreground = newActivity().get();

        assertTrue("BACK DOWN must be consumed by the foreground activity's router while the card is visible",
                dispatch(foreground, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        assertTrue("BACK UP must be consumed by the foreground activity's router while the card is visible",
                dispatch(foreground, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertNull("BACK must dismiss the visible card (off the old activity's decor)",
                cardOf(old));
        assertEquals("BACK must never install", 0, install.calls);
        assertNull("the foreground activity (the player) must never see the routed BACK key",
                foreground.lastKeyEvent);
    }

    @Test
    public void attachIsIdempotentAndDetachRestoresTheOriginalCallback() {
        RecordingActivity activity = newActivity().get();
        Window window = activity.getWindow();
        JukeboxUpdateKeyRouter.attach(activity); // second attach: idempotent
        assertTrue(window.getCallback() instanceof JukeboxUpdateKeyRouter);

        JukeboxUpdateKeyRouter.detach(activity);
        assertSame("detach must restore the original callback (the activity)",
                activity, window.getCallback());
        JukeboxUpdateKeyRouter.detach(activity); // detach without wrapper: NOP
        assertSame(activity, window.getCallback());
    }

    @Test
    public void cardTextNamesTheKeysThatActuallyWork() {
        RecordingActivity activity = newActivity().get();
        JukeboxUpdateCard.show(activity, "32.68", 2473, new RecordingInstall());
        View card = cardOf(activity);
        assertNotNull(card);
        assertTrue(card instanceof ViewGroup);
        View text = ((ViewGroup) card).getChildAt(0);
        assertTrue(text instanceof android.widget.TextView);
        String message = ((android.widget.TextView) text).getText().toString();
        assertTrue("card must name OK: " + message, message.contains("press OK to install"));
        assertTrue("card must name BACK: " + message, message.contains("BACK to dismiss"));
        assertTrue("card must name the build: " + message, message.contains("build 2473"));
    }
}
