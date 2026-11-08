package com.desklink.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.TextView;

/** Small programmatic-UI helpers (no AndroidX). */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static GradientDrawable rounded(int fill, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable roundedStroke(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = rounded(fill, radiusPx);
        g.setStroke(strokePx, stroke);
        return g;
    }

    static GradientDrawable circle(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    /** Ripple on top of {@code content}, clipped to a rounded rectangle of {@code radiusPx}. */
    static Drawable ripple(Drawable content, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, radiusPx));
    }

    static Drawable borderlessRipple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static ImageButton iconButton(Context c, int iconRes, int tint, CharSequence description, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(iconRes);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setBackground(borderlessRipple());
        b.setContentDescription(description);
        int p = dp(c, 10);
        b.setPadding(p, p, p, p);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(description);
        b.setOnClickListener(l);
        return b;
    }

    /** Pill button. primary = filled accent, else outlined. */
    static TextView button(Context c, CharSequence label, boolean primary, View.OnClickListener l) {
        TextView b = text(c, label, 15, primary ? Color.WHITE : color(c, R.color.text), true);
        b.setGravity(Gravity.CENTER);
        int h = dp(c, 12), w = dp(c, 20);
        b.setPadding(w, h, w, h);
        b.setMinHeight(dp(c, 48));
        GradientDrawable bg = primary
                ? rounded(color(c, R.color.accent), dp(c, 24))
                : roundedStroke(Color.TRANSPARENT, color(c, R.color.stroke), dp(c, 1), dp(c, 24));
        b.setBackground(ripple(bg, dp(c, 24)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }


    /** Lets the window draw behind the system bars on every API level (API 35 does this by default). */
    static void edgeToEdge(Activity a) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** System bar + keyboard insets in pixels. */
    static final class Bars {
        int left, top, right, bottom;
        int ime;
        boolean imeVisible;
    }

    @SuppressWarnings("deprecation")
    static Bars bars(WindowInsets in, Context c) {
        Bars b = new Bars();
        if (Build.VERSION.SDK_INT >= 30) {
            Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = in.getInsets(WindowInsets.Type.ime());
            b.left = s.left;
            b.top = s.top;
            b.right = s.right;
            b.bottom = s.bottom;
            b.imeVisible = in.isVisible(WindowInsets.Type.ime()) && ime.bottom > 0;
            b.ime = b.imeVisible ? ime.bottom : 0;
        } else {
            b.left = in.getSystemWindowInsetLeft();
            b.top = in.getSystemWindowInsetTop();
            b.right = in.getSystemWindowInsetRight();
            int sys = in.getSystemWindowInsetBottom();
            int stable = in.getStableInsetBottom();
            b.bottom = stable;
            b.imeVisible = sys > stable + dp(c, 60);
            b.ime = b.imeVisible ? sys : 0;
        }
        return b;
    }

    /** Pads {@code v} so content avoids the system bars and the keyboard. */
    static void padForInsets(final View v, final int extraPx) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            Bars b = bars(insets, view.getContext());
            view.setPadding(b.left + extraPx, b.top + extraPx, b.right + extraPx, Math.max(b.bottom, b.ime) + extraPx);
            return insets;
        });
        v.requestApplyInsets();
    }
}
