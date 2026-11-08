#include "encoder.h"

#include <codecapi.h>
#include <mferror.h>
#include <strmif.h>

#include <algorithm>
#include <chrono>

#include "h264.h"

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "mf.lib")
#pragma comment(lib, "strmiids.lib")

struct AsyncEncoderState {
  std::mutex m;
  std::condition_variable cv;
  int needInput = 0;
  int haveOutput = 0;
  bool error = false;
};

// Receives METransformNeedInput / METransformHaveOutput from an async (hardware) MFT.
class EventPump final : public IMFAsyncCallback {
 public:
  EventPump(std::shared_ptr<AsyncEncoderState> st, IMFMediaEventGenerator* gen) : st_(std::move(st)), gen_(gen) {}
  STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
    if (!ppv) return E_POINTER;
    if (riid == __uuidof(IUnknown) || riid == __uuidof(IMFAsyncCallback)) {
      *ppv = static_cast<IMFAsyncCallback*>(this);
      AddRef();
      return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
  }
  STDMETHODIMP_(ULONG) AddRef() override { return (ULONG)InterlockedIncrement(&ref_); }
  STDMETHODIMP_(ULONG) Release() override {
    ULONG r = (ULONG)InterlockedDecrement(&ref_);
    if (r == 0) delete this;
    return r;
  }
  STDMETHODIMP GetParameters(DWORD*, DWORD*) override { return E_NOTIMPL; }
  STDMETHODIMP Invoke(IMFAsyncResult* res) override {
    ComPtr<IMFMediaEvent> ev;
    HRESULT hr = gen_->EndGetEvent(res, &ev);
    if (FAILED(hr)) {
      if (!stop_) {
        std::lock_guard<std::mutex> lk(st_->m);
        st_->error = true;
      }
      st_->cv.notify_all();
      return S_OK;
    }
    MediaEventType type = MEUnknown;
    HRESULT status = S_OK;
    ev->GetType(&type);
    ev->GetStatus(&status);
    {
      std::lock_guard<std::mutex> lk(st_->m);
      if (type == METransformNeedInput) st_->needInput++;
      else if (type == METransformHaveOutput) st_->haveOutput++;
      else if (type == MEError) st_->error = true;
      if (FAILED(status)) st_->error = true;
    }
    st_->cv.notify_all();
    if (!stop_ && FAILED(gen_->BeginGetEvent(this, nullptr))) {
      std::lock_guard<std::mutex> lk(st_->m);
      st_->error = true;
    }
    return S_OK;
  }
  void Stop() { stop_ = true; }

 private:
  ~EventPump() = default;
  volatile LONG ref_ = 1;
  std::shared_ptr<AsyncEncoderState> st_;
  ComPtr<IMFMediaEventGenerator> gen_;
  std::atomic<bool> stop_{false};
};

H264Encoder::~H264Encoder() { Shutdown(); }

void H264Encoder::ReleaseMft() {
  if (mft_) {
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
  }
  if (pump_) pump_->Stop();
  if (mft_) {
    ComPtr<IMFShutdown> sd;
    if (SUCCEEDED(mft_.As(&sd))) sd->Shutdown();
    mft_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, 0);
  }
  if (pump_) {
    pump_->Release();
    pump_ = nullptr;
  }
  gen_.Reset();
  codec_.Reset();
  mft_.Reset();
  dm_.Reset();
  st_.reset();
}

void H264Encoder::Shutdown() {
  ReleaseMft();
  staging_.Reset();
  ctx_.Reset();
  dev_.Reset();
}

