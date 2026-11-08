#include "config.h"

#include <cstdio>
#include <sstream>

std::wstring Config::Path() const { return DataDir() + L"\\config.txt"; }

static std::vector<std::string> Split(const std::string& s, char sep) {
  std::vector<std::string> out;
  std::string cur;
  for (char c : s) {
    if (c == sep) {
      out.push_back(cur);
      cur.clear();
    } else {
      cur.push_back(c);
    }
  }
  out.push_back(cur);
  return out;
}

bool Config::LoadOrCreate() {
  std::lock_guard<std::mutex> lk(mu_);
  std::string text;
  {
    FILE* f = _wfopen(Path().c_str(), L"rb");
    if (f) {
      char buf[4096];
      size_t n;
      while ((n = fread(buf, 1, sizeof(buf), f)) > 0) text.append(buf, n);
      fclose(f);
    }
  }
  std::string keyB64;
  std::istringstream in(text);
  std::string line;
  while (std::getline(in, line)) {
    while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
    if (line.empty() || line[0] == '#') continue;
    size_t eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string k = line.substr(0, eq), v = line.substr(eq + 1);
    if (k == "host_id") {
      Unhex(v, hostId);
    } else if (k == "host_key") {
      keyB64 = v;
    } else if (k == "port") {
      port = atoi(v.c_str());
    } else if (k == "remote") {
      remoteAccess_ = v != "0";
    } else if (k == "overlay") {
      auto p = Split(v, ',');
      if (p.size() == 2) {
        overlayX_ = atoi(p[0].c_str());
        overlayY_ = atoi(p[1].c_str());
      }
    } else if (k == "device") {
      auto p = Split(v, ',');
      if (p.size() >= 6) {
        PairedDevice d;
        if (!Unhex(p[0], d.id) || d.id.size() != 16) continue;
        if (!Unhex(p[1], d.tokenHash) || d.tokenHash.size() != 32) continue;
        d.created = _atoi64(p[2].c_str());
        d.lastSeen = _atoi64(p[3].c_str());
        d.name = UrlUnescape(p[4]);
        d.model = UrlUnescape(p[5]);
        devices_.push_back(d);
      }
    }
  }
  if (port <= 0 || port > 65535) port = kDefaultPort;

  bool dirty = false;
  if (hostId.size() != 16) {
    hostId.resize(16);
    crypto::Random(hostId.data(), 16);
    dirty = true;
  }
  bool haveKey = false;
  if (!keyB64.empty()) {
    Bytes prot, blob;
    if (B64Decode(keyB64, prot) && crypto::Unprotect(prot, blob) && crypto::EcKey::ImportEcdsaPrivate(blob, hostKey)) {
      hostKeyProtected_ = prot;
      haveKey = true;
    } else {
      Log("config: stored host key could not be decrypted — generating a new identity (phones must pair again)");
    }
    SecureZeroMemory(blob.data(), blob.size());
  }
  if (!haveKey) {
    if (!crypto::EcKey::GenerateEcdsa(hostKey)) return false;
    Bytes blob;
    hostKey.ExportPrivate(blob);
    crypto::Protect(blob, hostKeyProtected_, g_opt.systemMode);
    SecureZeroMemory(blob.data(), blob.size());
    devices_.clear();  // tokens were bound to the old identity's pairing; start clean
    dirty = true;
  }
  if (!hostKey.ExportPublic(hostPub)) return false;
  uint8_t fp[32];
  crypto::Sha256(hostPub, 65, fp);
  fingerprint = Hex(fp, 32);
  if (dirty) return SaveLocked();
  return true;
}

bool Config::SaveLocked() {
  std::string o;
  o += "# DeskLink host configuration (do not share: contains this PC's identity key)\n";
  o += "host_id=" + Hex(hostId) + "\n";
  o += "host_key=" + B64Encode(hostKeyProtected_) + "\n";
  o += "port=" + std::to_string(port) + "\n";
  o += std::string("remote=") + (remoteAccess_ ? "1" : "0") + "\n";
  if (overlayX_ != INT_MIN) o += "overlay=" + std::to_string(overlayX_) + "," + std::to_string(overlayY_) + "\n";
  for (auto& d : devices_) {
    o += "device=" + Hex(d.id) + "," + Hex(d.tokenHash) + "," + std::to_string(d.created) + "," +
         std::to_string(d.lastSeen) + "," + UrlEscape(d.name) + "," + UrlEscape(d.model) + "\n";
  }
  std::wstring tmp = Path() + L".tmp";
  FILE* f = _wfopen(tmp.c_str(), L"wb");
  if (!f) return false;
  fwrite(o.data(), 1, o.size(), f);
  fclose(f);
  return MoveFileExW(tmp.c_str(), Path().c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != FALSE;
}

bool Config::RemoteAccess() {
  std::lock_guard<std::mutex> lk(mu_);
  return remoteAccess_;
}

void Config::SetRemoteAccess(bool on) {
  std::lock_guard<std::mutex> lk(mu_);
  remoteAccess_ = on;
  SaveLocked();
}

bool Config::Save() {
  std::lock_guard<std::mutex> lk(mu_);
  return SaveLocked();
}

std::vector<PairedDevice> Config::Devices() {
  std::lock_guard<std::mutex> lk(mu_);
  return devices_;
}

size_t Config::DeviceCount() {
  std::lock_guard<std::mutex> lk(mu_);
  return devices_.size();
}

bool Config::FindDevice(const uint8_t* id16, PairedDevice& out) {
  std::lock_guard<std::mutex> lk(mu_);
  for (auto& d : devices_) {
    if (memcmp(d.id.data(), id16, 16) == 0) {
      out = d;
      return true;
    }
  }
  return false;
}

void Config::AddDevice(const PairedDevice& d) {
  std::lock_guard<std::mutex> lk(mu_);
  devices_.push_back(d);
  SaveLocked();
}

bool Config::RemoveDevice(const Bytes& id) {
  std::lock_guard<std::mutex> lk(mu_);
  for (auto it = devices_.begin(); it != devices_.end(); ++it) {
    if (it->id == id) {
      devices_.erase(it);
      SaveLocked();
      return true;
    }
  }
  return false;
}

void Config::TouchDevice(const Bytes& id) {
  std::lock_guard<std::mutex> lk(mu_);
  for (auto& d : devices_) {
    if (d.id == id) {
      d.lastSeen = UnixNow();
      SaveLocked();
      return;
    }
  }
}

void Config::SetOverlayPos(int x, int y) {
  std::lock_guard<std::mutex> lk(mu_);
  overlayX_ = x;
  overlayY_ = y;
  SaveLocked();
}

void Config::GetOverlayPos(int& x, int& y) {
  std::lock_guard<std::mutex> lk(mu_);
  x = overlayX_;
  y = overlayY_;
}
