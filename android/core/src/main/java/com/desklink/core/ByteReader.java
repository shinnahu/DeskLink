package com.desklink.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Big-endian reader over a byte range. Throws {@link MalformedMessageException} on truncation. */
public final class ByteReader {
    private final byte[] b;
    private int p;
    private final int end;

    public ByteReader(byte[] b) { this(b, 0, b.length); }

    public ByteReader(byte[] b, int off, int len) {
        this.b = b;
        this.p = off;
        this.end = off + len;
    }

    private void need(int n) {
        if (n < 0 || end - p < n) throw new MalformedMessageException("message truncated");
    }

    public int u8() { need(1); return b[p++] & 0xFF; }

    public int u16() {
        need(2);
        int v = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        p += 2;
        return v;
    }

    public int i16() { return (short) u16(); }

    public int i32() {
        need(4);
        int v = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
        p += 4;
        return v;
    }

    public long u32() { return i32() & 0xFFFFFFFFL; }

    public long u64() {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[p + i] & 0xFF);
        p += 8;
        return v;
    }

    public byte[] bytes(int n) {
        need(n);
        byte[] out = Arrays.copyOfRange(b, p, p + n);
        p += n;
        return out;
    }

    public String str() {
        int n = u16();
        need(n);
        String s = new String(b, p, n, StandardCharsets.UTF_8);
        p += n;
        return s;
    }

    /** Remaining bytes decoded as UTF-8. */
    public String tailUtf8() {
        String s = new String(b, p, end - p, StandardCharsets.UTF_8);
        p = end;
        return s;
    }

    public byte[] tail() { return bytes(end - p); }

    public int remaining() { return end - p; }

    public int position() { return p; }

    public byte[] array() { return b; }

    public void skip(int n) { need(n); p += n; }
}
