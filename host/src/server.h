#pragma once
#include <map>

#include "common.h"

class SecureChannel;

// TCP listener (handshake, AUTH / STATUS_QUERY / pairing) + UDP LAN discovery responder.
class Server {
 public:
  ~Server();
  bool Start(const std::string& bindAddr, int port);
  void Stop();
  int port() const { return port_; }

 private:
  void AcceptLoop();
  void DiscoveryLoop();
  void HandleConnection(SOCKET s, std::string peer);
  bool HandlePairing(SecureChannel& ch, const std::string& name, const std::string& model, const uint8_t commit[32]);
  bool RateLimited(const std::string& ip);
  void RecordFailure(const std::string& ip);
  bool CheckKnock(const uint8_t* knock);
  std::string DiscoveryJson();

  SOCKET listen_ = INVALID_SOCKET;
  SOCKET udp_ = INVALID_SOCKET;
  std::thread acceptThread_, discThread_;
  std::atomic<bool> stop_{false};
  std::atomic<int> pending_{0};
  std::mutex failMu_;
  std::map<std::string, std::deque<uint64_t>> failures_;
  std::mutex knockMu_;
  std::map<std::string, uint64_t> knocks_;  // nonces of recent knocks: each opens one connection
  int port_ = 0;
};
