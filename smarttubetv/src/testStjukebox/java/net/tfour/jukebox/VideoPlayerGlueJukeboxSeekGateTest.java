package net.tfour.jukebox;

import android.os.Handler;
import android.os.Looper;

import androidx.leanback.media.PlayerAdapter;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.JukeboxMode;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.VideoPlayerGlue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Build 2465 (b): while a room song is active, seek keys must never seek the
 * room's song. The glue's rewind()/fastForward() (±10s; also the gamepad
 * L2/R2/trigger entry points via PlaybackActivity/PlaybackFragment) must be
 * no-ops then, and keep working when no room song is active.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoPlayerGlueJukeboxSeekGateTest {
    RecordingAdapter adapter;
    VideoPlayerGlue glue;

    static class RecordingAdapter extends PlayerAdapter {
        final List<Long> seeks = new ArrayList<>();
        long positionMs = 60_000;

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void seekTo(long positionMs) {
            seeks.add(positionMs);
        }

        @Override
        public long getDuration() {
            return 120_000;
        }

        @Override
        public long getCurrentPosition() {
            return positionMs;
        }

        @Override
        public long getBufferedPosition() {
            return 0;
        }
    }

    @Before
    public void setUp() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        adapter = new RecordingAdapter();
        glue = new VideoPlayerGlue(RuntimeEnvironment.getApplication(), adapter,
                new VideoPlayerGlue.OnActionClickedListener() {
                    @Override
                    public void onPrevious() {
                    }

                    @Override
                    public void onNext() {
                    }

                    @Override
                    public void onPlay() {
                    }

                    @Override
                    public void onPause() {
                    }

                    @Override
                    public void onAction(int actionId, int actionIndex) {
                    }

                    @Override
                    public void onLongAction(int actionId, int actionIndex) {
                    }

                    @Override
                    public void onTopEdgeFocused() {
                    }

                    @Override
                    public boolean onKeyDown(int keyCode) {
                        return false;
                    }
                });
    }

    @After
    public void tearDown() {
        JukeboxMode.setRoomActive(false);
        JukeboxMode.setSongSet(false);
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);
    }

    @Test
    public void rewindAndFastForwardAreNoOpsWhileRoomSongActive() {
        JukeboxMode.setRoomActive(true);
        JukeboxMode.setSongSet(true);

        glue.rewind();
        glue.fastForward();

        assertTrue("the room's song must not be seeked: " + adapter.seeks, adapter.seeks.isEmpty());
    }

    @Test
    public void rewindAndFastForwardStillWorkWhenNoRoomSong() {
        glue.rewind();
        glue.fastForward();

        assertEquals("without a room song seeking keeps working",
                java.util.Arrays.asList(50_000L, 70_000L), adapter.seeks);
    }
}
