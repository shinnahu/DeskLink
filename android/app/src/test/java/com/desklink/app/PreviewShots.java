package com.desklink.app;

import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

/**
 * The README's phone pictures, drawn by the app's own views: Robolectric runs Android's drawing code on the PC.
 * Only when asked:  gradlew :app:testDebugUnitTest --tests com.desklink.app.PreviewShots -Ppreviews=<folder>
 * Nothing connects anywhere (RemoteSession.offline). The PC is a stand-in ("OFFICE-PC"), and so is its picture: the
 * test reads desk-one.png, desk-all.png and game.png from <folder>/in and draws them where the video goes (a
 * TextureView only shows video with hardware drawing).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = PreviewShots.PORTRAIT)
public class PreviewShots {
    static final String PORTRAIT = "w412dp-h915dp-420dpi";
    static final String LANDSCAPE = "w915dp-h412dp-land-420dpi";
    static final String HOST_ID = "5d1e0c3a9b7f4e2a8c6d1f0e3b5a7c91";

    private File out, in;
    private Context ctx;

    @Before
    public void setUp() {
        String dir = System.getProperty("desklink.previews", "");
        assumeTrue("only when asked: -Ppreviews=<folder>", !dir.isEmpty());
        out = new File(dir);
        in = new File(out, "in");
        RemoteSession.offline = true;
        ctx = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        PairedHost h = new PairedHost();
        h.hostId = HOST_ID;
        h.hostName = "OFFICE-PC";
        h.fingerprint = "7f3a9c2e5b8d1f4a6c0e2b9d7a5f3c1e8b6d4a2f0c9e7b5d3a1f8c6e4b2d0a9f";
        h.addresses.add("192.168.1.20");
        h.pairedAt = now - 9L * 86_400_000L;
        h.lastConnected = now - 2L * 3_600_000L;
        h.lastSeenOnline = now;
        new HostStore(ctx).put(h);
        new Prefs(ctx).setPadColors(true);
    }

    // ------------------------------------------------------------------ the pictures

    @Test
    public void pcList() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        MainActivity.Status s = new MainActivity.Status(); // the PC answers: ready
        s.kind = MainActivity.ST_ONLINE;
        Map<String, MainActivity.Status> statuses = get(a, "statuses");
        statuses.put(HOST_ID, s);
        call(a, "render", new HostStore(ctx).get(HOST_ID));
        settle();
        shoot(a, "phone-pcs.png", null);
    }

    @Test
    public void session() throws Exception {
        new Prefs(ctx).setShowBar(true); // the shortcut bar is shown
        SessionActivity a = live(false, true);
        shoot(a, "phone-session.png", picture("desk-one.png"));
    }

    @Test
    public void menu() throws Exception {
        SessionActivity a = live(false, true);
        call(a, "showMenu", (Object) null);
        settle();
        shoot(a, "phone-menu.png", picture("desk-one.png"));
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    public void allScreens() throws Exception {
        SessionActivity a = live(true, true);
        shoot(a, "phone-all-screens.png", picture("desk-all.png"));
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    public void gaming() throws Exception {
        new Gamepad(ctx).setEnabled(true);
        SessionActivity a = live(false, false); // a game hides the PC's pointer
        shoot(a, "phone-gaming.png", picture("game.png"));
    }

    @Test
    public void bindings() throws Exception {
        new Gamepad(ctx).setEnabled(true);
        SessionActivity a = live(false, false);
        GamepadEditor editor = get(a, "padEditor");
        Gamepad gamepad = get(a, "gamepad");
        editor.editProfile(gamepad.active());
        settle();
        shoot(a, "phone-bindings.png", picture("game.png"));
    }

    @Test
    public void settings() throws Exception {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        settle();
        shoot(a, "phone-settings.png", null);
        // scrolled to Gaming mode
        ScrollView sv = find(a.getWindow().getDecorView(), ScrollView.class);
        TextView header = findText(a.getWindow().getDecorView(), a.getString(R.string.set_gaming));
        if (sv != null && header != null) {
            int[] s = new int[2], h = new int[2];
            sv.getChildAt(0).getLocationInWindow(s);
            header.getLocationInWindow(h);
            sv.scrollTo(0, Math.max(0, h[1] - s[1] - Math.round(12 * ctx.getResources().getDisplayMetrics().density)));
            settle();
            shoot(a, "phone-settings-gaming.png", null);
        }
    }

    // ------------------------------------------------------------------ a session as if connected

    /** The session screen as it is with the PC connected: two screens (one shown, or all), the pointer, a ping. */
    private SessionActivity live(boolean all, boolean pointer) throws Exception {
        Intent i = new Intent(ctx, SessionActivity.class).putExtra(SessionActivity.EXTRA_HOST_ID, HOST_ID);
        SessionActivity a = Robolectric.buildActivity(SessionActivity.class, i).setup().get();
        Messages.HostInfo info = new Messages.HostInfo();
        info.hostName = "OFFICE-PC";
        info.capabilities = Protocol.CAP_FILES | Protocol.CAP_CLIPBOARD | Protocol.CAP_KNOCK | Protocol.CAP_REL_MOUSE
                | Protocol.CAP_ALL_SCREENS | Protocol.CAP_CTRL_ALT_DEL | Protocol.CAP_SERVICE_MODE; // installed on the PC
        info.monitors.add(monitor(0, 0, 0, true));
        info.monitors.add(monitor(1, 1920, 120, false));
        set(a, "hostInfo", info);
        Messages.VideoFormat f = new Messages.VideoFormat();
        f.codec = 1;
        f.monitor = all ? Protocol.MONITOR_ALL : 0;
        f.width = f.desktopWidth = all ? 3840 : 1920;
        f.height = f.desktopHeight = all ? 1200 : 1080;
        a.onVideoFormat(f);
        a.onState(RemoteSession.State.LIVE, 0, null);
        Messages.CursorShape s = arrow();
        a.onCursorShape(s);
        a.onCursorState(all ? 2470 : 1190, all ? 330 : 420, pointer, s.id);
        settle();
        call(a, "refreshScreenButtons");
        ((TextView) get(a, "pingText")).setText("12 ms");
        return a;
    }

    private static Messages.Monitor monitor(int index, int left, int top, boolean primary) {
        Messages.Monitor m = new Messages.Monitor();
        m.index = index;
        m.left = left;
        m.top = top;
        m.width = 1920;
        m.height = 1080;
        m.primary = primary;
        m.name = "Display " + (index + 1);
        return m;
    }

    /** A Windows-style arrow, as the PC sends its pointer: RGBA, straight alpha. */
    private static Messages.CursorShape arrow() {
        int w = 22, h = 32;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path p = new Path();
        p.moveTo(1.5f, 1.5f);
        p.lineTo(1.5f, 25f);
        p.lineTo(7f, 19.8f);
        p.lineTo(11f, 29f);
        p.lineTo(15f, 27.3f);
        p.lineTo(11.2f, 18.3f);
        p.lineTo(18.5f, 18.3f);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.BLACK);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.4f);
        line.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(p, fill);
        c.drawPath(p, line);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            rgba[i * 4] = (byte) (v >> 16);
            rgba[i * 4 + 1] = (byte) (v >> 8);
            rgba[i * 4 + 2] = (byte) v;
            rgba[i * 4 + 3] = (byte) (v >>> 24);
        }
        Messages.CursorShape s = new Messages.CursorShape();
        s.id = 7;
        s.width = w;
        s.height = h;
        s.hotX = 1;
        s.hotY = 1;
        s.rgba = rgba;
        return s;
    }

    // ------------------------------------------------------------------ drawing and saving

    private Bitmap picture(String name) {
        Bitmap b = BitmapFactory.decodeFile(new File(in, name).getPath());
        if (b == null) throw new IllegalStateException("missing " + name + " in " + in);
        return b;
    }

    /** The whole window as on the phone; `pic` goes where the video is (the session's viewport). */
    private void shoot(Activity a, String name, Bitmap pic) throws Exception {
        View decor = a.getWindow().getDecorView();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        // the window on its own (see-through where the video is), then over the PC's picture
        if (pic != null) {
            ((View) get(a, "root")).setBackground(null);
            decor.setBackground(null);
        }
        Bitmap ui = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas uc = new Canvas(ui);
        Drawable bg = decor.getBackground();
        if (bg != null) {
            bg.setBounds(0, 0, w, h);
            bg.draw(uc);
        }
        decor.draw(uc);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        if (pic != null) {
            Viewport v = get(a, "viewport");
            View video = get(a, "videoView");
            int[] at = new int[2];
            video.getLocationInWindow(at);
            RectF r = new RectF(at[0] + v.ox, at[1] + v.oy, at[0] + v.ox + v.deskW * v.scale, at[1] + v.oy + v.deskH * v.scale);
            c.drawBitmap(pic, null, r, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            System.out.println("preview: " + name + " picture at " + r.toShortString());
        }
        c.drawBitmap(ui, 0, 0, null);
        save(bmp, name);
    }

    private void save(Bitmap b, String name) throws Exception {
        out.mkdirs();
        boolean ok;
        try (FileOutputStream os = new FileOutputStream(new File(out, name))) {
            ok = b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        if (!ok) { // raw pixels instead (width, height, ARGB ints), for a script to turn into a PNG
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(out, name + ".argb"))))) {
                os.writeInt(w);
                os.writeInt(h);
                for (int v : px) os.writeInt(v);
            }
        }
        System.out.println("preview: " + name + " " + b.getWidth() + "x" + b.getHeight() + (ok ? "" : " (raw)"));
    }

    // ------------------------------------------------------------------ helpers

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); // posted work, and sheets sliding in
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object o, String name) throws Exception {
        return (T) field(o.getClass(), name).get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        field(o.getClass(), name).set(o, v);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(o, args);
        }
        throw new NoSuchMethodException(name);
    }

    private static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T t = find(g.getChildAt(i), type);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static TextView findText(View v, String text) {
        if (v instanceof TextView && text.equalsIgnoreCase(String.valueOf(((TextView) v).getText()))) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findText(g.getChildAt(i), text);
                if (t != null) return t;
            }
        }
        return null;
    }
}
