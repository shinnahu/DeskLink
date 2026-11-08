#include "ui.h"

#include <commctrl.h>
#include <dwmapi.h>
#include <objidl.h>
#include <shellapi.h>
// GDI+ needs min/max from <algorithm> because NOMINMAX is defined
#include <algorithm>
namespace Gdiplus {
using std::max;
using std::min;
}  // namespace Gdiplus
#include <gdiplus.h>

#include <climits>
#include <cmath>

#include "app.h"
#include "files.h"
#include "protocol.h"
#include "remote.h"
#include "service.h"
#include "session.h"
#include "strings.h"

#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "comctl32.lib")

using namespace Gdiplus;

namespace ui {
namespace {

enum : UINT {
  WM_TRAY = WM_APP + 1,
  WM_SESS_START,
  WM_SESS_END,
  WM_SET_CLIP,
  WM_PAIR_ASK,
  WM_PAIR_DONE,
  WM_BALLOON,
  WM_REFRESH,
  WM_CLIP_PUSH,
  WM_FILE_RX,
};

enum : UINT {
  ID_PAIR = 100,
  ID_SENDFILE,
  ID_DISCONNECT,
  ID_OPENFILES,
  ID_STARTUP,
  ID_INSTALL,
  ID_EXIT,
  ID_REMOTE,
  ID_DEVICE_BASE = 1000,
};

enum BtnId { B_CLOSE = 1, B_REOPEN, B_ALLOW, B_DENY, B_INSTALL };

HINSTANCE g_inst = nullptr;
HWND g_main = nullptr, g_overlay = nullptr, g_pair = nullptr;
bool g_withTray = false, g_trayAdded = false;
UINT g_taskbarCreated = 0;
ULONG_PTR g_gdip = 0;
HICON g_iconSmall = nullptr, g_iconBig = nullptr;
std::wstring g_device;
std::wstring g_lastFile;
DWORD g_ourClipSeq = 0;
UINT g_cfExclude = 0;
std::vector<Bytes> g_menuDevices;
std::vector<std::wstring> g_menuDeviceNames;

struct PairAsk {
  std::string name, model, code;
  std::mutex m;
  std::condition_variable cv;
  int result = -2;
};
std::shared_ptr<PairAsk> g_ask;

enum class PState { Waiting, Closed, Request, Confirming, Done, Failed };
PState g_pstate = PState::Waiting;
std::wstring g_pname, g_pmodel, g_pcode;
struct Btn {
  RECT r;
  int id;
  bool primary;
  std::wstring text;
};
std::vector<Btn> g_pbtns;
int g_phover = -1;
bool g_installed = false;

RECT g_stopRect{};
bool g_stopHover = false, g_stopDown = false, g_ovPlaced = false;
int g_ovW = 0, g_ovH = 0;

float Scale(HWND h) { return (float)GetDpiForWindow(h) / 96.0f; }

void RoundRectPath(GraphicsPath& p, float x, float y, float w, float h, float r) {
  float d = std::min(r * 2, std::min(w, h));
  if (d <= 0.5f) {
    p.AddRectangle(RectF(x, y, w, h));
    return;
  }
  p.AddArc(x, y, d, d, 180, 90);
  p.AddArc(x + w - d, y, d, d, 270, 90);
  p.AddArc(x + w - d, y + h - d, d, d, 0, 90);
  p.AddArc(x, y + h - d, d, d, 90, 90);
  p.CloseFigure();
}

std::wstring Truncate(const std::wstring& s, size_t n) { return s.size() <= n ? s : s.substr(0, n - 1) + L"…"; }

// ---------------------------------------------------------------- clipboard
bool OpenClip() {
  for (int i = 0; i < 20; i++) {
    if (OpenClipboard(g_main)) return true;
    Sleep(10);
  }
  return false;
}

bool ReadClipboardText(std::string& out) {
  if (g_cfExclude && IsClipboardFormatAvailable(g_cfExclude)) return false;  // password managers opt out
  if (!IsClipboardFormatAvailable(CF_UNICODETEXT) || !OpenClip()) return false;
  bool ok = false;
  HANDLE h = GetClipboardData(CF_UNICODETEXT);
  if (h) {
    auto* p = (const wchar_t*)GlobalLock(h);
    if (p) {
      size_t n = wcsnlen(p, GlobalSize(h) / sizeof(wchar_t));
      if (n > 0 && n <= 512 * 1024) {
        out = W2U(std::wstring(p, n));
        ok = true;
      }
      GlobalUnlock(h);
    }
  }
  CloseClipboard();
  return ok;
}

void WriteClipboardText(const std::string& utf8) {
  std::wstring in = U2W(utf8), w;
  w.reserve(in.size() + 16);
  for (size_t i = 0; i < in.size(); i++) {
    if (in[i] == L'\n' && (i == 0 || in[i - 1] != L'\r')) w.push_back(L'\r');
    w.push_back(in[i]);
  }
  HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE, (w.size() + 1) * sizeof(wchar_t));
  if (!hg) return;
  memcpy(GlobalLock(hg), w.c_str(), (w.size() + 1) * sizeof(wchar_t));
  GlobalUnlock(hg);
  if (!OpenClip()) {
    GlobalFree(hg);
    return;
  }
  EmptyClipboard();
  if (!SetClipboardData(CF_UNICODETEXT, hg)) GlobalFree(hg);
  CloseClipboard();
  g_ourClipSeq = GetClipboardSequenceNumber();
}

// ---------------------------------------------------------------- tray
void UpdateTrayTip() {
  if (!g_trayAdded) return;
  NOTIFYICONDATAW nid{sizeof(nid)};
  nid.hWnd = g_main;
  nid.uID = 1;
  nid.uFlags = NIF_TIP | NIF_SHOWTIP;
  std::wstring tip = L"DeskLink — ";
  tip += !g_device.empty() ? Fmt(S_STATUS_CONNECTED, g_device)
                           : (g_app->PairingOpen() ? std::wstring(S(S_STATUS_PAIRING)) : std::wstring(S(S_STATUS_READY)));
  wcsncpy_s(nid.szTip, tip.c_str(), _TRUNCATE);
  Shell_NotifyIconW(NIM_MODIFY, &nid);
}

void AddTray() {
  NOTIFYICONDATAW nid{sizeof(nid)};
  nid.hWnd = g_main;
  nid.uID = 1;
  nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_SHOWTIP;
  nid.uCallbackMessage = WM_TRAY;
  nid.hIcon = g_iconSmall;
  wcsncpy_s(nid.szTip, L"DeskLink", _TRUNCATE);
  Shell_NotifyIconW(NIM_DELETE, &nid);
  if (Shell_NotifyIconW(NIM_ADD, &nid)) {
    nid.uVersion = NOTIFYICON_VERSION_4;
    Shell_NotifyIconW(NIM_SETVERSION, &nid);
    g_trayAdded = true;
    UpdateTrayTip();
  } else {
    g_trayAdded = false;
  }
}

void RemoveTray() {
  if (!g_trayAdded) return;
  NOTIFYICONDATAW nid{sizeof(nid)};
  nid.hWnd = g_main;
  nid.uID = 1;
  Shell_NotifyIconW(NIM_DELETE, &nid);
  g_trayAdded = false;
}

void Balloon(const std::wstring& title, const std::wstring& text) {
  if (!g_trayAdded) return;
  NOTIFYICONDATAW nid{sizeof(nid)};
  nid.hWnd = g_main;
  nid.uID = 1;
  nid.uFlags = NIF_INFO;
  nid.dwInfoFlags = NIIF_USER | NIIF_LARGE_ICON | NIIF_RESPECT_QUIET_TIME;
  nid.hBalloonIcon = g_iconBig;
  wcsncpy_s(nid.szInfoTitle, title.c_str(), _TRUNCATE);
  wcsncpy_s(nid.szInfo, text.c_str(), _TRUNCATE);
  Shell_NotifyIconW(NIM_MODIFY, &nid);
}

const wchar_t* kRunKey = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";

bool StartupEnabled() {
  wchar_t buf[MAX_PATH * 2];
  DWORD size = sizeof(buf);
  return RegGetValueW(HKEY_CURRENT_USER, kRunKey, L"DeskLink Host", RRF_RT_REG_SZ, nullptr, buf, &size) == ERROR_SUCCESS;
}

void SetStartup(bool on) {
  HKEY k;
  if (RegOpenKeyExW(HKEY_CURRENT_USER, kRunKey, 0, KEY_SET_VALUE, &k) != ERROR_SUCCESS) return;
  if (on) {
    std::wstring v = L"\"" + ExePath() + L"\"";
    RegSetValueExW(k, L"DeskLink Host", 0, REG_SZ, (const BYTE*)v.c_str(), (DWORD)((v.size() + 1) * sizeof(wchar_t)));
  } else {
    RegDeleteValueW(k, L"DeskLink Host");
  }
  RegCloseKey(k);
}

void RunInstallElevated() {
  std::thread([] {
    SHELLEXECUTEINFOW sei{sizeof(sei)};
    sei.fMask = SEE_MASK_NOCLOSEPROCESS;
    sei.lpVerb = L"runas";
    std::wstring exe = ExePath();
    sei.lpFile = exe.c_str();
    sei.lpParameters = L"--install";
    sei.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&sei) || !sei.hProcess) return;
    WaitForSingleObject(sei.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(sei.hProcess, &code);
    CloseHandle(sei.hProcess);
    if (code == 0) PostMessageW(g_main, WM_APP_QUIT, 0, 0);  // the service's agent takes over
  }).detach();
}

