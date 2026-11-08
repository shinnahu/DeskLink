#include "desktop.h"

#include <dxgi1_2.h>
#include <wrl/client.h>

#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib")

using Microsoft::WRL::ComPtr;

static std::wstring DesktopName(HDESK d) {
  wchar_t buf[256] = {};
  DWORD len = 0;
  if (!GetUserObjectInformationW(d, UOI_NAME, buf, sizeof(buf), &len)) return L"";
  return buf;
}

DesktopFollower::~DesktopFollower() {
  if (cur_) CloseDesktop(cur_);
}

bool DesktopFollower::Sync() {
  HDESK in = OpenInputDesktop(0, FALSE, GENERIC_ALL);
  if (!in) {
    secure_ = true;  // access denied => secure desktop while running as a normal user
    return false;
  }
  std::wstring n = DesktopName(in);
  secure_ = _wcsicmp(n.c_str(), L"Default") != 0;
  if (n == name_) {
    CloseDesktop(in);
    return false;
  }
  if (!SetThreadDesktop(in)) {
    CloseDesktop(in);
    return false;
  }
  if (cur_) CloseDesktop(cur_);
  cur_ = in;
  bool changed = !name_.empty();
  name_ = n;
  Log("desktop: thread now on '%s'", W2U(n).c_str());
  return changed;
}

bool IsInputDesktopSecure() {
  HDESK in = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
  if (!in) return true;
  bool secure = _wcsicmp(DesktopName(in).c_str(), L"Default") != 0;
  CloseDesktop(in);
  return secure;
}

std::vector<MonitorDesc> ListMonitors() {
  std::vector<MonitorDesc> out;
  ComPtr<IDXGIFactory1> f;
  if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&f)))) return out;
  ComPtr<IDXGIAdapter1> a;
  for (UINT ai = 0; f->EnumAdapters1(ai, &a) != DXGI_ERROR_NOT_FOUND; ai++) {
    ComPtr<IDXGIOutput> o;
    for (UINT oi = 0; a->EnumOutputs(oi, &o) != DXGI_ERROR_NOT_FOUND; oi++) {
      DXGI_OUTPUT_DESC d{};
      o->GetDesc(&d);
      if (!d.AttachedToDesktop) continue;
      MONITORINFOEXW mi{};
      mi.cbSize = sizeof(mi);
      GetMonitorInfoW(d.Monitor, &mi);
      MonitorDesc m;
      m.index = (int)out.size();
      m.rect = d.DesktopCoordinates;
      m.primary = (mi.dwFlags & MONITORINFOF_PRIMARY) != 0;
      char name[96];
      snprintf(name, sizeof(name), "Display %d (%ldx%ld)", m.index + 1, m.rect.right - m.rect.left,
               m.rect.bottom - m.rect.top);
      m.name = name;
      out.push_back(m);
    }
  }
#ifdef DESKLINK_TESTING
  // --fake-screens: the same pretend screens the capture shows (see Streamer::BuildPipeline)
  for (int k = 1; k <= g_opt.testFakeScreens && !out.empty(); k++) {
    MonitorDesc m = out[0];
    LONG w = m.rect.right - m.rect.left, h = m.rect.bottom - m.rect.top, l = m.rect.left + w * k, t = m.rect.top + 240 * k;
    m.rect = k == 3 ? RECT{l, t, l + h, t + w} : RECT{l, t, l + w, t + h};
    m.index = (int)out.size();
    m.primary = false;
    m.name = "Pretend " + std::to_string(k);
    out.push_back(m);
  }
#endif
  return out;
}
