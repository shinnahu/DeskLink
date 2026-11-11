package com.desklink.core;

/** Constants from PROTOCOL.md (v1). */
public final class Protocol {
    private Protocol() {}

    public static final int DEFAULT_PORT = 47810;
    public static final int DISCOVERY_PORT = 47811;
    public static final int VERSION = 1;
    public static final int CLIENT_VERSION = 1;

    public static final int CLIENT_HELLO_LEN = 102;
    public static final int SERVER_HELLO_LEN = 231;
    public static final int SERVER_HELLO_SIGNED_LEN = 167;
    public static final int MAX_HANDSHAKE_FRAME = 4096;
    public static final int MAX_RECORD = 16 * 1024 * 1024;

    // ---- client -> host
    public static final int AUTH = 0x01;
    public static final int PAIR_REQUEST = 0x02;
    public static final int PAIR_REVEAL = 0x03;
    public static final int STATUS_QUERY = 0x04;
    public static final int STREAM_START = 0x10;
    public static final int STREAM_PAUSE = 0x11;
    public static final int STREAM_RESUME = 0x12;
    public static final int FRAME_ACK = 0x13;
    public static final int KEYFRAME_REQUEST = 0x14;
    public static final int STREAM_CONFIG = 0x15;
    public static final int SET_OPTIONS = 0x16;
    public static final int SELECT_MONITOR = 0x17;
    public static final int MOUSE_MOVE = 0x20;
    public static final int MOUSE_BUTTON = 0x21;
    public static final int MOUSE_WHEEL = 0x22;
    public static final int KEY = 0x23;
    public static final int TEXT = 0x24;
    public static final int RELEASE_ALL = 0x25;
    public static final int ACTION = 0x26;
    /** A mouse move by dx/dy counts, like a real mouse (game cameras); only with CAP_REL_MOUSE. */
    public static final int MOUSE_REL = 0x27;
    public static final int CLIPBOARD = 0x30;
    public static final int FILE_BEGIN = 0x40;
    public static final int FILE_DATA = 0x41;
    public static final int FILE_END = 0x42;
    public static final int FILE_CANCEL = 0x43;
    public static final int FILE_PROGRESS = 0x44;
    public static final int FILE_DONE = 0x45;
    public static final int FILE_REQUEST = 0x46;
    public static final int FILE_REQUEST_NONE = 0x47;
    public static final int PING = 0x50;
    public static final int PONG = 0x51;
    public static final int NOTICE = 0x52;
    public static final int BYE = 0x5F;

    // ---- host -> client
    public static final int AUTH_RESULT = 0x80;
    public static final int PAIR_CHALLENGE = 0x81;
    public static final int PAIR_RESULT = 0x82;
    public static final int HOST_INFO = 0x83;
    public static final int STATUS_RESULT = 0x84;
    public static final int VIDEO_FORMAT = 0x90;
    public static final int VIDEO_FRAME = 0x91;
    public static final int CURSOR_SHAPE = 0x92;
    public static final int CURSOR_STATE = 0x93;
    public static final int HOST_STATUS = 0x94;
    /** The PC's sound (CAP_AUDIO), while asked for with STREAM_FLAG_AUDIO. */
    public static final int AUDIO_FORMAT = 0x95;
    public static final int AUDIO_DATA = 0x96;

    // ---- status codes
    public static final int ST_OK = 0;
    public static final int ST_UNKNOWN_DEVICE = 1;
    public static final int ST_BAD_TOKEN = 2;
    public static final int ST_BUSY = 3;
    public static final int ST_PAIRING_CLOSED = 4;
    public static final int ST_DENIED = 5;
    public static final int ST_RATE_LIMITED = 6;
    public static final int ST_VERSION = 7;
    public static final int ST_TIMEOUT = 8;

    // ---- HOST_INFO capabilities
    public static final int CAP_CTRL_ALT_DEL = 1;
    public static final int CAP_FILES = 2;
    public static final int CAP_CLIPBOARD = 4;
    public static final int CAP_SERVICE_MODE = 8;
    /** Connections from the internet start with a knock (PROTOCOL.md §2); HOST_INFO ends with the REMOTE_* flags. */
    public static final int CAP_KNOCK = 16;
    /** The PC understands MOUSE_REL. */
    public static final int CAP_REL_MOUSE = 32;
    /** Monitor MONITOR_ALL shows every screen at once. */
    public static final int CAP_ALL_SCREENS = 64;
    /** The PC's sound comes with the stream when asked for (STREAM_FLAG_AUDIO). */
    public static final int CAP_AUDIO = 128;

