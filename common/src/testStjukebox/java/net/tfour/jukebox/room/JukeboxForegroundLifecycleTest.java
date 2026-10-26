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
 * Build 2467, issue (1): the foreground gate must not depend on the AMS
 * importance poll (lags the transition) NOR on a resumed-activity counter —
 * build 2466 read "backgrounded" inside every same-task activity hop because
 * Android pauses the old activity (JukeboxJoinActivity, focused, visible)
 * BEFORE the new one (SplashActivity taking a VIEW intent) resumes, and the
 * deep-link open runs exactly in that window (SplashActivity.onCreate ->
 * openVideo -> ViewManager.startView). The rebuilt tracker counts STARTED
 * activities (the old activity only stops after the new one resumes) plus the
 * currently resumed activity (dropped on its own stop, never its pause), so
 * transitional hops inside our own task (Splash -> Join -> Playback) never read
 * backgrounded — while HOME / another app on top (all our activities stopped)
 * still does.
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
    public void trackerFollowsActivityStartAndStop() {
        JukeboxForeground.init(app());

        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();

        assertTrue("a started activity of the app means foreground", JukeboxForeground.isAppInForeground());

        ac.pause();
        assertTrue("a paused-but-visible activity (same-task hop in progress) is still foreground",
                JukeboxForeground.isAppInForeground());

        ac.stop();
        assertFalse("a stopped activity (HOME, another app on top) means backgrounded",
                JukeboxForeground.isAppInForeground());

        ac.resume();
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause().stop();
        assertFalse(JukeboxForeground.isAppInForeground());

        ac.destroy();
    }

    @Test
    public void sameTaskHopNeverReadsBackgrounded() {
        // The 2466 failure window: A (join screen) paused, B (Splash taking the
        // VIEW intent) created but not yet started/resumed, the deep-link open
        // already running inside B.onCreate. A only stops AFTER B resumes.
        JukeboxForeground.init(app());

        ActivityController<Activity> join = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(JukeboxForeground.isAppInForeground());

        join.pause(); // A.onPause fires before B.onCreate
        ActivityController<Activity> splash = Robolectric.buildActivity(Activity.class).create();
        assertTrue("mid-hop (old paused, new in onCreate): still foreground",
                JukeboxForeground.isAppInForeground());

        splash.start(); // B.onStart
        assertTrue(JukeboxForeground.isAppInForeground());

        splash.resume(); // B.onResume
        join.stop(); // A.onStop only after B resumed
        assertTrue("the new activity keeps the app foregrounded", JukeboxForeground.isAppInForeground());

        splash.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
    }

    @Test
    public void secondStartedActivityKeepsForegroundDuringTheTransition() {
        JukeboxForeground.init(app());

        ActivityController<Activity> first = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> second = Robolectric.buildActivity(Activity.class).setup();

        assertTrue(JukeboxForeground.isAppInForeground());

        first.pause().stop().destroy();
        assertTrue("the second activity still keeps the app foregrounded",
                JukeboxForeground.isAppInForeground());

        second.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
    }

    @Test
    public void resumedActivityOutlivesItsPauseUntilItStops() {
        JukeboxForeground.init(app());

        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();
        ac.pause();
        assertTrue("paused activity: resumed slot kept until its stop", JukeboxForeground.isAppInForeground());

        ac.stop();
        assertFalse(JukeboxForeground.isAppInForeground());

        // A new hop: A resumes over stopped activities, then A pauses (B coming)
        // — even with the started counter somehow zero, the resumed slot holds.
        ac.resume();
        ac.pause();
        assertTrue(JukeboxForeground.isAppInForeground());
        ac.stop();
        assertFalse(JukeboxForeground.isAppInForeground());

        ac.destroy();
    }

    @Test
    public void testOverrideBeatsTheTracker() {
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();

        JukeboxForeground.setTestOverride(false);
        assertFalse(JukeboxForeground.isAppInForeground());
        assertFalse(JukeboxForeground.isCallerInForeground(ac.get()));
        JukeboxForeground.setTestOverride(true);
        assertTrue(JukeboxForeground.isAppInForeground());
        assertTrue(JukeboxForeground.isCallerInForeground(null));
        JukeboxForeground.setTestOverride(null);
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause().stop().destroy();
    }

    @Test
    public void beforeAnyLifecycleEventFallsBackToUpstreamCheck() {
        // Fresh process: no activity started yet. Robolectric's importance query
        // defaults to foreground (see the old comment in JukeboxForeground), so
        // the upstream fallback must allow — production fills the answer as soon
        // as the first activity starts.
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
    public void extraStopNeverDrivesTheStartedCounterNegative() {
        // An unmatched stop (activity recreation edge case, multi-window) must
        // not push the counter below zero — a stuck negative/zero state would
        // defer room resumes forever after the next start.
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();
        assertTrue("a started activity of the app means foreground", JukeboxForeground.isAppInForeground());

        ac.pause();
        assertTrue(JukeboxForeground.isAppInForeground());
        ac.stop(); // paired stop: started set emptied
        assertFalse(JukeboxForeground.isAppInForeground());
        ac.stop(); // spurious duplicate stop: idempotent removal, no corruption
        assertFalse(JukeboxForeground.isAppInForeground());

        ac.start();
        assertTrue("the start after a duplicate stop must report foreground again",
                JukeboxForeground.isAppInForeground());

        ac.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
    }

    @Test
    public void destroyingAStartedActivityDropsItFromTheTracker() {
        // The TrackerDebugTest sequence from the 2466 debugging session: the
        // activity's paired stop was spurious (never fired), so it stayed in the
        // STARTED set; its later start re-added it and its destroy must drop it
        // — a destroyed started activity keeping the app "foreground" would take
        // over the TV from HOME forever.
        JukeboxForeground.init(app());
        ActivityController<Activity> ac = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause().stop();
        assertFalse(JukeboxForeground.isAppInForeground());

        ac.start(); // re-started: foreground again, resumed slot still cleared
        assertTrue(JukeboxForeground.isAppInForeground());

        ac.pause();
        ac.stop(); // spurious: Robolectric skips the onStop, set keeps the entry
        ac.destroy(); // destroy must drop the started entry (and the resumed slot)
        assertFalse("the destroyed started activity must not keep the app foreground",
                JukeboxForeground.isAppInForeground());
    }

    @Test
    public void callerAwareCheckTreatsOwnResumedActivityAsForeground() {
        JukeboxForeground.init(app());

        // No activity at all: a non-activity caller falls back to the global
        // answer (backgrounded after lifecycles were seen).
        ActivityController<Activity> gone = Robolectric.buildActivity(Activity.class).setup();
        gone.pause().stop().destroy();
        assertFalse(JukeboxForeground.isAppInForeground());
        assertFalse(JukeboxForeground.isCallerInForeground(app()));

        // The join screen (an own activity) is resumed and asks to launch: even
        // mid-transition (paused while the next hop comes up) this is a
        // foreground launch.
        ActivityController<Activity> join = Robolectric.buildActivity(Activity.class).setup();
        join.pause(); // same-task hop in progress
        assertTrue(JukeboxForeground.isCallerInForeground(join.get()));

        // Once everything stopped again, neither a dead caller nor the stopped
        // join activity may claim foreground.
        join.stop();
        ActivityController<Activity> dead = Robolectric.buildActivity(Activity.class).setup();
        dead.pause().stop().destroy();
        assertFalse(JukeboxForeground.isCallerInForeground(dead.get()));
        assertFalse(JukeboxForeground.isCallerInForeground(join.get()));

        join.destroy();
    }
}
