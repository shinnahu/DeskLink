// Developer self-tests (not used in normal operation).
//   DeskLinkHost.exe --test-capture <seconds> <out.h264> [maxW maxH fps kbps monitor(index | all)]
#include <cstdio>

#include "capture.h"
#include "crypto.h"
#include "protocol.h"
#include "vdisplay.h"

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
        formats++;
        framesAtFormat = frames;
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
  uint64_t formats = 0, framesAtFormat = 0;  // pictures (re)started, and the frames before the last one started
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
  // --test-drop-virtual: part way through, the virtual screen is taken away as its driver might; the picture must
  // come back (a new virtual screen)
  uint64_t dropAt = g_opt.testDropVirtualAt > 0 ? NowMs() + (uint64_t)g_opt.testDropVirtualAt * 1000 : 0;
  uint64_t formatsAtDrop = 0;
  bool dropped = false;
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
    if (dropAt && NowMs() >= dropAt) {
      dropAt = 0;
      formatsAtDrop = sink.formats;
      dropped = vscreen::SimulateDrop();
      Log("selftest: the virtual screen taken away, as by its driver: %s (%llu frames so far)",
          dropped ? "done" : "there was none", sink.frames);
    }
  }
  StreamStats st = s.Stats();
  s.Stop();
  fclose(f);
  Log("selftest: done frames=%llu keys=%llu bytes=%llu cursorMsgs=%llu encoder=%s kbps=%d", sink.frames, sink.keys,
      sink.bytes, sink.cursorMsgs, st.encoder.c_str(), st.kbps);
  bool back = !dropped || (sink.formats > formatsAtDrop && sink.frames > sink.framesAtFormat);
  if (dropped)
    Log("selftest: after the virtual screen was taken away: %s (%llu frames since the picture restarted)",
        back ? "the picture came back" : "NO PICTURE AGAIN", sink.formats > formatsAtDrop ? sink.frames - sink.framesAtFormat : 0);
  return sink.frames > 0 && back ? 0 : 1;
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
      {"2001:db8:1::1", "2001:db8:2::2", false, false, "a public IPv6 address (documentation range)"},
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
  // pairing is refused from the router's own address (some routers hand internet connections on under it)
  struct RouterCase {
    const char* peer;
    bool router;
    const char* what;
  } routerCases[] = {
      {"192.168.0.1", true, "the router itself"},
      {"::ffff:192.168.0.1", true, "the router itself, on a dual-stack socket"},
      {"fe80::1", true, "the router's IPv6 (link-local)"},
      {"192.168.0.5", false, "a phone on the home Wi-Fi"},
      {"192.168.0.10", false, "another device on the home Wi-Fi"},
  };
  std::vector<std::string> gateways = {"192.168.0.1", "fe80::1"};
  for (auto& c : routerCases) {
    bool got = remote::IsFromRouter(c.peer, gateways);
    if (got != c.router) fails++;
    Log("hometest: %-5s %-8s %s (%s)", got == c.router ? "ok" : "FAIL", got ? "router" : "device", c.what, c.peer);
  }
  auto mine = remote::TailscaleAddresses();
  Log("hometest: this PC's Tailscale addresses: %zu, its routers: %zu", mine.size(), remote::GatewayAddresses().size());
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

// ---------------------------------------------------------------- virtual screen
// Needs Parsec's virtual display driver. A virtual screen is put in beside the monitor and the pings keep it; taken
// away by the driver, it's replaced. Then it goes again: because a monitor is on (it stays while forced), and 30 s
// after the last stream ended.
namespace {
bool WaitUntil(uint64_t ms, const std::function<bool()>& done) {
  for (uint64_t end = NowMs() + ms; NowMs() < end; Sleep(100))
    if (done()) return true;
  return done();
}

void DescribeVirtual() {
  std::wstring n = vscreen::VirtualOnDesktop();
  DEVMODEW dm{};
  dm.dmSize = sizeof(dm);
  if (!n.empty() && EnumDisplaySettingsW(n.c_str(), ENUM_CURRENT_SETTINGS, &dm))
    Log("vstest: virtual screen %s at %ld,%ld, %lux%lu @%lu Hz", W2U(n).c_str(), dm.dmPosition.x, dm.dmPosition.y,
        dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency);
}
}  // namespace

