package com.desklink.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import com.desklink.core.Protocol;

/**
 * Touch gestures → mouse input, Chrome-Remote-Desktop style.
 * Trackpad: drag = move pointer (let go while moving and it glides on, like a flung list), tap = click,
 * 2-finger tap = right click, 3-finger tap = middle click, 2-finger swipe = scroll (it carries on the same way),
 * hold (or double-tap) + drag = drag, pinch = zoom (only the picture; the pointer keeps its size and stays on
 * screen), 3-finger swipe up/down = keyboard.
 * Direct: tap = click at finger, hold = right click, drag = left drag, 2 fingers = scroll, pinch = zoom.
 */
final class TouchInput {
    interface Sink {
        boolean inputEnabled();
        /** New pointer position in desktop pixels; {@code follow} = keep it visible (trackpad). */
        void movePointer(float x, float y, boolean follow);
        void button(int button, boolean down);
        void wheel(int dy, int dx);
        void viewportChanged();
        void keyboard(boolean show);
        /** Touch and hold took (a drag started, or a right click went out): buzz and show it at the pointer. */
        void held();
        /** Camera mode: the mouse moves by this many counts, like a real mouse (a game's camera turns). */
        void moveRelative(int dx, int dy);
        void touching(boolean touching);
    }

    private static final int LONG_PRESS_MS = 450;
    private static final int DOUBLE_TAP_MS = 300;
    private static final int TWO_TAP_MAX_MS = 400;
    private static final int THREE_TAP_MAX_MS = 500;
    /**
     * Momentum: a touch on a pointer still gliding faster than this (dp/s) just stops it, without clicking.
     * A tap on a glide that is nearly over is a normal click.
     */
    private static final float GLIDE_CATCH_DP_S = 150f;

    private final Viewport vp;
    private final Sink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop, doubleTapSlop, density;
    private final float minFling, maxFling; // view px per second

    boolean direct;
    boolean invertScroll;
    /** Trackpad speed multiplier from the Pointer speed setting (1 = version 1.0). */
    float pointerSpeed = 1f;
    /** How far a flicked pointer glides on, compared with before 2.5 (Settings → Glide); 0 = it stops at once. */
    float glide = 1f;
    float px = 960, py = 540; // pointer, desktop pixels
    /**
     * Camera mode (Gaming mode while a game hides the pointer): one finger sliding on the picture moves the mouse by
     * counts, like a real mouse, so the game's camera turns. Taps don't click and touch-and-hold doesn't press: the
     * gamepad's buttons do that. Two and three fingers work as usual.
     */
    boolean camera;
    /** Camera mode: mouse counts per dp of finger travel (Settings → Camera speed). */
    float cameraSpeed = 5f;
    private float camAccX, camAccY; // the parts of a count not sent yet

    private int maxPointers;
    private boolean moved, dragging, longFired, active;
    private long downTime, lastEventTime;
    private float downX, downY, lastX, lastY;
    private int twoMode; // 0 undecided, 1 scroll, 2 zoom
    private int scrollAxis; // 0 free, 1 vertical, 2 horizontal
    private float startSpan, lastSpan, startFx, startFy, lastFx, lastFy;
    private float wheelAccX, wheelAccY;
    private boolean threeHandled;
    private float threeStartY;
    private boolean threeMoved;
    private boolean doubleTapCandidate;
    private long lastTapUp;
    private float lastTapX, lastTapY;
    // trackpad drag (button held): the finger that moves the pointer. A finger that lands during the drag takes over,
    // so a drag can go on with another finger (one hand runs out of screen); it ends when the last finger lifts.
    private int driverId = -1;
    private boolean handedOver;

    // momentum: the finger's slide carries on with Android's own fling physics (as in Chrome Remote Desktop)
    private VelocityTracker tracker;
    private final Scroller flinger;
    private boolean gliding, glideInterrupted;
    private int flingX, flingY; // where the virtual finger is, view px from where the real one let go
    private final Choreographer.FrameCallback glideFrame = this::glideStep;

    // scroll momentum: a two-finger swipe carries on the same way once the fingers let go
    private final Scroller scrollFlinger;
    private boolean scrollFlinging;
    private int sflX, sflY; // where the virtual fingers are, view px from where the real ones let go
    private final Choreographer.FrameCallback scrollFrame = this::scrollStep;

    private final Runnable longPress = this::onLongPress;

