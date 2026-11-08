#pragma once
#include <cstddef>
#include <cstdint>

// See PROTOCOL.md — this file must stay in sync with it.
constexpr uint16_t kDefaultPort = 47810;
constexpr uint16_t kDiscoveryPort = 47811;
constexpr uint8_t kProtocolVersion = 1;

namespace msg {
enum : uint8_t {
  AUTH = 0x01,
  PAIR_REQUEST = 0x02,
  PAIR_REVEAL = 0x03,
  STATUS_QUERY = 0x04,

  STREAM_START = 0x10,
  STREAM_PAUSE = 0x11,
  STREAM_RESUME = 0x12,
  FRAME_ACK = 0x13,
  KEYFRAME_REQUEST = 0x14,
  STREAM_CONFIG = 0x15,
  SET_OPTIONS = 0x16,
  SELECT_MONITOR = 0x17,

  MOUSE_MOVE = 0x20,
  MOUSE_BUTTON = 0x21,
  MOUSE_WHEEL = 0x22,
  KEY = 0x23,
  TEXT = 0x24,
  RELEASE_ALL = 0x25,
  ACTION = 0x26,
  MOUSE_REL = 0x27,  // caps::REL_MOUSE: a mouse move by dx/dy counts, like a real mouse (game cameras)

  CLIPBOARD = 0x30,

  FILE_START = 0x40,   // "FILE_BEGIN" in PROTOCOL.md (name clashes with a Windows macro)
  FILE_DATA = 0x41,
  FILE_FINISH = 0x42,  // "FILE_END" in PROTOCOL.md
  FILE_CANCEL = 0x43,
  FILE_PROGRESS = 0x44,
  FILE_DONE = 0x45,
  FILE_REQUEST = 0x46,
  FILE_REQUEST_NONE = 0x47,

  PING = 0x50,
  PONG = 0x51,
  NOTICE = 0x52,
  BYE = 0x5F,

  AUTH_RESULT = 0x80,
  PAIR_CHALLENGE = 0x81,
  PAIR_RESULT = 0x82,
  HOST_INFO = 0x83,
  STATUS_RESULT = 0x84,

  VIDEO_FORMAT = 0x90,
  VIDEO_FRAME = 0x91,
  CURSOR_SHAPE = 0x92,
  CURSOR_STATE = 0x93,
  HOST_STATUS = 0x94,
};
}  // namespace msg

namespace st {
enum : uint8_t { OK = 0, UNKNOWN_DEVICE = 1, BAD_TOKEN = 2, BUSY = 3, PAIRING_CLOSED = 4, DENIED = 5, RATE_LIMITED = 6, VERSION = 7, TIMEOUT = 8 };
}

namespace bye {
enum : uint8_t { NORMAL = 0, STOPPED_ON_PC = 1, REPLACED = 2, HOST_EXIT = 3, PROTOCOL = 4, TIMEOUT = 5 };
}

namespace caps {
// KNOCK: connections from the internet must start with a knock (PROTOCOL.md §2), and HOST_INFO ends with the
// mobile-data flags. REL_MOUSE: MOUSE_REL is understood. ALL_SCREENS: monitor kMonitorAll shows every screen at once.
enum : uint32_t { CTRL_ALT_DEL = 1, FILES = 2, CLIPBOARD = 4, SERVICE_MODE = 8, KNOCK = 16, REL_MOUSE = 32, ALL_SCREENS = 64 };
}

// STREAM_START / SELECT_MONITOR / VIDEO_FORMAT monitor values besides an index
constexpr uint8_t kMonitorAll = 0xFE;      // every screen at once, as Windows arranges them (caps::ALL_SCREENS)
constexpr uint8_t kMonitorPrimary = 0xFF;  // the main screen

constexpr size_t kKnockSize = 60;  // "DLKN" u64 unixTime nonce[16] mac[32]

namespace hoststatus {
enum : uint8_t { SECURE_DESKTOP = 1, NO_SIGNAL = 2 };
}

namespace action {
enum : uint8_t { CTRL_ALT_DEL = 1, LOCK_PC = 2, TASK_MANAGER = 3 };
}
