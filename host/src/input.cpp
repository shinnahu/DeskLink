#include "input.h"

#include <algorithm>
#include <chrono>

#include "protocol.h"

InputInjector::~InputInjector() { Stop(); }

void InputInjector::Start() {
  if (th_.joinable()) return;
  {
    std::lock_guard<std::mutex> lk(mu_);
    stop_ = false;
  }
  th_ = std::thread([this] { Run(); });
}

void InputInjector::Stop() {
  {
    std::lock_guard<std::mutex> lk(mu_);
    stop_ = true;
  }
  cv_.notify_all();
  if (th_.joinable()) th_.join();
}

void InputInjector::Post(InputEvent&& ev) {
  {
    std::lock_guard<std::mutex> lk(mu_);
    if (ev.type == InputEvent::Move) {
      // coalesce: a newer absolute position supersedes a queued one that has not been injected yet
      if (!q_.empty() && q_.back().type == InputEvent::Move) q_.pop_back();
      recent_.push_back({ev.x, ev.y, NowMs()});
      while (recent_.size() > 64) recent_.pop_front();
    } else if (ev.type == InputEvent::RelMove && !q_.empty() && q_.back().type == InputEvent::RelMove) {
      // relative moves add up: one queued move takes the new one too (nothing is lost, nothing lags behind)
      q_.back().x = std::clamp(q_.back().x + ev.x, -32767, 32767);
      q_.back().y = std::clamp(q_.back().y + ev.y, -32767, 32767);
      return;
    }
    q_.push_back(std::move(ev));
  }
  cv_.notify_one();
}

void InputInjector::SetMonitorRect(const RECT& r) {
  std::lock_guard<std::mutex> lk(mu_);
  mon_ = r;
}

bool InputInjector::IsRecentClientPos(int x, int y) {
  std::lock_guard<std::mutex> lk(mu_);
  uint64_t now = NowMs();
  while (!recent_.empty() && now - recent_.front().t > 600) recent_.pop_front();
  for (auto& r : recent_)
    if (std::abs(r.x - x) <= 2 && std::abs(r.y - y) <= 2) return true;
  return false;
}

void InputInjector::Run() {
  if (g_opt.systemMode) desk_.Sync();
  for (;;) {
    InputEvent ev;
    {
      std::unique_lock<std::mutex> lk(mu_);
      cv_.wait(lk, [&] { return stop_ || !q_.empty(); });
      if (stop_ && q_.empty()) break;
      ev = std::move(q_.front());
      q_.pop_front();
    }
    if (g_opt.systemMode) desk_.Sync();
    switch (ev.type) {
      case InputEvent::Move: DoMove(ev.x, ev.y); break;
      case InputEvent::RelMove: DoRelMove(ev.x, ev.y); break;
      case InputEvent::Button: DoButton(ev.button, ev.down != 0); break;
      case InputEvent::Wheel: DoWheel(ev.dy, ev.dx); break;
      case InputEvent::Key: DoKey(ev.vk, ev.down != 0); break;
      case InputEvent::Text: DoText(ev.text); break;
      case InputEvent::ReleaseAll: DoReleaseAll(); break;
      case InputEvent::Action: DoAction(ev.action); break;
    }
  }
  DoReleaseAll();
}

void InputInjector::Send(INPUT* in, UINT n) {
  for (UINT i = 0; i < n; i++) {
    if (in[i].type == INPUT_MOUSE) in[i].mi.dwExtraInfo = kInjectTag;
    if (in[i].type == INPUT_KEYBOARD) in[i].ki.dwExtraInfo = kInjectTag;
  }
#ifdef DESKLINK_TESTING
  if (g_opt.testNoInject) {  // protocol tests: say what would have been injected, leave the desktop alone
    for (UINT i = 0; i < n; i++) {
      if (in[i].type == INPUT_MOUSE)
        Log("input(test): mouse flags=0x%04lx dx=%ld dy=%ld", in[i].mi.dwFlags, in[i].mi.dx, in[i].mi.dy);
      else if (in[i].type == INPUT_KEYBOARD)
        Log("input(test): key vk=0x%02x flags=0x%04lx", in[i].ki.wVk, in[i].ki.dwFlags);
    }
    return;
  }
#endif
  UINT sent = SendInput(n, in, sizeof(INPUT));
  if (sent != n) {
    static uint64_t lastLog = 0;
    if (NowMs() - lastLog > 5000) {
      lastLog = NowMs();
      Log("input: SendInput injected %u/%u (err %lu) - secure desktop or higher-privilege window?", sent, n,
          GetLastError());
    }
  }
}

