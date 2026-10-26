package net.tfour.jukebox.room;

import android.app.Activity;
import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build 2466, issue (1): the foreground gate must not depend on the AMS
 * importance poll, which lags the activity transition — build 2465's one-shot
 * resume check ran while AMS still reported "background" right after the
 * relaunch tap, deferred silently and the room never played again. The tracker
 * flips inside the activity's own resume/pause callbacks, before any posted
 * room listener callback can run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxForegroundLifecycleTest {
    private Application app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() {
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
    }

    @After
    public void tearDown() {
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
    }

    @Test
    public void trackerFollowsActivityResumeAndPause() {
        JukeboxForeground.init(app());

        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();

        assertTrue("a resumed activity of the app means foreground", JukeboxForeground.isAppInForeground());

        ac.pause();
        assertFalse("a paused activity (HOME, another app on top) means backgrounded",
                JukeboxForeground.isAppInForeground());

        ac.resume();
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause().stop();
        assertFalse("a stopped activity means backgrounded", JukeboxForeground.isAppInForeground());

        ac.destroy();
    }

    @Test
    public void secondResumedActivityKeepsForegroundDuringTheTransition() {
        JukeboxForeground.init(app());

        ActivityController<Activity> first = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> second = Robolectric.buildActivity(Activity.class).setup();

        // The old activity pauses before the new one resumes: the counter dips to
        // zero for that instant, but both belong to the app, so once either is
        // resumed the app is foregrounded.
        assertTrue(JukeboxForeground.isAppInForeground());

        first.pause().stop().destroy();
        assertTrue("the second activity still keeps the app foregrounded",
                JukeboxForeground.isAppInForeground());

        second.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
    }

    @Test
    public void testOverrideBeatsTheTracker() {
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();

        JukeboxForeground.setTestOverride(false);
        assertFalse(JukeboxForeground.isAppInForeground());
        JukeboxForeground.setTestOverride(true);
        assertTrue(JukeboxForeground.isAppInForeground());
        JukeboxForeground.setTestOverride(null);
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause().stop().destroy();
    }

    @Test
    public void beforeAnyLifecycleEventFallsBackToUpstreamCheck() {
        // Fresh process: no activity resumed yet. Robolectric's importance query
        // defaults to foreground (see the old comment in JukeboxForeground), so
        // the upstream fallback must allow — production fills the answer as soon
        // as the first activity resumes.
        JukeboxForeground.init(app());
        assertTrue(JukeboxForeground.isAppInForeground());
    }

    @Test
    public void initIsIdempotentPerAppInstance() {
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();
        JukeboxForeground.init(app()); // e.g. defensive double init

        assertTrue(JukeboxForeground.isAppInForeground());
        ac.pause().stop().destroy();
    }

    @Test
    public void extraPauseNeverDrivesTheResumedCounterNegative() {
        // Review critical #1 / test gap 10: an unmatched pause (activity
        // recreation edge case, multi-window) must not push the counter below
        // zero — a negative counter keeps isAppInForeground() false even after
        // the next resume, so the room resume would keep deferring forever.
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause();
        assertFalse(JukeboxForeground.isAppInForeground());
        ac.pause(); // spurious duplicate pause: clamped at zero, not -1
        assertFalse(JukeboxForeground.isAppInForeground());

        ac.resume();
        assertTrue("the resume after a duplicate pause must report foreground again",
                JukeboxForeground.isAppInForeground());

        ac.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
    }
}
