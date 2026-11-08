#pragma once
#include "common.h"

#include <bcrypt.h>

namespace crypto {

bool Init();
void Random(void* p, size_t n);
void Sha256(const void* p, size_t n, uint8_t out[32]);
void HmacSha256(const uint8_t* key, size_t keyLen, const void* data, size_t n, uint8_t out[32]);

class Sha256Stream {
 public:
  Sha256Stream();
  ~Sha256Stream();
  Sha256Stream(const Sha256Stream&) = delete;
  Sha256Stream& operator=(const Sha256Stream&) = delete;
  void Update(const void* p, size_t n);
  void Final(uint8_t out[32]);

 private:
  BCRYPT_HASH_HANDLE h_ = nullptr;
};

// P-256 key: either an ECDH key (ephemeral) or an ECDSA key (host identity).
class EcKey {
 public:
  EcKey() = default;
  ~EcKey();
  EcKey(EcKey&& o) noexcept { *this = std::move(o); }
  EcKey& operator=(EcKey&& o) noexcept;
  EcKey(const EcKey&) = delete;
  EcKey& operator=(const EcKey&) = delete;

  static bool GenerateEcdh(EcKey& out);
  static bool GenerateEcdsa(EcKey& out);
  static bool ImportEcdsaPrivate(const Bytes& blob, EcKey& out);

  bool ExportPublic(uint8_t out65[65]) const;  // 0x04 || X || Y
  bool ExportPrivate(Bytes& blob) const;       // CNG ECCPRIVATE blob
  bool SignSha256(const void* msg, size_t n, uint8_t sig64[64]) const;  // raw r||s
  bool Agree(const uint8_t peer65[65], uint8_t z32[32]) const;          // big-endian X
  bool valid() const { return h_ != nullptr; }

 private:
  BCRYPT_KEY_HANDLE h_ = nullptr;
};

class AesGcm {
 public:
  AesGcm() = default;
  ~AesGcm();
  AesGcm(const AesGcm&) = delete;
  AesGcm& operator=(const AesGcm&) = delete;
  bool Init(const uint8_t key[32]);
  // out must hold n + 16 bytes (ciphertext || tag)
  bool Seal(uint64_t seq, const uint8_t aad[4], const uint8_t* in, size_t n, uint8_t* out);
  // n includes the tag; out must hold n - 16 bytes
  bool Open(uint64_t seq, const uint8_t aad[4], const uint8_t* in, size_t n, uint8_t* out);

 private:
  BCRYPT_KEY_HANDLE h_ = nullptr;
};

bool Protect(const Bytes& in, Bytes& out, bool machineScope);
bool Unprotect(const Bytes& in, Bytes& out);

}  // namespace crypto
