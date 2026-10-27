package net.tfour.jukebox.room;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The join screen re-asks the controller once after an idle-room skip, then
 * stops. The controller hides a repeat ask while a load is in flight (one
 * open either way), so these tests count {@code resumeRoomSongIfNeeded}
 * calls. A second playing state, a forced-clickable QR, and an idle click
 * are counted the same way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class JukeboxJoinExactlyOnceAskTest {
    static final String V1 = "yxfo4Jfpc-c";
    static final String TITLE = "Lady May";

    final List<Runnable> roomStarts = new ArrayList<>();
    final List<HandlerThread> threads = new ArrayList<>();
    Transport t;
    JukeboxRoom room;
    AskCounter asks;
    ActivityController<JukeboxJoinActivity> ac;

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
        JukeboxForeground.setTestOverride(true);
        ViewManager.instance(app()).register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);

        t = new Transport();
        room = new JukeboxRoom(app(), (thread, listener) -> {
            threads.add(thread);
            return new JukeboxRoomClient("https://jukebox.example", t,
                    new JukeboxJoinColdLaunchTest.FakeHttp(),
                    new JukeboxJoinColdLaunchTest.FakeScheduler(),
                    new JukeboxJoinColdLaunchTest.MapStore(), listener, "");
        }, roomStarts::add);
        setSingleton(JukeboxRoom.class, "sInstance", room);
        asks = installAskCounter();
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
    public void songAfterIdleSkipAsksOnceAndLaterRoomOrStateUpdatesDoNot() {
        launch();
        int mark = asks.vias.size();

        // Room-ready is posted before state, and the song is already adopted
        // when either callback runs: the room update is the one ask.
        runDeferredConnect(state(V1, TITLE));

        assertEquals(Collections.singletonList("join:onRoom"), since(mark));
        assertEquals(Collections.singletonList(PlaybackActivity.class.getName()), startedActivities());

        t.msg(state(V1, "Lady May (again)"));
        idle();
        ac.get().onRoomUpdated();
        ac.get().onStateChanged("Lady May (again)");

        assertEquals("later room and state updates must not ask again",
                Collections.singletonList("join:onRoom"), since(mark));
        assertEquals(Collections.emptyList(), startedActivities());
    }

    @Test
    public void noSongArrivalDoesNotAskAgain() {
        launch();
        assertEquals(Collections.singletonList("join:onResume:tick1"), new ArrayList<>(asks.vias));

        runDeferredConnect(null);
        t.msg(idleState());
        idle();
        ac.get().onRoomUpdated();
        ac.get().onStateChanged(null);
        ac.get().onStateChanged("");

        assertEquals("no song: the idle tick is the only resume check",
                Collections.singletonList("join:onResume:tick1"), new ArrayList<>(asks.vias));
        assertEquals(Collections.emptyList(), startedActivities());
    }

    @Test
    public void secondPlayingStateDoesNotAskOrReopen() {
        launch();
        runDeferredConnect(null);
        int mark = asks.vias.size();

        t.msg(state(V1, TITLE));
        idle();
        t.msg(state(V1, TITLE));
        idle();

        assertEquals("the idle-to-playing edge asks once; a later playing state does not",
                Collections.singletonList("join:onState"), since(mark));
        assertEquals(Collections.singletonList(PlaybackActivity.class.getName()), startedActivities());
    }

    @Test
    public void stateAfterFocusAlreadyOnTheSongUsesTheOnStateOneShotOnce() {
        // bind() has already marked the room playing, so onStateChanged's
        // idle-to-playing edge is gone. The one-shot still owes one ask.
        launch();
        adoptSongWithoutNotifying();
        ac.get().bind(room.getCode(), room.getJoinUrl(), room.getStatus(), room.getNowPlaying());
        int mark = asks.vias.size();

        ac.get().onStateChanged(TITLE);
        ac.get().onStateChanged(TITLE);

        assertEquals(Collections.singletonList("join:onState"), since(mark));
        assertEquals(Collections.singletonList(PlaybackActivity.class.getName()), startedActivities());
    }

    @Test
    public void backgroundedRoomUpdateAsksOnceWhileTheRetryTickIsStillQueued() {
        launch();
        adoptSongWithoutNotifying();
        JukeboxForeground.setTestOverride(false);
        int mark = asks.vias.size();

        // Both calls are synchronous. The posted retry tick has not run, so
        // only the clear inside the one-shot stands between them.
        ac.get().onRoomUpdated();
        ac.get().onRoomUpdated();

        assertEquals(Collections.singletonList("join:onRoom"), since(mark));
        assertEquals(Collections.emptyList(), startedActivities());
    }

    @Test
    public void keepAskingTickDisarmsTheIdleOneShot() {
        launch();
        adoptSongWithoutNotifying();
        JukeboxForeground.setTestOverride(false);
        int mark = asks.vias.size();

        // onResume restarts the retry. The tick is told to keep asking
        // (app backgrounded) and must disarm the one-shot before the next
        // room update.
        ac.pause().resume();
        idle();
        ac.get().onRoomUpdated();

        assertEquals(Collections.singletonList("join:onResume:tick1"), since(mark));
        assertEquals(Collections.emptyList(), startedActivities());
    }

    @Test
    public void playingToIdleHandsFocusBackToBrowseAndQrIsNotClickable() {
        launch();
        layoutLikeTheTv();
        runDeferredConnect(null);

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        Button browse = ac.get().findViewById(R.id.jukebox_browse);
        assertFalse(qr.isFocusable());
        assertFalse(qr.isClickable());
        assertTrue(browse.hasFocus());

        t.msg(state(V1, TITLE));
        idle();
        assertTrue(qr.isFocusable());
        assertTrue(qr.isClickable());
        assertTrue(qr.hasFocus());

        t.msg(idleState());
        idle();

        assertFalse("the QR must not stay focusable when the room goes idle", qr.isFocusable());
        assertFalse("playing to idle must not leave the QR clickable", qr.isClickable());
        assertTrue("focus must return to Browse when the room goes idle", browse.hasFocus());
    }

    @Test
    public void idleClickDoesNotAskToResume() {
        launch();
        int mark = asks.vias.size();

        View qr = ac.get().findViewById(R.id.jukebox_qr);
        assertFalse(qr.isClickable());
        qr.performClick();
        idle();

        assertEquals("an idle click must not reach the resume retry",
                mark, asks.vias.size());
        assertEquals(Collections.emptyList(), startedActivities());
    }

    private void launch() {
        ac = Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        idle();
        assertEquals(Collections.singletonList("join:onResume:tick1"), new ArrayList<>(asks.vias));
    }

    private void runDeferredConnect(String pendingState) {
        t.pendingState = pendingState;
        assertEquals(1, roomStarts.size());
        roomStarts.remove(0).run();
        idle();
    }

    /** Adopts a song without the join screen's listener seeing the callbacks. */
    private void adoptSongWithoutNotifying() {
        room.removeListener(ac.get());
        runDeferredConnect(state(V1, TITLE));
        assertEquals(V1, room.getCurrentVideoId());
    }

    private List<String> since(int mark) {
        return new ArrayList<>(asks.vias.subList(mark, asks.vias.size()));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Robolectric does not measure the decor. Focus needs a real layout pass. */
    private void layoutLikeTheTv() {
        View root = ac.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }

    private static String state(String videoId, String title) {
        return "{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":{\"songId\":\"s1\",\"videoId\":\""
                + videoId + "\",\"title\":\"" + title + "\"},\"queue\":[],\"listeners\":1}";
    }

    private static String idleState() {
        return "{\"t\":\"state\",\"room\":\"room1\",\"nowPlaying\":null,\"queue\":[],\"listeners\":1}";
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

    private static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static AskCounter installAskCounter() throws Exception {
        PlaybackPresenter presenter = PlaybackPresenter.instance(app());
        Field listenersField = PlaybackPresenter.class.getDeclaredField("mEventListeners");
        listenersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlayerEventListener> listeners =
                (List<PlayerEventListener>) listenersField.get(presenter);
        for (int i = listeners.size() - 1; i >= 0; i--) {
            if (listeners.get(i) instanceof JukeboxRoomController) {
                listeners.remove(i);
            }
        }
        AskCounter counter = new AskCounter();
        listeners.add(counter);
        return counter;
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        setSingleton(clazz, fieldName, null);
    }

    private static void setSingleton(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Counts every join-screen call to {@link JukeboxRoomController#resumeRoomSongIfNeeded}. */
    static final class AskCounter extends JukeboxRoomController {
        final List<String> vias = new ArrayList<>();

        @Override
        public boolean resumeRoomSongIfNeeded(String via) {
            vias.add(via);
            return super.resumeRoomSongIfNeeded(via);
        }
    }

    static class Transport implements JukeboxRoomClient.Transport {
        JukeboxRoomClient.Transport.Callbacks cb;
        String pendingState;

        public void connect(String url, JukeboxRoomClient.Transport.Callbacks c) {
            cb = c;
            c.onOpen();
            if (pendingState != null) {
                String message = pendingState;
                pendingState = null;
                c.onMessage(message);
            }
        }

        public boolean send(String text) {
            return true;
        }

        public void close() {
        }

        void msg(String s) {
            cb.onMessage(s);
        }
    }
}
