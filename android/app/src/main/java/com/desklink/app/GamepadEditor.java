package com.desklink.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.desklink.core.Vk;

import java.util.function.IntConsumer;

/**
 * Gaming profiles, in DeskLink's own pages: the list (tap a profile to use it, ✎ to change it, + for a new one), and
 * a profile laid out like a game's controller settings: every control as a tile with its icon and what it does; tap
 * one to pick a key or mouse button from a keyboard-like picker. Renaming and deleting (red, asked twice) are at the
 * top of the profile. New profiles start as a copy of the one in use.
 */
final class GamepadEditor {
    interface Listener {
        /** A profile changed (buttons, name), one was added or deleted, or another one is in use: redraw the gamepad. */
        void profilesChanged();
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Activity a;
    private final Gamepad store;
    private final Listener listener;
    private final Prefs prefs;
    private Sheet list;              // the profiles page, while it's open
    private LinearLayout listRows;

    GamepadEditor(Activity a, Gamepad store, Listener listener) {
        this.a = a;
        this.store = store;
        this.listener = listener;
        prefs = new Prefs(a);
    }

    private int dp(float v) { return Ui.dp(a, v); }

    private int color(int res) { return Ui.color(a, res); }

    // ------------------------------------------------------------------ the profiles

    /** The profiles, in a card that slides up (no top bar): tap one to use it, ✎ to change it, New profile to add one. */
    void showProfiles() {
        if (list != null && list.isShowing()) return;
        list = new Sheet(a, Sheet.BOTTOM);
        list.title(a.getString(R.string.gp_profiles), a.getString(R.string.gp_profiles_hint));
        listRows = new LinearLayout(a);
        listRows.setOrientation(LinearLayout.VERTICAL);
        list.body.addView(listRows, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView add = Ui.button(a, a.getString(R.string.gp_new_profile), true, v -> newProfile());
        withIcon(add, R.drawable.ic_add);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(WRAP, WRAP);
        al.topMargin = dp(14);
        al.bottomMargin = dp(6);
        al.gravity = Gravity.CENTER_HORIZONTAL;
        list.body.addView(add, al);
        list.onDismiss(() -> {
            list = null;
            listRows = null;
        });
        fillProfiles();
        list.show();
    }

    private void fillProfiles() {
        if (list == null || listRows == null) return;
        listRows.removeAllViews();
        Gamepad.Profile active = store.active();
        boolean first = true;
        for (final Gamepad.Profile p : store.profiles()) {
            if (!first) rowDivider(listRows);
            first = false;
            list.row(listRows, R.drawable.ic_gamepad, p.name, summary(p), p == active, Sheet.NORMAL, v -> {
                store.setActive(p); // this one is in use now
                listener.profilesChanged();
                fillProfiles();
            }, R.drawable.ic_edit, a.getString(R.string.gp_edit, p.name), v -> editProfile(p));
        }
    }

    /** "W A S D · ✕ Space": the stick's keys and what ✕ does. */
    private String summary(Gamepad.Profile p) {
        StringBuilder b = new StringBuilder();
        for (int k : new int[] {Gamepad.UP, Gamepad.LEFT, Gamepad.DOWN, Gamepad.RIGHT}) {
            if (b.length() > 0) b.append(' ');
            b.append(p.bind[k] == Gamepad.NONE ? "–" : Gamepad.actionName(a, p.bind[k]));
        }
        return b + "  ·  ✕ " + Gamepad.actionName(a, p.bind[Gamepad.CROSS]);
    }

    // ------------------------------------------------------------------ one profile

    void editProfile(final Gamepad.Profile p) {
        final Sheet page = new Sheet(a, Sheet.PAGE);
        page.pageTitle(p.name);
        page.barAction(R.drawable.ic_edit, color(R.color.text), a.getString(R.string.rename), v -> rename(p, page));
        page.barAction(R.drawable.ic_delete, color(R.color.red), a.getString(R.string.gp_delete), v -> confirmDelete(p, page));
        TextView hint = Ui.text(a, a.getString(R.string.gp_controls_hint), 13, color(R.color.text_dim), false);
        hint.setPadding(dp(4), dp(4), dp(4), 0);
        page.body.addView(hint);
        final LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        page.body.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        fillControls(p, grid);
        page.onDismiss(this::fillProfiles); // the list shows the new name, or loses a deleted profile
        page.show();
    }

    private void fillControls(Gamepad.Profile p, LinearLayout grid) {
        grid.removeAllViews();
        int cols = a.getResources().getDisplayMetrics().widthPixels >= dp(600) ? 4 : 2;
        group(grid, p, R.string.gp_group_move, new int[] {Gamepad.UP, Gamepad.DOWN, Gamepad.LEFT, Gamepad.RIGHT}, cols);
        group(grid, p, R.string.gp_group_buttons,
                new int[] {Gamepad.TRIANGLE, Gamepad.CIRCLE, Gamepad.CROSS, Gamepad.SQUARE}, cols);
        group(grid, p, R.string.gp_group_shoulders, new int[] {Gamepad.L1, Gamepad.L2, Gamepad.R1, Gamepad.R2}, cols);
        group(grid, p, R.string.gp_group_middle, new int[] {Gamepad.SELECT, Gamepad.START, Gamepad.L3, Gamepad.R3}, cols);
    }

    private void group(LinearLayout grid, Gamepad.Profile p, int title, int[] controls, int cols) {
        TextView h = Ui.text(a, a.getString(title), 13, color(R.color.accent), true);
        h.setPadding(dp(4), dp(20), dp(4), dp(8));
        grid.addView(h);
        LinearLayout row = null;
        for (int i = 0; i < controls.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                grid.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            tl.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(tile(p, controls[i], grid), tl);
        }
    }

    /** A control: its icon, what it does (big) and its name (small). */
    private View tile(final Gamepad.Profile p, final int control, final LinearLayout grid) {
        LinearLayout t = new LinearLayout(a);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(dp(72));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(Ui.ripple(Ui.rounded(color(R.color.surface), dp(16)), dp(16)));
        t.setClickable(true);
        t.setFocusable(true);
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(PadIcons.icon(a, control, prefs.padColors(), prefs.arrowColor()));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(42), dp(42));
        il.setMarginEnd(dp(12));
        t.addView(icon, il);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        int action = p.bind[control];
        boolean none = action == Gamepad.NONE;
        TextView what = Ui.text(a, none ? "—" : Gamepad.actionName(a, action), 16,
                none ? color(R.color.text_dim) : color(R.color.text), true);
        what.setMaxLines(1);
        what.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(what);
        TextView name = Ui.text(a, Gamepad.controlName(a, control), 12, color(R.color.text_dim), false);
        name.setMaxLines(1);
        texts.addView(name);
        t.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        t.setContentDescription(Gamepad.controlName(a, control) + ", " + (none ? a.getString(R.string.gp_none) : Gamepad.actionName(a, action)));
        t.setOnClickListener(v -> pickAction(p, control, () -> fillControls(p, grid)));
        return t;
    }

