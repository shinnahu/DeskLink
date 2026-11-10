#include "capture.h"

#include <d3d11_1.h>
#include <mfapi.h>

#include <algorithm>
#include <chrono>
#include <climits>

#include "protocol.h"
#include "vdisplay.h"

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")

Streamer::Streamer(StreamSink* sink) : sink_(sink) {}

Streamer::~Streamer() { Stop(); }

void Streamer::Start(const StreamParams& p) {
  Stop();
  {
    std::lock_guard<std::mutex> lk(mu_);
    params_ = p;
    paused_ = false;
    autoRate_ = true;
    maxKbps_ = p.kbps > 0 ? std::max(300, p.kbps) : 20000;
    int area = std::min(p.maxW, 3840) * std::min(p.maxH, 2160);
    int start = area >= 1920 * 1080 ? 8000 : area >= 1280 * 720 ? 4000 : 2500;
    kbps_ = std::min(maxKbps_, start);
    inflight_.clear();
    srtt_ = 0;
    minRtt_ = UINT64_MAX;
    stats_ = StreamStats{};
  }
  stop_ = false;
  th_ = std::thread([this] { Run(); });
}

void Streamer::Stop() {
  stop_ = true;
  cv_.notify_all();
  if (th_.joinable()) th_.join();
}

void Streamer::Pause() {
  std::lock_guard<std::mutex> lk(mu_);
  paused_ = true;
  inflight_.clear();
}

void Streamer::Resume() {
  {
    std::lock_guard<std::mutex> lk(mu_);
    paused_ = false;
    keyRequested_ = true;
    cursorResend_ = true;
    inflight_.clear();
  }
  cv_.notify_all();
}

void Streamer::RequestKeyframe() {
  std::lock_guard<std::mutex> lk(mu_);
  keyRequested_ = true;
}

void Streamer::Configure(int maxW, int maxH, int maxFps, int kbps) {
  std::lock_guard<std::mutex> lk(mu_);
  bool reinit = false;
  if (maxW > 0 && maxW != params_.maxW) { params_.maxW = maxW; reinit = true; }
  if (maxH > 0 && maxH != params_.maxH) { params_.maxH = maxH; reinit = true; }
  if (maxFps > 0 && maxFps != params_.maxFps) { params_.maxFps = maxFps; reinit = true; }
  if (kbps > 0) {
    params_.kbps = kbps;
    maxKbps_ = std::max(300, kbps);
    kbps_ = std::min(kbps_, maxKbps_);
    reinit = true;
  } else if (kbps == 0 && params_.kbps != 0) {
    params_.kbps = 0;
    maxKbps_ = 20000;
  }
  if (reinit) reinitRequested_ = true;
}

void Streamer::SelectMonitor(int idx) {
  std::lock_guard<std::mutex> lk(mu_);
  params_.monitor = idx;
  reinitRequested_ = true;
}

StreamStats Streamer::Stats() {
  std::lock_guard<std::mutex> lk(mu_);
  StreamStats s = stats_;
  s.kbps = kbps_;
  s.srttMs = (int)srtt_;
  return s;
}

void Streamer::OnAck(uint32_t id) {
  std::lock_guard<std::mutex> lk(mu_);
  uint64_t now = NowMs();
  while (!inflight_.empty() && (int32_t)(inflight_.front().id - id) <= 0) {
    Inflight f = inflight_.front();
    inflight_.pop_front();
    if (f.id == id) {
      uint64_t rtt = now - f.sentMs;
      srtt_ = srtt_ ? (srtt_ * 7 + rtt) / 8 : rtt;
      if (rtt < minRtt_ || now - minRttAt_ > 10000) {
        minRtt_ = rtt;
        minRttAt_ = now;
      }
    }
  }
}

size_t Streamer::InflightCount() {
  std::lock_guard<std::mutex> lk(mu_);
  // frames that were never acked (lost ack / stuck client) expire after 3 s so we never freeze forever
  uint64_t now = NowMs();
  while (!inflight_.empty() && now - inflight_.front().sentMs > 3000) inflight_.pop_front();
  return inflight_.size();
}

int Streamer::Window() {
  std::lock_guard<std::mutex> lk(mu_);
  if (minRtt_ == UINT64_MAX) return 3;
  int w = (int)(minRtt_ * (uint64_t)fps_ / 1000) + 2;
  return std::clamp(w, 3, 10);
}

void Streamer::SendStatus(uint8_t flags, const std::string& text) {
  if (flags == statusFlags_ && text == statusText_) return;
  statusFlags_ = flags;
  statusText_ = text;
  Writer w(msg::HOST_STATUS);
  w.u8(flags).str(text);
  sink_->SendStream(std::move(w.b));
}

// ---------------------------------------------------------------- pipeline
void Streamer::Teardown() {
  for (auto& s : srcs_)
    if (s.dupl) s.dupl->ReleaseFrame();
  enc_.Shutdown();
  ring_.clear();
  inView_.Reset();
  vp_.Reset();
  vpEnum_.Reset();
  vctx_.Reset();
  vdev_.Reset();
  capTex_.Reset();
  srcs_.clear();  // their duplications, staging textures and other graphics cards' devices
  all_ = false;
  if (ctx_) ctx_->ClearState();
  ctx_.Reset();
  dev_.Reset();
  pipelineOk_ = false;
  haveContent_ = false;
}

static HRESULT MakeDevice(IDXGIAdapter1* a, bool video, ComPtr<ID3D11Device>& dev, ComPtr<ID3D11DeviceContext>& ctx,
                          D3D_FEATURE_LEVEL& got) {
  static const D3D_FEATURE_LEVEL fls[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1,
                                          D3D_FEATURE_LEVEL_10_0};
  UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | (video ? D3D11_CREATE_DEVICE_VIDEO_SUPPORT : 0);
  return D3D11CreateDevice(a, D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags, fls, (UINT)std::size(fls), D3D11_SDK_VERSION,
                           &dev, &got, &ctx);
}

