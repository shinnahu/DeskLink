#include "files.h"

#include <shlobj.h>
#include <shlwapi.h>
#include <shobjidl.h>
#include <userenv.h>
#include <wtsapi32.h>

#include <algorithm>

#include "protocol.h"
#include "session.h"
#include "ui.h"

#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "userenv.lib")
#pragma comment(lib, "wtsapi32.lib")

// ---------------------------------------------------------------- user context
UserContext::UserContext() {
  if (!g_opt.systemMode) return;
  DWORD sid = WTSGetActiveConsoleSessionId();
  if (sid == 0xFFFFFFFF || !WTSQueryUserToken(sid, &token_)) {
    ok_ = false;
    token_ = nullptr;
    return;
  }
  impersonating_ = ImpersonateLoggedOnUser(token_) != FALSE;
  ok_ = impersonating_;
}

UserContext::~UserContext() {
  if (impersonating_) RevertToSelf();
  if (token_) CloseHandle(token_);
}

// ---------------------------------------------------------------- helpers
// Caller must already be in the right security context (UserContext) — contexts don't nest.
static std::wstring ReceivedFilesDirFor(HANDLE token) {
  if (!g_opt.dataDir.empty()) {  // tests: keep received files out of the user's Downloads
    std::wstring d = g_opt.dataDir + L"\\received";
    SHCreateDirectoryExW(nullptr, d.c_str(), nullptr);
    return d;
  }
  PWSTR p = nullptr;
  std::wstring dir;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Downloads, KF_FLAG_CREATE, token, &p))) dir = p;
  if (p) CoTaskMemFree(p);
  if (dir.empty()) return L"";
  dir += L"\\DeskLink";
  SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
  return dir;
}

std::wstring ReceivedFilesDir() {
  UserContext uc;
  return ReceivedFilesDirFor(uc.token());
}

static std::wstring SanitizeName(const std::string& utf8) {
  std::wstring n = U2W(utf8);
  size_t slash = n.find_last_of(L"/\\");
  if (slash != std::wstring::npos) n = n.substr(slash + 1);
  for (auto& c : n)
    if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) c = L'_';
  while (!n.empty() && (n.back() == L'.' || n.back() == L' ')) n.pop_back();
  while (!n.empty() && n.front() == L' ') n.erase(n.begin());
  if (n.empty()) n = L"file";
  std::wstring base = n.substr(0, n.find(L'.'));
  static const wchar_t* reserved[] = {L"CON", L"PRN", L"AUX", L"NUL", L"COM1", L"COM2", L"COM3", L"COM4", L"COM5",
                                      L"COM6", L"COM7", L"COM8", L"COM9", L"LPT1", L"LPT2", L"LPT3", L"LPT4", L"LPT5",
                                      L"LPT6", L"LPT7", L"LPT8", L"LPT9"};
  for (auto r : reserved)
    if (_wcsicmp(base.c_str(), r) == 0) n = L"_" + n;
  if (n.size() > 150) {
    size_t dot = n.rfind(L'.');
    std::wstring ext = (dot != std::wstring::npos && n.size() - dot <= 12) ? n.substr(dot) : L"";
    n = n.substr(0, 150 - ext.size()) + ext;
  }
  return n;
}

static std::wstring UniquePath(const std::wstring& dir, const std::wstring& name) {
  std::wstring p = dir + L"\\" + name;
  if (GetFileAttributesW(p.c_str()) == INVALID_FILE_ATTRIBUTES) return p;
  size_t dot = name.rfind(L'.');
  std::wstring stem = dot == std::wstring::npos || dot == 0 ? name : name.substr(0, dot);
  std::wstring ext = dot == std::wstring::npos || dot == 0 ? L"" : name.substr(dot);
  for (int i = 1; i < 10000; i++) {
    p = dir + L"\\" + stem + L" (" + std::to_wstring(i) + L")" + ext;
    if (GetFileAttributesW(p.c_str()) == INVALID_FILE_ATTRIBUTES) return p;
  }
  return dir + L"\\" + std::to_wstring(GetTickCount64()) + L"-" + name;
}

