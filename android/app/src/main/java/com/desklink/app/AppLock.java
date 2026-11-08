package com.desklink.app;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The optional fingerprint lock (off by default; main screen ⋮ → Lock with fingerprint). When it's on, DeskLink
 * covers its screen and asks for the fingerprint, or the phone's PIN / pattern, when it's opened and again after
 * 30 s in the background. Needs Android 10 (where the phone's PIN / pattern can stand in for the fingerprint, so
 * nobody gets locked out) and a screen lock on the phone.
 */
final class AppLock {
    private static final long RELOCK_MS = 30_000;
    private static final int TAG = 0x44_4C_4C_4B; // marks the cover view

    private static boolean installed;
    private static int started;           // DeskLink activities on screen
    private static long backgroundSince;  // uptime when the last one left; 0 = the app just started
    private static boolean unlocked;

    private AppLock() {}

    static boolean supported() { return Build.VERSION.SDK_INT >= 29; }

    /** The phone has a fingerprint, PIN, pattern or password to check against. */
    static boolean canLock(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return supported() && km != null && km.isDeviceSecure();
    }

    /** Call from every activity's onCreate (before it starts); only the first call does anything. */
    static void install(Activity a) {
        if (installed) return;
        installed = true;
        a.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity act) {
                if (started++ == 0) {
                    long away = backgroundSince == 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - backgroundSince;
                    if (away > RELOCK_MS) unlocked = false;
                }
                if (!unlocked && new Prefs(act).appLock() && canLock(act)) cover(act);
                else if (unlocked) uncoverAll(act); // unlocked on another screen meanwhile
            }

            @Override
            public void onActivityStopped(Activity act) {
                if (--started <= 0) {
                    started = 0;
                    backgroundSince = SystemClock.uptimeMillis();
                }
            }

            @Override public void onActivityCreated(Activity act, Bundle b) {}
            @Override public void onActivityResumed(Activity act) {}
            @Override public void onActivityPaused(Activity act) {}
            @Override public void onActivitySaveInstanceState(Activity act, Bundle b) {}
            @Override public void onActivityDestroyed(Activity act) {}
        });
    }

    /** Turning the lock on counts as unlocked for now (the person turning it on is the owner). */
    static void enabledNow() { unlocked = true; }

    private static void cover(final Activity a) {
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG) != null) return;
        FrameLayout cover = new FrameLayout(a);
        cover.setTag(TAG);
        cover.setBackgroundColor(Ui.color(a, R.color.bg));
        cover.setClickable(true); // nothing underneath can be touched
        cover.setFocusable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.mipmap.ic_launcher);
        int s = Ui.dp(a, 72);
        col.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView title = Ui.text(a, a.getString(R.string.lock_title), 18, Ui.color(a, R.color.text), true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(a, 16);
        tl.bottomMargin = Ui.dp(a, 20);
        col.addView(title, tl);
        col.addView(Ui.button(a, a.getString(R.string.lock_unlock), true, v -> prompt(a)));
        cover.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        decor.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.post(() -> prompt(a));
    }

    private static void uncoverAll(Activity a) {
        View v = a.getWindow().getDecorView().findViewWithTag(TAG);
        if (v != null) ((ViewGroup) v.getParent()).removeView(v);
    }

    @SuppressWarnings("deprecation")
    @android.annotation.TargetApi(29)
    private static void prompt(final Activity a) {
        if (!supported() || a.isFinishing()) return;
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(a.getString(R.string.lock_prompt_title));
        // the fingerprint, or the phone's PIN / pattern
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setDeviceCredentialAllowed(true);
        }
        b.build().authenticate(new CancellationSignal(), a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                unlocked = true;
                uncoverAll(a);
            }
            // cancelled or failed: the cover stays, with its Unlock button
        });
    }
}
