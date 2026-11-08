package com.desklink.core;

/** Windows virtual-key codes used by the client, plus a char → VK map (US layout) for shortcuts. */
public final class Vk {
    private Vk() {}

    public static final int BACK = 0x08, TAB = 0x09, RETURN = 0x0D, SHIFT = 0x10, CONTROL = 0x11, MENU = 0x12,
            PAUSE = 0x13, CAPITAL = 0x14, ESCAPE = 0x1B, SPACE = 0x20, PRIOR = 0x21, NEXT = 0x22, END = 0x23,
            HOME = 0x24, LEFT = 0x25, UP = 0x26, RIGHT = 0x27, DOWN = 0x28, SNAPSHOT = 0x2C, INSERT = 0x2D,
            DELETE = 0x2E, LWIN = 0x5B, RWIN = 0x5C, APPS = 0x5D, NUMPAD0 = 0x60, MULTIPLY = 0x6A, ADD = 0x6B,
            SUBTRACT = 0x6D, DECIMAL = 0x6E, DIVIDE = 0x6F, F1 = 0x70, NUMLOCK = 0x90, SCROLL = 0x91,
            LSHIFT = 0xA0, RSHIFT = 0xA1, LCONTROL = 0xA2, RCONTROL = 0xA3, LMENU = 0xA4, RMENU = 0xA5,
            OEM_1 = 0xBA, OEM_PLUS = 0xBB, OEM_COMMA = 0xBC, OEM_MINUS = 0xBD, OEM_PERIOD = 0xBE, OEM_2 = 0xBF,
            OEM_3 = 0xC0, OEM_4 = 0xDB, OEM_5 = 0xDC, OEM_6 = 0xDD, OEM_7 = 0xDE;

    public static final int A = 0x41, C = 0x43, V = 0x56, X = 0x58, Y = 0x59, Z = 0x5A;

    /** VK for a character when it must be sent as a key (e.g. with Ctrl held). 0 if none. */
    public static int fromChar(int c) {
        if (c >= 'a' && c <= 'z') return 0x41 + (c - 'a');
        if (c >= 'A' && c <= 'Z') return 0x41 + (c - 'A');
        if (c >= '0' && c <= '9') return 0x30 + (c - '0');
        switch (c) {
            case ' ': return SPACE;
            case '\n': case '\r': return RETURN;
            case '\t': return TAB;
            case '\b': return BACK;
            case '-': return OEM_MINUS;
            case '=': return OEM_PLUS;
            case '[': return OEM_4;
            case ']': return OEM_6;
            case '\\': return OEM_5;
            case ';': return OEM_1;
            case '\'': return OEM_7;
            case ',': return OEM_COMMA;
            case '.': return OEM_PERIOD;
            case '/': return OEM_2;
            case '`': return OEM_3;
            default: return 0;
        }
    }

    public static boolean isLetterOrDigit(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