static std::string MimeFor(const std::wstring& path) {
  const wchar_t* ext = PathFindExtensionW(path.c_str());
  if (!ext || !*ext) return "application/octet-stream";
  wchar_t buf[128];
  DWORD len = (DWORD)std::size(buf);
  if (SUCCEEDED(AssocQueryStringW(ASSOCF_NONE, ASSOCSTR_CONTENTTYPE, ext, nullptr, buf, &len))) return W2U(buf);
  return "application/octet-stream";
}

// ---------------------------------------------------------------- receiving (phone -> PC)
FileReceiver::~FileReceiver() { CancelAll(); }

void FileReceiver::CancelAll() {
  UserContext uc;
  for (auto& kv : in_) {
    if (kv.second.h != INVALID_HANDLE_VALUE) CloseHandle(kv.second.h);
    if (!kv.second.tmp.empty()) DeleteFileW(kv.second.tmp.c_str());
  }
  in_.clear();
}

void FileReceiver::Fail(uint32_t id, const std::string& why) {
  auto it = in_.find(id);
  if (it != in_.end()) {
    UserContext uc;
    if (it->second.h != INVALID_HANDLE_VALUE) CloseHandle(it->second.h);
    if (!it->second.tmp.empty()) DeleteFileW(it->second.tmp.c_str());
    in_.erase(it);
  }
  Writer c(msg::FILE_CANCEL);
  c.u32(id);
  s_->SendControl(std::move(c.b));
  Writer d(msg::FILE_DONE);
  d.u32(id).u8(1).str(why);
  s_->SendControl(std::move(d.b));
  Log("files: incoming %u failed: %s", id, why.c_str());
}

