package net.tfour.jukebox.room;

import android.view.KeyEvent;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.PlayerUIController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2469, gazebo c4: the KEYCODE_MEDIA_PLAY_PAUSE key event routed through
 * the player's key handler must be swallowed (logged) while a jukebox room
 * song is active — the room owns play/pause — and keep toggling playback
 * without one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayerUIControllerPlayPauseKeyTest {
    private PlaybackPresenter presenter;
    private PlayerUIController controller;
    private RecordingPlayerView playerView;
    private Method handlePlayPauseKey;

    /** Records every playWhenReady flip the controller dispatches at the view. */
    class RecordingPlayerView implements InvocationHandler {
        final List<Boolean> playWhenReadyCalls = new ArrayList<>();
        boolean playWhenReady;

        PlaybackView proxy() {
            return (PlaybackView) Proxy.newProxyInstance(
                    PlaybackView.class.getClassLoader(),
                    new Class<?>[]{PlaybackView.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getPlayWhenReady".equals(name)) {
                return playWhenReady;
            }
            if ("setPlayWhenReady".equals(name) && args != null && args.length == 1) {
                playWhenReady = (Boolean) args[0];
                playWhenReadyCalls.add((Boolean) args[0]);
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        ShadowLog.clear();

        java.lang.reflect.Field vm = ViewManager.class.getDeclaredField("sInstance");
        vm.setAccessible(true);
        vm.set(null, null);
        java.lang.reflect.Field pp = PlaybackPresenter.class.getDeclaredField("sInstance");
        pp.setAccessible(true);
        pp.set(null, null);

        playerView = new RecordingPlayerView();
        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        presenter.setView(playerView.proxy());
        controller = presenter.getController(PlayerUIController.class);
        assertNotNull("the presenter must have registered the UI controller", controller);
        handlePlayPauseKey = PlayerUIController.class.getDeclaredMethod("handlePlayPauseKey", int.class);
        handlePlayPauseKey.setAccessible(true);
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        java.lang.reflect.Field vm = ViewManager.class.getDeclaredField("sInstance");
        vm.setAccessible(true);
        vm.set(null, null);
        java.lang.reflect.Field pp = PlaybackPresenter.class.getDeclaredField("sInstance");
        pp.setAccessible(true);
        pp.set(null, null);
    }

    private boolean handlePlayPauseKey(int keyCode) throws Exception {
        return (Boolean) handlePlayPauseKey.invoke(controller, keyCode);
    }

    private static boolean swallowed(String what) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ("JukeboxKeys".equals(item.tag) && item.msg != null && item.msg.contains(what)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void playPauseKeyIsSwallowedWhileRoomSongActive() throws Exception {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        boolean handled = handlePlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        assertTrue("the swallow must handle (consume) the key", handled);
        assertTrue("the room's song must never be paused: " + playerView.playWhenReadyCalls,
                playerView.playWhenReadyCalls.isEmpty());
        assertTrue("the swallow must be logged",
                swallowed("swallowed " + KeyEvent.keyCodeToString(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
    }

    @Test
    public void playAndPauseKeysAreSwallowedWhileRoomSongActive() throws Exception {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        assertTrue(handlePlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertTrue(handlePlayPauseKey(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue("nothing may reach the player: " + playerView.playWhenReadyCalls,
                playerView.playWhenReadyCalls.isEmpty());
    }

    @Test
    public void playPauseKeyStillTogglesWithoutARoomSong() throws Exception {
        assertTrue("the key must still be handled (toggle) without a room song",
                handlePlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertEquals("playback must toggle without a room song",
                java.util.Arrays.asList(Boolean.TRUE), playerView.playWhenReadyCalls);
        assertFalse("nothing may be logged without a room song", swallowed("swallowed"));
    }
}