bool H264Encoder::Init(ID3D11Device* dev, int width, int height, int fps, int kbps, bool allowHardware) {
  Shutdown();
  dev_ = dev;
  dev_->GetImmediateContext(&ctx_);
  w_ = width;
  h_ = height;
  fps_ = std::max(1, fps);
  kbps_ = std::max(200, kbps);

  MFT_REGISTER_TYPE_INFO in{MFMediaType_Video, MFVideoFormat_NV12};
  MFT_REGISTER_TYPE_INFO out{MFMediaType_Video, MFVideoFormat_H264};

  if (allowHardware) {
    ComPtr<IMFAttributes> attr;
    ComPtr<IDXGIDevice> dxgiDev;
    ComPtr<IDXGIAdapter> adapter;
    DXGI_ADAPTER_DESC ad{};
    if (SUCCEEDED(dev_.As(&dxgiDev)) && SUCCEEDED(dxgiDev->GetAdapter(&adapter)) && SUCCEEDED(adapter->GetDesc(&ad)) &&
        SUCCEEDED(MFCreateAttributes(&attr, 1))) {
      attr->SetBlob(MFT_ENUM_ADAPTER_LUID, (const UINT8*)&ad.AdapterLuid, sizeof(LUID));
    }
    IMFActivate** acts = nullptr;
    UINT32 count = 0;
    HRESULT hr = MFTEnum2(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, &in, &out,
                          attr.Get(), &acts, &count);
    if (FAILED(hr) || count == 0) {
      hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, &in, &out, &acts,
                     &count);
    }
    bool ok = false;
    if (SUCCEEDED(hr)) {
      for (UINT32 i = 0; i < count; i++) {
        if (!ok) ok = TryInit(acts[i], true);
        acts[i]->Release();
      }
      CoTaskMemFree(acts);
    }
    if (ok) return true;
  }

  IMFActivate** acts = nullptr;
  UINT32 count = 0;
  bool ok = false;
  if (SUCCEEDED(MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER, &in, &out,
                          &acts, &count))) {
    for (UINT32 i = 0; i < count; i++) {
      if (!ok) ok = TryInit(acts[i], false);
      acts[i]->Release();
    }
    CoTaskMemFree(acts);
  }
  if (!ok) Log("encoder: no usable H.264 encoder found");
  return ok;
}

bool H264Encoder::TryInit(IMFActivate* act, bool hw) {
  ReleaseMft();
  hw_ = hw;
  async_ = false;
  WCHAR* nm = nullptr;
  UINT32 nmLen = 0;
  name_ = "?";
  if (SUCCEEDED(act->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &nm, &nmLen))) {
    name_ = W2U(nm);
    CoTaskMemFree(nm);
  }
  HRESULT hr = act->ActivateObject(IID_PPV_ARGS(&mft_));
  if (FAILED(hr)) {
    Log("encoder: activate %s failed 0x%08x", name_.c_str(), (unsigned)hr);
    return false;
  }
  ComPtr<IMFAttributes> a;
  if (SUCCEEDED(mft_->GetAttributes(&a)) && a) {
    UINT32 isAsync = 0;
    if (SUCCEEDED(a->GetUINT32(MF_TRANSFORM_ASYNC, &isAsync)) && isAsync) {
      a->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
      async_ = true;
    }
    a->SetUINT32(MF_LOW_LATENCY, TRUE);
  }
  if (hw) {
    UINT token = 0;
    if (FAILED(MFCreateDXGIDeviceManager(&token, &dm_)) || FAILED(dm_->ResetDevice(dev_.Get(), token))) {
      ReleaseMft();
      return false;
    }
    hr = mft_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, (ULONG_PTR)dm_.Get());
    if (FAILED(hr)) {
      Log("encoder: %s rejected D3D manager 0x%08x", name_.c_str(), (unsigned)hr);
      ReleaseMft();
      return false;
    }
  }
  mft_.As(&codec_);
  DWORD inIds[1] = {0}, outIds[1] = {0};
  hr = mft_->GetStreamIDs(1, inIds, 1, outIds);
  if (SUCCEEDED(hr)) {
    inId_ = inIds[0];
    outId_ = outIds[0];
  } else {
    inId_ = outId_ = 0;
  }

  ApplyCodecSettings();
  bool typesOk = false;
  for (uint32_t profile : {(uint32_t)eAVEncH264VProfile_High, (uint32_t)eAVEncH264VProfile_Main,
                           (uint32_t)eAVEncH264VProfile_Base}) {
    if (ConfigureTypes(profile)) {
      typesOk = true;
      break;
    }
  }
  if (!typesOk) {
    Log("encoder: %s rejected media types", name_.c_str());
    ReleaseMft();
    return false;
  }
  ApplyCodecSettings();

  MFT_OUTPUT_STREAM_INFO osi{};
  mft_->GetOutputStreamInfo(outId_, &osi);
  providesSamples_ = (osi.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
  outBufSize_ = std::max<DWORD>(osi.cbSize, (DWORD)(w_ * h_ * 3 / 2));

  mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
  mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);

  if (async_) {
    if (FAILED(mft_.As(&gen_))) {
      ReleaseMft();
      return false;
    }
    st_ = std::make_shared<AsyncEncoderState>();
    pump_ = new EventPump(st_, gen_.Get());
    if (FAILED(gen_->BeginGetEvent(pump_, nullptr))) {
      ReleaseMft();
      return false;
    }
  } else if (!hw_) {
    D3D11_TEXTURE2D_DESC td{};
    td.Width = (UINT)w_;
    td.Height = (UINT)h_;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_NV12;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_STAGING;
    td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    if (FAILED(dev_->CreateTexture2D(&td, nullptr, &staging_))) {
      ReleaseMft();
      return false;
    }
  }
  Log("encoder: using %s (%s, %s) %dx%d@%d %d kbps", name_.c_str(), hw_ ? "hardware" : "software",
      async_ ? "async" : "sync", w_, h_, fps_, kbps_);
  return true;
}

