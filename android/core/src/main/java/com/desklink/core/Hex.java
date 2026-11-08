package com.desklink.core;

public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        if (b == null) return "";
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = DIGITS[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[b[i] & 0xF];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        if (s == null) return new byte[0];
        s = s.trim();
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** "AB12-CD34-EF56" style short form of a 64-hex fingerprint (first 6 bytes). */
    public static String shortFingerprint(String fpHex) {
        if (fpHex == null || fpHex.length() < 12) return fpHex == null ? "" : fpHex.toUpperCase();
        String u = fpHex.substring(0, 12).toUpperCase();
        return u.substring(0, 4) + "-" + u.substring(4, 8) + "-" + u.substring(8, 12);
    }
}