// Asks for BGRA where it can (HDR / high bit depth screens); older systems take the plain call.
static HRESULT Duplicate(IDXGIOutput1* o, ID3D11Device* dev, ComPtr<IDXGIOutputDuplication>& out) {
  HRESULT hr = E_NOINTERFACE;
  ComPtr<IDXGIOutput5> o5;
  if (SUCCEEDED(o->QueryInterface(IID_PPV_ARGS(&o5)))) {
    DXGI_FORMAT fmts[] = {DXGI_FORMAT_B8G8R8A8_UNORM};
    hr = o5->DuplicateOutput1(dev, 0, 1, fmts, &out);
  }
  if (FAILED(hr)) hr = o->DuplicateOutput(dev, &out);
  return hr;
}

static bool Turned(DXGI_MODE_ROTATION r) { return r != DXGI_MODE_ROTATION_IDENTITY && r != DXGI_MODE_ROTATION_UNSPECIFIED; }

// A turned screen's picture comes as the panel scans it (w x h); here it is turned clockwise by `rot` (as the video
// processor does for one screen shown alone) into dst, dw x dh, the way it is on the desktop.
static void Turn(const uint8_t* src, UINT pitch, int w, int h, DXGI_MODE_ROTATION rot, uint32_t* dst, int dw, int dh) {
  for (int y = 0; y < dh; y++) {
    uint32_t* d = dst + (size_t)y * dw;
    for (int x = 0; x < dw; x++) {
      int u, v;
      if (rot == DXGI_MODE_ROTATION_ROTATE90) {
        u = y;
        v = h - 1 - x;
      } else if (rot == DXGI_MODE_ROTATION_ROTATE180) {
        u = w - 1 - x;
        v = h - 1 - y;
      } else {  // 270
        u = w - 1 - y;
        v = x;
      }
      d[x] = *(const uint32_t*)(src + (size_t)v * pitch + (size_t)u * 4);
    }
  }
}

bool Streamer::InitPipeline(std::string& why, uint8_t& flags) {
  // All screens may not work out in one picture (too big for the graphics card or the encoder, a screen that can't be
  // duplicated): then a smaller picture, then the main screen alone. VIDEO_FORMAT tells the phone which it got.
  for (int attempt = 0;;) {
    int next = -1;
    if (BuildPipeline(why, flags, attempt, next)) return true;
    Teardown();
    if (next <= attempt) return false;
    Log("capture: all screens: %s Trying %s.", why.c_str(), next == 1 ? "a smaller picture" : "the main screen alone");
    attempt = next;
  }
}

// attempt 0: as asked; 1: all screens in a smaller picture; 2: the main screen instead of all screens.
// A failure another attempt may get past sets next to that attempt.
bool Streamer::BuildPipeline(std::string& why, uint8_t& flags, int attempt, int& next) {
  flags = 0;
  next = -1;
  StreamParams p;
  {
    std::lock_guard<std::mutex> lk(mu_);
    p = params_;
  }
  ComPtr<IDXGIFactory1> factory;
  if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
    why = "DXGI unavailable";
    flags = hoststatus::NO_SIGNAL;
    return false;
  }
  struct Out {
    ComPtr<IDXGIAdapter1> a;
    LUID card{};
    ComPtr<IDXGIOutput1> o;
    RECT r;
    bool primary;
    bool virt;  // DeskLink's virtual screen (vdisplay.h)
    std::wstring gdi;
  };
  bool noScreen = false;  // test: as if every monitor were switched off
#ifdef DESKLINK_TESTING
  noScreen = g_opt.testNoScreen;
#endif
  std::vector<Out> outs;
  int ghosts = 0;
  ComPtr<IDXGIAdapter1> a;
  for (UINT ai = 0; factory->EnumAdapters1(ai, &a) != DXGI_ERROR_NOT_FOUND; ai++) {
    DXGI_ADAPTER_DESC1 ad{};
    a->GetDesc1(&ad);
    ComPtr<IDXGIOutput> o;
    for (UINT oi = 0; a->EnumOutputs(oi, &o) != DXGI_ERROR_NOT_FOUND; oi++) {
      DXGI_OUTPUT_DESC d{};
      o->GetDesc(&d);
      if (!d.AttachedToDesktop) continue;
      MONITORINFO mi{sizeof(mi)};
      GetMonitorInfoW(d.Monitor, &mi);
      Out x;
      x.a = a;
      x.card = ad.AdapterLuid;
      o.As(&x.o);
      x.r = d.DesktopCoordinates;
      x.primary = (mi.dwFlags & MONITORINFOF_PRIMARY) != 0;
      x.virt = vscreen::IsVirtual(d.DeviceName);
      x.gdi = d.DeviceName;
      if (noScreen && !x.virt) continue;
      // the last monitor switched off or unplugged: Windows keeps its screen on the desktop with nothing behind it,
      // and draws nothing to it, so no frames would ever come
      if (!x.virt && !vscreen::HasMonitor(d.DeviceName)) {
        ghosts++;
        continue;
      }
      if (x.o) outs.push_back(x);
    }
  }
  if (ghosts != ghostsLogged_) {
    ghostsLogged_ = ghosts;
    if (ghosts) Log("capture: %d screen(s) left out: their monitor is off or unplugged", ghosts);
  }
  if (outs.empty()) {
    // No screen at all: the monitor is switched off (a DisplayPort monitor tells Windows it was unplugged) or
    // unplugged, so Windows draws no desktop. A virtual screen gives it one to draw on.
    switch (vscreen::Ensure(noScreen)) {
      case vscreen::State::Starting: why = "The PC's monitor is off. Starting a virtual screen…"; break;
      case vscreen::State::Waiting: why = "The PC's monitor is turning on…"; break;
      case vscreen::State::Failed: why = "The PC's monitor is off, and the virtual screen didn't start."; break;
      default:
        why = "The PC's monitor is off or unplugged. Turn it on, or install the virtual display driver on the PC "
              "(see DeskLink's README).";
    }
    flags = hoststatus::NO_SIGNAL;
    return false;
  }
