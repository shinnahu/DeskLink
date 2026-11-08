package com.desklink.app;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gaming mode's on-screen gamepad: a D-pad (or a stick), △ ○ ✕ □, L1 L2 R1 R2, L3 R3, Select and Start, drawn as
 * outlines over the picture. Holding a control holds its key or mouse button down on the PC. Every control is its
 * own view, added straight to the session screen, so a finger on a control and a finger on the picture are separate
 * touches: the trackpad keeps working between the controls, and several controls can be held at once.
 */
final class GamepadControls {
    interface Sink {
        /** A key (Windows virtual key) or a mouse button (Gamepad.MOUSE + n) goes down or up on the PC. */
        void padAction(int action, boolean down);
    }

    /** △ ○ ✕ □ when the buttons are colored (by control, Gamepad.TRIANGLE … SQUARE). */
    static final int[] FACE_COLORS = {0xFF1FAA8C, 0xFFE83287, 0xFF0070D1, 0xFFA363D9};
    /** With Prefs.LABELS_ON_START: how long the names stay before they fade, and how long the fade takes. */
    private static final long LABELS_SHOWN_MS = 5000, LABELS_FADE_MS = 700;

    private static final int RIGHT = 1 << Gamepad.RIGHT, UP = 1 << Gamepad.UP, LEFT = 1 << Gamepad.LEFT,
            DOWN = 1 << Gamepad.DOWN;
    /** The four directions counter-clockwise from the right, as Gamepad.UP … RIGHT. */
    private static final int[] AROUND = {Gamepad.RIGHT, Gamepad.UP, Gamepad.LEFT, Gamepad.DOWN};

