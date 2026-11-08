package com.desklink.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.desklink.core.Vk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Gaming mode's profiles. Each profile has its own name and says what every control of the on-screen gamepad does
 * on the PC: a key, a mouse button, or nothing. Kept in the app's settings (JSON), with which profile is in use and
 * whether Gaming mode is on.
 */
final class Gamepad {
    // the controls (L3 / R3 came later: profiles saved before them read them as NONE)
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, TRIANGLE = 4, CIRCLE = 5, CROSS = 6, SQUARE = 7,
            L1 = 8, L2 = 9, R1 = 10, R2 = 11, SELECT = 12, START = 13, L3 = 14, R3 = 15, CONTROLS = 16;

    // actions: NONE, a Windows virtual key (1..254), or MOUSE + button (1 left, 2 right, 3 middle, 4, 5)
    static final int NONE = 0, MOUSE = 0x100, ACTIONS = MOUSE + 6;

    static boolean isMouse(int action) { return action > MOUSE && action < ACTIONS; }

    static final class Profile {
        final String id;
        String name;
        final int[] bind = new int[CONTROLS];

        Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** The first profile: the D-pad is WASD, □ ○ △ are the mouse buttons, ✕ is Space, Start is Esc; L3 / R3 unset. */
    private static final int[] DEFAULT = new int[CONTROLS];

    static {
        DEFAULT[UP] = 'W';
        DEFAULT[DOWN] = 'S';
        DEFAULT[LEFT] = 'A';
        DEFAULT[RIGHT] = 'D';
        DEFAULT[SQUARE] = MOUSE + 1;
        DEFAULT[CIRCLE] = MOUSE + 2;
        DEFAULT[TRIANGLE] = MOUSE + 3;
        DEFAULT[CROSS] = Vk.SPACE;
        DEFAULT[START] = Vk.ESCAPE;
        DEFAULT[SELECT] = Vk.TAB;
        DEFAULT[L1] = 'Q';
        DEFAULT[R1] = 'E';
        DEFAULT[L2] = Vk.SHIFT;
        DEFAULT[R2] = Vk.CONTROL;
    }

    private final Context context;
    private final SharedPreferences sp;
    private final List<Profile> profiles = new ArrayList<>();
    private String activeId;

    Gamepad(Context c) {
        context = c.getApplicationContext();
        sp = context.getSharedPreferences("gamepad", Context.MODE_PRIVATE);
        load();
        if (profiles.isEmpty()) {
            Profile p = new Profile(newId(), context.getString(R.string.gp_default_profile));
            System.arraycopy(DEFAULT, 0, p.bind, 0, CONTROLS);
            profiles.add(p);
            activeId = p.id;
            save();
        }
        if (find(activeId) == null) activeId = profiles.get(0).id;
    }

    boolean enabled() { return sp.getBoolean("on", false); }

    void setEnabled(boolean on) { sp.edit().putBoolean("on", on).apply(); }

    List<Profile> profiles() { return Collections.unmodifiableList(profiles); }

    Profile active() {
        Profile p = find(activeId);
        return p != null ? p : profiles.get(0);
    }

    void setActive(Profile p) {
        if (!profiles.contains(p)) return;
        activeId = p.id;
        save();
    }

    /** A new profile that starts as a copy of {@code from}'s bindings. */
    Profile create(String name, Profile from) {
        Profile p = new Profile(newId(), name);
        System.arraycopy(from.bind, 0, p.bind, 0, CONTROLS);
        profiles.add(p);
        save();
        return p;
    }

    void rename(Profile p, String name) {
        p.name = name;
        save();
    }

    void bind(Profile p, int control, int action) {
        p.bind[control] = action;
        save();
    }

    /** Deletes a profile; the last one can't be deleted. Returns false if it wasn't. */
    boolean delete(Profile p) {
        if (profiles.size() <= 1 || !profiles.remove(p)) return false;
        if (p.id.equals(activeId)) activeId = profiles.get(0).id;
        save();
        return true;
    }

    private Profile find(String id) {
        if (id == null) return null;
        for (Profile p : profiles) if (p.id.equals(id)) return p;
        return null;
    }

    private static String newId() { return UUID.randomUUID().toString(); }

