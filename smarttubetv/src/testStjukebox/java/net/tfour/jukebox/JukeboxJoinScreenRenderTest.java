package net.tfour.jukebox;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import net.tfour.jukebox.room.JukeboxRoomClient;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

/**
 * Renders the TV join screen with fixed values (no network/emulator) and writes a PNG
 * proof to build/jukebox-proof/join-screen.png. Uses Robolectric native graphics.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class JukeboxJoinScreenRenderTest {
    static final String JOIN_URL = "https://jukebox.tfour.net/r/abcdefghijklmnopqrstuv";

    @Test
    public void rendersCodeQrStatusAndNowPlaying() throws Exception {
        ActivityController<JukeboxJoinActivity> ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        net.tfour.jukebox.room.JukeboxRoom.instance(a).stop(); // no background room traffic in tests
        a.bind("K7QPZX", JOIN_URL, "Connected", "Never Gonna Give You Up");

        assertEquals("K7QPZX", ((TextView) a.findViewById(R.id.jukebox_code)).getText().toString());
        assertEquals("Connected", ((TextView) a.findViewById(R.id.jukebox_status)).getText().toString());
        assertTrue(((TextView) a.findViewById(R.id.jukebox_now_playing)).getText().toString().contains("Never Gonna"));
        assertTrue(((TextView) a.findViewById(R.id.jukebox_hint)).getText().toString().contains("jukebox.tfour.net"));
        assertNotNull(((ImageView) a.findViewById(R.id.jukebox_qr)).getDrawable());
        assertTrue(a.findViewById(R.id.jukebox_browse).isFocusable());

        View root = a.getWindow().getDecorView();
        int w = 1920, h = 1080;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));

        File dir = new File("build/jukebox-proof");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "join-screen.png"))) {
            assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        ac.pause().stop().destroy();
    }

    @Test
    public void qrBitmapIsSquareAndHasDarkAndLightModules() {
        Bitmap q = JukeboxJoinActivity.qrBitmap(JOIN_URL, 256);
        assertNotNull(q);
        assertEquals(256, q.getWidth());
        assertEquals(256, q.getHeight());
        assertNull(JukeboxJoinActivity.qrBitmap(null, 256));
    }

    @Test
    public void joinButtonsPresentAndBackHiddenOnOwnRoom() throws Exception {
        ActivityController<JukeboxJoinActivity> ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        net.tfour.jukebox.room.JukeboxRoom.instance(a).stop(); // no background room traffic in tests
        assertEquals(View.VISIBLE, a.findViewById(R.id.jukebox_join_existing).getVisibility());
        assertTrue(a.findViewById(R.id.jukebox_join_existing).isFocusable());
        // On the TV's own room there is nowhere to go back to.
        assertEquals(View.GONE, a.findViewById(R.id.jukebox_back_to_own).getVisibility());
        ac.pause().stop().destroy();
    }

    @Test
    public void joinButtonOpensCodeDialog() throws Exception {
        ActivityController<JukeboxJoinActivity> ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        net.tfour.jukebox.room.JukeboxRoom.instance(a).stop();
        a.findViewById(R.id.jukebox_join_existing).performClick();
        assertEquals(1, ShadowAlertDialog.getShownDialogs().size());
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getShownDialogs().get(0);
        assertEquals("Join existing room", Shadows.shadowOf(dialog).getTitle().toString());
        assertTrue(Shadows.shadowOf(dialog).getView() instanceof EditText);
        dialog.dismiss();
        ac.pause().stop().destroy();
    }

    @Test
    public void joinErrorMessages() {
        assertEquals(R.string.jukebox_join_not_found,
                JukeboxJoinActivity.joinErrorMessage(JukeboxRoomClient.JoinError.NOT_FOUND));
        assertEquals(R.string.jukebox_join_too_many,
                JukeboxJoinActivity.joinErrorMessage(JukeboxRoomClient.JoinError.RATE_LIMITED));
        assertEquals(R.string.jukebox_join_invalid,
                JukeboxJoinActivity.joinErrorMessage(JukeboxRoomClient.JoinError.INVALID_CODE));
        assertEquals(R.string.jukebox_join_offline,
                JukeboxJoinActivity.joinErrorMessage(JukeboxRoomClient.JoinError.NETWORK));
    }
}