bool H264Encoder::ConfigureTypes(uint32_t profile) {
  ComPtr<IMFMediaType> ot;
  MFCreateMediaType(&ot);
  ot->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  ot->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
  ot->SetUINT32(MF_MT_AVG_BITRATE, (UINT32)kbps_ * 1000);
  MFSetAttributeSize(ot.Get(), MF_MT_FRAME_SIZE, (UINT32)w_, (UINT32)h_);
  MFSetAttributeRatio(ot.Get(), MF_MT_FRAME_RATE, (UINT32)fps_, 1);
  MFSetAttributeRatio(ot.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
  ot->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  ot->SetUINT32(MF_MT_MPEG2_PROFILE, profile);
  ot->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
  ot->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
  ot->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709);
  ot->SetUINT32(MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709);
  HRESULT hr = mft_->SetOutputType(outId_, ot.Get(), 0);
  if (FAILED(hr)) return false;

  ComPtr<IMFMediaType> it;
  MFCreateMediaType(&it);
  it->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  it->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
  MFSetAttributeSize(it.Get(), MF_MT_FRAME_SIZE, (UINT32)w_, (UINT32)h_);
  MFSetAttributeRatio(it.Get(), MF_MT_FRAME_RATE, (UINT32)fps_, 1);
  MFSetAttributeRatio(it.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
  it->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  it->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
  it->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
  if (!hw_) it->SetUINT32(MF_MT_DEFAULT_STRIDE, (UINT32)w_);
  hr = mft_->SetInputType(inId_, it.Get(), 0);
  if (SUCCEEDED(hr)) return true;

  // Some MFTs only accept one of their own advertised input types.
  for (DWORD i = 0;; i++) {
    ComPtr<IMFMediaType> cand;
    if (FAILED(mft_->GetInputAvailableType(inId_, i, &cand))) break;
    GUID sub{};
    cand->GetGUID(MF_MT_SUBTYPE, &sub);
    if (sub != MFVideoFormat_NV12) continue;
    MFSetAttributeSize(cand.Get(), MF_MT_FRAME_SIZE, (UINT32)w_, (UINT32)h_);
    MFSetAttributeRatio(cand.Get(), MF_MT_FRAME_RATE, (UINT32)fps_, 1);
    if (SUCCEEDED(mft_->SetInputType(inId_, cand.Get(), 0))) return true;
  }
  return false;
}

void H264Encoder::ApplyCodecSettings() {
  if (!codec_) return;
  auto setU4 = [&](const GUID& g, ULONG x) {
    VARIANT v;
    VariantInit(&v);
    v.vt = VT_UI4;
    v.ulVal = x;
    return codec_->SetValue(&g, &v);
  };
  auto setBool = [&](const GUID& g, bool b) {
    VARIANT v;
    VariantInit(&v);
    v.vt = VT_BOOL;
    v.boolVal = b ? VARIANT_TRUE : VARIANT_FALSE;
    return codec_->SetValue(&g, &v);
  };
  setBool(CODECAPI_AVLowLatencyMode, true);
  setU4(CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
  setU4(CODECAPI_AVEncCommonMeanBitRate, (ULONG)kbps_ * 1000);
  setU4(CODECAPI_AVEncMPVDefaultBPictureCount, 0);
  setU4(CODECAPI_AVEncMPVGOPSize, (ULONG)fps_ * 300);  // keyframes only on demand
  // ~250 ms VBV keeps single frames (keyframes) from monopolising the link
  setU4(CODECAPI_AVEncCommonBufferSize, (ULONG)kbps_ * 1000 / 4);
  if (!hw_) setU4(CODECAPI_AVEncCommonQualityVsSpeed, 20);
}

void H264Encoder::ForceKeyframe() {
  if (!codec_) return;
  VARIANT v;
  VariantInit(&v);
  v.vt = VT_UI4;
  v.ulVal = 1;
  codec_->SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &v);
}

void H264Encoder::SetBitrate(int kbps) {
  kbps_ = std::max(200, kbps);
  if (!codec_) return;
  VARIANT v;
  VariantInit(&v);
  v.vt = VT_UI4;
  v.ulVal = (ULONG)kbps_ * 1000;
  codec_->SetValue(&CODECAPI_AVEncCommonMeanBitRate, &v);
}

bool H264Encoder::MakeGpuSample(ID3D11Texture2D* nv12, int64_t ts, ComPtr<IMFSample>& s) {
  ComPtr<IMFMediaBuffer> buf;
  if (FAILED(MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), nv12, 0, FALSE, &buf))) return false;
  ComPtr<IMF2DBuffer> b2;
  if (SUCCEEDED(buf.As(&b2))) {
    DWORD len = 0;
    if (SUCCEEDED(b2->GetContiguousLength(&len))) buf->SetCurrentLength(len);
  }
  if (FAILED(MFCreateSample(&s))) return false;
  s->AddBuffer(buf.Get());
  s->SetSampleTime(ts);
  s->SetSampleDuration(10000000LL / fps_);
  return true;
}