void OpenReceivedFolder(const std::wstring& select) {
  std::wstring dir = ReceivedFilesDir();
  if (!select.empty()) {
    std::wstring args = L"/select,\"" + select + L"\"";
    ShellExecuteW(nullptr, L"open", L"explorer.exe", args.c_str(), nullptr, SW_SHOWNORMAL);
  } else if (!dir.empty()) {
    ShellExecuteW(nullptr, L"open", dir.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
  }
}

void ShowPairing();

void HandleCommand(UINT cmd) {
  switch (cmd) {
    case 0: break;
    case ID_PAIR: ShowPairing(); break;
    case ID_SENDFILE:
      if (auto s = g_app->Active()) PickAndSendFiles(s, false);
      break;
    case ID_DISCONNECT: g_app->StopActive(bye::STOPPED_ON_PC, "Disconnected from the PC"); break;
    case ID_OPENFILES: OpenReceivedFolder(L""); break;
    case ID_STARTUP: SetStartup(!StartupEnabled()); break;
    case ID_INSTALL: RunInstallElevated(); break;
    case ID_EXIT: Quit(); break;
    case ID_REMOTE: remote::SetEnabled(!remote::Enabled()); break;
    default:
      if (cmd >= ID_DEVICE_BASE && cmd < ID_DEVICE_BASE + g_menuDevices.size()) {
        size_t i = cmd - ID_DEVICE_BASE;
        std::wstring q = Fmt(S_MENU_REMOVE_Q, g_menuDeviceNames[i]);
        if (MessageBoxW(g_main, q.c_str(), L"DeskLink", MB_YESNO | MB_ICONQUESTION | MB_SETFOREGROUND | MB_TOPMOST) ==
            IDYES) {
          Bytes id = g_menuDevices[i];
          g_app->cfg.RemoveDevice(id);
          if (auto s = g_app->Active())
            if (s->device().id == id) s->Stop(bye::STOPPED_ON_PC, "This phone was unlinked on the PC");
        }
      }
  }
}

void ShowTrayMenu() {
  HMENU m = CreatePopupMenu();
  std::wstring header = L"DeskLink — " + U2W(g_app->hostName);
  AppendMenuW(m, MF_STRING | MF_GRAYED, 0, header.c_str());
  std::wstring status = !g_device.empty() ? Fmt(S_STATUS_CONNECTED, g_device)
                                          : (g_app->PairingOpen() ? S(S_STATUS_PAIRING) : S(S_STATUS_READY));
  AppendMenuW(m, MF_STRING | MF_GRAYED, 0, status.c_str());
  AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
  AppendMenuW(m, MF_STRING, ID_PAIR, S(S_MENU_PAIR));
  HMENU sub = CreatePopupMenu();
  g_menuDevices.clear();
  g_menuDeviceNames.clear();
  for (auto& d : g_app->cfg.Devices()) {
    g_menuDevices.push_back(d.id);
    std::wstring n = U2W(d.name);
    g_menuDeviceNames.push_back(n);
    std::wstring label = n + L"   ✕";
    AppendMenuW(sub, MF_STRING, ID_DEVICE_BASE + g_menuDevices.size() - 1, label.c_str());
  }
  if (g_menuDevices.empty()) AppendMenuW(sub, MF_STRING | MF_GRAYED, 0, S(S_MENU_NONE));
  AppendMenuW(m, MF_POPUP, (UINT_PTR)sub, S(S_MENU_PAIRED));
  if (!g_device.empty()) {
    AppendMenuW(m, MF_STRING, ID_SENDFILE, S(S_MENU_SENDFILE));
    AppendMenuW(m, MF_STRING, ID_DISCONNECT, S(S_MENU_DISCONNECT));
  }
  AppendMenuW(m, MF_STRING, ID_OPENFILES, S(S_MENU_OPENFILES));
  // reaching this PC over mobile data: what the router allowed, and the switch
  AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
  std::wstring rs = remote::Describe();
  AppendMenuW(m, MF_STRING | MF_GRAYED, 0, rs.c_str());
  AppendMenuW(m, MF_STRING | (remote::Enabled() ? MF_CHECKED : 0), ID_REMOTE, S(S_MENU_REMOTE));
  if (!g_opt.systemMode) {
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING | (StartupEnabled() ? MF_CHECKED : 0), ID_STARTUP, S(S_MENU_STARTUP));
    AppendMenuW(m, MF_STRING, ID_INSTALL, S(S_MENU_INSTALL));
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, ID_EXIT, S(S_MENU_EXIT));
  }
  SetForegroundWindow(g_main);
  POINT pt;
  GetCursorPos(&pt);
  UINT cmd = (UINT)TrackPopupMenuEx(m, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, pt.x, pt.y, g_main, nullptr);
  PostMessageW(g_main, WM_NULL, 0, 0);
  DestroyMenu(m);
  HandleCommand(cmd);
}

