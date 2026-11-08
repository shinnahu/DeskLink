package com.desklink.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny Annex-B helpers: split NAL units, find SPS/PPS. */
public final class H264 {
    private H264() {}

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    /** A NAL unit inside a buffer, including its start code. */
    public static final class Nal {
        public final int start;      // offset of the start code
        public final int payload;    // offset of the NAL header byte
        public final int end;        // exclusive
        public final int type;

        Nal(int start, int payload, int end, int type) {
            this.start = start;
            this.payload = payload;
            this.end = end;
            this.type = type;
        }
    }

    public static List<Nal> split(byte[] b, int off, int len) {
        List<Nal> out = new ArrayList<>();
        int end = off + len;
        int i = off;
        int curStart = -1, curPayload = -1;
        while (i + 3 <= end) {
            int sc = 0;
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) sc = 3;
                else if (i + 4 <= end && b[i + 2] == 0 && b[i + 3] == 1) sc = 4;
            }
            if (sc > 0) {
                if (curPayload >= 0) out.add(new Nal(curStart, curPayload, i, b[curPayload] & 0x1F));
                curStart = i;
                curPayload = i + sc;
                i += sc;
            } else {
                i++;
            }
        }
        if (curPayload >= 0 && curPayload < end) out.add(new Nal(curStart, curPayload, end, b[curPayload] & 0x1F));
        return out;
    }

    /** Returns {sps, pps} each with a 4-byte start code, or null if either is missing. */
    public static byte[][] findParameterSets(byte[] b, int off, int len) {
        byte[] sps = null, pps = null;
        for (Nal n : split(b, off, len)) {
            if (n.type == NAL_SPS && sps == null) sps = withStartCode(b, n);
            else if (n.type == NAL_PPS && pps == null) pps = withStartCode(b, n);
        }
        return sps != null && pps != null ? new byte[][] {sps, pps} : null;
    }

    private static byte[] withStartCode(byte[] b, Nal n) {
        int plen = n.end - n.payload;
        // trim trailing zero bytes (part of a following 4-byte start code)
        while (plen > 1 && b[n.payload + plen - 1] == 0) plen--;
        byte[] out = new byte[4 + plen];
        out[3] = 1;
        System.arraycopy(b, n.payload, out, 4, plen);
        return out;
    }

    public static boolean containsIdr(byte[] b, int off, int len) {
        for (Nal n : split(b, off, len)) if (n.type == NAL_IDR) return true;
        return false;
    }

    public static boolean sameBytes(byte[] a, byte[] b) { return Arrays.equals(a, b); }
}
