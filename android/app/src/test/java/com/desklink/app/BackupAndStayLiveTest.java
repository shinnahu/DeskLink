package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Looper;

import com.desklink.core.BackupRun;
import com.desklink.core.Connector;
import com.desklink.core.HostConnection;
import com.desklink.core.PairedHost;
import com.desklink.core.Pairing;
import com.desklink.core.Protocol;
import com.desklink.core.SecureChannel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Settings → Back up photos and videos and → Stay connected, with the app's own code (BackupService, the session
 * screen) against a test build of DeskLink 2.12 on this PC. Only when asked:
 *   gradlew :app:testDebugUnitTest --tests com.desklink.app.BackupAndStayLiveTest -Phost=127.0.0.1:47910 -Phostdata=<its --data folder>
 * The PC is DeskLinkHostTest.exe --data <folder> --bind 127.0.0.1 --port 47910 --no-ui --auto-approve --no-inject: its
 * backups go to <folder>\backup, its log is <folder>\host.log. The photos and videos are files made here (the media
 * store of the phone Robolectric stands in for is empty). Staying connected plays a quiet tone on this PC's speakers
 * for a few seconds, to see the PC's sound reach the phone while it's in the background.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BackupAndStayLiveTest {
    private static final String PHONE = "Backup test phone";
    private static PairedHost paired;

    private Context ctx;
    private File hostData, work;
    private ActivityController<SessionActivity> session;
    private final String run = Long.toHexString(System.nanoTime() & 0xFFFFFF); // files of this run's own

    @Before
    public void setUp() throws Exception {
        String host = System.getProperty("desklink.host", "");
        String data = System.getProperty("desklink.hostdata", "");
        assumeTrue("only when asked: -Phost=<address:port> -Phostdata=<the PC's --data folder>", !host.isEmpty() && !data.isEmpty());
        hostData = new File(data);
        work = Files.createTempDirectory("desklink-backup").toFile();
        RemoteSession.offline = false;
        ctx = RuntimeEnvironment.getApplication();
        if (paired == null) {
            Connector.Target t = Connector.parse(host, 47810);
            SecureChannel ch = Connector.open(Collections.singletonList(t.host), t.port, null, 5000, 250);
            paired = Pairing.finishWithHostInfo(ch, Pairing.pair(ch, t.port, PHONE, "Robolectric", null), PHONE);
            paired.addresses.clear(); // only the address asked for: the PC lists its others too
            paired.addresses.add(t.host);
            paired.lastAddress = t.host;
        }
        new HostStore(ctx).put(paired.copy());
        BackupService.networkOk = c -> true; // (Robolectric's phone has no Wi-Fi to speak of)
    }

    @After
    public void tearDown() throws Exception {
        BackupService.testItems = null;
        BackupService.testFiles = null;
        BackupService.networkOk = BackupService::onWifi;
        if (session != null) {
            call(session.get(), "finishSession"); // says bye: the next test's connection isn't told the PC is busy
            pump(400);
        }
        if (work != null) deleteAll(work);
    }

    // ------------------------------------------------------------------ the backup

    @Test
    public void theBackupGoesToThePcAndNextTimeOnlyWhatIsNew() throws Exception {
        List<BackupRun.Item> items = new ArrayList<>();
        items.add(file("DCIM/Camera", "IMG_" + run + "_1.jpg", 2 * 1024 * 1024 + 11, at(2024, 3, 1)));
        items.add(file("DCIM/Camera", "VID_" + run + "_2.mp4", 9 * 1024 * 1024 + 7, at(2024, 3, 2)));
        items.add(file("Pictures/Screenshots", "Screenshot ñ " + run + ".png", 300 * 1024, at(2023, 12, 24)));
        BackupService.testItems = items;
        BackupService.testFiles = slowed(20); // (over 127.0.0.1 it would be over before it's looked at)

        startBackup();
        waitFor(() -> BackupService.status() != null && BackupService.status().phase == BackupRun.Phase.SENDING
                || !BackupService.running(), 20_000, "the backup under way");
        Notification going = notification(21);
        assertNotNull("a notification while it runs", going);
        assertEquals(ctx.getString(R.string.backup_to, paired.name()), title(going));
        waitFor(() -> !BackupService.running(), 60_000, "the backup's end");

        Notification done = notification(22);
        assertEquals(ctx.getString(R.string.backup_done), title(done));
        assertEquals(ctx.getResources().getQuantityString(R.plurals.backup_done_files, 3, "3", paired.name()), text(done));
        for (BackupRun.Item i : items) {
            File src = new File(((Uri) i.ref).getPath());
            File pc = onPc(i);
            assertTrue("on the PC: " + pc, pc.isFile());
            assertTrue("the same bytes: " + pc, same(src, pc));
            assertEquals("the phone's date: " + pc, i.modifiedMs, pc.lastModified());
        }
        assertEquals(3, BackupService.status().sent);
        assertEquals(3, new Prefs(ctx).lastBackupFiles());

        // the next time: all of it is there, nothing goes
        startBackup();
        waitFor(() -> !BackupService.running(), 30_000, "the second backup's end");
        assertEquals(ctx.getString(R.string.backup_done), title(notification(22)));
        assertEquals(0, BackupService.status().sent);
        assertEquals(3, BackupService.status().done);
    }

    @Test
    public void aStoppedBackupGoesOnFromWhereItGotTo() throws Exception {
        BackupRun.Item small = file("DCIM/Camera", "IMG_" + run + "_small.jpg", 500 * 1024, at(2024, 5, 1));
        BackupRun.Item big = file("DCIM/Camera", "VID_" + run + "_big.mp4", 300L * 1024 * 1024 + 3, at(2024, 5, 2));
        BackupService.testItems = Arrays.asList(small, big);
        BackupService.testFiles = slowed(2); // about 30 MB a second: it's stopped part way

        ServiceController<BackupService> sc = startBackup();
        waitFor(() -> {
            BackupRun.Status s = BackupService.status();
            return s != null && s.sent >= 1 && s.bytesDone > small.size + 20L * 1024 * 1024 || !BackupService.running();
        }, 60_000, "part of the big file sent");
        assertTrue("still running", BackupService.running());
        sc.withIntent(new Intent(ctx, BackupService.class).setAction(BackupService.ACTION_STOP)).startCommand(0, 2);
        waitFor(() -> !BackupService.running(), 30_000, "the backup stopped");

        Notification stopped = notification(22);
        assertEquals(ctx.getString(R.string.backup_stopped), title(stopped));
        assertTrue("it offers to continue", stopped.actions != null && stopped.actions.length == 1
                && ctx.getString(R.string.backup_continue).contentEquals(stopped.actions[0].title));
        File dir = onPc(big).getParentFile();
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".part"));
        assertTrue("the PC kept a part of the big file", parts != null && parts.length == 1);
        long had = parts[0].length();
        assertTrue("a part, not all: " + had, had > 0 && had < big.size);

        BackupService.testFiles = null;
        startBackup(); // Continue
        waitFor(() -> !BackupService.running(), 60_000, "the backup's end");
        assertEquals(ctx.getString(R.string.backup_done), title(notification(22)));
        assertTrue("the big file is whole on the PC", same(new File(((Uri) big.ref).getPath()), onPc(big)));
        BackupRun.Status s = BackupService.status();
        assertEquals("only the big file went", 1, s.sent);
        assertEquals("from where the PC got to", big.size - had, s.sentBytes);
        File[] left = dir.listFiles((d, n) -> n.endsWith(".part"));
        assertTrue("no part is left", left == null || left.length == 0);
    }

    // ------------------------------------------------------------------ staying connected

    @Test
    public void stayingConnectedKeepsTheSessionAndThePcsSound() throws Exception {
        Prefs p = new Prefs(ctx);
        p.setStayConnected(true);
        p.setSound(true);
        // a stand-in AAC decoder (each frame: 1024 stereo samples) and the bytes that reach the speaker
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2048, 4096,
                (in, out) -> {
                    in.position(in.limit());
                    while (out.hasRemaining()) out.put((byte) 1);
                }));
        AtomicLong speaker = new AtomicLong();
        ShadowAudioTrack.addAudioDataListener((track, data, format) -> speaker.addAndGet(data.length));

        connect();
        assertTrue("the PC keeps its sound while paused", connection().hostInfo().can(Protocol.CAP_SOUND_PAUSED));
        long mark = log().length();
        Process tone = playTone(10);
        try {
            pump(1500);
            Application app = RuntimeEnvironment.getApplication();
            shadowOf(app).clearStartedServices();
            session.pause().stop(); // the phone locked
            Intent kept = shadowOf(app).getNextStartedService();
            assertNotNull("the session is kept alive", kept);
            assertEquals(SessionService.class.getName(), kept.getComponent().getClassName());
            assertEquals(SessionService.ACTION_START, kept.getAction());
            waitFor(() -> log().substring((int) mark).contains("session " + PHONE + ": STREAM_PAUSE, the sound goes on"),
                    5000, "the PC told to keep the sound");
            long before = speaker.get();
            pump(2500);
            long during = speaker.get() - before;
            System.out.println("sound to the speaker in 2.5 s in the background: " + during + " bytes");
            assertTrue("the PC's sound plays in the background", during > 0);

            // more than a minute later, by the app's clock: still connected
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(70));
            pump(500);
            assertNotNull("still connected", connection());
            assertFalse(connection().isClosed());
            assertFalse("the PC still has the session", log().substring((int) mark).contains("session " + PHONE + ": stopping"));
        } finally {
            tone.destroy();
        }
        session.start(); // back in front
        Intent let = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull("told to stop keeping it alive", let);
        assertEquals(SessionService.ACTION_STOP, let.getAction());
        session.resume();
        pump(300);
    }

    @Test
    public void withoutItTheConnectionClosesAMinuteAfterLeaving() throws Exception {
        Prefs p = new Prefs(ctx);
        p.setStayConnected(false);
        p.setSound(false);
        connect();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).clearStartedServices();
        session.pause().stop();
        assertNull("nothing kept alive", shadowOf(app).getNextStartedService());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        pump(300);
        assertNull("disconnected a minute after leaving", connection());
    }

    // ------------------------------------------------------------------ the phone

    private ServiceController<BackupService> startBackup() {
        Intent i = new Intent(ctx, BackupService.class).setAction(BackupService.ACTION_START)
                .putExtra(BackupService.EXTRA_HOST, paired.hostId);
        return Robolectric.buildService(BackupService.class, i).create().startCommand(0, 1);
    }

    /** A photo or video on the phone: a file here, of random bytes, with that date. */
    private BackupRun.Item file(String folder, String name, long size, long modifiedMs) throws IOException {
        File dir = new File(work, folder);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File f = new File(dir, name);
        Random r = new Random(name.hashCode());
        byte[] buf = new byte[1024 * 1024];
        try (OutputStream o = new FileOutputStream(f)) {
            for (long left = size; left > 0; left -= buf.length) {
                r.nextBytes(buf);
                o.write(buf, 0, (int) Math.min(buf.length, left));
            }
        }
        assertTrue(f.setLastModified(modifiedMs));
        String mime = name.endsWith(".mp4") ? "video/mp4" : name.endsWith(".png") ? "image/png" : "image/jpeg";
        return new BackupRun.Item(folder, name, mime, size, modifiedMs, Uri.fromFile(f));
    }

    /** The phone's files, read with a pause before each piece (the connection takes 64 KB at a time). */
    private BackupRun.Files slowed(int msPerRead) {
        return (item, offset) -> {
            BackupRun.Opened o = BackupScan.open(ctx, item, offset);
            return new BackupRun.Opened(new java.io.FilterInputStream(o.in) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        Thread.sleep(msPerRead);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return super.read(b, off, len);
                }
            }, o.size);
        };
    }

    private File onPc(BackupRun.Item i) {
        return new File(new File(new File(hostData, "backup"), PHONE), i.folder.replace('/', File.separatorChar) + File.separator + i.name);
    }

    private static long at(int y, int m, int d) {
        return java.time.LocalDateTime.of(y, m, d, 10, 30, 15).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + 123;
    }

    private Notification notification(int id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return shadowOf(nm).getNotification(id);
    }

    private static String title(Notification n) { return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)); }

    private static String text(Notification n) { return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)); }

    /** The session screen, connected to the PC. */
    private void connect() throws Exception {
        Intent i = new Intent(ctx, SessionActivity.class).putExtra(SessionActivity.EXTRA_HOST_ID, paired.hostId);
        session = Robolectric.buildActivity(SessionActivity.class, i).setup().windowFocusChanged(true);
        waitFor(() -> connection() != null, 10_000, "a connection to the PC");
        pump(800); // STREAM_START is on its way
    }

    private HostConnection connection() {
        try {
            RemoteSession s = get(session.get(), "session");
            return s == null ? null : s.connection();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the PC

    private String log() {
        try {
            return new String(Files.readAllBytes(new File(hostData, "host.log").toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** A quiet tone (about -30 dB) through this PC's speakers, for that long: the PC sends what they play. */
    private Process playTone(int seconds) throws IOException {
        int rate = 48000, n = rate * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 4).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate)
                .putInt(rate * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 4);
        for (int i = 0; i < n; i++) {
            short v = (short) Math.round(Math.sin(2 * Math.PI * 587 * i / rate) * 1000);
            b.putShort(v).putShort(v);
        }
        File wav = new File(work, "tone.wav");
        Files.write(wav.toPath(), b.array());
        return new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(New-Object Media.SoundPlayer '" + wav.getAbsolutePath() + "').PlaySync()").start();
    }

    // ------------------------------------------------------------------ helpers

    private static long clockAt = System.nanoTime();

    /** Lets posted work run for a while, the app's clock moved on by the time that really passed. */
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

    /** The two files hold the same bytes. */
    private static boolean same(File a, File b) throws IOException {
        if (a.length() != b.length()) return false;
        try (java.io.InputStream x = new java.io.BufferedInputStream(new java.io.FileInputStream(a), 1 << 20);
             java.io.InputStream y = new java.io.BufferedInputStream(new java.io.FileInputStream(b), 1 << 20)) {
            byte[] p = new byte[1 << 16], q = new byte[1 << 16];
            while (true) {
                int n = x.readNBytes(p, 0, p.length), m = y.readNBytes(q, 0, q.length);
                if (n != m) return false;
                if (n == 0) return true;
                for (int i = 0; i < n; i++) if (p[i] != q[i]) return false;
            }
        }
    }

    private static void deleteAll(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) deleteAll(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
