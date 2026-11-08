#pragma once
#include <functional>

#include "common.h"
#include "crypto.h"

// Server side of the DeskLink secure channel (PROTOCOL.md §2–3).
// Send() may be called from one thread at a time (guarded), Recv() from one reader thread.
class SecureChannel {
 public:
  SecureChannel(SOCKET s, std::string peer) : s_(s), peer_(std::move(peer)) {}
  ~SecureChannel();
  SecureChannel(const SecureChannel&) = delete;
  SecureChannel& operator=(const SecureChannel&) = delete;

  // knockRequired: the peer is on the internet, so a knock (checkKnock) must come before the ClientHello. From the
  // home network a knock is optional (and still checked if sent).
  bool ServerHandshake(const crypto::EcKey& hostKey, const uint8_t hostPub[65], bool knockRequired,
                       const std::function<bool(const uint8_t* knock)>& checkKnock);
  bool Send(const uint8_t* p, size_t n);
  bool Send(const Bytes& b) { return Send(b.data(), b.size()); }
  bool Recv(Bytes& plain);
  void SetRecvTimeout(int ms);
  void Shutdown();  // unblocks a reader stuck in recv()

  const uint8_t* th() const { return th_; }
  SOCKET sock() const { return s_; }
  const std::string& peer() const { return peer_; }
  std::string peerIp() const;

 private:
  bool SendAll(const void* p, size_t n);
  bool RecvAll(void* p, size_t n);

  SOCKET s_;
  std::string peer_;
  crypto::AesGcm tx_, rx_;
  uint64_t txSeq_ = 0, rxSeq_ = 0;
  uint8_t th_[32] = {};
  Bytes txBuf_, rxBuf_;
  std::mutex sendMu_;
  std::atomic<bool> closed_{false};
};
