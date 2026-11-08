package com.desklink.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.desklink.core.HostConnection;
import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;
import com.desklink.core.Vk;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The remote desktop screen. */
public final class SessionActivity extends Activity implements HostConnection.Listener, RemoteSession.StateListener,
        TouchInput.Sink, KeyInputView.Sink, ShortcutBar.Sink, VideoDecoder.Callback, TextureView.SurfaceTextureListener,
        GamepadControls.Sink, GamepadEditor.Listener, SettingsScreen.Host {

    static final String EXTRA_HOST_ID = "hostId";
    private static final int REQ_PICK = 41;


    private HostStore store;
    private Prefs prefs;
    private String hostId;
    private String hostName = "";
    private RemoteSession session;
    private final Handler main = new Handler(Looper.getMainLooper());

    // views
    private FrameLayout root;
    private TextureView videoView;
    private CursorView cursorView;
    private View touchLayer;
    private KeyInputView keyInput;
    private LinearLayout toolbar;
    private View statusDot;
    private TextView titleText, pingText;
    private ImageButton btnMode;
    private ImageButton btnScreens, btnNextScreen; // the PC's screens: all at once, the next one
    private FrameLayout handle;
    private ShortcutBar shortcutBar;
    // bottom-right dock: a small [shortcuts][keyboard] pill, or the full shortcut bar with those two at its end
    private LinearLayout dock, dockButtons;
    private View dockDivider;
    private ImageButton btnBar, btnKeyboard;
    private SettingsScreen settings; // over everything while open (the session carries on underneath)
    private LinearLayout overlay;
    private ProgressBar overlaySpinner;
    private TextView overlayTitle, overlayDetail, overlayPrimary, overlaySecondary;
    private TextView weakBanner;
    private LinearLayout snack;
    private TextView snackText, snackAction;
    private ProgressBar snackProgress;
    private TextView statsText;

    // state
    private final Viewport viewport = new Viewport();
    private TouchInput touch;
    private volatile VideoDecoder decoder;
    private Surface surface;
    private Messages.VideoFormat format;          // main thread
    private volatile Messages.VideoFormat netFormat; // network thread (detects format changes)
    private Messages.HostInfo hostInfo;
    private final Map<Integer, Messages.CursorShape> shapes = new HashMap<>();
    private int shapeId = -1;
    private Ui.Bars bars = new Ui.Bars();
    private boolean imeVisible, barPinned, fullScreen, weak, toolbarShown = true, barWasVisible;
    private boolean touching;
    private long lastTouchMs;
    private int hostStatusFlags;
    private String hostStatusMsg = "";
    private RemoteSession.State state = RemoteSession.State.CONNECTING;
    private int stateDetail;
    private long waitingSince;
    private int monitorIndex = Protocol.MONITOR_PRIMARY; // the screen shown: an index, MONITOR_ALL, or the main one
    private int lastScreen = -1; // the last single screen shown (all screens → one screen goes back to it)
    private final AtomicInteger framesRendered = new AtomicInteger();
    private long statsBytes = -1;
    private volatile int lastRtt = -1;
    private ClipboardManager clipboard;
    private ClipSync clipSync;
    // Gaming mode: profiles, the on-screen gamepad, its editor
    private Gamepad gamepad;
    private GamepadControls pad;
    private GamepadEditor padEditor;
    private boolean padLabelsShown;  // the names were shown when the picture first came (Gaming mode on at start)
    private boolean labelsChanged;   // the names setting changed in Settings: shown again when it closes
    private boolean pcCursorShown = true; // the PC's pointer is on show (a game playing hides it)
    private final ArrayDeque<Uri> uploads = new ArrayDeque<>();
    private boolean uploading;
    private final Map<Integer, FileSaver.Target> downloads = new ConcurrentHashMap<>();
    private final Matrix matrix = new Matrix();
    private Object backCallback;
    // keyboard slide: the dock rides on the keyboard while it animates
    private boolean imeAnimating;
    private int imeAnimStart;
    // the system closes the keyboard when DeskLink loses focus (home, another app, a menu): it comes back after
    private boolean restoreKeyboard;
    private long imeHiddenAt, keyboardHideRequestedAt;

    private final Runnable hideSnack = () -> snack.setVisibility(View.GONE);
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            updateStats();
            main.postDelayed(this, 1000);
        }
    };
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> main.postDelayed(this::checkClipboard, 100);

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLock.install(this); // the optional fingerprint lock
        store = new HostStore(this);
        prefs = new Prefs(this);
        hostId = getIntent().getStringExtra(EXTRA_HOST_ID);
        PairedHost host = hostId == null ? null : store.get(hostId);
        if (host == null) {
            finish();
            return;
        }
        hostName = host.name();
        new Thread(VideoDecoder::maxSize, "dl-decoder-size").start(); // read once, off the main thread, for STREAM_START
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        Ui.edgeToEdge(this);
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipSync = clipboard == null ? null : newClipSync();

        touch = new TouchInput(this, viewport, this);
        touch.direct = prefs.directTouch();
        touch.invertScroll = prefs.invertScroll();
        touch.pointerSpeed = Prefs.pointerSpeedFactor(prefs.pointerSpeed());
        touch.cameraSpeed = Prefs.cameraSpeedFactor(prefs.cameraSpeed());
        touch.glide = Prefs.glideDistance(prefs.glide());
        barPinned = prefs.alwaysShowBar();
        fullScreen = prefs.fullScreen();
        gamepad = new Gamepad(this);
        padEditor = new GamepadEditor(this, gamepad, this);
        if (gamepad.enabled()) touch.direct = false; // Gaming mode: between the controls the screen is a trackpad
        buildUi();
        keyInput.setPredictive(prefs.predictiveText());
        applyFullScreen();
        registerBack();

        session = new RemoteSession(store, hostId, Prefs.deviceName(this), this, this);
        session.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startDecoder(); // released in onStop; the TextureView keeps its surface
        if (session != null) session.onForeground();
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        main.removeCallbacks(statsTick);
        main.post(statsTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(statsTick);
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener);
        if (session != null && !isFinishing()) {
            touch.reset();              // releases a drag in progress
            shortcutBar.releaseAll();   // key-ups for latched/held modifiers go out first
            pad.releaseAll();           // ...and for gamepad buttons being held
            session.onBackground();     // then RELEASE_ALL + STREAM_PAUSE
        }
        stopDecoder();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (touch != null) touch.reset(); // stops a pointer glide
        if (session != null) session.stop();
        stopDecoder();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            main.postDelayed(this::checkClipboard, 250);
            if (fullScreen) applyFullScreen();
            if (restoreKeyboard) {
                restoreKeyboard = false;
                // after the system has handled the focus change, or it may close the keyboard again
                main.postDelayed(() -> {
                    boolean inSession = state != RemoteSession.State.ENDED && state != RemoteSession.State.FAILED;
                    if (hasWindowFocus() && !imeVisible && inSession && !isFinishing()) showKeyboard();
                }, 150);
            }
        } else {
            // Leaving DeskLink (home, recents, another app, the notification shade) or opening one of its menus:
            // the system closes the keyboard. If it was open, open it again when DeskLink is back in front. (The
            // keyboard can report closing just before the focus change.)
            long now = SystemClock.uptimeMillis();
            boolean closedJustNow = now - imeHiddenAt < 400 && now - keyboardHideRequestedAt > 1000;
            restoreKeyboard = imeVisible || closedJustNow;
        }
    }

    private void finishSession() {
        if (touch != null) touch.reset();
        if (shortcutBar != null) shortcutBar.releaseAll();
        if (pad != null) pad.releaseAll();
        if (session != null) session.stop();
        finish();
    }

    // =================================================================== back

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::onBackRequested;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        onBackRequested();
    }

    private void onBackRequested() {
        if (Sheet.dismissTop(this)) return; // (on Android 13 and later an open sheet takes Back by itself)
        if (settingsShown()) {
            closeSettings();
            return;
        }
        if (imeVisible) {
            hideKeyboard();
            return;
        }
        if (state == RemoteSession.State.FAILED || state == RemoteSession.State.ENDED) {
            finishSession();
            return;
        }
        Sheet.confirm(this, getString(R.string.disconnect_confirm, hostName), null, getString(R.string.disconnect),
                R.drawable.ic_power, Sheet.DANGER, this::finishSession);
    }

    // =================================================================== UI construction

    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        videoView = new TextureView(this);
        videoView.setSurfaceTextureListener(this);
        root.addView(videoView, match);

        cursorView = new CursorView(this);
        root.addView(cursorView, new FrameLayout.LayoutParams(match));

        touchLayer = new View(this);
        touchLayer.setOnTouchListener((v, e) -> touch.onTouch(e));
        root.addView(touchLayer, new FrameLayout.LayoutParams(match));

        // Gaming mode's controls, above the touch layer: a finger between them still works the trackpad
        pad = new GamepadControls(this, this);
        pad.addTo(root, root.indexOfChild(touchLayer) + 1);
        pad.setProfile(gamepad.active());
        pad.setPadType(prefs.padType());
        pad.setLineLevel(prefs.padLines());
        pad.setOpacity(prefs.padOpacity() / 10f);
        pad.setStickLook(prefs.stickLook());
        pad.setArrowColor(prefs.arrowColor());
        pad.setColored(prefs.padColors());
        pad.setLabelMode(prefs.padLabels());
        pad.setVisible(gamepad.enabled());

        keyInput = new KeyInputView(this);
        keyInput.setSink(this);
        root.addView(keyInput, new FrameLayout.LayoutParams(1, 1));

        weakBanner = Ui.text(this, getString(R.string.conn_weak), 13, Color.BLACK, true);
        weakBanner.setBackground(Ui.rounded(Ui.color(this, R.color.amber), Ui.dp(this, 14)));
        weakBanner.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        weakBanner.setVisibility(View.GONE);
        root.addView(weakBanner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        statsText = Ui.text(this, "", 11, Color.WHITE, false);
        statsText.setTypeface(Typeface.MONOSPACE);
        statsText.setBackground(Ui.rounded(0x99000000, Ui.dp(this, 6)));
        statsText.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        statsText.setVisibility(prefs.showStats() ? View.VISIBLE : View.GONE);
        root.addView(statsText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        buildOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(match));

        buildSnack();
        root.addView(snack, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        buildDock();
        root.addView(dock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));

        buildToolbar();
        root.addView(toolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        buildHandle();
        root.addView(handle, new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 26), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                viewport.setView(r - l, b - t);
                updateTransform();
                if (bars != null) placePad();
            }
        });
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            bars = Ui.bars(insets, this);
            boolean was = imeVisible;
            imeVisible = bars.imeVisible;
            if (was && !imeVisible) imeHiddenAt = SystemClock.uptimeMillis();
            refreshDock();
            layoutChrome();
            if (imeVisible && !was) {
                viewport.ensureVisible(touch.px, touch.py, Ui.dp(this, 40));
                updateTransform();
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) trackKeyboardAnimation();
        setContentView(root);
        setToolbarShown(true);
    }

    /** Bottom-right dock: the keyboard button lives here (never in the top toolbar) next to the shortcut-bar toggle. */
    private void buildDock() {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.BOTTOM); // the two buttons line up with the bar's group row, under an open group
        dock.setClickable(true); // touches on the dock never reach the remote screen

        shortcutBar = new ShortcutBar(this, this, prefs.barGroup());
        shortcutBar.setHoldOnly(prefs.modsHoldOnly()); // Ctrl / Shift / Alt / Win: lock on tap, or only while held
        dock.addView(shortcutBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dockButtons = new LinearLayout(this);
        dockButtons.setGravity(Gravity.CENTER);
        dockDivider = new View(this);
        dockDivider.setBackgroundColor(Ui.color(this, R.color.stroke));
        dockButtons.addView(dockDivider, new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 32)));
        btnBar = dockButton(R.drawable.ic_shortcuts, v -> toggleBar());
        btnKeyboard = dockButton(R.drawable.ic_keyboard, v -> toggleKeyboard());
        int s = Ui.dp(this, 48);
        dockButtons.addView(btnBar, new LinearLayout.LayoutParams(s, s));
        dockButtons.addView(btnKeyboard, new LinearLayout.LayoutParams(s, s));
        dock.addView(dockButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshDock();
    }

    private ImageButton dockButton(int icon, View.OnClickListener l) {
        ImageButton b = Ui.iconButton(this, icon, Ui.color(this, R.color.text), "", v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        int p = Ui.dp(this, 12);
        b.setPadding(p, p, p, p);
        return b;
    }

    /** Toggle look of the bar button (highlighted while the bar is pinned) and keyboard / hide-keyboard icon. */
    private void refreshDock() {
        if (btnBar == null) return;
        int r = Ui.dp(this, 12);
        btnBar.setImageTintList(ColorStateList.valueOf(Ui.color(this, barPinned ? R.color.accent : R.color.text)));
        btnBar.setBackground(barPinned
                ? Ui.ripple(new InsetDrawable(Ui.rounded(0x473B82F6, r), Ui.dp(this, 4)), r)
                : Ui.borderlessRipple());
        btnBar.setSelected(barPinned);
        String bar = getString(barPinned ? R.string.bar_unpin : R.string.bar_pin);
        btnBar.setContentDescription(bar);
        btnKeyboard.setImageResource(imeVisible ? R.drawable.ic_keyboard_hide : R.drawable.ic_keyboard);
        String kb = getString(imeVisible ? R.string.tb_keyboard_hide : R.string.tb_keyboard);
        btnKeyboard.setContentDescription(kb);
        btnBar.setTooltipText(bar);
        btnKeyboard.setTooltipText(kb);
    }

    /** API 30+: the dock (and the snack above it) slides together with the keyboard instead of jumping. */
    @android.annotation.TargetApi(30)
    private void trackKeyboardAnimation() {
        root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
            @Override
            public void onPrepare(WindowInsetsAnimation a) {
                if ((a.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                imeAnimating = true;
                imeAnimStart = imeVisible ? bars.ime : 0; // still the state before the animation
            }

            @Override
            public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation a, WindowInsetsAnimation.Bounds b) {
                if ((a.getTypeMask() & WindowInsets.Type.ime()) != 0) followKeyboard(imeAnimStart);
                return b;
            }

            @Override
            public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
                if (imeAnimating) followKeyboard(insets.getInsets(WindowInsets.Type.ime()).bottom);
                return insets;
            }

            @Override
            public void onEnd(WindowInsetsAnimation a) {
                if ((a.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                imeAnimating = false;
                layoutChrome(); // resets the translation; a bar that rode the closing keyboard down collapses now
            }
        });
    }

    /** Laid out at the keyboard's final position; translated to where the keyboard's top edge is right now. */
    private void followKeyboard(int imeNow) {
        int finalEdge = imeVisible ? bars.ime : bars.bottom;
        float ty = finalEdge - Math.max(bars.bottom, imeNow);
        dock.setTranslationY(ty);
        snack.setTranslationY(ty);
    }

    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xE60D1117);
        toolbar.setClickable(true);

        statusDot = new View(this);
        statusDot.setBackground(Ui.circle(Ui.color(this, R.color.amber)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.setMarginStart(Ui.dp(this, 14));
        toolbar.addView(statusDot, dlp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleText = Ui.text(this, hostName, 15, Ui.color(this, R.color.text), true);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pingText = Ui.text(this, "", 12, Ui.color(this, R.color.text_dim), false);
        titles.addView(titleText);
        titles.addView(pingText);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(this, 10));
        toolbar.addView(titles, tlp);

        // (keyboard + shortcut-bar buttons live in the bottom dock, always reachable)
        // One mode button: tap = trackpad → direct touch → Gaming mode → trackpad; touch and hold = every mode,
        // with each gaming profile
        btnMode = addToolButton(R.drawable.ic_mouse, R.string.tb_mode, v -> cycleMode());
        btnMode.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showModeMenu(v);
            return true;
        });
        refreshModeButton();
        // the PC's screens, when it has more than one: all of them at once, and the next one (sending and getting
        // files are in ⋮)
        btnScreens = addToolButton(R.drawable.ic_screens_all, R.string.tb_screens_all, v -> toggleAllScreens());
        btnNextScreen = addToolButton(R.drawable.ic_screen_next, R.string.tb_screen_next, v -> nextScreen());
        refreshScreenButtons();
        addToolButton(R.drawable.ic_more, R.string.tb_menu, this::showMenu);
        // the bar stays until it's hidden here; the handle at the top brings it back
        addToolButton(R.drawable.ic_expand_less, R.string.tb_hide, v -> {
            prefs.setToolbarHidden(true);
            setToolbarShown(false);
        });
    }

    private ImageButton addToolButton(int icon, int description, final View.OnClickListener action) {
        ImageButton b = Ui.iconButton(this, icon, Ui.color(this, R.color.text), getString(description), action);
        toolbar.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        return b;
    }

    /**
     * The screen buttons show when the PC has more than one screen ("all screens" only on a PC that can): "all
     * screens" lit while they show, and then the next-screen button goes back to one screen.
     */
    private void refreshScreenButtons() {
        if (btnScreens == null) return;
        int n = hostInfo != null ? hostInfo.monitors.size() : 0;
        boolean all = monitorIndex == Protocol.MONITOR_ALL;
        btnNextScreen.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        btnScreens.setVisibility(n > 1 && hostInfo.can(Protocol.CAP_ALL_SCREENS) ? View.VISIBLE : View.GONE);
        int r = Ui.dp(this, 12);
        btnScreens.setImageTintList(ColorStateList.valueOf(Ui.color(this, all ? R.color.accent : R.color.text)));
        btnScreens.setBackground(all
                ? Ui.ripple(new InsetDrawable(Ui.rounded(0x473B82F6, r), Ui.dp(this, 4)), r)
                : Ui.borderlessRipple());
        btnScreens.setSelected(all);
        String a = getString(R.string.tb_screens_all);
        btnScreens.setContentDescription(a);
        btnScreens.setTooltipText(a);
        btnNextScreen.setImageResource(all ? R.drawable.ic_screen_one : R.drawable.ic_screen_next);
        String next = getString(all ? R.string.tb_screen_one : R.string.tb_screen_next);
        btnNextScreen.setContentDescription(next);
        btnNextScreen.setTooltipText(next);
    }

    /** Top bar: every screen at once (pinch to look closer), or back to the one shown before. */
    private void toggleAllScreens() {
        showScreen(monitorIndex == Protocol.MONITOR_ALL ? singleScreen() : Protocol.MONITOR_ALL);
    }

    /** Top bar: the next screen to the right (after the last one, the first); from all screens, back to one. */
    private void nextScreen() {
        if (hostInfo == null || hostInfo.monitors.size() < 2) return;
        if (monitorIndex == Protocol.MONITOR_ALL) {
            showScreen(singleScreen());
            return;
        }
        List<Messages.Monitor> order = screensInOrder();
        int cur = monitorIndex == Protocol.MONITOR_PRIMARY ? primaryScreen() : monitorIndex, at = 0;
        for (int i = 0; i < order.size(); i++) if (order.get(i).index == cur) at = i;
        showScreen(order.get((at + 1) % order.size()).index);
    }

    private void showScreen(int index) {
        if (index != Protocol.MONITOR_ALL) lastScreen = index;
        monitorIndex = index;
        HostConnection c = session != null ? session.connection() : null;
        if (c != null) c.selectMonitor(index);
        refreshScreenButtons();
        // which one, briefly (unless a file is on its way: its progress is in the same place)
        if (!uploading && downloads.isEmpty()) showSnack(screenName(index), -1, null, null, 1500);
    }

    /** The PC's screens left to right (then top to bottom), as Windows arranges them. */
    private List<Messages.Monitor> screensInOrder() {
        List<Messages.Monitor> l = new ArrayList<>(hostInfo.monitors);
        Collections.sort(l, (x, y) -> x.left != y.left ? Integer.compare(x.left, y.left) : Integer.compare(x.top, y.top));
        return l;
    }

    private int primaryScreen() {
        if (hostInfo != null) for (Messages.Monitor m : hostInfo.monitors) if (m.primary) return m.index;
        return 0;
    }

    /** The single screen to go back to from all screens: the last one shown, or the main one. */
    private int singleScreen() {
        return lastScreen >= 0 ? lastScreen : primaryScreen();
    }

    /** "All screens", or "Screen 2 of 3" counting from the left. */
    private String screenName(int index) {
        if (index == Protocol.MONITOR_ALL) return getString(R.string.tb_screens_all);
        if (hostInfo != null) {
            List<Messages.Monitor> order = screensInOrder();
            for (int i = 0; i < order.size(); i++)
                if (order.get(i).index == index) return getString(R.string.screen_n_of, i + 1, order.size());
        }
        return getString(R.string.menu_monitor);
    }

    /** The mode button shows the mode in use: a mouse (trackpad), a hand (direct touch) or a gamepad. */
    private void refreshModeButton() {
        if (btnMode == null) return;
        int m = mode();
        btnMode.setImageResource(m == MODE_GAMING ? R.drawable.ic_gamepad : m == MODE_DIRECT ? R.drawable.ic_touch : R.drawable.ic_mouse);
        String now = m == MODE_GAMING ? getString(R.string.mode_gaming_profile, gamepad.active().name)
                : getString(m == MODE_DIRECT ? R.string.touch_direct : R.string.touch_trackpad);
        String d = getString(R.string.tb_mode) + " · " + now;
        btnMode.setContentDescription(d);
        btnMode.setTooltipText(d);
    }

    private void buildHandle() {
        handle = new FrameLayout(this);
        handle.setBackground(Ui.ripple(Ui.rounded(0xCC0D1117, Ui.dp(this, 13)), Ui.dp(this, 13)));
        android.widget.ImageView chev = new android.widget.ImageView(this);
        chev.setImageResource(R.drawable.ic_expand_more);
        chev.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text)));
        handle.addView(chev, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        handle.setContentDescription(getString(R.string.tb_show));
        handle.setOnClickListener(v -> {
            prefs.setToolbarHidden(false);
            setToolbarShown(true);
        });
        handle.setVisibility(View.GONE);
    }

    private void buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setClickable(true); // swallow touches → no input while not live
        int p = Ui.dp(this, 28);
        overlay.setPadding(p, p, p, p);

        overlaySpinner = new ProgressBar(this);
        overlaySpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        overlay.addView(overlaySpinner, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        overlayTitle = Ui.text(this, "", 19, Color.WHITE, true);
        overlayTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 18);
        overlay.addView(overlayTitle, tlp);

        overlayDetail = Ui.text(this, "", 14, Ui.color(this, R.color.text_dim), false);
        overlayDetail.setGravity(Gravity.CENTER);
        overlayDetail.setMaxWidth(Ui.dp(this, 340));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 8);
        overlay.addView(overlayDetail, dlp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        overlayPrimary = Ui.button(this, "", true, null);
        overlaySecondary = Ui.button(this, "", false, null);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        buttons.addView(overlaySecondary, b1);
        buttons.addView(overlayPrimary, new LinearLayout.LayoutParams(b1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 22);
        overlay.addView(buttons, blp);
    }

    private void buildSnack() {
        snack = new LinearLayout(this);
        snack.setOrientation(LinearLayout.HORIZONTAL);
        snack.setGravity(Gravity.CENTER_VERTICAL);
        snack.setBackground(Ui.rounded(Ui.color(this, R.color.surface2), Ui.dp(this, 12)));
        snack.setElevation(Ui.dp(this, 6));
        int p = Ui.dp(this, 14);
        snack.setPadding(p, Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        snackText = Ui.text(this, "", 14, Ui.color(this, R.color.text), false);
        snack.addView(snackText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        snackProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        snackProgress.setMax(100);
        snackProgress.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 70), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMarginStart(Ui.dp(this, 10));
        snack.addView(snackProgress, plp);
        snackAction = Ui.text(this, "", 14, Ui.color(this, R.color.accent), true);
        snackAction.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        snack.addView(snackAction);
        snack.setVisibility(View.GONE);
    }

    /** Positions toolbar, dock, snack and the viewport for the current insets / keyboard / state. */
    private void layoutChrome() {
        int dp8 = Ui.dp(this, 8), dp12 = Ui.dp(this, 12);
        toolbar.setPadding(bars.left, bars.top, bars.right + Ui.dp(this, 4), Ui.dp(this, 2));

        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) handle.getLayoutParams();
        hl.topMargin = bars.top;
        handle.setLayoutParams(hl);

        // whole session, reconnects included (coming back from the background reconnects; nothing should flicker)
        boolean inSession = state != RemoteSession.State.ENDED && state != RemoteSession.State.FAILED;
        // keyboard sliding away: the bar rides it down and collapses when it's gone (onEnd)
        boolean closing = imeAnimating && !imeVisible && imeAnimStart > 0;
        // Gaming mode: the gamepad's buttons sit in the bottom corners, so the dock and a pinned bar step aside
        // (the keyboard still brings the bar, with the three-finger swipe)
        boolean gaming = gamepad.enabled() && inSession;
        // pinned = stays until the user turns it off: no timer, no hiding with the keyboard or on reconnects
        boolean barVisible = imeVisible || closing || (barPinned && inSession && !gaming);
        if (!barVisible && barWasVisible) shortcutBar.releaseAll(); // never leave Ctrl/Shift/Alt/Win latched out of sight
        barWasVisible = barVisible;
        boolean dockVisible = barVisible || (inSession && !gaming);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int bottomEdge = imeVisible ? bars.ime : bars.bottom; // where the keyboard / navigation bar ends up

        dock.setVisibility(dockVisible ? View.VISIBLE : View.GONE);
        shortcutBar.setVisibility(barVisible ? View.VISIBLE : View.GONE);
        dockDivider.setVisibility(barVisible ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dock.getLayoutParams();
        int startInset = rtl ? bars.right : bars.left, endInset = rtl ? bars.left : bars.right;
        if (barVisible) {
            // full-width bar: scrolling keys, then [shortcuts][keyboard] fixed at the end
            dock.setBackgroundColor(0xF0161B22);
            dockButtons.setBackground(null);
            dockButtons.setOrientation(LinearLayout.HORIZONTAL);
            dl.width = ViewGroup.LayoutParams.MATCH_PARENT;
            dl.setMargins(0, 0, 0, bottomEdge);
            dl.setMarginStart(startInset);
            dl.setMarginEnd(endInset);
        } else {
            // small pill in the corner (stacked in landscape, where it fits beside the picture)
            dock.setBackground(null);
            dockButtons.setBackground(Ui.rounded(0xCC0D1117, Ui.dp(this, 24)));
            dockButtons.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            dl.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            dl.setMargins(0, 0, 0, bottomEdge + dp12);
            dl.setMarginStart(0);
            dl.setMarginEnd(endInset + dp12);
        }
        dock.setLayoutParams(dl);
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) dockButtons.getLayoutParams();
        bl.bottomMargin = barVisible ? Ui.dp(this, 6) : 0; // level with the group row (the bar's bottom padding)
        dockButtons.setLayoutParams(bl);
        if (!imeAnimating) {
            dock.setTranslationY(0);
            snack.setTranslationY(0);
        }
        int barH = barVisible ? shortcutBar.barHeight() : 0;

        int snackBottom = bottomEdge + barH + dp8, snackEnd = dp12;
        if (dockVisible && !barVisible) {
            if (landscape) snackEnd += Ui.dp(this, 48) + dp12; // pill stands beside the snack
            else snackBottom += Ui.dp(this, 48) + dp12;        // pill sits below it
        }
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) snack.getLayoutParams();
        sl.setMargins(bars.left + dp12, 0, bars.right + snackEnd, snackBottom);
        snack.setLayoutParams(sl);

        FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) weakBanner.getLayoutParams();
        wl.topMargin = bars.top + Ui.dp(this, 62);
        weakBanner.setLayoutParams(wl);
        FrameLayout.LayoutParams stl = (FrameLayout.LayoutParams) statsText.getLayoutParams();
        stl.topMargin = bars.top + Ui.dp(this, 62);
        stl.leftMargin = bars.left + dp8;
        statsText.setLayoutParams(stl);

        overlay.setPadding(bars.left + Ui.dp(this, 28), bars.top + Ui.dp(this, 56), bars.right + Ui.dp(this, 28), bottomEdge + Ui.dp(this, 28));

        viewport.setInsets(bars.left, topEdge(), bars.right, bars.bottom, bottomEdge + barH);
        updateTransform();
        placePad();
    }

    /** Gaming mode's controls: below the top bar, above the navigation bar, the keyboard and the shortcut bar. */
    private void placePad() {
        if (pad == null || bars == null) return;
        int bottom = (imeVisible ? bars.ime : bars.bottom)
                + (shortcutBar.getVisibility() == View.VISIBLE ? shortcutBar.barHeight() : 0);
        pad.place(root.getWidth(), root.getHeight(), bars.left, topEdge(), bars.right, bottom);
    }

    /**
     * The top bar never hides by itself: it stays until its ˄ button hides it (remembered for next time), and the ˅
     * handle brings it back. While connecting or reconnecting it's always shown, since it shows the connection.
     * While shown, the picture (and the gamepad's top buttons) sit below it instead of under it.
     */
    private void setToolbarShown(boolean show) {
        if (!show && state != RemoteSession.State.LIVE) show = true; // keep it while connecting / lost
        toolbarShown = show;
        toolbar.animate().cancel();
        if (show) {
            toolbar.setVisibility(View.VISIBLE);
            toolbar.setAlpha(1f);
            handle.setVisibility(View.GONE);
        } else {
            toolbar.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (!toolbarShown) toolbar.setVisibility(View.GONE);
            }).start();
            handle.setVisibility(View.VISIBLE);
        }
        layoutChrome();
    }

    /**
     * Where the top bar ends (the picture starts below it), or the status bar's edge while it's hidden. Hidden by
     * the user but shown for a reconnect, it just lies over the picture, so the picture doesn't jump each time.
     */
    private int topEdge() {
        boolean kept = toolbarShown && !prefs.toolbarHidden();
        return kept ? bars.top + Ui.dp(this, 50) : bars.top; // the bar: 48 dp of buttons + 2 dp padding
    }

    // =================================================================== viewport / cursor

    private void updateTransform() {
        if (videoView == null) return;
        videoView.setTransform(viewport.textureMatrix(matrix));
        // A TextureView applies a new transform only when it redraws, and by itself it redraws only when a new
        // video frame arrives. The PC sends frames only when its screen changes, so without this a pinch or a
        // pan on a still screen showed nothing, then jumped the next time something on the PC changed.
        videoView.invalidate();
        updateCursor();
    }

    private void updateCursor() {
        Messages.CursorShape s = shapes.get(shapeId);
        float base = s != null && s.height > 0 ? s.height : 24f;
        // One on-screen size at every zoom level: pinching zooms the picture, never the pointer.
        float cs = Ui.dp(this, 28) / base;
        cursorView.setPosition(viewport.toViewX(touch.px), viewport.toViewY(touch.py), cs);
    }

    // =================================================================== state UI

    @Override
    public void onState(RemoteSession.State s, int detail, String message) {
        state = s;
        stateDetail = detail;
        if (s == RemoteSession.State.WAITING_VIDEO) {
            waitingSince = System.currentTimeMillis();
            if (detail == RemoteSession.WAIT_NEW_STREAM) {
                // A new/resumed stream: the PC re-reports lock / no-signal via HOST_STATUS, and the next
                // keyframe marks the stream live again. (On WAIT_VIDEO_RESET the decoder already reset itself.)
                hostStatusFlags = 0;
                VideoDecoder d = decoder;
                if (d != null) d.resetStream();
            }
        }
        if (s != RemoteSession.State.LIVE && s != RemoteSession.State.WAITING_VIDEO) {
            shortcutBar.resetSilently();
            pad.resetSilently();
            touch.reset();
        }
        if (s == RemoteSession.State.LIVE && !padLabelsShown) {
            // opened in Gaming mode: the names show (and fade) once the picture is there, not behind "Connecting…"
            padLabelsShown = true;
            if (gamepad.enabled()) pad.showLabels();
        }
        refreshOverlay();
        updateStatusDot();
        // live: as the user left it; otherwise always shown (it carries the connection state)
        setToolbarShown(s != RemoteSession.State.LIVE || !prefs.toolbarHidden()); // also lays out (the dock goes when the session ends)
    }

    private boolean isLive() {
        return state == RemoteSession.State.LIVE && (hostStatusFlags & (Protocol.HS_SECURE_DESKTOP | Protocol.HS_NO_SIGNAL)) == 0;
    }

    private void refreshOverlay() {
        String title = null, detail = "";
        boolean spinner = false;
        int bg = 0xE6000000;
        String primary = null, secondary = null;
        Runnable onPrimary = null, onSecondary = null;
        boolean ever = session != null && session.everConnected();
        switch (state) {
            case CONNECTING:
                title = getString(R.string.conn_connecting, hostName);
                spinner = true;
                bg = Color.BLACK;
                secondary = getString(R.string.cancel);
                onSecondary = this::finishSession;
                break;
            case WAITING_VIDEO:
                if ((hostStatusFlags & Protocol.HS_SECURE_DESKTOP) != 0) {
                    // locked PC / UAC prompt: no frames will come until it's back — say so instead of spinning
                    title = getString(R.string.conn_secure);
                    detail = getString(R.string.conn_secure_hint);
                } else if ((hostStatusFlags & Protocol.HS_NO_SIGNAL) != 0 && hostStatusMsg != null && !hostStatusMsg.isEmpty()) {
                    title = hostStatusMsg;
                    spinner = true;
                } else {
                    title = getString(R.string.conn_waiting_video);
                    spinner = true;
                }
                bg = ever && format != null ? 0xD9000000 : Color.BLACK;
                secondary = getString(R.string.cancel);
                onSecondary = this::finishSession;
                break;
            case LOST: {
                int attempt = Math.max(0, stateDetail);
                spinner = true;
                bg = ever ? 0xE0000000 : Color.BLACK;
                if (stateDetail == RemoteSession.LOST_BACKGROUND) {
                    title = getString(R.string.conn_paused_bg);
                    detail = getString(R.string.conn_reconnecting_simple);
                } else if (!ever && attempt >= 3) {
                    title = getString(R.string.conn_unreachable, hostName);
                    detail = unreachableHint();
                } else {
                    title = ever ? getString(R.string.conn_lost) : getString(R.string.conn_connecting, hostName);
                    detail = attempt > 0 ? getString(R.string.conn_reconnecting, attempt) : getString(R.string.conn_reconnecting_simple);
                    if (attempt >= 3) detail += "\n\n" + unreachableHint();
                }
                secondary = getString(R.string.cancel);
                onSecondary = this::finishSession;
                break;
            }
            case ENDED:
                switch (stateDetail) {
                    case RemoteSession.END_STOPPED_ON_PC: title = getString(R.string.conn_ended_pc); break;
                    case RemoteSession.END_REPLACED: title = getString(R.string.conn_ended_replaced); break;
                    case RemoteSession.END_HOST_EXIT: title = getString(R.string.conn_ended_host_exit); break;
                    default: title = getString(R.string.conn_disconnected);
                }
                bg = 0xF0000000;
                primary = getString(R.string.reconnect);
                onPrimary = () -> session.retryNow();
                secondary = getString(R.string.close);
                onSecondary = this::finishSession;
                break;
            case FAILED:
                bg = 0xF0000000;
                switch (stateDetail) {
                    case RemoteSession.FAIL_UNLINKED: title = getString(R.string.conn_unlinked, hostName); break;
                    case RemoteSession.FAIL_IDENTITY: title = getString(R.string.conn_identity, hostName); break;
                    case RemoteSession.FAIL_BUSY:
                        title = getString(R.string.conn_busy);
                        primary = getString(R.string.retry);
                        onPrimary = () -> session.retryNow();
                        break;
                    case RemoteSession.FAIL_VERSION: title = getString(R.string.conn_version); break;
                    default: title = getString(R.string.conn_disconnected);
                }
                secondary = getString(R.string.close);
                onSecondary = this::finishSession;
                break;
            case LIVE:
            default:
                if ((hostStatusFlags & Protocol.HS_SECURE_DESKTOP) != 0) {
                    title = getString(R.string.conn_secure);
                    detail = getString(R.string.conn_secure_hint);
                    bg = 0xD9000000;
                } else if ((hostStatusFlags & Protocol.HS_NO_SIGNAL) != 0) {
                    title = hostStatusMsg == null || hostStatusMsg.isEmpty() ? getString(R.string.conn_waiting_video) : hostStatusMsg;
                    spinner = true;
                    bg = 0xD9000000;
                }
                break;
        }
        if (title == null) {
            overlay.setVisibility(View.GONE);
            return;
        }
        overlay.setVisibility(View.VISIBLE);
        overlay.setBackgroundColor(bg);
        overlaySpinner.setVisibility(spinner ? View.VISIBLE : View.GONE);
        overlayTitle.setText(title);
        overlayDetail.setText(detail);
        overlayDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        setOverlayButton(overlayPrimary, primary, onPrimary);
        setOverlayButton(overlaySecondary, secondary, onSecondary);
    }

    private void setOverlayButton(TextView b, String label, final Runnable action) {
        if (label == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
    }

    private void updateStatusDot() {
        int c;
        switch (state) {
            case LIVE: c = weak ? Ui.color(this, R.color.amber) : Ui.color(this, R.color.green); break;
            case WAITING_VIDEO:
            case CONNECTING: c = Ui.color(this, R.color.amber); break;
            default: c = Ui.color(this, R.color.red);
        }
        statusDot.setBackground(Ui.circle(c));
    }

    @Override
    public void onWeak(boolean w) {
        weak = w;
        weakBanner.setVisibility(w && state == RemoteSession.State.LIVE ? View.VISIBLE : View.GONE);
        updateStatusDot();
    }

    @Override
    public void onConnected(HostConnection c, boolean background) {
        hostInfo = c.hostInfo();
        updateCameraMode(); // whether this PC can take mouse moves for a game's camera
        refreshScreenButtons(); // shown when it has more than one screen
        // what reaches this PC over mobile data: said once, and again whenever it changes
        final int rf = hostInfo != null ? hostInfo.remoteFlags : -1;
        if (rf >= 0 && (rf & Protocol.REMOTE_CHECKING) == 0 && rf != prefs.remoteShown(hostId)) {
            prefs.setRemoteShown(hostId, rf);
            final String note = remoteSummary(rf);
            main.postDelayed(() -> showSnack(note, -1, null, null, 8000), 1500);
        }
        if (hostInfo != null && hostInfo.hostName != null && !hostInfo.hostName.isEmpty()) {
            PairedHost h = store.get(hostId);
            if (h != null) hostName = h.name();
            titleText.setText(hostName);
        }
        shapes.clear();
        shapeId = -1;
        cursorView.setShape(null);
        hostStatusFlags = 0;
        VideoDecoder d = decoder;
        if (d != null) d.resetStream();
        // Always START (RemoteSession pauses right after when we're in the background, and a later RESUME
        // then continues this stream).
        int[] p = Prefs.streamParams(this, prefs.quality());
        // The phone clipboard goes first. The PC sends its own clipboard as soon as the stream starts, and that used
        // to replace a copy just made on the phone before the phone could send it. Now a new copy on the phone is
        // sent before the stream starts and wins; if the phone clipboard can't be read yet (DeskLink isn't in
        // front), the PC's clipboard waits until it can.
        if (clipSync != null) clipSync.restart();
        checkClipboard(c);
        int[] dec = VideoDecoder.maxSize(); // a picture of all the PC's screens stays within what this phone decodes
        c.startStream(monitorIndex, p[0], p[1], p[2], p[3], prefs.clipboardSync(), dec[0], dec[1]);
        statsBytes = -1;
        startNextUpload();
    }

    // =================================================================== HostConnection.Listener (network thread)

    @Override
    public void onVideoFormat(Messages.VideoFormat f) {
        Messages.VideoFormat prev = netFormat;
        netFormat = f;
        final boolean changed = prev != null && (prev.width != f.width || prev.height != f.height || prev.monitor != f.monitor
                || prev.desktopWidth != f.desktopWidth || prev.desktopHeight != f.desktopHeight);
        VideoDecoder d = decoder;
        if (d != null) {
            d.setSize(f.width, f.height);
            // Synchronously (network thread), so the keyframe that follows VIDEO_FORMAT is not dropped.
            if (changed) d.resetStream();
        }
        main.post(() -> {
            boolean desktopChanged = format == null || format.desktopWidth != f.desktopWidth || format.desktopHeight != f.desktopHeight;
            format = f;
            // the screen the PC shows (what was asked for, or the main one when all screens didn't work out there)
            if (f.monitor == Protocol.MONITOR_ALL || (hostInfo != null && f.monitor < hostInfo.monitors.size())) {
                monitorIndex = f.monitor;
                if (f.monitor != Protocol.MONITOR_ALL) lastScreen = f.monitor;
                refreshScreenButtons();
            }
            viewport.setDesktop(f.desktopWidth, f.desktopHeight);
            if (desktopChanged) touch.setPointer(f.desktopWidth / 2f, f.desktopHeight / 2f);
            updateTransform();
            if (changed && session != null) {
                session.videoReset(); // size / monitor changed: the old picture is not live anymore
                HostConnection c = session.connection();
                if (c != null) c.requestKeyframe();
            }
        });
    }

    @Override
    public void onVideoFrame(int frameId, boolean keyframe, long timestampUs, byte[] data, int off, int len) {
        VideoDecoder d = decoder;
        if (d != null) d.submit(data, off, len, keyframe, timestampUs);
    }

    @Override
    public void onCursorShape(Messages.CursorShape s) {
        main.post(() -> {
            shapes.put(s.id, s);
            if (s.id == shapeId) {
                cursorView.setShape(s);
                updateCursor();
            }
        });
    }

    @Override
    public void onCursorState(int x, int y, boolean visible, int sid) {
        main.post(() -> {
            cursorView.setCursorVisible(visible);
            if (visible != pcCursorShown) {
                pcCursorShown = visible;
                updateCameraMode(); // a game took the pointer away: the picture turns its camera now
            }
            if (sid != shapeId) {
                shapeId = sid;
                cursorView.setShape(shapes.get(sid));
            }
            if (!touching && System.currentTimeMillis() - lastTouchMs > 400) touch.setPointer(x, y);
            updateCursor();
        });
    }

    @Override
    public void onHostStatus(int flags, String message) {
        main.post(() -> {
            hostStatusFlags = flags;
            hostStatusMsg = message;
            if (!isLive()) touch.reset();
            refreshOverlay();
        });
    }

    @Override
    public void onClipboard(String text) {
        main.post(() -> onHostClipboard(text));
    }

    @Override
    public void onNotice(int level, String text) {
        main.post(() -> showSnack(text, -1, null, null, 4000));
    }

    @Override
    public void onRtt(int rttMs) { lastRtt = rttMs; }

    @Override
    public void onFileRequestNone() {
        main.post(() -> showSnack(getString(R.string.files_none_chosen), -1, null, null, 3000));
    }

    @Override
    public HostConnection.IncomingFileSink onIncomingFile(int id, String name, long size, String mime) {
        try {
            FileSaver.Target t = FileSaver.create(this, name, mime);
            downloads.put(id, t);
            main.post(() -> showSnack(getString(R.string.files_receiving, t.name, 0), 0, null, null, 0));
            return t;
        } catch (IOException | RuntimeException e) {
            main.post(() -> showSnack(getString(R.string.files_receive_failed, name), -1, null, null, 4000));
            return null;
        }
    }

    @Override
    public void onIncomingFileProgress(int id, long received, long size) {
        final FileSaver.Target t = downloads.get(id);
        if (t == null) return;
        final int pct = size > 0 ? (int) Math.min(100, received * 100 / size) : 0;
        main.post(() -> showSnack(getString(R.string.files_receiving, t.name, pct), pct, null, null, 0));
    }

    @Override
    public void onIncomingFileDone(int id, int status, String error) {
        final FileSaver.Target t = downloads.remove(id);
        if (t == null) return;
        main.post(() -> {
            if (status == Protocol.FD_OK) {
                if (t.uri != null) {
                    showSnack(getString(R.string.files_received, t.name), -1, getString(R.string.open), () -> openFile(t), 8000);
                } else {
                    showSnack(getString(R.string.files_received_app, t.where()), -1, null, null, 8000);
                }
            } else {
                showSnack(getString(R.string.files_receive_failed, t.name), -1, null, null, 5000);
            }
        });
    }

    // =================================================================== decoder

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        viewport.setView(w, h);
        updateTransform();
        startDecoder();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        viewport.setView(w, h);
        updateTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stopDecoder();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void startDecoder() {
        if (surface == null || decoder != null) return;
        VideoDecoder d = new VideoDecoder(surface, this);
        if (format != null) d.setSize(format.width, format.height);
        d.start();
        decoder = d;
        HostConnection c = session == null ? null : session.connection();
        if (c != null) c.requestKeyframe();
    }

    private void stopDecoder() {
        VideoDecoder d = decoder;
        decoder = null;
        if (d != null) d.release();
    }

    @Override
    public void onFirstFrame() {
        main.post(() -> {
            if (session != null) session.firstFrame();
        });
    }

    @Override
    public void onFrameRendered() { framesRendered.incrementAndGet(); }

    @Override
    public void onNeedKeyframe() {
        HostConnection c = session == null ? null : session.connection();
        if (c != null) c.requestKeyframe();
    }

    @Override
    public void onDecoderReset() {
        // decoder thread: the frame on screen is stale until the next keyframe renders
        main.post(() -> {
            if (session != null) session.videoReset();
        });
    }

    // =================================================================== TouchInput.Sink

    @Override
    public boolean inputEnabled() { return isLive(); }

    @Override
    public void movePointer(float x, float y, boolean follow) {
        HostConnection c = session.connection();
        if (c != null) c.mouseMove(Math.round(x), Math.round(y));
        if (follow && viewport.ensureVisible(x, y, Ui.dp(this, 24))) {
            updateTransform();
        } else {
            updateCursor();
        }
    }

    @Override
    public void button(int button, boolean down) {
        if (down) {
            shortcutBar.markUsed();       // Shift/Ctrl held on the bar + click = chord, not a latch
            keyInput.resetComposition();  // the click moves the caret on the PC
        }
        HostConnection c = session.connection();
        if (c != null) c.mouseButton(button, down); // ups always go out so nothing stays pressed
    }

    @Override
    public void wheel(int dy, int dx) {
        HostConnection c = session.connection();
        if (c != null) c.mouseWheel(dy, dx);
    }

    @Override
    public void viewportChanged() { updateTransform(); }

    @Override
    public void keyboard(boolean show) {
        if (show) showKeyboard();
        else hideKeyboard();
    }

    /** Camera mode: the mouse moves by counts (the game's camera turns). */
    @Override
    public void moveRelative(int dx, int dy) {
        HostConnection c = session.connection();
        if (c != null && isLive()) c.mouseRel(dx, dy);
    }

    /**
     * Gaming mode on, and a game hides the PC's pointer (it's playing, not in a menu): one finger on the picture
     * turns the game's camera, like a mouse or a controller's right stick. The PC must know MOUSE_REL (DeskLink 2.1).
     */
    private void updateCameraMode() {
        touch.camera = gamepad.enabled() && !pcCursorShown && hostInfo != null && hostInfo.can(Protocol.CAP_REL_MOUSE);
    }

    @Override
    public void held() {
        touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        cursorView.showHold(); // a ring pops up round the pointer: the press can be seen, not only felt
    }

    @Override
    public void touching(boolean t) {
        touching = t;
        lastTouchMs = System.currentTimeMillis();
    }

    // =================================================================== keyboard

    @Override
    public void typeText(String text) {
        HostConnection c = session.connection();
        if (c == null || !isLive()) return;
        shortcutBar.markUsed();
        if (shortcutBar.modifiersActive()) {
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                int vk = Vk.fromChar(cp);
                if (vk != 0) c.keyTap(vk);
                else c.text(new String(Character.toChars(cp)));
            }
        } else {
            c.text(text);
        }
    }

    @Override
    public void keyTap(int vk) {
        HostConnection c = session.connection();
        if (c == null || !isLive()) return;
        shortcutBar.markUsed();
        c.keyTap(vk);
    }

    /** IME / hardware keyboard key. Key-ups are always forwarded so nothing stays stuck on the PC. */
    @Override
    public void keyEvent(int vk, boolean down) {
        HostConnection c = session.connection();
        if (c == null) return;
        if (down) {
            if (!isLive()) return;
            shortcutBar.markUsed();
        }
        c.key(vk, down);
    }

    @Override
    public boolean modifiersActive() { return shortcutBar.modifiersActive(); }

    /** Shortcut-bar key. Key-ups are always forwarded so nothing stays stuck on the PC. */
    @Override
    public void key(int vk, boolean down) {
        HostConnection c = session.connection();
        if (c == null) return;
        if (down) {
            if (!isLive()) return;
            keyInput.resetComposition(); // e.g. arrows / Home / Ctrl+Z moved the caret on the PC
        }
        c.key(vk, down);
    }

    private void showKeyboard() {
        keyInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) ic.show(WindowInsets.Type.ime());
        }
        if (imm != null) imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        // closed on purpose (button, Back, three fingers down, Gaming mode): it doesn't come back by itself
        restoreKeyboard = false;
        keyboardHideRequestedAt = SystemClock.uptimeMillis();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) ic.hide(WindowInsets.Type.ime());
        }
        if (imm != null) imm.hideSoftInputFromWindow(keyInput.getWindowToken(), 0);
    }

    private void toggleKeyboard() {
        if (imeVisible) hideKeyboard();
        else showKeyboard();
    }

    /** A group of the shortcut bar was opened or closed: remember it, and make room for the bar's new height. */
    @Override
    public void barGroupChanged(int group) {
        prefs.setBarGroup(group);
        layoutChrome();
    }

    /** Pins / unpins the shortcut bar. Pinned = on screen until turned off, keyboard open or not. */
    private void toggleBar() {
        barPinned = !barPinned;
        prefs.setAlwaysShowBar(barPinned);
        refreshDock();
        layoutChrome();
        showSnack(getString(barPinned ? R.string.bar_pinned : R.string.bar_unpinned), -1, null, null, 2000);
    }

    // =================================================================== settings (one page over the session)

    private boolean settingsShown() { return settings != null && settings.view.getVisibility() == View.VISIBLE; }

    /** Settings, over the picture: the session carries on underneath, and changes apply right away. */
    private void showSettings() {
        if (imeVisible) hideKeyboard();
        touch.reset();
        if (settings == null) {
            settings = new SettingsScreen(this, this, padEditor);
            root.addView(settings.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            settings.refresh();
        }
        labelsChanged = false;
        View v = settings.view;
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.requestApplyInsets(); // added after the window's insets went round: clear of the system bars and cutout
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 24));
        v.animate().alpha(1f).translationY(0).setDuration(180).start();
    }

    @Override
    public void closeSettings() {
        if (!settingsShown()) return;
        final View v = settings.view;
        v.animate().cancel();
        v.animate().alpha(0f).translationY(Ui.dp(this, 24)).setDuration(150).withEndAction(() -> v.setVisibility(View.GONE)).start();
        // a new setting for the names shows on the gamepad right away (and fades if that's the setting)
        if (labelsChanged && gamepad.enabled()) pad.showLabels();
        labelsChanged = false;
    }

    /** A setting changed in Settings (already saved): apply it to this session now. */
    @Override
    public void settingChanged(int key) {
        HostConnection c = session.connection();
        switch (key) {
            case SettingsScreen.K_POINTER_SPEED:
                touch.pointerSpeed = Prefs.pointerSpeedFactor(prefs.pointerSpeed());
                break;
            case SettingsScreen.K_INVERT_SCROLL:
                touch.invertScroll = prefs.invertScroll();
                break;
            case SettingsScreen.K_PREDICTIVE:
                keyInput.setPredictive(prefs.predictiveText());
                break;
            case SettingsScreen.K_PAD_LABELS:
                pad.setLabelMode(prefs.padLabels());
                labelsChanged = true;
                break;
            case SettingsScreen.K_PAD_COLORS:
                pad.setColored(prefs.padColors());
                break;
            case SettingsScreen.K_GLIDE:
                touch.glide = Prefs.glideDistance(prefs.glide());
                break;
            case SettingsScreen.K_PAD_LINES:
                pad.setLineLevel(prefs.padLines());
                break;
            case SettingsScreen.K_PAD_OPACITY:
                pad.setOpacity(prefs.padOpacity() / 10f);
                break;
            case SettingsScreen.K_STICK_LOOK:
                pad.setStickLook(prefs.stickLook());
                break;
            case SettingsScreen.K_ARROW_COLOR:
                pad.setArrowColor(prefs.arrowColor());
                break;
            case SettingsScreen.K_MODS_HOLD:
                shortcutBar.setHoldOnly(prefs.modsHoldOnly());
                break;
            case SettingsScreen.K_PAD_TYPE:
                pad.setPadType(prefs.padType());
                break;
            case SettingsScreen.K_CAMERA_SPEED:
                touch.cameraSpeed = Prefs.cameraSpeedFactor(prefs.cameraSpeed());
                break;
            case SettingsScreen.K_QUALITY:
                setQuality(prefs.quality());
                break;
            case SettingsScreen.K_FULL_SCREEN:
                fullScreen = prefs.fullScreen();
                applyFullScreen();
                break;
            case SettingsScreen.K_STATS:
                statsText.setVisibility(prefs.showStats() ? View.VISIBLE : View.GONE);
                break;
            case SettingsScreen.K_CLIPBOARD: {
                boolean v = prefs.clipboardSync();
                // turning it on makes the PC send its clipboard; a newer copy on the phone wins over it
                if (v && clipSync != null) clipSync.restart();
                if (c != null) c.setOptions(v);
                if (v) main.postDelayed(this::checkClipboard, 250);
                break;
            }
            default:
                break; // the fingerprint lock needs nothing here
        }
    }

    // =================================================================== clipboard (the rules are in ClipSync)

    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"; // ClipDescription (API 33)
    private static final int PASTE_DELAY_MS = 150; // lets the phone's copy land on the PC clipboard before Ctrl+V

    private ClipSync newClipSync() {
        ClipSync.Phone phone = new ClipSync.Phone() {
            @Override
            public boolean canRead() { return hasWindowFocus(); }

            @Override
            public ClipSync.Info info() {
                ClipDescription d = clipboard.getPrimaryClipDescription();
                if (d == null) return null;
                PersistableBundle x = d.getExtras();
                boolean sendable = d.hasMimeType("text/*") && (x == null || !x.getBoolean(EXTRA_IS_SENSITIVE));
                return new ClipSync.Info(d.getLabel(), sendable, d.getTimestamp());
            }

            @Override
            public String text() {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(SessionActivity.this);
                return t == null ? "" : t.toString();
            }

            @Override
            public void set(String text) { clipboard.setPrimaryClip(ClipData.newPlainText(ClipSync.LABEL, text)); }
        };
        ClipSync.Seen seen = new ClipSync.Seen() {
            @Override
            public long stamp() { return prefs.clipSeenStamp(); }

            @Override
            public int hash() { return prefs.clipSeenHash(); }

            @Override
            public void set(long stamp, int hash) { prefs.setClipSeen(stamp, hash); }
        };
        return new ClipSync(phone, seen);
    }

    private boolean checkClipboard() {
        return checkClipboard(session == null ? null : session.connection());
    }

    /** Sends a copy made on the phone that hasn't gone to a PC yet (needs window focus). Returns true if it sent one. */
    private boolean checkClipboard(HostConnection c) {
        if (c == null || isFinishing() || !prefs.clipboardSync() || clipSync == null) return false;
        return clipSync.check(c::sendClipboard, SystemClock.uptimeMillis());
    }

    /** The PC's clipboard arrived (main thread). */
    private void onHostClipboard(String text) {
        if (prefs.clipboardSync() && clipSync != null) clipSync.fromPc(text, SystemClock.uptimeMillis());
    }

    /** Paste on the PC (shortcut bar, or the keyboard's own Paste): a new copy on the phone goes to the PC first. */
    @Override
    public int beforePaste() {
        return isLive() && checkClipboard() ? PASTE_DELAY_MS : 0;
    }

    // =================================================================== files

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(g, REQ_PICK);
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uploads.add(u);
            }
        } else if (data.getData() != null) {
            uploads.add(data.getData());
        }
        startNextUpload();
    }

    private void startNextUpload() {
        if (uploading || uploads.isEmpty()) return;
        final HostConnection c = session == null ? null : session.connection();
        if (c == null) return;
        uploading = true;
        final Uri u = uploads.poll();
        new Thread(() -> {
            final FileSaver.Source src;
            try {
                src = FileSaver.open(this, u);
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    uploading = false;
                    showSnack(getString(R.string.files_send_failed, String.valueOf(u.getLastPathSegment()), String.valueOf(e.getMessage())), -1, null, null, 5000);
                    startNextUpload();
                });
                return;
            }
            main.post(() -> showSnack(getString(R.string.files_sending, src.name, 0), 0, null, null, 0));
            c.sendFile(src.name, src.size, src.mime, src.in, new HostConnection.SendListener() {
                @Override
                public void onProgress(long acked, long size) {
                    final int pct = size > 0 ? (int) Math.min(100, acked * 100 / size) : 0;
                    main.post(() -> showSnack(getString(R.string.files_sending, src.name, pct), pct, null, null, 0));
                }

                @Override
                public void onDone(int status, String info) {
                    main.post(() -> {
                        uploading = false;
                        if (status == Protocol.FD_OK) {
                            showSnack(getString(R.string.files_sent, info == null || info.isEmpty() ? src.name : info), -1, null, null, 5000);
                        } else {
                            showSnack(getString(R.string.files_send_failed, src.name, String.valueOf(info)), -1, null, null, 6000);
                        }
                        startNextUpload();
                    });
                }
            });
        }, "dl-upload").start();
    }

    private void requestFileFromPc() {
        HostConnection c = session.connection();
        if (c == null || state != RemoteSession.State.LIVE) {
            showSnack(getString(R.string.files_not_connected), -1, null, null, 2500);
            return;
        }
        c.requestFile();
        showSnack(getString(R.string.files_pick_on_pc), -1, null, null, 6000);
    }

    private void openFile(FileSaver.Target t) {
        if (t.uri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(t.uri, t.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, t.name, Toast.LENGTH_SHORT).show();
        }
    }

    /** progress < 0 = none; durationMs 0 = stay until replaced. */
    private void showSnack(CharSequence text, int progress, CharSequence action, Runnable onAction, long durationMs) {
        snackText.setText(text);
        snackProgress.setVisibility(progress >= 0 ? View.VISIBLE : View.GONE);
        if (progress >= 0) snackProgress.setProgress(progress);
        if (action != null) {
            snackAction.setVisibility(View.VISIBLE);
            snackAction.setText(action);
            snackAction.setOnClickListener(v -> {
                snack.setVisibility(View.GONE);
                if (onAction != null) onAction.run();
            });
        } else {
            snackAction.setVisibility(View.GONE);
        }
        snack.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideSnack);
        if (durationMs > 0) main.postDelayed(hideSnack, durationMs);
    }

    // =================================================================== menu

    /** ⋮: files, things to do on the PC, Settings, Disconnect (DeskLink's own menu, not Android's pop-up). */
    private void showMenu(View anchor) {
        final Sheet s = new Sheet(this, Sheet.BOTTOM);
        s.title(hostName, null);
        if (hostInfo == null || hostInfo.can(Protocol.CAP_FILES)) {
            s.row(R.drawable.ic_upload, getString(R.string.tb_send), null, false, Sheet.NORMAL, v -> {
                s.dismiss();
                pickFiles();
            });
            s.row(R.drawable.ic_download, getString(R.string.tb_get), null, false, Sheet.NORMAL, v -> {
                s.dismiss();
                requestFileFromPc();
            });
        }
        if (hostInfo != null && hostInfo.monitors.size() > 1) {
            s.row(R.drawable.ic_screen_one, getString(R.string.menu_monitor), screenName(monitorIndex == Protocol.MONITOR_PRIMARY
                    ? primaryScreen() : monitorIndex), false, Sheet.NORMAL, v -> {
                s.dismiss();
                showMonitors();
            });
        }
        if (hostInfo == null || hostInfo.can(Protocol.CAP_CTRL_ALT_DEL)) {
            s.row(R.drawable.ic_keyboard, getString(R.string.menu_cad), null, false, Sheet.NORMAL, v -> {
                s.dismiss();
                act(Protocol.ACT_CTRL_ALT_DEL);
            });
        }
        s.row(R.drawable.ic_lock, getString(R.string.menu_lock), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            act(Protocol.ACT_LOCK_PC);
        });
        s.row(R.drawable.ic_task, getString(R.string.menu_task_manager), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            act(Protocol.ACT_TASK_MANAGER);
        });
        s.divider();
        s.row(R.drawable.ic_settings, getString(R.string.settings), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            showSettings();
        });
        s.row(R.drawable.ic_power, getString(R.string.disconnect), null, false, Sheet.DANGER, v -> {
            s.dismiss();
            finishSession();
        });
        s.show();
    }

    private void act(int action) {
        HostConnection c = session.connection();
        if (c != null) c.action(action);
    }

    /** ⋮ → Screen: all of them at once, then each from the left, the one shown checked. */
    private void showMonitors() {
        if (hostInfo == null) return;
        final Sheet s = new Sheet(this, Sheet.BOTTOM);
        s.title(getString(R.string.menu_monitor), null);
        int cur = monitorIndex == Protocol.MONITOR_PRIMARY ? primaryScreen() : monitorIndex;
        if (hostInfo.can(Protocol.CAP_ALL_SCREENS)) {
            s.row(R.drawable.ic_screens_all, getString(R.string.tb_screens_all), null, cur == Protocol.MONITOR_ALL,
                    Sheet.NORMAL, v -> {
                        s.dismiss();
                        showScreen(Protocol.MONITOR_ALL);
                    });
        }
        List<Messages.Monitor> order = screensInOrder();
        for (int i = 0; i < order.size(); i++) {
            final Messages.Monitor mo = order.get(i);
            s.row(R.drawable.ic_screen_one, getString(R.string.monitor_n, i + 1) + (mo.primary ? " ★" : ""),
                    mo.width + "×" + mo.height, mo.index == cur, Sheet.NORMAL, v -> {
                        s.dismiss();
                        showScreen(mo.index);
                    });
        }
        s.show();
    }

    /** One line on reaching this PC away from home, from the flags it reported (Protocol.REMOTE_*). */
    private String remoteSummary(int f) {
        if ((f & Protocol.REMOTE_OFF) != 0) return getString(R.string.remote_off);
        if ((f & (Protocol.REMOTE_IPV4 | Protocol.REMOTE_IPV6)) != 0) return getString(R.string.remote_ready);
        if ((f & Protocol.REMOTE_IPV6_MAYBE) != 0) return getString(R.string.remote_maybe_v6);
        if ((f & Protocol.REMOTE_SHARED_IPV4) != 0) return getString(R.string.remote_shared);
        return getString(R.string.remote_no_upnp);
    }

    /** "Can't reach": the usual checks, and on mobile data the likely reason, from what the PC last reported. */
    private String unreachableHint() {
        String why = mobileDataReason();
        return why == null ? getString(R.string.conn_unreachable_hint) : getString(R.string.conn_unreachable_mobile, why);
    }

    /** Why the PC may be out of reach over mobile data (null when this phone isn't on mobile data). */
    private String mobileDataReason() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.Network n = cm != null ? cm.getActiveNetwork() : null;
        android.net.NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
        if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return null;
        PairedHost h = store.get(hostId);
        int f = h != null ? h.remote : -1;
        // never told (an older PC, or not connected at home since): connecting at home teaches the phone the way in
        if (f < 0 || (f & Protocol.REMOTE_CHECKING) != 0) return getString(R.string.away_unknown);
        if ((f & Protocol.REMOTE_OFF) != 0) return getString(R.string.away_off);
        if ((f & Protocol.REMOTE_IPV4) != 0) return getString(R.string.away_ready);
        boolean v6 = (f & Protocol.REMOTE_IPV6) != 0, v6maybe = (f & Protocol.REMOTE_IPV6_MAYBE) != 0;
        if ((v6 || v6maybe) && !hasGlobalIpv6(cm.getLinkProperties(n))) {
            // only IPv6 could work, and this connection has none
            String s = getString(R.string.away_no_ipv6);
            return (f & Protocol.REMOTE_NO_UPNP) != 0 ? s + " " + getString(R.string.away_try_upnp) : s;
        }
        if (v6) return getString(R.string.away_ready);
        if ((f & Protocol.REMOTE_SHARED_IPV4) != 0) {
            String s = getString(R.string.away_shared);
            return v6maybe ? s + " " + getString(R.string.away_v6_hint) : s;
        }
        return getString(R.string.away_no_upnp); // UPnP also opens IPv6 on routers that can
    }

    /** Whether this connection has an internet IPv6 address (unknown counts as yes, so it isn't blamed wrongly). */
    private static boolean hasGlobalIpv6(android.net.LinkProperties lp) {
        if (lp == null) return true;
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            java.net.InetAddress a = la.getAddress();
            if (!(a instanceof java.net.Inet6Address) || a.isLinkLocalAddress() || a.isLoopbackAddress()) continue;
            if ((a.getAddress()[0] & 0xfe) == 0xfc) continue; // unique-local: not reachable from the internet
            return true;
        }
        return false;
    }

    // =================================================================== touch modes: trackpad, direct touch, gaming

    private static final int MODE_TRACKPAD = 0, MODE_DIRECT = 1, MODE_GAMING = 2;

    private int mode() { return gamepad.enabled() ? MODE_GAMING : touch.direct ? MODE_DIRECT : MODE_TRACKPAD; }

    /** The mode button: trackpad → direct touch → Gaming mode (with the profile in use) → trackpad. */
    private void cycleMode() {
        int m = mode();
        setMode(m == MODE_TRACKPAD ? MODE_DIRECT : m == MODE_DIRECT ? MODE_GAMING : MODE_TRACKPAD, null);
    }

    /** Touch and hold on the mode button: every mode, Gaming mode with each profile, and the profiles. */
    private void showModeMenu(View anchor) {
        final Sheet s = new Sheet(this, Sheet.BOTTOM);
        s.title(getString(R.string.tb_mode), null);
        final int m = mode();
        s.row(R.drawable.ic_mouse, getString(R.string.touch_trackpad), null, m == MODE_TRACKPAD, Sheet.NORMAL, v -> {
            s.dismiss();
            if (mode() != MODE_TRACKPAD) setMode(MODE_TRACKPAD, null);
        });
        s.row(R.drawable.ic_touch, getString(R.string.touch_direct), null, m == MODE_DIRECT, Sheet.NORMAL, v -> {
            s.dismiss();
            if (mode() != MODE_DIRECT) setMode(MODE_DIRECT, null);
        });
        s.divider();
        for (final Gamepad.Profile p : gamepad.profiles()) {
            s.row(R.drawable.ic_gamepad, getString(R.string.mode_gaming_profile, p.name), null,
                    m == MODE_GAMING && p == gamepad.active(), Sheet.NORMAL, v -> {
                        s.dismiss();
                        setMode(MODE_GAMING, p);
                    });
        }
        s.row(R.drawable.ic_edit, getString(R.string.gp_edit_profiles), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            padEditor.showProfiles();
        });
        s.show();
    }

    /** Switches the mode; for Gaming mode with profile {@code p} (null = the one in use). */
    private void setMode(int m, Gamepad.Profile p) {
        boolean gaming = m == MODE_GAMING, was = gamepad.enabled();
        if (gaming) {
            touch.direct = false; // between the gamepad's controls the screen is a trackpad
        } else {
            touch.direct = m == MODE_DIRECT;
            prefs.setDirectTouch(touch.direct);
        }
        if (p != null) gamepad.setActive(p);
        gamepad.setEnabled(gaming);
        pad.setProfile(gamepad.active());
        pad.setVisible(gaming); // leaving Gaming mode lets go of anything held
        if (gaming) pad.showLabels(); // what each control does, for a moment (or always / never: Settings)
        updateCameraMode();
        if (gaming && !was && imeVisible) hideKeyboard(); // the gamepad needs the room; three fingers up brings it back
        refreshModeButton();
        layoutChrome();
        String note = gaming ? getString(R.string.gp_on, gamepad.active().name)
                : getString(m == MODE_DIRECT ? R.string.mode_switched_direct : R.string.mode_switched_trackpad);
        showSnack(note, -1, null, null, gaming ? 3000 : 1800);
    }

    /** A gamepad control went down or up. Ups always go out (nothing stays pressed on the PC). */
    @Override
    public void padAction(int action, boolean down) {
        if (Gamepad.isMouse(action)) {
            if (down && !isLive()) return;
            button(action - Gamepad.MOUSE, down); // at the pointer, like a trackpad click
        } else {
            key(action, down);
        }
    }

    /** A profile was edited, renamed, added or deleted: the gamepad shows it (with the names, for a moment). */
    @Override
    public void profilesChanged() {
        pad.setProfile(gamepad.active());
        if (gamepad.enabled()) pad.showLabels();
        refreshModeButton(); // its description names the profile
    }

    private void setQuality(int q) {
        prefs.setQuality(q);
        int[] p = Prefs.streamParams(this, q);
        HostConnection c = session.connection();
        if (c != null) c.streamConfig(p[0], p[1], p[2], p[3]);
    }

    @SuppressWarnings("deprecation")
    private void applyFullScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic == null) return;
            if (fullScreen) {
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ic.hide(WindowInsets.Type.systemBars());
            } else {
                ic.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (fullScreen) flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    // =================================================================== stats

    private void updateStats() {
        int rtt = lastRtt;
        HostConnection c = session == null ? null : session.connection();
        if (c != null && state == RemoteSession.State.WAITING_VIDEO && System.currentTimeMillis() - waitingSince > 3000) {
            waitingSince = System.currentTimeMillis();
            c.requestKeyframe(); // safety net if the first keyframe got lost in a decoder reset
        }
        pingText.setText(c != null && rtt >= 0 ? String.format(Locale.US, "%d ms", rtt) : "");
        int fps = framesRendered.getAndSet(0);
        long bytes = c != null ? c.bytesReceived() : 0;
        long delta = statsBytes >= 0 && bytes >= statsBytes ? bytes - statsBytes : 0;
        statsBytes = bytes;
        if (statsText.getVisibility() == View.VISIBLE) {
            String mbps = String.format(Locale.US, "%.1f", delta * 8 / 1_000_000.0);
            int w = format != null ? format.width : 0, h = format != null ? format.height : 0;
            String dec = decoder != null ? decoder.codecName() : "";
            statsText.setText(getString(R.string.stats_fmt, fps, mbps, Math.max(0, rtt), w, h) + (dec.isEmpty() ? "" : "\n" + dec));
        }
    }
}
