package com.desklink.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** P-256 / SHA-256 / HMAC / HKDF helpers (JCA only, works on Android API 26+ and desktop JDKs). */
public final class Crypto {
    private Crypto() {}

    private static final SecureRandom RNG = new SecureRandom();

    /** DER SubjectPublicKeyInfo prefix for an uncompressed P-256 point (followed by the 65 point bytes). */
    private static final byte[] P256_SPKI_PREFIX = Hex.unhex("3059301306072a8648ce3d020106082a8648ce3d030107034200");

    public static final byte[] KEYS_INFO = "desklink-v1-keys".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SAS_LABEL = "desklink-v1-sas".getBytes(StandardCharsets.US_ASCII);

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair newP256KeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uncompressed point 0x04 || X || Y (65 bytes). */
    public static byte[] encodePoint(PublicKey pub) {
        ECPoint w = ((ECPublicKey) pub).getW();
        byte[] out = new byte[65];
        out[0] = 4;
        put32(w.getAffineX(), out, 1);
        put32(w.getAffineY(), out, 33);
        return out;
    }

    public static PublicKey decodePoint(byte[] p65) throws GeneralSecurityException {
        if (p65 == null || p65.length != 65 || p65[0] != 4) throw new GeneralSecurityException("bad P-256 point");
        byte[] spki = new byte[P256_SPKI_PREFIX.length + 65];
        System.arraycopy(P256_SPKI_PREFIX, 0, spki, 0, P256_SPKI_PREFIX.length);
        System.arraycopy(p65, 0, spki, P256_SPKI_PREFIX.length, 65);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
    }

    /** ECDH shared secret = 32-byte big-endian X coordinate. */
    public static byte[] ecdh(PrivateKey priv, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(peer, true);
        return leftPad(ka.generateSecret(), 32);
    }

    /** Verifies a raw r||s (64 bytes) ECDSA-P256-SHA256 signature over msg. */
    public static boolean verifyP256(PublicKey pub, byte[] msg, byte[] rawSig) throws GeneralSecurityException {
        if (rawSig == null || rawSig.length != 64) return false;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(pub);
        s.update(msg);
        return s.verify(rawToDer(rawSig));
    }

    /** Signs msg and returns raw r||s (used by the desktop test host). */
    public static byte[] signP256Raw(PrivateKey priv, byte[] msg) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(priv);
        s.update(msg);
        return derToRaw(s.sign());
    }

    /** HKDF-SHA256(salt=TH, ikm=Z, info="desklink-v1-keys", L=64) → {keyC2H, keyH2C}. */
    public static byte[][] deriveSessionKeys(byte[] transcriptHash, byte[] z) {
        byte[] prk = hmac(transcriptHash, z);
        byte[] t1 = hmac(prk, KEYS_INFO, new byte[] {1});
        byte[] t2 = hmac(prk, t1, KEYS_INFO, new byte[] {2});
        return new byte[][] {t1, t2};
    }

    /** 6-digit comparison code, formatted "123 456". */
    public static String sasCode(byte[] transcriptHash, byte[] nonceC, byte[] nonceH) {
        byte[] d = sha256(SAS_LABEL, transcriptHash, nonceC, nonceH);
        long v = ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
        String s = String.format(java.util.Locale.US, "%06d", v % 1_000_000L);
        return s.substring(0, 3) + " " + s.substring(3);
    }

    // ------------------------------------------------------------------ helpers

    static byte[] leftPad(byte[] b, int n) {
        if (b.length == n) return b;
        byte[] out = new byte[n];
        if (b.length > n) System.arraycopy(b, b.length - n, out, 0, n);
        else System.arraycopy(b, 0, out, n - b.length, b.length);
        return out;
    }

    private static void put32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        byte[] p = leftPad(b, 32);
        System.arraycopy(p, 0, out, off, 32);
    }

    public static byte[] rawToDer(byte[] rs) {
        byte[] r = derInt(rs, 0);
        byte[] s = derInt(rs, 32);
        ByteArrayOutputStream o = new ByteArrayOutputStream(72);
        o.write(0x30);
        o.write(2 + r.length + 2 + s.length);
        o.write(0x02);
        o.write(r.length);
        o.write(r, 0, r.length);
        o.write(0x02);
        o.write(s.length);
        o.write(s, 0, s.length);
        return o.toByteArray();
    }

    private static byte[] derInt(byte[] rs, int off) {
        int i = off;
        int end = off + 32;
        while (i < end - 1 && rs[i] == 0) i++;
        boolean pad = (rs[i] & 0x80) != 0;
        byte[] out = new byte[(end - i) + (pad ? 1 : 0)];
        System.arraycopy(rs, i, out, pad ? 1 : 0, end - i);
        return out;
    }

    public static byte[] derToRaw(byte[] der) throws GeneralSecurityException {
        try {
            int p = 0;
            if (der[p++] != 0x30) throw new GeneralSecurityException("bad DER");
            int len = der[p++] & 0xFF;
            if (len == 0x81) p++;
            byte[] out = new byte[64];
            for (int k = 0; k < 2; k++) {
                if (der[p++] != 0x02) throw new GeneralSecurityException("bad DER int");
                int n = der[p++] & 0xFF;
                byte[] v = new byte[n];
                System.arraycopy(der, p, v, 0, n);
                p += n;
                byte[] v32 = leftPad(new BigInteger(1, v).toByteArray(), 32);
                System.arraycopy(v32, 0, out, k * 32, 32);
            }
            return out;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new GeneralSecurityException("bad DER", e);
        }
    }
}
