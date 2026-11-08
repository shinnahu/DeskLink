#include "remote.h"

#include "common.h"  // windows.h first: winhttp.h needs it

#include <iphlpapi.h>
#include <winhttp.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <map>
#include <thread>

#include "app.h"
#include "strings.h"

#pragma comment(lib, "winhttp.lib")

namespace remote {
namespace {

constexpr int kLeaseSeconds = 3600;
constexpr auto kRenewEvery = std::chrono::minutes(30);

// ---------------------------------------------------------------- small string helpers
std::string Trim(const std::string& s) {
  size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
  return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}

std::string Lower(std::string s) {
  for (auto& c : s) c = (char)tolower((unsigned char)c);
  return s;
}

std::string XmlUnescape(std::string s) {
  static const std::pair<const char*, const char*> kEnt[] = {
      {"&lt;", "<"}, {"&gt;", ">"}, {"&quot;", "\""}, {"&apos;", "'"}, {"&amp;", "&"}};  // &amp; last
  for (auto& e : kEnt) {
    size_t p = 0, n = strlen(e.first), m = strlen(e.second);
    while ((p = s.find(e.first, p)) != std::string::npos) {
      s.replace(p, n, e.second);
      p += m;
    }
  }
  return s;
}

// The text of the first element called `name` (any namespace prefix) at or after `from`; "" if none. Enough for the
// flat elements of UPnP descriptions and SOAP replies.
std::string Tag(const std::string& x, const std::string& name, size_t from = 0) {
  for (size_t p = x.find('<', from); p != std::string::npos; p = x.find('<', p + 1)) {
    if (p + 1 >= x.size()) break;
    char c = x[p + 1];
    if (c == '/' || c == '?' || c == '!') continue;
    size_t e = x.find_first_of(" \t\r\n/>", p + 1);
    if (e == std::string::npos) break;
    std::string tn = x.substr(p + 1, e - p - 1);
    size_t colon = tn.find(':');
    if (colon != std::string::npos) tn = tn.substr(colon + 1);
    if (tn != name) continue;
    size_t gt = x.find('>', e);
    if (gt == std::string::npos) break;
    if (x[gt - 1] == '/') return "";  // <name/>
    size_t close = x.find("</", gt + 1);
    if (close == std::string::npos) break;
    return XmlUnescape(Trim(x.substr(gt + 1, close - gt - 1)));
  }
  return "";
}

// "http://192.168.0.1:5000/rootDesc.xml" -> "http://192.168.0.1:5000"
std::string Origin(const std::string& url) {
  size_t s = url.find("://");
  if (s == std::string::npos) return "";
  size_t p = url.find('/', s + 3);
  return p == std::string::npos ? url : url.substr(0, p);
}

// "http://192.168.0.1:5000/x" -> "192.168.0.1"
std::string HostOf(const std::string& url) {
  std::string o = Origin(url);
  size_t s = o.find("://");
  std::string hp = s == std::string::npos ? o : o.substr(s + 3);
  size_t colon = hp.rfind(':');
  return colon == std::string::npos ? hp : hp.substr(0, colon);
}

std::string Resolve(const std::string& base, const std::string& u) {
  if (u.empty()) return "";
  if (u.rfind("http://", 0) == 0 || u.rfind("https://", 0) == 0) return u;
  std::string b = base;
  while (!b.empty() && b.back() == '/') b.pop_back();
  return u[0] == '/' ? Origin(b) + u : b + "/" + u;
}

// ---------------------------------------------------------------- HTTP to the router (never through a proxy)
int Http(const std::string& url, const wchar_t* method, const std::string& body, const std::string& soapAction,
         std::string& out) {
  out.clear();
  std::wstring w = U2W(url);
  URL_COMPONENTS uc{};
  uc.dwStructSize = sizeof(uc);
  wchar_t host[256] = {}, path[2048] = {}, extra[1024] = {};
  uc.lpszHostName = host;
  uc.dwHostNameLength = 256;
  uc.lpszUrlPath = path;
  uc.dwUrlPathLength = 2048;
  uc.lpszExtraInfo = extra;
  uc.dwExtraInfoLength = 1024;
  if (!WinHttpCrackUrl(w.c_str(), 0, 0, &uc)) return 0;
  HINTERNET ses = WinHttpOpen(L"DeskLink", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (!ses) return 0;
  auto c1 = MakeScopeExit([&] { WinHttpCloseHandle(ses); });
  WinHttpSetTimeouts(ses, 3000, 3000, 3000, 5000);
  HINTERNET con = WinHttpConnect(ses, host, uc.nPort, 0);
  if (!con) return 0;
  auto c2 = MakeScopeExit([&] { WinHttpCloseHandle(con); });
  std::wstring obj = std::wstring(path) + extra;
  HINTERNET req = WinHttpOpenRequest(con, method, obj.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                     uc.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0);
  if (!req) return 0;
  auto c3 = MakeScopeExit([&] { WinHttpCloseHandle(req); });
  std::wstring hdr;
  if (!soapAction.empty()) hdr = L"Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPAction: \"" + U2W(soapAction) + L"\"\r\n";
  if (!WinHttpSendRequest(req, hdr.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : hdr.c_str(), hdr.empty() ? 0 : (DWORD)-1L,
                          body.empty() ? WINHTTP_NO_REQUEST_DATA : (LPVOID)body.data(), (DWORD)body.size(),
                          (DWORD)body.size(), 0) ||
      !WinHttpReceiveResponse(req, nullptr)) {
    return 0;
  }
  DWORD code = 0, sz = sizeof(code);
  WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &code, &sz,
                      WINHTTP_NO_HEADER_INDEX);
  for (;;) {
    DWORD avail = 0;
    if (!WinHttpQueryDataAvailable(req, &avail) || avail == 0) break;
    if (out.size() + avail > 512 * 1024) break;  // a router's description is a few KB
    size_t at = out.size();
    out.resize(at + avail);
    DWORD got = 0;
    if (!WinHttpReadData(req, &out[at], avail, &got)) {
      out.resize(at);
      break;
    }
    out.resize(at + got);
  }
  return (int)code;
}

// ---------------------------------------------------------------- UPnP: find the router (SSDP) and its services
std::vector<std::string> DiscoverLocations() {
  std::vector<std::string> out;
  SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == INVALID_SOCKET) return out;
  auto c = MakeScopeExit([&] { closesocket(s); });
  sockaddr_in any{};
  any.sin_family = AF_INET;
  bind(s, (sockaddr*)&any, sizeof(any));
  DWORD ttl = 2;
  setsockopt(s, IPPROTO_IP, IP_MULTICAST_TTL, (const char*)&ttl, sizeof(ttl));
  sockaddr_in to{};
  to.sin_family = AF_INET;
  to.sin_port = htons(1900);
  inet_pton(AF_INET, "239.255.255.250", &to.sin_addr);
  static const char* kSt[] = {"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                              "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                              "urn:schemas-upnp-org:service:WANIPConnection:1",
                              "urn:schemas-upnp-org:service:WANPPPConnection:1"};
  for (int round = 0; round < 2; round++) {
    for (const char* st : kSt) {
      std::string m = std::string("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ") +
                      st + "\r\n\r\n";
      sendto(s, m.data(), (int)m.size(), 0, (sockaddr*)&to, sizeof(to));
    }
  }
  uint64_t end = NowMs() + 2500;
  char buf[2048];
  while (NowMs() < end) {
    fd_set fs;
    FD_ZERO(&fs);
    FD_SET(s, &fs);
    timeval tv{0, 200000};
    if (select(0, &fs, nullptr, nullptr, &tv) <= 0) continue;
    int n = recv(s, buf, sizeof(buf) - 1, 0);
    if (n <= 0) continue;
    std::string r(buf, (size_t)n);
    std::string low = Lower(r);
    size_t k = low.find("\nlocation:");
    if (k == std::string::npos) continue;
    size_t v = k + 10, e = r.find("\r\n", v);
    std::string loc = Trim(r.substr(v, e == std::string::npos ? std::string::npos : e - v));
    if (loc.rfind("http://", 0) == 0 && std::find(out.begin(), out.end(), loc) == out.end()) out.push_back(loc);
  }
  return out;
}

struct Soap {
  std::string control, type;
};

struct Gateway {
  bool ok = false;
  std::string location;
  std::vector<Soap> wans;  // WANIPConnection / WANPPPConnection services; the first that works is used
  Soap fw;                 // WANIPv6FirewallControl (IGD v2), if the router has it
  std::string localIp;     // this PC's IPv4 address facing the router
};

std::string LocalIpToward(const std::string& host) {
  SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == INVALID_SOCKET) return "";
  auto c = MakeScopeExit([&] { closesocket(s); });
  sockaddr_in to{};
  to.sin_family = AF_INET;
  to.sin_port = htons(1900);
  if (inet_pton(AF_INET, host.c_str(), &to.sin_addr) != 1) return "";
  if (connect(s, (sockaddr*)&to, sizeof(to)) != 0) return "";  // UDP: nothing is sent
  sockaddr_in me{};
  int len = sizeof(me);
  if (getsockname(s, (sockaddr*)&me, &len) != 0) return "";
  char out[INET_ADDRSTRLEN] = {};
  inet_ntop(AF_INET, &me.sin_addr, out, sizeof(out));
  return out;
}