    TouchInput(Context c, Viewport vp, Sink sink) {
        this.vp = vp;
        this.sink = sink;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        doubleTapSlop = vc.getScaledDoubleTapSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        density = c.getResources().getDisplayMetrics().density;
        flinger = new Scroller(c); // one straight-line glide (OverScroller would bend diagonal ones)
        scrollFlinger = new Scroller(c);
    }

    void setPointer(float x, float y) {
        px = clampX(x);
        py = clampY(y);
    }

    private float clampX(float x) { return Math.max(0, Math.min(vp.deskW - 1, x)); }

    private float clampY(float y) { return Math.max(0, Math.min(vp.deskH - 1, y)); }

    /** Pointer acceleration (version 1.0's curve): finger speed in dp per ms → multiplier. */
    private static float accel(float dpPerMs) {
        return 1f + Math.min(1.8f, Math.max(0f, (dpPerMs - 0.25f) * 1.5f));
    }

    /** Cancels an in-progress gesture (e.g. when input gets disabled). */
    void reset() {
        stopGlide();
        stopScrollFling();
        handler.removeCallbacks(longPress);
        if (dragging) sink.button(Protocol.BTN_LEFT, false);
        dragging = false;
        active = false;
        sink.touching(false);
    }

    boolean onTouch(MotionEvent e) {
        int action = e.getActionMasked();
        if (!sink.inputEnabled()) {
            if (active) reset();
            return true;
        }
        if (tracker == null) tracker = VelocityTracker.obtain();
        if (action == MotionEvent.ACTION_DOWN) tracker.clear();
        tracker.addMovement(e);
        switch (action) {
            case MotionEvent.ACTION_DOWN: onDown(e); break;
            case MotionEvent.ACTION_POINTER_DOWN: onPointerDown(e); break;
            case MotionEvent.ACTION_MOVE: onMove(e); break;
            case MotionEvent.ACTION_POINTER_UP: onPointerUp(e); break;
            case MotionEvent.ACTION_UP: onUp(e); break;
            case MotionEvent.ACTION_CANCEL: reset(); break;
            default: break;
        }
        return true;
    }

    private void onDown(MotionEvent e) {
        glideInterrupted = catchGlide(); // touching a fast-gliding pointer just stops it (that tap doesn't click)
        active = true;
        sink.touching(true);
        maxPointers = 1;
        moved = false;
        dragging = false;
        handedOver = false;
        driverId = e.getPointerId(0);
        longFired = false;
        twoMode = 0;
        scrollAxis = 0;
        threeHandled = false;
        threeMoved = false;
        downX = lastX = e.getX();
        downY = lastY = e.getY();
        downTime = lastEventTime = e.getEventTime();
        doubleTapCandidate = !direct && !camera && downTime - lastTapUp < DOUBLE_TAP_MS
                && Math.hypot(downX - lastTapX, downY - lastTapY) < doubleTapSlop * 1.5;
        camAccX = camAccY = 0;
        if (direct) {
            px = clampX(vp.toDesktopX(downX));
            py = clampY(vp.toDesktopY(downY));
            sink.movePointer(px, py, false);
        }
        handler.removeCallbacks(longPress);
        if (!camera) handler.postDelayed(longPress, LONG_PRESS_MS); // a thumb resting on the camera mustn't press
    }

    private void onLongPress() {
        if (!active || maxPointers != 1 || moved || dragging || camera) return;
        longFired = true;
        if (direct) {
            sink.button(Protocol.BTN_RIGHT, true);
            sink.button(Protocol.BTN_RIGHT, false);
        } else {
            dragging = true;
            sink.button(Protocol.BTN_LEFT, true);
        }
        sink.held();
    }

    private void onPointerDown(MotionEvent e) {
        int n = e.getPointerCount();
        maxPointers = Math.max(maxPointers, n);
        handler.removeCallbacks(longPress);
        stopScrollFling(); // a finger landing again stops a scroll that is still carrying on
        if (dragging && !direct) {
            // Trackpad drag in progress: the new finger carries it on (no zoom, no scroll). The other fingers can
            // stay down or lift; the button stays pressed until the last one lifts.
            int i = e.getActionIndex();
            driverId = e.getPointerId(i);
            lastX = e.getX(i);
            lastY = e.getY(i);
            lastEventTime = e.getEventTime();
            handedOver = true;
            return;
        }
        if (n == 2) {
            twoMode = 0;
            startSpan = lastSpan = span(e);
            startFx = lastFx = focusX(e);
            startFy = lastFy = focusY(e);
            wheelAccX = wheelAccY = 0;
        } else if (n >= 3) {
            twoMode = -1;
            threeStartY = focusY(e);
            threeHandled = false;
        }
    }

