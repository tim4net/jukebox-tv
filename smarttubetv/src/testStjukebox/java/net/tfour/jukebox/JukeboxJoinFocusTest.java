package net.tfour.jukebox;

import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import net.tfour.jukebox.room.JukeboxRoom;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Join screen focus (Nina 2486): uiautomator found BROWSE YOUTUBE
 * (jukebox_browse) focused. The room code is the primary view and must own
 * focus on create and again on re-entry/resume.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-notouch")
public class JukeboxJoinFocusTest {
    @Before
    public void setUp() throws Exception {
        resetRoom();
    }

    @After
    public void tearDown() throws Exception {
        // Stop the room, drain anything it posted, then drop the singleton so a
        // later test's idle() cannot observe a room this screen started.
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().getMainLooper()).idle();
        JukeboxRoom room = JukeboxRoom.instance(
                org.robolectric.RuntimeEnvironment.getApplication());
        room.stop();
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().getMainLooper()).idle();
        resetRoom();
    }

    private static void resetRoom() throws Exception {
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void roomCodeIsFocusedOnCreateNotBrowse() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);
        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("create must focus the room code, not " + name(focused)
                        + " (codeFocused=" + code.isFocused()
                        + " browseFocused=" + browse.isFocused() + ")",
                code, focused);
        assertFalse("BROWSE YOUTUBE must not own the default focus", browse.isFocused());
        ac.pause().stop().destroy();
    }

    @Test
    public void roomCodeWinsAgainOnResumeEvenIfBrowseTookFocus() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);

        // The user (or the old layout) left Browse focused. Leaving and
        // coming back — pause/resume, the re-entry path — must restore the code.
        assertTrue("browse must be able to take focus before the resume check",
                browse.requestFocus());
        assertTrue(browse.isFocused());
        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("resume must focus the room code, not " + name(focused)
                        + " (codeFocused=" + code.isFocused()
                        + " browseFocused=" + browse.isFocused() + ")",
                code, focused);
        assertFalse(browse.isFocused());
        ac.pause().stop().destroy();
    }

    private static String name(View view) {
        if (view == null) {
            return "nothing";
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        return view.getClass().getSimpleName();
    }
}
