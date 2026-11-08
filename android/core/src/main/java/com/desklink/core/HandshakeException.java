package com.desklink.core;

import java.io.IOException;

/** The peer is not a valid DeskLink host (bad magic/version/signature). */
public class HandshakeException extends IOException {
    public HandshakeException(String msg) { super(msg); }

    public HandshakeException(String msg, Throwable cause) { super(msg, cause); }
}
