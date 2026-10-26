package net.tfour.jukebox;

import android.content.Intent;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import net.tfour.jukebox.room.JukeboxForeground;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Player-start smoke test.
 *
 * A true player start (ExoPlayer decoding a real stream) is infeasible under
 * Robolectric: PlaybackView needs a full window/surface and the video loader
 * needs the network. The strongest feasible test without network is: register
 * the real PlaybackView -> PlaybackActivity mapping (as MainApplication does),
 * call PlaybackPresenter.instance(context).openVideo(videoId) and assert that
 * the presenter is created, holds the video, and dispatches a launch intent
 * for the real PlaybackActivity (the exact path the app uses to open the player).
 *
 * NOTE: ViewManager and PlaybackPresenter are static singletons that outlive
 * Robolectric's per-test Application. They capture the Application they were
 * first created with, so intents fired through a stale singleton never reach
 * the current test's shadow instrumentation. Both singletons are reset in
 * setUp() so this test is deterministic regardless of test order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class PlaybackStartSmokeTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    @Before
    public void setUp() throws Exception {
        resetSingleton(ViewManager.class, "sInstance");
        resetSingleton(PlaybackPresenter.class, "sInstance");
        // This test uses a plain Application (no MainApplication), so the jukebox
        // launch gate would never be (re-)initialized here — drop whatever an
        // earlier test in this JVM left behind, or the backgrounded-looking gate
        // would block openVideo's activity launch.
        JukeboxLaunch.resetForTests();
        JukeboxForeground.setTestOverride(null);

        ViewManager viewManager = ViewManager.instance(RuntimeEnvironment.getApplication());
        // Same registration MainApplication.setupViewManager() performs
        viewManager.register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void openVideoStartsPlaybackActivity() {
        PlaybackPresenter presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());

        assertNotNull("PlaybackPresenter.instance(context) must be non-null", presenter);

        // Must not throw with no player view attached (network-free code path:
        // the video is queued as pending inside VideoLoaderController)
        presenter.openVideo(VIDEO_ID);

        assertNotNull("openVideo must set the current video", presenter.getVideo());
        assertEquals(VIDEO_ID, presenter.getVideo().videoId);

        Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("openVideo must start the playback activity", started);
        assertEquals(new Intent(RuntimeEnvironment.getApplication(), PlaybackActivity.class).getComponent(),
                started.getComponent());
    }
}
