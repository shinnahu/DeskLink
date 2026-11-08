package com.desklink.core.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.desklink.core.ByteReader;
import com.desklink.core.ByteWriter;
import com.desklink.core.Crypto;
import com.desklink.core.Hex;
import com.desklink.core.Protocol;

/**
 * Minimal host implementation of PROTOCOL.md for loopback-testing the client core (auto-approves pairing,
 * sends synthetic "video", echoes clipboard, stores uploads in the temp dir). Not the real host.
 */
public final class FakeHost {
    private final KeyPair identity = Crypto.newP256KeyPair();
    private final byte[] identityPub = Crypto.encodePoint(identity.getPublic());
    private final byte[] hostId = Crypto.random(16);
    private final Map<String, String> tokens = new ConcurrentHashMap<>(); // deviceId hex -> sha256(token) hex
    private final long startMs = System.currentTimeMillis();
    private volatile String activeDevice = "";

    /** Usage: FakeHost [tcpPort] [udpDiscoveryPort]. Everything binds to 127.0.0.1 only (no firewall prompts). */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        int udp = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        new FakeHost().run(port, udp);
    }

    private void run(int port, int udpPort) throws IOException {
        System.out.println("FakeHost on 127.0.0.1:" + port + " fingerprint " + Hex.hex(Crypto.sha256(identityPub)));
        if (udpPort > 0) startDiscovery(udpPort);
        try (ServerSocket ss = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                final Socket s = ss.accept();
                new Thread(() -> {
                    try {
                        handle(s);
                    } catch (Exception e) {
                        System.out.println("[conn] ended: " + e);
                    } finally {
                        try { s.close(); } catch (IOException ignored) {}
                    }
                }).start();
            }
        }
    }

    private void startDiscovery(int udpPort) {
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), udpPort))) {
                byte[] buf = new byte[64];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    if (!new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).equals("DESKLINK?1")) continue;
                    String json = "{\"id\":\"" + Hex.hex(hostId) + "\",\"name\":\"FakeHost\",\"port\":" + Protocol.DEFAULT_PORT
                            + ",\"fp\":\"" + Hex.hex(Crypto.sha256(identityPub)) + "\",\"pairing\":true,\"busy\":false,\"ver\":1}";
                    byte[] reply = ("DESKLINK!1" + json).getBytes(StandardCharsets.UTF_8);
                    ds.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            } catch (IOException e) {
                System.out.println("[discovery] off: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Server side of the secure channel. */
    private static final class Chan {
        final DataInputStream in;
        final OutputStream out;
        final SecretKeySpec sendKey, recvKey;
        final Cipher enc, dec;
        long sendSeq, recvSeq;
        final byte[] th;

        Chan(DataInputStream in, OutputStream out, byte[] keyH2C, byte[] keyC2H, byte[] th) throws GeneralSecurityException {
            this.in = in;
            this.out = out;
            this.sendKey = new SecretKeySpec(keyH2C, "AES");
            this.recvKey = new SecretKeySpec(keyC2H, "AES");
            this.enc = Cipher.getInstance("AES/GCM/NoPadding");
            this.dec = Cipher.getInstance("AES/GCM/NoPadding");
            this.th = th;
        }

        static byte[] nonce(long seq) {
            byte[] n = new byte[12];
            for (int i = 0; i < 8; i++) n[4 + i] = (byte) (seq >>> (56 - 8 * i));
            return n;
        }

        synchronized void send(byte[] pt) throws IOException {
            int len = pt.length + 16;
            byte[] hdr = {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
            try {
                enc.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, nonce(sendSeq++)));
                enc.updateAAD(hdr);
                byte[] ct = enc.doFinal(pt);
                out.write(hdr);
                out.write(ct);
                out.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        byte[] recv() throws IOException {
            int len = in.readInt();
            byte[] ct = new byte[len];
            in.readFully(ct);
            byte[] hdr = {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
            try {
                dec.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(128, nonce(recvSeq++)));
                dec.updateAAD(hdr);
                return dec.doFinal(ct);
            } catch (GeneralSecurityException e) {
                throw new IOException("bad record", e);
            }
        }
    }

    private void handle(Socket s) throws Exception {
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        int len = in.readInt();
        if (len != Protocol.CLIENT_HELLO_LEN) throw new IOException("bad ClientHello len " + len);
        byte[] ch = new byte[len];
        in.readFully(ch);
        if (!new String(ch, 0, 4, StandardCharsets.US_ASCII).equals("DLNK") || ch[4] != 1) throw new IOException("bad hello");
        byte[] clientEph = Arrays.copyOfRange(ch, 5, 70);
        KeyPair eph = Crypto.newP256KeyPair();
        byte[] signedPart = new ByteWriter(167).bytes("DLNK".getBytes(StandardCharsets.US_ASCII)).u8(1)
                .bytes(Crypto.encodePoint(eph.getPublic())).bytes(Crypto.random(32)).bytes(identityPub).toByteArray();
        byte[] toSign = new ByteWriter(ch.length + signedPart.length).bytes(ch).bytes(signedPart).toByteArray();
        byte[] sig = Crypto.signP256Raw(identity.getPrivate(), toSign);
        byte[] sh = new ByteWriter(231).bytes(signedPart).bytes(sig).toByteArray();
        out.write(new ByteWriter(4).i32(sh.length).toByteArray());
        out.write(sh);
        out.flush();
        byte[] z = Crypto.ecdh(eph.getPrivate(), Crypto.decodePoint(clientEph));
        byte[] th = Crypto.sha256(ch, sh);
        byte[][] keys = Crypto.deriveSessionKeys(th, z);
        final Chan c = new Chan(in, out, keys[1], keys[0], th);
        System.out.println("[conn] handshake ok from " + s.getRemoteSocketAddress());

        final Session sess = new Session(c);
        while (true) {
            byte[] m = c.recv();
            if (!sess.onMessage(m)) break;
        }
    }

    private final class Session {
        final Chan c;
        volatile boolean streaming, authed;
        volatile int acked = -1;
        int sent = -1;
        byte[] commit;
        byte[] nonceH;
        String pendingName = "", pendingModel = "";
        FileOutputStream upload;
        File uploadFile;
        long uploadBytes, uploadProgress;
        Thread video;

        Session(Chan c) { this.c = c; }

        boolean onMessage(byte[] m) throws Exception {
            int type = m[0] & 0xFF;
            ByteReader r = new ByteReader(m, 1, m.length - 1);
            switch (type) {
                case Protocol.PAIR_REQUEST:
                    commit = r.bytes(32);
                    pendingName = r.str();
                    pendingModel = r.str();
                    nonceH = Crypto.random(32);
                    c.send(new ByteWriter(33).u8(Protocol.PAIR_CHALLENGE).bytes(nonceH).toByteArray());
                    return true;
                case Protocol.PAIR_REVEAL: {
                    byte[] nonceC = r.bytes(32);
                    if (commit == null || !Arrays.equals(Crypto.sha256(nonceC), commit)) {
                        c.send(new ByteWriter().u8(Protocol.PAIR_RESULT).u8(Protocol.ST_DENIED).str("bad commitment").toByteArray());
                        return false;
                    }
                    String code = Crypto.sasCode(c.th, nonceC, nonceH);
                    System.out.println("[pair] '" + pendingName + "' (" + pendingModel + ") code " + code + " -> auto-approve");
                    byte[] devId = Crypto.random(16), token = Crypto.random(32);
                    tokens.put(Hex.hex(devId), Hex.hex(Crypto.sha256(token)));
                    c.send(new ByteWriter().u8(Protocol.PAIR_RESULT).u8(Protocol.ST_OK).bytes(devId).bytes(token).bytes(hostId)
                            .str("FakeHost").str("paired").toByteArray());
                    return true;
                }
                case Protocol.AUTH: {
                    byte[] devId = r.bytes(16), token = r.bytes(32);
                    String name = r.str();
                    String expect = tokens.get(Hex.hex(devId));
                    boolean ok = expect == null ? Boolean.getBoolean("fakehost.acceptAny") : expect.equals(Hex.hex(Crypto.sha256(token)));
                    if (!ok) {
                        c.send(new ByteWriter().u8(Protocol.AUTH_RESULT).u8(expect == null ? Protocol.ST_UNKNOWN_DEVICE : Protocol.ST_BAD_TOKEN).str("no").toByteArray());
                        return false;
                    }
                    authed = true;
                    activeDevice = name;
                    System.out.println("[auth] " + name + " ok");
                    c.send(new ByteWriter().u8(Protocol.AUTH_RESULT).u8(Protocol.ST_OK).str("").toByteArray());
                    c.send(new ByteWriter().u8(Protocol.HOST_INFO).bytes(hostId).str("FakeHost").u32(7)
                            .u8(1).u8(0).i32(0).i32(0).i32(1920).i32(1080).u8(1).str("Fake monitor")
                            .u8(2).str("127.0.0.1").str("localhost").toByteArray());
                    startPinger();
                    return true;
                }
                case Protocol.STATUS_QUERY: {
                    byte[] devId = r.bytes(16), token = r.bytes(32);
                    String expect = tokens.get(Hex.hex(devId));
                    int st = expect == null ? Protocol.ST_UNKNOWN_DEVICE : (expect.equals(Hex.hex(Crypto.sha256(token))) ? 0 : Protocol.ST_BAD_TOKEN);
                    int flags = activeDevice.isEmpty() ? 0 : Protocol.SQ_IN_USE;
                    c.send(new ByteWriter().u8(Protocol.STATUS_RESULT).u8(st).u8(flags).str("FakeHost").str(activeDevice)
                            .u32((System.currentTimeMillis() - startMs) / 1000).toByteArray());
                    return false;
                }
                default:
                    if (!authed) {
                        System.out.println("[conn] message 0x" + Integer.toHexString(type) + " before auth");
                        return false;
                    }
                    return onSessionMessage(type, m, r);
            }
        }

        void startPinger() {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(2000);
                        c.send(new ByteWriter(9).u8(Protocol.PING).u64(System.nanoTime() / 1_000_000).toByteArray());
                    }
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
        }

        boolean onSessionMessage(int type, byte[] m, ByteReader r) throws Exception {
            switch (type) {
                case Protocol.PING: {
                    byte[] p = m.clone();
                    p[0] = (byte) Protocol.PONG;
                    c.send(p);
                    return true;
                }
                case Protocol.PONG: return true;
                case Protocol.STREAM_START:
                    System.out.println("[stream] start monitor=" + r.u8() + " max=" + r.u16() + "x" + r.u16() + " fps=" + r.u8() + " kbps=" + r.u32() + " flags=" + r.u8());
                    startVideo();
                    return true;
                case Protocol.STREAM_PAUSE: streaming = false; System.out.println("[stream] pause"); return true;
                case Protocol.STREAM_RESUME: System.out.println("[stream] resume"); startVideo(); return true;
                case Protocol.FRAME_ACK: acked = r.i32(); synchronized (this) { notifyAll(); } return true;
                case Protocol.KEYFRAME_REQUEST: System.out.println("[stream] keyframe request"); return true;
                case Protocol.MOUSE_MOVE: System.out.println("[input] move " + r.i32() + "," + r.i32()); return true;
                case Protocol.MOUSE_BUTTON: System.out.println("[input] button " + r.u8() + (r.u8() != 0 ? " down" : " up")); return true;
                case Protocol.MOUSE_WHEEL: System.out.println("[input] wheel dy=" + r.i16() + " dx=" + r.i16()); return true;
                case Protocol.KEY: System.out.println("[input] key 0x" + Integer.toHexString(r.u16()) + (r.u8() != 0 ? " down" : " up")); return true;
                case Protocol.TEXT: System.out.println("[input] text '" + r.tailUtf8() + "'"); return true;
                case Protocol.RELEASE_ALL: System.out.println("[input] release all"); return true;
                case Protocol.ACTION: System.out.println("[input] action " + r.u8()); return true;
                case Protocol.CLIPBOARD: {
                    String t = r.tailUtf8();
                    System.out.println("[clip] from phone: " + t);
                    c.send(new ByteWriter().u8(Protocol.CLIPBOARD).utf8Tail("echo: " + t).toByteArray());
                    return true;
                }
                case Protocol.FILE_BEGIN: {
                    int id = r.i32();
                    long size = r.u64();
                    String name = r.str();
                    uploadFile = new File(System.getProperty("java.io.tmpdir"), "fakehost-" + new File(name).getName());
                    upload = new FileOutputStream(uploadFile);
                    uploadBytes = 0;
                    uploadProgress = 0;
                    System.out.println("[file] begin #" + id + " " + name + " " + size + " bytes");
                    return true;
                }
                case Protocol.FILE_DATA: {
                    int id = r.i32();
                    int n = r.remaining();
                    upload.write(m, r.position(), n);
                    uploadBytes += n;
                    if (uploadBytes - uploadProgress >= Protocol.FILE_PROGRESS_EVERY) {
                        uploadProgress = uploadBytes;
                        c.send(new ByteWriter(13).u8(Protocol.FILE_PROGRESS).i32(id).u64(uploadBytes).toByteArray());
                    }
                    return true;
                }
                case Protocol.FILE_END: {
                    int id = r.i32();
                    upload.close();
                    c.send(new ByteWriter(13).u8(Protocol.FILE_PROGRESS).i32(id).u64(uploadBytes).toByteArray());
                    c.send(new ByteWriter().u8(Protocol.FILE_DONE).i32(id).u8(Protocol.FD_OK).str(uploadFile.getAbsolutePath()).toByteArray());
                    System.out.println("[file] done #" + id + " " + uploadBytes + " bytes -> " + uploadFile);
                    return true;
                }
                case Protocol.FILE_CANCEL: System.out.println("[file] cancel #" + r.i32()); return true;
                case Protocol.FILE_PROGRESS: return true;
                case Protocol.FILE_DONE: System.out.println("[file] phone confirmed #" + r.i32() + " status " + r.u8()); return true;
                case Protocol.FILE_REQUEST: {
                    byte[] data = new byte[300 * 1024];
                    for (int i = 0; i < data.length; i++) data[i] = (byte) ('a' + i % 26);
                    int id = 2;
                    c.send(new ByteWriter().u8(Protocol.FILE_BEGIN).i32(id).u64(data.length).str("from-pc.txt").str("text/plain").toByteArray());
                    for (int off = 0; off < data.length; off += 65536) {
                        int n = Math.min(65536, data.length - off);
                        c.send(new ByteWriter(5 + n).u8(Protocol.FILE_DATA).i32(id).bytes(data, off, n).toByteArray());
                    }
                    c.send(new ByteWriter(5).u8(Protocol.FILE_END).i32(id).toByteArray());
                    return true;
                }
                case Protocol.BYE:
                    System.out.println("[conn] bye");
                    streaming = false;
                    activeDevice = "";
                    return false;
                default:
                    System.out.println("[conn] unhandled 0x" + Integer.toHexString(type));
                    return true;
            }
        }

        synchronized void startVideo() throws IOException {
            c.send(new ByteWriter().u8(Protocol.VIDEO_FORMAT).u8(1).u16(1280).u16(720).u8(0).i32(0).i32(0).i32(1920).i32(1080).toByteArray());
            byte[] shape = new byte[4 * 4 * 4];
            Arrays.fill(shape, (byte) 0xFF);
            c.send(new ByteWriter().u8(Protocol.CURSOR_SHAPE).i32(1).u16(4).u16(4).u16(0).u16(0).bytes(shape).toByteArray());
            c.send(new ByteWriter().u8(Protocol.CURSOR_STATE).i32(960).i32(540).u8(1).i32(1).toByteArray());
            streaming = true;
            if (video != null && video.isAlive()) return;
            video = new Thread(() -> {
                try {
                    boolean first = true;
                    while (streaming) {
                        synchronized (Session.this) {
                            while (streaming && sent - acked >= 3) Session.this.wait(100);
                        }
                        if (!streaming) break;
                        sent++;
                        ByteWriter w = new ByteWriter(4096).u8(Protocol.VIDEO_FRAME).i32(sent).u8(first ? 1 : 0).u64(System.nanoTime() / 1000);
                        if (first) {
                            w.bytes(new byte[] {0, 0, 0, 1, 0x67, 0x64, 0, 0x28, (byte) 0xAC, 0x2B, 0x40});
                            w.bytes(new byte[] {0, 0, 0, 1, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80});
                            w.bytes(new byte[] {0, 0, 0, 1, 0x65});
                        } else {
                            w.bytes(new byte[] {0, 0, 0, 1, 0x41});
                        }
                        w.bytes(Crypto.random(first ? 20000 : 1500));
                        c.send(w.toByteArray());
                        first = false;
                        Thread.sleep(33);
                    }
                } catch (Exception e) {
                    streaming = false;
                }
            });
            video.setDaemon(true);
            video.start();
        }
    }
}
