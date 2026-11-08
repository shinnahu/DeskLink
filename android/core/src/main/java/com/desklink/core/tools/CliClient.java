package com.desklink.core.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.desklink.core.Connector;
import com.desklink.core.Discovery;
import com.desklink.core.Hex;
import com.desklink.core.HostConnection;
import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Pairing;
import com.desklink.core.Protocol;
import com.desklink.core.SecureChannel;
import com.desklink.core.StatusProbe;

/**
 * Desktop test client for the DeskLink host.
 * Credentials are kept in ./desklink-cli.json (override with env DESKLINK_CREDS).
 */
public final class CliClient {
    private CliClient() {}

    private static void usage() {
        System.out.println("DeskLink CLI client\n"
                + "  pair      <host> [port]                        pair (shows the 6-digit code), saves credentials\n"
                + "  status    <host> [port]                        STATUS_QUERY probe\n"
                + "  connect   <host> [port] [seconds] [out.h264]   stream video, ack frames, print stats\n"
                + "  send-file <host> <path>                        upload a file to the PC\n"
                + "  get-file  <host> [seconds]                     ask the PC to pick a file and download it here\n"
                + "  type      <host> <text>                        type text (TEXT message)\n"
                + "  clip      <host> <text>                        set the PC clipboard\n"
                + "  move      <host> <x> <y>                       move the mouse (monitor-relative pixels)\n"
                + "  click     <host> <x> <y> [left|right|middle]   click at a position\n"
                + "  key       <host> <vkHex>                       press+release a virtual key, e.g. 0x5B\n"
                + "  discover                                       LAN discovery (UDP broadcast)\n"
                + "<host> may be ip, name, ip:port or [ipv6]:port.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            return;
        }
        String cmd = args[0];
        try {
            switch (cmd) {
                case "discover": discover(); break;
                case "pair": pair(arg(args, 1), intArg(args, 2, Protocol.DEFAULT_PORT)); break;
                case "status": status(arg(args, 1), intArg(args, 2, -1)); break;
                case "connect": connect(arg(args, 1), intArg(args, 2, -1), intArg(args, 3, 10), args.length > 4 ? args[4] : null); break;
                case "send-file": sendFile(arg(args, 1), arg(args, 2)); break;
                case "get-file": getFile(arg(args, 1), intArg(args, 2, 60)); break;
                case "type": simple(arg(args, 1), c -> c.text(joinFrom(args, 2))); break;
                case "clip": simple(arg(args, 1), c -> c.sendClipboard(joinFrom(args, 2))); break;
                case "move": simple(arg(args, 1), c -> c.mouseMove(Integer.parseInt(arg(args, 2)), Integer.parseInt(arg(args, 3)))); break;
                case "click": simple(arg(args, 1), c -> {
                    int btn = Protocol.BTN_LEFT;
                    String b = args.length > 4 ? args[4] : "left";
                    if (b.startsWith("r")) btn = Protocol.BTN_RIGHT;
                    else if (b.startsWith("m")) btn = Protocol.BTN_MIDDLE;
                    c.mouseMove(Integer.parseInt(arg(args, 2)), Integer.parseInt(arg(args, 3)));
                    c.mouseButton(btn, true);
                    c.mouseButton(btn, false);
                });
                    break;
                case "key": simple(arg(args, 1), c -> c.keyTap(Integer.decode(arg(args, 2)))); break;
                default: usage();
            }
        } catch (IOException e) {
            System.out.println("ERROR: " + e);
            System.exit(2);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ commands

    private static void discover() {
        System.out.println("Scanning for 2.5 s...");
        List<Discovery.Found> all = Discovery.scan(Discovery.broadcastTargets(), 2500, f -> System.out.println("  found " + f));
        System.out.println(all.size() + " host(s).");
    }

    private static void pair(String host, int port) throws IOException {
        String devName = "CLI on " + localName();
        System.out.println("Connecting to " + host + " ...");
        SecureChannel ch = Connector.open(Collections.singletonList(host), port, null, 8000, 0);
        System.out.println("Host fingerprint: " + ch.fingerprint() + "  (" + Hex.shortFingerprint(ch.fingerprint()) + ")");
        PairedHost h;
        try {
            h = Pairing.pair(ch, Connector.parse(host, port).port, devName, System.getProperty("os.name"),
                    code -> System.out.println(">>> Comparison code: " + code + "  — click Allow on the PC if it matches"));
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        Pairing.finishWithHostInfo(ch, h, devName);
        saveCreds(h);
        System.out.println("PAIRED with '" + h.hostName + "' hostId=" + h.hostId + " deviceId=" + Hex.hex(h.deviceId));
        System.out.println("Addresses: " + h.addresses);
        System.out.println("Credentials saved to " + credsFile().getAbsolutePath());
    }

    private static void status(String host, int port) throws IOException {
        PairedHost h = credsFor(host, port);
        long t0 = System.currentTimeMillis();
        Messages.StatusResult s = StatusProbe.probe(h, StatusProbe.DEFAULT_TIMEOUT_MS);
        System.out.println(s + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void connect(String host, int port, int seconds, String outPath) throws Exception {
        PairedHost h = credsFor(host, port);
        final OutputStream out = outPath == null ? null : new FileOutputStream(outPath);
        final AtomicInteger frames = new AtomicInteger(), keyframes = new AtomicInteger();
        final AtomicLong videoBytes = new AtomicLong();
        final CountDownLatch closed = new CountDownLatch(1);
        final HostConnection[] ref = new HostConnection[1];
        HostConnection.Listener l = new HostConnection.Listener() {
            @Override public void onHostInfo(Messages.HostInfo info) { System.out.println("HOST_INFO " + info); }
            @Override public void onVideoFormat(Messages.VideoFormat f) { System.out.println("VIDEO_FORMAT " + f); }
            @Override public void onVideoFrame(int id, boolean key, long ts, byte[] d, int off, int len) {
                HostConnection c = ref[0];
                if (c != null) c.ackFrame(id);
                frames.incrementAndGet();
                if (key) {
                    keyframes.incrementAndGet();
                    System.out.println("keyframe #" + id + " " + len + " bytes");
                }
                videoBytes.addAndGet(len);
                if (out != null) {
                    try { out.write(d, off, len); } catch (IOException ignored) {}
                }
            }
            @Override public void onCursorShape(Messages.CursorShape s) { System.out.println("CURSOR_SHAPE id=" + s.id + " " + s.width + "x" + s.height + " hot=" + s.hotX + "," + s.hotY); }
            @Override public void onCursorState(int x, int y, boolean v, int shape) { System.out.println("CURSOR_STATE " + x + "," + y + " visible=" + v + " shape=" + shape); }
            @Override public void onHostStatus(int flags, String m) { System.out.println("HOST_STATUS flags=" + flags + " " + m); }
            @Override public void onClipboard(String t) { System.out.println("CLIPBOARD from PC: " + abbreviate(t)); }
            @Override public void onNotice(int level, String t) { System.out.println("NOTICE[" + level + "] " + t); }
            @Override public void onClosed(HostConnection.CloseInfo info) { System.out.println("CLOSED: " + info); closed.countDown(); }
        };
        long t0 = System.currentTimeMillis();
        HostConnection c = HostConnection.connect(h, "CLI on " + localName(), l, 8000);
        ref[0] = c;
        System.out.println("Connected via " + c.address() + " in " + (System.currentTimeMillis() - t0) + " ms; " + c.hostInfo());
        touch(h, c.address());
        c.startStream(Protocol.MONITOR_PRIMARY, 1920, 1920, 60, 0, true);
        long lastBytes = 0;
        int lastFrames = 0;
        for (int s = 1; s <= seconds; s++) {
            if (closed.await(1, TimeUnit.SECONDS)) break;
            int f = frames.get();
            long b = videoBytes.get();
            System.out.printf("t=%ds frames=%d (+%d fps) keyframes=%d video=%d kbps rtt=%d ms%n",
                    s, f, f - lastFrames, keyframes.get(), (b - lastBytes) * 8 / 1000, c.rttMs());
            lastFrames = f;
            lastBytes = b;
        }
        c.releaseAll();
        c.disconnect();
        closed.await(3, TimeUnit.SECONDS);
        if (out != null) out.close();
        System.out.println("Total frames " + frames.get() + ", " + videoBytes.get() + " bytes" + (outPath != null ? " -> " + outPath : ""));
    }

    private static void sendFile(String host, String path) throws Exception {
        PairedHost h = credsFor(host, -1);
        File f = new File(path);
        if (!f.isFile()) throw new IOException("not a file: " + path);
        HostConnection c = HostConnection.connect(h, "CLI on " + localName(), null, 8000);
        final CountDownLatch done = new CountDownLatch(1);
        final long size = f.length();
        long t0 = System.currentTimeMillis();
        c.sendFile(f.getName(), size, guessMime(f.getName()), new FileInputStream(f), new HostConnection.SendListener() {
            long lastPct = -1;
            @Override public void onProgress(long acked, long total) {
                long pct = total > 0 ? acked * 100 / total : 0;
                if (pct / 10 != lastPct / 10) System.out.println("  " + pct + "% (" + acked + "/" + total + ")");
                lastPct = pct;
            }
            @Override public void onDone(int status, String info) {
                System.out.println("FILE_DONE status=" + status + " info=" + info);
                done.countDown();
            }
        });
        done.await(10, TimeUnit.MINUTES);
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        System.out.println(size + " bytes in " + ms + " ms (" + (size * 8 / ms) + " kbps)");
        c.disconnect();
        Thread.sleep(300);
    }

    private static void getFile(String host, int seconds) throws Exception {
        PairedHost h = credsFor(host, -1);
        final CountDownLatch done = new CountDownLatch(1);
        HostConnection.Listener l = new HostConnection.Listener() {
            @Override public HostConnection.IncomingFileSink onIncomingFile(int id, String name, long size, String mime) {
                final File target = uniqueFile(new File(name).getName());
                System.out.println("Receiving '" + name + "' (" + size + " bytes, " + mime + ") -> " + target);
                try {
                    final FileOutputStream fos = new FileOutputStream(target);
                    return new HostConnection.IncomingFileSink() {
                        @Override public void write(byte[] b, int off, int len) throws IOException { fos.write(b, off, len); }
                        @Override public void finish() throws IOException { fos.close(); }
                        @Override public void abort() {
                            try { fos.close(); } catch (IOException ignored) {}
                            //noinspection ResultOfMethodCallIgnored
                            target.delete();
                        }
                    };
                } catch (IOException e) {
                    return null;
                }
            }
            @Override public void onIncomingFileDone(int id, int status, String error) {
                System.out.println("file #" + id + " done status=" + status + (error != null ? " " + error : ""));
                done.countDown();
            }
            @Override public void onFileRequestNone() {
                System.out.println("The PC user cancelled the file picker.");
                done.countDown();
            }
        };
        HostConnection c = HostConnection.connect(h, "CLI on " + localName(), l, 8000);
        c.requestFile();
        System.out.println("Asked the PC to show its file picker; waiting up to " + seconds + " s ...");
        done.await(seconds, TimeUnit.SECONDS);
        Thread.sleep(500);
        c.disconnect();
        Thread.sleep(300);
    }

    private interface Action {
        void run(HostConnection c) throws Exception;
    }

    private static void simple(String host, Action a) throws Exception {
        PairedHost h = credsFor(host, -1);
        HostConnection c = HostConnection.connect(h, "CLI on " + localName(), null, 8000);
        System.out.println("Connected via " + c.address());
        a.run(c);
        Thread.sleep(400);
        c.disconnect();
        Thread.sleep(300);
        System.out.println("done");
    }

    // ------------------------------------------------------------------ helpers

    private static String arg(String[] a, int i) {
        if (a.length <= i) {
            usage();
            System.exit(1);
        }
        return a[i];
    }

    private static int intArg(String[] a, int i, int def) {
        if (a.length <= i) return def;
        try {
            return Integer.parseInt(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String joinFrom(String[] a, int i) {
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < a.length; k++) {
            if (k > i) sb.append(' ');
            sb.append(a[k]);
        }
        return sb.toString().replace("\\n", "\n").replace("\\t", "\t");
    }

    private static String abbreviate(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 100 ? t.substring(0, 100) + "…(" + s.length() + " chars)" : t;
    }

    private static String localName() {
        String n = System.getenv("COMPUTERNAME");
        if (n != null && !n.isEmpty()) return n;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "desktop";
        }
    }

    private static File credsFile() {
        String p = System.getenv("DESKLINK_CREDS");
        return new File(p != null && !p.isEmpty() ? p : "desklink-cli.json");
    }

    private static void saveCreds(PairedHost h) throws IOException {
        Files.write(credsFile().toPath(), h.toJson().getBytes(StandardCharsets.UTF_8));
    }

    private static PairedHost credsFor(String host, int port) throws IOException {
        File f = credsFile();
        if (!f.isFile()) throw new IOException("No credentials at " + f.getAbsolutePath() + " — run 'pair' first");
        PairedHost h = PairedHost.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        Connector.Target t = Connector.parse(host, port > 0 ? port : h.port);
        if (port > 0) h.port = port;
        else if (t.port != h.port) h.port = t.port;
        h.addresses = new ArrayList<>(Collections.singletonList(host));
        h.lastAddress = host;
        return h;
    }

    private static void touch(PairedHost h, String address) {
        // keep the CLI creds file untouched except for bookkeeping fields
        try {
            File f = credsFile();
            PairedHost stored = PairedHost.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            stored.lastConnected = System.currentTimeMillis();
            stored.lastSeenOnline = stored.lastConnected;
            saveCreds(stored);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static File uniqueFile(String name) {
        File f = new File(name);
        int i = 1;
        while (f.exists()) {
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            f = new File(base + " (" + i++ + ")" + ext);
        }
        return f;
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
