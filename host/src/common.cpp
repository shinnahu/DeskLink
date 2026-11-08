#include "common.h"

#include <shlobj.h>
#include <wincrypt.h>

#include <cstdarg>
#include <cstdio>

#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "advapi32.lib")

RuntimeOptions g_opt;

std::string W2U(const std::wstring& w) {
  if (w.empty()) return {};
  int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
  std::string s((size_t)n, '\0');
  WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), s.data(), n, nullptr, nullptr);
  return s;
}

std::wstring U2W(const std::string& s) {
  if (s.empty()) return {};
  int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
  std::wstring w((size_t)n, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
  return w;
}

std::string Hex(const uint8_t* p, size_t n) {
  static const char* d = "0123456789abcdef";
  std::string s;
  s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) {
    s.push_back(d[p[i] >> 4]);
    s.push_back(d[p[i] & 15]);
  }
  return s;
}

bool Unhex(const std::string& s, Bytes& out) {
  if (s.size() % 2) return false;
  auto v = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  };
  out.clear();
  for (size_t i = 0; i < s.size(); i += 2) {
    int a = v(s[i]), b = v(s[i + 1]);
    if (a < 0 || b < 0) return false;
    out.push_back((uint8_t)(a << 4 | b));
  }
  return true;
}

std::string B64Encode(const Bytes& b) {
  if (b.empty()) return {};
  DWORD n = 0;
  CryptBinaryToStringA(b.data(), (DWORD)b.size(), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, nullptr, &n);
  std::string s(n, '\0');
  CryptBinaryToStringA(b.data(), (DWORD)b.size(), CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, s.data(), &n);
  s.resize(n);
  while (!s.empty() && (s.back() == '\0' || s.back() == '\r' || s.back() == '\n')) s.pop_back();
  return s;
}

bool B64Decode(const std::string& s, Bytes& out) {
  DWORD n = 0;
  if (!CryptStringToBinaryA(s.c_str(), (DWORD)s.size(), CRYPT_STRING_BASE64, nullptr, &n, nullptr, nullptr)) return false;
  out.resize(n);
  if (!CryptStringToBinaryA(s.c_str(), (DWORD)s.size(), CRYPT_STRING_BASE64, out.data(), &n, nullptr, nullptr)) return false;
  out.resize(n);
  return true;
}

std::string UrlEscape(const std::string& s) {
  static const char* d = "0123456789ABCDEF";
  std::string o;
  for (unsigned char c : s) {
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~' || c == ' ') {
      o.push_back((char)c);
    } else {
      o.push_back('%');
      o.push_back(d[c >> 4]);
      o.push_back(d[c & 15]);
    }
  }
  return o;
}

std::string UrlUnescape(const std::string& s) {
  std::string o;
  for (size_t i = 0; i < s.size(); i++) {
    if (s[i] == '%' && i + 2 < s.size()) {
      Bytes b;
      if (Unhex(s.substr(i + 1, 2), b)) {
        o.push_back((char)b[0]);
        i += 2;
        continue;
      }
    }
    o.push_back(s[i]);
  }
  return o;
}

std::string JsonEscape(const std::string& s) {
  std::string o;
  for (unsigned char c : s) {
    switch (c) {
      case '"': o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break;
      case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", c);
          o += buf;
        } else {
          o.push_back((char)c);
        }
    }
  }
  return o;
}

uint64_t NowMs() { return GetTickCount64(); }

uint64_t NowUs() {
  static LARGE_INTEGER freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
  LARGE_INTEGER c;
  QueryPerformanceCounter(&c);
  return (uint64_t)(c.QuadPart / freq.QuadPart * 1000000 + (c.QuadPart % freq.QuadPart) * 1000000 / freq.QuadPart);
}

int64_t UnixNow() {
  FILETIME ft;
  GetSystemTimeAsFileTime(&ft);
  ULARGE_INTEGER u;
  u.LowPart = ft.dwLowDateTime;
  u.HighPart = ft.dwHighDateTime;
  return (int64_t)((u.QuadPart - 116444736000000000ULL) / 10000000ULL);
}

// ---------------------------------------------------------------- logging
static std::mutex g_logMu;
static FILE* g_logFile = nullptr;
static bool g_logConsole = false;

void LogOpen(const std::wstring& path, bool toConsole) {
  std::lock_guard<std::mutex> lk(g_logMu);
  g_logConsole = toConsole;
  WIN32_FILE_ATTRIBUTE_DATA fa{};
  if (GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &fa) && fa.nFileSizeLow > 2 * 1024 * 1024) {
    std::wstring old = path + L".old";
    MoveFileExW(path.c_str(), old.c_str(), MOVEFILE_REPLACE_EXISTING);
  }
  g_logFile = _wfsopen(path.c_str(), L"a", _SH_DENYNO);
}

void Log(const char* fmt, ...) {
  char msg[2048];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);
  SYSTEMTIME t;
  GetLocalTime(&t);
  char line[2200];
  snprintf(line, sizeof(line), "%02d:%02d:%02d.%03d [%5lu] %s\n", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds,
           GetCurrentThreadId(), msg);
  std::lock_guard<std::mutex> lk(g_logMu);
  if (g_logFile) {
    fputs(line, g_logFile);
    fflush(g_logFile);
  }
  if (g_logConsole) {
    fputs(line, stdout);
    fflush(stdout);
  }
  OutputDebugStringA(line);
}

// ---------------------------------------------------------------- environment
bool IsSystemAccount() {
  HANDLE tok = nullptr;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &tok)) return false;
  BYTE buf[256];
  DWORD len = 0;
  bool sys = false;
  if (GetTokenInformation(tok, TokenUser, buf, sizeof(buf), &len)) {
    sys = IsWellKnownSid(((TOKEN_USER*)buf)->User.Sid, WinLocalSystemSid) != FALSE;
  }
  CloseHandle(tok);
  return sys;
}

std::wstring ExePath() {
  wchar_t buf[MAX_PATH * 2];
  DWORD n = GetModuleFileNameW(nullptr, buf, (DWORD)std::size(buf));
  return std::wstring(buf, n);
}

std::wstring ExeDir() {
  std::wstring p = ExePath();
  size_t k = p.find_last_of(L"\\/");
  return k == std::wstring::npos ? p : p.substr(0, k);
}

static std::wstring KnownFolder(REFKNOWNFOLDERID id) {
  PWSTR p = nullptr;
  std::wstring r;
  if (SUCCEEDED(SHGetKnownFolderPath(id, KF_FLAG_CREATE, nullptr, &p))) r = p;
  if (p) CoTaskMemFree(p);
  return r;
}

std::wstring DataDir() {
  static std::wstring dir;
  static std::once_flag once;
  std::call_once(once, [] {
    if (!g_opt.dataDir.empty()) {
      dir = g_opt.dataDir;
    } else if (g_opt.systemMode) {
      dir = KnownFolder(FOLDERID_ProgramData) + L"\\DeskLink";
    } else {
      dir = KnownFolder(FOLDERID_LocalAppData) + L"\\DeskLink";
    }
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
  });
  return dir;
}

std::string HostDisplayName() {
  wchar_t buf[256];
  DWORD n = (DWORD)std::size(buf);
  if (GetComputerNameExW(ComputerNamePhysicalDnsHostname, buf, &n)) return W2U(std::wstring(buf, n));
  n = (DWORD)std::size(buf);
  if (GetComputerNameW(buf, &n)) return W2U(std::wstring(buf, n));
  return "PC";
}

bool IsSpanishUi() { return PRIMARYLANGID(GetUserDefaultUILanguage()) == LANG_SPANISH; }