#ifdef DESKLINK_TESTING
  // test: the first screen shown again as more screens, each further right and lower down, to try all screens on a
  // PC with one screen: the 1st as it is, the 2nd upside down, the 3rd standing (turned 90°; both through the CPU)
  for (int k = 1; k <= g_opt.testFakeScreens; k++) {
    Out x = outs[0];
    LONG w = x.r.right - x.r.left, h = x.r.bottom - x.r.top, l = x.r.left + w * k, t = x.r.top + 240 * k;
    x.r = k == 3 ? RECT{l, t, l + h, t + w} : RECT{l, t, l + w, t + h};
    x.primary = false;
    outs.push_back(x);
  }
#endif
  bool all = p.monitor == kAllScreens && outs.size() > 1 && attempt < 2;
  int idx = -1;
  if (p.monitor >= 0 && p.monitor < (int)outs.size()) idx = p.monitor;
  for (int i = 0; idx < 0 && i < (int)outs.size(); i++)
    if (outs[i].primary) idx = i;
  if (idx < 0) idx = 0;
  auto sameCard = [&](int i, int j) {
    return outs[i].card.LowPart == outs[j].card.LowPart && outs[i].card.HighPart == outs[j].card.HighPart;
  };
  if (all) {
    // the pictures meet on the graphics card that runs the most screens (the main screen's, if that's a tie)
    auto screensOn = [&](int i) {
      int n = 0;
      for (int j = 0; j < (int)outs.size(); j++) n += sameCard(i, j) ? 1 : 0;
      return n;
    };
    for (int i = 0; i < (int)outs.size(); i++)
      if (screensOn(i) > screensOn(idx)) idx = i;
  }
  Out& sel = outs[idx];

  D3D_FEATURE_LEVEL got{};
  HRESULT hr = MakeDevice(sel.a.Get(), true, dev_, ctx_, got);
  if (FAILED(hr)) {
    why = "Could not open the graphics device.";
    flags = hoststatus::NO_SIGNAL;
    Log("capture: D3D11CreateDevice failed 0x%08x", (unsigned)hr);
    return false;
  }
  ComPtr<ID3D10Multithread> mt;
  if (SUCCEEDED(ctx_.As(&mt))) mt->SetMultithreadProtected(TRUE);

  // a screen that can't be duplicated: the lock screen / an administrator prompt, or capture not available
  auto noDuplicate = [&](HRESULT h) {
    bool secure = h == E_ACCESSDENIED || IsInputDesktopSecure();
    why = secure ? "The PC is locked or showing an administrator prompt." : "Screen capture is not available right now.";
    flags = secure ? hoststatus::SECURE_DESKTOP : hoststatus::NO_SIGNAL;
    Log("capture: DuplicateOutput failed 0x%08x", (unsigned)h);
    if (all && !secure) next = 2;
    return false;
  };
  int srcW, srcH;  // capTex_: the picture that goes into the video processor
  DXGI_MODE_ROTATION vpRot = DXGI_MODE_ROTATION_IDENTITY;
  if (!all) {
    Source s;
    s.rect = sel.r;
    s.gdi = sel.gdi;
    s.virt = sel.virt;
    s.dev = dev_;
    s.ctx = ctx_;
    hr = Duplicate(sel.o.Get(), dev_.Get(), s.dupl);
    if (FAILED(hr)) return noDuplicate(hr);
    DXGI_OUTDUPL_DESC dd{};
    s.dupl->GetDesc(&dd);
    s.texW = (int)dd.ModeDesc.Width;
    s.texH = (int)dd.ModeDesc.Height;
    s.rot = dd.Rotation;
    vpRot = dd.Rotation;  // the video processor turns it
    srcW = s.texW;
    srcH = s.texH;
    monRect_ = sel.r;
    monIndex_ = idx;
    srcs_.push_back(std::move(s));
  } else {
    RECT box = outs[0].r;
    for (auto& o : outs) UnionRect(&box, &box, &o.r);
    srcW = box.right - box.left;
    srcH = box.bottom - box.top;
    int maxTex = got >= D3D_FEATURE_LEVEL_11_0 ? D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION : 8192;
    if (srcW > maxTex || srcH > maxTex) {
      why = "The screens together are too big for one picture.";
      flags = hoststatus::NO_SIGNAL;
      next = 2;
      return false;
    }
    all_ = true;
    monRect_ = box;
    monIndex_ = kMonitorAll;
    for (int i = 0; i < (int)outs.size(); i++) {
      Out& o = outs[i];
      Source s;
      s.rect = o.r;
      s.gdi = o.gdi;
      s.virt = o.virt;
      s.ox = o.r.left - box.left;
      s.oy = o.r.top - box.top;
      bool mirror = false;
#ifdef DESKLINK_TESTING
      // a pretend screen shows screen 0's picture (a process duplicates a screen only once)
      mirror = g_opt.testFakeScreens > 0 && i > 0;
#endif
      if (mirror) {
        s.mirrorOf = 0;
        s.dev = dev_;
        s.ctx = ctx_;
        s.texW = srcs_[0].texW;
        s.texH = srcs_[0].texH;
        if (i == 2) s.rot = DXGI_MODE_ROTATION_ROTATE180;
        if (i == 3) s.rot = DXGI_MODE_ROTATION_ROTATE90;
      } else {
        if (sameCard(i, idx)) {
          s.dev = dev_;
          s.ctx = ctx_;
        } else {  // a screen on another graphics card: duplicated there, its picture brought over through the CPU
          D3D_FEATURE_LEVEL fl{};
          hr = MakeDevice(o.a.Get(), false, s.dev, s.ctx, fl);
          if (FAILED(hr)) {
            why = "Could not open a screen's graphics device.";
            flags = hoststatus::NO_SIGNAL;
            Log("capture: D3D11CreateDevice (screen %d) failed 0x%08x", i, (unsigned)hr);
            next = 2;
            return false;
          }
          s.cpu = true;
        }
        hr = Duplicate(o.o.Get(), s.dev.Get(), s.dupl);
        if (FAILED(hr)) return noDuplicate(hr);
        DXGI_OUTDUPL_DESC dd{};
        s.dupl->GetDesc(&dd);
        s.texW = (int)dd.ModeDesc.Width;
        s.texH = (int)dd.ModeDesc.Height;
        s.rot = dd.Rotation;
        bool sideways = s.rot == DXGI_MODE_ROTATION_ROTATE90 || s.rot == DXGI_MODE_ROTATION_ROTATE270;
        int w = sideways ? s.texH : s.texW, h = sideways ? s.texW : s.texH;  // as on the desktop
        if (w != o.r.right - o.r.left || h != o.r.bottom - o.r.top) {
          why = "A screen's picture doesn't match its place on the desktop.";
          flags = hoststatus::NO_SIGNAL;
          Log("capture: screen %d is %dx%d, its place on the desktop %ldx%ld", i, w, h, o.r.right - o.r.left,
              o.r.bottom - o.r.top);
          next = 2;
          return false;
        }
      }
      if (Turned(s.rot)) s.cpu = true;  // turned the right way round on the CPU
      if (s.cpu) {
        D3D11_TEXTURE2D_DESC sd{};
        sd.Width = (UINT)s.texW;
        sd.Height = (UINT)s.texH;
        sd.MipLevels = 1;
        sd.ArraySize = 1;
        sd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        sd.SampleDesc.Count = 1;
        sd.Usage = D3D11_USAGE_STAGING;
        sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(s.dev->CreateTexture2D(&sd, nullptr, &s.staging))) {
          why = "Out of video memory.";
          flags = hoststatus::NO_SIGNAL;
          next = 2;
          return false;
        }
      }
      srcs_.push_back(std::move(s));
    }
  }
  int deskW = monRect_.right - monRect_.left, deskH = monRect_.bottom - monRect_.top;

  // encoded size: one screen fits into maxW x maxH. All screens: each as sharp as it would be on its own, the whole
  // picture within what encoders and the phone's decoder take. Even dimensions.
  double scale = 1.0;
  for (auto& s : srcs_) {
    int w = s.rect.right - s.rect.left, h = s.rect.bottom - s.rect.top;
    if (p.maxW > 0) scale = std::min(scale, (double)p.maxW / w);
    if (p.maxH > 0) scale = std::min(scale, (double)p.maxH / h);
  }
  if (all_) {
    int capW = attempt == 0 ? 3840 : 1920, capH = attempt == 0 ? 2160 : 1920;
    if (p.decW >= 640) capW = std::min(capW, p.decW);
    if (p.decH >= 360) capH = std::min(capH, p.decH);
    scale = std::min(scale, std::min((double)capW / deskW, (double)capH / deskH));
  }
  encW_ = std::max(64, (int)(deskW * scale) & ~1);
  encH_ = std::max(64, (int)(deskH * scale) & ~1);
  fps_ = std::clamp(p.maxFps, 5, 120);

  D3D11_TEXTURE2D_DESC td{};
  td.Width = (UINT)srcW;
  td.Height = (UINT)srcH;
  td.MipLevels = 1;
  td.ArraySize = 1;
  td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
  td.SampleDesc.Count = 1;
  td.Usage = D3D11_USAGE_DEFAULT;
  td.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
  if (FAILED(dev_->CreateTexture2D(&td, nullptr, &capTex_))) {
    why = "Out of video memory.";
    flags = hoststatus::NO_SIGNAL;
    if (all_) next = 2;
    return false;
  }
  capDesc_ = td;
  // start black until the first captured frame arrives (all screens: it stays black where no screen is)
  {
    ComPtr<ID3D11RenderTargetView> rtv;
    if (SUCCEEDED(dev_->CreateRenderTargetView(capTex_.Get(), nullptr, &rtv))) {
      const float black[4] = {0, 0, 0, 1};
      ctx_->ClearRenderTargetView(rtv.Get(), black);
    }
  }

  if (FAILED(dev_.As(&vdev_)) || FAILED(ctx_.As(&vctx_))) {
    why = "The graphics driver has no video processor.";
    flags = hoststatus::NO_SIGNAL;
    return false;
  }
  D3D11_VIDEO_PROCESSOR_CONTENT_DESC cd{};
  cd.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
  cd.InputFrameRate = {60, 1};
  cd.InputWidth = (UINT)srcW;
  cd.InputHeight = (UINT)srcH;
  cd.OutputFrameRate = {60, 1};
  cd.OutputWidth = (UINT)encW_;
  cd.OutputHeight = (UINT)encH_;
  cd.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
  if (FAILED(vdev_->CreateVideoProcessorEnumerator(&cd, &vpEnum_)) ||
      FAILED(vdev_->CreateVideoProcessor(vpEnum_.Get(), 0, &vp_))) {
    why = "Video processor unavailable.";
    flags = hoststatus::NO_SIGNAL;
    if (all_) next = 2;
    return false;
  }
  D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC ivd{};
  ivd.FourCC = 0;
  ivd.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
  if (FAILED(vdev_->CreateVideoProcessorInputView(capTex_.Get(), vpEnum_.Get(), &ivd, &inView_))) {
    why = "Video processor input unavailable.";
    flags = hoststatus::NO_SIGNAL;
    if (all_) next = 2;
    return false;
  }
  ring_.resize(4);
  for (auto& s : ring_) {
    D3D11_TEXTURE2D_DESC nd{};
    nd.Width = (UINT)encW_;
    nd.Height = (UINT)encH_;
    nd.MipLevels = 1;
    nd.ArraySize = 1;
    nd.Format = DXGI_FORMAT_NV12;
    nd.SampleDesc.Count = 1;
    nd.Usage = D3D11_USAGE_DEFAULT;
    nd.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_VIDEO_ENCODER;
    if (FAILED(dev_->CreateTexture2D(&nd, nullptr, &s.tex))) {
      nd.BindFlags = D3D11_BIND_RENDER_TARGET;
      if (FAILED(dev_->CreateTexture2D(&nd, nullptr, &s.tex))) {
        why = "Out of video memory.";
        flags = hoststatus::NO_SIGNAL;
        if (all_) next = attempt == 0 ? 1 : 2;
        return false;
      }
    }
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ovd{};
    ovd.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    if (FAILED(vdev_->CreateVideoProcessorOutputView(s.tex.Get(), vpEnum_.Get(), &ovd, &s.view))) {
      why = "Video processor output unavailable.";
      flags = hoststatus::NO_SIGNAL;
      if (all_) next = attempt == 0 ? 1 : 2;
      return false;
    }
  }
  // colour: full-range sRGB in, BT.709 limited out
  ComPtr<ID3D11VideoContext1> vctx1;
  if (SUCCEEDED(vctx_.As(&vctx1))) {
    vctx1->VideoProcessorSetStreamColorSpace1(vp_.Get(), 0, DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709);
    vctx1->VideoProcessorSetOutputColorSpace1(vp_.Get(), DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709);
  } else {
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE in{}, out{};
    in.RGB_Range = 0;
    out.YCbCr_Matrix = 1;
    out.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    vctx_->VideoProcessorSetStreamColorSpace(vp_.Get(), 0, &in);
    vctx_->VideoProcessorSetOutputColorSpace(vp_.Get(), &out);
  }
  RECT src{0, 0, srcW, srcH}, dst{0, 0, encW_, encH_};
  vctx_->VideoProcessorSetStreamSourceRect(vp_.Get(), 0, TRUE, &src);
  vctx_->VideoProcessorSetStreamDestRect(vp_.Get(), 0, TRUE, &dst);
  vctx_->VideoProcessorSetOutputTargetRect(vp_.Get(), TRUE, &dst);
  vctx_->VideoProcessorSetStreamFrameFormat(vp_.Get(), 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
  vctx_->VideoProcessorSetStreamAutoProcessingMode(vp_.Get(), 0, FALSE);
  if (Turned(vpRot)) {
    D3D11_VIDEO_PROCESSOR_ROTATION rot = D3D11_VIDEO_PROCESSOR_ROTATION_IDENTITY;
    if (vpRot == DXGI_MODE_ROTATION_ROTATE90) rot = D3D11_VIDEO_PROCESSOR_ROTATION_90;
    if (vpRot == DXGI_MODE_ROTATION_ROTATE180) rot = D3D11_VIDEO_PROCESSOR_ROTATION_180;
    if (vpRot == DXGI_MODE_ROTATION_ROTATE270) rot = D3D11_VIDEO_PROCESSOR_ROTATION_270;
    vctx_->VideoProcessorSetStreamRotation(vp_.Get(), 0, TRUE, rot);
  }

  int kbps;
  {
    std::lock_guard<std::mutex> lk(mu_);
    kbps = kbps_;
  }
  if (!enc_.Init(dev_.Get(), encW_, encH_, fps_, kbps)) {
    why = "No H.264 encoder available on this PC.";
    flags = hoststatus::NO_SIGNAL;
    if (all_) next = attempt == 0 ? 1 : 2;
    return false;
  }
  {
    std::lock_guard<std::mutex> lk(mu_);
    stats_.encoder = enc_.name() + (enc_.hardware() ? " (GPU)" : " (CPU)");
  }
  t0Us_ = NowUs();
  // where the pointer is now, in this picture (it may have been on another screen until now)
  CURSORINFO ci{sizeof(ci)};
  if (GetCursorInfo(&ci)) {
    curPos_ = POINT{ci.ptScreenPos.x - monRect_.left, ci.ptScreenPos.y - monRect_.top};
    curVisible_ = (ci.flags & CURSOR_SHOWING) != 0 && PtInRect(&monRect_, ci.ptScreenPos);
  }
  ptrOwner_ = -1;
  ptrTime_ = 0;
  waitOn_ = 0;
  pipelineOk_ = true;
  sink_->SetMonitorRect(monRect_);

  Writer w(msg::VIDEO_FORMAT);
  w.u8(1).u16((uint16_t)encW_).u16((uint16_t)encH_).u8((uint8_t)monIndex_);
  w.i32(monRect_.left).i32(monRect_.top).i32(deskW).i32(deskH);
  sink_->SendStream(std::move(w.b));
  if (all_) {
    int viaCpu = 0;
    for (auto& s : srcs_) {
      viaCpu += s.cpu ? 1 : 0;
      Log("capture:   screen at %ld,%ld %ldx%ld -> picture %d,%d%s%s", s.rect.left, s.rect.top, s.rect.right - s.rect.left,
          s.rect.bottom - s.rect.top, s.ox, s.oy, Turned(s.rot) ? ", turned" : "", s.cpu ? ", through the CPU" : "");
    }
    Log("capture: all %zu screens (%ld,%ld %dx%d, %d through the CPU) -> %dx%d @%d fps, %s", srcs_.size(),
        monRect_.left, monRect_.top, deskW, deskH, viaCpu, encW_, encH_, fps_, enc_.name().c_str());
  } else {
    Log("capture: monitor %d (%ld,%ld %dx%d%s) -> %dx%d @%d fps, %s", monIndex_, monRect_.left, monRect_.top, deskW,
        deskH, sel.virt ? ", virtual screen" : "", encW_, encH_, fps_, enc_.name().c_str());
  }
  return true;
}

