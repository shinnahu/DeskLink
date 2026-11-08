// DeskLink Host — entry point.
//
//   DeskLinkHost.exe                 run (or, if the service is running, open its pairing window)
//   DeskLinkHost.exe --install       install as a Windows service (asks for admin)
//   DeskLinkHost.exe --uninstall     remove the service, firewall rule and files
//   options: --port N  --bind ADDR  --data DIR  --no-ui  --exit-after SECONDS
#include "common.h"

#include <objbase.h>
#include <shellapi.h>

#include <cstdio>

#include "app.h"
#include "crypto.h"
#include "files.h"
#include "remote.h"
#include "server.h"
#include "service.h"
#include "session.h"
#include "ui.h"

#pragma comment(lib, "ws2_32.lib")

int RunCaptureSelfTest(int seconds, const std::wstring& out, int maxW, int maxH, int fps, int kbps, int monitor);
int RunInputSelfTest();
int RunHomeSelfTest();

static int RunAgent(bool noUi, int exitAfterSec) {
  std::wstring mutexName = g_opt.systemMode ? L"Global\\DeskLinkHostAgent" : L"Local\\DeskLinkHostUser";
  if (!g_opt.dataDir.empty()) mutexName += L"-test";
  HANDLE mutex = CreateMutexW(nullptr, TRUE, mutexName.c_str());
  if (GetLastError() == ERROR_ALREADY_EXISTS) {
    if (HWND w = FindWindowW(ui::kMainClass, nullptr)) PostMessageW(w, ui::WM_APP_SHOW_PAIRING, 0, 0);
    Log("main: another instance is running");
    return 0;
  }

  WSADATA wsa;
  WSAStartup(MAKEWORD(2, 2), &wsa);
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);

  static App app;
  g_app = &app;
  app.noUi = noUi;
  if (!app.Init()) return 2;

  Server server;
  if (!server.Start(g_opt.bindAddr, app.cfg.port)) {
    if (!noUi && !g_opt.systemMode) {
      MessageBoxW(nullptr,
                  IsSpanishUi() ? L"DeskLink no pudo abrir el puerto de red 47810. ¿Ya hay otro DeskLink Host en ejecución?"
                                : L"DeskLink could not open network port 47810. Is another DeskLink Host already running?",
                  L"DeskLink", MB_ICONERROR);
    }
    return 3;
  }

  // Reaching the PC over mobile data: the router forwards our port (renewed in the background, removed on exit).
  remote::Start(server.port());

  // Quit signals: the service (SYSTEM agent) or the installer (user-mode instance).
  HANDLE quitEv = g_opt.systemMode ? OpenEventW(SYNCHRONIZE, FALSE, kAgentQuitEvent)
                                   : CreateEventW(nullptr, TRUE, FALSE, kUserQuitEvent);
  if (quitEv) {
    std::thread([quitEv, noUi] {
      WaitForSingleObject(quitEv, INFINITE);
      Log("main: quit requested");
      if (noUi) {
        remote::Stop();
        ExitProcess(0);
      }
      ui::Quit();
    }).detach();
  }
  if (exitAfterSec > 0) {
    std::thread([exitAfterSec, noUi] {
      Sleep((DWORD)exitAfterSec * 1000);
      Log("main: --exit-after reached");
      if (noUi) {
        g_app->StopActive(bye::HOST_EXIT, "Host exiting");
        remote::Stop();  // closes the router port again
        Sleep(500);
        ExitProcess(0);
      }
      ui::Quit();
    }).detach();
  }

  if (noUi) {
    for (;;) Sleep(1000);
  }
  ui::Init(true);
  if (app.cfg.DeviceCount() == 0) ui::ShowPairingWindow();
  ui::Run();

  app.StopActive(bye::HOST_EXIT, "The PC host is shutting down");
  for (int i = 0; i < 40 && app.Active(); i++) Sleep(25);
  remote::Stop();  // closes the router port again
  server.Stop();
  app.input.Stop();
  if (mutex) CloseHandle(mutex);
  return 0;
}

// True if both files exist and their contents differ (a different build of DeskLinkHost.exe).
static bool FilesDiffer(const std::wstring& a, const std::wstring& b) {
  if (_wcsicmp(a.c_str(), b.c_str()) == 0) return false;
  auto read = [](const std::wstring& p, std::string& out) {
    FILE* f = _wfopen(p.c_str(), L"rb");
    if (!f) return false;
    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    fclose(f);
    return true;
  };
  std::string x, y;
  return read(a, x) && read(b, y) && x != y;
}

