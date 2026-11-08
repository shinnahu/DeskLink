#include "server.h"

#include <mstcpip.h>

#include "app.h"
#include "channel.h"
#include "desktop.h"
#include "protocol.h"
#include "remote.h"
#include "session.h"
#include "ui.h"

#pragma comment(lib, "ws2_32.lib")

static bool ConstantTimeEq(const uint8_t* a, const uint8_t* b, size_t n) {
  uint8_t d = 0;
  for (size_t i = 0; i < n; i++) d |= (uint8_t)(a[i] ^ b[i]);
  return d == 0;
}

static std::string AddrToString(const sockaddr* sa) {
  char host[INET6_ADDRSTRLEN] = {};
  int port = 0;
  if (sa->sa_family == AF_INET) {
    auto* s = (const sockaddr_in*)sa;
    inet_ntop(AF_INET, &s->sin_addr, host, sizeof(host));
    port = ntohs(s->sin_port);
    return std::string(host) + ":" + std::to_string(port);
  }
  auto* s6 = (const sockaddr_in6*)sa;
  if (IN6_IS_ADDR_V4MAPPED(&s6->sin6_addr)) {
    in_addr a4;
    memcpy(&a4, &s6->sin6_addr.u.Byte[12], 4);
    inet_ntop(AF_INET, &a4, host, sizeof(host));
    return std::string(host) + ":" + std::to_string(ntohs(s6->sin6_port));
  }
  inet_ntop(AF_INET6, &s6->sin6_addr, host, sizeof(host));
  return "[" + std::string(host) + "]:" + std::to_string(ntohs(s6->sin6_port));
}

Server::~Server() { Stop(); }

bool Server::Start(const std::string& bindAddr, int port) {
  port_ = port;
  stop_ = false;
  if (bindAddr.empty()) {
    listen_ = socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP);
    if (listen_ != INVALID_SOCKET) {
      DWORD off = 0;
      setsockopt(listen_, IPPROTO_IPV6, IPV6_V6ONLY, (const char*)&off, sizeof(off));
      sockaddr_in6 a{};
      a.sin6_family = AF_INET6;
      a.sin6_port = htons((u_short)port);
      a.sin6_addr = in6addr_any;
      if (bind(listen_, (sockaddr*)&a, sizeof(a)) != 0) {
        closesocket(listen_);
        listen_ = INVALID_SOCKET;
      }
    }
  }
  if (listen_ == INVALID_SOCKET) {
    listen_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons((u_short)port);
    if (bindAddr.empty()) a.sin_addr.s_addr = INADDR_ANY;
    else inet_pton(AF_INET, bindAddr.c_str(), &a.sin_addr);
    BOOL excl = TRUE;
    setsockopt(listen_, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (const char*)&excl, sizeof(excl));
    if (bind(listen_, (sockaddr*)&a, sizeof(a)) != 0) {
      Log("server: cannot bind TCP port %d (error %d) - is another DeskLink host running?", port, WSAGetLastError());
      closesocket(listen_);
      listen_ = INVALID_SOCKET;
      return false;
    }
  }
  if (listen(listen_, 16) != 0) {
    Log("server: listen failed %d", WSAGetLastError());
    return false;
  }
  acceptThread_ = std::thread([this] { AcceptLoop(); });

  udp_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  sockaddr_in u{};
  u.sin_family = AF_INET;
  u.sin_port = htons(kDiscoveryPort);
  if (bindAddr.empty()) u.sin_addr.s_addr = INADDR_ANY;
  else inet_pton(AF_INET, bindAddr.c_str(), &u.sin_addr);
  if (udp_ != INVALID_SOCKET && bind(udp_, (sockaddr*)&u, sizeof(u)) == 0) {
    discThread_ = std::thread([this] { DiscoveryLoop(); });
  } else {
    Log("server: discovery port %d unavailable (error %d); LAN discovery disabled", kDiscoveryPort, WSAGetLastError());
    if (udp_ != INVALID_SOCKET) closesocket(udp_);
    udp_ = INVALID_SOCKET;
  }
  Log("server: listening on %s:%d", bindAddr.empty() ? "*" : bindAddr.c_str(), port);
  return true;
}

void Server::Stop() {
  stop_ = true;
  if (listen_ != INVALID_SOCKET) {
    closesocket(listen_);
    listen_ = INVALID_SOCKET;
  }
  if (udp_ != INVALID_SOCKET) {
    closesocket(udp_);
    udp_ = INVALID_SOCKET;
  }
  if (acceptThread_.joinable()) acceptThread_.join();
  if (discThread_.joinable()) discThread_.join();
}

