package com.desklink.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a {@link SecureChannel} to the first candidate address that completes the handshake
 * ("happy eyeballs": candidates start {@code staggerMs} apart, first verified handshake wins).
 */
public final class Connector {
    private Connector() {}

    private static final class State {
        SecureChannel winner;
        int pending;
        boolean done;
        final List<Socket> sockets = new ArrayList<>();
        IdentityMismatchException identity;
        HandshakeException handshake;
        IOException other;
    }

    /** Parsed "host", "host:port", "[v6]" or "[v6]:port". */
    public static final class Target {
        public final String host;
        public final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static Target parse(String address, int defaultPort) {
        String a = address.trim();
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                String h = a.substring(1, end);
                int port = defaultPort;
                if (a.length() > end + 2 && a.charAt(end + 1) == ':') port = parsePort(a.substring(end + 2), defaultPort);
                return new Target(h, port);
            }
        }
        int first = a.indexOf(':');
        if (first > 0 && first == a.lastIndexOf(':')) {
            return new Target(a.substring(0, first), parsePort(a.substring(first + 1), defaultPort));
        }
        return new Target(a, defaultPort);
    }

    private static int parsePort(String s, int def) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static SecureChannel open(List<String> addresses, final int port, final String pinnedFp,
                                     int timeoutMs, final int staggerMs) throws IOException {
        return open(addresses, port, pinnedFp, timeoutMs, staggerMs, null);
    }

    /**
     * {@code knockKey} (PairedHost.knockKey()): connections to internet addresses start with a knock made with it,
     * which the PC requires from the internet (PROTOCOL.md §2). Home-network addresses never get one.
     */
    public static SecureChannel open(List<String> addresses, final int port, final String pinnedFp,
                                     int timeoutMs, final int staggerMs, final byte[] knockKey) throws IOException {
        if (addresses == null || addresses.isEmpty()) throw new IOException("No address known for this PC");
        final State st = new State();
        final long start = System.currentTimeMillis();
        final long deadline = start + timeoutMs;
        final int connectTimeout = Math.max(1000, Math.min(timeoutMs, 5000));
        for (int i = 0; i < addresses.size(); i++) {
            final String addr = addresses.get(i);
            final long startAt = start + (long) i * staggerMs;
            synchronized (st) { st.pending++; }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() { attempt(st, addr, port, pinnedFp, startAt, deadline, connectTimeout, knockKey); }
            }, "dl-connect");
            t.setDaemon(true);
            t.start();
        }
        synchronized (st) {
            while (st.winner == null && st.pending > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    st.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            st.done = true;
            for (Socket s : st.sockets) closeQuietly(s);
            st.sockets.clear();
            st.notifyAll();
            if (st.winner != null) return st.winner;
            if (st.identity != null) throw st.identity;
            if (st.handshake != null) throw st.handshake;
            if (st.other != null && st.pending == 0) throw st.other;
            throw new SocketTimeoutException("Timed out connecting to the PC");
        }
    }

    private static void attempt(State st, String addr, int defaultPort, String fp, long startAt, long deadline, int connectTimeout,
                                byte[] knockKey) {
        try {
            synchronized (st) {
                while (!st.done && st.winner == null) {
                    long w = startAt - System.currentTimeMillis();
                    if (w <= 0) break;
                    st.wait(w);
                }
                if (st.done || st.winner != null) return;
            }
            Target target = parse(addr, defaultPort);
            InetAddress[] ips = InetAddress.getAllByName(target.host);
            for (InetAddress ip : ips) {
                Socket s = new Socket();
                synchronized (st) {
                    if (st.done || st.winner != null) return;
                    st.sockets.add(s);
                }
                try {
                    int to = (int) Math.max(500, Math.min(connectTimeout, deadline - System.currentTimeMillis()));
                    s.connect(new InetSocketAddress(ip, target.port), to);
                    if (knockKey != null && !isLocal(ip)) s.getOutputStream().write(knock(knockKey));
                    SecureChannel ch = SecureChannel.handshake(s, fp, addr);
                    boolean won = false;
                    synchronized (st) {
                        st.sockets.remove(s);
                        if (st.winner == null && !st.done) {
                            st.winner = ch;
                            won = true;
                            st.notifyAll();
                        }
                    }
                    if (!won) ch.close();
                    return;
                } catch (IdentityMismatchException e) {
                    synchronized (st) { st.identity = e; st.sockets.remove(s); }
                    closeQuietly(s);
                    return;
                } catch (HandshakeException e) {
                    synchronized (st) { st.handshake = e; st.sockets.remove(s); }
                    closeQuietly(s);
                } catch (IOException e) {
                    synchronized (st) { if (!st.done) st.other = e; st.sockets.remove(s); }
                    closeQuietly(s);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            synchronized (st) { if (!st.done) st.other = e; }
        } catch (RuntimeException e) {
            synchronized (st) { if (!st.done) st.other = new IOException(e.toString(), e); }
        } finally {
            synchronized (st) {
                st.pending--;
                st.notifyAll();
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    /**
     * Home network or Tailscale, as opposed to the internet: loopback, private and link-local IPv4, 100.64/10
     * (Tailscale / CGNAT), IPv6 link-local and unique-local (fc00::/7). Matches the PC's own rule.
     */
    public static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        if (b.length == 16) {
            boolean mapped = true; // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return isLocal(InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16)));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    /**
     * The knock (PROTOCOL.md §2), framed like a handshake message: u32 60, then "DLKN" u64 unixTime nonce[16]
     * HMAC-SHA256(key, the first 28 bytes). Only a paired phone has the key; the PC accepts each nonce once.
     */
    static byte[] knock(byte[] key) throws IOException {
        byte[] f = new byte[4 + 60];
        f[3] = 60;
        f[4] = 'D';
        f[5] = 'L';
        f[6] = 'K';
        f[7] = 'N';
        long t = System.currentTimeMillis() / 1000;
        for (int i = 0; i < 8; i++) f[8 + i] = (byte) (t >>> (56 - 8 * i));
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        System.arraycopy(nonce, 0, f, 16, 16);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            mac.update(f, 4, 28);
            System.arraycopy(mac.doFinal(), 0, f, 32, 32);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("knock: " + e, e);
        }
        return f;
    }
}