// Copies a screen's new picture into capTex_: one screen as it comes (the video processor turns it), all screens
// each into its place. 1 = copied, 0 = nothing to copy, -1 = failed (start over).
int Streamer::TakePicture(Source& s, IDXGIResource* res) {
  ComPtr<ID3D11Texture2D> t;
  if (!res || FAILED(res->QueryInterface(IID_PPV_ARGS(&t)))) return 0;
  D3D11_TEXTURE2D_DESC d{};
  t->GetDesc(&d);
  if ((int)d.Width != s.texW || (int)d.Height != s.texH || d.Format != DXGI_FORMAT_B8G8R8A8_UNORM) {
    Log("capture: unexpected desktop texture %ux%u fmt %d", d.Width, d.Height, (int)d.Format);
    return -1;
  }
  if (!all_) {
    ctx_->CopyResource(capTex_.Get(), t.Get());
  } else if (!s.cpu) {
    ctx_->CopySubresourceRegion(capTex_.Get(), 0, (UINT)s.ox, (UINT)s.oy, 0, t.Get(), 0, nullptr);
  } else {
    s.ctx->CopyResource(s.staging.Get(), t.Get());
    D3D11_MAPPED_SUBRESOURCE m{};
    HRESULT hr = s.ctx->Map(s.staging.Get(), 0, D3D11_MAP_READ, 0, &m);
    if (FAILED(hr)) {
      Log("capture: Map failed 0x%08x", (unsigned)hr);
      return -1;
    }
    int w = s.rect.right - s.rect.left, h = s.rect.bottom - s.rect.top;
    const void* px = m.pData;
    UINT pitch = m.RowPitch;
    if (Turned(s.rot)) {
      s.turned.resize((size_t)w * h);
      Turn((const uint8_t*)m.pData, m.RowPitch, s.texW, s.texH, s.rot, s.turned.data(), w, h);
      px = s.turned.data();
      pitch = (UINT)w * 4;
    }
    D3D11_BOX box{(UINT)s.ox, (UINT)s.oy, 0, (UINT)(s.ox + w), (UINT)(s.oy + h), 1};
    ctx_->UpdateSubresource(capTex_.Get(), 0, &box, px, pitch, 0);
    s.ctx->Unmap(s.staging.Get(), 0);
  }
  s.have = true;
  haveContent_ = true;
  return 1;
}