void Server::AcceptLoop() {
  while (!stop_) {
    sockaddr_storage ss{};
    int len = sizeof(ss);
    SOCKET s = accept(listen_, (sockaddr*)&ss, &len);
    if (s == INVALID_SOCKET) {
      if (stop_) break;
      Sleep(50);
      continue;
    }
    std::string peer = AddrToString((sockaddr*)&ss);
    if (pending_ >= 16) {
      closesocket(s);
      continue;
    }
    pending_++;
    std::thread([this, s, peer] {
      HandleConnection(s, peer);
      pending_--;
    }).detach();
  }
}

bool Server::RateLimited(const std::string& ip) {
  std::lock_guard<std::mutex> lk(failMu_);
  auto& q = failures_[ip];
  uint64_t now = NowMs();
  while (!q.empty() && now - q.front() > 10 * 60 * 1000) q.pop_front();
  return q.size() >= 10;
}

void Server::RecordFailure(const std::string& ip) {
  std::lock_guard<std::mutex> lk(failMu_);
  failures_[ip].push_back(NowMs());
}

// A knock (PROTOCOL.md §2): "DLKN" u64 unixTime nonce[16] HMAC-SHA256(key = SHA-256(token), the first 28 bytes).
// Only a paired phone can make one; it must be within 10 minutes of this PC's clock, and each opens one connection.
bool Server::CheckKnock(const uint8_t* k) {
  if (memcmp(k, "DLKN", 4) != 0) return false;
  uint64_t t = 0;
  for (int i = 0; i < 8; i++) t = t << 8 | k[4 + i];
  int64_t now = UnixNow();
  if ((int64_t)t < now - 600 || (int64_t)t > now + 600) return false;
  bool ok = false;
  for (auto& d : g_app->cfg.Devices()) {  // every device is checked: the time taken doesn't tell which matched
    uint8_t mac[32];
    crypto::HmacSha256(d.tokenHash.data(), d.tokenHash.size(), k, 28, mac);
    if (ConstantTimeEq(mac, k + 28, 32)) ok = true;
  }
  if (!ok) return false;
  std::string nonce((const char*)k + 12, 16);
  std::lock_guard<std::mutex> lk(knockMu_);
  uint64_t nowMs = NowMs();
  for (auto it = knocks_.begin(); it != knocks_.end();) {
    if (nowMs - it->second > 25 * 60 * 1000) it = knocks_.erase(it);
    else ++it;
  }
  if (knocks_.count(nonce)) return false;  // the same knock again: someone replaying it
  knocks_[nonce] = nowMs;
  return true;
}

static void SendResult(SecureChannel& ch, uint8_t type, uint8_t status, const std::string& message) {
  Writer w(type);
  w.u8(status).str(message);
  ch.Send(w.b);
}