    // ---- HOST_INFO mobile-data flags (what reaches the PC away from home)
    public static final int REMOTE_IPV4 = 1;          // the router forwards the port
    public static final int REMOTE_IPV6 = 2;          // the router's IPv6 firewall lets connections through
    public static final int REMOTE_IPV6_MAYBE = 4;    // public IPv6, but the router can't say whether it lets it in
    public static final int REMOTE_NO_UPNP = 8;       // the router didn't answer / refused (UPnP)
    public static final int REMOTE_SHARED_IPV4 = 16;  // CGNAT: the internet company shares the address
    public static final int REMOTE_CHECKING = 64;
    public static final int REMOTE_OFF = 128;

    // ---- STATUS_RESULT flags
    public static final int SQ_IN_USE = 1;
    public static final int SQ_SECURE_DESKTOP = 2;
    public static final int SQ_SERVICE_MODE = 4;

    // ---- HOST_STATUS flags
    public static final int HS_SECURE_DESKTOP = 1;
    public static final int HS_NO_SIGNAL = 2;

    // ---- mouse buttons
    public static final int BTN_LEFT = 1;
    public static final int BTN_RIGHT = 2;
    public static final int BTN_MIDDLE = 3;
    public static final int BTN_BACK = 4;
    public static final int BTN_FORWARD = 5;

    // ---- actions
    public static final int ACT_CTRL_ALT_DEL = 1;
    public static final int ACT_LOCK_PC = 2;
    public static final int ACT_TASK_MANAGER = 3;

    // ---- BYE reasons
    public static final int BYE_NORMAL = 0;
    public static final int BYE_STOPPED_ON_PC = 1;
    public static final int BYE_REPLACED = 2;
    public static final int BYE_HOST_EXITING = 3;
    public static final int BYE_PROTOCOL = 4;
    public static final int BYE_TIMEOUT = 5;

    // ---- FILE_DONE status
    public static final int FD_OK = 0;
    public static final int FD_ERROR = 1;
    public static final int FD_CANCELLED = 2;

    // ---- NOTICE levels
    public static final int NOTICE_INFO = 0;
    public static final int NOTICE_WARN = 1;
    public static final int NOTICE_ERROR = 2;

    public static final int FILE_CHUNK = 64 * 1024;          // we send 64 KiB (spec max 256 KiB)
    public static final long FILE_WINDOW = 4L * 1024 * 1024; // sender keeps <= 4 MiB un-acked
    public static final long FILE_PROGRESS_EVERY = 1024 * 1024;
    public static final int MAX_CLIPBOARD_BYTES = 1024 * 1024;

    public static final int STREAM_FLAG_CLIPBOARD = 1;
    /** STREAM_START / SET_OPTIONS: send the PC's sound (only to PCs with CAP_AUDIO). */
    public static final int STREAM_FLAG_AUDIO = 2;
    /** AUDIO_FORMAT codecs: none = the PC has no sound output right now. */
    public static final int AUDIO_CODEC_NONE = 0;
    public static final int AUDIO_CODEC_AAC_LC = 1;
    public static final int MONITOR_PRIMARY = 0xFF;
    /** Every screen at once, as Windows arranges them (only with CAP_ALL_SCREENS). */
    public static final int MONITOR_ALL = 0xFE;

    public static String statusName(int st) {
        switch (st) {
            case ST_OK: return "OK";
            case ST_UNKNOWN_DEVICE: return "UNKNOWN_DEVICE";
            case ST_BAD_TOKEN: return "BAD_TOKEN";
            case ST_BUSY: return "BUSY";
            case ST_PAIRING_CLOSED: return "PAIRING_CLOSED";
            case ST_DENIED: return "DENIED";
            case ST_RATE_LIMITED: return "RATE_LIMITED";
            case ST_VERSION: return "VERSION";
            case ST_TIMEOUT: return "TIMEOUT";
            default: return "STATUS_" + st;
        }
    }

    public static String byeName(int r) {
        switch (r) {
            case BYE_NORMAL: return "normal";
            case BYE_STOPPED_ON_PC: return "stopped on the PC";
            case BYE_REPLACED: return "replaced by a newer connection";
            case BYE_HOST_EXITING: return "host exiting";
            case BYE_PROTOCOL: return "protocol/auth error";
            case BYE_TIMEOUT: return "timeout";
            default: return "reason " + r;
        }
    }
}