bool Streamer::EncodeAndSend(bool forceKey) {
  Slot& s = ring_[ringIdx_];
  ringIdx_ = (ringIdx_ + 1) % ring_.size();
  D3D11_VIDEO_PROCESSOR_STREAM st{};
  st.Enable = TRUE;
  st.pInputSurface = inView_.Get();
  HRESULT hr = vctx_->VideoProcessorBlt(vp_.Get(), s.view.Get(), 0, 1, &st);
  if (FAILED(hr)) {
    Log("capture: VideoProcessorBlt failed 0x%08x", (unsigned)hr);
    return false;
  }
  std::vector<EncodedFrame> outs;
  uint64_t tsUs = NowUs();
  if (!enc_.Encode(s.tex.Get(), (int64_t)(tsUs - t0Us_) * 10, forceKey, outs)) return false;
  for (auto& f : outs) {
    sps_.Process(f.data);
    uint32_t id = nextFrameId_++;
    Writer w(msg::VIDEO_FRAME);
    w.b.reserve(f.data.size() + 16);
    w.u32(id).u8(f.key ? 1 : 0).u64(tsUs).raw(f.data);
    {
      std::lock_guard<std::mutex> lk(mu_);
      inflight_.push_back({id, NowMs()});
      stats_.frames++;
      stats_.bytes += f.data.size();
    }
    sink_->SendStream(std::move(w.b));
  }
  return true;
}

