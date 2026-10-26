package net.tfour.jukebox;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import net.tfour.jukebox.room.JukeboxRoom;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher (home icon, {@code am start}, and what
 * {@code monkey -p net.tfour.jukebox -c android.intent.category.LAUNCHER 1} resolves)
 * must open ONLY JukeboxJoinActivity — never SplashActivity/BrowseActivity
 * (issue tim4net/jukebox-tv#2). Robolectric resolves intents against the merged
 * manifest of the stjukebox variant, so this checks the same thing the device does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxLauncherTest {
    private static Intent mainLauncherIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(RuntimeEnvironment.getApplication().getPackageName());
        return intent;
    }

    @Test
    public void launcherResolvesToJoinActivityOnly() {
        List<ResolveInfo> ris = RuntimeEnvironment.getApplication().getPackageManager()
                .queryIntentActivities(mainLauncherIntent(), 0);
        assertNotNull(ris);
        assertEquals("exactly one MAIN/LAUNCHER activity expected but got: " + ris, 1, ris.size());
        assertEquals(JukeboxJoinActivity.class.getName(),
                ris.get(0).activityInfo.targetActivity != null
                        ? ris.get(0).activityInfo.targetActivity
                        : ris.get(0).activityInfo.name);
    }

    @Test
    public void launchIntentForPackageOpensJoinScreen() {
        Intent launch = RuntimeEnvironment.getApplication().getPackageManager()
                .getLaunchIntentForPackage(RuntimeEnvironment.getApplication().getPackageName());
        assertNotNull("package must have a launch intent", launch);
        assertNotNull("launch intent must target a concrete activity", launch.getComponent());
        assertEquals(JukeboxJoinActivity.class.getName(), launch.getComponent().getClassName());
    }

    @Test
    public void joinActivityIsSingleTaskSoRelaunchesReuseIt() throws Exception {
        Class<?> clazz = Class.forName("net.tfour.jukebox.JukeboxJoinActivity");
        ActivityInfo info = RuntimeEnvironment.getApplication().getPackageManager()
                .getActivityInfo(new android.content.ComponentName(
                        RuntimeEnvironment.getApplication(), clazz), 0);
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode);
    }

    @Test
    public void relaunchKeepsRoomAttached() throws Exception {
        resetJukeboxRoom();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        assertTrue("join screen must keep the room active", JukeboxRoom.instance(a).isActive());

        // Plain MAIN relaunch (singleTask: delivered as onNewIntent to the same instance)
        Intent incoming = new Intent(Intent.ACTION_MAIN);
        incoming.setPackage(RuntimeEnvironment.getApplication().getPackageName());
        a.onNewIntent(incoming);

        assertNull("plain relaunch must not be forwarded",
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertFalse(a.isFinishing());
        assertTrue("re-entry must re-attach to (or heal) the room", JukeboxRoom.instance(a).isActive());

        JukeboxRoom.instance(a).stop(); // no background room traffic after the test
        resetJukeboxRoom();
        ac.pause().stop().destroy();
    }

    private static void resetJukeboxRoom() throws Exception {
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
