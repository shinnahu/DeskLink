package com.desklink.core;

import java.io.IOException;

/** The host answered AUTH / PAIR / STATUS with a non-OK status. */
public final class AuthException extends IOException {
    public final int status;
    public final String hostMessage;

    public AuthException(int status, String hostMessage) {
        super(Protocol.statusName(status) + (hostMessage == null || hostMessage.isEmpty() ? "" : ": " + hostMessage));
        this.status = status;
        this.hostMessage = hostMessage == null ? "" : hostMessage;
    }
}
