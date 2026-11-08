#include "service.h"

#include <aclapi.h>
#include <sddl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <userenv.h>
#include <wtsapi32.h>

#include <cstdio>
#include <sstream>

#include "crypto.h"
#include "ui.h"

#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "userenv.lib")
#pragma comment(lib, "wtsapi32.lib")

static const wchar_t* kDisplay = L"DeskLink Host";
static const wchar_t* kUninstallKey = L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\DeskLinkHost";

bool IsServiceInstalled() {
  SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
  if (!scm) return false;
  SC_HANDLE s = OpenServiceW(scm, kServiceName, SERVICE_QUERY_STATUS);
  bool ok = s != nullptr;
  if (s) CloseServiceHandle(s);
  CloseServiceHandle(scm);
  return ok;
}

bool IsServiceRunning() {
  SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
  if (!scm) return false;
  bool running = false;
  SC_HANDLE s = OpenServiceW(scm, kServiceName, SERVICE_QUERY_STATUS);
  if (s) {
    SERVICE_STATUS st{};
    if (QueryServiceStatus(s, &st)) running = st.dwCurrentState == SERVICE_RUNNING;
    CloseServiceHandle(s);
  }
  CloseServiceHandle(scm);
  return running;
}

bool IsProcessElevated() {
  HANDLE tok = nullptr;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &tok)) return false;
  TOKEN_ELEVATION e{};
  DWORD len = 0;
  bool r = GetTokenInformation(tok, TokenElevation, &e, sizeof(e), &len) && e.TokenIsElevated;
  CloseHandle(tok);
  return r;
}

static int RelaunchElevated(const wchar_t* args) {
  SHELLEXECUTEINFOW sei{sizeof(sei)};
  sei.fMask = SEE_MASK_NOCLOSEPROCESS;
  sei.lpVerb = L"runas";
  std::wstring exe = ExePath();
  sei.lpFile = exe.c_str();
  sei.lpParameters = args;
  sei.nShow = SW_SHOWNORMAL;
  if (!ShellExecuteExW(&sei) || !sei.hProcess) return 1;
  WaitForSingleObject(sei.hProcess, INFINITE);
  DWORD code = 1;
  GetExitCodeProcess(sei.hProcess, &code);
  CloseHandle(sei.hProcess);
  return (int)code;
}

static DWORD RunHidden(const std::wstring& cmdline) {
  STARTUPINFOW si{sizeof(si)};
  si.dwFlags = STARTF_USESHOWWINDOW;
  si.wShowWindow = SW_HIDE;
  PROCESS_INFORMATION pi{};
  std::wstring c = cmdline;
  if (!CreateProcessW(nullptr, c.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi))
    return GetLastError();
  WaitForSingleObject(pi.hProcess, 30000);
  DWORD code = 1;
  GetExitCodeProcess(pi.hProcess, &code);
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return code;
}

static std::wstring KnownFolder(REFKNOWNFOLDERID id) {
  PWSTR p = nullptr;
  std::wstring r;
  if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) r = p;
  if (p) CoTaskMemFree(p);
  return r;
}

static void StopAndWait(SC_HANDLE svc) {
  SERVICE_STATUS st{};
  if (!QueryServiceStatus(svc, &st) || st.dwCurrentState == SERVICE_STOPPED) return;
  ControlService(svc, SERVICE_CONTROL_STOP, &st);
  for (int i = 0; i < 60; i++) {
    if (QueryServiceStatus(svc, &st) && st.dwCurrentState == SERVICE_STOPPED) return;
    Sleep(250);
  }
}

