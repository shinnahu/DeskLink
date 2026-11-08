package com.desklink.core;

import java.io.IOException;

import com.desklink.core.Messages.StatusResult;

/** STATUS_QUERY → STATUS_RESULT probe used by the PC list (never starts a session). */
public final class StatusProbe {
    private StatusProbe() {}

    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /**
     * Connects (verifying the pinned identity), asks for status and closes.
     * UNKNOWN_DEVICE / BAD_TOKEN are returned in {@link StatusResult#status}; network failures throw.
     */
    public static StatusResult probe(PairedHost h, int timeoutMs) throws IOException {
        SecureChannel ch = Connector.open(h.candidates(), h.port, h.fingerprint, timeoutMs, 120, h.knockKey());
        try {
            ch.setReadTimeout(timeoutMs);
            ch.send(new ByteWriter(49).u8(Protocol.STATUS_QUERY)
                    .bytes(HostConnection.fixed(h.deviceId, 16)).bytes(HostConnection.fixed(h.token, 32)).toByteArray());
            byte[] m = HostConnection.expect(ch, Protocol.STATUS_RESULT, Protocol.AUTH_RESULT);
            ByteReader r = new ByteReader(m, 1, m.length - 1);
            StatusResult s;
            if ((m[0] & 0xFF) == Protocol.AUTH_RESULT) {
                // Older host without STATUS_QUERY support: treat as online with unknown details.
                s = new StatusResult();
                s.status = r.u8();
            } else {
                s = StatusResult.parse(r);
            }
            s.address = ch.address();
            return s;
        } finally {
            ch.close();
        }
    }
}
