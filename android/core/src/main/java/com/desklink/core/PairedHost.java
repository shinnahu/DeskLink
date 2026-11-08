package com.desklink.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Everything the phone remembers about a linked PC. */
public final class PairedHost {
    public String hostId = "";          // 32 hex
    public String hostName = "";        // name reported by the PC
    public String displayName = "";     // user rename ("" = use hostName)
    public String fingerprint = "";     // 64 hex, pinned
    public byte[] deviceId = new byte[16];
    public byte[] token = new byte[32];
    public int port = Protocol.DEFAULT_PORT;
    public List<String> addresses = new ArrayList<>();
    public String lastAddress = "";
    public long lastSeenOnline;         // epoch ms, 0 = never
    public long lastConnected;          // epoch ms of the last remote session
    public long pairedAt;
    public boolean knock;               // the PC wants a knock on connections from the internet (CAP_KNOCK)
    public int remote = -1;             // Protocol.REMOTE_* as the PC last reported (-1 = unknown)

    /** Key for the knock on internet addresses: SHA-256 of the token (the PC keeps only that hash). Null if unused. */
    public byte[] knockKey() {
        if (!knock || token == null || token.length != 32) return null;
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(token);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    public String name() {
        return displayName != null && !displayName.isEmpty() ? displayName : (hostName == null || hostName.isEmpty() ? "PC" : hostName);
    }

    /** Ordered, de-duplicated connection candidates: last good address first. */
    public List<String> candidates() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (lastAddress != null && !lastAddress.isEmpty()) s.add(lastAddress);
        for (String a : addresses) if (a != null && !a.trim().isEmpty()) s.add(a.trim());
        return new ArrayList<>(s);
    }

    /** Adds new addresses (keeps existing order, appends unknown ones). */
    public boolean mergeAddresses(List<String> more) {
        boolean changed = false;
        for (String a : more) {
            if (a == null) continue;
            a = a.trim();
            if (a.isEmpty() || addresses.contains(a)) continue;
            addresses.add(a);
            changed = true;
        }
        return changed;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hostId", hostId);
        m.put("hostName", hostName);
        m.put("displayName", displayName);
        m.put("fp", fingerprint);
        m.put("deviceId", Hex.hex(deviceId));
        m.put("token", Hex.hex(token));
        m.put("port", (long) port);
        m.put("addresses", new ArrayList<Object>(addresses));
        m.put("lastAddress", lastAddress);
        m.put("lastSeen", lastSeenOnline);
        m.put("lastConnected", lastConnected);
        m.put("pairedAt", pairedAt);
        m.put("knock", knock ? 1L : 0L);
        m.put("remote", (long) remote);
        return m;
    }

    public String toJson() { return Json.write(toMap()); }

    public static PairedHost fromMap(Map<String, Object> m) {
        PairedHost h = new PairedHost();
        h.hostId = Json.getString(m, "hostId", "");
        h.hostName = Json.getString(m, "hostName", "");
        h.displayName = Json.getString(m, "displayName", "");
        h.fingerprint = Json.getString(m, "fp", "");
        h.deviceId = Hex.unhex(Json.getString(m, "deviceId", ""));
        h.token = Hex.unhex(Json.getString(m, "token", ""));
        h.port = (int) Json.getLong(m, "port", Protocol.DEFAULT_PORT);
        for (Object o : Json.getList(m, "addresses")) if (o != null) h.addresses.add(o.toString());
        h.lastAddress = Json.getString(m, "lastAddress", "");
        h.lastSeenOnline = Json.getLong(m, "lastSeen", 0);
        h.lastConnected = Json.getLong(m, "lastConnected", 0);
        h.pairedAt = Json.getLong(m, "pairedAt", 0);
        h.knock = Json.getLong(m, "knock", 0) != 0;
        h.remote = (int) Json.getLong(m, "remote", -1);
        return h;
    }

    public static PairedHost fromJson(String json) { return fromMap(Json.parseObject(json)); }

    public PairedHost copy() { return fromJson(toJson()); }
}
