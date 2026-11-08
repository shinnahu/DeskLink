package com.desklink.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.desklink.core.Vk;

import java.util.ArrayList;
import java.util.List;

/**
 * The key bar above the phone keyboard. Its bottom row holds four group buttons (Edit, Ctrl Alt, Esc Tab, arrows);
 * tapping one opens that group's keys in a row above it, tapping it again closes them. Nothing scrolls, and a key
 * fires only when the finger lifts without having slid away, so a drag across the bar never presses anything.
 */
@SuppressLint("ClickableViewAccessibility")
final class ShortcutBar extends LinearLayout {
    interface Sink {
        void key(int vk, boolean down);

        /** Called on Paste, before Ctrl+V; returns how long to wait (ms) before sending it. */
        default int beforePaste() { return 0; }

        /** A group was opened or closed by the user (the bar changed height). */
        default void barGroupChanged(int group) {}
    }

    static final int GROUP_NONE = -1, GROUP_EDIT = 0, GROUP_MODS = 1, GROUP_KEYS = 2, GROUP_ARROWS = 3;
    private static final int GROUPS = 4;

    private static final int HOLD_MS = 350;
    private static final int REPEAT_DELAY_MS = 400;
    private static final int REPEAT_MS = 55;
    private static final int ROW_DP = 48, PAD_DP = 6;
    private static final int KEY_PRESSED = 0xFF343C49;
    private static final int GROUP_MODS_ACTIVE = 0x993B82F6; // Ctrl/Shift/Alt/Win pressed while their group is closed

    private final Sink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int slop, keyBg, accent, textColor, radius;
    private final List<Modifier> modifiers = new ArrayList<>();
    private final List<List<View>> groupKeys = new ArrayList<>();
    private final TextView[] groupButtons = new TextView[GROUPS];
    private final EvenRow keysRow, groupRow;
    private final String modsLabel;
    private int open = GROUP_NONE;

