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
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Join screen default focus (Tim): an idle room focuses BROWSE YOUTUBE
 * (R.id.jukebox_browse) on create and again on resume. The room code
 * (R.id.jukebox_code) is not focusable and never gets default focus.
 * {@link JukeboxJoinActivity#defaultFocusId(boolean)} is Browse when idle
 * and the QR join action when a room song is playing.
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
    public void idleRoomFocusesBrowseOnCreateNotRoomCode() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);
        assertFalse("the room code is not a focus target", code.isFocusable());
        assertFalse("the room code is not focusable in touch mode", code.isFocusableInTouchMode());
        assertFalse("the room code is not the layout default", code.isFocusedByDefault());
        assertTrue("Browse is the layout's default focus", browse.isFocusedByDefault());
        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("idle create must focus BROWSE YOUTUBE, not " + name(focused)
                        + " (codeFocused=" + code.isFocused()
                        + " browseFocused=" + browse.isFocused() + ")",
                browse, focused);
        assertFalse("the room code must not own the default focus", code.isFocused());
        ac.pause().stop().destroy();
    }

    @Test
    public void idleRoomResumeLeavesBrowseFocusedAndRoomCodeCannotTakeFocus() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);

        assertFalse("the room code must not be focusable", code.isFocusable());
        assertFalse("the room code must not accept a focus request", code.requestFocus());
        assertFalse(code.isFocused());
        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("idle resume must focus BROWSE YOUTUBE, not " + name(focused)
                        + " (codeFocused=" + code.isFocused()
                        + " browseFocused=" + browse.isFocused() + ")",
                browse, focused);
        assertFalse("the room code must not take focus across idle resume", code.isFocused());
        ac.pause().stop().destroy();
    }

    @Test
    public void defaultFocusIdIsBrowseWhenIdleAndQrWhenPlaying() throws Exception {
        Method decide = JukeboxJoinActivity.class.getDeclaredMethod(
                "defaultFocusId", boolean.class);
        decide.setAccessible(true);
        assertEquals(Integer.valueOf(R.id.jukebox_browse), decide.invoke(null, false));
        assertEquals(Integer.valueOf(R.id.jukebox_qr), decide.invoke(null, true));

        for (String replaced : new String[] {"updateDefaultFocus", "requestDefaultFocus"}) {
            try {
                JukeboxJoinActivity.class.getDeclaredMethod(replaced);
                fail(replaced + " must be replaced by defaultFocusId");
            } catch (NoSuchMethodException expected) {
                // one pure function decides the focus target
            }
        }
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
