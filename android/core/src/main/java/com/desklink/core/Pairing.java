package com.desklink.core;

import java.io.IOException;
import java.util.Collections;

import com.desklink.core.Messages.HostInfo;

/** Numeric-comparison pairing (PROTOCOL.md §4). */
public final class Pairing {
    private Pairing() {}

    public interface Listener {
        /** The 6-digit code ("123 456") to show while the PC asks for Allow / Deny. */
        void onCode(String code);
    }

    /**
     * Runs the pairing exchange on a fresh channel (opened with no pinned fingerprint).
     * On success returns a {@link PairedHost} with credentials; throws {@link AuthException} if the PC refuses.
     */
    public static PairedHost pair(SecureChannel ch, int port, String deviceName, String deviceModel, Listener l) throws IOException {
        ch.setReadTimeout(20_000);
        byte[] nonceC = Crypto.random(32);
        ch.send(new ByteWriter(96).u8(Protocol.PAIR_REQUEST).bytes(Crypto.sha256(nonceC))
                .str(deviceName).str(deviceModel).toByteArray());

        byte[] m = HostConnection.expect(ch, Protocol.PAIR_CHALLENGE, Protocol.PAIR_RESULT);
        if ((m[0] & 0xFF) == Protocol.PAIR_RESULT) {
            throw resultError(new ByteReader(m, 1, m.length - 1));
        }
        byte[] nonceH = new ByteReader(m, 1, m.length - 1).bytes(32);
        ch.send(new ByteWriter(33).u8(Protocol.PAIR_REVEAL).bytes(nonceC).toByteArray());

        String code = Crypto.sasCode(ch.transcriptHash(), nonceC, nonceH);
        if (l != null) l.onCode(code);

        ch.setReadTimeout(150_000); // the PC shows Allow/Deny for up to 120 s
        m = HostConnection.expect(ch, Protocol.PAIR_RESULT);
        ByteReader r = new ByteReader(m, 1, m.length - 1);
        int status = r.u8();
        if (status != Protocol.ST_OK) {
            String msg = r.remaining() >= 2 ? r.str() : "";
            throw new AuthException(status, msg);
        }
        PairedHost h = new PairedHost();
        h.deviceId = r.bytes(16);
        h.token = r.bytes(32);
        h.hostId = Hex.hex(r.bytes(16));
        h.hostName = r.str();
        h.fingerprint = ch.fingerprint();
        h.port = port;
        h.addresses.add(ch.address());
        h.lastAddress = ch.address();
        h.pairedAt = System.currentTimeMillis();
        h.lastSeenOnline = h.pairedAt;
        return h;
    }

    private static AuthException resultError(ByteReader r) {
        int status = r.u8();
        String msg = "";
        try {
            if (status == Protocol.ST_OK) { r.skip(16 + 32 + 16); r.str(); }
            if (r.remaining() >= 2) msg = r.str();
        } catch (MalformedMessageException ignored) {
        }
        return new AuthException(status == Protocol.ST_OK ? Protocol.ST_DENIED : status, msg);
    }

    /**
     * After a successful pairing, authenticates on the same channel to learn all PC addresses
     * (HOST_INFO), then says BYE. The returned host has its address list filled in.
     */
    public static PairedHost finishWithHostInfo(SecureChannel ch, PairedHost h, String deviceName) {
        try {
            HostInfo info = HostConnection.authenticate(ch, h, deviceName);
            if (info.hostName != null && !info.hostName.isEmpty()) h.hostName = info.hostName;
            h.mergeAddresses(info.addresses);
            h.knock = info.can(Protocol.CAP_KNOCK); // from the first connection on, knock on internet addresses
            if (info.remoteFlags >= 0) h.remote = info.remoteFlags;
            try {
                ch.send(new ByteWriter(3).u8(Protocol.BYE).u8(Protocol.BYE_NORMAL).str("").toByteArray());
            } catch (IOException ignored) {
            }
        } catch (IOException | RuntimeException ignored) {
            // The pairing itself succeeded; address discovery is best effort.
            h.mergeAddresses(Collections.singletonList(ch.remoteIp()));
        } finally {
            ch.close();
        }
        return h;
    }
}