bool LoadGateway(const std::string& location, Gateway& gw) {
  std::string desc;
  if (Http(location, L"GET", "", "", desc) != 200 || desc.empty()) return false;
  std::string base = Tag(desc, "URLBase");
  if (base.empty()) base = Origin(location);
  Gateway g;
  g.location = location;
  for (size_t p = desc.find("<service"); p != std::string::npos; p = desc.find("<service", p + 1)) {
    char after = p + 8 < desc.size() ? desc[p + 8] : 0;
    if (after != '>' && after != ' ') continue;  // <serviceType>, <serviceList>, <serviceId>
    size_t e = desc.find("</service>", p);
    if (e == std::string::npos) break;
    std::string blk = desc.substr(p, e - p);
    Soap s{Resolve(base, Tag(blk, "controlURL")), Tag(blk, "serviceType")};
    if (s.control.empty() || s.type.empty()) continue;
    if (s.type.find(":service:WANIPConnection:") != std::string::npos ||
        s.type.find(":service:WANPPPConnection:") != std::string::npos) {
      g.wans.push_back(s);
    } else if (s.type.find(":service:WANIPv6FirewallControl:") != std::string::npos && g.fw.control.empty()) {
      g.fw = s;
    }
  }
  if (g.wans.empty() && g.fw.control.empty()) return false;
  g.localIp = LocalIpToward(HostOf(location));
  g.ok = true;
  gw = g;
  return true;
}