// ---------------------------------------------------------------- cursor
static uint32_t Fnv(const uint8_t* p, size_t n, uint32_t h = 2166136261u) {
  for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 16777619u;
  return h;
}

static void ConvertShape(const DXGI_OUTDUPL_POINTER_SHAPE_INFO& si, const uint8_t* buf, Bytes& rgba, int& w, int& h) {
  w = (int)si.Width;
  h = si.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME ? (int)si.Height / 2 : (int)si.Height;
  rgba.assign((size_t)w * h * 4, 0);
  std::vector<uint8_t> inv((size_t)w * h, 0);
  auto set = [&](int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    uint8_t* d = &rgba[((size_t)y * w + x) * 4];
    d[0] = r;
    d[1] = g;
    d[2] = b;
    d[3] = a;
  };
  if (si.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME) {
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        uint8_t mask = (uint8_t)(0x80 >> (x % 8));
        bool andBit = (buf[(size_t)y * si.Pitch + x / 8] & mask) != 0;
        bool xorBit = (buf[(size_t)(y + h) * si.Pitch + x / 8] & mask) != 0;
        if (!andBit && !xorBit) set(x, y, 0, 0, 0, 255);
        else if (!andBit && xorBit) set(x, y, 255, 255, 255, 255);
        else if (andBit && xorBit) inv[(size_t)y * w + x] = 1;
      }
    }
  } else {
    bool masked = si.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MASKED_COLOR;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        const uint8_t* s = buf + (size_t)y * si.Pitch + (size_t)x * 4;  // B G R A
        if (!masked) {
          set(x, y, s[2], s[1], s[0], s[3]);
        } else if (s[3] == 0) {
          set(x, y, s[2], s[1], s[0], 255);
        } else if (s[0] | s[1] | s[2]) {
          if (s[0] == 255 && s[1] == 255 && s[2] == 255) inv[(size_t)y * w + x] = 1;
          else set(x, y, s[2], s[1], s[0], 255);
        }
      }
    }
  }
  // "invert screen" pixels can't be reproduced on the phone: draw them black with a white halo
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
      if (inv[(size_t)y * w + x]) set(x, y, 0, 0, 0, 255);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      if (rgba[((size_t)y * w + x) * 4 + 3] != 0) continue;
      bool nearInv = false;
      for (int dy = -1; dy <= 1 && !nearInv; dy++)
        for (int dx = -1; dx <= 1 && !nearInv; dx++) {
          int xx = x + dx, yy = y + dy;
          if (xx >= 0 && yy >= 0 && xx < w && yy < h && inv[(size_t)yy * w + xx]) nearInv = true;
        }
      if (nearInv) set(x, y, 255, 255, 255, 255);
    }
  }
}