void FileReceiver::OnBegin(uint32_t id, uint64_t size, const std::string& name, const std::string& mime) {
  (void)mime;
  if (in_.count(id)) {
    Fail(id, "duplicate transfer id");
    return;
  }
  UserContext uc;
  if (!uc.ok()) {
    Fail(id, "Nobody is signed in on the PC.");
    return;
  }
  std::wstring dir = ReceivedFilesDirFor(uc.token());
  if (dir.empty()) {
    Fail(id, "The Downloads folder is not available.");
    return;
  }
  ULARGE_INTEGER freeBytes{};
  if (GetDiskFreeSpaceExW(dir.c_str(), &freeBytes, nullptr, nullptr) && freeBytes.QuadPart < size + 64ull * 1024 * 1024) {
    Fail(id, "Not enough free disk space on the PC.");
    return;
  }
  In f;
  f.dir = dir;
  f.name = SanitizeName(name);
  f.size = size;
  wchar_t tmpName[64];
  swprintf(tmpName, 64, L"\\.desklink-%u-%llu.part", id, (unsigned long long)GetTickCount64());
  f.tmp = dir + tmpName;
  f.h = CreateFileW(f.tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                    FILE_ATTRIBUTE_HIDDEN | FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
  if (f.h == INVALID_HANDLE_VALUE) {
    Fail(id, "Could not create the file on the PC.");
    return;
  }
  in_[id] = f;
  Log("files: receiving '%s' (%llu bytes)", W2U(f.name).c_str(), (unsigned long long)size);
}

void FileReceiver::OnData(uint32_t id, const uint8_t* p, size_t n) {
  auto it = in_.find(id);
  if (it == in_.end()) return;
  In& f = it->second;
  if (f.got + n > f.size) {
    Fail(id, "More data than announced.");
    return;
  }
  DWORD wr = 0;
  {
    UserContext uc;
    if (!WriteFile(f.h, p, (DWORD)n, &wr, nullptr) || wr != n) {
      Fail(id, "Write error on the PC (disk full?).");
      return;
    }
  }
  f.got += n;
  if (f.got - f.lastAck >= 1024 * 1024) {
    f.lastAck = f.got;
    Writer w(msg::FILE_PROGRESS);
    w.u32(id).u64(f.got);
    s_->SendControl(std::move(w.b));
  }
}

void FileReceiver::OnEnd(uint32_t id) {
  auto it = in_.find(id);
  if (it == in_.end()) return;
  In f = it->second;
  in_.erase(it);
  std::wstring finalPath;
  bool ok = false;
  {
    UserContext uc;
    CloseHandle(f.h);
    if (f.got == f.size) {
      finalPath = UniquePath(f.dir, f.name);
      ok = MoveFileExW(f.tmp.c_str(), finalPath.c_str(), MOVEFILE_WRITE_THROUGH) != FALSE;
      if (ok) SetFileAttributesW(finalPath.c_str(), FILE_ATTRIBUTE_NORMAL);
    }
    if (!ok) DeleteFileW(f.tmp.c_str());
  }
  Writer pr(msg::FILE_PROGRESS);
  pr.u32(id).u64(f.got);
  s_->SendControl(std::move(pr.b));
  Writer d(msg::FILE_DONE);
  d.u32(id).u8(ok ? 0 : 1).str(ok ? W2U(finalPath) : (f.got != f.size ? "Transfer incomplete." : "Could not save the file."));
  s_->SendControl(std::move(d.b));
  if (ok) {
    Log("files: saved %s", W2U(finalPath).c_str());
    ui::FileReceived(finalPath);
  }
}

void FileReceiver::OnCancel(uint32_t id) {
  auto it = in_.find(id);
  if (it == in_.end()) return;
  UserContext uc;
  CloseHandle(it->second.h);
  DeleteFileW(it->second.tmp.c_str());
  in_.erase(it);
  Writer d(msg::FILE_DONE);
  d.u32(id).u8(2).str("Cancelled");
  s_->SendControl(std::move(d.b));
}

// ---------------------------------------------------------------- sending (PC -> phone)
// One file to the phone: FILE_BEGIN, its data as fast as the phone takes it (4 MiB un-acknowledged at most), FILE_END,
// then the phone's FILE_DONE. read fills buf with up to `want` bytes (got: how many). The phone's status (0 = saved),
// or -1 when it didn't get there.
static int SendOne(Session& s, const std::string& name, const std::string& mime, uint8_t kind, uint64_t size,
                   const std::function<bool(uint8_t* buf, DWORD want, DWORD& got)>& read) {
  const uint64_t kChunk = 256 * 1024, kWindow = 4ull * 1024 * 1024;
  Bytes buf((size_t)std::min(kChunk, std::max<uint64_t>(size, 1)));
  uint32_t id = s.NewOutgoingId();
  s.RegisterOutgoing(id);
  Writer b(msg::FILE_START);
  b.u32(id).u64(size).str(name).str(mime);
  if (kind != fkind::FILE) b.u8(kind);  // (older phones stop reading after the type: they take it as a file)
  bool ok = s.SendBulk(std::move(b.b));
  uint64_t sent = 0;
  while (ok && sent < size) {
    if (s.OutgoingCancelled(id) || !s.WaitForWindow(id, sent, kWindow)) {
      ok = false;
      break;
    }
    DWORD want = (DWORD)std::min<uint64_t>(buf.size(), size - sent), rd = 0;
    if (!read(buf.data(), want, rd) || rd == 0) {
      ok = false;
      break;
    }
    Writer w(msg::FILE_DATA);
    w.b.reserve(rd + 8);
    w.u32(id).raw(buf.data(), rd);
    if (!s.SendBulk(std::move(w.b))) ok = false;
    sent += rd;
  }
  if (!ok) {
    Writer c(msg::FILE_CANCEL);
    c.u32(id);
    s.SendBulk(std::move(c.b));
    s.ForgetOutgoing(id);
    Log("files: sending '%s' aborted", name.c_str());
    return -1;
  }
  Writer e(msg::FILE_FINISH);
  e.u32(id);
  s.SendBulk(std::move(e.b));
  int st = s.WaitForDone(id, 120000);
  s.ForgetOutgoing(id);
  Log("files: sent '%s' (%llu bytes) status %d", name.c_str(), (unsigned long long)size, st);
  return st;
}

void SendFilesToPhone(std::shared_ptr<Session> s, std::vector<std::wstring> paths) {
  std::thread([s, paths] {
    for (auto& path : paths) {
      if (!s->alive()) break;
      HANDLE h;
      {
        UserContext uc;
        h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                        OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
      }
      std::wstring name = PathFindFileNameW(path.c_str());
      if (h == INVALID_HANDLE_VALUE) {
        s->SendNotice(1, "Could not open " + W2U(name));
        continue;
      }
      LARGE_INTEGER sz{};
      GetFileSizeEx(h, &sz);
      SendOne(*s, W2U(name), MimeFor(path), fkind::FILE, (uint64_t)sz.QuadPart, [h](uint8_t* buf, DWORD want, DWORD& got) {
        UserContext uc;
        return ReadFile(h, buf, want, &got, nullptr) != FALSE;
      });
      CloseHandle(h);
    }
  }).detach();
}

int SendBytesToPhone(Session& s, const std::string& name, const std::string& mime, uint8_t kind, const Bytes& data) {
  size_t at = 0;
  return SendOne(s, name, mime, kind, data.size(), [&](uint8_t* buf, DWORD want, DWORD& got) {
    got = (DWORD)std::min<size_t>(want, data.size() - at);
    memcpy(buf, data.data() + at, got);
    at += got;
    return true;
  });
}

// Shows an "open files" dialog in the current (user) context.
static std::vector<std::wstring> ShowPickerDialog() {
  std::vector<std::wstring> out;
  HRESULT hrInit = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
  {
    // Topmost owner in the middle of the primary screen so the dialog appears in front.
    int sw = GetSystemMetrics(SM_CXSCREEN), sh = GetSystemMetrics(SM_CYSCREEN);
    HWND owner = CreateWindowExW(WS_EX_TOPMOST | WS_EX_TOOLWINDOW, L"STATIC", L"DeskLink", WS_POPUP, sw / 2, sh / 2, 1, 1,
                                 nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (owner) {
      ShowWindow(owner, SW_SHOWNA);
      HWND fg = GetForegroundWindow();
      DWORD fgThread = fg ? GetWindowThreadProcessId(fg, nullptr) : 0, me = GetCurrentThreadId();
      if (fgThread && fgThread != me) AttachThreadInput(me, fgThread, TRUE);
      SetForegroundWindow(owner);
      if (fgThread && fgThread != me) AttachThreadInput(me, fgThread, FALSE);
    }
    IFileOpenDialog* dlg = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dlg)))) {
      DWORD opts = 0;
      dlg->GetOptions(&opts);
      dlg->SetOptions(opts | FOS_ALLOWMULTISELECT | FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST | FOS_PATHMUSTEXIST);
      dlg->SetTitle(IsSpanishUi() ? L"DeskLink: elegí archivos para enviar al teléfono"
                                  : L"DeskLink: choose files to send to your phone");
      dlg->SetOkButtonLabel(IsSpanishUi() ? L"Enviar" : L"Send");
      if (SUCCEEDED(dlg->Show(owner))) {
        IShellItemArray* items = nullptr;
        if (SUCCEEDED(dlg->GetResults(&items))) {
          DWORD n = 0;
          items->GetCount(&n);
          for (DWORD i = 0; i < n; i++) {
            IShellItem* it = nullptr;
            if (SUCCEEDED(items->GetItemAt(i, &it))) {
              PWSTR p = nullptr;
              if (SUCCEEDED(it->GetDisplayName(SIGDN_FILESYSPATH, &p))) {
                out.push_back(p);
                CoTaskMemFree(p);
              }
              it->Release();
            }
          }
          items->Release();
        }
      }
      dlg->Release();
    }
    if (owner) DestroyWindow(owner);
  }
  if (SUCCEEDED(hrInit)) CoUninitialize();
  return out;
}