void InputInjector::ToAbsolute(int sx, int sy, LONG& dx, LONG& dy) {
  int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
  int vw = std::max(1, GetSystemMetrics(SM_CXVIRTUALSCREEN)), vh = std::max(1, GetSystemMetrics(SM_CYVIRTUALSCREEN));
  // aim at the first quarter of the target pixel so both floor- and round-based conversions land on it
  int64_t ax = (int64_t)(sx - vx) * 4 + 1, ay = (int64_t)(sy - vy) * 4 + 1;
  dx = (LONG)std::clamp<int64_t>(ax * 65536 / (4LL * vw), 0, 65535);
  dy = (LONG)std::clamp<int64_t>(ay * 65536 / (4LL * vh), 0, 65535);
}

void InputInjector::DoMove(int x, int y) {
  RECT m;
  {
    std::lock_guard<std::mutex> lk(mu_);
    m = mon_;
  }
  int mw = std::max(1, (int)(m.right - m.left)), mh = std::max(1, (int)(m.bottom - m.top));
  x = std::clamp(x, 0, mw - 1);
  y = std::clamp(y, 0, mh - 1);
  INPUT in{};
  in.type = INPUT_MOUSE;
  ToAbsolute(m.left + x, m.top + y, in.mi.dx, in.mi.dy);
  in.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
  Send(&in, 1);
}

// Moves the mouse by dx/dy counts, like a real mouse: games that read the mouse directly (raw input, e.g. a 3D
// camera) turn by it; where the pointer shows, Windows moves it by the same amount (with its own pointer speed).
void InputInjector::DoRelMove(int dx, int dy) {
  if (dx == 0 && dy == 0) return;
  INPUT in{};
  in.type = INPUT_MOUSE;
  in.mi.dx = dx;
  in.mi.dy = dy;
  in.mi.dwFlags = MOUSEEVENTF_MOVE;
  Send(&in, 1);
}

void InputInjector::DoButton(int b, bool down) {
  INPUT in{};
  in.type = INPUT_MOUSE;
  switch (b) {
    case 1: in.mi.dwFlags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP; break;
    case 2: in.mi.dwFlags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP; break;
    case 3: in.mi.dwFlags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP; break;
    case 4:
    case 5:
      in.mi.dwFlags = down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
      in.mi.mouseData = b == 4 ? XBUTTON1 : XBUTTON2;
      break;
    default: return;
  }
  if (down) buttonsDown_.insert(b);
  else buttonsDown_.erase(b);
  Send(&in, 1);
}

void InputInjector::DoWheel(int dy, int dx) {
  INPUT in[2]{};
  UINT n = 0;
  if (dy) {
    in[n].type = INPUT_MOUSE;
    in[n].mi.dwFlags = MOUSEEVENTF_WHEEL;
    in[n].mi.mouseData = (DWORD)dy;
    n++;
  }
  if (dx) {
    in[n].type = INPUT_MOUSE;
    in[n].mi.dwFlags = MOUSEEVENTF_HWHEEL;
    in[n].mi.mouseData = (DWORD)dx;
    n++;
  }
  if (n) Send(in, n);
}

static bool IsExtendedVk(uint16_t vk) {
  switch (vk) {
    case VK_INSERT: case VK_DELETE: case VK_HOME: case VK_END: case VK_PRIOR: case VK_NEXT:
    case VK_LEFT: case VK_RIGHT: case VK_UP: case VK_DOWN: case VK_NUMLOCK: case VK_SNAPSHOT:
    case VK_DIVIDE: case VK_RCONTROL: case VK_RMENU: case VK_LWIN: case VK_RWIN: case VK_APPS:
    case VK_CANCEL:
      return true;
    default:
      return false;
  }
}

