#pragma once
#include <d3d11.h>
#include <dxgi1_5.h>

#include <climits>
#include <functional>
#include <set>

#include "common.h"
#include "desktop.h"
#include "encoder.h"
#include "h264.h"

class StreamSink {
 public:
  virtual ~StreamSink() = default;
  virtual void SendStream(Bytes&& msg) = 0;            // high-priority, in order
  virtual bool IsClientCursorPos(int x, int y) = 0;    // true if the client just moved the pointer there
  virtual void SetMonitorRect(const RECT& r) = 0;      // streamed monitor in virtual-desktop pixels
};

constexpr int kAllScreens = -2;  // StreamParams::monitor: every screen at once

struct StreamParams {
  int monitor = -1;  // -1 = primary, kAllScreens = all of them in one picture
  int maxW = 1920, maxH = 1080;
  int maxFps = 60;
  int kbps = 0;  // 0 = adaptive
  int decW = 0, decH = 0;  // the largest picture the phone's decoder takes (0 = it didn't say)
};

// A screenshot: the stream's picture before it's made smaller, the way it is on the desktop (BGRX, rows top down,
// w * 4 bytes each), or why there is none.
struct Shot {
  int w = 0, h = 0;
  std::vector<uint32_t> px;
  uint8_t fail = 0;  // shotfail::* (protocol.h); 0 = px holds the picture
  std::string why;   // for the log and the phone
};
using ShotDone = std::function<void(Shot&&)>;

struct StreamStats {
  uint32_t frames = 0;
  uint64_t bytes = 0;
  int kbps = 0;
  int srttMs = 0;
  std::string encoder;
};

// Desktop Duplication -> D3D11 video processor (BGRA->NV12, scaling) -> MF H.264 -> sink.
// Frames are only produced when the screen changes; a few "top-off" frames refine quality
// after motion stops. At most Window() frames may be un-acknowledged.
// All screens: every monitor is duplicated and copied into one picture laid out as Windows arranges them (black
// where no screen is); that picture then goes the same way.
class Streamer {
 public:
  explicit Streamer(StreamSink* sink);
  ~Streamer();
  void Start(const StreamParams& p);
  void Stop();
  void Pause();
  void Resume();
  void OnAck(uint32_t frameId);
  void RequestKeyframe();
  void Configure(int maxW, int maxH, int maxFps, int kbps);
  void SelectMonitor(int idx);
  // A screenshot of what's shown, at full size: done gets it on the capture thread once there is a picture (3 s at
  // most), or here at once when no stream runs. Call from the thread that starts and stops the stream.
  void RequestShot(ShotDone done);
  StreamStats Stats();
  bool running() const { return th_.joinable(); }

 private:
  // One duplicated screen (one screen shown: just that one; all screens: one per screen).
  struct Source {
    ComPtr<IDXGIOutputDuplication> dupl;
    RECT rect{};                 // where it is on the desktop (virtual-desktop pixels)
    int texW = 0, texH = 0;      // the duplicated picture (as the screen scans it; turned screens come sideways)
    int ox = 0, oy = 0;          // all screens: its place in capTex_
    DXGI_MODE_ROTATION rot = DXGI_MODE_ROTATION_IDENTITY;
    bool cpu = false;            // all screens: on another graphics card, or turned: copied through the CPU
    ComPtr<ID3D11Device> dev;    // the device it is duplicated with (cpu: maybe its own graphics card's)
    ComPtr<ID3D11DeviceContext> ctx;
    ComPtr<ID3D11Texture2D> staging;
    std::vector<uint32_t> turned;  // cpu, turned screen: the picture the right way round
    bool have = false;           // its picture is in capTex_
    int mirrorOf = -1;           // test builds (--fake-screens): no duplication, shows that screen's picture
    std::wstring gdi;            // \\.\DISPLAYn
    bool virt = false;           // DeskLink's virtual screen (vdisplay.h)
  };