void Streamer::HandlePointer(size_t i, const DXGI_OUTDUPL_FRAME_INFO& fi) {
  if (fi.LastMouseUpdateTime.QuadPart == 0) return;
  Source& src = srcs_[i];
  bool shapeChanged = false;
  if (fi.PointerShapeBufferSize > 0) {
    shapeBuf_.resize(fi.PointerShapeBufferSize);
    UINT req = 0;
    DXGI_OUTDUPL_POINTER_SHAPE_INFO si{};
    if (SUCCEEDED(src.dupl->GetFramePointerShape((UINT)shapeBuf_.size(), shapeBuf_.data(), &req, &si))) {
      Shape s;
      int w = 0, h = 0;
      ConvertShape(si, shapeBuf_.data(), s.rgba, w, h);
      s.w = (uint16_t)w;
      s.h = (uint16_t)h;
      s.hx = (uint16_t)si.HotSpot.x;
      s.hy = (uint16_t)si.HotSpot.y;
      uint32_t hdr[3] = {(uint32_t)w, (uint32_t)h, (uint32_t)(si.HotSpot.x << 16 | si.HotSpot.y)};
      s.id = Fnv(s.rgba.data(), s.rgba.size(), Fnv((const uint8_t*)hdr, sizeof(hdr)));
      if (s.id == 0) s.id = 1;
      shapeChanged = s.id != shape_.id;
      shape_ = std::move(s);
      if (!sentShapes_.count(shape_.id) && shape_.w > 0 && shape_.h > 0) {
        Writer w2(msg::CURSOR_SHAPE);
        w2.u32(shape_.id).u16(shape_.w).u16(shape_.h).u16(shape_.hx).u16(shape_.hy).raw(shape_.rgba);
        sink_->SendStream(std::move(w2.b));
        sentShapes_.insert(shape_.id);
      }
    }
  }
  // All screens: each one says where the pointer is on it. One that has just lost it doesn't hide it while another
  // has it, and an older word doesn't move it back.
  bool here = fi.PointerPosition.Visible != FALSE;
  bool update = true;
  if (srcs_.size() > 1 && ptrOwner_ != (int)i) {
    if (!here) update = false;
    else if (curVisible_ && ptrTime_ > fi.LastMouseUpdateTime.QuadPart) update = false;
  }
  if (update) {
    if (here) {
      curPos_.x = src.ox + fi.PointerPosition.Position.x + shape_.hx;
      curPos_.y = src.oy + fi.PointerPosition.Position.y + shape_.hy;
    }
    curVisible_ = here;
    ptrOwner_ = (int)i;
    ptrTime_ = fi.LastMouseUpdateTime.QuadPart;
  }
  bool visible = curVisible_;
  bool moved = visible && (curPos_.x != lastSentPos_.x || curPos_.y != lastSentPos_.y);
  bool fromClient = moved && sink_->IsClientCursorPos(curPos_.x, curPos_.y);
  uint64_t now = NowMs();
  bool send = shapeChanged || visible != lastSentVisible_ || shape_.id != lastSentShape_;
  if (!send && moved && !fromClient && now - lastCursorSendMs_ >= 33) send = true;
  if (fromClient) lastSentPos_ = curPos_;  // client already knows this position
  if (send) {
    Writer w(msg::CURSOR_STATE);
    w.i32(curPos_.x).i32(curPos_.y).u8(visible ? 1 : 0).u32(shape_.id);
    sink_->SendStream(std::move(w.b));
    lastSentPos_ = curPos_;
    lastSentVisible_ = visible;
    lastSentShape_ = shape_.id;
    lastCursorSendMs_ = now;
  }
}

void Streamer::ResendCursor() {
  if (shape_.id && shape_.w && shape_.h) {
    Writer w2(msg::CURSOR_SHAPE);
    w2.u32(shape_.id).u16(shape_.w).u16(shape_.h).u16(shape_.hx).u16(shape_.hy).raw(shape_.rgba);
    sink_->SendStream(std::move(w2.b));
    sentShapes_.insert(shape_.id);
  }
  Writer w(msg::CURSOR_STATE);
  w.i32(curPos_.x).i32(curPos_.y).u8(curVisible_ ? 1 : 0).u32(shape_.id);
  sink_->SendStream(std::move(w.b));
  lastSentPos_ = curPos_;
  lastSentVisible_ = curVisible_;
  lastSentShape_ = shape_.id;
}

// ---------------------------------------------------------------- rate control
void Streamer::AdaptBitrate(uint64_t now) {
  if (now - lastAdaptMs_ < 500) return;
  uint64_t period = lastAdaptMs_ ? now - lastAdaptMs_ : 500;
  lastAdaptMs_ = now;
  uint64_t srtt, minRtt;
  uint32_t frames;
  int kbps, maxKbps;
  {
    std::lock_guard<std::mutex> lk(mu_);
    srtt = srtt_;
    minRtt = minRtt_;
    frames = stats_.frames;
    kbps = kbps_;
    maxKbps = maxKbps_;
  }
  uint32_t sent = frames - framesAtLastAdapt_;
  framesAtLastAdapt_ = frames;
  uint64_t blocked = blockedMs_;
  blockedMs_ = 0;
  if (minRtt == UINT64_MAX || !pipelineOk_) return;
  int next = kbps;
  bool congested = srtt > minRtt + 80 || blocked * 100 > period * 35;
  if (congested) {
    next = std::max(500, kbps * 3 / 4);
    lastDecreaseMs_ = now;
  } else if (sent >= 5 && srtt < minRtt + 30 && blocked * 100 < period * 5 && now - lastDecreaseMs_ > 3000) {
    next = std::min(maxKbps, kbps * 11 / 10 + 100);
  }
  if (next > maxKbps) next = maxKbps;
  if (next != kbps) {
    {
      std::lock_guard<std::mutex> lk(mu_);
      kbps_ = next;
    }
    enc_.SetBitrate(next);
  }
}