static int AppMain(int argc, wchar_t** argv) {
  std::vector<std::wstring> args(argv + 1, argv + argc);
  auto has = [&](const wchar_t* a) {
    for (auto& s : args)
      if (_wcsicmp(s.c_str(), a) == 0) return true;
    return false;
  };
  auto val = [&](const wchar_t* a) -> std::wstring {
    for (size_t i = 0; i + 1 < args.size(); i++)
      if (_wcsicmp(args[i].c_str(), a) == 0) return args[i + 1];
    return L"";
  };
  g_opt.dataDir = val(L"--data");
  g_opt.bindAddr = W2U(val(L"--bind"));
  g_opt.port = _wtoi(val(L"--port").c_str());
  bool quiet = has(L"--quiet");
#ifdef DESKLINK_TESTING
  g_opt.testAutoApprove = has(L"--auto-approve");
  g_opt.testSendFile = val(L"--test-send-file");
  g_opt.testPeersAreInternet = has(L"--peers-are-internet");
  g_opt.testNoInject = has(L"--no-inject");
  g_opt.testFakeScreens = _wtoi(val(L"--fake-screens").c_str());
  g_opt.testRouterIsLoopback = has(L"--router-is-loopback");
  if (!val(L"--upnp-url").empty()) remote::SetTestLocation(W2U(val(L"--upnp-url")));
#endif

  if (has(L"--service")) {
    g_opt.systemMode = true;
    LogOpen(DataDir() + L"\\host.log", false);
    return RunService();
  }
  if (has(L"--install")) return RunInstall(quiet);
  if (has(L"--uninstall")) return RunUninstall(quiet);
  if (has(L"--pick-files")) return RunPickFilesHelper();

  g_opt.systemMode = has(L"--system") && IsSystemAccount();
  LogOpen(DataDir() + L"\\host.log", g_opt.console);
  if (!crypto::Init()) {
    Log("main: crypto init failed");
    return 1;
  }

  for (size_t i = 0; i < args.size(); i++) {
    if (_wcsicmp(args[i].c_str(), L"--test-capture") == 0 && i + 2 < args.size()) {
      int secs = _wtoi(args[i + 1].c_str());
      std::wstring out = args[i + 2];
      int w = i + 3 < args.size() ? _wtoi(args[i + 3].c_str()) : 1920;
      int h = i + 4 < args.size() ? _wtoi(args[i + 4].c_str()) : 1080;
      int fps = i + 5 < args.size() ? _wtoi(args[i + 5].c_str()) : 60;
      int kbps = i + 6 < args.size() ? _wtoi(args[i + 6].c_str()) : 0;
      // monitor: "all" (every screen in one picture), an index, or the main screen when left out
      int mon = -1;
      if (i + 7 < args.size()) mon = _wcsicmp(args[i + 7].c_str(), L"all") == 0 ? kAllScreens : _wtoi(args[i + 7].c_str());
      CoInitializeEx(nullptr, COINIT_MULTITHREADED);
      return RunCaptureSelfTest(secs, out, w, h, fps, kbps, mon);
    }
  }
#ifdef DESKLINK_TESTING
  if (has(L"--test-input")) return RunInputSelfTest();
  if (has(L"--test-home")) return RunHomeSelfTest();
  if (!val(L"--test-render-ui").empty()) return ui::RenderPreviews(val(L"--test-render-ui"));
#endif

  // Plain double-click while the service is running: if this is a different DeskLinkHost.exe (a newer one), offer
  // to update the installed service with it; otherwise just bring up its pairing window.
  if (!has(L"--agent") && !g_opt.systemMode && g_opt.dataDir.empty() && IsServiceRunning()) {
    if (FilesDiffer(ExePath(), InstalledExePath())) {
      const wchar_t* q = IsSpanishUi() ? L"DeskLink ya está instalado en esta PC.\n\n¿Actualizarlo a esta versión?"
                                       : L"DeskLink is already installed on this PC.\n\nUpdate it to this version?";
      if (MessageBoxW(nullptr, q, L"DeskLink", MB_YESNO | MB_ICONQUESTION | MB_SETFOREGROUND) == IDYES) return RunInstall(false);
    }
    if (HWND w = FindWindowW(ui::kMainClass, nullptr)) PostMessageW(w, ui::WM_APP_SHOW_PAIRING, 0, 0);
    return 0;
  }
  Log("main: starting agent (%s mode) from %s", g_opt.systemMode ? "service" : "user", W2U(ExePath()).c_str());
  return RunAgent(has(L"--no-ui"), _wtoi(val(L"--exit-after").c_str()));
}

#ifdef DESKLINK_CONSOLE
int wmain(int argc, wchar_t** argv) {
  g_opt.console = true;
  return AppMain(argc, argv);
}
#else
int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
  int argc = 0;
  wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  int r = AppMain(argc, argv);
  LocalFree(argv);
  return r;
}
#endif
