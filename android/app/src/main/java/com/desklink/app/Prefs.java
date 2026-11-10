package com.desklink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Build;
import android.provider.Settings;

/** User settings + device helpers. */
final class Prefs {
    static final int QUALITY_AUTO = 0, QUALITY_HIGH = 1, QUALITY_BALANCED = 2, QUALITY_SAVER = 3;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean directTouch() { return sp.getBoolean("directTouch", false); }

    void setDirectTouch(boolean v) { sp.edit().putBoolean("directTouch", v).apply(); }

    boolean invertScroll() { return sp.getBoolean("invertScroll", false); }

    void setInvertScroll(boolean v) { sp.edit().putBoolean("invertScroll", v).apply(); }

    /** The phone keyboard's suggestions, autocorrect and swipe typing (on unless turned off in Settings). */
    boolean predictiveText() { return sp.getBoolean("predictive", true); }

    void setPredictiveText(boolean v) { sp.edit().putBoolean("predictive", v).apply(); }

    boolean clipboardSync() {
        if (!sp.getBoolean("clipboardOn25", false)) {
            // 2.5: on for everyone once (turned off earlier, it comes back on; it can be turned off again in Settings)
            sp.edit().putBoolean("clipboardOn25", true).putBoolean("clipboard", true).apply();
        }
        return sp.getBoolean("clipboard", true);
    }

    void setClipboardSync(boolean v) { sp.edit().putBoolean("clipboard", v).apply(); }

    /**
     * The last copy on the phone clipboard that DeskLink looked at: its clipboard timestamp, or on phones without
     * clipboard timestamps (stamp 0) a hash of its text. A copy that doesn't match is new and goes to the PC.
     */
    long clipSeenStamp() { return sp.getLong("clipSeenStamp", 0); }

    int clipSeenHash() { return sp.getInt("clipSeenHash", 0); }

    void setClipSeen(long stamp, int hash) { sp.edit().putLong("clipSeenStamp", stamp).putInt("clipSeenHash", hash).apply(); }

    boolean showStats() { return sp.getBoolean("stats", false); }

    void setShowStats(boolean v) { sp.edit().putBoolean("stats", v).apply(); }

    /** The short notes that confirm a tap (Shortcut bar stays on screen, Trackpad mode, Screen 2 of 2): on by default. */
    boolean tapNotes() { return sp.getBoolean("tapNotes", true); }

    void setTapNotes(boolean v) { sp.edit().putBoolean("tapNotes", v).apply(); }

    boolean fullScreen() { return sp.getBoolean("fullScreen", false); }

    void setFullScreen(boolean v) { sp.edit().putBoolean("fullScreen", v).apply(); }

    boolean alwaysShowBar() { return sp.getBoolean("showBar", false); }

    void setAlwaysShowBar(boolean v) { sp.edit().putBoolean("showBar", v).apply(); }

    /** The mobile-data flags last shown for a PC (Protocol.REMOTE_*), so the note appears again only when they change. */
    int remoteShown(String hostId) { return sp.getInt("remoteShown_" + hostId, -1); }

    void setRemoteShown(String hostId, int flags) { sp.edit().putInt("remoteShown_" + hostId, flags).apply(); }

    /** Gaming mode: what each control does, written on it: always, for 5 s when Gaming mode starts, or never. */
    static final int LABELS_ALWAYS = 0, LABELS_ON_START = 1, LABELS_OFF = 2;

    int padLabels() {
        int v = sp.getInt("padLabels", LABELS_ON_START);
        return v >= LABELS_ALWAYS && v <= LABELS_OFF ? v : LABELS_ON_START;
    }

    void setPadLabels(int v) { sp.edit().putInt("padLabels", v).apply(); }

    /**
     * Gaming mode's left thumb, like an emulator's pad type: Digital = the D-pad only; DualShock = an analog stick
     * (the default), which presses the same four keys but steers smoothly in between.
     */
    static final int PAD_DIGITAL = 0, PAD_DUALSHOCK = 1;

