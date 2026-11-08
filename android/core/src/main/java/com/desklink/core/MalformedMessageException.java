package com.desklink.core;

/** A received message did not match the expected layout. */
public final class MalformedMessageException extends RuntimeException {
    public MalformedMessageException(String msg) { super(msg); }
}
