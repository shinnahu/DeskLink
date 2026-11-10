#include "vdisplay.h"

#include "common.h"  // windows.h first

#include <setupapi.h>
#include <shlwapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>

#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "shlwapi.lib")

namespace vscreen {
namespace {

// Parsec VDD: its device interface and control codes. Every request carries 32 bytes; an answer is 4 bytes.
const GUID kInterface = {0x00b41627, 0x04c4, 0x429e, {0xa2, 0x6e, 0x02, 0x65, 0xcf, 0x50, 0xc8, 0xfa}};
constexpr DWORD kAdd = 0x0022e004;        // adds a screen -> its number (0-15)
constexpr DWORD kRemove = 0x0022a008;     // takes away the screen numbered in[0..1] (big-endian)
constexpr DWORD kPing = 0x0022a00c;       // keeps the screens: at most 200 ms apart, or they all go after about 1 s
constexpr DWORD kVersion = 0x0022e010;    // -> the driver's minor version
const wchar_t kMonitorId[] = L"PSCCDD0";  // the virtual screens' monitor id, part of their device paths
// Windows' device class for monitors
const GUID kMonitorClass = {0x4d36e96e, 0xe325, 0x11ce, {0xbf, 0xc1, 0x08, 0x00, 0x2b, 0xe1, 0x03, 0x18}};

constexpr uint64_t kGraceMs = 30000;        // kept this long after the last stream ends
constexpr uint64_t kMonitorOnMs = 2000;     // a real monitor on the desktop this long: the virtual screen goes
constexpr uint64_t kWaitMonitorMs = 5000;   // a monitor that was just connected gets this long to come on first
constexpr uint64_t kAttachMs = 15000;       // Windows has put a new screen on the desktop by then
constexpr uint64_t kRetryMs = 10000;        // after the driver failed or wasn't there, it's asked again this often
constexpr uint64_t kLostMs = 1000;          // our screen's monitor gone this long: the driver took it away
constexpr uint64_t kReaddMs = 1500;         // a screen the driver took away is put in again after this

std::mutex mu;  // everything below
std::condition_variable cv;
int index = -1;          // the driver's number for our screen; -1: none
HANDLE dev = INVALID_HANDLE_VALUE;  // the driver, opened for our screen (tests: SimulateDrop)
bool keeping = false;    // the keeper thread runs (it watches the screen, and takes it away when it's not needed)
bool removeNow = false;  // tests: take it away now
bool pinned = false;     // tests: it stays while a real monitor is on
int holders = 0;
uint64_t idleSince = 0;  // nobody has held it since then
uint64_t addedAt = 0;
uint64_t lastAsk = 0, spellStart = 0;  // Ensure: its last call, and when this spell without a screen began
uint64_t retryAt = 0;
State retryState = State::NoDriver;  // what Ensure answers until retryAt
State loggedFail = State::Starting;  // the failure logged last (logged again only when it changes)
int losses = 0;                      // screens the driver took away, one after another
uint64_t lastLoss = 0;

// One request in flight. The driver may fail DeviceIoControl at once and still take the request, so the answer is
// always awaited. One that doesn't finish is cancelled; if even that doesn't finish, the request is left to the driver
// (which may still write into it) rather than freed.
struct Request {
  OVERLAPPED ov{};
  BYTE in[32]{};
  DWORD out = 0;
};

bool Ioctl(HANDLE h, DWORD code, int screen, DWORD* answer, DWORD timeoutMs) {
  auto* r = new Request;
  r->ov.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (!r->ov.hEvent) {
    delete r;
    return false;
  }
  if (screen >= 0) {
    r->in[0] = (BYTE)(screen >> 8);
    r->in[1] = (BYTE)screen;
  }
  DWORD got = 0;
  DeviceIoControl(h, code, r->in, sizeof(r->in), &r->out, sizeof(r->out), nullptr, &r->ov);
  bool ok = GetOverlappedResultEx(h, &r->ov, &got, timeoutMs, FALSE) != FALSE;
  if (!ok && GetLastError() == WAIT_TIMEOUT) {
    CancelIoEx(h, &r->ov);
    if (!GetOverlappedResultEx(h, &r->ov, &got, 2000, FALSE) && GetLastError() == WAIT_TIMEOUT) {
      Log("vscreen: the virtual display driver doesn't answer (request 0x%08lx)", code);
      return false;
    }
  }
  if (ok && answer) {
    if (got >= sizeof(DWORD)) *answer = r->out;
    else ok = false;
  }
  CloseHandle(r->ov.hEvent);
  delete r;
  return ok;
}

// The driver's device, opened for requests; INVALID_HANDLE_VALUE when it isn't there (present = false) or can't be
// opened.
HANDLE OpenDriver(bool& present) {
  present = false;
  HANDLE h = INVALID_HANDLE_VALUE;
  HDEVINFO info = SetupDiGetClassDevsW(&kInterface, nullptr, nullptr, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
  if (info == INVALID_HANDLE_VALUE) return h;
  SP_DEVICE_INTERFACE_DATA ifd{sizeof(ifd)};
  for (DWORD i = 0; h == INVALID_HANDLE_VALUE && SetupDiEnumDeviceInterfaces(info, nullptr, &kInterface, i, &ifd); i++) {
    present = true;
    DWORD need = 0;
    SetupDiGetDeviceInterfaceDetailW(info, &ifd, nullptr, 0, &need, nullptr);
    if (need < sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W)) continue;
    std::vector<BYTE> buf(need);
    auto* d = (SP_DEVICE_INTERFACE_DETAIL_DATA_W*)buf.data();
    d->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
    if (!SetupDiGetDeviceInterfaceDetailW(info, &ifd, d, need, nullptr, nullptr)) continue;
    h = CreateFileW(d->DevicePath, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_NO_BUFFERING | FILE_FLAG_OVERLAPPED | FILE_FLAG_WRITE_THROUGH,
                    nullptr);
    if (h == INVALID_HANDLE_VALUE) Log("vscreen: can't open the virtual display driver (error %lu)", GetLastError());
  }
  SetupDiDestroyDeviceInfoList(info);
  return h;
}

bool VirtualPath(const wchar_t* path) { return path && StrStrIW(path, kMonitorId) != nullptr; }

// The display paths Windows has: the active ones (on the desktop), or all of them. False if it can't say.
bool Paths(bool activeOnly, std::vector<DISPLAYCONFIG_PATH_INFO>& paths) {
  UINT32 flags = activeOnly ? QDC_ONLY_ACTIVE_PATHS : QDC_ALL_PATHS;
  std::vector<DISPLAYCONFIG_MODE_INFO> modes;
  UINT32 np = 0, nm = 0;
  LONG r;
  int tries = 0;
  do {  // the layout can change between the two calls
    if (GetDisplayConfigBufferSizes(flags, &np, &nm) != ERROR_SUCCESS) return false;
    paths.resize(np);
    modes.resize(nm);
    r = QueryDisplayConfig(flags, &np, paths.data(), &nm, modes.data(), nullptr);
  } while (r == ERROR_INSUFFICIENT_BUFFER && ++tries < 3);
  if (r != ERROR_SUCCESS) return false;
  paths.resize(np);
  return true;
}

// Is a monitor behind this path's screen (virt: one of the virtual ones)? When the last monitor goes (switched off,
// unplugged), Windows keeps its screen and even calls it available ("forced" by the system, so the desktop doesn't
// vanish), but no monitor device is behind it any more: its device path is empty. Then nothing is drawn to it.
bool MonitorBehind(const DISPLAYCONFIG_PATH_INFO& p, bool& virt) {
  virt = false;
  if (!p.targetInfo.targetAvailable) return false;
  DISPLAYCONFIG_TARGET_DEVICE_NAME tn{};
  tn.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
  tn.header.size = sizeof(tn);
  tn.header.adapterId = p.targetInfo.adapterId;
  tn.header.id = p.targetInfo.id;
  if (DisplayConfigGetDeviceInfo(&tn.header) != ERROR_SUCCESS) return true;  // can't tell: as if it were
  if (!tn.monitorDevicePath[0]) return false;
  virt = VirtualPath(tn.monitorDevicePath);
  return true;
}

// A real monitor (not a virtual screen): on the desktop now, or just connected (Windows may not show it yet).
bool RealMonitor(bool onDesktop) {
  std::vector<DISPLAYCONFIG_PATH_INFO> paths;
  if (!Paths(onDesktop, paths)) return false;
  for (auto& p : paths) {
    bool virt;
    if (MonitorBehind(p, virt) && !virt) return true;
  }
  return false;
}

// Is one of the driver's monitors there? Asked of Plug and Play, which answers at once, even while Windows rearranges
// the screens (the display layout can keep a caller waiting then).
enum class Presence { No, Yes, Unknown };
Presence VirtualMonitorThere() {
  HDEVINFO info = SetupDiGetClassDevsW(&kMonitorClass, nullptr, nullptr, DIGCF_PRESENT);
  if (info == INVALID_HANDLE_VALUE) return Presence::Unknown;
  Presence r = Presence::No;
  SP_DEVINFO_DATA d{sizeof(d)};
  wchar_t id[512];
  for (DWORD i = 0; r == Presence::No && SetupDiEnumDeviceInfo(info, i, &d); i++)
    if (SetupDiGetDeviceInstanceIdW(info, &d, id, ARRAYSIZE(id), nullptr) && StrStrIW(id, kMonitorId)) r = Presence::Yes;
  SetupDiDestroyDeviceInfoList(info);
  return r;
}

// The pings, on a thread of their own: the driver takes every screen away about a second after the last one, so
// nothing may hold them up. (Looking at the display layout can wait for a second or more while Windows rearranges the
// screens, as when a monitor was just switched off and the virtual screen comes in.)
struct Pings {
  std::atomic<bool> stop{false};
  std::atomic<int> missed{0};          // in a row
  std::atomic<uint64_t> longest{0};    // the longest time between two answered pings
};

void Pinger(HANDLE h, Pings* p) {
  SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
  uint64_t last = NowMs();
  while (!p->stop) {
    if (Ioctl(h, kPing, -1, nullptr, 500)) {
      uint64_t now = NowMs(), gap = now - last;
      last = now;
      p->missed = 0;
      if (gap > p->longest) p->longest = gap;
      if (gap >= 500) Log("vscreen: the pings were %llu ms apart", gap);
    } else {
      p->missed++;
    }
    Sleep(100);
  }
}

// Watches our screen while it's in, and takes it away once it's not needed. When the driver has taken it away by
// itself, a new one is put in.
void Keeper(HANDLE h, int screen) {
  Pings pings;
  std::thread pinger(Pinger, h, &pings);
  const char* why = nullptr;
  bool lost = false;  // gone without being asked to: Ensure puts a new one in
  bool seen = false;  // its monitor has been there
  uint64_t added = NowMs(), monitorSince = 0, goneSince = 0;
  for (;;) {
    bool forced;
    {
      std::unique_lock<std::mutex> lk(mu);
      cv.wait_for(lk, std::chrono::milliseconds(500), [] { return removeNow; });
      uint64_t now = NowMs();
      if (removeNow) why = "asked to";
      else if (holders == 0 && now - idleSince >= kGraceMs) why = "no phone has needed it for 30 s";
      forced = pinned;
    }
    if (why) break;
    if (pings.missed >= 3) {
      why = "the driver stopped answering";
      lost = true;
      break;
    }
    // the looks below are taken without the lock: they may wait while Windows rearranges the screens
    uint64_t now = NowMs();
    Presence there = VirtualMonitorThere();
    if (there == Presence::Yes) {
      seen = true;
      goneSince = 0;
    } else if (there == Presence::No) {
      if (!goneSince) goneSince = now;
      if (seen && now - goneSince >= kLostMs) why = "the driver took it away";
      else if (!seen && now - added >= kAttachMs) why = "its monitor never came";
      if (why) {
        lost = true;
        break;
      }
    }
    if (forced) continue;
    if (!RealMonitor(true)) {
      monitorSince = 0;
    } else if (!monitorSince) {
      monitorSince = now;
    } else if (now - monitorSince >= kMonitorOnMs) {
      why = "the PC's monitor is on again";
      break;
    }
  }
  pings.stop = true;
  pinger.join();
  {
    std::lock_guard<std::mutex> lk(mu);
    dev = INVALID_HANDLE_VALUE;
  }
  Ioctl(h, kRemove, screen, nullptr, 1000);
  Ioctl(h, kPing, -1, nullptr, 500);
  CloseHandle(h);
  uint64_t now = NowMs();
  Log("vscreen: virtual screen %d removed (%s) after %.1f s; the pings were at most %llu ms apart", screen, why,
      (now - added) / 1000.0, (unsigned long long)pings.longest.load());
  std::lock_guard<std::mutex> lk(mu);
  index = -1;
  keeping = false;
  removeNow = false;
  pinned = false;
  if (lost) {  // put a new one in soon; not over and over if it keeps going
    losses = now - lastLoss < 60000 ? losses + 1 : 1;
    lastLoss = now;
    retryAt = now + (losses >= 3 ? kRetryMs : kReaddMs);
    retryState = losses >= 3 ? State::Failed : State::Starting;
  }
  cv.notify_all();
}

}  // namespace

void Hold() {
  std::lock_guard<std::mutex> lk(mu);
  holders++;
}

void Release() {
  std::lock_guard<std::mutex> lk(mu);
  if (holders > 0 && --holders == 0) idleSince = NowMs();
}

State Ensure(bool force) {
  std::unique_lock<std::mutex> lk(mu);
  uint64_t now = NowMs();
  if (now - lastAsk > 2000) spellStart = now;  // the capture asks every 0.4 s while there's no screen
  lastAsk = now;
  if (force) pinned = true;
  if (index >= 0) return now - std::max(addedAt, spellStart) > kAttachMs ? State::Failed : State::Starting;
  if (keeping && !cv.wait_for(lk, std::chrono::seconds(5), [] { return !keeping; })) return State::Waiting;
  if (now < retryAt) return retryState;
  // a monitor that was just switched on: Windows turns it on in a moment
  if (!force && now - spellStart < kWaitMonitorMs && RealMonitor(false)) return State::Waiting;

  bool present = false;
  HANDLE h = OpenDriver(present);
  DWORD version = 0, screen = 0;
  bool ok = h != INVALID_HANDLE_VALUE && Ioctl(h, kVersion, -1, &version, 1000) && Ioctl(h, kAdd, -1, &screen, 5000) &&
            screen < 16;
  if (!ok) {
    if (h != INVALID_HANDLE_VALUE) CloseHandle(h);  // a screen added after all goes once the pings stop
    retryState = present ? State::Failed : State::NoDriver;
    retryAt = now + kRetryMs;
    if (retryState != loggedFail) {
      loggedFail = retryState;
      Log(present ? "vscreen: the virtual display driver didn't add a screen"
                  : "vscreen: no virtual screen: Parsec's virtual display driver isn't installed");
    }
    return retryState;
  }
  Ioctl(h, kPing, -1, nullptr, 500);
  loggedFail = State::Starting;
  index = (int)screen;
  dev = h;
  addedAt = NowMs();
  if (holders == 0) idleSince = addedAt;
  keeping = true;
  std::thread(Keeper, h, (int)screen).detach();  // the process may end with it running: the driver cleans up then
  Log("vscreen: virtual screen %lu added (Parsec VDD 0.%lu)", screen, version);
  return State::Starting;
}

bool Installed() {
  HDEVINFO info = SetupDiGetClassDevsW(&kInterface, nullptr, nullptr, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
  if (info == INVALID_HANDLE_VALUE) return false;
  SP_DEVICE_INTERFACE_DATA ifd{sizeof(ifd)};
  bool any = SetupDiEnumDeviceInterfaces(info, nullptr, &kInterface, 0, &ifd) != FALSE;
  SetupDiDestroyDeviceInfoList(info);
  return any;
}

bool IsVirtual(const wchar_t* gdiName) {
  DISPLAY_DEVICEW dd{};
  dd.cb = sizeof(dd);
  for (DWORD i = 0; EnumDisplayDevicesW(gdiName, i, &dd, EDD_GET_DEVICE_INTERFACE_NAME); i++) {
    if (VirtualPath(dd.DeviceID)) return true;
    dd.cb = sizeof(dd);
  }
  return false;
}

std::wstring VirtualOnDesktop() {
  DISPLAY_DEVICEW ad{};
  ad.cb = sizeof(ad);
  for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &ad, 0); i++) {
    if ((ad.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) && IsVirtual(ad.DeviceName)) return ad.DeviceName;
    ad.cb = sizeof(ad);
  }
  return L"";
}