void Server::HandleConnection(SOCKET s, std::string peer) {
  BOOL nd = TRUE;
  setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char*)&nd, sizeof(nd));
  tcp_keepalive ka{1, 10000, 2000};
  DWORD ret = 0;
  WSAIoctl(s, SIO_KEEPALIVE_VALS, &ka, sizeof(ka), nullptr, 0, &ret, nullptr, nullptr);
  auto ch = std::make_unique<SecureChannel>(s, peer);
  std::string ip = ch->peerIp();
  if (RateLimited(ip)) {
    Log("server: %s rate limited", peer.c_str());
    return;
  }
  // From the internet (mobile data): only while that's allowed on the PC, and only after a valid knock, which
  // only a paired phone can make. Anything else is dropped before the handshake.
  bool local = remote::IsLocalAddress(ip);
  if (!local && !remote::Enabled()) return;
  ch->SetRecvTimeout(local ? 15000 : 5000);
  auto knockOk = [&](const uint8_t* k) { return CheckKnock(k); };
  if (!ch->ServerHandshake(g_app->cfg.hostKey, g_app->cfg.hostPub, !local, knockOk)) {
    if (!local) RecordFailure(ip);  // ten tries from the internet that fail and that address is ignored for 10 min
    return;
  }
  ch->SetRecvTimeout(15000);

  Bytes m;
  for (int step = 0; step < 8; step++) {
    if (!ch->Recv(m) || m.empty()) return;
    Reader r(m.data() + 1, m.size() - 1);
    switch (m[0]) {
      case msg::AUTH:
      case msg::STATUS_QUERY: {
        uint8_t id[16], tok[32];
        r.raw(id, 16);
        r.raw(tok, 32);
        std::string clientName = m[0] == msg::AUTH ? r.str() : "";
        if (m[0] == msg::AUTH) r.u16();
        uint8_t replyType = m[0] == msg::AUTH ? msg::AUTH_RESULT : msg::STATUS_RESULT;
        if (!r.ok) return;
        PairedDevice d;
        uint8_t st = st::OK;
        if (!g_app->cfg.FindDevice(id, d)) {
          st = st::UNKNOWN_DEVICE;
        } else {
          uint8_t th[32];
          crypto::Sha256(tok, 32, th);
          if (!ConstantTimeEq(th, d.tokenHash.data(), 32)) st = st::BAD_TOKEN;
        }
        SecureZeroMemory(tok, sizeof(tok));
        if (st != st::OK) {
          RecordFailure(ip);
          Log("server: %s %s rejected (%u)", peer.c_str(), m[0] == msg::AUTH ? "AUTH" : "STATUS", st);
          if (replyType == msg::AUTH_RESULT) {
            SendResult(*ch, msg::AUTH_RESULT, st,
                       "This phone is not linked to " + g_app->hostName + " anymore. Pair it again.");
          } else {
            Writer w(msg::STATUS_RESULT);
            w.u8(st).u8(0).str(g_app->hostName).str("").u32(0);
            ch->Send(w.b);
          }
          return;
        }
        auto cur = g_app->Active();
        bool otherActive = cur && cur->alive() && cur->device().id != d.id && NowMs() - cur->lastRecvMs() < 5000;
        if (m[0] == msg::STATUS_QUERY) {
          uint8_t flags = 0;
          if (otherActive) flags |= 1;
          if (IsInputDesktopSecure()) flags |= 2;
          if (g_opt.systemMode) flags |= 4;
          Writer w(msg::STATUS_RESULT);
          w.u8(st::OK).u8(flags).str(g_app->hostName).str(otherActive ? cur->device().name : "");
          w.u32((uint32_t)(GetTickCount64() / 1000));
          ch->Send(w.b);
          g_app->cfg.TouchDevice(d.id);
          return;
        }
        if (otherActive) {
          SendResult(*ch, msg::AUTH_RESULT, st::BUSY, "In use by " + cur->device().name);
          return;
        }
        // AUTH OK -> session
        SendResult(*ch, msg::AUTH_RESULT, st::OK, "");
        Writer hi(msg::HOST_INFO);
        hi.raw(g_app->cfg.hostId).str(g_app->hostName).u32(g_app->Capabilities());
        auto mons = ListMonitors();
        hi.u8((uint8_t)mons.size());
        for (auto& mo : mons) {
          hi.u8((uint8_t)mo.index).i32(mo.rect.left).i32(mo.rect.top);
          hi.i32(mo.rect.right - mo.rect.left).i32(mo.rect.bottom - mo.rect.top);
          hi.u8(mo.primary ? 1 : 0).str(mo.name);
        }
        auto addrs = g_app->LocalAddresses();
        std::string pub = remote::PublicAddress();  // the router's internet address, while it forwards our port
        if (!pub.empty()) addrs.push_back(pub);
        hi.u8((uint8_t)addrs.size());
        for (auto& a : addrs) hi.str(a);
        hi.u8(remote::Flags());  // what works over mobile data (caps::KNOCK)
        if (!ch->Send(hi.b)) return;
        auto sess = std::make_shared<Session>(std::move(ch), d);
        g_app->ActivateSession(sess);
        sess->Start();
        return;
      }
      case msg::PAIR_REQUEST: {
        uint8_t commit[32];
        r.raw(commit, 32);
        std::string name = r.str(), model = r.str();
        if (!r.ok) return;
        if (name.empty()) name = "Phone";
        if (name.size() > 60) name.resize(60);
        if (model.size() > 60) model.resize(60);
        if (!local) {  // a new phone is only ever linked at home
          Writer w(msg::PAIR_RESULT);
          w.u8(st::PAIRING_CLOSED).str("Link the phone at home, on the same Wi-Fi as " + g_app->hostName + ".");
          ch->Send(w.b);
          return;
        }
        if (!HandlePairing(*ch, name, model, commit)) return;
        ch->SetRecvTimeout(15000);
        break;  // the phone may now AUTH on the same connection
      }
      default:
        Log("server: %s unexpected message 0x%02x before auth", peer.c_str(), m[0]);
        return;
    }
  }
}