    private void onMove(MotionEvent e) {
        int n = e.getPointerCount();
        if (handedOver && dragging && !direct) {
            int i = e.findPointerIndex(driverId);
            if (i < 0) return;
            float x = e.getX(i), y = e.getY(i);
            float dx = x - lastX, dy = y - lastY;
            long dt = Math.max(1, e.getEventTime() - lastEventTime);
            float speed = (float) Math.hypot(dx, dy) / dt / density; // dp per ms
            float gain = pointerSpeed * accel(speed) / vp.scale;
            px = clampX(px + dx * gain);
            py = clampY(py + dy * gain);
            sink.movePointer(px, py, true);
            lastX = x;
            lastY = y;
            lastEventTime = e.getEventTime();
            return;
        }
        if (n == 1 && maxPointers == 1) {
            float x = e.getX(), y = e.getY();
            if (!moved && Math.hypot(x - downX, y - downY) > slop) {
                moved = true;
                handler.removeCallbacks(longPress);
                if (!direct && doubleTapCandidate && !dragging) {
                    dragging = true;
                    sink.button(Protocol.BTN_LEFT, true);
                } else if (direct && !longFired && !dragging) {
                    dragging = true;
                    sink.button(Protocol.BTN_LEFT, true); // at the touch-down position
                }
            }
            if (moved) {
                if (direct) {
                    if (!longFired) {
                        px = clampX(vp.toDesktopX(x));
                        py = clampY(vp.toDesktopY(y));
                        sink.movePointer(px, py, false);
                    }
                } else if (camera && !dragging) {
                    // the game's camera: mouse counts, straight from the finger (no speed-up, so aiming stays even)
                    camAccX += (x - lastX) / density * cameraSpeed;
                    camAccY += (y - lastY) / density * cameraSpeed;
                    int cx = (int) camAccX, cy = (int) camAccY;
                    if (cx != 0 || cy != 0) {
                        camAccX -= cx;
                        camAccY -= cy;
                        sink.moveRelative(cx, cy);
                    }
                } else {
                    float dx = x - lastX, dy = y - lastY;
                    long dt = Math.max(1, e.getEventTime() - lastEventTime);
                    float speed = (float) Math.hypot(dx, dy) / dt / density; // dp per ms
                    float gain = pointerSpeed * accel(speed) / vp.scale;
                    px = clampX(px + dx * gain);
                    py = clampY(py + dy * gain);
                    sink.movePointer(px, py, true);
                }
            }
            lastX = x;
            lastY = y;
            lastEventTime = e.getEventTime();
        } else if (n == 2 && maxPointers == 2) {
            float fx = focusX(e), fy = focusY(e), sp = span(e);
            if (twoMode == 0) {
                float spanDelta = Math.abs(sp - startSpan);
                float focusDelta = (float) Math.hypot(fx - startFx, fy - startFy);
                if (spanDelta > slop * 1.5f && spanDelta > focusDelta) twoMode = 2;
                else if (focusDelta > slop) twoMode = 1;
            }
            if (twoMode == 1) {
                scroll(fx - lastFx, fy - lastFy);
            } else if (twoMode == 2) {
                if (lastSpan > 0) vp.zoomBy(sp / lastSpan, fx, fy);
                vp.panBy(fx - lastFx, fy - lastFy);
                sink.viewportChanged();
            }
            lastFx = fx;
            lastFy = fy;
            lastSpan = sp;
        } else if (n >= 3) {
            float fy = focusY(e);
            float d = fy - threeStartY;
            if (Math.abs(d) > slop) threeMoved = true;
            if (!threeHandled && Math.abs(d) > 50 * density) {
                threeHandled = true;
                sink.keyboard(d < 0);
            }
        }
    }

    private void scroll(float dxView, float dyView) {
        if (scrollAxis == 0) {
            if (Math.abs(dyView) > Math.abs(dxView) * 1.5f) scrollAxis = 1;
            else if (Math.abs(dxView) > Math.abs(dyView) * 1.5f) scrollAxis = 2;
        }
        if (scrollAxis == 1) dxView = 0;
        if (scrollAxis == 2) dyView = 0;
        float k = 2f / Math.max(0.05f, vp.scale); // ~120 units per 60 desktop px → content follows the fingers
        float sign = invertScroll ? -1f : 1f;
        wheelAccY += dyView * k * sign;
        wheelAccX += -dxView * k * sign;
        int wy = (int) wheelAccY, wx = (int) wheelAccX;
        if (wy != 0 || wx != 0) {
            wheelAccY -= wy;
            wheelAccX -= wx;
            sink.wheel(wy, wx);
        }
    }

