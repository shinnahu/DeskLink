#include "audio.h"

#include <audioclient.h>
#include <mfapi.h>
#include <mferror.h>
#include <mftransform.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
#include <propsys.h>
#include <wrl/client.h>

#include <algorithm>
#include <cmath>

#include "protocol.h"

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")

using Microsoft::WRL::ComPtr;

namespace {

constexpr int kRate = 48000;                 // asked of Windows, which converts; else the mix format's own rate
constexpr int kChannels = 2;                 // what the phone gets
constexpr int kFrame = 1024;                 // samples per AAC frame
constexpr UINT32 kAacBytesPerSec = 16000;    // 128 kbps
constexpr uint64_t kQuietMs = 1000;          // this long without sound: nothing more is sent until there is some
constexpr uint64_t kStoppedMs = 50;          // no sound handed out this long: Windows stopped (nothing plays)
constexpr REFERENCE_TIME kBuffer = 2000000;  // 200 ms of loopback buffer (100 ns units)

// KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, without ksmedia.h
const GUID kFloat = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
// PKEY_Device_FriendlyName, without functiondiscoverykeys_devpkey.h
const PROPERTYKEY kFriendlyName = {{0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}}, 14};

std::wstring DeviceName(IMMDevice* dev) {
  ComPtr<IPropertyStore> ps;
  std::wstring name;
  if (SUCCEEDED(dev->OpenPropertyStore(STGM_READ, &ps))) {
    PROPVARIANT v;
    PropVariantInit(&v);
    if (SUCCEEDED(ps->GetValue(kFriendlyName, &v)) && v.vt == VT_LPWSTR && v.pwszVal) name = v.pwszVal;
    PropVariantClear(&v);
  }
  return name;
}

HRESULT DefaultOutput(IMMDeviceEnumerator* en, ComPtr<IMMDevice>& dev, std::wstring& id) {
  dev.Reset();
  HRESULT hr = en->GetDefaultAudioEndpoint(eRender, eConsole, &dev);
  if (FAILED(hr)) return hr;
  LPWSTR s = nullptr;
  if (SUCCEEDED(dev->GetId(&s)) && s) {
    id = s;
    CoTaskMemFree(s);
  }
  return S_OK;
}

// What the loopback hands out, and how it becomes 16-bit stereo
struct Loopback {
  ComPtr<IAudioClient> client;
  ComPtr<IAudioCaptureClient> cap;
  int rate = 0;
  int channels = 0;      // as handed out
  bool isFloat = false;  // 32-bit float; else integer in `bytes` bytes (the valid bits at the top)
  int bytes = 2;
  bool converted = false;  // Windows converts to 16-bit stereo itself
  float gainL[32] = {}, gainR[32] = {};
};

// Each channel's share of left and right, by the speaker it feeds (the mask's set bits, in order)
void SetGains(Loopback& lb, DWORD mask) {
  for (int c = 0; c < lb.channels && c < 32; c++) {
    DWORD bit = 0;
    if (mask) {
      bit = mask & (~mask + 1);  // the lowest set bit
      mask &= mask - 1;
    }
    float l, r;
    switch (bit) {
      case SPEAKER_FRONT_LEFT: l = 1.f, r = 0.f; break;
      case SPEAKER_FRONT_RIGHT: l = 0.f, r = 1.f; break;
      case SPEAKER_FRONT_CENTER: case SPEAKER_BACK_CENTER: case SPEAKER_TOP_CENTER:
      case SPEAKER_TOP_FRONT_CENTER: case SPEAKER_TOP_BACK_CENTER: l = r = 0.707f; break;
      case SPEAKER_LOW_FREQUENCY: l = r = 0.f; break;
      case SPEAKER_BACK_LEFT: case SPEAKER_SIDE_LEFT: case SPEAKER_FRONT_LEFT_OF_CENTER:
      case SPEAKER_TOP_FRONT_LEFT: case SPEAKER_TOP_BACK_LEFT: l = 0.707f, r = 0.f; break;
      case SPEAKER_BACK_RIGHT: case SPEAKER_SIDE_RIGHT: case SPEAKER_FRONT_RIGHT_OF_CENTER:
      case SPEAKER_TOP_FRONT_RIGHT: case SPEAKER_TOP_BACK_RIGHT: l = 0.f, r = 0.707f; break;
      default:  // no mask: the first two are left and right, the others go to both
        l = c == 0 ? 1.f : c == 1 ? 0.f : 0.5f;
        r = c == 1 ? 1.f : c == 0 ? 0.f : 0.5f;
    }
    if (lb.channels == 1) l = r = 1.f;
    lb.gainL[c] = l;
    lb.gainR[c] = r;
  }
}

