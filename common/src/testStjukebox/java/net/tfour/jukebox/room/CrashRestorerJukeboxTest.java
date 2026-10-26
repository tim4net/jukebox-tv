package net.tfour.jukebox.room;

import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Build 2465 (a): after Home + reopen the app used to restore SmartTube's
 * last-played browse video (V19xtIK6Djw) before the room corrected itself. While
 * a jukebox room is active (room set or song set), {@link CrashRestorer} must
 * never restore the last-played video/state: reopening must not even briefly
 * load any video other than the room's song.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CrashRestorerJukeboxTest {
    private static final String STALE_BROWSE_VIDEO = "V19xtIK6Djw";

    PlaybackPresenter presenter;
    List<String> opened;
    List<String> playerCalls;

    @Before
    public void setUp() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager.class, "sInstance");

        opened = new ArrayList<>();
        playerCalls = new ArrayList<>();

        presenter = PlaybackPresenter.instance(RuntimeEnvironment.getApplication());
        registerOpenRecorder(opened);
    }

    @After
    public void tearDown() throws Exception {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        resetSingleton(PlaybackPresenter.class, "sInstance");
        resetSingleton(com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager.class, "sInstance");
    }

    private static void resetSingleton(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    /** Registers a controller that records every videoId the presenter opens. */
    private void registerOpenRecorder(final List<String> opened) throws Exception {
        BasePlayerController recorder = new BasePlayerController() {
            @Override
            public void onNewVideo(Video item) {
                if (item != null) {
                    opened.add(item.videoId);
                }
            }
        };
        Field field = PlaybackPresenter.class.getDeclaredField("mEventListeners");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> listeners = (List<Object>) field.get(presenter);
        listeners.add(recorder);
    }

    /** Saved state as BrowseFragment persists it when Home is pressed mid-player. */
    private Bundle savedStateWithPlayerForeground() {
        Bundle bundle = new Bundle();
        bundle.putBoolean("IsPlayerInForeground", true);
        return bundle;
    }

    private void rememberLastPlayed(String videoId) {
        VideoStateService.instance(RuntimeEnvironment.getApplication())
                .save(new VideoStateService.State(Video.from(videoId), 30_000, 200_000));
    }

    @Test
    public void restoreSkippedWhileRoomSongActive() {
        rememberLastPlayed(STALE_BROWSE_VIDEO);
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        new CrashRestorer(RuntimeEnvironment.getApplication(), savedStateWithPlayerForeground())
                .restorePlayback();

        assertTrue("the stale browse video must not even briefly load while a room song is active: " + opened,
                opened.isEmpty());
    }

    @Test
    public void restoreSkippedWhileRoomActiveEvenIdle() {
        rememberLastPlayed(STALE_BROWSE_VIDEO);
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(false); // connected but idle room

        new CrashRestorer(RuntimeEnvironment.getApplication(), savedStateWithPlayerForeground())
                .restorePlayback();

        assertTrue("no last-played restore while in a room: " + opened, opened.isEmpty());
    }

    @Test
    public void restoreStillRunsWhenNoRoomIsActive() {
        rememberLastPlayed(STALE_BROWSE_VIDEO);

        new CrashRestorer(RuntimeEnvironment.getApplication(), savedStateWithPlayerForeground())
                .restorePlayback();

        assertEquals("without a room SmartTube keeps its restore behavior",
                java.util.Arrays.asList(STALE_BROWSE_VIDEO), opened);
    }
}