  void Run();
  bool InitPipeline(std::string& why, uint8_t& flags);
  bool BuildPipeline(std::string& why, uint8_t& flags, int attempt, int& next);
  void Teardown();
  int TakePicture(Source& s, IDXGIResource* res);
  bool EncodeAndSend(bool forceKey);
  void HandlePointer(size_t src, const DXGI_OUTDUPL_FRAME_INFO& fi);
  void ResendCursor();
  void SendStatus(uint8_t flags, const std::string& text);
  void AdaptBitrate(uint64_t now);
  void ServeShots(uint64_t now);
  bool GrabShot(Shot& out);
  size_t InflightCount();
  int Window();

  StreamSink* sink_;
  std::thread th_;
  std::atomic<bool> stop_{false};

  std::mutex mu_;
  std::condition_variable cv_;
  StreamParams params_;
  bool paused_ = false;
  bool reinitRequested_ = false;
  bool keyRequested_ = false;
  bool cursorResend_ = false;
  struct ShotWait {
    ShotDone done;
    uint64_t since;
  };
  std::vector<ShotWait> shots_;  // screenshots asked for, waiting for a picture
  struct Inflight {
    uint32_t id;
    uint64_t sentMs;
  };
  std::deque<Inflight> inflight_;
  uint64_t srtt_ = 0, minRtt_ = UINT64_MAX, minRttAt_ = 0;
  StreamStats stats_;

  // ---- capture thread only
  ComPtr<ID3D11Device> dev_;
  ComPtr<ID3D11DeviceContext> ctx_;
  std::vector<Source> srcs_;
  bool all_ = false;       // all screens in one picture
  size_t waitOn_ = 0;      // the screen to wait on for a change (the one that changed last)
  ComPtr<ID3D11Texture2D> capTex_;
  D3D11_TEXTURE2D_DESC capDesc_{};
  ComPtr<ID3D11Texture2D> shotTex_;  // capTex_ copied where the CPU can read it, for a screenshot
  ComPtr<ID3D11VideoDevice> vdev_;
  ComPtr<ID3D11VideoContext> vctx_;
  ComPtr<ID3D11VideoProcessorEnumerator> vpEnum_;
  ComPtr<ID3D11VideoProcessor> vp_;
  ComPtr<ID3D11VideoProcessorInputView> inView_;
  struct Slot {
    ComPtr<ID3D11Texture2D> tex;
    ComPtr<ID3D11VideoProcessorOutputView> view;
  };
  std::vector<Slot> ring_;
  size_t ringIdx_ = 0;
  H264Encoder enc_;
  h264::SpsFixer sps_;
  bool pipelineOk_ = false;
  bool haveContent_ = false;
  RECT monRect_{};
  int monIndex_ = 0;
  int encW_ = 0, encH_ = 0, fps_ = 60;
  uint32_t nextFrameId_ = 1;
  uint64_t t0Us_ = 0;
  int kbps_ = 8000, maxKbps_ = 20000;
  bool autoRate_ = true;
  uint64_t lastAdaptMs_ = 0, lastDecreaseMs_ = 0, blockedMs_ = 0;
  uint32_t framesAtLastAdapt_ = 0;

  // cursor
  Bytes shapeBuf_;
  std::set<uint32_t> sentShapes_;
  struct Shape {
    uint32_t id = 0;
    uint16_t w = 0, h = 0, hx = 0, hy = 0;
    Bytes rgba;
  } shape_;
  POINT curPos_{};
  bool curVisible_ = false;
  int ptrOwner_ = -1;      // all screens: the screen that last said where the pointer is
  int64_t ptrTime_ = 0;    // ...and when
  POINT lastSentPos_{INT_MIN, INT_MIN};
  bool lastSentVisible_ = false;
  uint32_t lastSentShape_ = 0;
  uint64_t lastCursorSendMs_ = 0;
  uint8_t statusFlags_ = 0xFF;
  std::string statusText_;
  int ghostsLogged_ = 0;  // screens left out because their monitor is gone (logged when it changes)
  DesktopFollower desk_;
};
