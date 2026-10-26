package net.tfour.jukebox.room;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * While a jukebox room is active, VideoLoaderController (SmartTube's auto-advance)
 * must be a silent no-op: the server "play" message is the only thing that may open
 * the next video (issue tim4net/jukebox-tv#2: playback drifted to YouTube's own
 * suggestions). The player view is a recording dynamic proxy; the current video is
 * injected so {@code getPlayer()}/{@code getVideo()} are non-null and the gate is the
 * only possible early return.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoLoaderJukeboxGateTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    PlaybackPresenter presenter;
    PlaybackView player;
    List<String> playerCalls;

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager.class, "sInstance");

        playerCalls = new ArrayList<>();
        player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        playerCalls.add(method.getName());
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0f;
                        return null;
                    }
                });

        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(player);
        // Same effect as presenter.openVideo(videoId) but without the controller fan-out:
        // the loader only needs a non-null current video to get past its own guards.
        Video video = Video.from(VIDEO_ID);
        Field videoField = PlaybackPresenter.class.getDeclaredField("mVideo");
        videoField.setAccessible(true);
        videoField.set(presenter, new WeakReference<>(video));
        assertNotNull(presenter.getPlayer());
        assertEquals(VIDEO_ID, presenter.getVideo().videoId);
        playerCalls.clear(); // setView() itself probes getVideo(); ignore setup traffic
    }

    @After
    public void tearDown() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    private VideoLoaderController loader() {
        VideoLoaderController vlc = presenter.getController(VideoLoaderController.class);
        assertNotNull(vlc);
        return vlc;
    }

    @Test
    public void playEndAndLoadNextAreNoOpsWhileRoomActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(false); // idle room: no current song, autoplay still blocked

        VideoLoaderController vlc = loader();
        vlc.onPlayEnd();
        vlc.loadNext();

        assertTrue("no player interaction while the room is active: " + playerCalls,
                playerCalls.isEmpty());
    }

    @Test
    public void playEndAndLoadNextAreNoOpsWhileSongSet() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(true);

        VideoLoaderController vlc = loader();
        vlc.onPlayEnd();
        vlc.loadNext();

        assertTrue(playerCalls.isEmpty());
    }

    @Test
    public void loadNextRunsItsNormalPathWhenNoRoomIsActive() {
        // Negative control proving the tests above are stopped by the jukebox gate:
        // without it, loadNext() proceeds into the (uninitialized) suggestions
        // machinery, which throws in this harness.
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        try {
            loader().loadNext();
            fail("unblocked loadNext() should have advanced (harness: NPE from missing suggestions controller)");
        } catch (NullPointerException expected) {
            // expected: mSuggestionsController is null in this harness
        }
    }
}