// Moves the per-user identity (user-scope DPAPI) to ProgramData (machine-scope DPAPI) so phones
// that were paired in "run without installing" mode keep working with the service.
static void MigrateConfig(const std::wstring& progData) {
  std::wstring src = KnownFolder(FOLDERID_LocalAppData) + L"\\DeskLink\\config.txt";
  std::wstring dst = progData + L"\\config.txt";
  if (GetFileAttributesW(dst.c_str()) != INVALID_FILE_ATTRIBUTES) return;
  FILE* f = _wfopen(src.c_str(), L"rb");
  if (!f) return;
  std::string text;
  char buf[4096];
  size_t n;
  while ((n = fread(buf, 1, sizeof(buf), f)) > 0) text.append(buf, n);
  fclose(f);
  std::istringstream in(text);
  std::string line, out;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.rfind("host_key=", 0) == 0) {
      Bytes prot, blob, prot2;
      if (B64Decode(line.substr(9), prot) && crypto::Unprotect(prot, blob) && crypto::Protect(blob, prot2, true))
        line = "host_key=" + B64Encode(prot2);
      SecureZeroMemory(blob.data(), blob.size());
    }
    out += line + "\n";
  }
  FILE* o = _wfopen(dst.c_str(), L"wb");
  if (o) {
    fwrite(out.data(), 1, out.size(), o);
    fclose(o);
  }
}

static void LockDownDir(const std::wstring& dir) {
  PSECURITY_DESCRIPTOR sd = nullptr;
  // SYSTEM and Administrators only (identity key + pairing tokens live here)
  if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)", SDDL_REVISION_1, &sd,
                                                            nullptr))
    return;
  BOOL present = FALSE, defaulted = FALSE;
  PACL dacl = nullptr;
  GetSecurityDescriptorDacl(sd, &present, &dacl, &defaulted);
  SetNamedSecurityInfoW((LPWSTR)dir.c_str(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                        nullptr, nullptr, dacl, nullptr);
  LocalFree(sd);
}

std::wstring InstalledExePath() { return KnownFolder(FOLDERID_ProgramFiles) + L"\\DeskLink\\DeskLinkHost.exe"; }

