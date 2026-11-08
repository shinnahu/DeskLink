#pragma once
#include "common.h"
#include "config.h"
#include "input.h"

class Session;

// Process-wide state shared by the server, sessions and the UI.
class App {
 public:
  bool Init();

  Config cfg;
  std::string hostName;
  InputInjector input;
  uint64_t startMs = NowMs();
  bool noUi = false;

  // ---- the (single) active remote session
  std::shared_ptr<Session> Active();
  // Makes s the active session; returns the session it replaced (already asked to stop).
  void ActivateSession(const std::shared_ptr<Session>& s);
  void OnSessionEnded(Session* s);
  void StopActive(uint8_t reason, const std::string& msg);

  // ---- pairing mode
  void OpenPairing(int seconds = 600);
  void ClosePairing();
  bool PairingOpen();
  int PairingSecondsLeft();
  std::atomic<bool> pairingBusy{false};

  uint32_t Capabilities() const;
  std::vector<std::string> LocalAddresses();
  void OnLocalClipboard(const std::string& utf8);

 private:
  std::mutex mu_;
  std::shared_ptr<Session> active_;
  uint64_t pairingUntil_ = 0;
};

extern App* g_app;
