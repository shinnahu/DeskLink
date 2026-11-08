// Developer self-tests (not used in normal operation).
//   DeskLinkHost.exe --test-capture <seconds> <out.h264> [maxW maxH fps kbps monitor(index | all)]
#include <cstdio>

#include "capture.h"
#include "crypto.h"
#include "protocol.h"

namespace {

class FileSink : public StreamSink {
 public:
  explicit FileSink(FILE* f) : f_(f) {}
  void SendStream(Bytes&& m) override {
    if (m.empty()) return;
    switch (m[0]) {
      case msg::VIDEO_FORMAT: {
        Reader r(m.data() + 1, m.size() - 1);
        int codec = r.u8(), w = r.u16(), h = r.u16(), mon = r.u8();
        int dl = r.i32(), dt = r.i32(), dw = r.i32(), dh = r.i32();
        Log("selftest: VIDEO_FORMAT codec=%d %dx%d monitor=%d desktop=%d,%d %dx%d", codec, w, h, mon, dl, dt, dw, dh);
        break;
      }
      case msg::VIDEO_FRAME: {
        Reader r(m.data() + 1, m.size() - 1);
        uint32_t id = r.u32();
        uint8_t flags = r.u8();
        r.u64();
        size_t len = 0;
        const uint8_t* p = r.rest(len);
        fwrite(p, 1, len, f_);
        frames++;
        bytes += len;
        if (flags & 1) keys++;
        if (id <= 3 || (flags & 1)) Log("selftest: frame %u %s %zu bytes", id, (flags & 1) ? "KEY" : "", len);
        std::lock_guard<std::mutex> lk(mu);
        pendingAcks.push_back(id);
        break;
      }
      case msg::CURSOR_SHAPE: {
        Reader r(m.data() + 1, m.size() - 1);
        uint32_t id = r.u32();
        int w = r.u16(), h = r.u16(), hx = r.u16(), hy = r.u16();
        Log("selftest: CURSOR_SHAPE id=%08x %dx%d hot=%d,%d", id, w, h, hx, hy);
        break;
      }
      case msg::CURSOR_STATE: {
        Reader r(m.data() + 1, m.size() - 1);
        int x = r.i32(), y = r.i32(), vis = r.u8();
        cursorMsgs++;
        if (cursorMsgs <= 3) Log("selftest: CURSOR_STATE %d,%d visible=%d", x, y, vis);
        break;
      }
      case msg::HOST_STATUS: {
        Reader r(m.data() + 1, m.size() - 1);
        int fl = r.u8();
        std::string t = r.str();
        Log("selftest: HOST_STATUS flags=%d '%s'", fl, t.c_str());
        break;
      }
    }
  }
  bool IsClientCursorPos(int, int) override { return false; }
  void SetMonitorRect(const RECT& r) override { Log("selftest: monitor rect %ld,%ld,%ld,%ld", r.left, r.top, r.right, r.bottom); }

  FILE* f_;
  std::mutex mu;
  std::vector<uint32_t> pendingAcks;
  uint64_t frames = 0, bytes = 0, keys = 0, cursorMsgs = 0;
};

}  // namespace

int RunCaptureSelfTest(int seconds, const std::wstring& out, int maxW, int maxH, int fps, int kbps, int monitor) {
  FILE* f = _wfopen(out.c_str(), L"wb");
  if (!f) {
    Log("selftest: cannot open output");
    return 2;
  }
  FileSink sink(f);
  Streamer s(&sink);
  StreamParams p;
  p.maxW = maxW;
  p.maxH = maxH;
  p.maxFps = fps;
  p.kbps = kbps;
  p.monitor = monitor;
  s.Start(p);
  uint64_t end = NowMs() + (uint64_t)seconds * 1000;
  uint64_t nextKey = NowMs() + 1500;
  while (NowMs() < end) {
    Sleep(5);
    std::vector<uint32_t> acks;
    {
      std::lock_guard<std::mutex> lk(sink.mu);
      acks.swap(sink.pendingAcks);
    }
    for (uint32_t id : acks) s.OnAck(id);
    if (NowMs() >= nextKey) {  // exercise forced keyframes every 1.5 s
      s.RequestKeyframe();
      nextKey += 1500;
    }
  }
  StreamStats st = s.Stats();
  s.Stop();
  fclose(f);
  Log("selftest: done frames=%llu keys=%llu bytes=%llu cursorMsgs=%llu encoder=%s kbps=%d", sink.frames, sink.keys,
      sink.bytes, sink.cursorMsgs, st.encoder.c_str(), st.kbps);
  return sink.frames > 0 ? 0 : 1;
}