HRESULT Fail(std::string& problem, const char* why, HRESULT hr) {
  problem = why;
  return hr;
}

// Opens the loopback of `dev`: first as 48 kHz 16-bit stereo converted by Windows, else in the mix format itself
// (when its rate suits AAC). problem says why it didn't open.
HRESULT OpenLoopback(IMMDevice* dev, Loopback& lb, std::string& problem) {
  ComPtr<IAudioClient> c;
  HRESULT hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)c.GetAddressOf());
  if (FAILED(hr)) return Fail(problem, "can't use the sound output", hr);
  WAVEFORMATEX want{};
  want.wFormatTag = WAVE_FORMAT_PCM;
  want.nChannels = kChannels;
  want.nSamplesPerSec = kRate;
  want.wBitsPerSample = 16;
  want.nBlockAlign = kChannels * 2;
  want.nAvgBytesPerSec = kRate * want.nBlockAlign;
  hr = c->Initialize(AUDCLNT_SHAREMODE_SHARED,
                     AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                         AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                     kBuffer, 0, &want, nullptr);
  if (SUCCEEDED(hr)) {
    lb.rate = kRate;
    lb.channels = kChannels;
    lb.isFloat = false;
    lb.bytes = 2;
    lb.converted = true;
    SetGains(lb, SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT);
  } else {
    c.Reset();
    hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)c.GetAddressOf());
    if (FAILED(hr)) return Fail(problem, "can't use the sound output", hr);
    WAVEFORMATEX* mix = nullptr;
    hr = c->GetMixFormat(&mix);
    if (FAILED(hr)) return Fail(problem, "the sound output has no format", hr);
    lb.rate = (int)mix->nSamplesPerSec;
    lb.channels = mix->nChannels;
    lb.bytes = mix->wBitsPerSample / 8;
    lb.isFloat = mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT;
    DWORD mask = 0;
    if (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE && mix->cbSize >= 22) {
      auto* x = (WAVEFORMATEXTENSIBLE*)mix;
      lb.isFloat = x->SubFormat == kFloat;
      mask = x->dwChannelMask;
    }
    bool ok = (lb.rate == 48000 || lb.rate == 44100) && lb.channels >= 1 && lb.channels <= 32 &&
              (lb.isFloat ? lb.bytes == 4 : lb.bytes >= 2 && lb.bytes <= 4);
    if (ok) hr = c->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, kBuffer, 0, mix, nullptr);
    char why[96];
    snprintf(why, sizeof(why), "its format (%d Hz, %d channels, %d-bit%s) isn't usable", lb.rate, lb.channels,
             lb.bytes * 8, lb.isFloat ? " float" : "");
    CoTaskMemFree(mix);
    if (!ok) return Fail(problem, why, E_NOTIMPL);
    if (FAILED(hr)) return Fail(problem, "Windows didn't let it record the sound output", hr);
    lb.converted = false;
    SetGains(lb, mask);
  }
  hr = c->GetService(IID_PPV_ARGS(&lb.cap));
  if (SUCCEEDED(hr)) hr = c->Start();
  if (FAILED(hr)) return Fail(problem, "Windows didn't start recording the sound output", hr);
  lb.client = c;
  return S_OK;
}

float SampleAt(const BYTE* p, const Loopback& lb) {
  if (lb.isFloat) {
    float v;
    memcpy(&v, p, 4);
    return v;
  }
  switch (lb.bytes) {
    case 2: {
      int16_t v;
      memcpy(&v, p, 2);
      return v / 32768.f;
    }
    case 3: return (int32_t)((uint32_t)p[0] << 8 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 24) / 2147483648.f;
    default: {
      int32_t v;
      memcpy(&v, p, 4);
      return v / 2147483648.f;
    }
  }
}