std::string Arg(const char* n, const std::string& v) { return std::string("<") + n + ">" + v + "</" + n + ">"; }
std::string Arg(const char* n, int v) { return Arg(n, std::to_string(v)); }

int Call(const Soap& sv, const char* action, const std::string& args, std::string& resp) {
  std::string body = std::string("<?xml version=\"1.0\"?>\r\n") +
                     "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                     "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" +
                     action + " xmlns:u=\"" + sv.type + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>\r\n";
  return Http(sv.control, L"POST", body, sv.type + "#" + action, resp);
}

int ErrorCode(const std::string& resp) {
  std::string e = Tag(resp, "errorCode");
  return e.empty() ? 0 : atoi(e.c_str());
}

bool ParseV4(const std::string& ip, uint8_t b[4]) {
  in_addr a{};
  if (inet_pton(AF_INET, ip.c_str(), &a) != 1) return false;
  memcpy(b, &a, 4);
  return true;
}

bool IsPublicV4(const std::string& ip) {
  uint8_t b[4];
  if (!ParseV4(ip, b)) return false;
  if (b[0] == 0 || b[0] == 10 || b[0] == 127) return false;
  if (b[0] == 172 && (b[1] & 0xF0) == 16) return false;
  if (b[0] == 192 && b[1] == 168) return false;
  if (b[0] == 169 && b[1] == 254) return false;
  if (b[0] == 100 && (b[1] & 0xC0) == 64) return false;  // 100.64.0.0/10: the internet company's own NAT (CGNAT)
  if (b[0] >= 224) return false;
  return true;
}