// ---------------------------------------------------------------- overlay pill
void RenderOverlay(bool place) {
  if (!g_overlay || g_device.empty()) return;
  float s = Scale(g_overlay);
  std::wstring text = Fmt(S_SHARED_WITH, Truncate(g_device, 28));
  FontFamily ff(L"Segoe UI");
  FontFamily ffb(L"Segoe UI Semibold");
  Font font(&ff, 13.0f * s, FontStyleRegular, UnitPixel);
  Font bfont(ffb.IsAvailable() ? &ffb : &ff, 12.5f * s, ffb.IsAvailable() ? FontStyleRegular : FontStyleBold, UnitPixel);
  RectF tb, bb;
  {
    Bitmap tmp(1, 1);
    Graphics mg(&tmp);
    mg.SetTextRenderingHint(TextRenderingHintAntiAliasGridFit);
    mg.MeasureString(text.c_str(), -1, &font, PointF(0, 0), StringFormat::GenericTypographic(), &tb);
    mg.MeasureString(S(S_STOP), -1, &bfont, PointF(0, 0), StringFormat::GenericTypographic(), &bb);
  }
  int h = (int)(36 * s), padL = (int)(14 * s), dot = (int)(8 * s), gap = (int)(9 * s), gap2 = (int)(14 * s),
      padR = (int)(5 * s);
  int btnH = (int)(26 * s), btnW = (int)(bb.Width + 28 * s);
  int w = padL + dot + gap + (int)std::ceil(tb.Width) + gap2 + btnW + padR;

  BITMAPINFO bi{};
  bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
  bi.bmiHeader.biWidth = w;
  bi.bmiHeader.biHeight = -h;
  bi.bmiHeader.biPlanes = 1;
  bi.bmiHeader.biBitCount = 32;
  bi.bmiHeader.biCompression = BI_RGB;
  void* bits = nullptr;
  HDC screen = GetDC(nullptr);
  HDC mem = CreateCompatibleDC(screen);
  HBITMAP dib = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
  HGDIOBJ old = SelectObject(mem, dib);
  {
    Bitmap bmp(w, h, w * 4, PixelFormat32bppPARGB, (BYTE*)bits);
    Graphics g(&bmp);
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    g.SetPixelOffsetMode(PixelOffsetModeHighQuality);
    g.SetTextRenderingHint(TextRenderingHintAntiAliasGridFit);
    g.Clear(Color(0, 0, 0, 0));
    GraphicsPath pill;
    RoundRectPath(pill, 0.5f, 0.5f, w - 1.0f, h - 1.0f, (h - 1) / 2.0f);
    SolidBrush bg(Color(240, 32, 33, 36));
    g.FillPath(&bg, &pill);
    Pen border(Color(56, 255, 255, 255), 1.0f);
    g.DrawPath(&border, &pill);
    SolidBrush green(Color(255, 52, 199, 89));
    g.FillEllipse(&green, (REAL)padL, (h - dot) / 2.0f, (REAL)dot, (REAL)dot);
    SolidBrush fg(Color(255, 232, 234, 237));
    g.DrawString(text.c_str(), -1, &font, PointF((REAL)(padL + dot + gap), (h - tb.Height) / 2.0f),
                 StringFormat::GenericTypographic(), &fg);
    float bx = (float)(w - padR - btnW), by = (h - btnH) / 2.0f;
    GraphicsPath bp;
    RoundRectPath(bp, bx, by, (REAL)btnW, (REAL)btnH, btnH / 2.0f);
    SolidBrush bbg(g_stopHover ? Color(255, 238, 80, 66) : Color(255, 210, 45, 35));
    g.FillPath(&bbg, &bp);
    SolidBrush white(Color(255, 255, 255, 255));
    g.DrawString(S(S_STOP), -1, &bfont, PointF(bx + (btnW - bb.Width) / 2, by + (btnH - bb.Height) / 2),
                 StringFormat::GenericTypographic(), &white);
    g_stopRect = {(LONG)bx, (LONG)by, (LONG)(bx + btnW), (LONG)(by + btnH)};
  }
  RECT wr;
  GetWindowRect(g_overlay, &wr);
  POINT pos{wr.left, wr.top};
  if (place) {
    int x = INT_MIN, y = INT_MIN;
    g_app->cfg.GetOverlayPos(x, y);
    POINT probe{x + w / 2, y + h / 2};
    if (x != INT_MIN && MonitorFromPoint(probe, MONITOR_DEFAULTTONULL)) {
      pos = {x, y};
    } else {
      MONITORINFO mi{sizeof(mi)};
      GetMonitorInfoW(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY), &mi);
      pos = {mi.rcWork.right - w - (int)(16 * s), mi.rcWork.bottom - h - (int)(16 * s)};
    }
  } else if (g_ovW && w != g_ovW) {
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromWindow(g_overlay, MONITOR_DEFAULTTONEAREST), &mi);
    if (wr.left + g_ovW / 2 > (mi.rcMonitor.left + mi.rcMonitor.right) / 2) pos.x = wr.right - w;  // keep right edge
  }
  SIZE size{w, h};
  POINT src{0, 0};
  BLENDFUNCTION bf{AC_SRC_OVER, 0, 255, AC_SRC_ALPHA};
  UpdateLayeredWindow(g_overlay, screen, &pos, &size, mem, &src, 0, &bf, ULW_ALPHA);
  g_ovW = w;
  g_ovH = h;
  SelectObject(mem, old);
  DeleteObject(dib);
  DeleteDC(mem);
  ReleaseDC(nullptr, screen);
}

