package net.tfour.jukebox;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity;

import net.tfour.jukebox.room.JukeboxRoom;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JukeboxJoinActivity is the package's launcher activity, so AppUtil.createAppIntent(url)
 * view intents (ATV channels, recommendations, global search) resolve to it. Those must
 * be forwarded unchanged (action, data, extras, flags) to SplashActivity, while plain
 * MAIN launcher launches must stay on the join screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxJoinActivityForwardTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Before
    public void setUp() throws Exception {
        // JukeboxRoom is a static singleton: reset it so room-start assertions are
        // deterministic regardless of test order.
        resetJukeboxRoom();
    }

    private static void resetJukeboxRoom() throws Exception {
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static Intent viewIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_URL));
        intent.setPackage(RuntimeEnvironment.getApplication().getPackageName());
        intent.putExtra("jukebox.test.extra", "K7QPZX");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private static void assertForwardedUnchanged(Intent expected, Intent started) {
        assertNotNull("view intent must be forwarded to SplashActivity", started);
        assertEquals(new ComponentName(RuntimeEnvironment.getApplication(), SplashActivity.class),
                started.getComponent());
        assertEquals(expected.getAction(), started.getAction());
        assertEquals(expected.getData(), started.getData());
        assertEquals(expected.getFlags(), started.getFlags());
        assertEquals(expected.getStringExtra("jukebox.test.extra"),
                started.getStringExtra("jukebox.test.extra"));
    }

    @Test
    public void createWithViewIntentForwardsToSplashActivityAndFinishes() throws Exception {
        Intent incoming = viewIntent();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class, incoming).setup();
        JukeboxJoinActivity a = ac.get();

        assertForwardedUnchanged(incoming,
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertTrue("join screen must finish after forwarding", a.isFinishing());

        // No room traffic: the forwarded path must not start the jukebox room
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        assertNull("forwarded launch must not start JukeboxRoom", field.get(null));

        ac.pause().stop().destroy();
    }

    @Test
    public void newIntentWithViewIntentForwardsToSplashActivityAndFinishes() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        JukeboxRoom.instance(a).stop(); // no background room traffic in tests

        Intent incoming = viewIntent();
        a.onNewIntent(incoming); // same package: protected override is callable

        assertForwardedUnchanged(incoming,
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertTrue("join screen must finish after forwarding", a.isFinishing());

        ac.pause().stop().destroy();
    }

    @Test
    public void mainLauncherLaunchStaysOnJoinScreen() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        JukeboxRoom.instance(a).stop(); // no background room traffic in tests

        Intent incoming = new Intent(Intent.ACTION_MAIN);
        incoming.setPackage(RuntimeEnvironment.getApplication().getPackageName());
        a.onNewIntent(incoming);

        assertNull("plain MAIN launch must not be forwarded",
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertFalse("plain MAIN launch must keep the join screen", a.isFinishing());

        ac.pause().stop().destroy();
    }
}