// Forwards the TCP port to this PC. Tries the same port outside, then a few others if someone else has it.
bool MapPort(const Soap& sv, int port, const std::string& client, int& extOut) {
  for (int i = 0; i < 4; i++) {
    int ext = i == 0 ? port : port + 1000 * i + 7;
    for (int lease : {kLeaseSeconds, 0}) {
      std::string resp;
      std::string args = Arg("NewRemoteHost", "") + Arg("NewExternalPort", ext) + Arg("NewProtocol", "TCP") +
                         Arg("NewInternalPort", port) + Arg("NewInternalClient", client) + Arg("NewEnabled", "1") +
                         Arg("NewPortMappingDescription", "DeskLink") + Arg("NewLeaseDuration", lease);
      if (Call(sv, "AddPortMapping", args, resp) == 200) {
        extOut = ext;
        return true;
      }
      int err = ErrorCode(resp);
      if (err == 725 && lease != 0) continue;  // OnlyPermanentLeasesSupported
      if (err == 718) {                        // ConflictInMappingEntry: is it ours already?
        std::string r2;
        std::string q = Arg("NewRemoteHost", "") + Arg("NewExternalPort", ext) + Arg("NewProtocol", "TCP");
        if (Call(sv, "GetSpecificPortMappingEntry", q, r2) == 200 && Tag(r2, "NewInternalClient") == client &&
            atoi(Tag(r2, "NewInternalPort").c_str()) == port) {
          extOut = ext;
          return true;
        }
        break;  // someone else's: try another outside port
      }
      Log("remote: AddPortMapping %d refused (error %d)", ext, err);
      return false;  // not authorized, or the router won't do it
    }
  }
  return false;
}

// ---------------------------------------------------------------- state
std::mutex g_mu;
std::condition_variable g_cv;
bool g_stop = false, g_wake = false;
std::thread g_thread;
int g_port = 0;
uint8_t g_flags = CHECKING;
std::string g_public;
std::string g_testLocation;

// worker-thread only
Gateway g_gw;
Soap g_wan;          // the WAN service in use
int g_mappedExt = 0;
std::map<std::string, std::string> g_pinholes;  // IPv6 address -> the router's UniqueID

void Publish(uint8_t f, const std::string& pub) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (f != g_flags || pub != g_public) Log("remote: flags 0x%02x %s", f, pub.c_str());
  g_flags = f;
  g_public = pub;
}

bool FindGateway() {
  std::vector<std::string> locs;
  if (!g_testLocation.empty()) {
    locs.push_back(g_testLocation);
  } else {
#ifdef DESKLINK_TESTING
    return false;  // test builds never look for the real router
#else
    locs = DiscoverLocations();
#endif
  }
  for (auto& l : locs) {
    Gateway g;
    if (!LoadGateway(l, g)) continue;
    g_gw = g;
    g_wan = {};
    for (auto& w : g.wans) {  // the WAN connection that has an internet address
      std::string r;
      if (Call(w, "GetExternalIPAddress", "", r) == 200 && !Tag(r, "NewExternalIPAddress").empty()) {
        g_wan = w;
        break;
      }
    }
    if (g_wan.control.empty() && !g.wans.empty()) g_wan = g.wans[0];
    Log("remote: router %s (%zu WAN services, IPv6 firewall control: %s), this PC is %s", l.c_str(), g.wans.size(),
        g.fw.control.empty() ? "no" : "yes", g.localIp.c_str());
    return true;
  }
  return false;
}

