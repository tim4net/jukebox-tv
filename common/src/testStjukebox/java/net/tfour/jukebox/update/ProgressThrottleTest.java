package net.tfour.jukebox.update;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Issue #28: {@link ProgressThrottle} pins the exact download-progress
 * throttling semantics that used to sit inline in
 * {@code JukeboxUpdatePresenter.downloadHttp} — a report every 10% when the
 * length is known (exactly once per step, boundary inclusive), otherwise a
 * report every ~2MB, never both for one chunk (a percent-step chunk does NOT
 * reset the byte-step baseline), and a jump past several percent steps
 * reports once and catches {@code nextPercent} up by a single step.
 */
public class ProgressThrottleTest {

    /** Feeds every {@code done} value and collects the reported percents. */
    private static List<Integer> reportsOf(long total, long... doneValues) {
        ProgressThrottle throttle = new ProgressThrottle(total);
        List<Integer> reports = new ArrayList<>();
        for (long done : doneValues) {
            int percent = throttle.onBytes(done);
            if (percent != ProgressThrottle.NO_REPORT) {
                reports.add(percent);
            }
        }
        return reports;
    }

    @Test
    public void knownLengthReportsEachTenPercentStepExactlyOnce() {
        long[] done = new long[1000];
        for (int i = 0; i < done.length; i++) {
            done[i] = i + 1;
        }
        assertEquals(Arrays.asList(10, 20, 30, 40, 50, 60, 70, 80, 90, 100),
                reportsOf(1000, done));
    }

    @Test
    public void knownLengthReportsExactlyAtTheBoundary() {
        assertEquals(Arrays.asList(10), reportsOf(1000, 99, 100, 101));
    }

    @Test
    public void knownLengthNeverReportsAbove100Percent() {
        assertEquals(Arrays.asList(100), reportsOf(1000, 2000));
    }

    @Test
    public void unknownLengthZeroReportsEveryBytesStep() {
        long step = ProgressThrottle.BYTES_STEP;
        assertEquals(Arrays.asList(0, 0),
                reportsOf(0, step - 1, step, 2 * step - 1, 2 * step));
    }

    @Test
    public void unknownLengthNegativeReportsEveryBytesStep() {
        long step = ProgressThrottle.BYTES_STEP;
        assertEquals(Arrays.asList(0, 0),
                reportsOf(-1, step - 1, step, 2 * step - 1, 2 * step));
    }

    @Test
    public void aChunkCrossingBothStepsAdvancesOnlyThePercentStep() {
        // total = 25 * BYTES_STEP, so percents land on quarters. The chunk
        // at 5.5 steps (22%) crosses BOTH its percent step and a byte step,
        // but only the percent branch may fire: lastBytes stays at 2 steps,
        // so 5.75 steps (3.75 steps past that OLD baseline) still byte-steps,
        // while 6.5 steps (only 0.75 steps past the new baseline) does not.
        // Swapping the branch order, or updating lastBytes on a percent-step
        // chunk, changes this exact sequence.
        long step = ProgressThrottle.BYTES_STEP;
        assertEquals(Arrays.asList(4, 8, 10, 22, 23),
                reportsOf(25 * step, step, 2 * step, 5 * step / 2,
                        11 * step / 2, 23 * step / 4, 13 * step / 2));
    }

    @Test
    public void aJumpPastSeveralPercentStepsReportsOnceThenAgainNextChunk() {
        // 550 jumps past the 20..50 steps: reported once as 55, nextPercent
        // catches up by ONE step (to 30), so the very next chunk reports too.
        assertEquals(Arrays.asList(10, 55, 56), reportsOf(1000, 100, 550, 560));
    }
}
