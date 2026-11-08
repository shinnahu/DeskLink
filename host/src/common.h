#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using Bytes = std::vector<uint8_t>;

// ---------------------------------------------------------------- strings
std::string W2U(const std::wstring& w);
std::wstring U2W(const std::string& s);
std::string Hex(const uint8_t* p, size_t n);
inline std::string Hex(const Bytes& b) { return Hex(b.data(), b.size()); }
bool Unhex(const std::string& s, Bytes& out);
std::string B64Encode(const Bytes& b);
bool B64Decode(const std::string& s, Bytes& out);
std::string UrlEscape(const std::string& s);
std::string UrlUnescape(const std::string& s);
std::string JsonEscape(const std::string& s);

// ---------------------------------------------------------------- time
uint64_t NowMs();   // monotonic milliseconds
uint64_t NowUs();   // monotonic microseconds
int64_t UnixNow();  // wall clock seconds

// ---------------------------------------------------------------- logging
void LogOpen(const std::wstring& path, bool toConsole);
void Log(const char* fmt, ...);

// ---------------------------------------------------------------- environment
struct RuntimeOptions {
  bool systemMode = false;      // agent launched by the service as SYSTEM
  bool console = false;         // mirror log to a console window
  std::string bindAddr;         // empty = all interfaces (dual stack)
  int port = 0;                 // 0 = config value
  std::wstring dataDir;         // override for tests
  bool testAutoApprove = false; // only honoured in DESKLINK_TESTING builds
  std::wstring testSendFile;    // DESKLINK_TESTING: answer FILE_REQUEST with this file
  bool testPeersAreInternet = false;  // DESKLINK_TESTING: treat every connection as coming from the internet
  bool testNoInject = false;          // DESKLINK_TESTING: log input instead of injecting it
  int testFakeScreens = 0;            // DESKLINK_TESTING: the main screen also shown as this many more screens
  bool testRouterIsLoopback = false;  // DESKLINK_TESTING: 127.0.0.1 counts as this PC's router
};
extern RuntimeOptions g_opt;

bool IsSystemAccount();
std::wstring ExePath();
std::wstring ExeDir();
std::wstring DataDir();
std::string HostDisplayName();
bool IsSpanishUi();

// ---------------------------------------------------------------- big-endian serialization
struct Writer {
  Bytes b;
  Writer() = default;
  explicit Writer(uint8_t type) { b.push_back(type); }
  Writer& u8(uint8_t v) { b.push_back(v); return *this; }
  Writer& u16(uint16_t v) { b.push_back((uint8_t)(v >> 8)); b.push_back((uint8_t)v); return *this; }
  Writer& u32(uint32_t v) { for (int i = 3; i >= 0; --i) b.push_back((uint8_t)(v >> (i * 8))); return *this; }
  Writer& u64(uint64_t v) { for (int i = 7; i >= 0; --i) b.push_back((uint8_t)(v >> (i * 8))); return *this; }
  Writer& i16(int16_t v) { return u16((uint16_t)v); }
  Writer& i32(int32_t v) { return u32((uint32_t)v); }
  Writer& raw(const void* p, size_t n) { auto c = (const uint8_t*)p; b.insert(b.end(), c, c + n); return *this; }
  Writer& raw(const Bytes& v) { return raw(v.data(), v.size()); }
  Writer& str(const std::string& s) {
    size_t n = s.size() > 65535 ? 65535 : s.size();
    u16((uint16_t)n);
    return raw(s.data(), n);
  }
};

struct Reader {
  const uint8_t* p;
  size_t n;
  size_t pos = 0;
  bool ok = true;
  Reader(const uint8_t* d, size_t len) : p(d), n(len) {}
  explicit Reader(const Bytes& v) : p(v.data()), n(v.size()) {}
  bool need(size_t k) { if (!ok || pos + k > n) { ok = false; return false; } return true; }
  uint8_t u8() { return need(1) ? p[pos++] : 0; }
  uint16_t u16() { if (!need(2)) return 0; uint16_t v = (uint16_t)(p[pos] << 8 | p[pos + 1]); pos += 2; return v; }
  uint32_t u32() {
    if (!need(4)) return 0;
    uint32_t v = (uint32_t)p[pos] << 24 | (uint32_t)p[pos + 1] << 16 | (uint32_t)p[pos + 2] << 8 | p[pos + 3];
    pos += 4;
    return v;
  }
  uint64_t u64() { uint64_t hi = u32(); uint64_t lo = u32(); return hi << 32 | lo; }
  int16_t i16() { return (int16_t)u16(); }
  int32_t i32() { return (int32_t)u32(); }
  bool raw(void* out, size_t k) { if (!need(k)) return false; memcpy(out, p + pos, k); pos += k; return true; }
  std::string str() {
    uint16_t k = u16();
    if (!need(k)) return {};
    std::string s((const char*)p + pos, k);
    pos += k;
    return s;
  }
  const uint8_t* rest(size_t& len) { len = ok ? n - pos : 0; const uint8_t* r = p + pos; pos = n; return r; }
};

// ---------------------------------------------------------------- misc helpers
struct WinHandle {
  HANDLE h = nullptr;
  WinHandle() = default;
  explicit WinHandle(HANDLE x) : h(x) {}
  ~WinHandle() { reset(); }
  WinHandle(const WinHandle&) = delete;
  WinHandle& operator=(const WinHandle&) = delete;
  void reset(HANDLE x = nullptr) { if (h && h != INVALID_HANDLE_VALUE) CloseHandle(h); h = x; }
  bool valid() const { return h && h != INVALID_HANDLE_VALUE; }
  operator HANDLE() const { return h; }
};

template <typename F>
struct ScopeExit {
  F f;
  ~ScopeExit() { f(); }
};
template <typename F>
ScopeExit<F> MakeScopeExit(F f) { return ScopeExit<F>{f}; }