bool H264Encoder::MakeCpuSample(ID3D11Texture2D* nv12, int64_t ts, ComPtr<IMFSample>& s) {
  ctx_->CopyResource(staging_.Get(), nv12);
  D3D11_MAPPED_SUBRESOURCE mr{};
  if (FAILED(ctx_->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mr))) return false;
  DWORD size = (DWORD)(w_ * h_ * 3 / 2);
  ComPtr<IMFMediaBuffer> buf;
  if (FAILED(MFCreateMemoryBuffer(size, &buf))) {
    ctx_->Unmap(staging_.Get(), 0);
    return false;
  }
  BYTE* dst = nullptr;
  buf->Lock(&dst, nullptr, nullptr);
  const uint8_t* src = (const uint8_t*)mr.pData;
  for (int y = 0; y < h_; y++) memcpy(dst + (size_t)y * w_, src + (size_t)y * mr.RowPitch, (size_t)w_);
  const uint8_t* uv = src + (size_t)mr.RowPitch * h_;
  for (int y = 0; y < h_ / 2; y++) memcpy(dst + (size_t)w_ * h_ + (size_t)y * w_, uv + (size_t)y * mr.RowPitch, (size_t)w_);
  buf->Unlock();
  buf->SetCurrentLength(size);
  ctx_->Unmap(staging_.Get(), 0);
  if (FAILED(MFCreateSample(&s))) return false;
  s->AddBuffer(buf.Get());
  s->SetSampleTime(ts);
  s->SetSampleDuration(10000000LL / fps_);
  return true;
}