int RunInstall(bool quiet) {
  if (!IsProcessElevated()) return RelaunchElevated(quiet ? L"--install --quiet" : L"--install");
  crypto::Init();
  std::wstring dir = KnownFolder(FOLDERID_ProgramFiles) + L"\\DeskLink";
  std::wstring target = dir + L"\\DeskLinkHost.exe";
  std::wstring progData = KnownFolder(FOLDERID_ProgramData) + L"\\DeskLink";
  SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
  SHCreateDirectoryExW(nullptr, progData.c_str(), nullptr);

  SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_ALL_ACCESS);
  if (!scm) return 2;
  SC_HANDLE svc = OpenServiceW(scm, kServiceName, SERVICE_ALL_ACCESS);
  if (svc) StopAndWait(svc);

  // close a "run without installing" instance of this user
  if (HANDLE ev = OpenEventW(EVENT_MODIFY_STATE, FALSE, kUserQuitEvent)) {
    SetEvent(ev);
    CloseHandle(ev);
    Sleep(800);
  }

  if (_wcsicmp(ExePath().c_str(), target.c_str()) != 0) {
    bool copied = false;
    for (int i = 0; i < 20 && !copied; i++) {
      copied = CopyFileW(ExePath().c_str(), target.c_str(), FALSE) != FALSE;
      if (!copied) Sleep(250);
    }
    if (!copied) {
      if (!quiet) MessageBoxW(nullptr, L"Could not copy DeskLinkHost.exe to Program Files.", L"DeskLink", MB_ICONERROR);
      return 3;
    }
  }
  MigrateConfig(progData);
  LockDownDir(progData);

  std::wstring bin = L"\"" + target + L"\" --service";
  if (svc) {
    ChangeServiceConfigW(svc, SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL, bin.c_str(), nullptr,
                         nullptr, nullptr, nullptr, nullptr, kDisplay);
  } else {
    svc = CreateServiceW(scm, kServiceName, kDisplay, SERVICE_ALL_ACCESS, SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START,
                         SERVICE_ERROR_NORMAL, bin.c_str(), nullptr, nullptr, nullptr, nullptr, nullptr);
  }
  if (!svc) {
    if (!quiet) MessageBoxW(nullptr, L"Could not register the DeskLink service.", L"DeskLink", MB_ICONERROR);
    CloseServiceHandle(scm);
    return 4;
  }
  SERVICE_DESCRIPTIONW desc{(LPWSTR)L"Lets your linked phone see and control this PC (DeskLink)."};
  ChangeServiceConfig2W(svc, SERVICE_CONFIG_DESCRIPTION, &desc);
  SC_ACTION acts[3] = {{SC_ACTION_RESTART, 2000}, {SC_ACTION_RESTART, 5000}, {SC_ACTION_RESTART, 15000}};
  SERVICE_FAILURE_ACTIONSW fa{};
  fa.dwResetPeriod = 86400;
  fa.cActions = 3;
  fa.lpsaActions = acts;
  ChangeServiceConfig2W(svc, SERVICE_CONFIG_FAILURE_ACTIONS, &fa);

  // firewall: allow inbound for this program only (TCP 47810 session + UDP 47811 discovery)
  RunHidden(L"netsh advfirewall firewall delete rule name=\"DeskLink Host\"");
  RunHidden(L"netsh advfirewall firewall add rule name=\"DeskLink Host\" dir=in action=allow program=\"" + target +
            L"\" enable=yes profile=any");

  // allow services to simulate Ctrl+Alt+Del (SendSAS)
  HKEY k;
  if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System", 0, nullptr, 0,
                      KEY_QUERY_VALUE | KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS) {
    DWORD v = 0, size = sizeof(v);
    if (RegQueryValueExW(k, L"SoftwareSASGeneration", nullptr, nullptr, (BYTE*)&v, &size) != ERROR_SUCCESS) v = 0;
    if ((v & 1) == 0) {
      v |= 1;
      RegSetValueExW(k, L"SoftwareSASGeneration", 0, REG_DWORD, (const BYTE*)&v, sizeof(v));
    }
    RegCloseKey(k);
  }

  // "Apps & features" entry
  if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, kUninstallKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) ==
      ERROR_SUCCESS) {
    auto setStr = [&](const wchar_t* name, const std::wstring& val) {
      RegSetValueExW(k, name, 0, REG_SZ, (const BYTE*)val.c_str(), (DWORD)((val.size() + 1) * sizeof(wchar_t)));
    };
    auto setDw = [&](const wchar_t* name, DWORD val) { RegSetValueExW(k, name, 0, REG_DWORD, (const BYTE*)&val, sizeof(val)); };
    setStr(L"DisplayName", kDisplay);
    setStr(L"DisplayVersion", L"1.0.0");
    setStr(L"Publisher", L"DeskLink");
    setStr(L"DisplayIcon", target);
    setStr(L"InstallLocation", dir);
    setStr(L"UninstallString", L"\"" + target + L"\" --uninstall");
    setDw(L"NoModify", 1);
    setDw(L"NoRepair", 1);
    WIN32_FILE_ATTRIBUTE_DATA fad{};
    if (GetFileAttributesExW(target.c_str(), GetFileExInfoStandard, &fad)) setDw(L"EstimatedSize", fad.nFileSizeLow / 1024);
    RegCloseKey(k);
  }
  // the service replaces the per-user autostart
  if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_SET_VALUE, &k) ==
      ERROR_SUCCESS) {
    RegDeleteValueW(k, L"DeskLink Host");
    RegCloseKey(k);
  }

  BOOL started = StartServiceW(svc, 0, nullptr);
  CloseServiceHandle(svc);
  CloseServiceHandle(scm);
  if (!started && GetLastError() != ERROR_SERVICE_ALREADY_RUNNING) {
    if (!quiet) MessageBoxW(nullptr, L"The DeskLink service was installed but did not start.", L"DeskLink", MB_ICONWARNING);
    return 5;
  }
  return 0;
}

