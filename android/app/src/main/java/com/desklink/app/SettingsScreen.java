package com.desklink.app;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntFunction;

/**
 * Settings: one page, full screen from the main screen (SettingsActivity) and over the picture during a session,
 * where a change applies right away. Everything is kept in Prefs; the host is told what changed.
 */
final class SettingsScreen {
    interface Host {
        /** A setting changed (K_*) and is saved: apply it now. */
        void settingChanged(int key);

        /** The ← at the top. */
        void closeSettings();
    }

    static final int K_POINTER_SPEED = 1, K_INVERT_SCROLL = 2, K_PREDICTIVE = 3, K_PAD_LABELS = 4, K_PAD_COLORS = 5,
            K_QUALITY = 6, K_FULL_SCREEN = 7, K_STATS = 8, K_CLIPBOARD = 9, K_APP_LOCK = 10, K_PAD_TYPE = 11,
            K_CAMERA_SPEED = 12, K_MODS_HOLD = 14, K_GLIDE = 15, K_PAD_LINES = 16, K_PAD_OPACITY = 17,
            K_STICK_LOOK = 18, K_ARROW_COLOR = 19, K_NOTES = 20, K_SOUND = 21;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private interface OnToggle {
        void toggled(Switch s, boolean on);
    }

    private interface OnPick {
        void picked(int value);
    }

    /** The page. It has its own background and takes every touch, so nothing reaches what is under it. */
    final View view;
    private final Activity a;
    private final Prefs prefs;
    private final Host host;
    private final GamepadEditor editor;
    private final int text, dim, accent;
    private final LinearLayout col;