// Appends `frames` frames as 16-bit stereo; loud: some of it is above the quietest few steps (not silence)
void Append(const Loopback& lb, const BYTE* data, UINT32 frames, std::vector<int16_t>& pcm, bool& loud) {
  size_t at = pcm.size();
  if (lb.converted) {
    pcm.resize(at + frames * 2);
    memcpy(pcm.data() + at, data, frames * 4);
  } else {
    pcm.reserve(at + frames * 2);
    const int stride = lb.channels * lb.bytes;
    for (UINT32 f = 0; f < frames; f++) {
      const BYTE* p = data + (size_t)f * stride;
      float l = 0.f, r = 0.f;
      for (int c = 0; c < lb.channels && c < 32; c++) {
        float s = SampleAt(p + c * lb.bytes, lb);
        l += s * lb.gainL[c];
        r += s * lb.gainR[c];
      }
      pcm.push_back((int16_t)std::lrintf(std::clamp(l, -1.f, 1.f) * 32767.f));
      pcm.push_back((int16_t)std::lrintf(std::clamp(r, -1.f, 1.f) * 32767.f));
    }
  }
  for (size_t i = at; i < pcm.size() && !loud; i++)
    if (pcm[i] > 3 || pcm[i] < -3) loud = true;
}

// What the capture did lately, for the log
struct Counts {
  uint32_t packets = 0, silent = 0;  // handed out by Windows, and those it marked silent
  uint32_t loudTicks = 0, sent = 0;  // looks with sound in them, AAC frames sent
};

// Takes everything the loopback has. got: something came; false: the output went away.
bool Read(Loopback& lb, std::vector<int16_t>& pcm, bool& loud, bool& got, Counts& counts) {
  for (;;) {
    UINT32 n = 0;
    if (FAILED(lb.cap->GetNextPacketSize(&n))) return false;
    if (n == 0) return true;
    BYTE* data = nullptr;
    UINT32 frames = 0;
    DWORD flags = 0;
    if (FAILED(lb.cap->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) return false;
    got = true;
    counts.packets++;
    if (flags & AUDCLNT_BUFFERFLAGS_SILENT) counts.silent++;
    if (flags & AUDCLNT_BUFFERFLAGS_SILENT) pcm.insert(pcm.end(), (size_t)frames * 2, 0);
    else Append(lb, data, frames, pcm, loud);
    lb.cap->ReleaseBuffer(frames);
  }
}

// Windows' AAC encoder (Media Foundation): 16-bit PCM in, one raw AAC-LC frame per output
class AacEncoder {
 public:
  using Out = std::function<void(const BYTE*, DWORD, LONGLONG)>;  // a frame, and its time (100 ns)

  bool Init(int rate, int channels) {
    rate_ = rate;
    channels_ = channels;
    MFT_REGISTER_TYPE_INFO in{MFMediaType_Audio, MFAudioFormat_PCM}, out{MFMediaType_Audio, MFAudioFormat_AAC};
    IMFActivate** acts = nullptr;
    UINT32 n = 0;
    HRESULT hr = MFTEnumEx(MFT_CATEGORY_AUDIO_ENCODER, MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT |
                           MFT_ENUM_FLAG_SORTANDFILTER, &in, &out, &acts, &n);
    if (FAILED(hr) || n == 0) return false;
    hr = acts[0]->ActivateObject(IID_PPV_ARGS(&mft_));
    for (UINT32 i = 0; i < n; i++) acts[i]->Release();
    CoTaskMemFree(acts);
    if (FAILED(hr)) return false;
    ComPtr<IMFMediaType> it, ot;
    if (FAILED(MFCreateMediaType(&it)) || FAILED(MFCreateMediaType(&ot))) return false;
    it->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    it->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    it->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    it->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
    it->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
    it->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, channels * 2);
    it->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, rate * channels * 2);
    ot->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    ot->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC);
    ot->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    ot->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
    ot->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
    ot->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, kAacBytesPerSec);
    ot->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0);  // raw frames, no ADTS headers
    // input first, then output; some versions want the other order
    if (FAILED(mft_->SetInputType(0, it.Get(), 0)) || FAILED(mft_->SetOutputType(0, ot.Get(), 0))) {
      if (FAILED(mft_->SetOutputType(0, ot.Get(), 0)) || FAILED(mft_->SetInputType(0, it.Get(), 0))) return false;
    }
    MFT_OUTPUT_STREAM_INFO osi{};
    mft_->GetOutputStreamInfo(0, &osi);
    outSize_ = osi.cbSize ? osi.cbSize : 8192;
    provides_ = (osi.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    return true;
  }

  // Interleaved 16-bit samples, `frames` of them per channel, starting at `time` (100 ns)
  bool Encode(const int16_t* pcm, size_t frames, LONGLONG time, const Out& out) {
    ComPtr<IMFSample> s;
    ComPtr<IMFMediaBuffer> b;
    DWORD size = (DWORD)(frames * channels_ * 2);
    if (FAILED(MFCreateSample(&s)) || FAILED(MFCreateMemoryBuffer(size, &b))) return false;
    BYTE* p = nullptr;
    if (FAILED(b->Lock(&p, nullptr, nullptr))) return false;
    memcpy(p, pcm, size);
    b->Unlock();
    b->SetCurrentLength(size);
    s->AddBuffer(b.Get());
    s->SetSampleTime(time);
    s->SetSampleDuration((LONGLONG)frames * 10000000 / rate_);
    HRESULT hr = mft_->ProcessInput(0, s.Get(), 0);
    if (hr == MF_E_NOTACCEPTING) {
      if (!Drain(out)) return false;
      hr = mft_->ProcessInput(0, s.Get(), 0);
    }
    if (FAILED(hr)) {
      Log("audio: the encoder took no sound (0x%08x)", (unsigned)hr);
      return false;
    }
    return Drain(out);
  }

 private:
  bool Drain(const Out& out) {
    for (;;) {
      MFT_OUTPUT_DATA_BUFFER ob{};
      ComPtr<IMFSample> mine;
      if (!provides_) {
        ComPtr<IMFMediaBuffer> b;
        if (FAILED(MFCreateSample(&mine)) || FAILED(MFCreateMemoryBuffer(outSize_, &b))) return false;
        mine->AddBuffer(b.Get());
        ob.pSample = mine.Get();
      }
      DWORD status = 0;
      HRESULT hr = mft_->ProcessOutput(0, 1, &ob, &status);
      if (ob.pEvents) ob.pEvents->Release();
      if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return true;
      if (FAILED(hr)) {
        Log("audio: the encoder failed (0x%08x)", (unsigned)hr);
        return false;
      }
      ComPtr<IMFMediaBuffer> buf;
      if (ob.pSample && SUCCEEDED(ob.pSample->ConvertToContiguousBuffer(&buf))) {
        BYTE* p = nullptr;
        DWORD n = 0;
        LONGLONG t = 0;
        ob.pSample->GetSampleTime(&t);
        if (SUCCEEDED(buf->Lock(&p, nullptr, &n))) {
          if (n > 0) out(p, n, t);
          buf->Unlock();
        }
      }
      if (provides_ && ob.pSample) ob.pSample->Release();
    }
  }

  ComPtr<IMFTransform> mft_;
  DWORD outSize_ = 0;
  bool provides_ = false;
  int rate_ = 0, channels_ = 0;
};