int RunUninstall(bool quiet) {
  if (!IsProcessElevated()) return RelaunchElevated(quiet ? L"--uninstall --quiet" : L"--uninstall");
  bool es = IsSpanishUi();
  if (!quiet && MessageBoxW(nullptr, es ? L"¿Desinstalar DeskLink Host de esta PC?" : L"Uninstall DeskLink Host from this PC?",
                            L"DeskLink", MB_YESNO | MB_ICONQUESTION) != IDYES)
    return 1;
  SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_ALL_ACCESS);
  if (scm) {
    SC_HANDLE svc = OpenServiceW(scm, kServiceName, SERVICE_ALL_ACCESS);
    if (svc) {
      StopAndWait(svc);
      DeleteService(svc);
      CloseServiceHandle(svc);
    }
    CloseServiceHandle(scm);
  }
  RunHidden(L"netsh advfirewall firewall delete rule name=\"DeskLink Host\"");
  RegDeleteKeyW(HKEY_LOCAL_MACHINE, kUninstallKey);
  std::wstring progData = KnownFolder(FOLDERID_ProgramData) + L"\\DeskLink";
  bool purge = quiet || MessageBoxW(nullptr,
                                    es ? L"¿Borrar también los teléfonos vinculados y la configuración?"
                                       : L"Also delete the linked phones and settings?",
                                    L"DeskLink", MB_YESNO | MB_ICONQUESTION) == IDYES;
  if (purge) {
    std::wstring cfg = progData + L"\\config.txt";
    DeleteFileW(cfg.c_str());
    DeleteFileW((progData + L"\\host.log").c_str());
    DeleteFileW((progData + L"\\host.log.old").c_str());
    RemoveDirectoryW(progData.c_str());
  }
  std::wstring dir = KnownFolder(FOLDERID_ProgramFiles) + L"\\DeskLink";
  std::wstring target = dir + L"\\DeskLinkHost.exe";
  // the running exe can't delete itself: let cmd do it a moment later
  std::wstring cmd = L"cmd.exe /c ping -n 3 127.0.0.1 >nul & del /f /q \"" + target + L"\" & rmdir \"" + dir + L"\"";
  STARTUPINFOW si{sizeof(si)};
  PROCESS_INFORMATION pi{};
  if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
  }
  if (!quiet) MessageBoxW(nullptr, es ? L"DeskLink Host se desinstaló." : L"DeskLink Host was removed.", L"DeskLink", MB_ICONINFORMATION);
  return 0;
}

// ---------------------------------------------------------------- service runtime
static SERVICE_STATUS_HANDLE g_ssh = nullptr;
static SERVICE_STATUS g_ss{};
static HANDLE g_stopEv = nullptr, g_wakeEv = nullptr, g_agentQuitEv = nullptr;

static void Report(DWORD state, DWORD wait = 0) {
  g_ss.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
  g_ss.dwCurrentState = state;
  g_ss.dwControlsAccepted =
      state == SERVICE_RUNNING ? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN | SERVICE_ACCEPT_SESSIONCHANGE : 0;
  g_ss.dwWaitHint = wait;
  g_ss.dwCheckPoint = (state == SERVICE_RUNNING || state == SERVICE_STOPPED) ? 0 : g_ss.dwCheckPoint + 1;
  SetServiceStatus(g_ssh, &g_ss);
}

static DWORD WINAPI CtrlHandler(DWORD ctrl, DWORD, LPVOID, LPVOID) {
  switch (ctrl) {
    case SERVICE_CONTROL_STOP:
    case SERVICE_CONTROL_SHUTDOWN:
      Report(SERVICE_STOP_PENDING, 5000);
      SetEvent(g_stopEv);
      return NO_ERROR;
    case SERVICE_CONTROL_SESSIONCHANGE:
      SetEvent(g_wakeEv);
      return NO_ERROR;
    case SERVICE_CONTROL_INTERROGATE:
      return NO_ERROR;
  }
  return ERROR_CALL_NOT_IMPLEMENTED;
}