// ---------------------------------------------------------------- home or internet
// Which connections count as home (no knock; pairing allowed) and which as the internet. 100.64/10 is home only when
// it came in through this PC's own Tailscale address.
#include "remote.h"

int RunHomeSelfTest() {
  struct Case {
    const char* peer;
    const char* ours;
    bool tailscale;  // this PC has Tailscale at 100.101.102.103
    bool home;
    const char* what;
  } cases[] = {
      {"192.168.0.5", "192.168.0.2", false, true, "phone on the home Wi-Fi"},
      {"::ffff:192.168.0.5", "::ffff:192.168.0.2", false, true, "the same, on a dual-stack socket"},
      {"10.1.2.3", "10.1.2.9", false, true, "another private network range"},
      {"169.254.10.20", "169.254.1.1", false, true, "link-local"},
      {"127.0.0.1", "127.0.0.1", false, true, "this PC"},
      {"fd7a:115c:a1e0::5", "fd7a:115c:a1e0::9", false, true, "Tailscale's IPv6 (unique-local)"},
      {"8.8.8.8", "192.168.0.2", false, false, "a public IPv4 address"},
      {"2001:db8:1::1", "2001:db8:2::2", false, false, "a public IPv6 address"},
      {"100.128.0.1", "192.168.0.2", false, false, "100.128.x (outside 100.64/10)"},
      {"100.70.1.2", "192.168.0.2", false, false, "100.x through the router (internet company's CGNAT)"},
      {"100.70.1.2", "192.168.0.2", true, false, "the same while this PC also has Tailscale"},
      {"100.70.1.2", "100.101.102.103", true, true, "100.x through this PC's Tailscale"},
      {"::ffff:100.70.1.2", "::ffff:100.101.102.103", true, true, "the same, on a dual-stack socket"},
      {"100.70.1.2", "100.101.102.103", false, false, "100.x to a 100.x address that isn't Tailscale's"},
      {"100.70.1.2", "", true, false, "100.x with this PC's end unknown (LAN discovery)"},
  };
  int fails = 0;
  for (auto& c : cases) {
    std::vector<std::string> ts;
    if (c.tailscale) ts.push_back("100.101.102.103");
    bool got = remote::IsHomeConnection(c.peer, c.ours, ts);
    if (got != c.home) fails++;
    Log("hometest: %-5s %-8s %s (%s -> %s)", got == c.home ? "ok" : "FAIL", got ? "home" : "internet", c.what, c.peer,
        c.ours);
  }
  auto mine = remote::TailscaleAddresses();
  Log("hometest: this PC's Tailscale addresses: %zu", mine.size());
  Log("hometest: %s", fails ? "FAILED" : "all ok");
  return fails ? 1 : 0;
}

// ---------------------------------------------------------------- input self-test
// Injects tagged events and verifies them with low-level hooks that SWALLOW only our tagged
// events, so nothing reaches the real desktop (the user's own mouse/keyboard pass through).
#include "desktop.h"
#include "input.h"

namespace {
struct Rec {
  int kind;  // 0 mouse, 1 key
  int a, b;  // mouse: x,y   key: vk, scan
  DWORD flags, data;
  WPARAM msg;
};
std::mutex g_recMu;
std::vector<Rec> g_recs;

LRESULT CALLBACK LLMouse(int code, WPARAM wp, LPARAM lp) {
  if (code == HC_ACTION) {
    auto* m = (MSLLHOOKSTRUCT*)lp;
    if (m->dwExtraInfo == kInjectTag) {
      std::lock_guard<std::mutex> lk(g_recMu);
      g_recs.push_back({0, m->pt.x, m->pt.y, m->flags, m->mouseData, wp});
      return 1;
    }
  }
  return CallNextHookEx(nullptr, code, wp, lp);
}

LRESULT CALLBACK LLKey(int code, WPARAM wp, LPARAM lp) {
  if (code == HC_ACTION) {
    auto* k = (KBDLLHOOKSTRUCT*)lp;
    if (k->dwExtraInfo == kInjectTag) {
      std::lock_guard<std::mutex> lk(g_recMu);
      g_recs.push_back({1, (int)k->vkCode, (int)k->scanCode, k->flags, 0, wp});
      return 1;
    }
  }
  return CallNextHookEx(nullptr, code, wp, lp);
}
}  // namespace

