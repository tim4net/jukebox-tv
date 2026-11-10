package net.tfour.jukebox.room;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Lower-third card panel for dedications and "now playing" trivia notes
 * (stjukebox flavor only), in the spirit of {@link JukeboxPopupOverlay} but much
 * simpler: a semi-transparent dark panel at the bottom-left of the activity decor,
 * white text, at most 2 lines per line pair. Not focusable and never taking key
 * events, it never pauses or touches the player. {@link #show(Activity, List)}
 * runs the planned cards in sequence on the main handler (postDelayed is
 * uptime-based, i.e. monotonic) and removes the panel after the last card;
 * {@link #dismiss(Activity)} removes it and cancels pending callbacks. Tagged
 * like the badge/overlay, so a second show replaces the first (one panel per
 * activity).
 */
public final class JukeboxLowerThird extends LinearLayout {
    public static final String TAG = "jukebox_lower_third";
    private static final String LOG_TAG = "JukeboxLowerThird";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final TextView mTitleView;
    private final TextView mBodyView;
    private List<JukeboxLowerThirdPlan.Card> mCards;
    private int mCardIndex;

    private JukeboxLowerThird(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);
        setGravity(Gravity.START);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        // Semi-transparent dark lower-third.
        setBackgroundColor(Color.argb(Math.round(0.70f * 255), 0, 0, 0));
        mTitleView = textView(activity, 20, true);
        addView(mTitleView);
        mBodyView = textView(activity, 16, false);
        addView(mBodyView);
    }

    /**
     * Adds (or reuses) the one lower-third panel of this activity and runs the
     * cards in sequence. A second show replaces the first. No-ops for a
     * finishing/destroyed activity, no window, a non-FrameLayout decor or an
     * empty plan.
     */
    public static void show(Activity activity, List<JukeboxLowerThirdPlan.Card> cards) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            JukeboxLog.i(LOG_TAG, "skip: activity gone");
            return;
        }
        if (activity.getWindow() == null || cards == null || cards.isEmpty()) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            JukeboxLog.i(LOG_TAG, "skip: decor not FrameLayout");
            return;
        }
        FrameLayout frame = (FrameLayout) decor;
        View existing = frame.findViewWithTag(TAG);
        JukeboxLowerThird panel;
        if (existing instanceof JukeboxLowerThird) {
            panel = (JukeboxLowerThird) existing;
        } else {
            if (existing != null) {
                frame.removeView(existing);
            }
            panel = new JukeboxLowerThird(activity);
            panel.setTag(TAG);
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            // Bottom-left with ~8% screen margin, away from the bottom-right badge.
            int marginH = Math.round(metrics.widthPixels * 0.08f);
            int marginV = Math.round(metrics.heightPixels * 0.08f);
            lp.setMargins(marginH, marginV, marginH, marginV);
            frame.addView(panel, lp);
            panel.bringToFront();
            JukeboxLog.i(LOG_TAG, "attach cards=" + cards.size());
        }
        panel.run(cards);
    }

    /** Removes the panel and cancels its pending callbacks. Idempotent. */
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
        if (existing instanceof JukeboxLowerThird) {
            ((JukeboxLowerThird) existing).cancelSequence();
        }
        if (existing != null && existing.getParent() == frame) {
            frame.removeView(existing);
        }
    }

    private void run(List<JukeboxLowerThirdPlan.Card> cards) {
        cancelSequence();
        mCards = cards;
        mCardIndex = -1;
        next();
    }

    private void cancelSequence() {
        mHandler.removeCallbacksAndMessages(null);
        mCards = null;
    }

    private void next() {
        List<JukeboxLowerThirdPlan.Card> cards = mCards;
        if (cards == null) {
            return; // cancelled/dismissed
        }
        mCardIndex++;
        if (mCardIndex >= cards.size()) {
            JukeboxLog.i(LOG_TAG, "sequence done, removing panel");
            removeFromParent();
            return;
        }
        JukeboxLowerThirdPlan.Card card = cards.get(mCardIndex);
        mTitleView.setText(card.title);
        if (card.text != null) {
            mBodyView.setText(card.text);
            mBodyView.setVisibility(VISIBLE);
        } else {
            mBodyView.setText("");
            mBodyView.setVisibility(GONE);
        }
        requestLayout();
        invalidate();
        // postDelayed is uptime-based (monotonic): never skewed by wall-clock changes.
        mHandler.postDelayed(this::next, card.durationMs);
    }

    private void removeFromParent() {
        cancelSequence();
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelSequence();
        super.onDetachedFromWindow();
    }

    private static TextView textView(Context context, float sizeSp, boolean bold) {
        TextView view = new TextView(context);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setClickable(false);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Title line currently shown (test/inspection seam). */
    public CharSequence getTitleText() {
        return mTitleView.getText();
    }

    /** Second line currently shown, empty for a title-only card (test/inspection seam). */
    public CharSequence getBodyText() {
        return mBodyView.getText();
    }
}