static INPUT MakeKey(uint16_t vk, bool down) {
  INPUT in{};
  in.type = INPUT_KEYBOARD;
  in.ki.wVk = vk;
  UINT sc = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC_EX);
  in.ki.wScan = (WORD)(sc & 0xFF);
  in.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
  if ((sc & 0xFF00) == 0xE000 || IsExtendedVk(vk)) in.ki.dwFlags |= KEYEVENTF_EXTENDEDKEY;
  return in;
}

void InputInjector::DoKey(uint16_t vk, bool down) {
  if (vk == 0 || vk > 0xFE) return;
  INPUT in = MakeKey(vk, down);
  if (down) keysDown_.insert(vk);
  else keysDown_.erase(vk);
  Send(&in, 1);
}

void InputInjector::DoText(const std::wstring& s) {
  std::vector<INPUT> v;
  v.reserve(s.size() * 2);
  auto vkPress = [&](uint16_t vk) {
    v.push_back(MakeKey(vk, true));
    v.push_back(MakeKey(vk, false));
  };
  for (size_t i = 0; i < s.size(); i++) {
    wchar_t c = s[i];
    if (c == L'\r') {
      if (i + 1 < s.size() && s[i + 1] == L'\n') continue;
      vkPress(VK_RETURN);
    } else if (c == L'\n') {
      vkPress(VK_RETURN);
    } else if (c == L'\t') {
      vkPress(VK_TAB);
    } else if (c == L'\b') {
      vkPress(VK_BACK);
    } else if (c >= 0x20 || c == 0) {
      if (c == 0) continue;
      INPUT d{};
      d.type = INPUT_KEYBOARD;
      d.ki.wScan = c;
      d.ki.dwFlags = KEYEVENTF_UNICODE;
      v.push_back(d);
      d.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
      v.push_back(d);
    }
  }
  for (size_t off = 0; off < v.size(); off += 64) {
    UINT n = (UINT)std::min<size_t>(64, v.size() - off);
    Send(v.data() + off, n);
  }
}

void InputInjector::DoReleaseAll() {
  std::vector<INPUT> v;
  for (uint16_t vk : keysDown_) v.push_back(MakeKey(vk, false));
  keysDown_.clear();
  for (int b : std::set<int>(buttonsDown_)) {
    INPUT in{};
    in.type = INPUT_MOUSE;
    if (b == 1) in.mi.dwFlags = MOUSEEVENTF_LEFTUP;
    else if (b == 2) in.mi.dwFlags = MOUSEEVENTF_RIGHTUP;
    else if (b == 3) in.mi.dwFlags = MOUSEEVENTF_MIDDLEUP;
    else {
      in.mi.dwFlags = MOUSEEVENTF_XUP;
      in.mi.mouseData = b == 4 ? XBUTTON1 : XBUTTON2;
    }
    v.push_back(in);
  }
  buttonsDown_.clear();
  if (!v.empty()) Send(v.data(), (UINT)v.size());
}

typedef VOID(WINAPI* SendSASFn)(BOOL);

void InputInjector::DoAction(uint8_t a) {
  switch (a) {
    case action::CTRL_ALT_DEL: {
      HMODULE m = LoadLibraryW(L"sas.dll");
      auto fn = m ? (SendSASFn)GetProcAddress(m, "SendSAS") : nullptr;
      if (fn && g_opt.systemMode) {
        fn(FALSE);
        Log("input: SendSAS");
      } else if (notice) {
        notice("Ctrl+Alt+Del needs DeskLink to be installed as a service on the PC.");
      }
      break;
    }
    case action::LOCK_PC:
      if (!LockWorkStation()) {
        // e.g. running as SYSTEM: fall back to Win+L
        INPUT in[4] = {MakeKey(VK_LWIN, true), MakeKey('L', true), MakeKey('L', false), MakeKey(VK_LWIN, false)};
        Send(in, 4);
      }
      break;
    case action::TASK_MANAGER: {
      INPUT in[6] = {MakeKey(VK_CONTROL, true), MakeKey(VK_SHIFT, true),   MakeKey(VK_ESCAPE, true),
                     MakeKey(VK_ESCAPE, false), MakeKey(VK_SHIFT, false), MakeKey(VK_CONTROL, false)};
      Send(in, 6);
      break;
    }
  }
}
