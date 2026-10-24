package net.tfour.jukebox.room;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * "Pop-Up Video" bubble overlay on the playback window (stjukebox flavor only). Not
 * focusable, so it never steals DPAD focus from the player; added to the activity decor
 * like {@link JukeboxJoinBadge} and idempotent by view tag.
 *
 * Fetches bubbles for the current room song from GET /api/popups/&lt;videoId&gt; (OkHttp on a
 * background thread, cached per videoId; 404/empty/malformed/network error silently no-op)
 * and polls the player position every ~500ms while attached, popping one retro bubble per
 * {@link JukeboxProtocol.Bubble} at its [atSec, atSec+6s) window. Each bubble shows at most
 * once per song. Everything is removed when popups turn off, the video changes or playback
 * leaves (see {@link #dismiss(Activity)}).
 */
public final class JukeboxPopupOverlay extends FrameLayout {
    public static final String TAG = "jukebox_popup_overlay";
    private static final long POLL_INTERVAL_MS = 500;
    private static final long ANIM_IN_MS = 250;
    private static final long ANIM_OUT_MS = 150;
    private static final int MAX_CACHED_VIDEOS = 64;

    /** Player position in ms, or a negative value when unavailable (no player / other video). */
    public interface PositionSupplier {
        long getPositionMs();
    }

    private static final Map<String, List<JukeboxProtocol.Bubble>> sCache = new ConcurrentHashMap<>();
    private static volatile OkHttpClient sClient;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<Integer> mShownIndexes = new HashSet<>();
    private final RectF mRect = new RectF();
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mPolling || getWindowToken() == null) {
                return;
            }
            tick();
            if (mPolling && getWindowToken() != null) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };
    private final Runnable mHideCurrent = new Runnable() {
        @Override
        public void run() {
            hideBubble(false);
        }
    };

    private PositionSupplier mPosition;
    private String mVideoId;
    private List<JukeboxProtocol.Bubble> mBubbles;
    private int mFetchGeneration;
    private boolean mPolling;
    private BubbleView mCurrentBubble;
    private int mCurrentIndex = -1;

    private JukeboxPopupOverlay(Context context) {
        super(context);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        // No background: touches pass through to the player.
    }

    /** Adds/updates the overlay for the given room song. Idempotent; resets per-video state on change. */
    public static void show(Activity activity, String origin, String videoId, PositionSupplier supplier) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activity.getWindow() == null || supplier == null
                || origin == null || !JukeboxProtocol.isValidVideoId(videoId)) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        FrameLayout frame = (FrameLayout) decor;
        View existing = frame.findViewWithTag(TAG);
        JukeboxPopupOverlay overlay;
        if (existing instanceof JukeboxPopupOverlay) {
            overlay = (JukeboxPopupOverlay) existing;
        } else {
            if (existing != null) {
                frame.removeView(existing);
            }
            overlay = new JukeboxPopupOverlay(activity);
            overlay.setTag(TAG);
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            // Top-left area, away from the bottom-right QR/room-code badge.
            lp.setMargins(Math.round(metrics.widthPixels * 0.05f),
                    Math.round(metrics.heightPixels * 0.08f), 0, 0);
            frame.addView(overlay, lp);
            overlay.bringToFront();
        }
        overlay.bind(origin, videoId, supplier);
    }

    /** Removes the overlay (bubbles, poll, in-flight fetch) from the activity decor. Idempotent. */
    public static void dismiss(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        FrameLayout frame = (FrameLayout) decor;
        View existing = frame.findViewWithTag(TAG);
        if (existing != null && existing.getParent() == frame) {
            frame.removeView(existing);
        }
    }

    private void bind(String origin, String videoId, PositionSupplier supplier) {
        mPosition = supplier;
        if (!videoId.equals(mVideoId)) {
            mVideoId = videoId;
            mShownIndexes.clear();
            hideBubble(true);
            List<JukeboxProtocol.Bubble> cached = sCache.get(videoId);
            if (cached != null) {
                mBubbles = cached;
            } else {
                mBubbles = null;
                fetchBubbles(origin + "/api/popups/" + videoId);
            }
        }
        startPolling();
    }

    private void fetchBubbles(String url) {
        final int generation = ++mFetchGeneration;
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (Throwable invalidUrl) {
            return; // silent no-op
        }
        try {
            client().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    onFetchFinished(generation, null);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            onFetchFinished(generation, null);
                            return;
                        }
                        ResponseBody body = response.body();
                        onFetchFinished(generation, body != null ? body.string() : null);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable rejected) {
            // Silent no-op.
        }
    }

    private void onFetchFinished(final int generation, final String body) {
        mHandler.post(() -> {
            if (generation != mFetchGeneration || mVideoId == null) {
                return;
            }
            List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(body);
            if (bubbles == null) {
                bubbles = Collections.emptyList();
            }
            if (sCache.size() >= MAX_CACHED_VIDEOS) {
                sCache.clear();
            }
            sCache.put(mVideoId, bubbles);
            mBubbles = bubbles;
        });
    }

    private void tick() {
        long positionMs = mPosition != null ? mPosition.getPositionMs() : -1L;
        if (positionMs < 0 || mBubbles == null) {
            if (mCurrentBubble != null) {
                hideBubble(false);
            }
            return;
        }
        if (mCurrentIndex >= 0 && mCurrentIndex < mBubbles.size()
                && !JukeboxProtocol.isBubbleActive(mBubbles.get(mCurrentIndex), positionMs)) {
            hideBubble(false);
        }
        if (mCurrentBubble != null) {
            return; // one bubble at a time
        }
        int index = JukeboxProtocol.selectBubble(mBubbles, positionMs, mShownIndexes);
        if (index >= 0) {
            mShownIndexes.add(index); // each bubble once per song
            showBubble(index);
        }
    }

    private void showBubble(int index) {
        final BubbleView view = new BubbleView(getContext(), mBubbles.get(index).text, index);
        mCurrentBubble = view;
        mCurrentIndex = index;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.topMargin = (index % 3) * dp(64); // small stagger so bubbles don't always cover the same spot
        lp.leftMargin = (index % 2) * dp(40);
        view.setScaleX(0.3f);
        view.setScaleY(0.3f);
        addView(view, lp);
        // Pop/scale-in: 0.3 -> 1.1 (overshoot) -> 1.
        PropertyValuesHolder holder = PropertyValuesHolder.ofKeyframe("",
                Keyframe.ofFloat(0f, 0.3f), Keyframe.ofFloat(0.7f, 1.1f), Keyframe.ofFloat(1f, 1f));
        ValueAnimator anim = ValueAnimator.ofPropertyValuesHolder(holder);
        anim.setDuration(ANIM_IN_MS);
        anim.addUpdateListener(animation -> {
            float scale = (Float) animation.getAnimatedValue();
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        anim.start();
        // Hard cap on screen time, covers a paused player whose position never advances.
        mHandler.postDelayed(mHideCurrent, JukeboxProtocol.POPUP_WINDOW_MS);
    }

    private void hideBubble(boolean immediate) {
        mHandler.removeCallbacks(mHideCurrent);
        final BubbleView view = mCurrentBubble;
        mCurrentBubble = null;
        mCurrentIndex = -1;
        if (view == null) {
            return;
        }
        if (immediate) {
            removeView(view);
            return;
        }
        view.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(ANIM_OUT_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        removeView(view);
                    }
                });
    }

    private void startPolling() {
        mPolling = true;
        mHandler.removeCallbacks(mPoll);
        mHandler.postDelayed(mPoll, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        mPolling = false;
        mHandler.removeCallbacks(mPoll);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPolling();
        mFetchGeneration++;
        mHandler.removeCallbacks(mHideCurrent);
        hideBubble(true);
        super.onDetachedFromWindow();
    }

    private static OkHttpClient client() {
        OkHttpClient client = sClient;
        if (client == null) {
            synchronized (JukeboxPopupOverlay.class) {
                client = sClient;
                if (client == null) {
                    client = new OkHttpClient();
                    sClient = client;
                }
            }
        }
        return client;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Retro VH1-style bubble: yellow/white rounded body with a thick black outline, bold
     * black text and a small tail. Width is capped at ~40% of the screen.
     */
    private static final class BubbleView extends View {
        private static final int[] COLORS = {0xFFFBD84A, Color.WHITE};

        private final StaticLayout mLayout;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mTail = new Path();
        private final int mPadH;
        private final int mPadTop;
        private final int mBodyBottom;
        private final float mRadius;
        private final float mStrokeWidth;
        private final float mTailBase;
        private final float mTailWidth;
        private final float mTailTipX;
        private final float mTailHeight;
        private final int mTextWidth;

        BubbleView(Context context, String text, int colorIndex) {
            super(context);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(false);
            float density = getResources().getDisplayMetrics().density;
            float scaled = getResources().getDisplayMetrics().scaledDensity;
            TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.BLACK);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(18f * scaled);
            mStrokeWidth = Math.max(2f, 3f * density);
            mStroke.setColor(Color.BLACK);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(mStrokeWidth);
            mFill.setColor(COLORS[Math.abs(colorIndex) % COLORS.length]);
            mPadH = Math.round(12 * density);
            mPadTop = Math.round(10 * density);
            int padBottom = Math.round(8 * density);
            mRadius = 14f * density;
            mTailWidth = 14f * density;
            mTailHeight = 10f * density;
            mTailBase = mPadH + 6f * density;
            mTailTipX = mTailBase + mTailWidth * 0.3f;
            int maxTextWidth = Math.max(1,
                    Math.round(getResources().getDisplayMetrics().widthPixels * 0.40f) - 2 * mPadH);
            mLayout = new StaticLayout(text, textPaint, maxTextWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, true);
            float tight = 0f;
            for (int i = 0; i < mLayout.getLineCount(); i++) {
                tight = Math.max(tight, mLayout.getLineWidth(i));
            }
            mTextWidth = Math.min((int) Math.ceil(tight), maxTextWidth);
            mBodyBottom = mPadTop + mLayout.getHeight() + padBottom;
            setContentDescription(text);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int stroke = Math.round(mStrokeWidth);
            setMeasuredDimension(mTextWidth + 2 * mPadH + stroke,
                    mBodyBottom + Math.round(mTailHeight) + stroke);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            // Grow from the tail corner.
            setPivotX(mTailTipX);
            setPivotY(h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = mStrokeWidth / 2f;
            mRect.set(inset, inset, getWidth() - inset, mBodyBottom - inset);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mFill);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mStroke);
            // Tail: filled triangle whose base sits on the bubble bottom edge.
            float baseY = mBodyBottom - inset;
            mTail.reset();
            mTail.moveTo(mTailBase, baseY);
            mTail.lineTo(mTailTipX, mBodyBottom + mTailHeight - inset);
            mTail.lineTo(mTailBase + mTailWidth, baseY);
            mTail.close();
            canvas.drawPath(mTail, mFill);
            canvas.drawLines(new float[]{
                    mTailBase, baseY, mTailTipX, mBodyBottom + mTailHeight - inset,
                    mTailBase + mTailWidth, baseY, mTailTipX, mBodyBottom + mTailHeight - inset}, mStroke);
            canvas.save();
            canvas.translate(mPadH, mPadTop);
            mLayout.draw(canvas);
            canvas.restore();
        }
    }
}
