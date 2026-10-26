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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Cold launch of the join screen.
 *
 * The room video opens exactly once when the room is already playing, and
 * exactly once when the first resume tick sees no song and a state snapshot
 * then arrives. That idle-to-playing transition moves default focus off
 * Browse YouTube. A later resume or state, while the room stays playing,
 * does not move focus again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxJoinColdLaunchTest {
    /** Valid 11-character id. The state title is the Nina 2481 song. */
    static final String V1 = "TNWhiskey01";
    static final String TITLE = "Tennessee Whiskey";

    FakeTransport t;
    JukeboxRoom room;
    PlaybackPresenter presenter;
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
        // The update checker is not part of these tests. Drop its lifecycle hook
        // so a cold launch does not start a second activity.
        JukeboxUpdate.resetForTests();

        ViewManager.instance(app()).register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);

        t = new FakeTransport();
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new FakeHttp(), new FakeScheduler(), new MapStore(), listener, "");
        }, Runnable::run);
        setSingleton(JukeboxRoom.class, "sInstance", room);

        presenter = PlaybackPresenter.instance(app());
        JukeboxRoomController wired = presenter.getController(JukeboxRoomController.class);
        // See JukeboxExactlyOnceOpenTest: constructor onInit runs before the
        // application context is visible. Attach once the presenter is published.
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
    public void coldLaunchWithRoomPlayingOpensTheRoomVideoExactlyOnce() {
        adoptPlayWhileBackgrounded();
        JukeboxForeground.setTestOverride(true);

        launchJoin();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(V1, presenter.getVideo().videoId);
        assertEquals("cold launch from the join screen with the room playing opens the room video exactly once",
                Arrays.asList(PlaybackActivity.class.getName()), startedActivities());
    }

    @Test
    public void coldLaunchThenStateArrivesOpensThePlayerExactlyOnce() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull("the first tick runs before any song is known", room.getCurrentVideoId());
        assertEquals("the idle first tick must not open a player",
                Arrays.asList(), startedActivities());

        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(V1, room.getCurrentVideoId());
        assertEquals(V1, presenter.getVideo().videoId);
        assertEquals("a state snapshot that arrives after the idle tick opens the room video exactly once",
                Arrays.asList(PlaybackActivity.class.getName()), startedActivities());
    }

    @Test
    public void stateArrivalMovesDefaultFocusOffBrowse() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertEquals("Browse YouTube", browse.getText().toString());
        assertFalse("before any song, the QR join action is not focusable", qr.isFocusable());
        assertTrue(browse.hasFocus());

        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the idle-to-playing transition makes the join action focusable",
                qr.isFocusable());
        assertTrue("the idle-to-playing transition moves default focus off Browse YouTube",
                qr.hasFocus());
        assertFalse(browse.hasFocus());
    }

    @Test
    public void onResumeWhileAlreadyPlayingDoesNotMoveFocus() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();
        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertTrue("the room is playing, so the join action can take focus", qr.isFocusable());
        assertTrue(browse.requestFocus() && browse.hasFocus());

        ac.pause().resume();

        assertTrue("a later onResume, while the room stays playing, leaves focus on Browse",
                browse.hasFocus());
        assertFalse(qr.hasFocus());
    }

    @Test
    public void laterStateWhileStillPlayingDoesNotMoveFocus() {
        JukeboxForeground.setTestOverride(true);
        launchJoin();
        layoutLikeTheTv();
        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertTrue(qr.isFocusable());
        assertTrue(browse.requestFocus() && browse.hasFocus());

        t.msg(state("s1", V1, TITLE));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("a later state, while the room stays playing, does not move focus",
                browse.hasFocus());
        assertFalse(qr.hasFocus());
    }

    private void adoptPlayWhileBackgrounded() {
        JukeboxForeground.setTestOverride(false);
        t.msg(play("s1", V1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(V1, room.getCurrentVideoId());
        assertNull(presenter.getVideo());
        drainStarted();
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