    int padType() { return sp.getInt("padType", PAD_DUALSHOCK) == PAD_DIGITAL ? PAD_DIGITAL : PAD_DUALSHOCK; }

    void setPadType(int v) { sp.edit().putInt("padType", v).apply(); }

    /** Gaming mode: how fast dragging on the empty space turns a game's camera, 1 … {@link #CAMERA_SPEED_LEVELS}. */
    static final int CAMERA_SPEED_LEVELS = 9, CAMERA_SPEED_DEFAULT = 5;
    /** Mouse counts per dp of finger travel, per level (5 is like an 800 DPI mouse moved as far as the finger). */
    private static final float[] CAMERA_SPEEDS = {2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f, 13f};

    int cameraSpeed() {
        return Math.max(1, Math.min(CAMERA_SPEED_LEVELS, sp.getInt("cameraSpeed", CAMERA_SPEED_DEFAULT)));
    }

    void setCameraSpeed(int level) { sp.edit().putInt("cameraSpeed", level).apply(); }

    static float cameraSpeedFactor(int level) {
        return CAMERA_SPEEDS[Math.max(1, Math.min(CAMERA_SPEED_LEVELS, level)) - 1];
    }

    /** Trackpad glide (momentum): how far the pointer carries on after a flick, 0 (off) … {@link #GLIDE_LEVELS}. */
    static final int GLIDE_LEVELS = 9, GLIDE_DEFAULT = 5;
    /** How far, compared with level 5 (the glide before 2.5). */
    private static final float[] GLIDE_DISTANCE = {0f, 0.25f, 0.4f, 0.6f, 0.8f, 1f, 1.3f, 1.7f, 2.2f, 3f};

    int glide() { return Math.max(0, Math.min(GLIDE_LEVELS, sp.getInt("glide", GLIDE_DEFAULT))); }

    void setGlide(int level) { sp.edit().putInt("glide", level).apply(); }

    static float glideDistance(int level) { return GLIDE_DISTANCE[Math.max(0, Math.min(GLIDE_LEVELS, level))]; }

    /** Gaming mode: how heavy the buttons' lines and names are, 1 (thinnest) … 5 (the icons as drawn). */
    static final int LINE_LEVELS = 5, LINE_DEFAULT = 3;

    int padLines() { return Math.max(1, Math.min(LINE_LEVELS, sp.getInt("padLines", LINE_DEFAULT))); }

    void setPadLines(int v) { sp.edit().putInt("padLines", v).apply(); }

    /** Gaming mode: how see-through the controls are, in tens of percent: 2 (20 %) … 10 (solid, the default). */
    static final int OPACITY_MIN = 2, OPACITY_MAX = 10;

    int padOpacity() { return Math.max(OPACITY_MIN, Math.min(OPACITY_MAX, sp.getInt("padOpacity", OPACITY_MAX))); }

    void setPadOpacity(int v) { sp.edit().putInt("padOpacity", v).apply(); }

    /** The DualShock stick: the ring with its arrows and corner marks, the ring alone, or only the ball. */
    static final int STICK_FULL = 0, STICK_RING = 1, STICK_BALL = 2;

    int stickLook() {
        int v = sp.getInt("stickLook", STICK_RING); // the ring and the ball, by default
        return v >= STICK_FULL && v <= STICK_BALL ? v : STICK_RING;
    }

    void setStickLook(int v) { sp.edit().putInt("stickLook", v).apply(); }

    /** The stick's arrows (and the D-pad's lit arms): one of {@link #ARROW_COLORS}; white (the first) by default. */
    static final int[] ARROW_COLORS = {0xFFFFFFFF, 0xFFE05A5A, 0xFF3B82F6, 0xFF1FAA8C, 0xFFF2C94C, 0xFFE83287,
            0xFFA363D9, 0xFFF2994A};

    int arrowColor() { return sp.getInt("arrowColor", ARROW_COLORS[0]); }

    void setArrowColor(int c) { sp.edit().putInt("arrowColor", c).apply(); }

    /**
     * The shortcut bar's Ctrl, Shift, Alt and Win: a tap keeps one pressed until it's tapped again (the default), or
     * it's only pressed while a finger holds it.
     */
    boolean modsHoldOnly() { return sp.getBoolean("modsHold", false); }

