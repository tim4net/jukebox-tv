package net.tfour.jukebox.room;

import android.os.Handler;
import android.os.Looper;

/**
 * Handler-based {@link Scheduler}. Defaults to the main looper; pass a background
 * looper when scheduled work must not touch the main thread.
 */
public class HandlerScheduler implements JukeboxRoomClient.Scheduler {
    private final Handler mHandler;

    public HandlerScheduler() {
        this(Looper.getMainLooper());
    }

    public HandlerScheduler(Looper looper) {
        mHandler = new Handler(looper);
    }

    @Override
    public void schedule(Runnable r, long delayMs) {
        mHandler.postDelayed(r, Math.max(0, delayMs));
    }

    @Override
    public void cancelAll() {
        mHandler.removeCallbacksAndMessages(null);
    }
}
