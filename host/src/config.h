#pragma once
#include <climits>

#include "common.h"
#include "crypto.h"
#include "protocol.h"

struct PairedDevice {
  Bytes id;         // 16 bytes, assigned by the host at pairing
  Bytes tokenHash;  // SHA-256 of the 32-byte secret token held by the phone
  std::string name;
  std::string model;
  int64_t created = 0;
  int64_t lastSeen = 0;
};

class Config {
 public:
  bool LoadOrCreate();
  bool Save();

  std::vector<PairedDevice> Devices();
  bool FindDevice(const uint8_t* id16, PairedDevice& out);
  void AddDevice(const PairedDevice& d);
  bool RemoveDevice(const Bytes& id);
  void TouchDevice(const Bytes& id);
  size_t DeviceCount();

  void SetOverlayPos(int x, int y);
  void GetOverlayPos(int& x, int& y);

  // Reaching the PC over mobile data (remote.cpp): on unless turned off in the tray menu.
  bool RemoteAccess();
  void SetRemoteAccess(bool on);

  Bytes hostId;
  crypto::EcKey hostKey;
  uint8_t hostPub[65] = {};
  std::string fingerprint;  // 64 hex chars
  int port = kDefaultPort;

 private:
  bool SaveLocked();
  std::wstring Path() const;

  std::mutex mu_;
  std::vector<PairedDevice> devices_;
  Bytes hostKeyProtected_;
  int overlayX_ = INT_MIN, overlayY_ = INT_MIN;
  bool remoteAccess_ = true;
};
