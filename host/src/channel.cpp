#include "channel.h"

#include "protocol.h"

static constexpr size_t kClientHello = 102;
static constexpr size_t kServerHello = 231;
static constexpr size_t kSignedPart = 167;
static constexpr uint32_t kMaxRecord = 16u * 1024 * 1024;

static void Put32(uint8_t* p, uint32_t v) {
  p[0] = (uint8_t)(v >> 24);
  p[1] = (uint8_t)(v >> 16);
  p[2] = (uint8_t)(v >> 8);
  p[3] = (uint8_t)v;
}
static uint32_t Get32(const uint8_t* p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }

SecureChannel::~SecureChannel() {
  if (s_ != INVALID_SOCKET) closesocket(s_);
}

void SecureChannel::SetRecvTimeout(int ms) {
  DWORD t = (DWORD)ms;
  setsockopt(s_, SOL_SOCKET, SO_RCVTIMEO, (const char*)&t, sizeof(t));
}

void SecureChannel::Shutdown() {
  if (!closed_.exchange(true)) shutdown(s_, SD_BOTH);
}

std::string SecureChannel::peerIp() const {
  size_t k = peer_.rfind(':');
  std::string ip = k == std::string::npos ? peer_ : peer_.substr(0, k);
  if (!ip.empty() && ip.front() == '[') ip = ip.substr(1, ip.size() - 2);
  return ip;
}

bool SecureChannel::SendAll(const void* p, size_t n) {
  const char* c = (const char*)p;
  while (n > 0) {
    int k = send(s_, c, (int)(n > 1 << 20 ? 1 << 20 : n), 0);
    if (k <= 0) return false;
    c += k;
    n -= (size_t)k;
  }
  return true;
}

bool SecureChannel::RecvAll(void* p, size_t n) {
  char* c = (char*)p;
  while (n > 0) {
    int k = recv(s_, c, (int)(n > 1 << 20 ? 1 << 20 : n), 0);
    if (k <= 0) return false;
    c += k;
    n -= (size_t)k;
  }
  return true;
}

bool SecureChannel::ServerHandshake(const crypto::EcKey& hostKey, const uint8_t hostPub[65], bool knockRequired,
                                    const std::function<bool(const uint8_t* knock)>& checkKnock) {
  uint8_t lenb[4];
  uint8_t ch[kClientHello];
  if (!RecvAll(lenb, 4)) return false;
  uint32_t len = Get32(lenb);
  if (len == kKnockSize) {
    uint8_t k[kKnockSize];
    if (!RecvAll(k, kKnockSize) || !checkKnock(k)) {
      Log("handshake %s: bad knock", peer_.c_str());
      return false;
    }
    if (!RecvAll(lenb, 4)) return false;
    len = Get32(lenb);
  } else if (knockRequired) {
    Log("handshake %s: from the internet without a knock", peer_.c_str());
    return false;  // nothing else is read: scanners never reach the handshake
  }
  if (len != kClientHello || !RecvAll(ch, kClientHello)) {
    Log("handshake %s: bad ClientHello", peer_.c_str());
    return false;
  }
  if (memcmp(ch, "DLNK", 4) != 0 || ch[4] != kProtocolVersion || ch[5] != 0x04) {
    Log("handshake %s: unsupported magic/version %u", peer_.c_str(), ch[4]);
    return false;
  }

  crypto::EcKey eph;
  if (!crypto::EcKey::GenerateEcdh(eph)) return false;
  uint8_t sh[kServerHello];
  memcpy(sh, "DLNK", 4);
  sh[4] = kProtocolVersion;
  if (!eph.ExportPublic(sh + 5)) return false;
  crypto::Random(sh + 70, 32);
  memcpy(sh + 102, hostPub, 65);

  Bytes m(ch, ch + kClientHello);
  m.insert(m.end(), sh, sh + kSignedPart);
  if (!hostKey.SignSha256(m.data(), m.size(), sh + kSignedPart)) return false;

  uint8_t frame[4 + kServerHello];
  Put32(frame, (uint32_t)kServerHello);
  memcpy(frame + 4, sh, kServerHello);
  if (!SendAll(frame, sizeof(frame))) return false;

  uint8_t z[32];
  if (!eph.Agree(ch + 5, z)) {
    Log("handshake %s: ECDH failed", peer_.c_str());
    return false;
  }
  {
    crypto::Sha256Stream h;
    h.Update(ch, kClientHello);
    h.Update(sh, kServerHello);
    h.Final(th_);
  }
  static const char kInfo[] = "desklink-v1-keys";
  const size_t infoLen = sizeof(kInfo) - 1;
  uint8_t prk[32], t1[32], t2[32];
  crypto::HmacSha256(th_, 32, z, 32, prk);
  uint8_t buf[32 + 16 + 1];
  memcpy(buf, kInfo, infoLen);
  buf[infoLen] = 0x01;
  crypto::HmacSha256(prk, 32, buf, infoLen + 1, t1);
  memcpy(buf, t1, 32);
  memcpy(buf + 32, kInfo, infoLen);
  buf[32 + infoLen] = 0x02;
  crypto::HmacSha256(prk, 32, buf, 32 + infoLen + 1, t2);
  bool ok = rx_.Init(t1) && tx_.Init(t2);
  SecureZeroMemory(z, sizeof(z));
  SecureZeroMemory(prk, sizeof(prk));
  SecureZeroMemory(t1, sizeof(t1));
  SecureZeroMemory(t2, sizeof(t2));
  return ok;
}

bool SecureChannel::Send(const uint8_t* p, size_t n) {
  std::lock_guard<std::mutex> lk(sendMu_);
  if (closed_) return false;
  uint32_t len = (uint32_t)(n + 16);
  txBuf_.resize(4 + (size_t)len);
  Put32(txBuf_.data(), len);
  if (!tx_.Seal(txSeq_++, txBuf_.data(), p, n, txBuf_.data() + 4)) return false;
  return SendAll(txBuf_.data(), txBuf_.size());
}

bool SecureChannel::Recv(Bytes& plain) {
  uint8_t lenb[4];
  if (!RecvAll(lenb, 4)) return false;
  uint32_t len = Get32(lenb);
  if (len < 17 || len > kMaxRecord) {
    Log("channel %s: bad record length %u", peer_.c_str(), len);
    return false;
  }
  rxBuf_.resize(len);
  if (!RecvAll(rxBuf_.data(), len)) return false;
  plain.resize(len - 16);
  if (!rx_.Open(rxSeq_++, lenb, rxBuf_.data(), len, plain.data())) {
    Log("channel %s: decryption failed", peer_.c_str());
    return false;
  }
  return true;
}