bool RealMonitorOnDesktop() { return RealMonitor(true); }

bool HasMonitor(const wchar_t* gdiName) {
  std::vector<DISPLAYCONFIG_PATH_INFO> paths;
  if (!Paths(true, paths)) return true;  // can't tell: as before
  bool found = false;
  for (auto& p : paths) {
    DISPLAYCONFIG_SOURCE_DEVICE_NAME sn{};
    sn.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
    sn.header.size = sizeof(sn);
    sn.header.adapterId = p.sourceInfo.adapterId;
    sn.header.id = p.sourceInfo.id;
    if (DisplayConfigGetDeviceInfo(&sn.header) != ERROR_SUCCESS || _wcsicmp(sn.viewGdiDeviceName, gdiName) != 0) continue;
    bool virt;
    if (MonitorBehind(p, virt)) return true;  // (a cloned screen: any of its monitors)
    found = true;
  }
  return !found;  // not among the paths: can't tell
}

void Unpin() {
  std::lock_guard<std::mutex> lk(mu);
  pinned = false;
}

void RemoveNow() {
  std::unique_lock<std::mutex> lk(mu);
  if (!keeping) return;
  removeNow = true;
  cv.notify_all();
  cv.wait_for(lk, std::chrono::seconds(8), [] { return !keeping; });
}

bool SimulateDrop() {
  std::lock_guard<std::mutex> lk(mu);  // (held, so the keeper can't close the handle meanwhile)
  return index >= 0 && dev != INVALID_HANDLE_VALUE && Ioctl(dev, kRemove, index, nullptr, 1000);
}

}  // namespace vscreen