int RunVirtualScreenSelfTest() {
  if (!vscreen::Installed()) {
    Log("vstest: Parsec's virtual display driver isn't installed");
    return 2;
  }
  auto on = [] { return !vscreen::VirtualOnDesktop().empty(); };
  int fails = 0;
  auto check = [&](bool ok, const char* what) {
    Log("vstest: %s: %s", what, ok ? "OK" : "FAILED");
    if (!ok) fails++;
    return ok;
  };
  vscreen::Hold();  // as a stream does
  // 1. in, beside the monitor (forced: a monitor that's on would send it away)
  uint64_t t = NowMs();
  if (!check(vscreen::Ensure(true) == vscreen::State::Starting, "the driver adds a screen")) return 1;
  if (check(WaitUntil(10000, on), "Windows puts it on the desktop")) {
    Log("vstest: on the desktop after %llu ms", NowMs() - t);
    DescribeVirtual();
  }
  // 2. the pings keep it (without them the driver takes it away after about a second)
  Sleep(3000);
  check(on(), "still there after 3 s");
  // 3. the driver takes it away by itself (as when the pings came late): that's noticed and a new one is put in
  t = NowMs();
  if (check(vscreen::SimulateDrop(), "the driver takes it away (simulated)")) {
    if (check(WaitUntil(5000, [&] { return !on(); }), "it leaves the desktop"))
      Log("vstest: off the desktop %llu ms after that", NowMs() - t);
    // as the capture does while it has no screen: asks for one again and again
    if (check(WaitUntil(15000, [&] { vscreen::Ensure(true); return on(); }), "a new one is put in"))
      Log("vstest: a new one on the desktop %llu ms after the old one was taken away", NowMs() - t);
  }
  // 4. a monitor that's on sends it away after 2 s
  if (vscreen::RealMonitorOnDesktop()) {
    t = NowMs();
    vscreen::Unpin();
    if (check(WaitUntil(6000, [&] { return !on(); }), "a monitor that's on sends it away"))
      Log("vstest: gone %llu ms after that", NowMs() - t);
  } else {
    Log("vstest: no monitor is on: step 3 skipped");
  }
  // 5. in again, then the stream ends: it stays 30 s, then goes
  t = NowMs();
  if (check(vscreen::Ensure(true) == vscreen::State::Starting, "the driver adds a screen again") &&
      check(WaitUntil(10000, on), "Windows puts it on the desktop again")) {
    Log("vstest: on the desktop after %llu ms", NowMs() - t);
    vscreen::Release();
    t = NowMs();
    Sleep(25000);
    check(on(), "still there 25 s after the stream ended");
    if (check(WaitUntil(10000, [&] { return !on(); }), "gone about 30 s after the stream ended"))
      Log("vstest: gone %llu ms after the stream ended", NowMs() - t);
  }
  vscreen::RemoveNow();
  Log("vstest: %d failure(s)", fails);
  return fails == 0 ? 0 : 1;
}

// ---------------------------------------------------------------- the PC's sound
// Plays a quiet 1 kHz tone through the default sound output while an AudioStreamer listens, then decodes what it sent
// with Windows' own AAC decoder: the tone must come back, and nothing more may be sent once a second of quiet passed.
#include <audioclient.h>
#include <mfapi.h>
#include <mferror.h>
#include <mftransform.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
#include <wrl/client.h>

#include <cmath>

#include "audio.h"

