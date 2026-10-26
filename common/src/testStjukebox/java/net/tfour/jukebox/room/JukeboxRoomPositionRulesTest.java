package net.tfour.jukebox.room;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Review of the room-position estimate (issue #6 / PR #13).
 *
 * <p>The public answer is {@link JukeboxRoomClient#roomPositionMs(String)}:
 * <ul>
 * <li>elapsed time since the snapshot arrived is
 * {@link SystemClock#elapsedRealtime()}, so a monotonic advance moves the
 * song when the wall clock does not;</li>
 * <li>no server {@code now} means unknown ({@code -1}), not the local clock;</li>
 * <li>a position at or past {@code durationMs} minus {@link #END_MARGIN_MS}
 * is unknown — callers must not seek to the end;</li>
 * <li>one publish is one snapshot: mid-publish, the previous song's position
 * still answers and the new video id does not.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxRoomPositionRulesTest {
    static final String V1 = "dQw4w9WgXcQ";
    static final String V2 = "9bZkp7q19f0";
    /** Seek is refused once the estimate is this close to the end (or past it). */
    static final long END_MARGIN_MS = 2_000L;
    static final long ROOM_POS_MS = 120_000L;
    static final long DURATION_MS = 300_000L;
    /** Fixed server epoch so the cases do not depend on the test clock. */
    static final long SERVER_EPOCH_MS = 1_700_000_000_000L;

    JukeboxRoomClientTest.FakeTransport t;
    JukeboxRoomClientTest.FakeHttp http;
    JukeboxRoomClientTest.FakeScheduler sch;
    JukeboxRoomClientTest.MapStore store;
    JukeboxRoomClientTest.Rec rec;
    JukeboxRoomClient c;
    private boolean mRestoreNano;
    private long mSavedNanoTime;

    /** A real "state" snapshot. {@code serverNowMs == null} omits {@code now}. */
    static String stateJson(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                            boolean paused, Long pausedAtMs, Long durationMs) {
        StringBuilder sb = new StringBuilder("{\"t\":\"state\",\"room\":\"room1\"");
        if (serverNowMs != null) {
            sb.append(",\"now\":").append(serverNowMs);
        }
        sb.append(",\"nowPlaying\":{\"songId\":\"").append(songId)
                .append("\",\"videoId\":\"").append(videoId).append("\",\"title\":\"T ").append(songId)
                .append("\"");
        if (startedAtMs != null) {
            sb.append(",\"startedAt\":").append(startedAtMs);
        }
        if (paused) {
            sb.append(",\"paused\":true");
        }
        if (pausedAtMs != null) {
            sb.append(",\"pausedAt\":").append(pausedAtMs);
        }
        if (durationMs != null) {
            sb.append(",\"durationMs\":").append(durationMs);
        }
        sb.append("},\"queue\":[],\"listeners\":1}");
        return sb.toString();
    }

    @Before
    public void setUp() {
        JukeboxRoomClient.setDuringPositionPublishForTests(null);
        mRestoreNano = ShadowLooper.looperMode() == LooperMode.Mode.LEGACY;
        if (mRestoreNano) {
            mSavedNanoTime = System.nanoTime();
        }
        t = new JukeboxRoomClientTest.FakeTransport();
        http = new JukeboxRoomClientTest.FakeHttp();
        sch = new JukeboxRoomClientTest.FakeScheduler();
        store = new JukeboxRoomClientTest.MapStore();
        rec = new JukeboxRoomClientTest.Rec();
        c = new JukeboxRoomClient(JukeboxRoomClientTest.ORIGIN, t, http, sch, store, rec, "");
        c.start();
        t.open();
    }

    @After
    public void tearDown() {
        JukeboxRoomClient.setDuringPositionPublishForTests(null);
        if (mRestoreNano) {
            ShadowSystemClock.setNanoTime(mSavedNanoTime);
        }
        if (c != null) {
            c.stop();
        }
    }

    private void state(String songId, String videoId, Long startedAtMs, Long serverNowMs,
                       boolean paused, Long pausedAtMs, Long durationMs) {
        t.msg(stateJson(songId, videoId, startedAtMs, serverNowMs, paused, pausedAtMs, durationMs));
    }

    @Test
    @LooperMode(LooperMode.Mode.LEGACY)
    public void playingPositionAdvancesWithElapsedRealtimeNotWallClock() {
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        long serverNow = SERVER_EPOCH_MS;
        state("s1", V1, serverNow - ROOM_POS_MS, serverNow, false, null, DURATION_MS);

        // Monotonic clock only. In LEGACY mode the master scheduler is
        // elapsedRealtime, and System.currentTimeMillis stays on the wall clock.
        RuntimeEnvironment.getMasterScheduler().advanceBy(30_000L, TimeUnit.MILLISECONDS);

        long wallMoved = System.currentTimeMillis() - wall;
        long elapsedMoved = SystemClock.elapsedRealtime() - elapsed;
        assertTrue("elapsedRealtime must move by the monotonic advance (moved " + elapsedMoved
                        + "ms, wall moved " + wallMoved + "ms)",
                elapsedMoved >= 30_000L && elapsedMoved <= 31_000L);
        assertTrue("wall clock must not absorb that advance (moved " + wallMoved + "ms)",
                Math.abs(wallMoved) < 5_000L);
        long pos = c.roomPositionMs(V1);
        assertTrue("elapsed time since the snapshot must use SystemClock.elapsedRealtime() "
                        + "(got " + pos + "ms, want ~" + (ROOM_POS_MS + 30_000L) + ")",
                pos >= ROOM_POS_MS + 30_000L && pos <= ROOM_POS_MS + 30_000L + 1_000L);
    }

    @Test
    public void snapshotWithoutServerNowIsUnknown() {
        // startedAt is present and in the past of the local clock, so the
        // local-clock fallback would invent a position. Missing "now" is unknown.
        long wall = Math.max(System.currentTimeMillis(), SERVER_EPOCH_MS);
        SystemClock.setCurrentTimeMillis(wall);
        wall = System.currentTimeMillis();
        state("s1", V1, wall - ROOM_POS_MS, null, false, null, DURATION_MS);

        assertEquals("no server now is unknown", -1L, c.roomPositionMs(V1));
    }

    @Test
    public void positionPastDurationIsUnknown() {
        // Paused 5s past the end: frozen, so the local clock cannot smear it.
        // Today this is capped at durationMs; it must be unknown instead.
        long startedAt = SERVER_EPOCH_MS;
        state("s1", V1, startedAt, startedAt + DURATION_MS, true,
                startedAt + DURATION_MS + 5_000L, DURATION_MS);

        assertEquals("at or past the end is unknown, not durationMs",
                -1L, c.roomPositionMs(V1));
    }

    @Test
    public void positionAtOrInsideEndMarginIsUnknown() {
        long startedAt = SERVER_EPOCH_MS;
        long serverNow = startedAt + DURATION_MS;
        // Exactly duration - margin: "at" the margin. Must not seek here.
        state("s1", V1, startedAt, serverNow, true,
                startedAt + DURATION_MS - END_MARGIN_MS, DURATION_MS);
        assertEquals("position == durationMs - " + END_MARGIN_MS + "ms is unknown (got "
                        + c.roomPositionMs(V1) + ")",
                -1L, c.roomPositionMs(V1));

        // One millisecond inside the margin, still short of the end.
        state("s1", V1, startedAt, serverNow, true,
                startedAt + DURATION_MS - 1L, DURATION_MS);
        assertEquals("position == durationMs - 1ms is unknown (got " + c.roomPositionMs(V1) + ")",
                -1L, c.roomPositionMs(V1));
    }

    @Test
    public void positionPublishIsOneSnapshot() {
        long startedA = SERVER_EPOCH_MS;
        state("s1", V1, startedA, startedA + 200_000L, true, startedA + 100_000L, DURATION_MS);
        assertEquals("snapshot A is paused at 100s", 100_000L, c.roomPositionMs(V1));

        long startedB = SERVER_EPOCH_MS + 100_000_000L;
        final long[] midV1 = {-2L};
        final long[] midV2 = {-2L};
        JukeboxRoomClient.setDuringPositionPublishForTests(new Runnable() {
            @Override
            public void run() {
                midV1[0] = c.roomPositionMs(V1);
                midV2[0] = c.roomPositionMs(V2);
            }
        });
        state("s2", V2, startedB, startedB + 200_000L, true, startedB + 40_000L, DURATION_MS);

        assertEquals("previous song stays readable until the new record is published (got " + midV1[0] + ")",
                100_000L, midV1[0]);
        assertEquals("new video id is not visible until the whole record is published (got " + midV2[0] + ")",
                -1L, midV2[0]);
        assertEquals("published snapshot B is paused at 40s", 40_000L, c.roomPositionMs(V2));
        assertEquals("snapshot A is gone once B is published", -1L, c.roomPositionMs(V1));
    }
}