LRESULT CALLBACK OverlayProc(HWND h, UINT m, WPARAM wp, LPARAM lp) {
  switch (m) {
    case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
    case WM_SETCURSOR: {
      POINT pt;
      GetCursorPos(&pt);
      ScreenToClient(h, &pt);
      SetCursor(LoadCursorW(nullptr, PtInRect(&g_stopRect, pt) ? IDC_HAND : IDC_SIZEALL));
      return TRUE;
    }
    case WM_MOUSEMOVE: {
      POINT pt{(short)LOWORD(lp), (short)HIWORD(lp)};
      bool hov = PtInRect(&g_stopRect, pt) != FALSE;
      if (hov != g_stopHover) {
        g_stopHover = hov;
        RenderOverlay(false);
      }
      TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, h, 0};
      TrackMouseEvent(&tme);
      return 0;
    }
    case WM_MOUSELEAVE:
      if (g_stopHover) {
        g_stopHover = false;
        RenderOverlay(false);
      }
      return 0;
    case WM_LBUTTONDOWN: {
      POINT pt{(short)LOWORD(lp), (short)HIWORD(lp)};
      if (PtInRect(&g_stopRect, pt)) {
        g_stopDown = true;
        SetCapture(h);
      } else {
        ReleaseCapture();
        SendMessageW(h, WM_NCLBUTTONDOWN, HTCAPTION, 0);
      }
      return 0;
    }
    case WM_LBUTTONUP: {
      POINT pt{(short)LOWORD(lp), (short)HIWORD(lp)};
      if (g_stopDown) {
        g_stopDown = false;
        ReleaseCapture();
        if (PtInRect(&g_stopRect, pt)) g_app->StopActive(bye::STOPPED_ON_PC, "Sharing was stopped on the PC");
      }
      return 0;
    }
    case WM_EXITSIZEMOVE: {
      RECT r;
      GetWindowRect(h, &r);
      g_app->cfg.SetOverlayPos(r.left, r.top);
      return 0;
    }
    case WM_DPICHANGED:
      RenderOverlay(false);
      return 0;
  }
  return DefWindowProcW(h, m, wp, lp);
}

// ---------------------------------------------------------------- pairing window
void AnswerAsk(int v) {
  if (!g_ask) return;
  {
    std::lock_guard<std::mutex> lk(g_ask->m);
    if (g_ask->result == -2) g_ask->result = v;
  }
  g_ask->cv.notify_all();
}