// The router's IPv6 firewall: open DeskLink's port to this PC's stable addresses (IGD v2), or find it isn't blocking.
bool OpenV6(int port, const std::vector<std::string>& addrs) {
  if (g_gw.fw.control.empty() || addrs.empty()) return false;
  std::string r;
  if (Call(g_gw.fw, "GetFirewallStatus", "", r) != 200) return false;
  if (Tag(r, "FirewallEnabled") == "0") return true;  // nothing to open
  if (Tag(r, "InboundPinholeAllowed") != "1") return false;
  bool any = false;
  for (auto& a : addrs) {
    auto it = g_pinholes.find(a);
    if (it != g_pinholes.end()) {
      std::string r2;
      if (Call(g_gw.fw, "UpdatePinhole", Arg("UniqueID", it->second) + Arg("NewLeaseTime", kLeaseSeconds), r2) == 200) {
        any = true;
        continue;
      }
      g_pinholes.erase(it);
    }
    std::string r3;
    std::string args = Arg("RemoteHost", "") + Arg("RemotePort", 0) + Arg("InternalClient", a) + Arg("InternalPort", port) +
                       Arg("Protocol", 6) + Arg("LeaseTime", kLeaseSeconds);
    if (Call(g_gw.fw, "AddPinhole", args, r3) == 200) {
      g_pinholes[a] = Tag(r3, "UniqueID");
      any = true;
    } else {
      Log("remote: AddPinhole %s refused (error %d)", a.c_str(), ErrorCode(r3));
    }
  }
  return any;
}

void Refresh() {
  std::vector<std::string> v6 = StableGlobalIpv6();
  uint8_t f = 0;
  std::string pub;
  if (!g_gw.ok && !FindGateway()) {
    f |= NO_UPNP;
  } else {
    std::string ext;
    if (!g_wan.control.empty()) {
      std::string r;
      if (Call(g_wan, "GetExternalIPAddress", "", r) == 200) ext = Tag(r, "NewExternalIPAddress");
      if (!ext.empty() && !IsPublicV4(ext)) {
        // The internet company shares one address between many homes (CGNAT): forwarding the port can't make the
        // PC reachable, and would only open it to that company's own network. So don't (and undo an older one).
        f |= SHARED_IPV4;
        if (g_mappedExt) {
          Call(g_wan, "DeletePortMapping", Arg("NewRemoteHost", "") + Arg("NewExternalPort", g_mappedExt) + Arg("NewProtocol", "TCP"), r);
          g_mappedExt = 0;
        }
      } else {
        int extPort = 0;
        bool mapped = MapPort(g_wan, g_port, g_gw.localIp, extPort);
        if (mapped) g_mappedExt = extPort;
        if (mapped && !ext.empty()) {
          f |= IPV4;
          pub = extPort == g_port ? ext : ext + ":" + std::to_string(extPort);
        } else {
          if (!mapped && ext.empty()) g_gw.ok = false;  // the router stopped answering: look for it again next time
          f |= NO_UPNP;  // or it answers but won't forward the port
        }
      }
    }
    if (OpenV6(g_port, v6)) f |= IPV6;
  }
  if (!v6.empty() && !(f & IPV6)) f |= IPV6_MAYBE;
  Publish(f, pub);
}

void RemoveAll() {
  if (g_mappedExt && !g_wan.control.empty()) {
    std::string r;
    Call(g_wan, "DeletePortMapping", Arg("NewRemoteHost", "") + Arg("NewExternalPort", g_mappedExt) + Arg("NewProtocol", "TCP"), r);
    g_mappedExt = 0;
  }
  for (auto& p : g_pinholes) {
    std::string r;
    Call(g_gw.fw, "DeletePinhole", Arg("UniqueID", p.second), r);
  }
  g_pinholes.clear();
}

void Loop() {
  {
    std::unique_lock<std::mutex> lk(g_mu);
    g_cv.wait_for(lk, std::chrono::seconds(3), [] { return g_stop; });  // let the network settle after boot
  }
  bool active = false;
  for (;;) {
    {
      std::lock_guard<std::mutex> lk(g_mu);
      if (g_stop) break;
    }
    if (Enabled()) {
      Refresh();
      active = true;
    } else {
      if (active) RemoveAll();
      active = false;
      Publish(OFF, "");
    }
    std::unique_lock<std::mutex> lk(g_mu);
    g_cv.wait_for(lk, kRenewEvery, [] { return g_stop || g_wake; });
    if (g_stop) break;
    g_wake = false;
  }
  if (active) RemoveAll();
}

}  // namespace

