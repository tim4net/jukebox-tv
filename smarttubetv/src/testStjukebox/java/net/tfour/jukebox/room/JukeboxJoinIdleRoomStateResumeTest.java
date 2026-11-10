package net.tfour.jukebox.room;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gazebo 2484: the join screen's first resume tick runs before the room has a
 * current song ("resume skipped: idle room") and stops the retry. The socket
 * then delivers room-ready and the first state that names a song. Room-ready is
 * posted first; the song is adopted before either main-thread callback runs, so
 * {@code onRoomUpdated} binds and marks the room playing before
 * {@code onStateChanged} reads that flag. The idle-to-playing resume re-check
 * never runs, the screen still shows the title, and the player stays closed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxJoinIdleRoomStateResumeTest {
    /** The 2484 gazebo song. 11-character id, valid for the room protocol. */
    static final String V1 = "yxfo4Jfpc-c";
    static final String TITLE = "Lady May";

    final List<Runnable> roomStarts = new ArrayList<>();
    final List<HandlerThread> threads = new ArrayList<>();
    StateOnConnectTransport t;
    JukeboxRoom room;
    PlaybackPresenter presenter;
    ActivityController<JukeboxJoinActivity> ac;

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        JukeboxForeground.resetForTests();
        JukeboxForeground.setTestOverride(null);
        JukeboxLaunch.resetForTests();
        JukeboxUpdate.resetForTests();
        ShadowLog.clear();
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
        resetSingleton(JukeboxRoom.class, "sInstance");

        JukeboxApp.init(app());
        JukeboxUpdate.resetForTests();
        // Foreground answer is forced: Robolectric's importance poll is not the
        // device's. The resumed join screen is the case under test.
        JukeboxForeground.setTestOverride(true);

        ViewManager.instance(app()).register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);

        t = new StateOnConnectTransport();
        // The room thread's client.start() stays queued until the join screen's
        // first resume tick has already decided the room is idle. That is the
        // gazebo gap: the tick does not wait for the socket.
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new JukeboxJoinColdLaunchTest.FakeHttp(),
                    new JukeboxJoinColdLaunchTest.FakeScheduler(),
                    new JukeboxJoinColdLaunchTest.MapStore(), listener, "");
        }, roomStarts::add);
        setSingleton(JukeboxRoom.class, "sInstance", room);
        presenter = PlaybackPresenter.instance(app());
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
        roomStarts.clear();
        resetSingleton(JukeboxRoom.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(ViewManager.class, "sInstance");
    }

    @Test
    public void idleResumeThenFirstRoomStateOpensThePlayer() {
        ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("the resume tick runs before the socket has a song", room.getCurrentVideoId());
        assertTrue("the first resume tick must have skipped an idle room",
                logged("JukeboxPopup", "resume skipped: idle room (no current song) (via join:onResume:tick1)"));
        assertNull("an idle room must not open the player",
                Shadows.shadowOf(app()).getNextStartedActivity());
        assertEquals("client start is still waiting behind that tick", 1, roomStarts.size());

        // Room setup posts room-ready, then the socket adopts the current song
        // and posts state. Both listener callbacks run afterwards, room-ready first.
        t.pendingState = state(V1, TITLE);
        roomStarts.remove(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(V1, room.getCurrentVideoId());
        TextView nowPlaying = ac.get().findViewById(R.id.jukebox_now_playing);
        assertEquals("Now playing: " + TITLE, nowPlaying.getText().toString());

        Intent started = Shadows.shadowOf(app()).getNextStartedActivity();
        assertNotNull("the first state that names a song must re-run resume and open the player",
                started);
        assertEquals(PlaybackActivity.class.getName(),
                started.getComponent() != null ? started.getComponent().getClassName() : null);
        assertNotNull(presenter.getVideo());
        assertEquals(V1, presenter.getVideo().videoId);
        assertNull("the room song is opened once",
                Shadows.shadowOf(app()).getNextStartedActivity());
    }

    private static String state(String videoId, String title) {
        return "{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"songId\":\"s1\",\"videoId\":\""
                + videoId + "\",\"title\":\"" + title + "\"},\"queue\":[],\"listeners\":1}";
    }

    private static boolean logged(String tag, String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (tag.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /**
     * Websocket connect adopts the current song before returning, after the
     * client has already posted room-ready. Same order as a fast server: the
     * room-ready callback is queued first, then state.
     */
    static class StateOnConnectTransport implements JukeboxRoomClient.Transport {
        JukeboxRoomClient.Transport.Callbacks cb;
        String pendingState;

        public void connect(String url, JukeboxRoomClient.Transport.Callbacks c) {
            cb = c;
            c.onOpen();
            if (pendingState != null) {
                c.onMessage(pendingState);
            }
        }

        public boolean send(String text) {
            return true;
        }

        public void close() {
        }
    }
}
