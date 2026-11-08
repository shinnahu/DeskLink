package com.desklink.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UDP LAN discovery (PROTOCOL.md §1). */
public final class Discovery {
    private Discovery() {}

    private static final byte[] REQUEST = "DESKLINK?1".getBytes(StandardCharsets.US_ASCII);
    private static final String REPLY_PREFIX = "DESKLINK!1";
    private static final int[] SEND_AT_MS = {0, 300, 800};

    public static final class Found {
        public String id = "";
        public String name = "";
        public String fingerprint = "";
        public String address = "";
        public int port = Protocol.DEFAULT_PORT;
        public boolean pairing;
        public boolean busy;
        public int version = 1;

        @Override
        public String toString() {
            return name + " @ " + address + ":" + port + " id=" + id + (pairing ? " [pairing open]" : "") + (busy ? " [busy]" : "");
        }
    }

    public interface Listener {
        void onFound(Found f);
    }

    /** 255.255.255.255 plus the broadcast address of every up IPv4 interface. */
    public static List<InetAddress> broadcastTargets() {
        LinkedHashSet<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (IOException e) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new ArrayList<>(out);
    }

    /** Literal IP addresses only (no DNS lookups). */
    public static List<InetAddress> literalTargets(Collection<String> addresses) {
        List<InetAddress> out = new ArrayList<>();
        for (String a : addresses) {
            if (a == null) continue;
            String h = Connector.parse(a, Protocol.DEFAULT_PORT).host;
            if (!looksLikeIp(h)) continue;
            try {
                out.add(InetAddress.getByName(h));
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    static boolean looksLikeIp(String h) {
        if (h.indexOf(':') >= 0) return true; // IPv6 literal
        if (h.isEmpty()) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    /**
     * Sends the request to all targets (3 times) and collects replies for {@code durationMs}.
     * {@code listener} (may be null) is called on this thread as hosts appear. Blocking.
     */
    public static List<Found> scan(Collection<InetAddress> targets, int durationMs, Listener listener) {
        List<Found> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(100);
            long start = System.currentTimeMillis();
            int next = 0;
            byte[] buf = new byte[4096];
            while (true) {
                long el = System.currentTimeMillis() - start;
                if (el >= durationMs) break;
                while (next < SEND_AT_MS.length && el >= SEND_AT_MS[next]) {
                    for (InetAddress t : targets) {
                        try {
                            s.send(new DatagramPacket(REQUEST, REQUEST.length, t, Protocol.DISCOVERY_PORT));
                        } catch (IOException | SecurityException ignored) {
                        }
                    }
                    next++;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Found f = parse(p);
                if (f != null && seen.add(f.id + "|" + f.address)) {
                    out.add(f);
                    if (listener != null) listener.onFound(f);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (s != null) s.close();
        }
        return out;
    }

    static Found parse(DatagramPacket p) {
        try {
            String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
            if (!text.startsWith(REPLY_PREFIX)) return null;
            Map<String, Object> m = Json.parseObject(text.substring(REPLY_PREFIX.length()).trim());
            Found f = new Found();
            f.id = Json.getString(m, "id", "");
            f.name = Json.getString(m, "name", "PC");
            f.fingerprint = Json.getString(m, "fp", "");
            f.port = (int) Json.getLong(m, "port", Protocol.DEFAULT_PORT);
            f.pairing = Json.getBool(m, "pairing", false);
            f.busy = Json.getBool(m, "busy", false);
            f.version = (int) Json.getLong(m, "ver", 1);
            InetAddress a = p.getAddress();
            String ip = a.getHostAddress();
            int pct = ip.indexOf('%');
            if (pct > 0) ip = ip.substring(0, pct);
            f.address = ip;
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
