package com.desklink.app;

import com.desklink.core.HostConnection;

import java.util.HashMap;
import java.util.Map;

/**
 * App-wide registry of the live remote-session connection per PC. Other screens (e.g. "Send to PC")
 * reuse it instead of authenticating again — a second AUTH from this phone would make the PC replace
 * (and close) the running session.
 */
final class ActiveSessions {
    private ActiveSessions() {}

    private static final Map<String, HostConnection> MAP = new HashMap<>();

    static synchronized void put(String hostId, HostConnection c) {
        MAP.put(hostId, c);
    }

    static synchronized void remove(String hostId, HostConnection c) {
        if (c != null && MAP.get(hostId) == c) MAP.remove(hostId);
    }

    /** The live session connection for this PC, or null. */
    static synchronized HostConnection get(String hostId) {
        HostConnection c = MAP.get(hostId);
        if (c != null && c.isClosed()) {
            MAP.remove(hostId);
            return null;
        }
        return c;
    }
}
