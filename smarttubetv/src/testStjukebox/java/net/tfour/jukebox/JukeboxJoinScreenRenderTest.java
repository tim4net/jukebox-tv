package net.tfour.jukebox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

/**
 * Renders the TV join screen with fixed values (no network/emulator) and writes a PNG
 * proof to build/jukebox-proof/join-screen.png. Uses Robolectric native graphics.
 *
 * The attract restyle must keep the live QR and the room code inside the tall neon
 * panel painted on the 1920x1080 art. The overlay lives in a 16:9 art box
 * (jukebox_attract_box) positioned with percent guidelines, so the same panel
 * fractions hold at 1080p and at 4K, not just at one density.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class JukeboxJoinScreenRenderTest {
    static final String JOIN_URL = "https://jukebox.tfour.net/r/abcdefghijklmnopqrstuv";

    /** Neon panel inner box on the 1920x1080 art (inside the ~6px painted stroke). */
    private static final int PANEL_LEFT_PX = 1582, PANEL_RIGHT_PX = 1865;
    private static final int PANEL_TOP_PX = 90, PANEL_BOTTOM_PX = 990;
    /** Slack for guideline rounding and the painted stroke width. */
    private static final int PANEL_TOL_PX = 8;
    /** The same inner box as fractions of the 16:9 art (inner 0.824..0.971 x, 0.083..0.917 y). */
    private static final double PANEL_LEFT_F = 0.824, PANEL_RIGHT_F = 0.971;
    private static final double PANEL_TOP_F = 0.083, PANEL_BOTTOM_F = 0.917;

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
        assertNotNull("the attract art must back the join screen",
                ((ImageView) a.findViewById(R.id.jukebox_attract)).getDrawable());

        View root = a.getWindow().getDecorView();
        int w = 1920, h = 1080;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        // The overlay is locked to the art: the box fills the 16:9 window, so box-space
        // coordinates are the 1920x1080 art-space pixels the panel was measured on.
        View box = assertArtBoxIsFullScreen(a, w, h);
        // jukebox_qr and jukebox_code are direct children of the art box, so their
        // left/top are already art-space pixels.
        assertInsidePanelPixels("jukebox_qr", a.findViewById(R.id.jukebox_qr));
        assertInsidePanelPixels("jukebox_code", a.findViewById(R.id.jukebox_code));

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
    public void qrAndCodeStayInsideThePaintedPanelAt4K() throws Exception {
        // Same fractions of the art at 3840x2160: percent guidelines keep the live
        // overlay on the painted outline instead of drifting with density-scaled dp.
        ActivityController<JukeboxJoinActivity> ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        net.tfour.jukebox.room.JukeboxRoom.instance(a).stop(); // no background room traffic in tests
        a.bind("K7QPZX", JOIN_URL, "Connected", "Never Gonna Give You Up");

        View root = a.getWindow().getDecorView();
        int w = 3840, h = 2160;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        View box = assertArtBoxIsFullScreen(a, w, h);
        assertInsidePanelFractions(box, "jukebox_qr", a.findViewById(R.id.jukebox_qr));
        assertInsidePanelFractions(box, "jukebox_code", a.findViewById(R.id.jukebox_code));
        ac.pause().stop().destroy();
    }

    /** The 16:9 art box must fill a 16:9 window so art-space and window-space agree. */
    private static View assertArtBoxIsFullScreen(JukeboxJoinActivity a, int w, int h) {
        View box = a.findViewById(R.id.jukebox_attract_box);
        assertNotNull("the join screen must use a 16:9 attract art box", box);
        assertNear("art box width at " + w + "x" + h + " (16:9 window)", w, box.getWidth(), 4);
        assertNear("art box height at " + w + "x" + h + " (16:9 window)", h, box.getHeight(), 4);
        return box;
    }

    private static void assertInsidePanelPixels(String name, View v) {
        assertTrue(name + " left " + v.getLeft() + "px must sit inside the neon panel inner box "
                + "[" + PANEL_LEFT_PX + "," + PANEL_RIGHT_PX + "] (±" + PANEL_TOL_PX + ")",
                v.getLeft() >= PANEL_LEFT_PX - PANEL_TOL_PX);
        assertTrue(name + " right " + v.getRight() + "px must sit inside the neon panel inner box "
                + "[" + PANEL_LEFT_PX + "," + PANEL_RIGHT_PX + "] (±" + PANEL_TOL_PX + ")",
                v.getRight() <= PANEL_RIGHT_PX + PANEL_TOL_PX);
        assertTrue(name + " top " + v.getTop() + "px must sit inside the neon panel inner box "
                + "[" + PANEL_TOP_PX + "," + PANEL_BOTTOM_PX + "] (±" + PANEL_TOL_PX + ")",
                v.getTop() >= PANEL_TOP_PX - PANEL_TOL_PX);
        assertTrue(name + " bottom " + v.getBottom() + "px must sit inside the neon panel inner box "
                + "[" + PANEL_TOP_PX + "," + PANEL_BOTTOM_PX + "] (±" + PANEL_TOL_PX + ")",
                v.getBottom() <= PANEL_BOTTOM_PX + PANEL_TOL_PX);
    }

    private static void assertInsidePanelFractions(View box, String name, View v) {
        double boxW = box.getWidth();
        double boxH = box.getHeight();
        double tolX = PANEL_TOL_PX / 1920.0;
        double tolY = PANEL_TOL_PX / 1080.0;
        assertTrue(name + " left fraction " + (v.getLeft() / boxW) + " must stay in the panel "
                + "[" + PANEL_LEFT_F + "," + PANEL_RIGHT_F + "] of the art",
                v.getLeft() / boxW >= PANEL_LEFT_F - tolX);
        assertTrue(name + " right fraction " + (v.getRight() / boxW) + " must stay in the panel "
                + "[" + PANEL_LEFT_F + "," + PANEL_RIGHT_F + "] of the art",
                v.getRight() / boxW <= PANEL_RIGHT_F + tolX);
        assertTrue(name + " top fraction " + (v.getTop() / boxH) + " must stay in the panel "
                + "[" + PANEL_TOP_F + "," + PANEL_BOTTOM_F + "] of the art",
                v.getTop() / boxH >= PANEL_TOP_F - tolY);
        assertTrue(name + " bottom fraction " + (v.getBottom() / boxH) + " must stay in the panel "
                + "[" + PANEL_TOP_F + "," + PANEL_BOTTOM_F + "] of the art",
                v.getBottom() / boxH <= PANEL_BOTTOM_F + tolY);
    }

    private static void assertNear(String message, long expected, long actual, long tol) {
        assertTrue(message + ": expected ~" + expected + "±" + tol + ", was " + actual,
                Math.abs(expected - actual) <= tol);
    }

    @Test
    public void qrBitmapIsSquareAndHasDarkAndLightModules() {
        Bitmap q = JukeboxJoinActivity.qrBitmap(JOIN_URL, 256);
        assertNotNull(q);
        assertEquals(256, q.getWidth());
        assertEquals(256, q.getHeight());
        assertNull(JukeboxJoinActivity.qrBitmap(null, 256));
    }
}