    private void onPointerUp(MotionEvent e) {
        if (handedOver && dragging && !direct) {
            int up = e.getActionIndex();
            if (e.getPointerId(up) == driverId) {
                // the finger moving the drag lifted: the most recent of the others takes over, from where it is
                int next = up == e.getPointerCount() - 1 ? up - 1 : e.getPointerCount() - 1;
                driverId = e.getPointerId(next);
                lastX = e.getX(next);
                lastY = e.getY(next);
                lastEventTime = e.getEventTime();
            }
            return;
        }
        if (twoMode == 1 && e.getPointerCount() == 2) startScrollFling(e);
        if (twoMode == 2 && e.getPointerCount() - 1 < 2 && !direct) {
            if (dragging) {
                // hold-and-drag in progress (button down): moving the pointer would drag on the PC, so here
                // the picture goes to the pointer instead
                vp.ensureVisible(px, py, 24 * density);
                sink.viewportChanged();
            } else {
                keepPointerOnScreen();
            }
        }
    }

    /**
     * Trackpad, after a pinch: the picture stays exactly where the fingers left it. If the zoom left the pointer
     * outside the screen, the pointer comes to the nearest spot inside it (the picture doesn't jump back to it).
     */
    private void keepPointerOnScreen() {
        float m = 32 * density; // a bit more than the follow margin, so the next small move doesn't pan
        float l = vp.toDesktopX(vp.visLeft() + m), r = vp.toDesktopX(vp.visRight() - m);
        float t = vp.toDesktopY(vp.visTop() + m), b = vp.toDesktopY(vp.visBottom() - m);
        float nx = l < r ? clampX(Math.max(l, Math.min(r, px))) : px;
        float ny = t < b ? clampY(Math.max(t, Math.min(b, py))) : py;
        if (nx == px && ny == py) return;
        px = nx;
        py = ny;
        sink.movePointer(px, py, false);
    }

    private void onUp(MotionEvent e) {
        handler.removeCallbacks(longPress);
        long dur = e.getEventTime() - downTime;
        boolean wasDrag = dragging;
        if (dragging) {
            dragging = false;
            sink.button(Protocol.BTN_LEFT, false);
            lastTapUp = 0;
        } else if (maxPointers == 1 && !moved && !longFired && !camera) { // (camera: a tap does nothing)
            if (!glideInterrupted) {
                sink.button(Protocol.BTN_LEFT, true);
                sink.button(Protocol.BTN_LEFT, false);
                lastTapUp = e.getEventTime();
                lastTapX = e.getX();
                lastTapY = e.getY();
            }
        } else if (maxPointers == 2 && twoMode == 0 && dur < TWO_TAP_MAX_MS && !glideInterrupted) {
            sink.button(Protocol.BTN_RIGHT, true);
            sink.button(Protocol.BTN_RIGHT, false);
        } else if (maxPointers == 3 && !threeHandled && !threeMoved && dur < THREE_TAP_MAX_MS && !glideInterrupted) {
            sink.button(Protocol.BTN_MIDDLE, true);
            sink.button(Protocol.BTN_MIDDLE, false);
        }
        active = false;
        boolean glide = !direct && !camera && maxPointers == 1 && moved && !wasDrag && !longFired && startGlide();
        if (!glide) sink.touching(false); // while gliding we're still "touching": the PC's cursor echo is ignored
    }

    // ------------------------------------------------------------------ momentum

