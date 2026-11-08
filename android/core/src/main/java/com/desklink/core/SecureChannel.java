package com.desklink.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client side of the DeskLink secure channel (PROTOCOL.md §2–3).
 * One thread may call {@link #receive()} while another calls {@link #send(byte[])}.
 */
public final class SecureChannel implements Closeable {
    public static final int HANDSHAKE_TIMEOUT_MS = 8000;
    private static final byte[] MAGIC = "DLNK".getBytes(StandardCharsets.US_ASCII);

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();
    private final Object recvLock = new Object();
    private final Cipher enc;
    private final Cipher dec;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private long sendSeq;
    private long recvSeq;
    private final byte[] transcriptHash;
    private final byte[] hostStaticPub;
    private final String fingerprint;
    private final String address;
    private volatile boolean closed;

    private SecureChannel(Socket socket, DataInputStream in, OutputStream out, byte[] keyC2H, byte[] keyH2C,
                          byte[] th, byte[] hostStaticPub, String fingerprint, String address) throws GeneralSecurityException {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.sendKey = new SecretKeySpec(keyC2H, "AES");
        this.recvKey = new SecretKeySpec(keyH2C, "AES");
        this.enc = Cipher.getInstance("AES/GCM/NoPadding");
        this.dec = Cipher.getInstance("AES/GCM/NoPadding");
        this.transcriptHash = th;
        this.hostStaticPub = hostStaticPub;
        this.fingerprint = fingerprint;
        this.address = address;
    }

    /**
     * Performs the handshake on an already connected socket.
     *
     * @param pinnedFingerprint expected host fingerprint (hex) or null when pairing.
     * @param address           the address string used to reach the host (kept for bookkeeping).
     */
    public static SecureChannel handshake(Socket socket, String pinnedFingerprint, String address) throws IOException {
        boolean ok = false;
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);

            KeyPair eph = Crypto.newP256KeyPair();
            byte[] ephPub = Crypto.encodePoint(eph.getPublic());
            byte[] chBody = new ByteWriter(Protocol.CLIENT_HELLO_LEN)
                    .bytes(MAGIC).u8(Protocol.VERSION).bytes(ephPub).bytes(Crypto.random(32)).toByteArray();
            byte[] frame = new ByteWriter(4 + chBody.length).i32(chBody.length).bytes(chBody).toByteArray();
            out.write(frame);
            out.flush();

            int len = in.readInt();
            if (len != Protocol.SERVER_HELLO_LEN) {
                throw new HandshakeException("Unexpected ServerHello length " + len);
            }
            byte[] sh = new byte[len];
            in.readFully(sh);
            if (!Arrays.equals(Arrays.copyOfRange(sh, 0, 4), MAGIC)) throw new HandshakeException("Not a DeskLink host");
            int ver = sh[4] & 0xFF;
            if (ver != Protocol.VERSION) throw new HandshakeException("Unsupported host protocol version " + ver);
            byte[] hostEph = Arrays.copyOfRange(sh, 5, 70);
            byte[] hostStatic = Arrays.copyOfRange(sh, 102, 167);
            byte[] sig = Arrays.copyOfRange(sh, 167, 231);

            PublicKey hostStaticKey = Crypto.decodePoint(hostStatic);
            byte[] signed = new byte[chBody.length + Protocol.SERVER_HELLO_SIGNED_LEN];
            System.arraycopy(chBody, 0, signed, 0, chBody.length);
            System.arraycopy(sh, 0, signed, chBody.length, Protocol.SERVER_HELLO_SIGNED_LEN);
            if (!Crypto.verifyP256(hostStaticKey, signed, sig)) throw new HandshakeException("Host signature is invalid");

            String fp = Hex.hex(Crypto.sha256(hostStatic));
            if (pinnedFingerprint != null && !pinnedFingerprint.isEmpty() && !pinnedFingerprint.equalsIgnoreCase(fp)) {
                throw new IdentityMismatchException(pinnedFingerprint, fp);
            }

            byte[] z = Crypto.ecdh(eph.getPrivate(), Crypto.decodePoint(hostEph));
            byte[] th = Crypto.sha256(chBody, sh);
            byte[][] keys = Crypto.deriveSessionKeys(th, z);
            SecureChannel ch = new SecureChannel(socket, in, out, keys[0], keys[1], th, hostStatic, fp, address);
            ok = true;
            return ch;
        } catch (GeneralSecurityException e) {
            throw new HandshakeException("Crypto failure: " + e.getMessage(), e);
        } finally {
            if (!ok) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static byte[] nonce(long seq) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (seq >>> (56 - 8 * i));
        return n;
    }

    /** Encrypts and writes one record. plaintext = u8 type || payload. Thread-safe. */
    public void send(byte[] plaintext) throws IOException {
        synchronized (sendLock) {
            if (closed) throw new SocketException("channel closed");
            int ctLen = plaintext.length + 16;
            if (ctLen > Protocol.MAX_RECORD) throw new IOException("record too large");
            byte[] hdr = new byte[] {(byte) (ctLen >>> 24), (byte) (ctLen >>> 16), (byte) (ctLen >>> 8), (byte) ctLen};
            byte[] rec = new byte[4 + ctLen];
            System.arraycopy(hdr, 0, rec, 0, 4);
            try {
                enc.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, nonce(sendSeq++)));
                enc.updateAAD(hdr);
                int n = enc.doFinal(plaintext, 0, plaintext.length, rec, 4);
                if (n != ctLen) throw new IOException("unexpected ciphertext length");
            } catch (GeneralSecurityException e) {
                throw new IOException("encrypt failed: " + e.getMessage(), e);
            }
            out.write(rec);
            out.flush();
        }
    }

    /** Reads and decrypts one record (blocking). */
    public byte[] receive() throws IOException {
        synchronized (recvLock) {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                throw new EOFException("connection closed by the PC");
            }
            if (len < 17 || len > Protocol.MAX_RECORD) throw new IOException("bad record length " + len);
            byte[] ct = new byte[len];
            in.readFully(ct);
            byte[] hdr = new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
            try {
                dec.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(128, nonce(recvSeq++)));
                dec.updateAAD(hdr);
                return dec.doFinal(ct);
            } catch (AEADBadTagException e) {
                throw new IOException("record authentication failed");
            } catch (GeneralSecurityException e) {
                throw new IOException("decrypt failed: " + e.getMessage(), e);
            }
        }
    }

    public void setReadTimeout(int ms) {
        try { socket.setSoTimeout(ms); } catch (SocketException ignored) {}
    }

    public byte[] transcriptHash() { return transcriptHash; }

    public byte[] hostStaticPub() { return hostStaticPub; }

    public String fingerprint() { return fingerprint; }

    /** The address string (as given to the connector) this channel was opened to. */
    public String address() { return address; }

    public String remoteIp() {
        return socket.getInetAddress() == null ? address : socket.getInetAddress().getHostAddress();
    }

    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