bool Server::HandlePairing(SecureChannel& ch, const std::string& name, const std::string& model,
                           const uint8_t commit[32]) {
  auto reply = [&](uint8_t status, const std::string& message) {
    Writer w(msg::PAIR_RESULT);
    w.u8(status).str(message);
    ch.Send(w.b);
  };
  if (!g_app->PairingOpen()) {
    reply(st::PAIRING_CLOSED, "Pairing is off on " + g_app->hostName +
                                  ". On the PC, open DeskLink (tray icon) and choose \"Pair a phone\".");
    return false;
  }
  if (g_app->pairingBusy.exchange(true)) {
    reply(st::PAIRING_CLOSED, "Another phone is pairing with this PC right now. Try again in a minute.");
    return false;
  }
  auto release = MakeScopeExit([] { g_app->pairingBusy = false; });

  uint8_t nonceH[32];
  crypto::Random(nonceH, 32);
  Writer ch1(msg::PAIR_CHALLENGE);
  ch1.raw(nonceH, 32);
  if (!ch.Send(ch1.b)) return false;
  ch.SetRecvTimeout(30000);
  Bytes m;
  if (!ch.Recv(m) || m.size() != 33 || m[0] != msg::PAIR_REVEAL) return false;
  const uint8_t* nonceC = m.data() + 1;
  uint8_t check[32];
  crypto::Sha256(nonceC, 32, check);
  if (!ConstantTimeEq(check, commit, 32)) {
    Log("server: pairing commitment mismatch from %s", ch.peer().c_str());
    return false;
  }
  static const char kSas[] = "desklink-v1-sas";
  crypto::Sha256Stream hs;
  hs.Update(kSas, sizeof(kSas) - 1);
  hs.Update(ch.th(), 32);
  hs.Update(nonceC, 32);
  hs.Update(nonceH, 32);
  uint8_t d[32];
  hs.Final(d);
  uint32_t v = (uint32_t)d[0] << 24 | (uint32_t)d[1] << 16 | (uint32_t)d[2] << 8 | d[3];
  char code[8];
  snprintf(code, sizeof(code), "%06u", v % 1000000u);
  Log("server: pairing request from '%s' (%s) at %s, code %s", name.c_str(), model.c_str(), ch.peer().c_str(), code);

  int decision = ui::AskPairing(name, model, code, 120000);
  if (decision != 1) {
    reply(decision == 0 ? st::DENIED : st::TIMEOUT,
          decision == 0 ? "The pairing was declined on the PC." : "Nobody answered on the PC in time.");
    ui::PairingFinished(false, name);
    return false;
  }
  PairedDevice dev;
  dev.id.resize(16);
  crypto::Random(dev.id.data(), 16);
  uint8_t token[32];
  crypto::Random(token, 32);
  dev.tokenHash.resize(32);
  crypto::Sha256(token, 32, dev.tokenHash.data());
  dev.name = name;
  dev.model = model;
  dev.created = UnixNow();
  dev.lastSeen = dev.created;
  g_app->cfg.AddDevice(dev);
  Writer w(msg::PAIR_RESULT);
  w.u8(st::OK).raw(dev.id).raw(token, 32).raw(g_app->cfg.hostId).str(g_app->hostName).str("Paired");
  bool ok = ch.Send(w.b);
  SecureZeroMemory(token, sizeof(token));
#ifdef DESKLINK_TESTING
  if (!g_opt.testAutoApprove)  // integration tests pair several fake phones
#endif
    g_app->ClosePairing();
  ui::PairingFinished(true, name);
  Log("server: paired '%s'", name.c_str());
  return ok;
}

std::string Server::DiscoveryJson() {
  auto cur = g_app->Active();
  std::string j = "{\"id\":\"" + Hex(g_app->cfg.hostId) + "\",\"name\":\"" + JsonEscape(g_app->hostName) +
                  "\",\"port\":" + std::to_string(port_) + ",\"fp\":\"" + g_app->cfg.fingerprint +
                  "\",\"pairing\":" + (g_app->PairingOpen() ? "true" : "false") +
                  ",\"busy\":" + (cur && cur->alive() ? "true" : "false") + ",\"ver\":1}";
  return j;
}

void Server::DiscoveryLoop() {
  char buf[512];
  while (!stop_) {
    sockaddr_in from{};
    int fl = sizeof(from);
    int n = recvfrom(udp_, buf, sizeof(buf), 0, (sockaddr*)&from, &fl);
    if (n <= 0) {
      if (stop_) break;
      Sleep(20);
      continue;
    }
    char fromIp[INET_ADDRSTRLEN] = {};
    inet_ntop(AF_INET, &from.sin_addr, fromIp, sizeof(fromIp));
    if (n >= 10 && memcmp(buf, "DESKLINK?1", 10) == 0 && remote::IsLocalAddress(fromIp)) {  // home network only
      std::string resp = "DESKLINK!1" + DiscoveryJson();
      sendto(udp_, resp.data(), (int)resp.size(), 0, (sockaddr*)&from, fl);
    }
  }
}
