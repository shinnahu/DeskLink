#pragma once
#include "common.h"

#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <strmif.h>
#include <codecapi.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

struct EncodedFrame {
  Bytes data;  // Annex-B
  bool key = false;
};

struct AsyncEncoderState;
class EventPump;

// Media Foundation H.264 encoder: hardware MFT (GPU texture input) with a
// software MFT fallback (system-memory NV12). Low-latency CBR, no B-frames.
class H264Encoder {
 public:
  H264Encoder() = default;
  ~H264Encoder();
  H264Encoder(const H264Encoder&) = delete;
  H264Encoder& operator=(const H264Encoder&) = delete;

  bool Init(ID3D11Device* dev, int width, int height, int fps, int kbps, bool allowHardware = true);
  void Shutdown();
  // nv12 must not be modified until a later Encode() call returned.
  bool Encode(ID3D11Texture2D* nv12, int64_t ts100ns, bool forceKey, std::vector<EncodedFrame>& out);
  void SetBitrate(int kbps);

  bool hardware() const { return hw_; }
  const std::string& name() const { return name_; }

 private:
  bool TryInit(IMFActivate* act, bool hw);
  bool ConfigureTypes(uint32_t profile);
  void ApplyCodecSettings();
  void ForceKeyframe();
  bool ProcessOneOutput(std::vector<EncodedFrame>& out, bool& needMore);
  bool DrainAsync(std::vector<EncodedFrame>& out);
  bool MakeGpuSample(ID3D11Texture2D* nv12, int64_t ts, ComPtr<IMFSample>& s);
  bool MakeCpuSample(ID3D11Texture2D* nv12, int64_t ts, ComPtr<IMFSample>& s);
  void ReleaseMft();

  ComPtr<ID3D11Device> dev_;
  ComPtr<ID3D11DeviceContext> ctx_;
  ComPtr<IMFTransform> mft_;
  ComPtr<ICodecAPI> codec_;
  ComPtr<IMFDXGIDeviceManager> dm_;
  ComPtr<IMFMediaEventGenerator> gen_;
  ComPtr<ID3D11Texture2D> staging_;
  std::shared_ptr<AsyncEncoderState> st_;
  EventPump* pump_ = nullptr;
  DWORD inId_ = 0, outId_ = 0;
  bool hw_ = false, async_ = false, providesSamples_ = false;
  DWORD outBufSize_ = 0;
  int w_ = 0, h_ = 0, fps_ = 60, kbps_ = 8000;
  std::string name_;
};
