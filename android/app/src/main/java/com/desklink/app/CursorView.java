package com.desklink.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.desklink.core.Messages;

/** Draws the remote pointer locally so it moves without network latency. */
final class CursorView extends View {
    private Bitmap bitmap;
    private int hotX, hotY;
    private float x, y, scale = 1f;
    private boolean visible = true;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    CursorView(Context c) {
        super(c);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // classic arrow, 12 x 19 units, hotspot at 0,0
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17);
        arrow.lineTo(4, 13);
        arrow.lineTo(7, 19.5f);
        arrow.lineTo(9.5f, 18.5f);
        arrow.lineTo(6.5f, 12);
        arrow.lineTo(12, 12);
        arrow.close();
        setWillNotDraw(false);
    }

    void setShape(Messages.CursorShape s) {
        if (s == null || s.width <= 0 || s.height <= 0) {
            bitmap = null;
            invalidate();
            return;
        }
        int n = s.width * s.height;
        int[] px = new int[n];
        byte[] d = s.rgba;
        for (int i = 0; i < n; i++) {
            int r = d[i * 4] & 0xFF, g = d[i * 4 + 1] & 0xFF, b = d[i * 4 + 2] & 0xFF, a = d[i * 4 + 3] & 0xFF;
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        bitmap = Bitmap.createBitmap(px, s.width, s.height, Bitmap.Config.ARGB_8888);
        hotX = s.hotX;
        hotY = s.hotY;
        invalidate();
    }

    /** View coordinates of the hotspot and the scale (view px per cursor px). */
    void setPosition(float vx, float vy, float cursorScale) {
        if (vx == x && vy == y && cursorScale == scale) return;
        x = vx;
        y = vy;
        scale = cursorScale;
        invalidate();
    }

    void setCursorVisible(boolean v) {
        if (v == visible) return;
        visible = v;
        invalidate();
    }

    // touch and hold took: a dark ring pops up round the pointer and fades (grow, then fade; under the pointer)
    private static final long HOLD_GROW_MS = 110, HOLD_FADE_MS = 240;
    private final Paint holdFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holdRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.animation.ValueAnimator holdAnim;
    private float holdT = -1f; // 0 … 1 while it shows

    /** Touch and hold took (the button is down now): shown at the pointer, whether or not the arrow is drawn. */
    void showHold() {
        if (holdAnim != null) holdAnim.cancel();
        holdAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        holdAnim.setDuration(HOLD_GROW_MS + HOLD_FADE_MS);
        holdAnim.setInterpolator(null); // linear: the two phases are shaped in onDraw
        holdAnim.addUpdateListener(an -> {
            holdT = (float) an.getAnimatedValue();
            invalidate();
        });
        holdAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                holdT = -1f;
                invalidate();
            }
        });
        holdAnim.start();
    }

    private void drawHold(Canvas c) {
        float dp = getResources().getDisplayMetrics().density;
        float split = (float) HOLD_GROW_MS / (HOLD_GROW_MS + HOLD_FADE_MS);
        float grow = Math.min(1f, holdT / split);
        float fade = holdT <= split ? 1f : 1f - (holdT - split) / (1f - split);
        float r = 13 * dp * (0.3f + 0.7f * (1f - (1f - grow) * (1f - grow))); // eases out
        holdFill.setColor(Color.argb(Math.round(150 * fade), 30, 30, 30));
        holdRing.setStyle(Paint.Style.STROKE);
        holdRing.setStrokeWidth(1.5f * dp);
        holdRing.setColor(Color.argb(Math.round(200 * fade), 235, 235, 235));
        c.drawCircle(x, y, r, holdFill);
        c.drawCircle(x, y, r, holdRing);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (holdT >= 0f) drawHold(c);
        if (!visible) return;
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        if (bitmap != null) {
            c.drawBitmap(bitmap, -hotX, -hotY, bmpPaint);
        } else {
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
        }
        c.restore();
    }
}
