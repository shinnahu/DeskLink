package com.desklink.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable big-endian byte buffer. */
public final class ByteWriter {
    private byte[] buf;
    private int len;

    public ByteWriter() { this(64); }

    public ByteWriter(int capacity) { buf = new byte[Math.max(16, capacity)]; }

    private void ensure(int n) {
        if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
    }

    public ByteWriter u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter u16(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter i16(int v) { return u16(v & 0xFFFF); }

    public ByteWriter i32(int v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter u32(long v) { return i32((int) v); }

    public ByteWriter u64(long v) {
        ensure(8);
        for (int s = 56; s >= 0; s -= 8) buf[len++] = (byte) (v >>> s);
        return this;
    }

    public ByteWriter bytes(byte[] b) { return bytes(b, 0, b.length); }

    public ByteWriter bytes(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
        return this;
    }

    /** {@code u16 length + UTF-8}, truncated on a code-point boundary to 65535 bytes. */
    public ByteWriter str(String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        int n = b.length;
        if (n > 0xFFFF) {
            n = 0xFFFF;
            while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        }
        u16(n);
        return bytes(b, 0, n);
    }

    /** Raw UTF-8 bytes without a length prefix (for {@code tail} fields). */
    public ByteWriter utf8Tail(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public int length() { return len; }

    public byte[] toByteArray() { return Arrays.copyOf(buf, len); }

    /** Convenience: a message consisting of only the type byte. */
    public static byte[] msg(int type) { return new byte[] {(byte) type}; }
}