namespace {
using Microsoft::WRL::ComPtr;
constexpr double kPi = 3.14159265358979323846;
const GUID kFloatFormat = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

// A sine on the default output for `ms` (shared mode, its mix format, every channel)
bool PlayTone(int ms, double hz, double amp) {
  ComPtr<IMMDeviceEnumerator> en;
  ComPtr<IMMDevice> dev;
  ComPtr<IAudioClient> c;
  ComPtr<IAudioRenderClient> rc;
  WAVEFORMATEX* f = nullptr;
  bool ok = SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&en))) &&
            SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev)) &&
            SUCCEEDED(dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)c.GetAddressOf())) &&
            SUCCEEDED(c->GetMixFormat(&f)) &&
            SUCCEEDED(c->Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1000000, 0, f, nullptr)) &&
            SUCCEEDED(c->GetService(IID_PPV_ARGS(&rc)));
  bool isFloat = f && (f->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
                       (f->wFormatTag == WAVE_FORMAT_EXTENSIBLE && ((WAVEFORMATEXTENSIBLE*)f)->SubFormat == kFloatFormat));
  if (ok && !isFloat && f->wBitsPerSample != 16) {
    Log("audiotest: the output's mix format (%d-bit) isn't one this test writes", f->wBitsPerSample);
    ok = false;
  }
  if (ok) {
    UINT32 size = 0;
    c->GetBufferSize(&size);
    double phase = 0, step = 2 * kPi * hz / f->nSamplesPerSec;
    c->Start();
    for (uint64_t end = NowMs() + ms; NowMs() < end; Sleep(10)) {
      UINT32 pad = 0;
      c->GetCurrentPadding(&pad);
      UINT32 n = size - pad;
      BYTE* p = nullptr;
      if (n == 0 || FAILED(rc->GetBuffer(n, &p))) continue;
      for (UINT32 i = 0; i < n; i++, phase += step) {
        double v = amp * std::sin(phase);
        for (int ch = 0; ch < f->nChannels; ch++) {
          if (isFloat) ((float*)p)[i * f->nChannels + ch] = (float)v;
          else ((int16_t*)p)[i * f->nChannels + ch] = (int16_t)std::lrint(v * 32767);
        }
      }
      rc->ReleaseBuffer(n, 0);
    }
    Sleep(200);  // the rest of the buffer plays
    c->Stop();
  }
  if (f) CoTaskMemFree(f);
  return ok;
}

// Raw AAC frames -> interleaved 16-bit PCM, with Windows' AAC decoder
bool DecodeAac(const std::vector<Bytes>& frames, int rate, int channels, const Bytes& config, std::vector<int16_t>& pcm) {
  MFT_REGISTER_TYPE_INFO in{MFMediaType_Audio, MFAudioFormat_AAC}, out{MFMediaType_Audio, MFAudioFormat_PCM};
  IMFActivate** acts = nullptr;
  UINT32 n = 0;
  if (FAILED(MFTEnumEx(MFT_CATEGORY_AUDIO_DECODER, MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER, &in, &out, &acts,
                       &n)) || n == 0)
    return false;
  ComPtr<IMFTransform> mft;
  HRESULT hr = acts[0]->ActivateObject(IID_PPV_ARGS(&mft));
  for (UINT32 i = 0; i < n; i++) acts[i]->Release();
  CoTaskMemFree(acts);
  if (FAILED(hr)) return false;
  ComPtr<IMFMediaType> it;
  MFCreateMediaType(&it);
  it->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
  it->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC);
  it->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
  it->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
  it->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0);
  // HEAACWAVEINFO's fields after WAVEFORMATEX (payload type 0, profile "unknown"), then the AudioSpecificConfig
  BYTE ud[12 + 8] = {0, 0, 0xFE, 0};
  memcpy(ud + 12, config.data(), std::min<size_t>(config.size(), 8));
  it->SetBlob(MF_MT_USER_DATA, ud, (UINT32)(12 + std::min<size_t>(config.size(), 8)));
  if (FAILED(mft->SetInputType(0, it.Get(), 0))) return false;
  for (DWORD i = 0;; i++) {
    ComPtr<IMFMediaType> ot;
    if (FAILED(mft->GetOutputAvailableType(0, i, &ot))) return false;
    GUID sub{};
    UINT32 bits = 0;
    ot->GetGUID(MF_MT_SUBTYPE, &sub);
    ot->GetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, &bits);
    if (sub == MFAudioFormat_PCM && bits == 16 && SUCCEEDED(mft->SetOutputType(0, ot.Get(), 0))) break;
  }
  MFT_OUTPUT_STREAM_INFO osi{};
  mft->GetOutputStreamInfo(0, &osi);
  bool provides = (osi.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
  DWORD outSize = osi.cbSize ? osi.cbSize : 65536;
  mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
  auto drain = [&] {
    for (;;) {
      MFT_OUTPUT_DATA_BUFFER ob{};
      ComPtr<IMFSample> mine;
      if (!provides) {
        ComPtr<IMFMediaBuffer> b;
        MFCreateSample(&mine);
        MFCreateMemoryBuffer(outSize, &b);
        mine->AddBuffer(b.Get());
        ob.pSample = mine.Get();
      }
      DWORD st = 0;
      HRESULT h = mft->ProcessOutput(0, 1, &ob, &st);
      if (ob.pEvents) ob.pEvents->Release();
      if (FAILED(h)) return h == MF_E_TRANSFORM_NEED_MORE_INPUT;
      ComPtr<IMFMediaBuffer> buf;
      BYTE* p = nullptr;
      DWORD len = 0;
      if (ob.pSample && SUCCEEDED(ob.pSample->ConvertToContiguousBuffer(&buf)) &&
          SUCCEEDED(buf->Lock(&p, nullptr, &len))) {
        pcm.insert(pcm.end(), (const int16_t*)p, (const int16_t*)p + len / 2);
        buf->Unlock();
      }
      if (provides && ob.pSample) ob.pSample->Release();
    }
  };
  for (const Bytes& fr : frames) {
    ComPtr<IMFSample> s;
    ComPtr<IMFMediaBuffer> b;
    MFCreateSample(&s);
    MFCreateMemoryBuffer((DWORD)fr.size(), &b);
    BYTE* p = nullptr;
    b->Lock(&p, nullptr, nullptr);
    memcpy(p, fr.data(), fr.size());
    b->Unlock();
    b->SetCurrentLength((DWORD)fr.size());
    s->AddBuffer(b.Get());
    hr = mft->ProcessInput(0, s.Get(), 0);
    if (hr == MF_E_NOTACCEPTING) {
      if (!drain()) return false;
      hr = mft->ProcessInput(0, s.Get(), 0);
    }
    if (FAILED(hr) || !drain()) return false;
  }
  mft->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
  return drain();
}

