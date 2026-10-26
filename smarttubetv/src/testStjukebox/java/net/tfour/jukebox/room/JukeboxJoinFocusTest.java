package net.tfour.jukebox.room;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import net.tfour.jukebox.JukeboxApp;
import net.tfour.jukebox.JukeboxJoinActivity;
import net.tfour.jukebox.update.JukeboxUpdate;

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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * While the room is playing, the join screen's default focus is the join
 * action (the QR), not Browse YouTube. OK on the QR opens the room video.
 * Focus moves only on the idle-to-playing transition, and a playing-to-idle
 * transition hands it back to Browse.
 *
 * Driven through room messages and view events. No reflection into room
 * fields and no log-text assertions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class JukeboxJoinFocusTest {
    static final String V1 = "dQw4w9WgXcQ";
    static final String TITLE = "Never Gonna Give You Up";

    FakeTransport t;
    JukeboxRoom room;
    ActivityController<JukeboxJoinActivity> ac;
    final List<HandlerThread> threads = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
        JukeboxLaunch.resetForTests();
        JukeboxUpdate.resetForTests();
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
        resetSingleton(JukeboxRoom.class, "sInstance");

        JukeboxApp.init(app());
        JukeboxUpdate.resetForTests();
        ViewManager.instance(app()).register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);

        t = new FakeTransport();
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new FakeHttp(), new FakeScheduler(), new MapStore(), listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        PlaybackPresenter presenter = PlaybackPresenter.instance(app());
        JukeboxRoomController wired = presenter.getController(JukeboxRoomController.class);
        wired.onInit();
        room.start();
        t.open();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        drainStarted();
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
        JukeboxLaunch.resetForTests();
        JukeboxUpdate.resetForTests();
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
        if (ac != null) {
            ac.pause().stop().destroy();
            ac = null;
        }
        if (room != null) {
            room.stop();
        }
        for (HandlerThread thread : threads) {
            thread.quitSafely();
        }
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    @Test
    public void roomPlayingPutsDefaultFocusOnTheQrJoinActionNotBrowse() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertFalse("before any song, the QR join action is not focusable", qr.isFocusable());
        assertTrue(browse.hasFocus());

        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the QR join action must be focusable while the room plays", qr.isFocusable());
        assertTrue("the QR join action must take the default focus while the room plays",
                qr.hasFocus());
        assertFalse("Browse must not hold the default focus while the room plays",
                browse.hasFocus());
    }

    @Test
    public void okOnTheQrOpensThePlayerAtTheRoomVideo() {
        // Song known, but the join screen came up while the app looked
        // backgrounded, so the automatic resume has not opened anything.
        // OK on the QR is what opens the room video.
        JukeboxForeground.setTestOverride(false);
        t.msg(play("s1", V1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(V1, room.getCurrentVideoId());
        drainStarted();

        launchJoin();
        layoutLikeTheTv();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("the backgrounded join screen must not open the player",
                PlaybackPresenter.instance(app()).getVideo());
        drainStarted();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        assertTrue("the QR join action is focusable while the room plays", qr.isFocusable());
        JukeboxForeground.setTestOverride(true);
        assertTrue(qr.performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(V1, PlaybackPresenter.instance(app()).getVideo().videoId);
        assertEquals("OK on the QR opens the room video exactly once",
                java.util.Arrays.asList(PlaybackActivity.class.getName()), startedActivities());
    }

    @Test
    public void idleRoomKeepsBrowseReachableAsTheDefaultAction() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertFalse("with no room song the QR must not steal focus", qr.isFocusable());
        assertTrue("Browse must stay reachable/focusable as the default action",
                browse.isFocusable());
        assertTrue("focus must be grantable to Browse (default action)",
                browse.requestFocus() && browse.hasFocus());
    }

    @Test
    public void roomGoingIdleHandsDefaultFocusBackToBrowse() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();
        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertTrue(qr.hasFocus());

        t.msg("{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}");
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse("the QR must not stay focusable when the room goes idle", qr.isFocusable());
        assertTrue("focus must return to Browse explicitly when the room goes idle",
                browse.hasFocus());
    }

    private void launchJoin() {
        ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
    }

    /** Robolectric does not measure the decor. Focus needs a real layout pass. */
    private void layoutLikeTheTv() {
        View root = ac.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }

    private static String play(String songId, String videoId) {
        return "{\"t\":\"play\",\"song\":{\"songId\":\"" + songId + "\",\"videoId\":\"" + videoId
                + "\",\"title\":\"" + TITLE + "\",\"durationMs\":null}}";
    }

    private static String state(String songId, String videoId, String title) {
        return "{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"songId\":\"" + songId
                + "\",\"videoId\":\"" + videoId + "\",\"title\":\"" + title
                + "\"},\"queue\":[],\"listeners\":1}";
    }

    private static List<String> startedActivities() {
        List<String> names = new ArrayList<>();
        Intent intent;
        while ((intent = Shadows.shadowOf(app()).getNextStartedActivity()) != null) {
            names.add(intent.getComponent() != null
                    ? intent.getComponent().getClassName() : String.valueOf(intent.getAction()));
        }
        return names;
    }

    private static void drainStarted() {
        startedActivities();
    }

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        setSingleton(clazz, fieldName, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    static class FakeTransport implements JukeboxRoomClient.Transport {
        JukeboxRoomClient.Transport.Callbacks cb;
        boolean up;

        public void connect(String url, JukeboxRoomClient.Transport.Callbacks c) {
            cb = c;
        }

        public boolean send(String text) {
            return up;
        }

        public void close() {
            up = false;
        }

        void open() {
            up = true;
            cb.onOpen();
        }

        void msg(String s) {
            cb.onMessage(s);
        }
    }

    static class FakeHttp implements JukeboxRoomClient.Http {
        public String postJson(String url, String body) {
            return "{\"room\":\"room1\",\"code\":\"CODE1\"}";
        }

        public String get(String url) {
            return "{\"room\":{\"id\":\"room1\"},\"nowPlaying\":null}";
        }
    }

    static class FakeScheduler implements JukeboxRoomClient.Scheduler {
        public void schedule(Runnable r, long d) {
        }

        public void cancelAll() {
        }
    }

    static class MapStore implements JukeboxRoomClient.Store {
        final Map<String, String> m = new HashMap<>();

        public String get(String k) {
            return m.get(k);
        }

        public void put(String k, String v) {
            if (v == null) {
                m.remove(k);
            } else {
                m.put(k, v);
            }
        }
    }
}