// ---------------------------------------------------------------- public
void Start(int port) {
  g_port = port;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    g_flags = Enabled() ? CHECKING : OFF;
  }
#ifdef DESKLINK_TESTING
  if (g_testLocation.empty()) return;  // tests drive it with RefreshNowForTest
#endif
  g_thread = std::thread(Loop);
}

void Stop() {
  {
    std::lock_guard<std::mutex> lk(g_mu);
    g_stop = true;
  }
  g_cv.notify_all();
  if (g_thread.joinable()) g_thread.join();
}

bool Enabled() { return g_app ? g_app->cfg.RemoteAccess() : false; }

void SetEnabled(bool on) {
  if (!g_app) return;
  g_app->cfg.SetRemoteAccess(on);
  {
    std::lock_guard<std::mutex> lk(g_mu);
    g_flags = on ? CHECKING : OFF;
    if (!on) g_public.clear();
    g_wake = true;
  }
  g_cv.notify_all();
}

uint8_t Flags() {
  std::lock_guard<std::mutex> lk(g_mu);
  return g_flags;
}

std::string PublicAddress() {
  std::lock_guard<std::mutex> lk(g_mu);
  return g_public;
}

std::wstring Describe() {
  uint8_t f;
  std::string pub;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    f = g_flags;
    pub = g_public;
  }
  if (f & OFF) return S(S_REMOTE_OFF);
  if (f & CHECKING) return S(S_REMOTE_CHECKING);
  if ((f & IPV4) && (f & IPV6)) return Fmt(S_REMOTE_READY, U2W(pub) + L" + IPv6");
  if (f & IPV4) return Fmt(S_REMOTE_READY, U2W(pub));
  if (f & IPV6) return Fmt(S_REMOTE_READY, L"IPv6");
  if (f & IPV6_MAYBE) return S(S_REMOTE_MAYBE_V6);
  if (f & SHARED_IPV4) return S(S_REMOTE_SHARED);
  return S(S_REMOTE_NO_UPNP);
}

bool IsLocalAddress(const std::string& ip) {
#ifdef DESKLINK_TESTING
  if (g_opt.testPeersAreInternet) return false;
#endif
  uint8_t b4[4];
  if (ParseV4(ip, b4)) return !IsPublicV4(ip) || b4[0] == 127;
  in6_addr a{};
  if (inet_pton(AF_INET6, ip.c_str(), &a) != 1) return false;
  const uint8_t* b = a.u.Byte;
  static const uint8_t kLoop[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
  if (memcmp(b, kLoop, 16) == 0) return true;
  if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return true;  // link-local
  if ((b[0] & 0xfe) == 0xfc) return true;                  // unique-local (home network, Tailscale)
  static const uint8_t kMapped[12] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff};
  if (memcmp(b, kMapped, 12) == 0) {
    char v4[INET_ADDRSTRLEN] = {};
    inet_ntop(AF_INET, b + 12, v4, sizeof(v4));
    return IsLocalAddress(v4);
  }
  return false;
}

// "::ffff:a.b.c.d" (IPv4 on a dual-stack socket) -> "a.b.c.d"; anything else as it is
static std::string PlainV4(const std::string& ip) {
  if (ip.size() > 7 && _strnicmp(ip.c_str(), "::ffff:", 7) == 0 && ip.find('.') != std::string::npos) return ip.substr(7);
  return ip;
}

static bool InCgnatRange(const std::string& ip) {  // 100.64.0.0/10: Tailscale, or an internet company's NAT
  uint8_t b[4];
  return ParseV4(PlainV4(ip), b) && b[0] == 100 && (b[1] & 0xC0) == 64;
}