void PaintPair(HDC hdc) {
  RECT rc;
  GetClientRect(g_pair, &rc);
  int W = rc.right, H = rc.bottom;
  float s = Scale(g_pair);
  HDC mem = CreateCompatibleDC(hdc);
  HBITMAP bmp = CreateCompatibleBitmap(hdc, W, H);
  HGDIOBJ old = SelectObject(mem, bmp);
  {
    Graphics g(mem);
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);
    g.Clear(Color(255, 32, 33, 36));
    FontFamily ff(L"Segoe UI"), ffs(L"Segoe UI Semibold");
    FontFamily* semi = ffs.IsAvailable() ? &ffs : &ff;
    Font smallF(&ff, 12.5f * s, FontStyleRegular, UnitPixel);
    Font title(semi, 21.0f * s, FontStyleRegular, UnitPixel);
    Font body(&ff, 14.5f * s, FontStyleRegular, UnitPixel);
    Font code(semi, 44.0f * s, FontStyleRegular, UnitPixel);
    Font btn(semi, 14.0f * s, FontStyleRegular, UnitPixel);
    SolidBrush gray(Color(255, 154, 160, 166)), light(Color(255, 189, 193, 198)), white(Color(255, 241, 243, 244)),
        accent(Color(255, 138, 180, 248)), greenB(Color(255, 129, 201, 149));
    float pad = 30 * s, y = 22 * s, cw = W - 2 * pad;
    StringFormat wrap;
    wrap.SetTrimming(StringTrimmingEllipsisWord);

    std::wstring host = U2W(g_app->hostName);
    g.DrawString(Fmt(S_THIS_PC, host).c_str(), -1, &smallF, PointF(pad, y), &gray);
    y += 30 * s;

    auto drawWrapped = [&](const std::wstring& t, Font& f, Brush& b, float lineGap) {
      RectF lay(pad, y, cw, 400 * s), out;
      g.MeasureString(t.c_str(), -1, &f, lay, &wrap, &out);
      g.DrawString(t.c_str(), -1, &f, lay, &wrap, &b);
      y += out.Height + lineGap;
    };

    switch (g_pstate) {
      case PState::Waiting:
      case PState::Closed: {
        drawWrapped(S(S_PAIR_TITLE), title, white, 14 * s);
        drawWrapped(Fmt(S_PAIR_STEPS, host), body, light, 18 * s);
        if (g_pstate == PState::Waiting) {
          int left = g_app->PairingSecondsLeft();
          wchar_t t[32];
          swprintf(t, 32, L"%d:%02d", left / 60, left % 60);
          drawWrapped(Fmt(S_PAIR_WAITING, t), body, accent, 10 * s);
        } else {
          drawWrapped(S(S_PAIR_CLOSED), body, gray, 10 * s);
        }
        break;
      }
      case PState::Request:
      case PState::Confirming: {
        drawWrapped(Fmt(S_PAIR_REQ_TITLE, Truncate(g_pname, 40)), title, white, 10 * s);
        if (g_pstate == PState::Request) {
          drawWrapped(S(S_PAIR_REQ_BODY), body, light, 14 * s);
          std::wstring c = g_pcode.size() == 6 ? g_pcode.substr(0, 3) + L" " + g_pcode.substr(3) : g_pcode;
          RectF cb;
          g.MeasureString(c.c_str(), -1, &code, PointF(0, 0), &cb);
          float boxW = cb.Width + 48 * s, boxH = cb.Height + 14 * s, bx = pad;
          GraphicsPath box;
          RoundRectPath(box, bx, y, boxW, boxH, 12 * s);
          SolidBrush boxBg(Color(255, 48, 49, 52));
          g.FillPath(&boxBg, &box);
          g.DrawString(c.c_str(), -1, &code, PointF(bx + 24 * s, y + 7 * s), &white);
          y += boxH + 12 * s;
          if (!g_pmodel.empty()) drawWrapped(Fmt(S_PAIR_REQ_MODEL, g_pmodel), smallF, gray, 6 * s);
        } else {
          drawWrapped(S(S_PAIR_CONFIRMING), body, accent, 8 * s);
        }
        break;
      }
      case PState::Done: {
        float r = 13 * s;
        g.FillEllipse(&greenB, pad, y + 4 * s, r * 2, r * 2);
        Pen ck(Color(255, 32, 33, 36), 2.6f * s);
        ck.SetLineCap(LineCapRound, LineCapRound, DashCapRound);
        PointF pts[3] = {PointF(pad + r * 0.55f, y + 4 * s + r * 1.02f), PointF(pad + r * 0.88f, y + 4 * s + r * 1.35f),
                         PointF(pad + r * 1.48f, y + 4 * s + r * 0.70f)};
        g.DrawLines(&ck, pts, 3);
        RectF lay(pad + r * 2 + 12 * s, y, cw - r * 2 - 12 * s, 200 * s), out;
        std::wstring t = Fmt(S_PAIR_DONE_TITLE, Truncate(g_pname, 32));
        g.MeasureString(t.c_str(), -1, &title, lay, &wrap, &out);
        g.DrawString(t.c_str(), -1, &title, lay, &wrap, &white);
        y += std::max(out.Height, r * 2 + 8 * s) + 12 * s;
        drawWrapped(S(S_PAIR_DONE_BODY), body, light, 8 * s);
        break;
      }
      case PState::Failed:
        drawWrapped(S(S_PAIR_FAIL_TITLE), title, white, 12 * s);
        drawWrapped(Fmt(S_PAIR_FAIL_BODY, Truncate(g_pname, 40)), body, light, 8 * s);
        break;
    }

    // buttons
    g_pbtns.clear();
    std::vector<std::pair<int, bool>> spec;  // id, primary (right-most last)
    switch (g_pstate) {
      case PState::Waiting: spec = {{B_CLOSE, true}}; break;
      case PState::Closed: spec = {{B_CLOSE, false}, {B_REOPEN, true}}; break;
      case PState::Request: spec = {{B_DENY, false}, {B_ALLOW, true}}; break;
      case PState::Confirming: break;
      case PState::Done: spec = {{B_CLOSE, true}}; break;
      case PState::Failed: spec = {{B_CLOSE, false}, {B_REOPEN, true}}; break;
    }
    bool showInstall = !g_opt.systemMode && !g_installed &&
                       (g_pstate == PState::Waiting || g_pstate == PState::Closed || g_pstate == PState::Done);
    float bh = 38 * s, by = H - pad * 0.8f - bh, bx = W - pad;
    auto label = [](int id) -> std::wstring {
      switch (id) {
        case B_CLOSE: return S(S_CLOSE);
        case B_REOPEN: return S(S_REOPEN);
        case B_ALLOW: return S(S_ALLOW);
        case B_DENY: return S(S_DENY);
        case B_INSTALL: return S(S_INSTALL_BUTTON);
      }
      return L"";
    };
    auto drawBtn = [&](int id, bool primary, float right) -> float {
      std::wstring t = label(id);
      RectF tb;
      g.MeasureString(t.c_str(), -1, &btn, PointF(0, 0), &tb);
      float bw = std::max(100 * s, tb.Width + 36 * s), x = right - bw;
      GraphicsPath p;
      RoundRectPath(p, x, by, bw, bh, 9 * s);
      bool hov = g_phover == id;
      if (primary) {
        SolidBrush b(hov ? Color(255, 174, 203, 250) : Color(255, 138, 180, 248));
        g.FillPath(&b, &p);
      } else {
        SolidBrush b(hov ? Color(255, 60, 64, 67) : Color(255, 41, 42, 45));
        g.FillPath(&b, &p);
        Pen pen(Color(255, 95, 99, 104), 1.0f * s);
        g.DrawPath(&pen, &p);
      }
      SolidBrush tbr(primary ? Color(255, 32, 33, 36) : Color(255, 232, 234, 237));
      g.DrawString(t.c_str(), -1, &btn, PointF(x + (bw - tb.Width) / 2, by + (bh - tb.Height) / 2), &tbr);
      g_pbtns.push_back(Btn{{(LONG)x, (LONG)by, (LONG)(x + bw), (LONG)(by + bh)}, id, primary, t});
      return x;
    };
    float right = bx;
    for (auto it = spec.rbegin(); it != spec.rend(); ++it) right = drawBtn(it->first, it->second, right) - 10 * s;
    if (showInstall) {
      float x0 = pad;
      RectF hint(x0, by - 52 * s, cw, 44 * s);
      g.DrawString(S(S_INSTALL_HINT), -1, &smallF, hint, &wrap, &gray);
      std::wstring t = label(B_INSTALL);
      RectF tb;
      g.MeasureString(t.c_str(), -1, &btn, PointF(0, 0), &tb);
      drawBtn(B_INSTALL, false, x0 + std::max(100 * s, tb.Width + 36 * s));
    }
  }
  BitBlt(hdc, 0, 0, W, H, mem, 0, 0, SRCCOPY);
  SelectObject(mem, old);
  DeleteObject(bmp);
  DeleteDC(mem);
}