// Goertzel: the power of one frequency in the left channel of pcm[from .. from+count)
double PowerAt(const std::vector<int16_t>& pcm, int channels, size_t from, size_t count, double hz, int rate) {
  double c = 2 * std::cos(2 * kPi * hz / rate), s1 = 0, s2 = 0;
  for (size_t i = 0; i < count; i++) {
    double s = pcm[(from + i) * channels] + c * s1 - s2;
    s2 = s1;
    s1 = s;
  }
  return s1 * s1 + s2 * s2 - c * s1 * s2;
}

// An ADTS file of the frames (most players play it), for listening
void WriteAdts(const std::wstring& path, const std::vector<Bytes>& frames, int rate, int channels) {
  static const int rates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
  int idx = 3;
  for (int i = 0; i < 13; i++)
    if (rates[i] == rate) idx = i;
  FILE* f = _wfopen(path.c_str(), L"wb");
  if (!f) return;
  for (const Bytes& fr : frames) {
    int len = (int)fr.size() + 7;
    uint8_t h[7] = {0xFF, 0xF1, (uint8_t)((1 << 6) | (idx << 2) | (channels >> 2)),
                    (uint8_t)(((channels & 3) << 6) | (len >> 11)), (uint8_t)(len >> 3),
                    (uint8_t)(((len & 7) << 5) | 0x1F), 0xFC};
    fwrite(h, 1, 7, f);
    fwrite(fr.data(), 1, fr.size(), f);
  }
  fclose(f);
}
}  // namespace