    void setModsHoldOnly(boolean v) { sp.edit().putBoolean("modsHold", v).apply(); }

    /** Gaming mode: △ ○ ✕ □ in color (green, pink, blue, purple) instead of white. */
    boolean padColors() { return sp.getBoolean("padColors", false); }

    void setPadColors(boolean v) { sp.edit().putBoolean("padColors", v).apply(); }

    /** Ask for the fingerprint (or the phone's PIN) when DeskLink opens. Off unless turned on in Settings. */
    boolean appLock() { return sp.getBoolean("appLock", false); }

    void setAppLock(boolean v) { sp.edit().putBoolean("appLock", v).apply(); }

    /** The top bar was hidden with its ˄ button (it stays hidden, next time too, until the handle brings it back). */
    boolean toolbarHidden() { return sp.getBoolean("toolbarHidden", false); }

    void setToolbarHidden(boolean v) { sp.edit().putBoolean("toolbarHidden", v).apply(); }

    /** The shortcut-bar group left open (ShortcutBar.GROUP_*), so it is open again next time. */
    int barGroup() { return sp.getInt("barGroup", ShortcutBar.GROUP_NONE); }

    void setBarGroup(int g) { sp.edit().putInt("barGroup", g).apply(); }

    /** Trackpad pointer speed, 1 (slowest) … {@link #POINTER_SPEED_LEVELS}. */
    static final int POINTER_SPEED_LEVELS = 9, POINTER_SPEED_DEFAULT = 3;
    /**
     * Speed multiplier per level, on top of version 1.0's movement and acceleration. Level 6 is exactly version
     * 1.0; the default (level 3, since 2.2) is slower.
     */
    private static final float[] POINTER_SPEEDS = {0.4f, 0.5f, 0.6f, 0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f};

    int pointerSpeed() {
        if (!sp.getBoolean("speedDefault3", false)) {
            // 2.2 lowered the default from 5 to 3: a 5 kept from before (the old default) becomes the new one
            SharedPreferences.Editor e = sp.edit().putBoolean("speedDefault3", true);
            if (sp.getInt("pointerSpeed", 0) == 5) e.putInt("pointerSpeed", POINTER_SPEED_DEFAULT);
            e.apply();
        }
        return Math.max(1, Math.min(POINTER_SPEED_LEVELS, sp.getInt("pointerSpeed", POINTER_SPEED_DEFAULT)));
    }

    void setPointerSpeed(int level) { sp.edit().putInt("pointerSpeed", level).apply(); }

    static float pointerSpeedFactor(int level) {
        return POINTER_SPEEDS[Math.max(1, Math.min(POINTER_SPEED_LEVELS, level)) - 1];
    }

    int quality() { return sp.getInt("quality", QUALITY_AUTO); }

    void setQuality(int q) { sp.edit().putInt("quality", q).apply(); }

    /** {maxWidth, maxHeight, maxFps, bitrateKbps(0 = auto)} for the current quality and network. */
    static int[] streamParams(Context c, int quality) {
        switch (quality) {
            case QUALITY_HIGH: return new int[] {1920, 1920, 60, 0};
            case QUALITY_BALANCED: return new int[] {1600, 1600, 60, 5000};
            case QUALITY_SAVER: return new int[] {1024, 1024, 24, 1000};
            default:
                return isMetered(c) ? new int[] {1280, 1280, 30, 2500} : new int[] {1920, 1920, 60, 0};
        }
    }

    static boolean isMetered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String deviceName(Context c) {
        try {
            String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (RuntimeException ignored) {
        }
        return Build.MODEL;
    }

    static String deviceModel() {
        String m = Build.MODEL;
        String b = Build.MANUFACTURER;
        if (b != null && !m.toLowerCase(java.util.Locale.ROOT).startsWith(b.toLowerCase(java.util.Locale.ROOT))) m = cap(b) + " " + m;
        return m + " (Android " + Build.VERSION.RELEASE + ")";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