void SizePairWindow() {
  float s = Scale(g_pair);
  RECT r{0, 0, (LONG)(500 * s), (LONG)(370 * s)};
  AdjustWindowRectExForDpi(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, FALSE, 0, GetDpiForWindow(g_pair));
  MONITORINFO mi{sizeof(mi)};
  GetMonitorInfoW(MonitorFromWindow(g_pair, MONITOR_DEFAULTTOPRIMARY), &mi);
  int w = r.right - r.left, h = r.bottom - r.top;
  int x = (mi.rcWork.left + mi.rcWork.right - w) / 2, y = (mi.rcWork.top + mi.rcWork.bottom - h) / 2;
  SetWindowPos(g_pair, nullptr, x, y, w, h, SWP_NOZORDER | SWP_NOACTIVATE);
}

LRESULT CALLBACK PairProc(HWND h, UINT m, WPARAM wp, LPARAM lp) {
  switch (m) {
    case WM_PAINT: {
      PAINTSTRUCT ps;
      HDC hdc = BeginPaint(h, &ps);
      PaintPair(hdc);
      EndPaint(h, &ps);
      return 0;
    }
    case WM_ERASEBKGND: return 1;
    case WM_TIMER:
      if (g_pstate == PState::Waiting && !g_app->PairingOpen()) g_pstate = PState::Closed;
      if (g_pstate == PState::Waiting || g_pstate == PState::Closed) InvalidateRect(h, nullptr, FALSE);
      return 0;
    case WM_MOUSEMOVE: {
      POINT pt{(short)LOWORD(lp), (short)HIWORD(lp)};
      int hov = -1;
      for (auto& b : g_pbtns)
        if (PtInRect(&b.r, pt)) hov = b.id;
      if (hov != g_phover) {
        g_phover = hov;
        InvalidateRect(h, nullptr, FALSE);
      }
      TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, h, 0};
      TrackMouseEvent(&tme);
      return 0;
    }
    case WM_MOUSELEAVE:
      if (g_phover != -1) {
        g_phover = -1;
        InvalidateRect(h, nullptr, FALSE);
      }
      return 0;
    case WM_SETCURSOR:
      if (LOWORD(lp) == HTCLIENT) {
        SetCursor(LoadCursorW(nullptr, g_phover != -1 ? IDC_HAND : IDC_ARROW));
        return TRUE;
      }
      break;
    case WM_LBUTTONUP: {
      POINT pt{(short)LOWORD(lp), (short)HIWORD(lp)};
      for (auto& b : g_pbtns) {
        if (!PtInRect(&b.r, pt)) continue;
        switch (b.id) {
          case B_CLOSE: ShowWindow(h, SW_HIDE); break;
          case B_REOPEN:
            g_app->OpenPairing(600);
            g_pstate = PState::Waiting;
            break;
          case B_ALLOW:
            AnswerAsk(1);
            g_pstate = PState::Confirming;
            break;
          case B_DENY:
            AnswerAsk(0);
            g_pstate = PState::Failed;
            break;
          case B_INSTALL: RunInstallElevated(); break;
        }
        SetWindowPos(h, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        InvalidateRect(h, nullptr, FALSE);
        break;
      }
      return 0;
    }
    case WM_KEYDOWN:
      if (wp == VK_ESCAPE) SendMessageW(h, WM_CLOSE, 0, 0);
      return 0;
    case WM_CLOSE:
      if (g_pstate == PState::Request) {
        AnswerAsk(0);
        g_pstate = PState::Failed;
      }
      ShowWindow(h, SW_HIDE);
      return 0;
    case WM_DPICHANGED: {
      RECT* r = (RECT*)lp;
      SetWindowPos(h, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
      InvalidateRect(h, nullptr, FALSE);
      return 0;
    }
  }
  return DefWindowProcW(h, m, wp, lp);
}

void EnsurePairWindow() {
  if (g_pair) return;
  g_pair = CreateWindowExW(0, L"DeskLinkPairing", S(S_PAIR_WINDOW), WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                           CW_USEDEFAULT, CW_USEDEFAULT, 500, 370, nullptr, nullptr, g_inst, nullptr);
  BOOL dark = TRUE;
  DwmSetWindowAttribute(g_pair, 20 /*DWMWA_USE_IMMERSIVE_DARK_MODE*/, &dark, sizeof(dark));
  int corner = 2 /*DWMWCP_ROUND*/;
  DwmSetWindowAttribute(g_pair, 33 /*DWMWA_WINDOW_CORNER_PREFERENCE*/, &corner, sizeof(corner));
  SendMessageW(g_pair, WM_SETICON, ICON_SMALL, (LPARAM)g_iconSmall);
  SendMessageW(g_pair, WM_SETICON, ICON_BIG, (LPARAM)g_iconBig);
  SizePairWindow();
  SetTimer(g_pair, 1, 1000, nullptr);
}

void BringToFront(HWND h, bool topmost) {
  ShowWindow(h, SW_SHOWNORMAL);
  ShowWindow(h, SW_SHOWNORMAL);  // the first ShowWindow of a process may be overridden by STARTUPINFO
  if (topmost) SetWindowPos(h, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
  HWND fg = GetForegroundWindow();
  DWORD fgThread = fg ? GetWindowThreadProcessId(fg, nullptr) : 0, me = GetCurrentThreadId();
  if (fgThread && fgThread != me) AttachThreadInput(me, fgThread, TRUE);
  SetForegroundWindow(h);
  if (fgThread && fgThread != me) AttachThreadInput(me, fgThread, FALSE);
  FLASHWINFO fi{sizeof(fi), h, FLASHW_TRAY | FLASHW_TIMERNOFG, 3, 0};
  FlashWindowEx(&fi);
}

void ShowPairing() {
  if (!g_app->PairingOpen()) g_app->OpenPairing(600);
  EnsurePairWindow();
  g_installed = !g_opt.systemMode && IsServiceInstalled();
  if (g_pstate != PState::Request && g_pstate != PState::Confirming) g_pstate = PState::Waiting;
  InvalidateRect(g_pair, nullptr, FALSE);
  BringToFront(g_pair, false);
}

// ---------------------------------------------------------------- main window
LRESULT CALLBACK MainProc(HWND h, UINT m, WPARAM wp, LPARAM lp) {
  if (m == g_taskbarCreated && g_taskbarCreated) {
    if (g_withTray) AddTray();
    return 0;
  }
  switch (m) {
    case WM_TRAY:
      switch (LOWORD(lp)) {
        case WM_CONTEXTMENU:
        case NIN_SELECT:
        case NIN_KEYSELECT:
          ShowTrayMenu();
          break;
        case NIN_BALLOONUSERCLICK:
          if (!g_lastFile.empty()) OpenReceivedFolder(g_lastFile);
          break;
      }
      return 0;
    case WM_SESS_START: {
      auto* name = (std::wstring*)lp;
      g_device = *name;
      delete name;
      g_stopHover = false;
      RenderOverlay(!g_ovPlaced);
      g_ovPlaced = true;
      ShowWindow(g_overlay, SW_SHOWNOACTIVATE);
      SetWindowPos(g_overlay, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
      // keep the PC and its display awake while someone is connected
      SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
      UpdateTrayTip();
      return 0;
    }
    case WM_SESS_END:
      if (!g_app->Active()) {
        g_device.clear();
        ShowWindow(g_overlay, SW_HIDE);
        SetThreadExecutionState(ES_CONTINUOUS);
      }
      UpdateTrayTip();
      return 0;
    case WM_SET_CLIP: {
      auto* s = (std::string*)lp;
      WriteClipboardText(*s);
      delete s;
      return 0;
    }
    case WM_CLIP_PUSH: {
      std::string t;
      if (ReadClipboardText(t)) g_app->OnLocalClipboard(t);
      return 0;
    }
    case WM_CLIPBOARDUPDATE: {
      if (GetClipboardSequenceNumber() == g_ourClipSeq) return 0;
      if (!g_app->Active()) return 0;
      std::string t;
      if (ReadClipboardText(t)) g_app->OnLocalClipboard(t);
      return 0;
    }
    case WM_PAIR_ASK: {
      auto* p = (std::shared_ptr<PairAsk>*)lp;
      if (g_ask && g_ask != *p) AnswerAsk(0);  // only one request at a time
      g_ask = *p;
      delete p;
      g_pname = U2W(g_ask->name);
      g_pmodel = U2W(g_ask->model);
      g_pcode = U2W(g_ask->code);
      EnsurePairWindow();
      g_pstate = PState::Request;
      InvalidateRect(g_pair, nullptr, FALSE);
      BringToFront(g_pair, true);
      MessageBeep(MB_ICONQUESTION);
      return 0;
    }
    case WM_PAIR_DONE: {
      auto* name = (std::wstring*)lp;
      g_pname = *name;
      delete name;
      g_ask.reset();
      EnsurePairWindow();
      g_pstate = wp ? PState::Done : PState::Failed;
      SetWindowPos(g_pair, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
      if (IsWindowVisible(g_pair) || wp) {
        InvalidateRect(g_pair, nullptr, FALSE);
        ShowWindow(g_pair, SW_SHOWNORMAL);
      }
      UpdateTrayTip();
      return 0;
    }
    case WM_BALLOON: {
      auto* p = (std::pair<std::wstring, std::wstring>*)lp;
      Balloon(p->first, p->second);
      delete p;
      return 0;
    }
    case WM_FILE_RX: {
      auto* p = (std::wstring*)lp;
      g_lastFile = *p;
      delete p;
      std::wstring name = g_lastFile.substr(g_lastFile.find_last_of(L'\\') + 1);
      Balloon(S(S_FILE_RECEIVED), name);
      return 0;
    }
    case WM_REFRESH:
      UpdateTrayTip();
      if (g_pair && IsWindowVisible(g_pair)) InvalidateRect(g_pair, nullptr, FALSE);
      return 0;
    case WM_APP_SHOW_PAIRING:
      ShowPairing();
      return 0;
    case WM_APP_QUIT:
      DestroyWindow(h);
      return 0;
    case WM_TIMER:
      if (g_overlay && IsWindowVisible(g_overlay))
        SetWindowPos(g_overlay, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
      if (g_withTray && !g_trayAdded) AddTray();
      return 0;
    case WM_DESTROY:
      RemoveClipboardFormatListener(h);
      RemoveTray();
      PostQuitMessage(0);
      return 0;
  }
  return DefWindowProcW(h, m, wp, lp);
}

void Post(UINT msg, WPARAM wp, LPARAM lp) {
  if (!g_main || !PostMessageW(g_main, msg, wp, lp)) {
    // UI not running: free heap payloads
    if (msg == WM_SESS_START || msg == WM_PAIR_DONE || msg == WM_FILE_RX) delete (std::wstring*)lp;
    if (msg == WM_SET_CLIP) delete (std::string*)lp;
    if (msg == WM_BALLOON) delete (std::pair<std::wstring, std::wstring>*)lp;
  }
}

}  // namespace

#ifdef DESKLINK_TESTING
static int PngEncoder(CLSID* clsid) {
  UINT n = 0, size = 0;
  GetImageEncodersSize(&n, &size);
  std::vector<uint8_t> buf(size);
  auto* info = (ImageCodecInfo*)buf.data();
  GetImageEncoders(n, size, info);
  for (UINT i = 0; i < n; i++)
    if (wcscmp(info[i].MimeType, L"image/png") == 0) {
      *clsid = info[i].Clsid;
      return (int)i;
    }
  return -1;
}

int RenderPreviews(const std::wstring& dir) {
  static App previewApp;
  previewApp.hostName = "OFFICE-PC";  // the README's pictures: not this PC's own name
  previewApp.OpenPairing(594);        // "Waiting for a phone…  9:54 left"
  g_app = &previewApp;
  g_inst = GetModuleHandleW(nullptr);
  GdiplusStartupInput si;
  GdiplusStartup(&g_gdip, &si, nullptr);
  WNDCLASSEXW wc{sizeof(wc)};
  wc.hInstance = g_inst;
  wc.lpfnWndProc = DefWindowProcW;
  wc.lpszClassName = L"DeskLinkPairing";
  RegisterClassExW(&wc);
  g_pair = CreateWindowExW(0, L"DeskLinkPairing", L"x", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, 0, 0, 500, 370, nullptr,
                           nullptr, g_inst, nullptr);
  RECT r{0, 0, 500, 370};
  AdjustWindowRectEx(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0);
  SetWindowPos(g_pair, nullptr, 0, 0, r.right - r.left, r.bottom - r.top, SWP_NOZORDER | SWP_NOACTIVATE);
  CLSID png;
  PngEncoder(&png);
  g_pname = L"Galaxy S23";
  g_pmodel = L"Samsung SM-S911B";
  g_pcode = L"482177";
  struct {
    PState s;
    const wchar_t* file;
  } states[] = {{PState::Waiting, L"pair-waiting.png"}, {PState::Request, L"pair-request.png"},
                {PState::Done, L"pair-done.png"}, {PState::Failed, L"pair-failed.png"}};
  for (auto& st : states) {
    g_pstate = st.s;
    RECT cr;
    GetClientRect(g_pair, &cr);
    HDC screen = GetDC(nullptr);
    HDC mem = CreateCompatibleDC(screen);
    HBITMAP bmp = CreateCompatibleBitmap(screen, cr.right, cr.bottom);
    HGDIOBJ old = SelectObject(mem, bmp);
    PaintPair(mem);
    SelectObject(mem, old);
    {
      Bitmap b(bmp, nullptr);
      b.Save((dir + L"\\" + st.file).c_str(), &png, nullptr);
    }
    DeleteObject(bmp);
    DeleteDC(mem);
    ReleaseDC(nullptr, screen);
  }
  DestroyWindow(g_pair);
  GdiplusShutdown(g_gdip);
  return 0;
}
#endif
bool Init(bool withTray) {
  g_inst = GetModuleHandleW(nullptr);
  GdiplusStartupInput si;
  GdiplusStartup(&g_gdip, &si, nullptr);
  LoadIconMetric(g_inst, MAKEINTRESOURCEW(1), LIM_SMALL, &g_iconSmall);
  LoadIconMetric(g_inst, MAKEINTRESOURCEW(1), LIM_LARGE, &g_iconBig);
  g_cfExclude = RegisterClipboardFormatW(L"ExcludeClipboardContentFromMonitorProcessing");

  WNDCLASSEXW wc{sizeof(wc)};
  wc.hInstance = g_inst;
  wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  wc.hIcon = g_iconBig;
  wc.hIconSm = g_iconSmall;
  wc.lpfnWndProc = MainProc;
  wc.lpszClassName = kMainClass;
  RegisterClassExW(&wc);
  wc.lpfnWndProc = OverlayProc;
  wc.lpszClassName = L"DeskLinkOverlay";
  RegisterClassExW(&wc);
  wc.lpfnWndProc = PairProc;
  wc.lpszClassName = L"DeskLinkPairing";
  wc.hbrBackground = nullptr;
  RegisterClassExW(&wc);

  g_main = CreateWindowExW(0, kMainClass, L"DeskLink Host", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr, g_inst, nullptr);
  if (!g_main) return false;
  g_taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");
  // Explorer (and a second DeskLinkHost.exe) run at a lower integrity level than the SYSTEM agent.
  ChangeWindowMessageFilterEx(g_main, WM_TRAY, MSGFLT_ALLOW, nullptr);
  ChangeWindowMessageFilterEx(g_main, g_taskbarCreated, MSGFLT_ALLOW, nullptr);
  ChangeWindowMessageFilterEx(g_main, WM_APP_SHOW_PAIRING, MSGFLT_ALLOW, nullptr);
  AddClipboardFormatListener(g_main);

  g_overlay = CreateWindowExW(WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, L"DeskLinkOverlay",
                              L"DeskLink", WS_POPUP, 0, 0, 10, 10, nullptr, nullptr, g_inst, nullptr);
  g_withTray = withTray;
  if (withTray) AddTray();
  SetTimer(g_main, 1, 2000, nullptr);
  return true;
}

int Run() {
  MSG msg;
  while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
    TranslateMessage(&msg);
    DispatchMessageW(&msg);
  }
  if (g_gdip) GdiplusShutdown(g_gdip);
  return 0;
}

void Quit() {
  if (g_main) PostMessageW(g_main, WM_APP_QUIT, 0, 0);
}

void SessionStarted(const std::string& deviceName) { Post(WM_SESS_START, 0, (LPARAM) new std::wstring(U2W(deviceName))); }
void SessionEnded() { Post(WM_SESS_END, 0, 0); }
void SetClipboardText(const std::string& utf8) { Post(WM_SET_CLIP, 0, (LPARAM) new std::string(utf8)); }
void RequestClipboardPush() { Post(WM_CLIP_PUSH, 0, 0); }
void FileReceived(const std::wstring& path) { Post(WM_FILE_RX, 0, (LPARAM) new std::wstring(path)); }
void Notify(const std::wstring& title, const std::wstring& text) {
  Post(WM_BALLOON, 0, (LPARAM) new std::pair<std::wstring, std::wstring>(title, text));
}
void RefreshTray() { Post(WM_REFRESH, 0, 0); }
void ShowPairingWindow() { Post(WM_APP_SHOW_PAIRING, 0, 0); }

int AskPairing(const std::string& deviceName, const std::string& model, const std::string& code, int timeoutMs) {
#ifdef DESKLINK_TESTING
  if (g_opt.testAutoApprove) {
    Log("ui: TEST BUILD auto-approving pairing of '%s' (code %s)", deviceName.c_str(), code.c_str());
    return 1;
  }
#endif
  if (!g_main) return -1;
  auto ask = std::make_shared<PairAsk>();
  ask->name = deviceName;
  ask->model = model;
  ask->code = code;
  PostMessageW(g_main, WM_PAIR_ASK, 0, (LPARAM) new std::shared_ptr<PairAsk>(ask));
  std::unique_lock<std::mutex> lk(ask->m);
  if (!ask->cv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return ask->result != -2; })) ask->result = -1;
  return ask->result;
}

void PairingFinished(bool ok, const std::string& deviceName) {
  Post(WM_PAIR_DONE, ok ? 1 : 0, (LPARAM) new std::wstring(U2W(deviceName)));
}

}  // namespace ui
