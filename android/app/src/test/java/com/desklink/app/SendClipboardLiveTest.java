package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.desklink.core.Connector;
import com.desklink.core.HostConnection;
import com.desklink.core.PairedHost;
import com.desklink.core.Pairing;
import com.desklink.core.SecureChannel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/**
 * ⋮ → Send clipboard, from the session screen's own code to a real DeskLink on a PC and that PC's clipboard.
 * Only when asked:
 *   gradlew :app:testDebugUnitTest --tests com.desklink.app.SendClipboardLiveTest -Phost=127.0.0.1:47910 -Pstation=<StationRun.exe>
 * The PC is a test build of DeskLink Host (--auto-approve --no-inject, with its UI: that's where its clipboard is)
 * started by host/tools/StationRun in a window station of its own (host/README.md), so the clipboard it reads and
 * writes is that station's and not the one of whoever is using this PC. The test reads and changes that clipboard
 * through the same tool. The phone's clipboard is Robolectric's. There's no picture (the PC's log says its capture
 * fails in that station: "DuplicateOutput failed"); the test opens the ⋮ menu itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SendClipboardLiveTest {
    private static PairedHost paired; // linked once, for every test

    private Context ctx;
    private String station;
    private File work;
    private ClipboardManager phone;
    private SessionActivity a;

    @Before
    public void setUp() throws Exception {
        String host = System.getProperty("desklink.host", "");
        station = System.getProperty("desklink.station", "");
        assumeTrue("only when asked: -Phost=<address:port> -Pstation=<StationRun.exe>", !host.isEmpty() && !station.isEmpty());
        work = Files.createTempDirectory("desklink-live").toFile();
        RemoteSession.offline = false;
        ctx = RuntimeEnvironment.getApplication();
        if (paired == null) {
            Connector.Target t = Connector.parse(host, 47810);
            SecureChannel ch = Connector.open(Collections.singletonList(t.host), t.port, null, 5000, 250);
            paired = Pairing.finishWithHostInfo(ch, Pairing.pair(ch, t.port, "Test phone", "Robolectric", null), "Test phone");
            paired.addresses.clear(); // only the address asked for: the PC lists its others too
            paired.addresses.add(t.host);
            paired.lastAddress = t.host;
        }
        new HostStore(ctx).put(paired.copy());
        Prefs prefs = new Prefs(ctx);
        prefs.clipboardSync(); // the first call turns it on once, for everyone
        prefs.setSound(false);
        phone = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @After
    public void tearDown() throws Exception {
        if (a != null) {
            call(a, "finishSession"); // says bye: the next test's connection isn't told the PC is busy
            pump(400);
        }
    }

    // ------------------------------------------------------------------ the checks

    @Test
    public void withTheSyncOffOnlyTheButtonSends() throws Exception {
        new Prefs(ctx).setClipboardSync(false);
        setPc("on the PC before");
        connect();
        copyOnPhone("from the phone\nsecond line ñ");
        pump(1200);
        assertEquals("with the sync off a copy on the phone stays there", "on the PC before", pc());
        // why the button shares the clipboards for its one message: a clipboard sent as it is, the PC leaves
        connection().sendClipboard("sent without sharing");
        pump(1200);
        assertEquals("the PC takes no clipboard while the sync is off", "on the PC before", pc());

        tapSendClipboard();
        assertPc("from the phone\r\nsecond line ñ"); // the PC keeps Windows' line breaks
        assertEquals(ctx.getString(R.string.clip_sent), snack());

        // The sync is off again on the PC: a copy made there isn't sent. To see what the PC sends, the phone takes
        // the PC's clipboard from here on without telling the PC.
        new Prefs(ctx).setClipboardSync(true);
        setPc("copied on the PC afterwards");
        pump(1500);
        assertEquals("the PC sent nothing", "from the phone\nsecond line ñ", phoneText());
        // ...and it does send it once the phone asks for the sync, so the check above can tell
        a.settingChanged(SettingsScreen.K_CLIPBOARD);
        waitFor(() -> "copied on the PC afterwards".equals(phoneText()), 4000, "the PC's clipboard on the phone once the sync is on");
    }

    @Test
    public void withTheSyncOnTheButtonSendsAgainWhatWentBefore() throws Exception {
        setPc("on the PC before");
        connect();
        waitFor(() -> "on the PC before".equals(phoneText()), 4000, "the PC's clipboard on the phone at the start");

        copyOnPhone("a new copy on the phone");
        assertPc("a new copy on the phone"); // the sync, by itself
        clearPc(); // the PC's clipboard changes without the phone's
        pump(800);
        assertEquals("the sync doesn't send a copy twice", null, pc());
        tapSendClipboard();
        assertPc("a new copy on the phone");

        // text that came from the PC goes back to it too
        setPc("copied on the PC");
        waitFor(() -> "copied on the PC".equals(phoneText()), 4000, "the PC's copy on the phone");
        clearPc();
        pump(800);
        tapSendClipboard();
        assertPc("copied on the PC");
        assertEquals(ctx.getString(R.string.clip_sent), snack());
    }

    @Test
    public void aCopyMarkedPrivateGoesOnlyWithTheButton() throws Exception {
        setPc("on the PC before");
        connect();
        waitFor(() -> "on the PC before".equals(phoneText()), 4000, "the PC's clipboard on the phone at the start");
        ClipData secret = ClipData.newPlainText("password manager", "hunter2 ñ");
        PersistableBundle x = new PersistableBundle();
        x.putBoolean("android.content.extra.IS_SENSITIVE", true);
        secret.getDescription().setExtras(x);
        phone.setPrimaryClip(secret);
        pump(1200);
        assertEquals("the sync leaves it on the phone", "on the PC before", pc());
        tapSendClipboard();
        assertPc("hunter2 ñ");
    }

    @Test
    public void withNoTextNothingIsSent() throws Exception {
        setPc("on the PC before");
        connect();
        waitFor(() -> "on the PC before".equals(phoneText()), 4000, "the PC's clipboard on the phone at the start");
        phone.clearPrimaryClip();
        pump(300);
        tapSendClipboard();
        assertEquals(ctx.getString(R.string.clip_no_text), snack());
        pump(1000);
        assertEquals("on the PC before", pc());
    }

    // ------------------------------------------------------------------ the phone

    /** The session screen, connected to the PC. */
    private void connect() throws Exception {
        Intent i = new Intent(ctx, SessionActivity.class).putExtra(SessionActivity.EXTRA_HOST_ID, paired.hostId);
        // in front, with the window's focus: only then does Android let an app read the clipboard
        a = Robolectric.buildActivity(SessionActivity.class, i).setup().windowFocusChanged(true).get();
        assertTrue("the session screen has the window's focus", a.hasWindowFocus());
        waitFor(() -> connection() != null, 10_000, "a connection to the PC");
        pump(600); // STREAM_START is on its way, and with the sync on the PC's clipboard comes back
    }

    private HostConnection connection() {
        try {
            RemoteSession s = get(a, "session");
            return s == null ? null : s.connection();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyOnPhone(String text) {
        phone.setPrimaryClip(ClipData.newPlainText("notes", text));
    }

    private String phoneText() {
        ClipData c = phone.getPrimaryClip();
        return c == null || c.getItemCount() == 0 ? null : String.valueOf(c.getItemAt(0).getText());
    }

    /** ⋮, then the Send clipboard row. */
    private void tapSendClipboard() throws Exception {
        call(a, "showMenu", (Object) null);
        pump(300);
        TextView label = findText(a.getWindow().getDecorView(), ctx.getString(R.string.menu_send_clipboard));
        assertNotNull("the ⋮ menu has Send clipboard", label);
        View row = (View) label.getParent().getParent();
        assertTrue(row.performClick());
        pump(300);
    }

    private String snack() throws Exception {
        View s = get(a, "snack");
        TextView t = get(a, "snackText");
        return s.getVisibility() == View.VISIBLE ? String.valueOf(t.getText()) : null;
    }

    // ------------------------------------------------------------------ the PC's clipboard (in its own window station)

    /** The text on the PC's clipboard; null when it holds none. */
    private String pc() throws Exception {
        File f = new File(work, "clip.txt");
        int exit = inStation("getclip", f.getPath());
        assertTrue("the PC's clipboard was read (" + exit + ")", exit == 0 || exit == 1);
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int a1 = s.indexOf('\n'), a2 = s.indexOf('\n', a1 + 1);
        assertNotEquals("the PC's clipboard is not this desktop's", "WinSta0", s.substring(0, a1));
        return exit == 1 ? null : s.substring(a2 + 1);
    }

    private void assertPc(String want) throws Exception {
        long until = System.currentTimeMillis() + 4000;
        String got = pc();
        while (!want.equals(got) && System.currentTimeMillis() < until) {
            pump(100);
            got = pc();
        }
        assertEquals("the PC's clipboard", want, got);
    }

    /** A copy made on the PC. */
    private void setPc(String text) throws Exception {
        File f = new File(work, "set.txt");
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, inStation("setclip", f.getPath()));
    }

    private void clearPc() throws Exception {
        assertEquals(0, inStation("clearclip", null));
    }

    /** Runs the tool again inside the PC's window station, where its clipboard is. */
    private int inStation(String what, String file) throws Exception {
        ProcessBuilder b = file == null ? new ProcessBuilder(station, "wait", "-", station, what)
                : new ProcessBuilder(station, "wait", "-", station, what, file);
        Process p = b.redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    // ------------------------------------------------------------------ helpers

    private static long clockAt = System.nanoTime();

    /**
     * Lets posted work run for a while: the network threads post to the main thread, which only runs here. The app's
     * clock (Robolectric's) is moved on by the time that really passed, the PC's side included, since the app times
     * things by it (a PC clipboard that comes within 2 s of a new copy on the phone is the older one).
     */
    private static void pump(long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        do {
            Thread.sleep(10);
            long passed = (System.nanoTime() - clockAt) / 1_000_000;
            clockAt += passed * 1_000_000;
            if (passed > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(passed));
            shadowOf(Looper.getMainLooper()).idle();
        } while (System.currentTimeMillis() < until);
    }

    private static void waitFor(BooleanSupplier done, long ms, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) pump(50);
        assertTrue("waited " + ms + " ms for " + what, done.getAsBoolean());
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(o, args);
        }
        throw new NoSuchMethodException(name);
    }

    private static TextView findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
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
