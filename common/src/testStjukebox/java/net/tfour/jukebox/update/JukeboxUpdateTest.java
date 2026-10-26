package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Build 2471, issue #5: the self-update check follows app-level foreground
 * transitions on ANY activity (not just the join screen), with the old gates
 * removed and a 60s debounce, never checks/shows/launches from the background
 * (issue #3), and offers a newer version as a NON-MODAL corner card on the
 * current activity. Every decision is logged under {@code JukeboxUpdate}.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class JukeboxUpdateTest {

    /** Records checks instead of touching AppUpdatePresenter/the network. */
    static class Recording extends JukeboxUpdate {
        long clock = 1_000;
        int started;

        Recording(Context ctx) {
            super(ctx);
        }

        @Override
        protected void startCheck() {
            started++;
        }

        @Override
        protected long nowMs() {
            return clock;
        }
    }

    private Application app;
    private Recording update;

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() {
        // Robolectric defaults the importance check to foreground; pin it so
        // the gates read a definite answer (see JukeboxForeground).
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        JukeboxUpdate.resetForTests();
        app = app();
        update = new Recording(app);
        JukeboxUpdate.overrideForTests(update);
    }

    @After
    public void tearDown() {
        JukeboxUpdate.resetForTests();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private ActivityController<Activity> newActivity() {
        return Robolectric.buildActivity(Activity.class);
    }

    @Test
    public void foregroundTransitionOnAnyActivityTriggersCheck() throws Exception {
        JukeboxUpdate.init(app);
        // A plain activity (not the join screen) coming to the foreground.
        newActivity().setup();
        assertEquals("any activity's start must run the check", 1, update.started);
        assertTrue(logged("foreground trigger (activity started)"));
        assertTrue(logged("check: querying update manifest"));

        // Staying in the foreground (second activity same-task hop): the
        // STARTED set never empties mid-hop, so no new trigger fires for the
        // hop itself; the check count stays debounced at one.
        update.clock += 10_000;
        ActivityController<Activity> second = newActivity().setup();
        assertEquals("mid-hop starts are not foreground transitions", 1, update.started);
        second.get();
    }

    @Test
    public void secondCheckWithin60sIsDebouncedAndAfter60sChecksAgain() {
        JukeboxUpdate.init(app);
        ActivityController<Activity> first = newActivity().setup();
        assertEquals(1, update.started);

        // App backgrounded, then returned to the foreground 10s later:
        // within the 60s window -> debounced.
        first.pause().stop();
        update.clock += 10_000;
        ActivityController<Activity> second = newActivity().setup();
        assertEquals("second check within 60s must be debounced", 1, update.started);
        assertTrue(logged("skip: debounced"));
        assertTrue(logged("s left"));

        // Another transition 60s after the last real check: checks again.
        second.pause().stop();
        update.clock += JukeboxUpdate.MIN_CHECK_INTERVAL_MS;
        newActivity().setup();
        assertEquals("after the 60s window a new check must run", 2, update.started);
    }

    @Test
    public void backgroundedNeverChecksPromptsOrLaunches() {
        JukeboxUpdate.init(app);
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(false);

        newActivity().setup();
        assertEquals("no check may ever run from the background", 0, update.started);
        assertTrue(logged("skip: app is backgrounded"));

        // No prompt either: the presenter's card is gated the same way.
        Activity activity = newActivity().setup().get();
        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, () -> activity);
        presenter.onUpdateFound("32.68", Arrays.asList("fix"), null);
        assertNull("never show a prompt from the background", cardOf(activity));
        // And nothing was launched (no dialog activity, no installer intent).
        assertNull(nextStartedActivity());
    }

    @Test
    public void newerVersionShowsNonModalCardWithoutDisturbingThePlayer() {
        JukeboxUpdate.init(app);
        ActivityController<Activity> controller = newActivity().setup();
        Activity activity = controller.get();
        View focusBefore = activity.getCurrentFocus();

        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, () -> activity);
        presenter.onUpdateFound("32.68", Arrays.asList("fix", "polish"), null);

        View card = cardOf(activity);
        assertNotNull("newer version must attach the prompt card", card);
        assertTrue("card must be attached to the activity's decor",
                card.getParent() == activity.getWindow().getDecorView());
        assertSame("the card must not steal focus from the player",
                focusBefore, activity.getCurrentFocus());
        assertTrue(card.isFocusable());
        assertTrue(logged("prompt shown"));
        assertTrue(logged("result: update available versionName=32.68"));
        // No dialog activity was started over the playing screen.
        assertNull(nextStartedActivity());
    }

    @Test
    public void sameOrOlderVersionShowsNoPrompt() {
        Activity activity = newActivity().setup().get();
        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, () -> activity);

        presenter.onUpdateError(new IllegalStateException("Latest version"));

        assertNull("no prompt when the version is current", cardOf(activity));
        assertTrue(logged("result: no update"));
    }

    @Test
    public void dismissHidesUntilTheNextForegroundTransition() {
        JukeboxUpdate.init(app);
        ActivityController<Activity> controller = newActivity().setup();
        Activity activity = controller.get();
        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, () -> activity);

        presenter.onUpdateFound("32.68", null, null);
        assertNotNull(cardOf(activity));

        JukeboxUpdateCard.dismiss(activity, "dismissed");
        assertNull("dismiss must remove the card", cardOf(activity));
        assertTrue(logged("prompt dismissed"));

        // Next foreground transition -> a fresh check -> the offer shows again.
        controller.pause().stop();
        update.clock += JukeboxUpdate.MIN_CHECK_INTERVAL_MS;
        ActivityController<Activity> second = newActivity().setup();
        assertEquals(2, update.started);
        presenter = new JukeboxUpdatePresenter(app, () -> second.get());
        presenter.onUpdateFound("32.68", null, null);
        assertNotNull("next foreground transition must re-show the offer",
                cardOf(second.get()));
    }

    @Test
    public void cardAutoHidesAfterTimeout() {
        Activity activity = newActivity().setup().get();
        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, () -> activity);
        presenter.onUpdateFound("32.68", null, null);
        assertNotNull(cardOf(activity));

        ShadowLooper shadowLooper = org.robolectric.Shadows.shadowOf(Looper.getMainLooper());
        shadowLooper.idleFor(JukeboxUpdateCard.AUTO_HIDE_MS, TimeUnit.MILLISECONDS);
        // The card's timeout runnable is scheduled on the (shadow) main looper.
        assertNull("auto-hide after ~15s must remove the card", cardOf(activity));
        assertTrue(logged("prompt timeout"));
    }

    // ---- Nina's 2473 gazebo item (a): the card follows the foreground ----

    @Test
    public void cardAttachesToTheResumedActivityAndMovesWhenTheForegroundChanges() {
        JukeboxUpdate.init(app);
        ActivityController<Activity> first = newActivity().setup();
        Activity firstActivity = first.get();
        assertEquals("the resumed activity must be the first one", firstActivity, update.currentActivity());
        JukeboxUpdatePresenter presenter = new JukeboxUpdatePresenter(app, update::currentActivity);

        presenter.onUpdateFound("32.68", Arrays.asList("fix"), null);
        assertNotNull("the card must attach to the current resumed activity", cardOf(firstActivity));

        // The foreground changes while the card is visible (a second activity
        // is resumed, e.g. the player launching): the card must MOVE to the
        // newly resumed foreground activity's decor (2473 gazebo: it stayed
        // on the old activity's decor and was never drawn/never got keys).
        first.pause();
        ActivityController<Activity> second = newActivity().setup();
        Activity secondActivity = second.get();
        assertEquals("the resumed activity must now be the second one", secondActivity, update.currentActivity());

        assertNotNull("a visible card must move to the newly resumed foreground activity",
                cardOf(secondActivity));
        assertNull("the card must not stay on the old activity's decor", cardOf(firstActivity));
    }

    private static View cardOf(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return null;
        }
        return ((ViewGroup) decor).findViewWithTag(JukeboxUpdateCard.VIEW_TAG);
    }

    private static android.content.Intent nextStartedActivity() {
        return org.robolectric.Shadows.shadowOf(
                RuntimeEnvironment.getApplication()).getNextStartedActivity();
    }
}