// ---------------------------------------------------------------- main loop
void Streamer::Run() {
  CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  MFStartup(MF_VERSION, MFSTARTUP_LITE);
  SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
  if (g_opt.systemMode) desk_.Sync();
  vscreen::Hold();  // a virtual screen put in while there's no screen stays while this stream runs

  bool havePending = false, pendingKey = true, everEncoded = false;
  int topOff = 0;
  uint64_t lastEncodeMs = 0, lastChangeMs = 0, pausedSince = 0, lastInitAttempt = 0, lastLoopMs = NowMs();
  uint64_t lastMonitorCheck = 0;

  while (!stop_) {
    bool paused, reinit, key, cursorResend;
    {
      std::lock_guard<std::mutex> lk(mu_);
      paused = paused_;
      reinit = reinitRequested_;
      reinitRequested_ = false;
      key = keyRequested_;
      keyRequested_ = false;
      cursorResend = cursorResend_;
      cursorResend_ = false;
    }
    uint64_t now = NowMs();
    uint64_t loopDelta = now - lastLoopMs;
    lastLoopMs = now;

    if (paused) {
      if (!pausedSince) pausedSince = now;
      if (pipelineOk_ && now - pausedSince > 3000) {
        Log("capture: paused, releasing GPU resources");
        Teardown();
      }
      std::unique_lock<std::mutex> lk(mu_);
      cv_.wait_for(lk, std::chrono::milliseconds(100), [&] { return stop_.load() || !paused_; });
      continue;
    }
    pausedSince = 0;

    if (g_opt.systemMode && desk_.Sync()) reinit = true;
    if (reinit && pipelineOk_) Teardown();
    if (!pipelineOk_) {
      if (now - lastInitAttempt < 400) {
        Sleep(40);
        continue;
      }
      lastInitAttempt = now;
      std::string why;
      uint8_t flags = 0;
      if (!InitPipeline(why, flags)) {
        SendStatus(flags, why);
        continue;
      }
      SendStatus(0, "");
      havePending = false;
      pendingKey = true;
      everEncoded = false;
      topOff = 0;
      cursorResend = true;
      std::lock_guard<std::mutex> lk(mu_);
      inflight_.clear();
    }
    if (key) pendingKey = true;
    if (cursorResend) ResendCursor();

    // A monitor switched off while it's shown, or the virtual screen taken away by its driver: Windows stops drawing
    // to that screen without telling the duplication, so it's looked for once a second. Without it the picture would
    // freeze; this way a virtual screen comes in (a new one, when the driver took the last one away).
    if (now - lastMonitorCheck >= 1000) {
      lastMonitorCheck = now;
      const char* gone = nullptr;
      for (auto& s : srcs_)
        if (s.dupl && !vscreen::HasMonitor(s.gdi.c_str()))
          gone = s.virt ? "the virtual screen went away" : "a shown screen's monitor is off or unplugged";
      if (gone) {
        Log("capture: %s -> reinit", gone);
        Teardown();
        lastInitAttempt = 0;
        continue;
      }
    }

    // Wait for a change on a screen (all screens: the one that changed last), then pick up the others' without
    // waiting.
    bool failed = false, waited = false;
    size_t n = srcs_.size(), first = waitOn_;
    UINT wait = (havePending || (pendingKey && haveContent_)) ? 4 : n > 1 ? 16 : 40;
    for (size_t k = 0; k < n; k++) {
      size_t i = (first + k) % n;
      Source& s = srcs_[i];
      if (!s.dupl) continue;  // test: shows another screen's picture
      DXGI_OUTDUPL_FRAME_INFO fi{};
      ComPtr<IDXGIResource> res;
      HRESULT hr = s.dupl->AcquireNextFrame(waited ? 0 : wait, &fi, &res);
      waited = true;
      if (hr == DXGI_ERROR_WAIT_TIMEOUT) continue;  // nothing changed there
      if (FAILED(hr)) {
        Log("capture: AcquireNextFrame 0x%08x -> reinit", (unsigned)hr);
        if (hr == DXGI_ERROR_ACCESS_LOST) lastInitAttempt = 0;
        failed = true;
        break;
      }
      if (fi.LastPresentTime.QuadPart != 0 || !s.have) {
        int took = TakePicture(s, res.Get());
        for (size_t m = 0; took > 0 && m < n; m++)
          if (srcs_[m].mirrorOf == (int)i && TakePicture(srcs_[m], res.Get()) < 0) took = -1;
        if (took < 0) {
          s.dupl->ReleaseFrame();
          failed = true;
          break;
        }
        if (took > 0) {
          havePending = true;
          lastChangeMs = now;
          topOff = 0;
        }
      }
      if (n > 1 && (fi.LastPresentTime.QuadPart != 0 || fi.LastMouseUpdateTime.QuadPart != 0)) waitOn_ = i;
      HandlePointer(i, fi);
      s.dupl->ReleaseFrame();
    }
    if (failed) {
      Teardown();
      continue;
    }

    now = NowMs();
    bool want = (havePending || pendingKey) && haveContent_;
    bool canSend = InflightCount() < (size_t)Window();
    if (want && !canSend) blockedMs_ += loopDelta;
    uint64_t interval = 1000 / (uint64_t)fps_;
    if (want && canSend && (pendingKey || now - lastEncodeMs >= interval)) {
      if (!EncodeAndSend(pendingKey)) {
        Teardown();
        continue;
      }
      pendingKey = false;
      havePending = false;
      everEncoded = true;
      lastEncodeMs = now;
      topOff = 0;
    } else if (!havePending && everEncoded && topOff < 6 && canSend && now - lastChangeMs >= 150 &&
               now - lastEncodeMs >= 120) {
      if (!EncodeAndSend(false)) {
        Teardown();
        continue;
      }
      topOff++;
      lastEncodeMs = now;
    }
    AdaptBitrate(now);
  }
  Teardown();
  vscreen::Release();
  MFShutdown();
  CoUninitialize();
}