    ShortcutBar(Context c, Sink sink, int openGroup) {
        super(c);
        this.sink = sink;
        setOrientation(VERTICAL);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        keyBg = Ui.color(c, R.color.key_bg);
        accent = Ui.color(c, R.color.accent);
        textColor = Ui.color(c, R.color.text);
        radius = Ui.dp(c, 10);
        int pad = Ui.dp(c, PAD_DP);
        // away from the screen edge, where a swipe is the system's Back gesture
        setPadding(Ui.dp(c, 12), pad, Ui.dp(c, 4), pad);

        keysRow = new EvenRow(c, Ui.dp(c, 76));
        groupRow = new EvenRow(c, Ui.dp(c, 96));
        LayoutParams kl = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, ROW_DP));
        kl.bottomMargin = pad;
        addView(keysRow, kl);
        addView(groupRow, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, ROW_DP)));

        List<View> edit = new ArrayList<>();
        edit.add(comboKey(R.drawable.ic_undo, c.getString(R.string.cd_undo), new int[] {Vk.CONTROL, Vk.Z}));
        edit.add(comboKey(R.drawable.ic_redo, c.getString(R.string.cd_redo), new int[] {Vk.CONTROL, Vk.SHIFT, Vk.Z}));
        edit.add(comboKey(R.drawable.ic_cut, c.getString(R.string.cd_cut), new int[] {Vk.CONTROL, Vk.X}));
        edit.add(comboKey(R.drawable.ic_copy, c.getString(R.string.cd_copy), new int[] {Vk.CONTROL, Vk.C}));
        edit.add(comboKey(R.drawable.ic_paste, c.getString(R.string.cd_paste), new int[] {Vk.CONTROL, Vk.V}));
        groupKeys.add(edit);

        List<View> mods = new ArrayList<>();
        int[] modVks = {Vk.CONTROL, Vk.SHIFT, Vk.MENU, Vk.LWIN};
        int[] modNames = {R.string.key_ctrl, R.string.key_shift, R.string.key_alt, R.string.key_win};
        int[] modIcons = {0, R.drawable.ic_key_shift, 0, R.drawable.ic_key_win};
        for (int i = 0; i < modVks.length; i++) {
            Modifier m = new Modifier(modVks[i], c.getString(modNames[i]), modIcons[i]);
            modifiers.add(m);
            mods.add(m.view);
        }
        groupKeys.add(mods);

        // (the phone keyboard's ⌫ is Backspace, which deletes to the left; Del deletes to the right, or the selected files)
        List<View> keys = new ArrayList<>();
        keys.add(key(c.getString(R.string.key_esc), 0, Vk.ESCAPE, false));
        keys.add(key(c.getString(R.string.key_tab), R.drawable.ic_key_tab, Vk.TAB, false));
        keys.add(key(c.getString(R.string.key_del), R.drawable.ic_key_del, Vk.DELETE, true));
        groupKeys.add(keys);

        List<View> arrows = new ArrayList<>();
        arrows.add(key("←", 0, Vk.LEFT, true));
        arrows.add(key("↑", 0, Vk.UP, true));
        arrows.add(key("↓", 0, Vk.DOWN, true));
        arrows.add(key("→", 0, Vk.RIGHT, true));
        groupKeys.add(arrows);

        modsLabel = c.getString(R.string.group_mods);
        groupButtons[GROUP_EDIT] = groupButton(GROUP_EDIT, c.getString(R.string.group_edit), c.getString(R.string.cd_group_edit));
        groupButtons[GROUP_MODS] = groupButton(GROUP_MODS, modsLabel, c.getString(R.string.cd_group_mods));
        groupButtons[GROUP_KEYS] = groupButton(GROUP_KEYS, c.getString(R.string.group_keys), c.getString(R.string.cd_group_keys));
        groupButtons[GROUP_ARROWS] = groupButton(GROUP_ARROWS, "← ↑ ↓ →", c.getString(R.string.cd_group_arrows));
        setGroup(openGroup >= 0 && openGroup < GROUPS ? openGroup : GROUP_NONE, false);
    }

    /** Height in px (for the layout of things above the bar): one row, or two while a group is open. */
    int barHeight() {
        int row = Ui.dp(getContext(), ROW_DP), pad = Ui.dp(getContext(), PAD_DP);
        return open == GROUP_NONE ? row + 2 * pad : 2 * row + 3 * pad;
    }

    /** True while Ctrl/Shift/Alt/Win is latched, pressed, or has a finger on it. */
    boolean modifiersActive() {
        for (Modifier m : modifiers) if (m.down || m.latched || m.pressing) return true;
        return false;
    }

    boolean isDown(int vk) {
        for (Modifier m : modifiers) if (m.vk == vk && m.down) return true;
        return false;
    }

    /**
     * Call before sending any other key, text or click: a modifier with a finger on it right now is pressed on the
     * PC first, so the two go together (Ctrl held + V) and the modifier lets go when the finger lifts.
     */
    void markUsed() {
        for (Modifier m : modifiers) if (m.pressing) m.commit();
    }

    private boolean holdOnly;

    /**
     * Ctrl, Shift, Alt and Win: false = a tap keeps one pressed until it's tapped again; true = pressed only while a
     * finger holds it (a quick tap is a plain press, so Win alone opens Start).
     */
    void setHoldOnly(boolean v) {
        holdOnly = v;
        if (v) for (Modifier m : modifiers) if (m.latched) m.forceUp();
    }

    /** Releases every modifier this bar holds (call on disconnect / background). */
    void releaseAll() {
        for (Modifier m : modifiers) m.forceUp();
    }

    /** Clears visual/latched state without sending anything (after the connection was lost). */
    void resetSilently() {
        for (Modifier m : modifiers) m.reset();
    }

    // ------------------------------------------------------------------ groups

    /** Shows a group's keys above the group buttons; GROUP_NONE closes them. */
    private void setGroup(int g, boolean byUser) {
        open = g;
        keysRow.removeAllViews(); // a key held right now gets a cancel: it lets go
        if (g != GROUP_NONE) for (View k : groupKeys.get(g)) keysRow.addView(k, cell());
        keysRow.setVisibility(g == GROUP_NONE ? GONE : VISIBLE);
        refreshGroups();
        if (byUser) sink.barGroupChanged(g);
    }

    private void refreshGroups() {
        if (groupButtons[GROUPS - 1] == null) return; // still being built
        StringBuilder held = new StringBuilder();
        for (Modifier m : modifiers) {
            if (!m.down && !m.latched) continue;
            if (held.length() > 0) held.append('+');
            held.append(m.label);
        }
        for (int g = 0; g < GROUPS; g++) {
            boolean isOpen = g == open;
            boolean modsOn = g == GROUP_MODS && held.length() > 0;
            TextView b = groupButtons[g];
            b.setBackground(Ui.rounded(isOpen ? accent : modsOn ? GROUP_MODS_ACTIVE : keyBg, radius));
            b.setTextColor(isOpen || modsOn ? Color.WHITE : textColor);
        }
        // a latched modifier stays visible on its group button while another group is open
        groupButtons[GROUP_MODS].setText(held.length() > 0 ? held.toString() : modsLabel);
    }

    private TextView groupButton(int group, String label, String description) {
        TextView t = keyText(label, 14);
        t.setContentDescription(description);
        t.setOnTouchListener(new Press() {
            @Override
            void down(View v) { v.setBackground(Ui.rounded(KEY_PRESSED, radius)); }

            @Override
            void up(View v) { setGroup(open == group ? GROUP_NONE : group, true); }

            @Override
            void cancel(View v) { refreshGroups(); }
        });
        groupRow.addView(t, cell());
        return t;
    }

    // ------------------------------------------------------------------ keys

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        int m = Ui.dp(getContext(), 3);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private TextView keyText(String label, int sp) {
        TextView t = Ui.text(getContext(), label, sp, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        int p = Ui.dp(getContext(), 3);
        t.setPadding(p, 0, p, 0);
        t.setAutoSizeTextTypeUniformWithConfiguration(9, sp, 1, TypedValue.COMPLEX_UNIT_SP); // "Inicio" in a narrow key
        t.setBackground(Ui.rounded(keyBg, radius));
        return t;
    }

    /** Key look while pressed (lit) or not. */
    private void setLit(View v, boolean lit) {
        paint(v, lit ? accent : keyBg, lit ? Color.WHITE : textColor);
    }

    /** A key's background and its label's (or icon's) color. */
    private void paint(View v, int bg, int fg) {
        v.setBackground(Ui.rounded(bg, radius));
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(fg);
        } else if (v instanceof FrameLayout && ((FrameLayout) v).getChildAt(0) instanceof ImageView) {
            ((ImageView) ((FrameLayout) v).getChildAt(0)).setImageTintList(ColorStateList.valueOf(fg));
        }
    }

    /** A key showing an icon instead of a name (the name is what TalkBack says). */
    private FrameLayout iconKey(int icon, String description) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(Ui.rounded(keyBg, radius));
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(textColor));
        int s = Ui.dp(getContext(), 22);
        box.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        box.setContentDescription(description);
        return box;
    }

    /** A plain key (with a name, or an icon when {@code icon} isn't 0); repeating keys repeat while held. */
    private View key(String label, int icon, final int vk, final boolean repeat) {
        View t = icon != 0 ? iconKey(icon, label) : keyText(label, 15);
        t.setContentDescription(label);
        t.setOnTouchListener(new Press() {
            boolean repeating;
            final Runnable repeater = new Runnable() {
                @Override
                public void run() {
                    repeating = true;
                    markUsed();
                    sink.key(vk, true); // auto-repeat = repeated key-down
                    handler.postDelayed(this, REPEAT_MS);
                }
            };

            @Override
            void down(View v) {
                repeating = false;
                setLit(v, true);
                if (repeat) handler.postDelayed(repeater, REPEAT_DELAY_MS);
            }

            @Override
            void up(View v) {
                handler.removeCallbacks(repeater);
                setLit(v, false);
                markUsed();
                if (!repeating) sink.key(vk, true);
                sink.key(vk, false);
                repeating = false;
            }

            @Override
            void cancel(View v) {
                handler.removeCallbacks(repeater);
                setLit(v, false);
                if (repeating) sink.key(vk, false);
                repeating = false;
            }
        });
        return t;
    }

    /** Undo / redo / cut / copy / paste: a key combination, reusing modifiers already held on the bar. */
    private View comboKey(int icon, String description, final int[] keys) {
        FrameLayout box = iconKey(icon, description);
        box.setOnTouchListener(new Press() {
            @Override
            void down(View v) { setLit(v, true); }

            @Override
            void up(View v) {
                setLit(v, false);
                // Paste: a copy just made on the phone is sent to the PC first; give it a moment to land there
                int wait = isPaste(keys) ? sink.beforePaste() : 0;
                if (wait > 0) {
                    markUsed();
                    handler.postDelayed(() -> combo(keys), wait);
                } else {
                    combo(keys);
                }
            }

            @Override
            void cancel(View v) { setLit(v, false); }
        });
        return box;
    }

    private static boolean isPaste(int[] keys) {
        return keys.length == 2 && keys[0] == Vk.CONTROL && keys[1] == Vk.V;
    }

    private void combo(int[] keys) {
        markUsed();
        int main = keys[keys.length - 1];
        List<Integer> pressed = new ArrayList<>();
        for (int i = 0; i < keys.length - 1; i++) {
            int k = keys[i];
            if (!isDown(k)) {
                sink.key(k, true);
                pressed.add(k);
            }
        }
        sink.key(main, true);
        sink.key(main, false);
        for (int i = pressed.size() - 1; i >= 0; i--) sink.key(pressed.get(i), false);
    }

    // ------------------------------------------------------------------ touch

    /** Shared touch handling: fires when the finger lifts, unless it slid away from where it landed first. */
    private abstract class Press implements View.OnTouchListener {
        private float x0, y0;
        private boolean active;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    active = true;
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    down(v);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (active && cancelOnMove() && Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) {
                        active = false; // a drag, not a tap: nothing is sent
                        cancel(v);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (active) {
                        active = false;
                        up(v);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (active) {
                        active = false;
                        cancel(v);
                    }
                    break;
                default:
                    break;
            }
            return true;
        }

        boolean cancelOnMove() { return true; }

        abstract void down(View v);

        abstract void up(View v);

        abstract void cancel(View v);
    }

    /**
     * Ctrl / Shift / Alt / Win: tap = stays pressed until tapped again (or, with "only while held", a plain press);
     * hold = pressed while held. Nothing reaches the PC until it's a tap, a hold or a chord, so a drag that starts on
     * Win doesn't open the Start menu.
     */
    private final class Modifier extends Press {
        final int vk;
        final String label;
        final View view;
        boolean down;      // pressed on the PC
        boolean latched;   // stays pressed after a tap
        boolean pressing;  // a finger is on it
        boolean wasLatched;
        private final Runnable hold = this::commit;

        Modifier(int vk, String label, int icon) {
            this.vk = vk;
            this.label = label;
            view = icon != 0 ? iconKey(icon, label) : keyText(label, 15);
            view.setContentDescription(label);
            view.setOnTouchListener(this);
        }

        @Override
        boolean cancelOnMove() {
            // held (or chorded) on the PC right now: a finger that wobbles keeps it pressed. A drag that starts on
            // a latched key leaves it latched.
            return wasLatched || !down;
        }

        @Override
        void down(View v) {
            pressing = true;
            wasLatched = latched;
            if (!latched) handler.postDelayed(hold, HOLD_MS);
            refresh();
        }

        /** Presses it on the PC now: held long enough, or something else is being pressed with it. */
        void commit() {
            handler.removeCallbacks(hold);
            if (!down) {
                down = true;
                sink.key(vk, true);
            }
            refresh();
        }

        @Override
        void up(View v) {
            handler.removeCallbacks(hold);
            pressing = false;
            if (wasLatched || down) {
                // a tap on a latched key releases it; a hold or a chord (Ctrl held + V) was momentary
                latched = false;
                release();
            } else if (holdOnly || vk == Vk.LWIN) {
                // Win never stays pressed (nor the others, set to "only while held"): a quick tap is just a press of
                // the key, so Win alone opens Start; held, it's pressed while held (Win + a key)
                sink.key(vk, true);
                sink.key(vk, false);
            } else {
                latched = true; // a quick tap: pressed until tapped again
                commit();
            }
            refresh();
        }

        @Override
        void cancel(View v) {
            handler.removeCallbacks(hold);
            pressing = false;
            if (!wasLatched) release(); // pressed while held: let go (a drag that never pressed it sends nothing)
            refresh();
        }

        private void release() {
            if (down) {
                down = false;
                sink.key(vk, false);
            }
        }

        void forceUp() {
            handler.removeCallbacks(hold);
            pressing = false;
            latched = false;
            release();
            refresh();
        }

        void reset() {
            handler.removeCallbacks(hold);
            pressing = false;
            latched = false;
            down = false;
            refresh();
        }

        void refresh() {
            boolean on = down || latched;
            paint(view, on ? accent : pressing ? KEY_PRESSED : keyBg, on ? Color.WHITE : textColor);
            refreshGroups();
        }
    }

    /** A row whose children share its width evenly, each at most {@code maxChild} wide (start-aligned when wider). */
    private static final class EvenRow extends LinearLayout {
        private final int maxChild;

        EvenRow(Context c, int maxChild) {
            super(c);
            this.maxChild = maxChild;
            setOrientation(HORIZONTAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int n = 0;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) n++;
            if (n > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int cap = n * maxChild + getPaddingLeft() + getPaddingRight();
                if (MeasureSpec.getSize(widthSpec) > cap) widthSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
