#pragma once
#include <future>
#include <set>

#include "common.h"
#include "desktop.h"

// Every injected event carries this value in dwExtraInfo (lets self-tests recognise them).
constexpr ULONG_PTR kInjectTag = 0x0D35C11Bu;

struct InputEvent {
  enum Type : uint8_t { Move, Button, Wheel, Key, Text, ReleaseAll, Action, RelMove, Paste } type = Move;
  int32_t x = 0, y = 0;  // Move: monitor-relative pixels; RelMove: mouse counts (dx, dy)
  uint8_t button = 0, down = 0;
  int16_t dy = 0, dx = 0;
  uint16_t vk = 0;
  uint8_t action = 0;
  std::wstring text;
  // Paste: true once the text is on the clipboard, which is then pasted with Ctrl+V; not valid, false, or not ready
  // within a second: the text is typed instead
  std::shared_future<bool> onClipboard;
};

// Injects mouse/keyboard input on a dedicated thread that follows the input desktop.
// Coordinates are relative to the streamed monitor (see SetMonitorRect).
class InputInjector {
 public:
  InputInjector() = default;
  ~InputInjector();
  void Start();
  void Stop();
  void Post(InputEvent&& ev);
  void SetMonitorRect(const RECT& r);
  bool IsRecentClientPos(int x, int y);  // monitor-relative
  // Optional callback for ACTION messages that need host-level handling (e.g. notices)
  std::function<void(const std::string&)> notice;
#ifdef DESKLINK_TESTING
  // With --no-inject: given each event that would have been injected, instead of its log line (self-tests)
  std::function<void(const INPUT&)> testSent;
#endif

  // Pure helper used by the self-test: normalized absolute coordinates for a virtual-desktop pixel.
  static void ToAbsolute(int sx, int sy, LONG& dx, LONG& dy);

 private:
  void Run();
  void DoMove(int x, int y);
  void DoRelMove(int dx, int dy);
  void DoButton(int b, bool down);
  void DoWheel(int dy, int dx);
  void DoKey(uint16_t vk, bool down);
  void DoText(const std::wstring& s, bool pasted = false);
  void DoPaste(const InputEvent& ev);
  void DoReleaseAll();
  void DoAction(uint8_t a);
  void Send(INPUT* in, UINT n);

  std::thread th_;
  std::mutex mu_;
  std::condition_variable cv_;
  std::deque<InputEvent> q_;
  bool stop_ = false;
  RECT mon_{0, 0, 1920, 1080};
  struct Recent {
    int x, y;
    uint64_t t;
  };
  std::deque<Recent> recent_;
  // input thread only
  std::set<uint16_t> keysDown_;
  std::set<int> buttonsDown_;
  DesktopFollower desk_;
};
