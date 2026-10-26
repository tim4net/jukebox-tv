package net.tfour.jukebox.room;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build 2465 (b): during a room song, MEDIA_PREVIOUS restarted the track
 * (nina 2464 step 7: pos 39371ms→674ms). The restart came from
 * VideoStateController.onPreviousClicked's seek-to-start, which runs FIRST in the
 * controller chain — before the jukebox-gated loader. While a room song is active,
 * Previous must do nothing at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxPreviousKeyGateTest {
    private static final String VIDEO_ID = "4zAThXFOy2c";

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
                        playerCalls.add(args == null || args.length == 0 ? method.getName()
                                : method.getName() + ":" + java.util.Arrays.toString(args));
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return method.getName().equals("containsMedia");
                        if (type == int.class) return 0;
                        if (type == long.class) {
                            // 60s into a 120s video: way past the 10s restart threshold
                            return method.getName().equals("getDurationMs") ? 120_000L : 60_000L;
                        }
                        if (type == float.class) return 1f;
                        return null;
                    }
                });

        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(player);
        // Keep the restore/seek path off the network (history sync) in this harness.
        GeneralData.instance(RuntimeEnvironment.getApplication()).setHistoryState(GeneralData.HISTORY_DISABLED);
        Field videoField = PlaybackPresenter.class.getDeclaredField("mVideo");
        videoField.setAccessible(true);
        videoField.set(presenter, new WeakReference<>(Video.from(VIDEO_ID)));
        playerCalls.clear();
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

    @Test
    public void previousDoesNotRestartTrackWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertTrue(presenter.onPreviousClicked());

        assertFalse("previous must not seek the track back to the start: " + playerCalls,
                playerCalls.contains("setPositionMs:[100]"));
        assertFalse("previous must do nothing at all: " + playerCalls,
                playerCalls.contains("setPlayWhenReady:[false]"));
    }

    @Test
    public void previousStillRestartsTrackWhenNoRoom() {
        // Negative control: the SmartTube seek-to-start behavior is intact when
        // no room song is active.
        assertTrue(presenter.onPreviousClicked());

        assertTrue("without a room, previous still seeks to the start: " + playerCalls,
                playerCalls.contains("setPositionMs:[100]"));
    }
}