    private final Context c;
    private final Sink sink;
    private final float dp;
    private final DisplayMetrics metrics;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] bind = new int[Gamepad.CONTROLS];
    private final int[] held = new int[Gamepad.ACTIONS]; // how many controls hold each action down right now
    private final List<Control> views = new ArrayList<>();
    private final Dpad dpad;
    private final Stick stick;
    private final Button[] buttons = new Button[Gamepad.CONTROLS];
    private final Paint shade, lit, arrow, caption, label, knob;
    private final float captionH;
    private int padType = Prefs.PAD_DUALSHOCK;
    private int lastW, lastH, lastL, lastT, lastR, lastB; // the last place(): placed again when the pad type changes
    private boolean visible, colored;
    /** Line weight, by level 1 … 5: how much thinner (dp, each side) the icons' lines are drawn. */
    private static final float[] THIN_DP = {0.6f, 0.45f, 0.3f, 0.15f, 0f};
    private int lineLevel = Prefs.LINE_DEFAULT;
    private float thin;                       // px, each side
    private float opacity = 1f;
    private int stickLook = Prefs.STICK_RING;
    private int arrowColor = Prefs.ARROW_COLORS[0];
    // the names of what each control does (the captions): shown, fading, or gone
    private int labelMode = Prefs.LABELS_ON_START;
    private float labelAlpha = 1f;
    private ValueAnimator labelFade;
    private final Runnable fadeLabels = this::fadeLabels;

    GamepadControls(Context c, Sink sink) {
        this.c = c;
        this.sink = sink;
        metrics = c.getResources().getDisplayMetrics();
        dp = metrics.density;
        captionH = 16 * dp;
        shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(0x47000000); // behind each control, so it still shows on a bright PC screen
        lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        lit.setColor(0x66FFFFFF);
        knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        knob.setColor(0x38FFFFFF);
        arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrow.setColor(0xE6FFFFFF); // (Settings → Arrow color)
        caption = new Paint(Paint.ANTI_ALIAS_FLAG);
        caption.setColor(0xE6FFFFFF);
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setShadowLayer(2 * dp, 0, 0, 0xE6000000);
        label = new Paint(caption); // what a control does; it can fade away
        thin = THIN_DP[lineLevel - 1] * dp;
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); // (the default line weight's names)

        dpad = new Dpad();
        views.add(dpad);
        stick = new Stick();
        views.add(stick);
        for (int k = Gamepad.TRIANGLE; k < Gamepad.CONTROLS; k++) {
            buttons[k] = new Button(k);
            views.add(buttons[k]);
        }
    }

    /**
     * Adds the controls to the session screen at z-position {@code index} (just above its touch layer). They are
     * placed from the left edge on purpose: a controller has its D-pad on the left in every language.
     */
    @SuppressLint("RtlHardcoded")
    void addTo(ViewGroup root, int index) {
        for (Control v : views) {
            v.setVisibility(View.GONE);
            root.addView(v, index++, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        }
    }

    /** The profile's bindings. */
    void setProfile(Gamepad.Profile p) {
        System.arraycopy(p.bind, 0, bind, 0, Gamepad.CONTROLS);
        applyAlpha(); // a control that does nothing is drawn faint
        for (Control v : views) v.invalidate();
    }

    /** Prefs.PAD_DUALSHOCK (the analog stick) or PAD_DIGITAL (the D-pad), like an emulator's pad type. */
    void setPadType(int t) {
        if (t == padType) return;
        dpad.letGo(true);
        stick.letGo(true);
        padType = t;
        applyVisibility();
        if (lastW > 0) place(lastW, lastH, lastL, lastT, lastR, lastB); // Select / Start make room for the other one
    }

    void setVisible(boolean v) {
        boolean was = visible;
        visible = v;
        if (!v) {
            releaseAll();
            handler.removeCallbacks(fadeLabels);
            if (labelFade != null) labelFade.cancel();
        }
        applyVisibility();
        if (v && !was) showLabels();
    }

    private void applyVisibility() {
        boolean digital = padType == Prefs.PAD_DIGITAL;
        for (Control x : views) {
            boolean show = visible && (x == dpad ? digital : x != stick || !digital);
            x.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** △ ○ ✕ □ in their colors (green, pink, blue, purple) or white. */
    void setColored(boolean on) {
        if (colored == on) return;
        colored = on;
        for (Button b : buttons) if (b != null) b.loadIcon();
    }

    /** Prefs.LABELS_ALWAYS, LABELS_ON_START or LABELS_OFF; applies right away. */
    void setLabelMode(int m) {
        labelMode = m;
        showLabels();
    }

    /**
     * Gaming mode started (or came back, or another profile was picked): the names of what each control does show,
     * and with LABELS_ON_START they fade away after 5 seconds.
     */
    void showLabels() {
        handler.removeCallbacks(fadeLabels);
        if (labelFade != null) labelFade.cancel();
        setLabelAlpha(labelMode == Prefs.LABELS_OFF ? 0f : 1f);
        if (labelMode == Prefs.LABELS_ON_START && visible) handler.postDelayed(fadeLabels, LABELS_SHOWN_MS);
    }

    private void fadeLabels() {
        if (labelFade != null) labelFade.cancel();
        labelFade = ValueAnimator.ofFloat(labelAlpha, 0f);
        labelFade.setDuration(LABELS_FADE_MS);
        labelFade.addUpdateListener(an -> setLabelAlpha((float) an.getAnimatedValue()));
        labelFade.start();
    }

    private void setLabelAlpha(float a) {
        if (a == labelAlpha) return;
        labelAlpha = a;
        for (Control v : views) v.invalidate();
    }

    /** Lets go of everything held (Gaming mode turned off, leaving the app). */
    void releaseAll() {
        for (Control v : views) v.letGo(true);
    }

    /** Forgets what is held without sending anything (the connection is gone; the PC let go of everything). */
    void resetSilently() {
        for (Control v : views) v.letGo(false);
        Arrays.fill(held, 0);
    }

    /**
     * Positions the controls for a w × h screen; l, t, r, b are the insets (b reaches up to the keyboard or the
     * navigation bar). Like a controller held sideways: D-pad bottom left, buttons bottom right, shoulder buttons in
     * the top corners, Select and Start at the bottom in the middle.
     */
    void place(int w, int h, int l, int t, int r, int b) {
        if (w <= 0 || h <= 0) return;
        lastW = w;
        lastH = h;
        lastL = l;
        lastT = t;
        lastR = r;
        lastB = b;
        int edge = px(24), cap = (int) captionH;
        int pad = Math.min(px(150), Math.min(w, h) * 2 / 5);
        set(dpad, l + edge, h - b - edge - pad, pad, pad);
        // the stick's ring, a bit bigger than the D-pad; its view also holds the knob's whole travel, so it reaches
        // past the ring (and past the screen's edge, where nothing shows)
        int ring = Math.min(px(92), Math.round(Math.min(w, h) * 0.22f));
        int scx = l + edge + ring, scy = h - b - edge - ring, half = stick.halfFor(ring);
        set(stick, scx - half, scy - half, 2 * half, 2 * half);
        boolean digital = padType == Prefs.PAD_DIGITAL;
        int leftRight = digital ? l + edge + pad : scx + ring, leftTop = digital ? h - b - edge - pad : scy - ring;

        int fd = px(54), gap = px(58); // face button size, and how far each sits from the middle of the four
        int cx = w - r - edge - gap - fd / 2;
        int cy = h - b - edge - cap - gap - fd / 2;
        setRound(Gamepad.TRIANGLE, cx, cy - gap, fd);
        setRound(Gamepad.SQUARE, cx - gap, cy, fd);
        setRound(Gamepad.CIRCLE, cx + gap, cy, fd);
        setRound(Gamepad.CROSS, cx, cy + gap, fd);

        int sw = px(60), sh = px(44), top = t + px(12), sg = px(10);
        set(buttons[Gamepad.L1], l + edge, top, sw, sh + cap);
        set(buttons[Gamepad.L2], l + edge + sw + sg, top, sw, sh + cap);
        set(buttons[Gamepad.R1], w - r - edge - sw, top, sw, sh + cap);
        set(buttons[Gamepad.R2], w - r - edge - 2 * sw - sg, top, sw, sh + cap);

        // the middle row: L3 on the D-pad's side, Select, Start, R3 on the buttons' side (where a controller's two
        // sticks are, pressed in)
        int selW = px(50), selH = px(44), stW = px(46), stH = px(44), sd = px(46), mid = px(20);
        int total = sd + mid + selW + mid + stW + mid + sd, rowH = Math.max(stH, sd) + cap;
        int x0 = (w - total) / 2;
        int y0 = h - b - px(12) - rowH;
        int faceLeft = cx - gap - fd / 2;
        if (x0 < leftRight + px(8) || x0 + total > faceLeft - px(8)) {
            // portrait: no room between the stick (or D-pad) and the buttons, so above them
            y0 = Math.min(leftTop, cy - gap - fd / 2) - px(16) - rowH;
        }
        int body = rowH - cap, x = x0;
        set(buttons[Gamepad.L3], x, y0 + (body - sd) / 2, sd, sd + cap);
        x += sd + mid;
        set(buttons[Gamepad.SELECT], x, y0 + (body - selH) / 2, selW, selH + cap);
        x += selW + mid;
        set(buttons[Gamepad.START], x, y0 + (body - stH) / 2, stW, stH + cap);
        x += stW + mid;
        set(buttons[Gamepad.R3], x, y0 + (body - sd) / 2, sd, sd + cap);
    }

    private int px(float v) { return Math.round(v * dp); }

    private void setRound(int control, int cx, int cy, int d) {
        set(buttons[control], cx - d / 2, cy - d / 2, d, d + (int) captionH);
    }

    private static void set(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width == w && lp.height == h && lp.leftMargin == x && lp.topMargin == y) return;
        lp.width = w;
        lp.height = h;
        lp.leftMargin = x;
        lp.topMargin = y;
        v.setLayoutParams(lp);
    }

    private void hold(int action, boolean down) {
        if (action <= Gamepad.NONE || action >= Gamepad.ACTIONS) return;
        if (down) {
            if (held[action]++ == 0) sink.padAction(action, true);
        } else if (held[action] > 0 && --held[action] == 0) {
            sink.padAction(action, false); // two controls on the same key: it lets go when both have
        }
    }

    /** The name of what a control does, unless the names are faded out. */
    private void drawCaption(Canvas cv, int action, float x, float baseline, float maxW) {
        if (action == Gamepad.NONE || labelAlpha <= 0f) return;
        String s = Gamepad.actionName(c, action);
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, metrics);
        label.setTextSize(size);
        float tw = label.measureText(s);
        if (tw > maxW) label.setTextSize(Math.max(7 * dp, size * maxW / tw));
        int a = Math.round(0xE6 * labelAlpha);
        label.setColor(Color.argb(a, 0xFF, 0xFF, 0xFF));
        label.setShadowLayer(2 * dp, 0, 0, Color.argb(a, 0, 0, 0));
        cv.drawText(s, x, baseline, label);
    }

    /** How far apart two angles are, in degrees (0 … 180). */
    private static double angleDiff(double a, double b) {
        return Math.abs(((a - b) % 360 + 540) % 360 - 180);
    }

    // ------------------------------------------------------------------ the controls

    @SuppressLint("ViewConstructor")
    private abstract class Control extends View {
        Control() { super(c); }

        abstract boolean bound();

        abstract void letGo(boolean send);
    }

    /**
     * △ ○ ✕ □, a shoulder button, L3 / R3, Select or Start, drawn with its PlayStation icon (an outline) on a
     * dark plate, so it shows on a bright picture. Pressed while a finger is on it, even if it slides off.
     */
    @SuppressLint("ViewConstructor")
    private final class Button extends Control {
        final int control;
        boolean pressed;
        int action; // what it pressed: the profile may change while it's held
        private Drawable icon;
        private Bitmap art;   // the icon, white, as big as the box allows, its lines thinned as set
        private int artX, artY, iconW, iconH;
        private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF plate = new RectF();

        Button(int control) {
            this.control = control;
            setContentDescription(Gamepad.controlName(c, control));
            loadIcon();
        }

        /** The icon, in its color. */
        void loadIcon() {
            icon = PadIcons.mutated(c, PadIcons.res(control));
            int col = PadIcons.color(control, colored);
            artPaint.setColorFilter(new PorterDuffColorFilter(col == 0xFFFFFFFF ? 0xEBFFFFFF : col, PorterDuff.Mode.SRC_IN));
            rebuild();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rebuild();
        }

        /** Draws the icon once into a bitmap (redone when the size, style or line weight changes). */
        void rebuild() {
            int w = getWidth(), h = Math.round(bodyH());
            if (w <= 0 || h <= 0 || icon == null) return;
            float iw = Math.max(1, icon.getIntrinsicWidth()), ih = Math.max(1, icon.getIntrinsicHeight());
            float s = Math.min(w / iw, h / ih);
            iconW = Math.round(iw * s);
            iconH = Math.round(ih * s);
            float e = thin;
            if (control == Gamepad.SELECT || control == Gamepad.START) e = Math.min(e, 0.4f * dp); // their small letters
            art = thinned(icon, iconW, iconH, e);
            artX = Math.round((w - iconW) / 2f) - (art.getWidth() - iconW) / 2;
            artY = Math.round((h - iconH) / 2f) - (art.getHeight() - iconH) / 2;
            invalidate();
        }

        @Override
        boolean bound() { return bind[control] != Gamepad.NONE; }

        private boolean round() {
            return (control >= Gamepad.TRIANGLE && control <= Gamepad.SQUARE) || control == Gamepad.L3 || control == Gamepad.R3;
        }

        private float bodyH() { return getHeight() - captionH; }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!hit(e.getX(), e.getY())) return false; // its caption, or a round button's corners
                    pressed = true;
                    action = bind[control];
                    hold(action, true);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    letGo(true);
                    return true;
                default:
                    return true; // more fingers on the same button: it lets go when the last one lifts
            }
        }

        private boolean hit(float x, float y) {
            float w = getWidth(), h = bodyH(), m = 6 * dp; // a little slack around the icon
            if (round()) return Math.hypot(x - w / 2, y - h / 2) <= Math.min(w, h) / 2 + m;
            return x >= -m && x <= w + m && y >= -m && y <= h + m;
        }

        @Override
        void letGo(boolean send) {
            if (!pressed) return;
            pressed = false;
            if (send) hold(action, false);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = bodyH();
            cv.save();
            if (pressed) cv.scale(0.93f, 0.93f, w / 2f, h / 2f); // pushed in
            Paint p = pressed ? lit : shade;
            float l = (w - iconW) / 2f, t = (h - iconH) / 2f;
            if (round()) {
                cv.drawCircle(w / 2f, h / 2f, Math.min(iconW, iconH) * 0.46f, p);
            } else {
                plate.set(l + iconW * 0.14f, t + iconH * 0.14f, l + iconW * 0.86f, t + iconH * 0.86f);
                cv.drawRoundRect(plate, 6 * dp, 6 * dp, p);
            }
            if (art != null) cv.drawBitmap(art, artX, artY, artPaint);
            cv.restore();
            drawCaption(cv, bind[control], w / 2f, h + captionH * 0.8f, w + 8 * dp);
        }
    }

    /**
     * The D-pad (pad type Digital), the PlayStation one: 8 ways. The diagonals (45° wide, like the four arms) hold two
     * directions at once; the arms held are lit red.
     */
    @SuppressLint("ViewConstructor")
    private final class Dpad extends Control {
        private int pointer = -1;
        private int dirs;                         // bit per direction pressed (1 << Gamepad.UP …)
        private final int[] action = new int[4];  // what each direction pressed
        private Drawable pad;
        private Bitmap art; // the D-pad, white, its lines thinned as set
        private int artAt;
        private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Drawable[] arms = new Drawable[4];

        Dpad() {
            setContentDescription("D-pad");
            artPaint.setColorFilter(new PorterDuffColorFilter(0xEBFFFFFF, PorterDuff.Mode.SRC_IN));
            loadIcon();
        }

        void loadIcon() {
            pad = PadIcons.mutated(c, R.drawable.ps_dpad_outline);
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) arms[k] = PadIcons.mutated(c, PadIcons.arm(k));
            tintArms();
            rebuild();
        }

        /** The arms held are lit in the arrow color. */
        void tintArms() {
            for (Drawable d : arms) if (d != null) d.setTint((arrowColor & 0x00FFFFFF) | 0xF0000000);
            invalidate();
        }

        private int inset() { return Math.round(getWidth() * 0.04f); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rebuild();
        }

        void rebuild() {
            int side = getWidth() - 2 * inset();
            if (side <= 0 || pad == null) return;
            art = thinned(pad, side, side, thin);
            artAt = inset() - (art.getWidth() - side) / 2;
            invalidate();
        }

        @Override
        boolean bound() {
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) if (bind[k] != Gamepad.NONE) return true;
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX(), y = e.getY(), r = getWidth() / 2f;
                    if (Math.hypot(x - r, y - r) > r + 8 * dp) return false; // the square's corners: not the D-pad
                    pointer = e.getPointerId(0);
                    steer(x, y);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int i = e.findPointerIndex(pointer);
                    if (i >= 0) steer(e.getX(i), e.getY(i));
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(e.getActionIndex()) == pointer) letGo(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    letGo(true);
                    return true;
                default:
                    return true; // a second finger on the D-pad is ignored
            }
        }

        /** The finger's direction from the middle picks the arms; sliding round changes them without lifting. */
        private void steer(float x, float y) {
            float r = getWidth() / 2f, dx = x - r, dy = y - r;
            if (Math.hypot(dx, dy) < r * 0.2f) { // resting in the middle: nothing
                setDirs(0, true);
                return;
            }
            double a = Math.toDegrees(Math.atan2(-dy, dx)); // 0 = right, 90 = up
            int d = 0;
            if (angleDiff(a, 0) <= 67.5) d |= RIGHT; // each arm alone gets 45°, each diagonal the 45° between two
            if (angleDiff(a, 90) <= 67.5) d |= UP;
            if (angleDiff(a, 180) <= 67.5) d |= LEFT;
            if (angleDiff(a, -90) <= 67.5) d |= DOWN;
            setDirs(d, true);
        }

        private void setDirs(int d, boolean send) {
            if (d == dirs) return;
            boolean more = false;
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) {
                int bit = 1 << k;
                boolean was = (dirs & bit) != 0, now = (d & bit) != 0;
                if (was && !now && send) hold(action[k], false);
                if (!was && now) {
                    action[k] = bind[k];
                    hold(action[k], true);
                    more = true;
                }
            }
            dirs = d;
            if (more) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
        }

        @Override
        void letGo(boolean send) {
            pointer = -1;
            setDirs(0, send);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            int in = inset();
            cv.drawCircle(w / 2f, w / 2f, w * 0.42f, shade); // a dark plate, so it shows on a bright picture
            if (art != null) cv.drawBitmap(art, artAt, artAt, artPaint);
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) {
                if ((dirs & (1 << k)) == 0) continue;
                arms[k].setBounds(in, in, Math.round(w) - in, Math.round(w) - in);
                arms[k].draw(cv);
            }
            // what each arm does, on the arm
            float box = w - 2 * in, maxW = box * 0.3f;
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, metrics));
            float dy = -(label.ascent() + label.descent()) / 2;
            float near = in + box * 0.2f, far = in + box * 0.8f, mid = w / 2f;
            drawCaption(cv, bind[Gamepad.UP], mid, near + dy, maxW);
            drawCaption(cv, bind[Gamepad.DOWN], mid, far + dy, maxW);
            drawCaption(cv, bind[Gamepad.LEFT], near, mid + dy, maxW);
            drawCaption(cv, bind[Gamepad.RIGHT], far, mid + dy, maxW);
        }
    }

    /** The four directions' names around a middle (D-pad, stick), {@code lane} away from it. */
    private void drawDirCaptions(Canvas cv, float x, float y, float lane, float maxW) {
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, metrics));
        float dy = -(label.ascent() + label.descent()) / 2; // centres a caption on its point
        drawCaption(cv, bind[Gamepad.UP], x, y - lane + dy, maxW);
        drawCaption(cv, bind[Gamepad.DOWN], x, y + lane + dy, maxW);
        drawCaption(cv, bind[Gamepad.LEFT], x - lane, y + dy, maxW);
        drawCaption(cv, bind[Gamepad.RIGHT], x + lane, y + dy, maxW);
    }

    private static void drawArrow(Canvas cv, Path path, float x1, float y1, float x2, float y2, float x3, float y3, Paint p) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        cv.drawPath(path, p);
    }

    /**
     * The analog stick (pad type DualShock), drawn like an emulator's DualShock: a ring with a red arrow at each
     * side, a mark at each corner, and a ball for the knob that follows the thumb, out past the ring to the corners.
     * It presses the same four keys as the D-pad, but steers smoothly: the nearest key stays down and the next one
     * round is tapped, more of the time the further the stick leans towards it. A keyboard can't send "a bit to the
     * right" any other way; in most third-person games the character then heads in between.
     */
    @SuppressLint("ViewConstructor")
    private final class Stick extends Control {
        private static final float TRAVEL = 1.2f, KNOB = 0.45f; // the knob's reach and size, in ring radii
        private static final float DEAD_IN = 0.22f, DEAD_OUT = 0.15f; // out of / back into the middle, in ring radii
        // the shortest tap a game is sure to see (two frames at 60 fps); taps cycle as fast as that allows, so the
        // in-between heading wobbles as little as it can (half the earlier 120 ms cycle when it leans halfway)
        private static final long MIN_PULSE_MS = 34, MIN_CYCLE_MS = 68, MAX_CYCLE_MS = 200;

        private int pointer = -1;
        private float fx, fy;           // the thumb (view coordinates)
        private boolean active;         // out of the middle: keys are down
        private int dirs;               // bit per direction pressed (1 << Gamepad.UP …)
        private final int[] action = new int[4];
        // the main direction stays down; the side one is down for `duty` of the time
        private int mainDir = -1, sideDir = -1;
        private float duty;
        private boolean pulsing, sideOn;
        private final Runnable pulse = this::pulse;
        private final Path path = new Path();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), ball = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix ballAt = new Matrix();
        private RadialGradient ballShade;

        Stick() {
            setContentDescription("Stick");
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(0xEBFFFFFF); // like the buttons' icons
            shine.setColor(0x1FFFFFFF);
            lines();
        }

        /** The ring as heavy as the buttons' outlines (about 3.2 dp as drawn, less when thinned). */
        void lines() {
            ring.setStrokeWidth(Math.max(1.2f * dp, 3.2f * dp - 2 * thin));
            invalidate();
        }

        /** Half the view's side for a ring of radius {@code r}: room for the knob's whole travel. */
        int halfFor(int r) { return Math.round(r * (TRAVEL + KNOB)) + px(2); }

        private float ringR() { return (getWidth() / 2f - px(2)) / (TRAVEL + KNOB); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float kr = ringR() * KNOB; // a ball lit from the top left: made once per size, moved with a matrix
            ballShade = new RadialGradient(-0.3f * kr, -0.35f * kr, kr * 1.4f,
                    new int[] {0xEB5E5E5E, 0xEB333333, 0xEB1C1C1C}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            ball.setShader(ballShade);
        }

        @Override
        boolean bound() {
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) if (bind[k] != Gamepad.NONE) return true;
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX(), y = e.getY(), c0 = getWidth() / 2f;
                    // the ring and its corner marks; the rest of the (big) view is the picture's
                    if (Math.hypot(x - c0, y - c0) > ringR() * 1.45f) return false;
                    pointer = e.getPointerId(0);
                    track(x, y);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int i = e.findPointerIndex(pointer);
                    if (i >= 0) track(e.getX(i), e.getY(i));
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(e.getActionIndex()) == pointer) letGo(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    letGo(true);
                    return true;
                default:
                    return true; // a second finger on the stick is ignored
            }
        }

        private void track(float x, float y) {
            fx = x;
            fy = y;
            float c0 = getWidth() / 2f, dx = x - c0, dy = y - c0;
            float m = (float) Math.hypot(dx, dy) / ringR();
            boolean was = active;
            active = m >= (active ? DEAD_OUT : DEAD_IN);
            if (!active) {
                mainDir = sideDir = -1;
                stopPulse();
                setDirs(0, true);
            } else {
                double a = Math.toDegrees(Math.atan2(-dy, dx)); // 0 = right, 90 = up
                if (a < 0) a += 360;
                steer(a);
                if (!was) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            invalidate();
        }

        /** The nearest of the four directions stays down; the next one round is tapped for the angle in between. */
        private void steer(double a) {
            int near = Math.round((float) (a / 90)) % 4;     // 0 right, 1 up, 2 left, 3 down
            double off = a - near * 90;                      // how far round from it: −45 … 45
            if (off > 180) off -= 360;
            if (off < -180) off += 360;
            mainDir = AROUND[near];
            sideDir = AROUND[off >= 0 ? (near + 1) % 4 : (near + 3) % 4];
            float d = (float) (Math.abs(off) / 45);
            duty = d < 0.15f ? 0f : d > 0.85f ? 1f : d; // nearly straight / nearly diagonal: just that
            if (duty == 0f || duty == 1f) {
                stopPulse();
                setDirs((1 << mainDir) | (duty == 1f ? 1 << sideDir : 0), true);
            } else if (!pulsing) {
                pulsing = true; // start with a tap, so the turn begins right away
                sideOn = true;
                setDirs((1 << mainDir) | (1 << sideDir), true);
                postDelayed(pulse, onTime());
            } else {
                setDirs((1 << mainDir) | (sideOn ? 1 << sideDir : 0), true); // the next tap uses the new duty
            }
        }

        /** One tap cycle: as short as it can be while both its parts stay at least MIN_PULSE_MS. */
        private long cycle() {
            float shorter = Math.max(0.01f, Math.min(duty, 1 - duty));
            return Math.max(MIN_CYCLE_MS, Math.min(MAX_CYCLE_MS, Math.round(MIN_PULSE_MS / shorter)));
        }

        private long onTime() { return Math.max(1, Math.round(duty * cycle())); }

        private long offTime() { return Math.max(1, cycle() - onTime()); }

        private void pulse() {
            if (!pulsing || mainDir < 0) return;
            sideOn = !sideOn;
            setDirs((1 << mainDir) | (sideOn ? 1 << sideDir : 0), true);
            postDelayed(pulse, sideOn ? onTime() : offTime());
        }

        private void stopPulse() {
            pulsing = false;
            sideOn = false;
            removeCallbacks(pulse);
        }

        private void setDirs(int d, boolean send) {
            if (d == dirs) return;
            for (int k = Gamepad.UP; k <= Gamepad.RIGHT; k++) {
                int bit = 1 << k;
                boolean was = (dirs & bit) != 0, now = (d & bit) != 0;
                if (was && !now && send) hold(action[k], false);
                if (!was && now) {
                    action[k] = bind[k];
                    hold(action[k], true);
                }
            }
            dirs = d;
            invalidate();
        }

        @Override
        void letGo(boolean send) {
            pointer = -1;
            active = false;
            mainDir = sideDir = -1;
            stopPulse();
            setDirs(0, send);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float c0 = getWidth() / 2f, r = ringR();
            if (stickLook != Prefs.STICK_BALL) { // the ring (unless it's the ball alone)
                cv.drawCircle(c0, c0, r, shade);
                cv.drawCircle(c0, c0, r, ring);
            }
            if (stickLook == Prefs.STICK_FULL) {
                // an arrow inside each side, in the arrow color (lit while its key is down)
                float tip = r * 0.86f, base = r * 0.72f, half = r * 0.085f;
                drawArrow(cv, path, c0, c0 - tip, c0 - half, c0 - base, c0 + half, c0 - base, (dirs & UP) != 0 ? lit : arrow);
                drawArrow(cv, path, c0, c0 + tip, c0 - half, c0 + base, c0 + half, c0 + base, (dirs & DOWN) != 0 ? lit : arrow);
                drawArrow(cv, path, c0 - tip, c0, c0 - base, c0 - half, c0 - base, c0 + half, (dirs & LEFT) != 0 ? lit : arrow);
                drawArrow(cv, path, c0 + tip, c0, c0 + base, c0 - half, c0 + base, c0 + half, (dirs & RIGHT) != 0 ? lit : arrow);
                // a mark at each corner (filled while that diagonal is held)
                float leg = r * 0.17f;
                corner(cv, c0 - r, c0 - r, leg, leg, (dirs & (UP | LEFT)) == (UP | LEFT));
                corner(cv, c0 + r, c0 - r, -leg, leg, (dirs & (UP | RIGHT)) == (UP | RIGHT));
                corner(cv, c0 - r, c0 + r, leg, -leg, (dirs & (DOWN | LEFT)) == (DOWN | LEFT));
                corner(cv, c0 + r, c0 + r, -leg, -leg, (dirs & (DOWN | RIGHT)) == (DOWN | RIGHT));
            }
            // what the four directions do, just outside the ring
            drawDirCaptions(cv, c0, c0, r + 11 * dp, 64 * dp);
            // the knob: a ball under the thumb, as far as the corners
            float kx = c0, ky = c0;
            if (pointer >= 0) {
                float dx = fx - c0, dy = fy - c0, len = (float) Math.hypot(dx, dy), max = r * TRAVEL;
                if (len > max) {
                    dx = dx * max / len;
                    dy = dy * max / len;
                }
                kx += dx;
                ky += dy;
            }
            float kr = r * KNOB;
            if (ballShade != null) {
                ballAt.setTranslate(kx, ky);
                ballShade.setLocalMatrix(ballAt);
                cv.drawCircle(kx, ky, kr, ball);
            }
            if (active) cv.drawCircle(kx, ky, kr, shine);
        }

        /** A corner mark: a small right triangle with its square corner at (x, y), legs running inwards. */
        private void corner(Canvas cv, float x, float y, float lx, float ly, boolean on) {
            path.reset();
            path.moveTo(x, y);
            path.lineTo(x + lx, y);
            path.lineTo(x, y + ly);
            path.close();
            if (on) cv.drawPath(path, lit);
            cv.drawPath(path, ring);
        }
    }

    // ------------------------------------------------------------------ thinner lines

    private static final float[][] AROUND_8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {0.7071f, 0.7071f}, {-0.7071f, 0.7071f}, {0.7071f, -0.7071f}, {-0.7071f, -0.7071f}};

    /**
     * An icon drawn white into a bitmap of w × h (plus a small margin), its lines {@code e} px thinner on each side.
     * The lines are part of the icons' shapes, so it's done on the picture: only what is inside the shape in eight
     * copies shifted by e is kept (the lines shrink by e on each side).
     */
    private static Bitmap thinned(Drawable d, int w, int h, float e) {
        int m = (int) Math.ceil(e) + 1;
        Bitmap src = Bitmap.createBitmap(w + 2 * m, h + 2 * m, Bitmap.Config.ARGB_8888);
        d.setTint(Color.WHITE);
        d.setBounds(m, m, m + w, m + h);
        d.draw(new Canvas(src));
        if (e < 0.05f) return src;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(src, 0, 0, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        for (float[] dir : AROUND_8) cv.drawBitmap(src, dir[0] * e, dir[1] * e, p);
        return out;
    }

    /** How heavy the lines and names are: 1 (thinnest) … Prefs.LINE_LEVELS (the icons as drawn). */
    void setLineLevel(int level) {
        lineLevel = Math.max(1, Math.min(Prefs.LINE_LEVELS, level));
        thin = THIN_DP[lineLevel - 1] * dp;
        label.setTypeface(Typeface.create(lineLevel <= 2 ? "sans-serif-light" : lineLevel <= 4 ? "sans-serif" : "sans-serif-medium",
                Typeface.NORMAL));
        stick.lines();
        for (Button b : buttons) if (b != null) b.rebuild();
        dpad.rebuild();
        for (Control v : views) v.invalidate();
    }

    /** How see-through the controls are: 0.2 … 1 (solid). */
    void setOpacity(float o) {
        opacity = Math.max(0.1f, Math.min(1f, o));
        applyAlpha();
    }

    private void applyAlpha() {
        for (Control v : views) v.setAlpha(opacity * (v.bound() ? 1f : 0.45f)); // a control that does nothing is fainter
    }

    /** The stick: Prefs.STICK_FULL (ring, arrows, corner marks), STICK_RING, or STICK_BALL (the ball alone). */
    void setStickLook(int look) {
        stickLook = look;
        stick.invalidate();
    }

    /** The stick's arrows, and the D-pad's lit arms. */
    void setArrowColor(int color) {
        arrowColor = color;
        arrow.setColor((color & 0x00FFFFFF) | 0xE6000000);
        stick.invalidate();
        dpad.tintArms();
    }
}