Bytes AudioFormatMsg(uint8_t codec, int rate, int channels) {
  Writer w(msg::AUDIO_FORMAT);
  w.u8(codec).u32((uint32_t)rate).u8((uint8_t)channels);
  if (codec == acodec::AAC_LC) w.raw(audio::AacConfig(rate, channels));
  return std::move(w.b);
}

}  // namespace

namespace audio {
Bytes AacConfig(int rate, int channels) {
  static const int rates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
  for (int i = 0; i < 13; i++)
    if (rates[i] == rate) return {(uint8_t)((2 << 3) | (i >> 1)), (uint8_t)(((i & 1) << 7) | (channels << 3))};
  return {};
}
}  // namespace audio

void AudioStreamer::Start() {
  std::lock_guard<std::mutex> lk(mu_);
  if (thread_.joinable()) return;
  stop_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  thread_ = std::thread(&AudioStreamer::Run, this);
}

void AudioStreamer::Stop() {
  std::lock_guard<std::mutex> lk(mu_);
  if (!thread_.joinable()) return;
  SetEvent(stop_);
  thread_.join();
  CloseHandle(stop_);
  stop_ = nullptr;
}

bool AudioStreamer::running() {
  std::lock_guard<std::mutex> lk(mu_);
  return thread_.joinable();
}

