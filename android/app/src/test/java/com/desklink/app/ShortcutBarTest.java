package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.desklink.core.Vk;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The shortcut bar's keys, as the PC receives them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShortcutBarTest {
    private final List<String> sent = new ArrayList<>();

    private ShortcutBar bar(int openGroup) {
        return new ShortcutBar(RuntimeEnvironment.getApplication(), (vk, down) -> sent.add(vk + (down ? " down" : " up")),
                openGroup);
    }

    private static View find(View v, String description) {
        CharSequence d = v.getContentDescription();
        if (d != null && description.contentEquals(d) && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), description);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void touch(View v, int action) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, 5, 5, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    @Test
    public void escTabGroupHasEscTabSpaceDel() {
        ShortcutBar b = bar(ShortcutBar.GROUP_KEYS);
        for (String key : Arrays.asList("Esc", "Tab", "Space", "Del")) assertNotNull(key, find(b, key));
        assertNotNull(find(b, "Esc, Tab, Space, Del")); // the group button's description
    }

    @Test
    public void tapOnSpaceIsOneSpaceKeyPress() {
        View space = find(bar(ShortcutBar.GROUP_KEYS), "Space");
        touch(space, MotionEvent.ACTION_DOWN);
        touch(space, MotionEvent.ACTION_UP);
        assertEquals(Arrays.asList(Vk.SPACE + " down", Vk.SPACE + " up"), sent);
    }

    @Test
    public void holdingSpaceRepeatsItThenLetsGo() {
        View space = find(bar(ShortcutBar.GROUP_KEYS), "Space");
        touch(space, MotionEvent.ACTION_DOWN);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        touch(space, MotionEvent.ACTION_UP);
        assertTrue("repeated key-downs while held: " + sent, sent.size() >= 3);
        for (String s : sent.subList(0, sent.size() - 1)) assertEquals(Vk.SPACE + " down", s);
        assertEquals(Vk.SPACE + " up", sent.get(sent.size() - 1));
    }
}