int RunAudioSelfTest(int seconds, const std::wstring& out) {
  if (seconds < 1) seconds = 3;
  CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  MFStartup(MF_VERSION, MFSTARTUP_LITE);
  int fails = 0;
  auto check = [&](bool ok, const std::string& what) {
    Log("audiotest: %s: %s", what.c_str(), ok ? "OK" : "FAILED");
    if (!ok) fails++;
    return ok;
  };
  std::mutex mu;
  std::vector<std::pair<uint64_t, Bytes>> msgs;  // (when, message)
  AudioStreamer a([&](Bytes&& m) {
    std::lock_guard<std::mutex> lk(mu);
    msgs.emplace_back(NowMs(), std::move(m));
  });
  a.Start();
  Sleep(600);  // it opens the output and says its format
  Log("audiotest: a quiet 1 kHz tone through this PC's sound output for %d s", seconds);
  uint64_t toneStart = NowMs();
  bool played = PlayTone(seconds * 1000, 1000.0, 0.05);
  uint64_t toneEnd = NowMs();
  Sleep(2500);  // a second of quiet, then nothing more should come
  a.Stop();

  int codec = -1, rate = 0, channels = 0;
  Bytes config;
  std::vector<Bytes> frames;
  std::vector<uint64_t> times;
  uint64_t lastAt = 0, beforeTone = 0;
  size_t bytes = 0;
  for (auto& [at, m] : msgs) {
    Reader r(m.data() + 1, m.size() - 1);
    size_t n = 0;
    if (m[0] == msg::AUDIO_FORMAT) {
      codec = r.u8();
      rate = (int)r.u32();
      channels = r.u8();
      const uint8_t* p = r.rest(n);
      config.assign(p, p + n);
    } else if (m[0] == msg::AUDIO_DATA) {
      times.push_back(r.u64());
      const uint8_t* p = r.rest(n);
      frames.emplace_back(p, p + n);
      bytes += n;
      lastAt = at;
      if (at < toneStart) beforeTone++;
    }
  }
  check(played, "the tone played through the PC's sound output");
  char what[200];
  snprintf(what, sizeof(what), "AUDIO_FORMAT: codec %d, %d Hz, %d channel(s), config %zu bytes", codec, rate, channels,
           config.size());
  if (!check(codec == acodec::AAC_LC && (rate == 48000 || rate == 44100) && channels == 2 && config.size() == 2, what)) {
    MFShutdown();
    return 1;
  }
  double frameMs = 1024.0 * 1000 / rate, toneMs = (double)(toneEnd - toneStart);
  snprintf(what, sizeof(what), "%zu AAC frames (%.1f s of sound) for a %.1f s tone, %zu before it", frames.size(),
           frames.size() * frameMs / 1000, toneMs / 1000, (size_t)beforeTone);
  check(frames.size() * frameMs >= toneMs * 0.9 && frames.size() * frameMs <= toneMs + 1600 && beforeTone == 0, what);
  bool steady = true;
  for (size_t i = 1; i < times.size(); i++)
    if (times[i] <= times[i - 1]) steady = false;
  check(steady, "timestamps keep going up");
  double kbps = frames.empty() ? 0.0 : bytes * 8.0 / (frames.size() * frameMs);
  snprintf(what, sizeof(what), "%.0f kbps", kbps);
  check(kbps > 0 && kbps < 200, what);
  snprintf(what, sizeof(what), "nothing more sent %.1f s after the tone ended", lastAt > toneEnd ? (lastAt - toneEnd) / 1000.0 : 0.0);
  check(lastAt > toneEnd && lastAt - toneEnd < 1800, what);

  std::vector<int16_t> pcm;
  bool decoded = DecodeAac(frames, rate, channels, config, pcm);
  size_t got = pcm.size() / channels;
  snprintf(what, sizeof(what), "Windows' AAC decoder reads it back: %.2f s of sound", (double)got / rate);
  check(decoded && got >= frames.size() * 1024 * 9 / 10, what);
  if (decoded && got > (size_t)rate) {
    size_t from = (size_t)(rate * 0.3), count = std::min(got - from, (size_t)(rate * (seconds - 0.5)));
    double tone = PowerAt(pcm, channels, from, count, 1000, rate);
    double other =
        std::max(PowerAt(pcm, channels, from, count, 700, rate), PowerAt(pcm, channels, from, count, 1500, rate));
    double sum = 0;
    for (size_t i = 0; i < count; i++) sum += (double)pcm[(from + i) * channels] * pcm[(from + i) * channels];
    double rms = std::sqrt(sum / count);
    snprintf(what, sizeof(what), "the tone comes back: 1 kHz is %.0f dB above 700 Hz / 1.5 kHz, level %.0f (sent ~1158)",
             10 * std::log10(tone / std::max(other, 1.0)), rms);
    check(tone > other * 1000 && rms > 300, what);
  }
  if (!out.empty()) {
    WriteAdts(out, frames, rate, channels);
    Log("audiotest: wrote %s", W2U(out).c_str());
  }
  Log("audiotest: %d failure(s)", fails);
  MFShutdown();
  return fails == 0 ? 0 : 1;
}