static HANDLE LaunchAgent(DWORD session) {
  HANDLE tok = nullptr, dup = nullptr;
  if (!OpenProcessToken(GetCurrentProcess(),
                        TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT | TOKEN_ADJUST_SESSIONID | TOKEN_ASSIGN_PRIMARY,
                        &tok))
    return nullptr;
  BOOL ok = DuplicateTokenEx(tok, MAXIMUM_ALLOWED, nullptr, SecurityImpersonation, TokenPrimary, &dup);
  CloseHandle(tok);
  if (!ok) return nullptr;
  SetTokenInformation(dup, TokenSessionId, &session, sizeof(session));
  void* env = nullptr;
  CreateEnvironmentBlock(&env, dup, FALSE);
  STARTUPINFOW si{sizeof(si)};
  wchar_t desktop[] = L"winsta0\\default";
  si.lpDesktop = desktop;
  std::wstring cmd = L"\"" + ExePath() + L"\" --agent --system";
  PROCESS_INFORMATION pi{};
  ok = CreateProcessAsUserW(dup, nullptr, cmd.data(), nullptr, nullptr, FALSE,
                            CREATE_UNICODE_ENVIRONMENT | ABOVE_NORMAL_PRIORITY_CLASS, env, nullptr, &si, &pi);
  if (env) DestroyEnvironmentBlock(env);
  CloseHandle(dup);
  if (!ok) {
    Log("service: CreateProcessAsUser failed %lu", GetLastError());
    return nullptr;
  }
  CloseHandle(pi.hThread);
  Log("service: agent pid %lu started in session %lu", pi.dwProcessId, session);
  return pi.hProcess;
}

static void StopAgent(HANDLE& agent) {
  if (!agent) return;
  SetEvent(g_agentQuitEv);
  if (WaitForSingleObject(agent, 4000) != WAIT_OBJECT_0) TerminateProcess(agent, 1);
  CloseHandle(agent);
  agent = nullptr;
  ResetEvent(g_agentQuitEv);
}

static void WINAPI ServiceMain(DWORD, LPWSTR*) {
  g_ssh = RegisterServiceCtrlHandlerExW(kServiceName, CtrlHandler, nullptr);
  if (!g_ssh) return;
  Report(SERVICE_START_PENDING, 3000);
  g_stopEv = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  g_wakeEv = CreateEventW(nullptr, FALSE, FALSE, nullptr);
  g_agentQuitEv = CreateEventW(nullptr, TRUE, FALSE, kAgentQuitEvent);
  Report(SERVICE_RUNNING);
  Log("service: running");

  HANDLE agent = nullptr;
  DWORD agentSession = 0xFFFFFFFF;
  uint64_t nextStart = 0, backoff = 1000;
  for (;;) {
    DWORD sid = WTSGetActiveConsoleSessionId();
    if (agent && sid != agentSession) {
      Log("service: console session changed %lu -> %lu", agentSession, sid);
      StopAgent(agent);
    }
    if (!agent && sid != 0xFFFFFFFF && NowMs() >= nextStart) {
      agent = LaunchAgent(sid);
      agentSession = sid;
      if (!agent) nextStart = NowMs() + 5000;
    }
    HANDLE waits[3] = {g_stopEv, g_wakeEv, agent};
    DWORD n = agent ? 3 : 2;
    DWORD r = WaitForMultipleObjects(n, waits, FALSE, 2000);
    if (r == WAIT_OBJECT_0) break;
    if (agent && r == WAIT_OBJECT_0 + 2) {
      DWORD code = 0;
      GetExitCodeProcess(agent, &code);
      CloseHandle(agent);
      agent = nullptr;
      Log("service: agent exited with %lu", code);
      nextStart = NowMs() + backoff;
      backoff = std::min<uint64_t>(backoff * 2, 30000);
    } else if (agent) {
      backoff = 1000;
    }
  }
  StopAgent(agent);
  Log("service: stopped");
  Report(SERVICE_STOPPED);
}

int RunService() {
  SERVICE_TABLE_ENTRYW table[] = {{(LPWSTR)kServiceName, ServiceMain}, {nullptr, nullptr}};
  if (!StartServiceCtrlDispatcherW(table)) {
    Log("service: StartServiceCtrlDispatcher failed %lu (run with --install instead)", GetLastError());
    return 1;
  }
  return 0;
}
