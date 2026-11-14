#include "screenshot.h"

#include <wincodec.h>

#include <algorithm>

#include "protocol.h"
#include "session.h"

#pragma comment(lib, "windowscodecs.lib")

bool EncodePng(const Shot& shot, Bytes& png, std::string& why) {
  auto fail = [&](const char* what, HRESULT hr) {
    char b[96];
    snprintf(b, sizeof(b), "%s (0x%08x)", what, (unsigned)hr);
    why = b;
    return false;
  };
  ComPtr<IWICImagingFactory> f;
  ComPtr<IStream> st;
  ComPtr<IWICBitmapEncoder> enc;
  ComPtr<IWICBitmapFrameEncode> fr;
  ComPtr<IPropertyBag2> props;
  HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&f));
  if (FAILED(hr)) return fail("no image encoder", hr);
  if (FAILED(hr = CreateStreamOnHGlobal(nullptr, TRUE, &st))) return fail("no memory stream", hr);
  if (FAILED(hr = f->CreateEncoder(GUID_ContainerFormatPng, nullptr, &enc))) return fail("no PNG encoder", hr);
  if (FAILED(hr = enc->Initialize(st.Get(), WICBitmapEncoderNoCache))) return fail("PNG encoder", hr);
  if (FAILED(hr = enc->CreateNewFrame(&fr, &props))) return fail("PNG frame", hr);
  if (FAILED(hr = fr->Initialize(props.Get()))) return fail("PNG frame", hr);
  if (FAILED(hr = fr->SetSize((UINT)shot.w, (UINT)shot.h))) return fail("PNG size", hr);
  WICPixelFormatGUID fmt = GUID_WICPixelFormat24bppBGR;  // the PNG encoder may answer with another one it takes
  if (FAILED(hr = fr->SetPixelFormat(&fmt))) return fail("PNG pixel format", hr);
  int bpp = IsEqualGUID(fmt, GUID_WICPixelFormat24bppBGR) ? 3
            : IsEqualGUID(fmt, GUID_WICPixelFormat32bppBGRA) || IsEqualGUID(fmt, GUID_WICPixelFormat32bppBGR) ? 4
                                                                                                                : 0;
  if (!bpp) return fail("PNG pixel format", E_NOTIMPL);
  // in strips of rows: the screen's alpha is left out (24-bit) or made opaque (it isn't always set)
  const int kRows = 64;
  UINT stride = (UINT)(shot.w * bpp + 3) & ~3u;
  Bytes strip((size_t)stride * kRows);
  for (int y0 = 0; y0 < shot.h; y0 += kRows) {
    int rows = std::min(kRows, shot.h - y0);
    for (int y = 0; y < rows; y++) {
      const uint32_t* src = shot.px.data() + (size_t)(y0 + y) * shot.w;
      uint8_t* d = strip.data() + (size_t)y * stride;
      if (bpp == 3) {
        for (int x = 0; x < shot.w; x++, d += 3) {
          uint32_t p = src[x];  // B G R X in memory
          d[0] = (uint8_t)p;
          d[1] = (uint8_t)(p >> 8);
          d[2] = (uint8_t)(p >> 16);
        }
      } else {
        for (int x = 0; x < shot.w; x++) ((uint32_t*)d)[x] = src[x] | 0xFF000000u;
      }
    }
    if (FAILED(hr = fr->WritePixels((UINT)rows, stride, stride * (UINT)rows, strip.data())))
      return fail("PNG pixels", hr);
  }
  if (FAILED(hr = fr->Commit())) return fail("PNG frame", hr);
  if (FAILED(hr = enc->Commit())) return fail("PNG file", hr);
  STATSTG stat{};
  HGLOBAL mem = nullptr;
  if (FAILED(hr = st->Stat(&stat, STATFLAG_NONAME)) || FAILED(hr = GetHGlobalFromStream(st.Get(), &mem)))
    return fail("PNG memory", hr);
  const void* p = GlobalLock(mem);
  if (!p) return fail("PNG memory", E_OUTOFMEMORY);
  png.assign((const uint8_t*)p, (const uint8_t*)p + (size_t)stat.cbSize.QuadPart);
  GlobalUnlock(mem);
  return true;
}

// "Screenshot 2026-11-11 143205.png", the way Windows' Snipping Tool names them, at the PC's time
static std::string ShotName() {
  SYSTEMTIME t{};
  GetLocalTime(&t);
  char b[96];
  snprintf(b, sizeof(b), "%s %04u-%02u-%02u %02u%02u%02u.png", IsSpanishUi() ? "Captura de pantalla" : "Screenshot",
           t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond);
  return b;
}

static void SendShotFailed(Session& s, uint8_t reason, const std::string& why) {
  Writer w(msg::SCREENSHOT_FAILED);
  w.u8(reason).str(why);
  s.SendControl(std::move(w.b));
}

void SendShot(std::shared_ptr<Session> s, Shot&& shot) {
  if (!s->alive()) return;
  if (shot.fail) {
    SendShotFailed(*s, shot.fail, shot.why);
    return;
  }
  // off the capture thread: making the PNG takes a moment (a tenth of a second or so for a full-HD screen)
  auto pic = std::make_shared<Shot>(std::move(shot));
  std::thread([s, pic] {
    HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    uint64_t t0 = NowMs();
    Bytes png;
    std::string why;
    bool ok = EncodePng(*pic, png, why);
    if (ok) {
      Log("screenshot: %dx%d PNG, %zu KB in %llu ms", pic->w, pic->h, png.size() / 1024,
          (unsigned long long)(NowMs() - t0));
    } else {
      Log("screenshot: PNG failed: %s", why.c_str());
    }
    pic->px.clear();
    pic->px.shrink_to_fit();
    if (SUCCEEDED(co)) CoUninitialize();
    if (!ok) {
      SendShotFailed(*s, shotfail::FAILURE, why);
      return;
    }
    SendBytesToPhone(*s, ShotName(), "image/png", fkind::SCREENSHOT, png);
  }).detach();
}