    SettingsScreen(Activity a, Host host, GamepadEditor editor) {
        this.a = a;
        this.host = host;
        this.editor = editor;
        prefs = new Prefs(a);
        text = Ui.color(a, R.color.text);
        dim = Ui.color(a, R.color.text_dim);
        accent = Ui.color(a, R.color.accent);

        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.color(a, R.color.bg));
        page.setClickable(true);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(16), dp(4));
        top.addView(Ui.iconButton(a, R.drawable.ic_arrow_back, text, a.getString(R.string.back), v -> host.closeSettings()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.setMarginStart(dp(8));
        top.addView(Ui.text(a, a.getString(R.string.settings), 20, text, true), tl);
        page.addView(top);

        ScrollView scroll = new ScrollView(a);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(32));
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        Ui.padForInsets(page, 0);
        view = page;
        build();
    }

    /** Shows the saved settings again (they may have changed since the page was built). */
    void refresh() {
        col.removeAllViews();
        build();
    }

    private void build() {
        LinearLayout card = section(R.string.set_pointer);
        sliderRow(card, R.string.pointer_speed, R.string.set_pointer_speed_sum, 1, Prefs.POINTER_SPEED_LEVELS,
                Prefs.POINTER_SPEED_DEFAULT, prefs.pointerSpeed(), R.string.pointer_speed_slower,
                R.string.pointer_speed_faster, outOf(Prefs.POINTER_SPEED_LEVELS), level -> {
                    prefs.setPointerSpeed(level);
                    host.settingChanged(K_POINTER_SPEED);
                });
        divider(card);
        sliderRow(card, R.string.set_glide, R.string.set_glide_sum, 0, Prefs.GLIDE_LEVELS, Prefs.GLIDE_DEFAULT,
                prefs.glide(), R.string.set_less, R.string.set_more,
                n -> n == 0 ? a.getString(R.string.set_labels_off) : a.getString(R.string.pointer_speed_value, n, Prefs.GLIDE_LEVELS),
                level -> {
                    prefs.setGlide(level);
                    host.settingChanged(K_GLIDE);
                });
        divider(card);
        switchRow(card, R.string.menu_invert_scroll, a.getString(R.string.set_invert_sum), prefs.invertScroll(), (s, on) -> {
            prefs.setInvertScroll(on);
            host.settingChanged(K_INVERT_SCROLL);
        });

        card = section(R.string.set_keyboard);
        switchRow(card, R.string.menu_predictive, a.getString(R.string.set_predictive_sum), prefs.predictiveText(), (s, on) -> {
            prefs.setPredictiveText(on);
            host.settingChanged(K_PREDICTIVE);
        });
        divider(card);
        radioRow(card, R.string.set_mods, R.string.set_mods_sum, new int[] {R.string.set_mods_toggle, R.string.set_mods_hold},
                new int[] {0, 1}, prefs.modsHoldOnly() ? 1 : 0, v -> {
                    prefs.setModsHoldOnly(v == 1);
                    host.settingChanged(K_MODS_HOLD);
                });

        card = section(R.string.set_gaming);
        radioRow(card, R.string.set_pad_type, 0, new int[] {R.string.set_pad_dualshock, R.string.set_pad_digital},
                new int[] {Prefs.PAD_DUALSHOCK, Prefs.PAD_DIGITAL}, prefs.padType(), v -> {
                    prefs.setPadType(v);
                    host.settingChanged(K_PAD_TYPE);
                });
        divider(card);
        sliderRow(card, R.string.set_camera_speed, R.string.set_camera_speed_sum, 1, Prefs.CAMERA_SPEED_LEVELS,
                Prefs.CAMERA_SPEED_DEFAULT, prefs.cameraSpeed(), R.string.pointer_speed_slower,
                R.string.pointer_speed_faster, outOf(Prefs.CAMERA_SPEED_LEVELS), level -> {
                    prefs.setCameraSpeed(level);
                    host.settingChanged(K_CAMERA_SPEED);
                });
        divider(card);
        radioRow(card, R.string.set_labels, 0,
                new int[] {R.string.set_labels_always, R.string.set_labels_start, R.string.set_labels_off},
                new int[] {Prefs.LABELS_ALWAYS, Prefs.LABELS_ON_START, Prefs.LABELS_OFF}, prefs.padLabels(), v -> {
                    prefs.setPadLabels(v);
                    host.settingChanged(K_PAD_LABELS);
                });
        divider(card);
        sliderRow(card, R.string.set_lines, R.string.set_lines_sum, 1, Prefs.LINE_LEVELS, Prefs.LINE_DEFAULT,
                prefs.padLines(), R.string.set_thinner, R.string.set_as_drawn, outOf(Prefs.LINE_LEVELS), level -> {
                    prefs.setPadLines(level);
                    host.settingChanged(K_PAD_LINES);
                });
        divider(card);
        sliderRow(card, R.string.set_opacity, R.string.set_opacity_sum, Prefs.OPACITY_MIN, Prefs.OPACITY_MAX,
                Prefs.OPACITY_MAX, prefs.padOpacity(), R.string.set_see_through, R.string.set_solid,
                n -> a.getString(R.string.set_percent, n * 10), level -> {
                    prefs.setPadOpacity(level);
                    host.settingChanged(K_PAD_OPACITY);
                });
        divider(card);
        radioRow(card, R.string.set_stick_look, 0,
                new int[] {R.string.set_stick_full, R.string.set_stick_ring, R.string.set_stick_ball},
                new int[] {Prefs.STICK_FULL, Prefs.STICK_RING, Prefs.STICK_BALL}, prefs.stickLook(), v -> {
                    prefs.setStickLook(v);
                    host.settingChanged(K_STICK_LOOK);
                });
        divider(card);
        swatchRow(card, R.string.set_arrow_color, R.string.set_arrow_color_sum, Prefs.ARROW_COLORS, prefs.arrowColor(), c -> {
            prefs.setArrowColor(c);
            host.settingChanged(K_ARROW_COLOR);
        });
        divider(card);
        switchRow(card, R.string.set_colors, colorsSummary(), prefs.padColors(), (s, on) -> {
            prefs.setPadColors(on);
            host.settingChanged(K_PAD_COLORS);
        });
        divider(card);
        linkRow(card, R.string.set_profiles, R.string.set_profiles_sum, v -> editor.showProfiles());

        card = section(R.string.set_picture);
        radioRow(card, R.string.menu_quality, R.string.set_quality_sum,
                new int[] {R.string.quality_auto, R.string.quality_high, R.string.quality_balanced, R.string.quality_saver},
                new int[] {Prefs.QUALITY_AUTO, Prefs.QUALITY_HIGH, Prefs.QUALITY_BALANCED, Prefs.QUALITY_SAVER},
                prefs.quality(), v -> {
                    prefs.setQuality(v);
                    host.settingChanged(K_QUALITY);
                });
        divider(card);
        switchRow(card, R.string.menu_full_screen, a.getString(R.string.set_full_screen_sum), prefs.fullScreen(), (s, on) -> {
            prefs.setFullScreen(on);
            host.settingChanged(K_FULL_SCREEN);
        });
        divider(card);
        switchRow(card, R.string.menu_stats, a.getString(R.string.set_stats_sum), prefs.showStats(), (s, on) -> {
            prefs.setShowStats(on);
            host.settingChanged(K_STATS);
        });
        divider(card);
        switchRow(card, R.string.set_notes, a.getString(R.string.set_notes_sum), prefs.tapNotes(), (s, on) -> {
            prefs.setTapNotes(on);
            host.settingChanged(K_NOTES);
        });

        card = section(R.string.set_sound);
        switchRow(card, R.string.set_sound_play, a.getString(R.string.set_sound_sum), prefs.sound(), (s, on) -> {
            prefs.setSound(on);
            host.settingChanged(K_SOUND);
        });

        card = section(R.string.set_clipboard);
        switchRow(card, R.string.menu_clipboard, a.getString(R.string.set_clipboard_sum), prefs.clipboardSync(), (s, on) -> {
            prefs.setClipboardSync(on);
            host.settingChanged(K_CLIPBOARD);
        });

        if (AppLock.supported()) { // Android 10 and later
            card = section(R.string.set_security);
            switchRow(card, R.string.menu_app_lock, a.getString(R.string.set_app_lock_sum), prefs.appLock(), (s, on) -> {
                if (on == prefs.appLock()) return;
                if (on && !AppLock.canLock(a)) {
                    Toast.makeText(a, R.string.lock_need_screen_lock, Toast.LENGTH_LONG).show();
                    s.setChecked(false);
                    return;
                }
                prefs.setAppLock(on);
                if (on) {
                    AppLock.enabledNow(); // not right now: the next time DeskLink is opened
                    Toast.makeText(a, R.string.lock_on, Toast.LENGTH_LONG).show();
                }
                host.settingChanged(K_APP_LOCK);
            });
        }

        card = section(R.string.set_help);
        linkRow(card, R.string.menu_help, R.string.set_gestures_sum,
                v -> Sheet.textPage(a, a.getString(R.string.menu_help), a.getString(R.string.help_text)));

        TextView ver = Ui.text(a, a.getString(R.string.set_version, versionName()), 12, dim, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(24), 0, 0);
        col.addView(ver, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    // ------------------------------------------------------------------ rows

    /** A section: its name, then a rounded card that holds its rows. */
    private LinearLayout section(int title) {
        TextView h = Ui.text(a, a.getString(title), 13, accent, true);
        h.setPadding(dp(4), dp(22), dp(4), dp(8));
        col.addView(h);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(Ui.color(a, R.color.surface), dp(16)));
        card.setClipToOutline(true); // the rows' touch ripples keep the rounded corners
        col.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));
        return card;
    }

    private void divider(LinearLayout card) {
        View d = new View(a);
        d.setBackgroundColor(Ui.color(a, R.color.stroke));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
        lp.setMarginStart(dp(16));
        card.addView(d, lp);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Ui.ripple(new ColorDrawable(Color.TRANSPARENT), 0));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private LinearLayout texts(String title, CharSequence summary) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(a, title, 16, text, false));
        if (summary != null && summary.length() > 0) {
            TextView s = Ui.text(a, summary, 13, dim, false);
            s.setPadding(0, dp(2), 0, 0);
            t.addView(s);
        }
        return t;
    }

    /** A switch; touching anywhere on the row flips it. */
    private void switchRow(LinearLayout card, int title, CharSequence summary, boolean on, OnToggle l) {
        LinearLayout row = row();
        row.addView(texts(a.getString(title), summary), new LinearLayout.LayoutParams(0, WRAP, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setContentDescription(a.getString(title));
        sw.setOnCheckedChangeListener((b, v) -> l.toggled(sw, v));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMarginStart(dp(12));
        row.addView(sw, lp);
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
    }

    /** Opens something (a list of profiles, the gestures). */
    private void linkRow(LinearLayout card, int title, int summary, View.OnClickListener l) {
        LinearLayout row = row();
        row.addView(texts(a.getString(title), a.getString(summary)), new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageView chev = new ImageView(a);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setImageTintList(ColorStateList.valueOf(dim));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.setMarginStart(dp(12));
        row.addView(chev, lp);
        row.setOnClickListener(l);
        card.addView(row);
    }

    /** One choice out of a few, all on show. */
    private void radioRow(LinearLayout card, int title, int summary, int[] labels, int[] values, int current, OnPick l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(6));
        box.addView(texts(a.getString(title), summary != 0 ? a.getString(summary) : null));
        RadioGroup g = new RadioGroup(a);
        g.setOrientation(RadioGroup.VERTICAL);
        ColorStateList tint = new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {accent, dim});
        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(a);
            rb.setId(View.generateViewId());
            ids[i] = rb.getId();
            rb.setText(labels[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            rb.setTextColor(text);
            rb.setButtonTintList(tint);
            rb.setMinHeight(dp(44));
            rb.setPaddingRelative(dp(8), 0, 0, 0);
            g.addView(rb, new RadioGroup.LayoutParams(MATCH, WRAP));
            if (values[i] == current) g.check(ids[i]);
        }
        g.setOnCheckedChangeListener((group, id) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) l.picked(values[i]);
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(MATCH, WRAP);
        glp.topMargin = dp(4);
        box.addView(g, glp);
        card.addView(box);
    }

    /**
     * A level from {@code min} to {@code max}: a slider, its value as {@code show} writes it (the default marked),
     * the names of its two ends, and a button back to the default.
     */
    private void sliderRow(LinearLayout card, int title, int summary, final int min, final int max, final int def,
                           int current, int lowEnd, int highEnd, final IntFunction<String> show, final OnPick l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(a, a.getString(title), 16, text, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView value = Ui.text(a, "", 14, accent, true);
        head.addView(value);
        box.addView(head);

        final SeekBar seek = new SeekBar(a);
        seek.setMax(max - min);
        seek.setProgress(current - min);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        seek.setContentDescription(a.getString(title));
        levelLabel(value, current, def, show);
        final int[] last = {current};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int level = progress + min;
                levelLabel(value, level, def, show);
                if (level == last[0]) return;
                last[0] = level;
                l.picked(level);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, dp(40));
        slp.topMargin = dp(4);
        box.addView(seek, slp);

        LinearLayout ends = new LinearLayout(a);
        ends.addView(Ui.text(a, a.getString(lowEnd), 12, dim, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        ends.addView(Ui.text(a, a.getString(highEnd), 12, dim, false));
        box.addView(ends);

        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(6), 0, 0);
        foot.addView(Ui.text(a, a.getString(summary), 13, dim, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView reset = Ui.text(a, a.getString(R.string.reset_default), 14, accent, true);
        reset.setPadding(dp(12), dp(8), dp(4), dp(8));
        reset.setBackground(Ui.ripple(Ui.rounded(Color.TRANSPARENT, dp(8)), dp(8)));
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setOnClickListener(v -> seek.setProgress(def - min));
        foot.addView(reset);
        box.addView(foot);
        card.addView(box);
    }

    private void levelLabel(TextView v, int level, int def, IntFunction<String> show) {
        String s = show.apply(level);
        v.setText(level == def ? a.getString(R.string.set_value_default, s) : s);
    }

    /** "3 / 9". */
    private IntFunction<String> outOf(final int max) {
        return n -> a.getString(R.string.pointer_speed_value, n, max);
    }

    /** A color picked from a few dots; the one in use has a ring. */
    private void swatchRow(LinearLayout card, int title, int summary, final int[] colors, int current, final OnPick l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.addView(texts(a.getString(title), a.getString(summary)));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final View[] dots = new View[colors.length];
        for (int i = 0; i < colors.length; i++) {
            final int col = colors[i];
            FrameLayout cell = new FrameLayout(a);
            View dot = new View(a);
            dots[i] = dot;
            dot.setBackground(swatch(col, col == current));
            dot.setContentDescription(a.getString(R.string.set_color_n, i + 1));
            dot.setClickable(true);
            dot.setFocusable(true);
            dot.setOnClickListener(v -> {
                for (int k = 0; k < dots.length; k++) dots[k].setBackground(swatch(colors[k], dots[k] == v));
                l.picked(col);
            });
            int s = dp(32);
            cell.addView(dot, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(8);
        box.addView(row, rl);
        card.addView(box);
    }

    private android.graphics.drawable.GradientDrawable swatch(int color, boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        if (on) g.setStroke(dp(3), text);
        else g.setStroke(dp(1), Ui.color(a, R.color.stroke));
        return g;
    }

    /** "△ ○ ✕ □ in color…", each sign in its own color. */
    private CharSequence colorsSummary() {
        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] signs = {"△", "○", "✕", "□"}; // Gamepad.TRIANGLE … SQUARE, like GamepadControls.FACE_COLORS
        for (int i = 0; i < signs.length; i++) {
            int start = b.length();
            b.append(signs[i]);
            b.setSpan(new ForegroundColorSpan(GamepadControls.FACE_COLORS[i]), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(i < signs.length - 1 ? "  " : "   ");
        }
        b.append(a.getString(R.string.set_colors_sum));
        return b;
    }

    @SuppressWarnings("deprecation")
    private String versionName() {
        try {
            PackageManager pm = a.getPackageManager();
            PackageInfo pi = Build.VERSION.SDK_INT >= 33
                    ? pm.getPackageInfo(a.getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : pm.getPackageInfo(a.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int dp(float v) { return Ui.dp(a, v); }
}