int RunInputSelfTest() {
  DWORD hookTid = 0;
  HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  bool hooked = false;
  std::thread ht([&] {
    hookTid = GetCurrentThreadId();
    HHOOK mh = SetWindowsHookExW(WH_MOUSE_LL, LLMouse, GetModuleHandleW(nullptr), 0);
    HHOOK kh = SetWindowsHookExW(WH_KEYBOARD_LL, LLKey, GetModuleHandleW(nullptr), 0);
    hooked = mh && kh;
    MSG msg;
    PeekMessageW(&msg, nullptr, 0, 0, PM_NOREMOVE);
    SetEvent(ready);
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) DispatchMessageW(&msg);
    if (mh) UnhookWindowsHookEx(mh);
    if (kh) UnhookWindowsHookEx(kh);
  });
  WaitForSingleObject(ready, 5000);
  if (!hooked) {
    Log("inputtest: could not install hooks");
    PostThreadMessageW(hookTid, WM_QUIT, 0, 0);
    ht.join();
    return 2;
  }
  RECT r{0, 0, GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)};
  for (auto& m : ListMonitors())
    if (m.primary) r = m.rect;
  int w = r.right - r.left, h = r.bottom - r.top;
  POINT pts[] = {{0, 0}, {w - 1, h - 1}, {w / 2, h / 2}, {123, 457}, {w - 1, 0}, {0, h - 1}, {1, 1}, {w - 2, h - 2}, {641, 359}};

  InputInjector inj;
  inj.SetMonitorRect(r);
  inj.Start();
  auto post = [&](InputEvent e) {
    inj.Post(std::move(e));
    Sleep(15);  // keep moves from being coalesced
  };
  for (auto& p : pts) {
    InputEvent e;
    e.type = InputEvent::Move;
    e.x = p.x;
    e.y = p.y;
    post(e);
  }
  for (int b = 1; b <= 3; b++) {
    InputEvent d;
    d.type = InputEvent::Button;
    d.button = (uint8_t)b;
    d.down = 1;
    post(d);
    d.down = 0;
    post(d);
  }
  {
    InputEvent e;
    e.type = InputEvent::Wheel;
    e.dy = 120;
    e.dx = -120;
    post(e);
    e.dy = 30;
    e.dx = 0;
    post(e);
  }
  for (uint16_t vk : {(uint16_t)'A', (uint16_t)VK_LEFT, (uint16_t)VK_CONTROL}) {
    InputEvent e;
    e.type = InputEvent::Key;
    e.vk = vk;
    e.down = 1;
    post(e);
    e.down = 0;
    post(e);
  }
  {
    InputEvent e;
    e.type = InputEvent::Text;
    e.text = L"A\u00f1\u20ac\U0001F600\n";
    post(e);
  }
  Sleep(400);
  inj.Stop();
  PostThreadMessageW(hookTid, WM_QUIT, 0, 0);
  ht.join();

  int fails = 0;
  std::lock_guard<std::mutex> lk(g_recMu);
  size_t mi = 0;
  for (auto& rec : g_recs) {
    if (rec.kind == 0 && rec.msg == WM_MOUSEMOVE && mi < std::size(pts)) {
      int ex = r.left + pts[mi].x, ey = r.top + pts[mi].y;
      bool ok = rec.a == ex && rec.b == ey;
      if (!ok) fails++;
      Log("inputtest: move %zu expect (%d,%d) got (%d,%d) %s", mi, ex, ey, rec.a, rec.b, ok ? "OK" : "MISMATCH");
      mi++;
    } else if (rec.kind == 0) {
      Log("inputtest: mouse msg 0x%04x data=%d", (unsigned)rec.msg, (int)(short)HIWORD(rec.data));
    } else {
      Log("inputtest: key %s vk=0x%02x scan=0x%04x ext=%d", (rec.flags & LLKHF_UP) ? "up  " : "down", rec.a, rec.b,
          (rec.flags & LLKHF_EXTENDED) ? 1 : 0);
    }
  }
  if (mi != std::size(pts)) {
    Log("inputtest: expected %zu moves, saw %zu", std::size(pts), mi);
    fails++;
  }
  Log("inputtest: %zu events captured, %d failure(s)", g_recs.size(), fails);
  return fails == 0 ? 0 : 1;
}
