package com.desklink.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * DeskLink's own menus and dialogs, in the app's look instead of Android's stock pop-ups: a card that slides up from
 * the bottom over a dimmed screen (menus, pickers), a card in the middle (questions), or a whole page that slides in
 * (profiles, a profile's buttons, help). It sits on top of the activity's content; Back closes the top one.
 */
final class Sheet {
    static final int BOTTOM = 0, CENTER = 1, PAGE = 2;
    /** Row tones. */
    static final int NORMAL = 0, DANGER = 1;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    final Activity a;
    private final int style;
    private final FrameLayout overlay;
    private final LinearLayout card;
    /** Where the content goes (it scrolls when it's taller than the screen). */
    final LinearLayout body;
    private LinearLayout pageBar;
    private Runnable onDismiss;
    private Object backCallback;
    private boolean dismissed;
    final int text, dim, accent, red, green;

    Sheet(Activity a, int style) {
        this.a = a;
        this.style = style;
        text = Ui.color(a, R.color.text);
        dim = Ui.color(a, R.color.text_dim);
        accent = Ui.color(a, R.color.accent);
        red = Ui.color(a, R.color.red);
        green = Ui.color(a, R.color.green);

        overlay = new FrameLayout(a);
        overlay.setTag(this); // how dismissTop finds it
        overlay.setClickable(true); // nothing under it gets touches
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(style == PAGE);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));

        int surface = Ui.color(a, R.color.surface);
        if (style == PAGE) {
            overlay.setBackgroundColor(Ui.color(a, R.color.bg));
            pageBar = new LinearLayout(a);
            pageBar.setGravity(Gravity.CENTER_VERTICAL);
            pageBar.setPadding(dp(4), dp(4), dp(8), dp(4));
            card.addView(pageBar, new LinearLayout.LayoutParams(MATCH, WRAP));
            body.setPadding(dp(16), 0, dp(16), dp(32));
            card.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            overlay.addView(card, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            overlay.setBackgroundColor(0x99000000);
            overlay.setOnClickListener(v -> dismiss()); // a touch outside the card closes it
            float r = dp(22);
            GradientDrawable g = new GradientDrawable();
            g.setColor(surface);
            if (style == BOTTOM) g.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
            else g.setCornerRadius(r);
            card.setBackground(g);
            card.setElevation(dp(16));
            if (style == BOTTOM) {
                View grip = new View(a); // the little bar at the top of a bottom sheet
                grip.setBackground(Ui.rounded(Ui.color(a, R.color.stroke), dp(2)));
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(36), dp(4));
                gl.gravity = Gravity.CENTER_HORIZONTAL;
                gl.topMargin = dp(10);
                gl.bottomMargin = dp(4);
                card.addView(grip, gl);
            }
            body.setPadding(0, dp(style == BOTTOM ? 4 : 18), 0, dp(10));
            card.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, dp(style == BOTTOM ? 560 : 420));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(style == BOTTOM ? width : width - dp(48), WRAP,
                    style == BOTTOM ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
            lp.topMargin = dp(style == BOTTOM ? 56 : 24);
            lp.bottomMargin = style == BOTTOM ? 0 : dp(24);
            overlay.addView(card, lp);
        }
        overlay.setOnApplyWindowInsetsListener((v, in) -> {
            Ui.Bars b = Ui.bars(in, a);
            int bottom = Math.max(b.bottom, b.ime);
            if (style == BOTTOM) {
                overlay.setPadding(b.left, b.top, b.right, 0);
                card.setPadding(0, 0, 0, bottom); // the card runs under the navigation bar, its rows stay above it
            } else {
                overlay.setPadding(b.left, b.top, b.right, bottom);
            }
            return in;
        });
    }

    int dp(float v) { return Ui.dp(a, v); }

    Sheet onDismiss(Runnable r) {
        onDismiss = r;
        return this;
    }

    /** A touch outside the card leaves it open (pairing: only its button, or Back, cancels). */
    Sheet keepOnOutsideTouch() {
        overlay.setOnClickListener(null);
        overlay.setClickable(true);
        return this;
    }

    // ------------------------------------------------------------------ showing

    Sheet show() {
        ViewGroup content = a.findViewById(android.R.id.content);
        content.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        overlay.requestApplyInsets();
        if (style == PAGE) {
            overlay.setAlpha(0f);
            overlay.setTranslationX(dp(32));
            overlay.animate().alpha(1f).translationX(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(150).start();
            card.setTranslationY(dp(style == BOTTOM ? 48 : 16));
            card.animate().translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // above the activity's own Back handling, and above sheets opened before this one
            android.window.OnBackInvokedCallback cb = this::dismiss;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            backCallback = cb;
        }
        return this;
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        }
        View f = overlay.findFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
        overlay.animate().cancel();
        card.animate().cancel();
        overlay.animate().alpha(0f).setDuration(130).withEndAction(() -> {
            ViewGroup p = (ViewGroup) overlay.getParent();
            if (p != null) p.removeView(overlay);
        }).start();
        if (onDismiss != null) onDismiss.run();
    }

    boolean isShowing() { return !dismissed && overlay.getParent() != null; }

    /** Closes the top sheet, if there is one (Back on Android 12 and older). */
    static boolean dismissTop(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return false;
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            Object t = content.getChildAt(i).getTag();
            if (t instanceof Sheet && !((Sheet) t).dismissed) {
                ((Sheet) t).dismiss();
                return true;
            }
        }
        return false;
    }

    /** Whether any sheet is open over this activity. */
    static boolean anyOpen(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return false;
        for (int i = 0; i < content.getChildCount(); i++) {
            Object t = content.getChildAt(i).getTag();
            if (t instanceof Sheet && !((Sheet) t).dismissed) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ parts

    /** A bottom or middle card's title (and a line under it). */
    Sheet title(CharSequence title, CharSequence sub) {
        TextView t = Ui.text(a, title, 18, text, true);
        t.setPadding(dp(22), dp(style == BOTTOM ? 8 : 0), dp(22), dp(sub != null ? 2 : 10));
        body.addView(t);
        if (sub != null) {
            TextView s = Ui.text(a, sub, 14, dim, false);
            s.setPadding(dp(22), 0, dp(22), dp(12));
            body.addView(s);
        }
        return this;
    }

    /** A page's top bar: ← and the title; actions (icon buttons) go at the end with {@link #barAction}. */
    Sheet pageTitle(CharSequence title) {
        ImageButton back = Ui.iconButton(a, R.drawable.ic_arrow_back, text, a.getString(R.string.back), v -> dismiss());
        pageBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = Ui.text(a, title, 20, text, true);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.setMarginStart(dp(8));
        pageBar.addView(t, tl);
        return this;
    }

    /** Changes a page's title (a profile was renamed). */
    void setPageTitle(CharSequence title) {
        if (pageBar != null && pageBar.getChildCount() > 1) ((TextView) pageBar.getChildAt(1)).setText(title);
    }

    ImageButton barAction(int icon, int tint, CharSequence description, View.OnClickListener l) {
        ImageButton b = Ui.iconButton(a, icon, tint, description, l);
        pageBar.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    /**
     * A menu row: icon, label, an optional line under it, a green check when it's the one in use, and an optional
     * button at the end (edit). Tapping the row runs {@code l}.
     */
    View row(int icon, CharSequence label, CharSequence sub, boolean checked, int tone, View.OnClickListener l) {
        return row(body, icon, label, sub, checked, tone, l, 0, null, null);
    }

    View row(ViewGroup into, int icon, CharSequence label, CharSequence sub, boolean checked, int tone,
             View.OnClickListener l, int endIcon, CharSequence endDescription, View.OnClickListener endClick) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(18), dp(8), dp(endIcon != 0 ? 6 : 18), dp(8));
        r.setBackground(Ui.ripple(new ColorDrawable(Color.TRANSPARENT), 0));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(l);
        int fg = tone == DANGER ? red : text;
        if (icon != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(icon);
            iv.setImageTintList(ColorStateList.valueOf(tone == DANGER ? red : checked ? accent : dim));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(24), dp(24));
            il.setMarginEnd(dp(18));
            r.addView(iv, il);
        }
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(a, label, 16, fg, checked);
        texts.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = Ui.text(a, sub, 13, dim, false);
            s.setPadding(0, dp(1), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (checked) {
            ImageView ok = new ImageView(a);
            ok.setImageResource(R.drawable.ic_check_circle);
            ok.setImageTintList(ColorStateList.valueOf(green));
            ok.setContentDescription(a.getString(R.string.in_use));
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(dp(22), dp(22));
            ol.setMarginStart(dp(12));
            r.addView(ok, ol);
        }
        if (endIcon != 0) {
            ImageButton e = Ui.iconButton(a, endIcon, dim, endDescription, endClick);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(dp(44), dp(44));
            el.setMarginStart(dp(6));
            r.addView(e, el);
        }
        into.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        return r;
    }

    /** A thin line between groups of rows. */
    void divider() {
        View d = new View(a);
        d.setBackgroundColor(Ui.color(a, R.color.stroke));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
        lp.setMargins(dp(18), dp(6), dp(18), dp(6));
        body.addView(d, lp);
    }

    /** A paragraph of text. */
    TextView paragraph(CharSequence s) {
        TextView t = Ui.text(a, s, 15, text, false);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(style == PAGE ? 4 : 22), dp(4), dp(style == PAGE ? 4 : 22), dp(8));
        body.addView(t);
        return t;
    }

    /** Buttons at the bottom: a quiet one (Cancel) and the main one, red for a delete. */
    void buttons(CharSequence quiet, View.OnClickListener quietClick, CharSequence main, int mainIcon, int tone,
                 View.OnClickListener mainClick) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(4));
        if (quiet != null) {
            TextView q = Ui.button(a, quiet, false, quietClick != null ? quietClick : v -> dismiss());
            row.addView(q, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        TextView m = Ui.button(a, main, true, mainClick);
        if (tone == DANGER) m.setBackground(Ui.ripple(Ui.rounded(red, dp(24)), dp(24)));
        if (mainIcon != 0) {
            android.graphics.drawable.Drawable d = a.getDrawable(mainIcon);
            if (d != null) {
                d.setTint(Color.WHITE);
                int s = dp(20);
                d.setBounds(0, 0, s, s);
                m.setCompoundDrawablesRelative(d, null, null, null);
                m.setCompoundDrawablePadding(dp(8));
            }
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(WRAP, WRAP);
        ml.setMarginStart(dp(10));
        row.addView(m, ml);
        body.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    // ------------------------------------------------------------------ ready-made

    /** A question with a main button (red for something that can't be undone). */
    static Sheet confirm(Activity a, CharSequence title, CharSequence message, CharSequence yes, int yesIcon, int tone,
                         Runnable onYes) {
        Sheet s = new Sheet(a, CENTER);
        s.title(title, null);
        if (message != null) s.paragraph(message).setTextColor(s.dim);
        s.buttons(a.getString(R.string.cancel), null, yes, yesIcon, tone, v -> {
            s.dismiss();
            onYes.run();
        });
        return s.show();
    }

    /** A message with OK. */
    static Sheet message(Activity a, CharSequence title, CharSequence message) {
        Sheet s = new Sheet(a, CENTER);
        if (title != null) s.title(title, null);
        s.paragraph(message);
        s.buttons(null, null, a.getString(R.string.ok), 0, NORMAL, v -> s.dismiss());
        return s.show();
    }

    /** A text field in the app's look: one line, a number, or several lines (addresses). */
    EditText field(CharSequence value, CharSequence hint, boolean multiLine, boolean number) {
        EditText e = new EditText(a);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(text);
        e.setHintTextColor(dim);
        e.setBackground(Ui.roundedStroke(Ui.color(a, R.color.surface2), Ui.color(a, R.color.stroke), dp(1), dp(12)));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setSelectAllOnFocus(!multiLine);
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setTypeface(Typeface.MONOSPACE);
        } else {
            if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setSingleLine(true);
            e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(MATCH, WRAP);
        el.setMargins(dp(18), dp(4), dp(18), dp(6));
        body.addView(e, el);
        return e;
    }

    /** Puts the cursor in a field and brings up the keyboard. */
    void focus(EditText e) {
        e.requestFocus();
        e.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && isShowing()) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** A name to type (one line, or several), with Save. */
    static Sheet input(Activity a, CharSequence title, CharSequence hint, String value, boolean multiLine,
                       CharSequence save, Consumer<String> onSave) {
        Sheet s = new Sheet(a, BOTTOM);
        s.title(title, hint);
        EditText e = s.field(value, null, multiLine, false);
        Runnable done = () -> {
            String v = e.getText().toString();
            s.dismiss();
            onSave.accept(v);
        };
        if (!multiLine) {
            e.setOnEditorActionListener((v, id, ev) -> {
                if (id != EditorInfo.IME_ACTION_DONE) return false;
                done.run();
                return true;
            });
        }
        s.buttons(a.getString(R.string.cancel), null, save, 0, NORMAL, v -> done.run());
        s.show();
        s.focus(e);
        return s;
    }

    /** A page of text (help). */
    static Sheet textPage(Activity a, CharSequence title, CharSequence text) {
        Sheet s = new Sheet(a, PAGE);
        s.pageTitle(title);
        s.paragraph(text);
        return s.show();
    }
}
