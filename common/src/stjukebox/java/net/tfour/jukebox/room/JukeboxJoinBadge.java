package net.tfour.jukebox.room;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small bottom-right join badge on the playback window. Not focusable, so it never
 * steals DPAD focus from the player. The panel background is ~0.75 alpha; the QR
 * itself stays opaque so it can be scanned.
 */
public final class JukeboxJoinBadge extends LinearLayout {
    public static final String TAG = "jukebox_join_badge";
    private static final int QR_SIZE_PX = 160;
    private static final String HOST = "jukebox.tfour.net";

    private final ImageView mQrView;
    private final TextView mCodeView;
    private String mBoundJoinUrl;

    private JukeboxJoinBadge(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        int pad = dp(activity, 8);
        setPadding(pad, pad, pad, pad);
        // ~0.75 alpha dark panel
        setBackgroundColor(Color.argb(Math.round(0.75f * 255), 14, 15, 19));

        mQrView = new ImageView(activity);
        mQrView.setFocusable(false);
        mQrView.setClickable(false);
        mQrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mQrView.setBackgroundColor(Color.WHITE);
        int qrPad = dp(activity, 4);
        mQrView.setPadding(qrPad, qrPad, qrPad, qrPad);
        mQrView.setContentDescription(HOST);
        addView(mQrView, new LayoutParams(QR_SIZE_PX, QR_SIZE_PX));

        mCodeView = new TextView(activity);
        mCodeView.setFocusable(false);
        mCodeView.setClickable(false);
        mCodeView.setTextColor(Color.WHITE);
        mCodeView.setTextSize(16);
        mCodeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mCodeView.setGravity(Gravity.CENTER);
        LayoutParams codeLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(activity, 4);
        addView(mCodeView, codeLp);

        TextView hostView = new TextView(activity);
        hostView.setFocusable(false);
        hostView.setClickable(false);
        hostView.setText(HOST);
        hostView.setTextColor(Color.WHITE);
        hostView.setTextSize(12);
        hostView.setGravity(Gravity.CENTER);
        LayoutParams hostLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = dp(activity, 2);
        addView(hostView, hostLp);
    }

    /** Adds the badge to the activity decor if missing, then refreshes code and QR. Idempotent. */
    public static void show(Activity activity, String code, String joinUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activity.getWindow() == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        FrameLayout frame = (FrameLayout) decor;
        View existing = frame.findViewWithTag(TAG);
        JukeboxJoinBadge badge;
        if (existing instanceof JukeboxJoinBadge) {
            badge = (JukeboxJoinBadge) existing;
        } else {
            if (existing != null) {
                frame.removeView(existing);
            }
            badge = new JukeboxJoinBadge(activity);
            badge.setTag(TAG);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            int margin = dp(activity, 16);
            lp.setMargins(margin, margin, margin, margin);
            frame.addView(badge, lp);
            badge.bringToFront();
        }
        badge.bind(code, joinUrl);
    }

    private void bind(String code, String joinUrl) {
        mCodeView.setText(code != null ? code : "");
        if (joinUrl != null && !joinUrl.equals(mBoundJoinUrl)) {
            mBoundJoinUrl = joinUrl;
            Bitmap qr = JukeboxQr.bitmap(joinUrl, QR_SIZE_PX);
            if (qr != null) {
                mQrView.setImageBitmap(qr);
            }
        }
    }

    private static int dp(Activity activity, int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