int RunPickFilesHelper() {
  auto paths = ShowPickerDialog();
  HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
  for (auto& p : paths) {
    std::string line = W2U(p) + "\n";
    DWORD wr = 0;
    if (out && out != INVALID_HANDLE_VALUE) WriteFile(out, line.data(), (DWORD)line.size(), &wr, nullptr);
  }
  return 0;
}

// Service mode: the agent runs as SYSTEM, so the dialog is shown by a helper process
// started in the signed-in user's context (never browse the disk as SYSTEM).
static std::vector<std::wstring> PickAsUser() {
  std::vector<std::wstring> out;
  HANDLE token = nullptr;
  DWORD sid = WTSGetActiveConsoleSessionId();
  if (sid == 0xFFFFFFFF || !WTSQueryUserToken(sid, &token)) return out;
  SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
  HANDLE rd = nullptr, wr = nullptr;
  if (!CreatePipe(&rd, &wr, &sa, 0)) {
    CloseHandle(token);
    return out;
  }
  SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
  STARTUPINFOW si{sizeof(si)};
  si.dwFlags = STARTF_USESTDHANDLES;
  si.hStdOutput = wr;
  si.hStdError = wr;
  si.hStdInput = nullptr;
  wchar_t desktop[] = L"winsta0\\default";
  si.lpDesktop = desktop;
  std::wstring cmd = L"\"" + ExePath() + L"\" --pick-files";
  // The user's own environment and folder. Without them the helper inherits SYSTEM's (USERPROFILE =
  // ...\config\systemprofile), and the dialog stops on "...\systemprofile\Desktop is unavailable".
  void* env = nullptr;
  if (!CreateEnvironmentBlock(&env, token, FALSE)) env = nullptr;
  wchar_t home[MAX_PATH] = {};
  DWORD homeLen = MAX_PATH;
  bool haveHome = GetUserProfileDirectoryW(token, home, &homeLen) != FALSE;
  PROCESS_INFORMATION pi{};
  BOOL started = CreateProcessAsUserW(token, nullptr, cmd.data(), nullptr, nullptr, TRUE,
                                      CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT, env, haveHome ? home : nullptr, &si,
                                      &pi);
  if (env) DestroyEnvironmentBlock(env);
  if (started) {
    CloseHandle(wr);
    wr = nullptr;
    std::string data;
    char b[4096];
    DWORD n = 0;
    while (ReadFile(rd, b, sizeof(b), &n, nullptr) && n > 0) data.append(b, n);
    WaitForSingleObject(pi.hProcess, 5000);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    size_t start = 0;
    while (start < data.size()) {
      size_t e = data.find('\n', start);
      if (e == std::string::npos) e = data.size();
      std::string line = data.substr(start, e - start);
      while (!line.empty() && line.back() == '\r') line.pop_back();
      if (!line.empty()) out.push_back(U2W(line));
      start = e + 1;
    }
  } else {
    Log("files: could not start picker helper (%lu)", GetLastError());
  }
  if (wr) CloseHandle(wr);
  CloseHandle(rd);
  CloseHandle(token);
  return out;
}

void PickAndSendFiles(std::shared_ptr<Session> s, bool replyNoneIfCancelled) {
  std::thread([s, replyNoneIfCancelled] {
#ifdef DESKLINK_TESTING
    if (!g_opt.testSendFile.empty()) {  // integration tests: no dialog on the real desktop
      SendFilesToPhone(s, {g_opt.testSendFile});
      return;
    }
#endif
    auto paths = g_opt.systemMode ? PickAsUser() : ShowPickerDialog();
    if (paths.empty()) {
      if (replyNoneIfCancelled && s->alive()) s->SendControl(Writer(msg::FILE_REQUEST_NONE).b);
      return;
    }
    SendFilesToPhone(s, paths);
  }).detach();
}
