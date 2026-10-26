package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build 2466, issue (3): every app-initiated activity launch in the jukebox
 * flavor funnels through {@link JukeboxLaunch#checkLaunch(String, String)} and
 * is logged under the {@code JukeboxLaunch} tag; other flavors (and the
 * un-initialized state) keep the old always-allow behavior.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class JukeboxLaunchTest {
    @Before
    public void setUp() {
        JukeboxLaunch.resetForTests();
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        JukeboxLaunch.resetForTests();
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxLaunch.TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void uninitializedStateAllowsEverythingWithoutLogging() {
        assertTrue(JukeboxLaunch.checkLaunch("PlaybackActivity", "ViewManager.startView"));
        assertFalse("no JukeboxLaunch logs outside the jukebox flavor", logged("start"));
    }

    @Test
    public void nonJukeboxFlavorAllowsEverythingWithoutLogging() {
        JukeboxLaunch.init(false, null);
        assertTrue(JukeboxLaunch.checkLaunch("PlaybackActivity", "ViewManager.startView"));
        assertFalse(logged("start"));
    }

    @Test
    public void jukeboxInBackgroundBlocksAndLogs() {
        JukeboxLaunch.init(true, () -> false);

        assertFalse("a launch while backgrounded must be blocked",
                JukeboxLaunch.checkLaunch("PlaybackActivity", "ViewManager.startView"));
        assertTrue("the block must be verifiable in the logs",
                logged("blocked PlaybackActivity (ViewManager.startView)"));
    }

    @Test
    public void jukeboxInForegroundAllowsAndLogs() {
        JukeboxLaunch.init(true, () -> true);

        assertTrue(JukeboxLaunch.checkLaunch("SplashActivity", "ViewManager.startDefaultView"));
        assertTrue("the start must be verifiable in the logs",
                logged("start SplashActivity (ViewManager.startDefaultView)"));
    }

    @Test
    public void noteLaunchOnlyLogsInTheJukeboxFlavor() {
        JukeboxLaunch.noteLaunch("BrowseActivity", "join screen browse button");
        assertFalse(logged("BrowseActivity"));

        JukeboxLaunch.init(true, () -> true);
        JukeboxLaunch.noteLaunch("BrowseActivity", "join screen browse button");
        assertTrue(logged("start BrowseActivity (join screen browse button)"));
    }

    @Test
    public void noteIntentLogsActionDataAndIsnullSafe() {
        JukeboxLaunch.noteIntent(null, "SplashActivity.onCreate", null); // must not throw

        JukeboxLaunch.init(true, () -> true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/dQw4w9WgXcQ"));
        JukeboxLaunch.noteIntent(activity, "PlaybackActivity.onNewIntent", intent);
        assertTrue(logged("PlaybackActivity.onNewIntent: action=android.intent.action.VIEW"));
        assertTrue(logged("https://youtu.be/dQw4w9WgXcQ"));

        JukeboxLaunch.noteIntent(activity, "PlaybackActivity.onCreate", new Intent(Intent.ACTION_MAIN));
        assertTrue(logged("PlaybackActivity.onCreate: action=android.intent.action.MAIN"));

        // getReferrer() lives on the Activity (API 22+), not the Intent: a null
        // activity must still log (referrer n/a) instead of crashing.
        JukeboxLaunch.noteIntent(null, "SplashActivity.onNewIntent", new Intent(Intent.ACTION_MAIN));
        assertTrue(logged("SplashActivity.onNewIntent: action=android.intent.action.MAIN"));
        assertTrue(logged("referrer=n/a"));
    }

    @Test
    public void resetRestoresThePristineState() {
        JukeboxLaunch.init(true, () -> false);
        JukeboxLaunch.resetForTests();
        assertTrue("after reset the gate must allow (non-jukebox default)",
                JukeboxLaunch.checkLaunch("PlaybackActivity", "any"));
    }
}