    /**
     * Trackpad: when the finger lets go while still moving, the pointer glides on as if the finger had kept
     * sliding and slowed down the way a flung list does. The faster the release, the further it goes; a finger
     * that had stopped leaves the pointer where it is.
     */
    private boolean startGlide() {
        if (tracker == null || glide <= 0f) return false;
        tracker.computeCurrentVelocity(1000, maxFling);
        float vx = tracker.getXVelocity(), vy = tracker.getYVelocity(); // view px per second
        if (Math.hypot(vx, vy) < minFling) return false;
        flinger.forceFinished(true);
        // a fling's distance goes with friction^-0.736 (Android's spline), so this friction makes it `glide` times as long
        flinger.setFriction(ViewConfiguration.getScrollFriction() * (float) Math.pow(glide, -1.359));
        flinger.fling(0, 0, Math.round(vx), Math.round(vy),
                Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        flingX = flingY = 0;
        gliding = true;
        Choreographer.getInstance().postFrameCallback(glideFrame);
        return true;
    }

    private void glideStep(long frameNanos) {
        if (!gliding) return;
        if (!sink.inputEnabled() || !flinger.computeScrollOffset()) {
            stopGlide();
            return;
        }
        int cx = flinger.getCurrX(), cy = flinger.getCurrY();
        float dx = cx - flingX, dy = cy - flingY; // how far the virtual finger slid this frame
        flingX = cx;
        flingY = cy;
        if (dx != 0 || dy != 0) {
            // the same mapping as a real drag at the virtual finger's current speed
            float gain = pointerSpeed * accel(flinger.getCurrVelocity() / density / 1000f) / vp.scale;
            float nx = clampX(px + dx * gain), ny = clampY(py + dy * gain);
            boolean blocked = nx == px && ny == py; // pushed against the edge of the desktop
            px = nx;
            py = ny;
            sink.movePointer(px, py, true);
            if (blocked) {
                stopGlide();
                return;
            }
        }
        if (flinger.isFinished()) {
            stopGlide();
            return;
        }
        Choreographer.getInstance().postFrameCallback(glideFrame);
    }

    /**
     * A finger touching down stops a pointer glide or a scroll that is still carrying on; true if it was still
     * fast, so that touch only "catches" it (it doesn't click).
     */
    private boolean catchGlide() {
        boolean fast = gliding && flinger.getCurrVelocity() / density >= GLIDE_CATCH_DP_S
                || scrollFlinging && scrollFlinger.getCurrVelocity() / density >= GLIDE_CATCH_DP_S;
        stopGlide();
        stopScrollFling();
        return fast;
    }

    /** Stops a glide; true if one was running. */
    private boolean stopGlide() {
        if (!gliding) return false;
        gliding = false;
        flinger.forceFinished(true);
        Choreographer.getInstance().removeFrameCallback(glideFrame);
        sink.touching(false);
        return true;
    }

    // ------------------------------------------------------------------ scroll momentum

    /**
     * Two-finger scroll: when the fingers let go while still moving, the scroll carries on and slows down the way
     * a flung list does. Fingers that had stopped leave it where it is.
     */
    private void startScrollFling(MotionEvent e) {
        if (tracker == null) return;
        tracker.computeCurrentVelocity(1000, maxFling);
        float vx = 0, vy = 0; // the two fingers' average: how fast the scroll was going (view px per second)
        for (int i = 0; i < 2; i++) {
            int id = e.getPointerId(i);
            vx += tracker.getXVelocity(id) / 2f;
            vy += tracker.getYVelocity(id) / 2f;
        }
        if (scrollAxis == 1) vx = 0;      // stay on the axis the swipe was locked to
        else if (scrollAxis == 2) vy = 0;
        if (Math.hypot(vx, vy) < minFling) return;
        scrollFlinger.forceFinished(true);
        scrollFlinger.fling(0, 0, Math.round(vx), Math.round(vy),
                Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        sflX = sflY = 0;
        scrollFlinging = true;
        Choreographer.getInstance().postFrameCallback(scrollFrame);
    }

    private void scrollStep(long frameNanos) {
        if (!scrollFlinging) return;
        if (!sink.inputEnabled() || !scrollFlinger.computeScrollOffset()) {
            stopScrollFling();
            return;
        }
        int cx = scrollFlinger.getCurrX(), cy = scrollFlinger.getCurrY();
        float dx = cx - sflX, dy = cy - sflY; // how far the virtual fingers slid this frame
        sflX = cx;
        sflY = cy;
        if (dx != 0 || dy != 0) scroll(dx, dy); // the same mapping as a real two-finger swipe
        if (scrollFlinger.isFinished()) {
            stopScrollFling();
            return;
        }
        Choreographer.getInstance().postFrameCallback(scrollFrame);
    }

    /** Stops a scroll that is still carrying on. */
    private void stopScrollFling() {
        if (!scrollFlinging) return;
        scrollFlinging = false;
        scrollFlinger.forceFinished(true);
        Choreographer.getInstance().removeFrameCallback(scrollFrame);
    }

    private static float focusX(MotionEvent e) {
        float s = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    private static float focusY(MotionEvent e) {
        float s = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    private static float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
}
