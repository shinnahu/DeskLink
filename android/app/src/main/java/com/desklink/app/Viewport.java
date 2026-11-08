package com.desklink.app;

import android.graphics.Matrix;

/**
 * Maps the remote desktop (desktop pixels) onto the view: fit-to-screen scale, zoom, pan,
 * and keeps the pointer visible above the keyboard.
 */
final class Viewport {
    float viewW = 1, viewH = 1;
    // safe area (system bars) and the part hidden by keyboard + shortcut bar
    float insetL, insetT, insetR, insetB;
    float obscuredBottom;
    int deskW = 1920, deskH = 1080;

    float scale = 1f; // view px per desktop px
    float ox, oy;      // view position of desktop (0,0)
    private float zoom = 1f; // relative to fit
    private boolean initialized;
    private boolean laidOut;

    void setView(float w, float h) {
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        relayout();
    }

    void setInsets(float l, float t, float r, float b, float obscured) {
        insetL = l;
        insetT = t;
        insetR = r;
        insetB = b;
        obscuredBottom = Math.max(b, obscured);
        relayout();
    }

    void setDesktop(int w, int h) {
        if (w <= 0 || h <= 0) return;
        boolean changed = w != deskW || h != deskH;
        deskW = w;
        deskH = h;
        if (changed || !initialized) {
            zoom = 1f;
            initialized = true;
            laidOut = false;
        }
        relayout();
    }

    float fitScale() {
        float aw = viewW - insetL - insetR;
        float ah = viewH - insetT - insetB;
        return Math.max(0.01f, Math.min(aw / deskW, ah / deskH));
    }

    float maxScale() { return Math.max(fitScale() * 6f, 3f); }

    float visLeft() { return insetL; }

    float visTop() { return insetT; }

    float visRight() { return viewW - insetR; }

    float visBottom() { return Math.max(visTop() + 1, viewH - obscuredBottom); }

    boolean isZoomed() { return zoom > 1.01f; }

    private void relayout() {
        float desiredScale = fitScale() * zoom;
        // keep the same desktop point at the visible center when the scale changes due to layout
        float cxView = (visLeft() + visRight()) / 2f, cyView = (visTop() + visBottom()) / 2f;
        float dx = (cxView - ox) / scale;
        float dy = (cyView - oy) / scale;
        if (!laidOut) {
            dx = deskW / 2f;
            dy = deskH / 2f;
            laidOut = true;
        }
        scale = desiredScale;
        ox = cxView - dx * scale;
        oy = cyView - dy * scale;
        clamp();
    }

    void clamp() {
        float cw = deskW * scale, ch = deskH * scale;
        float l = visLeft(), r = visRight(), t = visTop(), b = visBottom();
        if (cw <= r - l) ox = l + (r - l - cw) / 2f;
        else ox = Math.min(l, Math.max(r - cw, ox));
        if (ch <= b - t) oy = t + (b - t - ch) / 2f;
        else oy = Math.min(t, Math.max(b - ch, oy));
    }

    /** Pinch zoom around a focal point in view coordinates. */
    void zoomBy(float factor, float fx, float fy) {
        float fit = fitScale();
        float ns = Math.max(fit, Math.min(maxScale(), scale * factor));
        float dx = (fx - ox) / scale, dy = (fy - oy) / scale;
        scale = ns;
        zoom = ns / fit;
        ox = fx - dx * scale;
        oy = fy - dy * scale;
        clamp();
    }

    void panBy(float dx, float dy) {
        ox += dx;
        oy += dy;
        clamp();
    }

    void resetZoom() {
        zoom = 1f;
        relayout();
    }

    /** Pans minimally so that desktop point (px,py) is inside the visible area with a margin. */
    boolean ensureVisible(float px, float py, float margin) {
        float vx = ox + px * scale, vy = oy + py * scale;
        float l = visLeft() + margin, r = visRight() - margin, t = visTop() + margin, b = visBottom() - margin;
        float nox = ox, noy = oy;
        if (r > l) {
            if (vx < l) nox += l - vx;
            else if (vx > r) nox -= vx - r;
        }
        if (b > t) {
            if (vy < t) noy += t - vy;
            else if (vy > b) noy -= vy - b;
        }
        if (nox == ox && noy == oy) return false;
        ox = nox;
        oy = noy;
        clamp();
        return true;
    }

    float toViewX(float px) { return ox + px * scale; }

    float toViewY(float py) { return oy + py * scale; }

    float toDesktopX(float vx) { return (vx - ox) / scale; }

    float toDesktopY(float vy) { return (vy - oy) / scale; }

    /** TextureView transform: the frame (stretched to the view by default) mapped onto the desktop rect. */
    Matrix textureMatrix(Matrix m) {
        m.reset();
        m.setScale(deskW * scale / viewW, deskH * scale / viewH);
        m.postTranslate(ox, oy);
        return m;
    }
}
