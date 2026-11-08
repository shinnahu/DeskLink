package com.desklink.core;

/** The host's identity key does not match the pinned fingerprint ("PC identity changed"). */
public final class IdentityMismatchException extends HandshakeException {
    public final String expected;
    public final String actual;

    public IdentityMismatchException(String expected, String actual) {
        super("PC identity changed (expected " + Hex.shortFingerprint(expected) + ", got " + Hex.shortFingerprint(actual) + ")");
        this.expected = expected;
        this.actual = actual;
    }
}