    // ------------------------------------------------------------------ the key picker

    private void pickAction(final Gamepad.Profile p, final int control, final Runnable changed) {
        final Sheet s = new Sheet(a, Sheet.BOTTOM);
        s.title(Gamepad.controlName(a, control), a.getString(R.string.gp_pick_sub));
        final int cur = p.bind[control];
        IntConsumer pick = action -> {
            s.dismiss();
            store.bind(p, control, action);
            listener.profilesChanged();
            changed.run();
        };
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), dp(6));
        int m = Gamepad.MOUSE;
        section(box, R.string.gp_keys_mouse, cur, pick, new int[][] {{m + 1, m + 2, m + 3}, {m + 4, m + 5}});
        section(box, R.string.gp_keys_common, cur, pick, new int[][] {
                {Vk.SPACE, Vk.RETURN, Vk.ESCAPE}, {Vk.TAB, Vk.BACK, Vk.CAPITAL}, {Vk.SHIFT, Vk.CONTROL, Vk.MENU}});
        section(box, R.string.gp_keys_arrows, cur, pick, new int[][] {{Vk.UP, Vk.DOWN, Vk.LEFT, Vk.RIGHT}});
        section(box, R.string.gp_keys_letters, cur, pick, new int[][] {
                chars("QWERTYUIOP"), chars("ASDFGHJKL"), chars("ZXCVBNM")});
        section(box, R.string.gp_keys_numbers, cur, pick, new int[][] {chars("1234567890")});
        int[] f1 = new int[6], f2 = new int[6];
        for (int i = 0; i < 6; i++) {
            f1[i] = Vk.F1 + i;
            f2[i] = Vk.F1 + 6 + i;
        }
        section(box, R.string.gp_keys_f, cur, pick, new int[][] {f1, f2});
        section(box, R.string.gp_keys_more, cur, pick, new int[][] {
                {Vk.INSERT, Vk.DELETE, Vk.HOME}, {Vk.END, Vk.PRIOR, Vk.NEXT}});
        // nothing at all: the control is drawn faint and does nothing
        TextView clear = keycap(Gamepad.NONE, cur, pick);
        clear.setText(R.string.gp_none);
        if (cur != Gamepad.NONE) {
            clear.setTextColor(color(R.color.red));
            clear.setBackground(Ui.ripple(Ui.roundedStroke(Color.TRANSPARENT, color(R.color.red), dp(1), dp(10)), dp(10)));
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(MATCH, dp(46));
        cl.setMargins(dp(3), dp(16), dp(3), 0);
        box.addView(clear, cl);
        s.body.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));
        s.show();
    }

    private static int[] chars(String s) {
        int[] k = new int[s.length()];
        for (int i = 0; i < k.length; i++) k[i] = s.charAt(i);
        return k;
    }

    private void section(LinearLayout box, int title, int cur, IntConsumer pick, int[][] rows) {
        TextView h = Ui.text(a, a.getString(title), 12, color(R.color.text_dim), true);
        h.setPadding(dp(4), dp(12), dp(4), dp(6));
        box.addView(h);
        for (int[] keys : rows) {
            LinearLayout row = new LinearLayout(a);
            for (int k : keys) {
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, dp(46), 1f);
                kl.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(keycap(k, cur, pick), kl);
            }
            box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    /** One key of the picker; the one in use now is lit. */
    private TextView keycap(final int action, int cur, final IntConsumer pick) {
        boolean on = action == cur;
        TextView k = Ui.text(a, Gamepad.actionName(a, action), 14, on ? Color.WHITE : color(R.color.text), true);
        k.setGravity(Gravity.CENTER);
        k.setMaxLines(1);
        k.setPadding(dp(2), 0, dp(2), 0);
        k.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        k.setBackground(Ui.ripple(Ui.rounded(on ? color(R.color.accent) : color(R.color.key_bg), dp(10)), dp(10)));
        k.setClickable(true);
        k.setFocusable(true);
        k.setOnClickListener(v -> pick.accept(action));
        return k;
    }

    // ------------------------------------------------------------------ new, rename, delete

    private void newProfile() {
        final Gamepad.Profile from = store.active();
        final String def = a.getString(R.string.gp_new_name, store.profiles().size() + 1);
        Sheet.input(a, a.getString(R.string.gp_new_profile), a.getString(R.string.gp_new_from, from.name), def, false,
                a.getString(R.string.save), name -> {
                    String n = name.trim();
                    Gamepad.Profile p = store.create(n.isEmpty() ? def : n, from);
                    listener.profilesChanged();
                    fillProfiles();
                    editProfile(p);
                });
    }

    private void rename(final Gamepad.Profile p, final Sheet page) {
        Sheet.input(a, a.getString(R.string.rename), null, p.name, false, a.getString(R.string.save), name -> {
            String n = name.trim();
            if (n.isEmpty()) return;
            store.rename(p, n);
            page.setPageTitle(n);
            listener.profilesChanged();
        });
    }

    /** The red trash can asks first; the last profile can't go. */
    private void confirmDelete(final Gamepad.Profile p, final Sheet page) {
        if (store.profiles().size() <= 1) {
            Sheet.message(a, null, a.getString(R.string.gp_last_profile));
            return;
        }
        Sheet.confirm(a, a.getString(R.string.gp_delete_title, p.name), a.getString(R.string.gp_delete_body),
                a.getString(R.string.gp_delete), R.drawable.ic_delete, Sheet.DANGER, () -> {
                    store.delete(p);
                    listener.profilesChanged();
                    page.dismiss();
                });
    }

    // ------------------------------------------------------------------ bits

    private void rowDivider(LinearLayout into) {
        View d = new View(a);
        d.setBackground(new ColorDrawable(color(R.color.stroke)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
        lp.setMarginStart(dp(60));
        into.addView(d, lp);
    }

    private void withIcon(TextView b, int icon) {
        android.graphics.drawable.Drawable d = a.getDrawable(icon);
        if (d == null) return;
        d.setTintList(ColorStateList.valueOf(Color.WHITE));
        int s = dp(20);
        d.setBounds(0, 0, s, s);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
    }
}