std::vector<std::string> TailscaleAddresses() {
  std::vector<std::string> out;
  ULONG size = 16 * 1024;
  std::vector<uint8_t> buf(size);
  auto* aa = (IP_ADAPTER_ADDRESSES*)buf.data();
  ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
  ULONG r = GetAdaptersAddresses(AF_INET, flags, nullptr, aa, &size);
  if (r == ERROR_BUFFER_OVERFLOW) {
    buf.resize(size);
    aa = (IP_ADAPTER_ADDRESSES*)buf.data();
    r = GetAdaptersAddresses(AF_INET, flags, nullptr, aa, &size);
  }
  if (r != NO_ERROR) return out;
  for (auto* a = aa; a; a = a->Next) {
    if (a->OperStatus != IfOperStatusUp) continue;
    std::wstring names = std::wstring(a->FriendlyName ? a->FriendlyName : L"") + L" " + (a->Description ? a->Description : L"");
    for (auto& c : names) c = (wchar_t)towlower(c);
    if (names.find(L"tailscale") == std::wstring::npos) continue;  // its adapter: "Tailscale" / "Tailscale Tunnel"
    for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
      if (u->Address.lpSockaddr->sa_family != AF_INET) continue;
      char host[INET_ADDRSTRLEN] = {};
      inet_ntop(AF_INET, &((sockaddr_in*)u->Address.lpSockaddr)->sin_addr, host, sizeof(host));
      out.push_back(host);
    }
  }
  return out;
}

bool IsHomeConnection(const std::string& peer, const std::string& ours, const std::vector<std::string>& tailscale) {
  if (!IsLocalAddress(peer)) return false;
  if (!InCgnatRange(peer)) return true;
  std::string me = PlainV4(ours);
  for (auto& t : tailscale)
    if (!me.empty() && t == me) return true;  // it came in through this PC's Tailscale
  return false;  // the same range on the internet company's side: a stranger
}

bool IsHomeConnection(const std::string& peer, const std::string& ours) {
  if (!InCgnatRange(peer)) return IsLocalAddress(peer);  // no need to look at the adapters
  return IsHomeConnection(peer, ours, TailscaleAddresses());
}

std::vector<std::string> StableGlobalIpv6() {
  std::vector<std::string> stable, dhcp;
  ULONG size = 32 * 1024;
  std::vector<uint8_t> buf(size);
  auto* aa = (IP_ADAPTER_ADDRESSES*)buf.data();
  ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
  ULONG r = GetAdaptersAddresses(AF_INET6, flags, nullptr, aa, &size);
  if (r == ERROR_BUFFER_OVERFLOW) {
    buf.resize(size);
    aa = (IP_ADAPTER_ADDRESSES*)buf.data();
    r = GetAdaptersAddresses(AF_INET6, flags, nullptr, aa, &size);
  }
  if (r != NO_ERROR) return {};
  for (auto* a = aa; a; a = a->Next) {
    if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK || a->IfType == IF_TYPE_TUNNEL) continue;
    for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
      if (u->Address.lpSockaddr->sa_family != AF_INET6 || u->DadState != IpDadStatePreferred) continue;
      const uint8_t* b = ((sockaddr_in6*)u->Address.lpSockaddr)->sin6_addr.u.Byte;
      if ((b[0] & 0xe0) != 0x20) continue;                                  // global unicast only
      if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) continue;  // Teredo
      if (b[0] == 0x20 && b[1] == 0x02) continue;                           // 6to4
      if (u->SuffixOrigin == IpSuffixOriginRandom) continue;                // temporary: changes every day
      char host[INET6_ADDRSTRLEN] = {};
      inet_ntop(AF_INET6, b, host, sizeof(host));
      (u->SuffixOrigin == IpSuffixOriginDhcp ? dhcp : stable).push_back(host);
    }
  }
  for (auto& d : dhcp) stable.push_back(d);
  if (stable.size() > 2) stable.resize(2);
  return stable;
}

#ifdef DESKLINK_TESTING
void SetTestLocation(const std::string& url) { g_testLocation = url; }

uint8_t RefreshNowForTest(int port) {
  g_port = port;
  Refresh();
  return Flags();
}
#endif

}  // namespace remote