void AudioStreamer::Run() {
  SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
  CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  MFStartup(MF_VERSION, MFSTARTUP_LITE);
  ComPtr<IMMDeviceEnumerator> en;
  if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&en))))
    Log("audio: Windows' sound devices can't be reached");
  auto stopped = [&](DWORD ms) { return WaitForSingleObject(stop_, ms) != WAIT_TIMEOUT; };
  std::string toldProblem;  // the problem logged (and sent as "no sound") last; again only when it changes
  auto noSound = [&](const std::string& why) {
    if (why == toldProblem) return;
    toldProblem = why;
    Log("audio: no sound for the phone: %s", why.c_str());
    send_(AudioFormatMsg(acodec::NONE, 0, 0));
  };

  while (en && !stopped(0)) {
    ComPtr<IMMDevice> dev;
    std::wstring id;
    HRESULT hr = DefaultOutput(en.Get(), dev, id);
    if (FAILED(hr)) {
      noSound("this PC has no sound output right now");
      stopped(2000);
      continue;
    }
    Loopback lb;
    std::string problem;
    hr = OpenLoopback(dev.Get(), lb, problem);
    AacEncoder enc;
    if (SUCCEEDED(hr) && !enc.Init(lb.rate, kChannels)) {
      problem = "Windows' AAC encoder isn't there (Media Feature Pack)";
      hr = E_FAIL;
    }
    if (FAILED(hr)) {
      char why[200];
      snprintf(why, sizeof(why), "%s (0x%08x)", problem.c_str(), (unsigned)hr);
      noSound(why);
      stopped(2000);
      continue;
    }
    toldProblem.clear();
    Log("audio: %s, %d Hz, %d channel(s)%s -> AAC %u kbps", W2U(DeviceName(dev.Get())).c_str(), lb.rate, lb.channels,
        lb.converted ? " (converted by Windows)" : "", kAacBytesPerSec * 8 / 1000);
    send_(AudioFormatMsg(acodec::AAC_LC, lb.rate, kChannels));

    // Sound is passed on while there is some; a second of quiet, or Windows handing out nothing (nothing plays),
    // ends a spell: the encoder gets silence to push the last sound out, then nothing more is sent.
    std::vector<int16_t> pcm;  // interleaved stereo, waiting for the encoder
    uint64_t fed = 0;          // frames given to the encoder: its clock
    bool active = false, gone = false, sentAny = false;
    uint64_t quietSince = 0, lastPacket = NowMs(), lastLook = NowMs(), lastCount = NowMs();
    Counts counts;
    auto out = [&](const BYTE* p, DWORD n, LONGLONG t) {
      Writer w(msg::AUDIO_DATA);
      w.u64((uint64_t)(t / 10)).raw(p, n);
      send_(std::move(w.b));
      counts.sent++;
      if (!sentAny) {
        sentAny = true;
        Log("audio: sound, sending it");
      }
    };
    auto encode = [&] {
      size_t frames = pcm.size() / kChannels;
      bool ok = frames == 0 || enc.Encode(pcm.data(), frames, (LONGLONG)(fed * 10000000 / lb.rate), out);
      fed += frames;
      pcm.clear();
      return ok;
    };
    while (!stopped(10)) {
      uint64_t now = NowMs();
      bool loud = false, got = false;
      if (!Read(lb, pcm, loud, got, counts)) {
        Log("audio: the sound output went away");
        gone = true;
        break;
      }
      if (got) lastPacket = now;
      if (loud) counts.loudTicks++;
      if (now - lastCount >= 60000) {  // what happened lately: shows a capture that hears nothing
        Log("audio: last minute: %u packets from Windows (%u marked silent), %u looks with sound, %u AAC frames sent",
            counts.packets, counts.silent, counts.loudTicks, counts.sent);
        counts = Counts{};
        lastCount = now;
      }
      if (!active) {
        if (loud) active = true, quietSince = 0;
        else pcm.clear();
      } else {
        if (got) {
          if (loud) quietSince = 0;
          else if (!quietSince) quietSince = now;
        }
        bool idle = !got && now - lastPacket >= kStoppedMs;
        if (idle || (quietSince && now - quietSince >= kQuietMs)) {
          if (idle) pcm.insert(pcm.end(), (size_t)2 * kFrame * kChannels, 0);  // pushes the last sound out
          active = false;
          quietSince = 0;
        }
        if (!encode()) {
          gone = true;
          break;
        }
      }
      if (now - lastLook >= 1000) {  // another default output (headphones plugged in, a monitor switched on)
        lastLook = now;
        ComPtr<IMMDevice> d2;
        std::wstring id2;
        if (FAILED(DefaultOutput(en.Get(), d2, id2)) || id2 != id) {
          Log("audio: the default sound output changed");
          break;
        }
      }
    }
    lb.client->Stop();
    if (gone) stopped(500);  // a device that's going away: give Windows a moment to pick another
  }
  MFShutdown();
  CoUninitialize();
}