bool H264Encoder::ProcessOneOutput(std::vector<EncodedFrame>& out, bool& needMore) {
  needMore = false;
  MFT_OUTPUT_DATA_BUFFER ob{};
  ob.dwStreamID = outId_;
  ComPtr<IMFSample> own;
  if (!providesSamples_) {
    ComPtr<IMFMediaBuffer> b;
    if (FAILED(MFCreateSample(&own)) || FAILED(MFCreateMemoryBuffer(outBufSize_, &b))) return false;
    own->AddBuffer(b.Get());
    ob.pSample = own.Get();
  }
  DWORD status = 0;
  HRESULT hr = mft_->ProcessOutput(0, 1, &ob, &status);
  if (ob.pEvents) {
    ob.pEvents->Release();
    ob.pEvents = nullptr;
  }
  if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
    needMore = true;
    return true;
  }
  if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
    ComPtr<IMFMediaType> t;
    if (SUCCEEDED(mft_->GetOutputAvailableType(outId_, 0, &t))) mft_->SetOutputType(outId_, t.Get(), 0);
    return true;
  }
  if (FAILED(hr)) {
    Log("encoder: ProcessOutput failed 0x%08x", (unsigned)hr);
    if (providesSamples_ && ob.pSample) ob.pSample->Release();
    return false;
  }
  IMFSample* s = ob.pSample;
  if (s) {
    ComPtr<IMFMediaBuffer> buf;
    if (SUCCEEDED(s->ConvertToContiguousBuffer(&buf))) {
      BYTE* p = nullptr;
      DWORD len = 0;
      if (SUCCEEDED(buf->Lock(&p, nullptr, &len))) {
        EncodedFrame f;
        f.data.assign(p, p + len);
        buf->Unlock();
        UINT32 clean = 0;
        s->GetUINT32(MFSampleExtension_CleanPoint, &clean);
        f.key = clean != 0 || h264::ContainsIdr(f.data);
        if (!f.data.empty()) out.push_back(std::move(f));
      }
    }
    if (providesSamples_) s->Release();
  }
  return true;
}

bool H264Encoder::DrainAsync(std::vector<EncodedFrame>& out) {
  for (;;) {
    {
      std::lock_guard<std::mutex> lk(st_->m);
      if (st_->error) return false;
      if (st_->haveOutput <= 0) return true;
      st_->haveOutput--;
    }
    bool needMore = false;
    if (!ProcessOneOutput(out, needMore)) return false;
  }
}

bool H264Encoder::Encode(ID3D11Texture2D* nv12, int64_t ts100ns, bool forceKey, std::vector<EncodedFrame>& out) {
  if (!mft_) return false;
  using namespace std::chrono;
  if (async_) {
    if (!DrainAsync(out)) return false;
    {
      std::unique_lock<std::mutex> lk(st_->m);
      if (!st_->cv.wait_for(lk, milliseconds(500), [&] { return st_->needInput > 0 || st_->haveOutput > 0 || st_->error; })) {
        Log("encoder: timed out waiting for NeedInput");
        return false;
      }
    }
    if (!DrainAsync(out)) return false;
    {
      std::unique_lock<std::mutex> lk(st_->m);
      if (!st_->cv.wait_for(lk, milliseconds(500), [&] { return st_->needInput > 0 || st_->error; })) return false;
      if (st_->error) return false;
      st_->needInput--;
    }
    ComPtr<IMFSample> s;
    if (!MakeGpuSample(nv12, ts100ns, s)) return false;
    if (forceKey) ForceKeyframe();
    HRESULT hr = mft_->ProcessInput(inId_, s.Get(), 0);
    if (FAILED(hr)) {
      Log("encoder: ProcessInput failed 0x%08x", (unsigned)hr);
      return false;
    }
    {
      std::unique_lock<std::mutex> lk(st_->m);
      st_->cv.wait_for(lk, milliseconds(60), [&] { return st_->haveOutput > 0 || st_->error; });
    }
    return DrainAsync(out);
  }

  ComPtr<IMFSample> s;
  bool ok = hw_ ? MakeGpuSample(nv12, ts100ns, s) : MakeCpuSample(nv12, ts100ns, s);
  if (!ok) return false;
  if (forceKey) ForceKeyframe();
  HRESULT hr = mft_->ProcessInput(inId_, s.Get(), 0);
  if (hr == MF_E_NOTACCEPTING) {
    bool needMore = false;
    while (!needMore)
      if (!ProcessOneOutput(out, needMore)) return false;
    hr = mft_->ProcessInput(inId_, s.Get(), 0);
  }
  if (FAILED(hr)) {
    Log("encoder: ProcessInput failed 0x%08x", (unsigned)hr);
    return false;
  }
  bool needMore = false;
  for (int guard = 0; !needMore && guard < 16; guard++)
    if (!ProcessOneOutput(out, needMore)) return false;
  return true;
}
