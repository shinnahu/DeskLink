#include "app.h"

#include <iphlpapi.h>

#include "protocol.h"
#include "session.h"
#include "ui.h"

#pragma comment(lib, "iphlpapi.lib")

App* g_app = nullptr;

bool App::Init() {
  if (!cfg.LoadOrCreate()) {
    Log("app: failed to load/create configuration in %s", W2U(DataDir()).c_str());
    return false;
  }
  if (g_opt.port > 0) cfg.port = g_opt.port;
  hostName = HostDisplayName();
  input.notice = [](const std::string& text) {
    if (auto s = g_app->Active()) s->SendNotice(1, text);
  };
  input.Start();
  if (cfg.DeviceCount() == 0) OpenPairing(15 * 60);
  Log("app: host '%s' id %s fingerprint %s, %zu paired device(s)", hostName.c_str(), Hex(cfg.hostId).c_str(),
      cfg.fingerprint.c_str(), cfg.DeviceCount());
  return true;
}

std::shared_ptr<Session> App::Active() {
  std::lock_guard<std::mutex> lk(mu_);
  return active_;
}

void App::ActivateSession(const std::shared_ptr<Session>& s) {
  std::shared_ptr<Session> old;
  {
    std::lock_guard<std::mutex> lk(mu_);
    old = active_;
    active_ = s;
  }
  if (old && old.get() != s.get()) old->Stop(bye::REPLACED, "Replaced by a newer connection");
  ui::SessionStarted(s->device().name);
}

void App::OnSessionEnded(Session* s) {
  bool wasActive = false;
  {
    std::lock_guard<std::mutex> lk(mu_);
    if (active_.get() == s) {
      active_.reset();
      wasActive = true;
    }
  }
  if (wasActive) ui::SessionEnded();
  // give the memory used by capture/encoding back right away (Task Manager shows the trimmed size)
  SetProcessWorkingSetSize(GetCurrentProcess(), (SIZE_T)-1, (SIZE_T)-1);
}

void App::StopActive(uint8_t reason, const std::string& msg) {
  if (auto s = Active()) s->Stop(reason, msg);
}

void App::OpenPairing(int seconds) {
  {
    std::lock_guard<std::mutex> lk(mu_);
    pairingUntil_ = NowMs() + (uint64_t)seconds * 1000;
  }
  ui::RefreshTray();
}

void App::ClosePairing() {
  {
    std::lock_guard<std::mutex> lk(mu_);
    pairingUntil_ = 0;
  }
  ui::RefreshTray();
}

bool App::PairingOpen() {
  std::lock_guard<std::mutex> lk(mu_);
  return pairingUntil_ > NowMs();
}

int App::PairingSecondsLeft() {
  std::lock_guard<std::mutex> lk(mu_);
  uint64_t now = NowMs();
  return pairingUntil_ > now ? (int)((pairingUntil_ - now + 999) / 1000) : 0;
}

uint32_t App::Capabilities() const {
  uint32_t c = caps::FILES | caps::CLIPBOARD | caps::KNOCK | caps::REL_MOUSE | caps::ALL_SCREENS | caps::AUDIO;
  if (g_opt.systemMode) c |= caps::CTRL_ALT_DEL | caps::SERVICE_MODE;
  return c;
}

std::vector<std::string> App::LocalAddresses() {
  std::vector<std::string> lan, tail, v6;
  ULONG size = 32 * 1024;
  std::vector<uint8_t> buf(size);
  auto* aa = (IP_ADAPTER_ADDRESSES*)buf.data();
  ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
  ULONG r = GetAdaptersAddresses(AF_UNSPEC, flags, nullptr, aa, &size);
  if (r == ERROR_BUFFER_OVERFLOW) {
    buf.resize(size);
    aa = (IP_ADAPTER_ADDRESSES*)buf.data();
    r = GetAdaptersAddresses(AF_UNSPEC, flags, nullptr, aa, &size);
  }
  if (r == NO_ERROR) {
    for (auto* a = aa; a; a = a->Next) {
      if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
      for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
        char host[INET6_ADDRSTRLEN] = {};
        if (u->Address.lpSockaddr->sa_family == AF_INET) {
          auto* sin = (sockaddr_in*)u->Address.lpSockaddr;
          inet_ntop(AF_INET, &sin->sin_addr, host, sizeof(host));
          uint8_t b0 = sin->sin_addr.S_un.S_un_b.s_b1, b1 = sin->sin_addr.S_un.S_un_b.s_b2;
          if (b0 == 169 && b1 == 254) continue;
          if (b0 == 100 && (b1 & 0xC0) == 64) tail.push_back(host);  // 100.64.0.0/10 (Tailscale / CGNAT)
          else lan.push_back(host);
        } else if (u->Address.lpSockaddr->sa_family == AF_INET6) {
          auto* sin6 = (sockaddr_in6*)u->Address.lpSockaddr;
          const uint8_t* b = sin6->sin6_addr.u.Byte;
          if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) continue;  // link-local
          if (u->SuffixOrigin == IpSuffixOriginRandom) continue;  // temporary privacy address
          inet_ntop(AF_INET6, &sin6->sin6_addr, host, sizeof(host));
          v6.push_back(host);
        }
      }
    }
  }
  std::vector<std::string> out;
  for (auto& s : lan) out.push_back(s);
  for (auto& s : tail) out.push_back(s);
  for (size_t i = 0; i < v6.size() && i < 3; i++) out.push_back(v6[i]);
  out.push_back(hostName);
  if (out.size() > 12) out.resize(12);
  return out;
}

void App::OnLocalClipboard(const std::string& utf8) {
  if (auto s = Active()) s->SendClipboard(utf8);
}
