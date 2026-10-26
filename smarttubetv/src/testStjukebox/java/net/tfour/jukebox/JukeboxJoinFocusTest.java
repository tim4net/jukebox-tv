package net.tfour.jukebox;

import android.app.Activity;
import android.view.View;
import android.widget.Button;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.R;

import net.tfour.jukebox.room.JukeboxForeground;
import net.tfour.jukebox.room.JukeboxRoom;
import net.tfour.jukebox.room.JukeboxRoomClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #8 (2477 bug 3), focus half: while the room is playing, the join
 * screen's default focus must be the join action (the QR), not the layout's
 * Browse button — a DPAD_CENTER press on the stuck join screen hit Browse and
 * loaded a non-room video. OK on the QR must ask the controller to open the
 * player at the room video. When the room goes idle the layout default
 * (Browse) must be restored explicitly, not left to chance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxJoinFocusTest {
    static final String V1 = "dQw4w9WgXcQ";

    ActivityController<JukeboxJoinActivity> ac;
    JukeboxJoinActivity a;
    View qr;
    Button browse;
    final List<android.os.HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        ShadowLog.clear();
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
        resetSingleton(JukeboxRoom.class, "sInstance");

        // Production wiring (like JukeboxJoinScreenOpenTest): lifecycle
        // tracker + gated ViewManager so the OK-on-QR resume retry can start
        // the player instead of crashing on unregistered views.
        JukeboxApp.init(RuntimeEnvironment.getApplication());
        ViewManager viewManager = ViewManager.instance(RuntimeEnvironment.getApplication());
        viewManager.register(PlaybackView.class, Activity.class);
        viewManager.setRoot(Activity.class);

        ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        a = ac.get();
        qr = a.findViewById(R.id.jukebox_qr);
        browse = (Button) a.findViewById(R.id.jukebox_browse);
        JukeboxRoom.instance(a).stop(); // no background room traffic in tests
        layoutLikeTheTv(); // Robolectric never measures/lays out: focus needs sizes
    }

    /** The same 1920x1080 measure+layout pass the render proof test uses. */
    private void layoutLikeTheTv() {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacksAndMessages(null);
        if (JukeboxRoom.instance(a) != null) {
            JukeboxRoom.instance(a).stop();
        }
        JukeboxForeground.resetForTests();
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
        ac.pause().stop().destroy();
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    /**
     * Fakes the room's active/current-song state via reflection (no network):
     * the join screen only reads isActive()/getCurrentVideoId().
     */
    private void setRoomPlaying(boolean playing, String videoId) throws Exception {
        JukeboxRoom room = JukeboxRoom.instance(a);
        Field activeField = JukeboxRoom.class.getDeclaredField("mActive");
        activeField.setAccessible(true);
        activeField.setBoolean(room, playing);
        Field clientField = JukeboxRoom.class.getDeclaredField("mClient");
        clientField.setAccessible(true);
        if (playing) {
            // No-op fakes (never started): the join screen only reads
            // isActive()/getCurrentVideoId() off the room.
            JukeboxRoomClient client = new JukeboxRoomClient(
                    "https://jukebox.tfour.net",
                    new JukeboxRoomClient.Transport() {
                        @Override public void connect(String url, Callbacks cb) { }
                        @Override public boolean send(String text) { return false; }
                        @Override public void close() { }
                    },
                    new JukeboxRoomClient.Http() {
                        @Override public String postJson(String url, String body) { return null; }
                        @Override public String get(String url) { return null; }
                    },
                    new JukeboxRoomClient.Scheduler() {
                        @Override public void schedule(Runnable r, long delayMs) { }
                        @Override public void cancelAll() { }
                    },
                    new JukeboxRoomClient.Store() {
                        @Override public String get(String key) { return null; }
                        @Override public void put(String key, String value) { }
                    },
                    new JukeboxRoomClient.Listener() {
                        @Override public void onRoomReady(String room, String code, String joinUrl) { }
                        @Override public void onPlay(String songId, String videoId, String title) { }
                        @Override public void onStatus(String status) { }
                        @Override public void onState(String nowPlayingTitle, int listeners) { }
                    },
                    "");
            Field videoField = JukeboxRoomClient.class.getDeclaredField("mCurrentVideoId");
            videoField.setAccessible(true);
            videoField.set(client, videoId);
            Field runningField = JukeboxRoomClient.class.getDeclaredField("mRunning");
            runningField.setAccessible(true);
            runningField.setBoolean(client, true);
            clientField.set(room, client);
        } else {
            clientField.set(room, null);
        }
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void roomPlayingPutsDefaultFocusOnTheQrJoinActionNotBrowse() throws Exception {
        setRoomPlaying(true, V1);
        a.bind("K7QPZX", null, "Connected", "Never Gonna Give You Up");

        assertTrue("the QR join action must be focusable while the room plays",
                qr.isFocusable());
        assertTrue("the QR join action must take the default focus while the room plays",
                qr.hasFocus());
        assertFalse("Browse must not hold the default focus while the room plays",
                browse.hasFocus());
    }

    @Test
    public void okOnTheQrOpensThePlayerAtTheRoomVideo() throws Exception {
        setRoomPlaying(true, V1);
        a.bind("K7QPZX", null, "Connected", "Never Gonna Give You Up");

        assertTrue(qr.performClick()); // DPAD_CENTER on the (focused) QR

        assertTrue("the OK action must be logged",
                logged("JukeboxJoin", "join action (OK on QR)"));
        // The bounded resume retry must ask the controller (not BrowseActivity):
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("the resume must be asked with the onOk via tag",
                logged("JukeboxPopup", "(via join:onOk"));
        assertTrue("the room video must be opened by the OK resume",
                logged("JukeboxPopup", "room song active but player idle"));
        assertFalse("OK on the QR must never start BrowseActivity",
                logged("JukeboxLaunch", "start BrowseActivity"));
    }

    @Test
    public void idleRoomKeepsBrowseReachableAsTheDefaultAction() throws Exception {
        setRoomPlaying(false, null);
        a.bind("K7QPZX", null, "Connected", "");

        assertFalse("with no room song the QR must not steal focus", qr.isFocusable());
        assertTrue("Browse must stay reachable/focusable as the default action",
                browse.isFocusable());
        assertTrue("focus must be grantable to Browse (default action)",
                browse.requestFocus() && browse.hasFocus());
    }

    @Test
    public void roomGoingIdleHandsDefaultFocusBackToBrowse() throws Exception {
        setRoomPlaying(true, V1);
        a.bind("K7QPZX", null, "Connected", "Hey");
        assertTrue(qr.hasFocus());

        // The room goes idle (song ended/cleared): the layout's own default
        // must be restored explicitly, not left stranded on the QR.
        setRoomPlaying(false, null);
        a.bind("K7QPZX", null, "Connected", "");

        assertFalse("the QR must not stay focusable when the room goes idle",
                qr.isFocusable());
        assertTrue("focus must return to Browse explicitly when the room goes idle",
                browse.hasFocus());
    }
}