    private void load() {
        try {
            JSONObject o = new JSONObject(sp.getString("profiles", "{}"));
            activeId = o.optString("active", null);
            JSONArray list = o.optJSONArray("list");
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                Profile p = new Profile(j.getString("id"), j.getString("name"));
                JSONArray b = j.getJSONArray("bind");
                for (int k = 0; k < CONTROLS && k < b.length(); k++) {
                    int a = b.getInt(k);
                    p.bind[k] = a >= 0 && a < ACTIONS ? a : NONE;
                }
                profiles.add(p);
            }
        } catch (JSONException e) {
            profiles.clear(); // unreadable: start again from the default profile
        }
    }

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (Profile p : profiles) {
                JSONArray b = new JSONArray();
                for (int a : p.bind) b.put(a);
                list.put(new JSONObject().put("id", p.id).put("name", p.name).put("bind", b));
            }
            sp.edit().putString("profiles", new JSONObject().put("active", activeId).put("list", list).toString()).apply();
        } catch (JSONException ignored) {
            // only strings and numbers: can't happen
        }
    }

    // ------------------------------------------------------------------ names

    private static final int[] CONTROL_NAMES = {R.string.gp_up, R.string.gp_down, R.string.gp_left, R.string.gp_right,
            R.string.gp_triangle, R.string.gp_circle, R.string.gp_cross, R.string.gp_square, 0, 0, 0, 0,
            R.string.gp_select, R.string.gp_start, 0, 0};

    static String controlName(Context c, int control) {
        switch (control) {
            case L1: return "L1";
            case L2: return "L2";
            case R1: return "R1";
            case R2: return "R2";
            case L3: return "L3";
            case R3: return "R3";
            default: return c.getString(CONTROL_NAMES[control]);
        }
    }

    /** Everything a control can be set to, in the order the picker lists it. */
    static int[] choices() {
        List<Integer> a = new ArrayList<>();
        a.add(NONE);
        for (int b = 1; b <= 5; b++) a.add(MOUSE + b);
        int[] named = {Vk.SPACE, Vk.RETURN, Vk.ESCAPE, Vk.TAB, Vk.BACK, Vk.SHIFT, Vk.CONTROL, Vk.MENU,
                Vk.UP, Vk.DOWN, Vk.LEFT, Vk.RIGHT};
        for (int k : named) a.add(k);
        for (int k = 'A'; k <= 'Z'; k++) a.add(k);
        for (int k = '0'; k <= '9'; k++) a.add(k);
        for (int k = 0; k < 12; k++) a.add(Vk.F1 + k);
        int[] more = {Vk.INSERT, Vk.DELETE, Vk.HOME, Vk.END, Vk.PRIOR, Vk.NEXT, Vk.CAPITAL};
        for (int k : more) a.add(k);
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i);
        return out;
    }

    static String actionName(Context c, int a) {
        if (a == NONE) return c.getString(R.string.gp_none);
        if (isMouse(a)) {
            switch (a - MOUSE) {
                case 1: return c.getString(R.string.gp_mouse_left);
                case 2: return c.getString(R.string.gp_mouse_right);
                case 3: return c.getString(R.string.gp_mouse_middle);
                case 4: return c.getString(R.string.gp_mouse_4);
                default: return c.getString(R.string.gp_mouse_5);
            }
        }
        if ((a >= 'A' && a <= 'Z') || (a >= '0' && a <= '9')) return String.valueOf((char) a);
        if (a >= Vk.F1 && a < Vk.F1 + 24) return "F" + (a - Vk.F1 + 1);
        switch (a) {
            case Vk.SPACE: return c.getString(R.string.gp_key_space);
            case Vk.RETURN: return "Enter";
            case Vk.ESCAPE: return "Esc";
            case Vk.TAB: return "Tab";
            case Vk.BACK: return c.getString(R.string.gp_key_backspace);
            case Vk.SHIFT: return "Shift";
            case Vk.CONTROL: return "Ctrl";
            case Vk.MENU: return "Alt";
            case Vk.UP: return "↑";
            case Vk.DOWN: return "↓";
            case Vk.LEFT: return "←";
            case Vk.RIGHT: return "→";
            case Vk.INSERT: return "Insert";
            case Vk.DELETE: return c.getString(R.string.key_del);
            case Vk.HOME: return c.getString(R.string.key_home);
            case Vk.END: return c.getString(R.string.key_end);
            case Vk.PRIOR: return c.getString(R.string.gp_key_pgup);
            case Vk.NEXT: return c.getString(R.string.gp_key_pgdn);
            case Vk.CAPITAL: return c.getString(R.string.gp_key_caps);
            default: return String.format(Locale.ROOT, "VK 0x%02X", a);
        }
    }
}
