package com.desklink.app;

import android.app.Activity;
import android.os.Bundle;

/** Settings from the main screen (the ⚙ next to the title). During a session the same page opens over the picture. */
public final class SettingsActivity extends Activity implements SettingsScreen.Host {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLock.install(this); // the optional fingerprint lock
        Ui.edgeToEdge(this);
        GamepadEditor editor = new GamepadEditor(this, new Gamepad(this), () -> {
            // nothing on screen shows the profiles here
        });
        setContentView(new SettingsScreen(this, this, editor).view);
    }

    @Override
    public void settingChanged(int key) {
        // everything is saved; the next session reads it
    }

    @Override
    public void closeSettings() {
        finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!Sheet.dismissTop(this)) super.onBackPressed(); // (Android 13 and later: an open sheet takes Back itself)
    }
}
