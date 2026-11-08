package com.desklink.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.desklink.core.Json;
import com.desklink.core.PairedHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Persists linked PCs (SharedPreferences, JSON). Thread-safe. */
final class HostStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "hosts";
    private static final String KEY = "list";

    interface Mutator {
        void apply(PairedHost h);
    }

    private final SharedPreferences sp;

    HostStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("unchecked")
    List<PairedHost> all() {
        synchronized (LOCK) {
            List<PairedHost> out = new ArrayList<>();
            String json = sp.getString(KEY, "[]");
            try {
                Object o = Json.parse(json);
                if (o instanceof List) {
                    for (Object e : (List<Object>) o) {
                        if (e instanceof Map) out.add(PairedHost.fromMap((Map<String, Object>) e));
                    }
                }
            } catch (RuntimeException ignored) {
            }
            return out;
        }
    }

    PairedHost get(String hostId) {
        for (PairedHost h : all()) if (h.hostId.equals(hostId)) return h;
        return null;
    }

    private void save(List<PairedHost> list) {
        List<Object> arr = new ArrayList<>();
        for (PairedHost h : list) arr.add(h.toMap());
        sp.edit().putString(KEY, Json.write(arr)).apply();
    }

    /** Adds or replaces (by hostId). A re-paired PC keeps the user's display name. */
    void put(PairedHost h) {
        synchronized (LOCK) {
            List<PairedHost> list = all();
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                PairedHost old = list.get(i);
                if (old.hostId.equals(h.hostId)) {
                    if ((h.displayName == null || h.displayName.isEmpty()) && old.displayName != null) h.displayName = old.displayName;
                    if (h.lastConnected == 0) h.lastConnected = old.lastConnected;
                    h.mergeAddresses(old.addresses);
                    list.set(i, h);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(h);
            save(list);
        }
    }

    void remove(String hostId) {
        synchronized (LOCK) {
            List<PairedHost> list = all();
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).hostId.equals(hostId)) list.remove(i);
            save(list);
        }
    }

    /** Read-modify-write of one host. Returns the updated copy or null. */
    PairedHost update(String hostId, Mutator m) {
        synchronized (LOCK) {
            List<PairedHost> list = all();
            for (PairedHost h : list) {
                if (h.hostId.equals(hostId)) {
                    m.apply(h);
                    save(list);
                    return h;
                }
            }
            return null;
        }
    }
}
