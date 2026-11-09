package net.tfour.jukebox.update;

/**
 * Issue #28: the download-progress throttling that used to sit inline in
 * {@code JukeboxUpdatePresenter.downloadHttp}. For every streamed chunk
 * {@link #onBytes(long)} decides whether this chunk reports progress: a
 * report every {@link #PERCENT_STEP} percent while the content length is
 * known, otherwise every {@link #BYTES_STEP} streamed bytes — never both
 * for one chunk (a percent-step chunk advances only the percent step, the
 * byte-step baseline is NOT reset there).
 */
final class ProgressThrottle {
    /** Returned by {@link #onBytes(long)} when this chunk must not report. */
    static final int NO_REPORT = -1;
    /** ~10% between reports when the content length is known. */
    static final int PERCENT_STEP = 10;
    /** ~2MB between reports when the content length is unknown. */
    static final long BYTES_STEP = 2L * 1024 * 1024;

    private final long mTotal;
    private int mNextPercent = PERCENT_STEP;
    private long mLastBytes;

    /** @param total content length; <= 0 means unknown (byte stepping only). */
    ProgressThrottle(long total) {
        mTotal = total;
    }

    /**
     * @return the percent to report for this chunk, or {@link #NO_REPORT}
     *         when the chunk must stay silent.
     */
    int onBytes(long done) {
        int percent = mTotal > 0 ? (int) Math.min(100, done * 100 / mTotal) : 0;
        if (percent >= mNextPercent) { // unknown length: percent stays 0, never a step
            mNextPercent += PERCENT_STEP; // lastBytes NOT updated here
            return percent;
        }
        if (done - mLastBytes >= BYTES_STEP) {
            mLastBytes = done;
            return percent;
        }
        return NO_REPORT;
    }
}
