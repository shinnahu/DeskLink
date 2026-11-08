#include "crypto.h"

#include <wincrypt.h>

#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")

#ifndef NT_SUCCESS
#define NT_SUCCESS(s) (((NTSTATUS)(s)) >= 0)
#endif

namespace crypto {

static BCRYPT_ALG_HANDLE g_sha = nullptr, g_hmac = nullptr, g_ecdh = nullptr, g_ecdsa = nullptr, g_aes = nullptr;

bool Init() {
  if (g_sha) return true;
  if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&g_sha, BCRYPT_SHA256_ALGORITHM, nullptr, 0))) return false;
  if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&g_hmac, BCRYPT_SHA256_ALGORITHM, nullptr, BCRYPT_ALG_HANDLE_HMAC_FLAG)))
    return false;
  if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&g_ecdh, BCRYPT_ECDH_P256_ALGORITHM, nullptr, 0))) return false;
  if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&g_ecdsa, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0))) return false;
  if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&g_aes, BCRYPT_AES_ALGORITHM, nullptr, 0))) return false;
  if (!NT_SUCCESS(BCryptSetProperty(g_aes, BCRYPT_CHAINING_MODE, (PUCHAR)BCRYPT_CHAIN_MODE_GCM,
                                    sizeof(BCRYPT_CHAIN_MODE_GCM), 0)))
    return false;
  return true;
}

void Random(void* p, size_t n) { BCryptGenRandom(nullptr, (PUCHAR)p, (ULONG)n, BCRYPT_USE_SYSTEM_PREFERRED_RNG); }

void Sha256(const void* p, size_t n, uint8_t out[32]) {
  Sha256Stream s;
  s.Update(p, n);
  s.Final(out);
}

void HmacSha256(const uint8_t* key, size_t keyLen, const void* data, size_t n, uint8_t out[32]) {
  BCRYPT_HASH_HANDLE h = nullptr;
  BCryptCreateHash(g_hmac, &h, nullptr, 0, (PUCHAR)key, (ULONG)keyLen, 0);
  BCryptHashData(h, (PUCHAR)data, (ULONG)n, 0);
  BCryptFinishHash(h, out, 32, 0);
  BCryptDestroyHash(h);
}

Sha256Stream::Sha256Stream() { BCryptCreateHash(g_sha, &h_, nullptr, 0, nullptr, 0, 0); }
Sha256Stream::~Sha256Stream() {
  if (h_) BCryptDestroyHash(h_);
}
void Sha256Stream::Update(const void* p, size_t n) {
  if (n) BCryptHashData(h_, (PUCHAR)p, (ULONG)n, 0);
}
void Sha256Stream::Final(uint8_t out[32]) { BCryptFinishHash(h_, out, 32, 0); }

// ---------------------------------------------------------------- EcKey
EcKey::~EcKey() {
  if (h_) BCryptDestroyKey(h_);
}

EcKey& EcKey::operator=(EcKey&& o) noexcept {
  if (this != &o) {
    if (h_) BCryptDestroyKey(h_);
    h_ = o.h_;
    o.h_ = nullptr;
  }
  return *this;
}

static bool Generate(BCRYPT_ALG_HANDLE alg, BCRYPT_KEY_HANDLE& out) {
  BCRYPT_KEY_HANDLE h = nullptr;
  if (!NT_SUCCESS(BCryptGenerateKeyPair(alg, &h, 256, 0))) return false;
  if (!NT_SUCCESS(BCryptFinalizeKeyPair(h, 0))) {
    BCryptDestroyKey(h);
    return false;
  }
  out = h;
  return true;
}

bool EcKey::GenerateEcdh(EcKey& out) {
  EcKey k;
  if (!Generate(g_ecdh, k.h_)) return false;
  out = std::move(k);
  return true;
}

bool EcKey::GenerateEcdsa(EcKey& out) {
  EcKey k;
  if (!Generate(g_ecdsa, k.h_)) return false;
  out = std::move(k);
  return true;
}

bool EcKey::ImportEcdsaPrivate(const Bytes& blob, EcKey& out) {
  EcKey k;
  if (!NT_SUCCESS(BCryptImportKeyPair(g_ecdsa, nullptr, BCRYPT_ECCPRIVATE_BLOB, &k.h_, (PUCHAR)blob.data(),
                                      (ULONG)blob.size(), 0)))
    return false;
  out = std::move(k);
  return true;
}

bool EcKey::ExportPublic(uint8_t out65[65]) const {
  uint8_t buf[sizeof(BCRYPT_ECCKEY_BLOB) + 64];
  ULONG cb = 0;
  if (!NT_SUCCESS(BCryptExportKey(h_, nullptr, BCRYPT_ECCPUBLIC_BLOB, buf, sizeof(buf), &cb, 0))) return false;
  auto* hdr = (BCRYPT_ECCKEY_BLOB*)buf;
  if (hdr->cbKey != 32 || cb < sizeof(BCRYPT_ECCKEY_BLOB) + 64) return false;
  out65[0] = 0x04;
  memcpy(out65 + 1, buf + sizeof(BCRYPT_ECCKEY_BLOB), 64);
  return true;
}

bool EcKey::ExportPrivate(Bytes& blob) const {
  ULONG cb = 0;
  if (!NT_SUCCESS(BCryptExportKey(h_, nullptr, BCRYPT_ECCPRIVATE_BLOB, nullptr, 0, &cb, 0))) return false;
  blob.resize(cb);
  if (!NT_SUCCESS(BCryptExportKey(h_, nullptr, BCRYPT_ECCPRIVATE_BLOB, blob.data(), cb, &cb, 0))) return false;
  blob.resize(cb);
  return true;
}

bool EcKey::SignSha256(const void* msg, size_t n, uint8_t sig64[64]) const {
  uint8_t hash[32];
  Sha256(msg, n, hash);
  ULONG cb = 0;
  if (!NT_SUCCESS(BCryptSignHash(h_, nullptr, hash, 32, sig64, 64, &cb, 0))) return false;
  return cb == 64;
}

bool EcKey::Agree(const uint8_t peer65[65], uint8_t z32[32]) const {
  if (peer65[0] != 0x04) return false;
  uint8_t blob[sizeof(BCRYPT_ECCKEY_BLOB) + 64];
  auto* hdr = (BCRYPT_ECCKEY_BLOB*)blob;
  hdr->dwMagic = BCRYPT_ECDH_PUBLIC_P256_MAGIC;
  hdr->cbKey = 32;
  memcpy(blob + sizeof(BCRYPT_ECCKEY_BLOB), peer65 + 1, 64);
  BCRYPT_KEY_HANDLE peer = nullptr;
  if (!NT_SUCCESS(BCryptImportKeyPair(g_ecdh, nullptr, BCRYPT_ECCPUBLIC_BLOB, &peer, blob, sizeof(blob), 0))) return false;
  BCRYPT_SECRET_HANDLE sec = nullptr;
  bool ok = false;
  if (NT_SUCCESS(BCryptSecretAgreement(h_, peer, &sec, 0))) {
    uint8_t raw[32];
    ULONG cb = 0;
    if (NT_SUCCESS(BCryptDeriveKey(sec, BCRYPT_KDF_RAW_SECRET, nullptr, raw, sizeof(raw), &cb, 0)) && cb == 32) {
      for (int i = 0; i < 32; i++) z32[i] = raw[31 - i];  // CNG returns little-endian
      ok = true;
    }
    SecureZeroMemory(raw, sizeof(raw));
    BCryptDestroySecret(sec);
  }
  BCryptDestroyKey(peer);
  return ok;
}

// ---------------------------------------------------------------- AES-GCM
AesGcm::~AesGcm() {
  if (h_) BCryptDestroyKey(h_);
}

bool AesGcm::Init(const uint8_t key[32]) {
  if (h_) {
    BCryptDestroyKey(h_);
    h_ = nullptr;
  }
  return NT_SUCCESS(BCryptGenerateSymmetricKey(g_aes, &h_, nullptr, 0, (PUCHAR)key, 32, 0));
}

static void MakeNonce(uint64_t seq, uint8_t nonce[12]) {
  memset(nonce, 0, 4);
  for (int i = 0; i < 8; i++) nonce[4 + i] = (uint8_t)(seq >> (56 - 8 * i));
}

bool AesGcm::Seal(uint64_t seq, const uint8_t aad[4], const uint8_t* in, size_t n, uint8_t* out) {
  uint8_t nonce[12];
  MakeNonce(seq, nonce);
  BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info;
  BCRYPT_INIT_AUTH_MODE_INFO(info);
  info.pbNonce = nonce;
  info.cbNonce = 12;
  info.pbAuthData = (PUCHAR)aad;
  info.cbAuthData = 4;
  info.pbTag = out + n;
  info.cbTag = 16;
  ULONG cb = 0;
  return NT_SUCCESS(BCryptEncrypt(h_, (PUCHAR)in, (ULONG)n, &info, nullptr, 0, out, (ULONG)n, &cb, 0)) && cb == n;
}

bool AesGcm::Open(uint64_t seq, const uint8_t aad[4], const uint8_t* in, size_t n, uint8_t* out) {
  if (n < 16) return false;
  size_t m = n - 16;
  uint8_t nonce[12];
  MakeNonce(seq, nonce);
  BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info;
  BCRYPT_INIT_AUTH_MODE_INFO(info);
  info.pbNonce = nonce;
  info.cbNonce = 12;
  info.pbAuthData = (PUCHAR)aad;
  info.cbAuthData = 4;
  info.pbTag = (PUCHAR)(in + m);
  info.cbTag = 16;
  ULONG cb = 0;
  return NT_SUCCESS(BCryptDecrypt(h_, (PUCHAR)in, (ULONG)m, &info, nullptr, 0, out, (ULONG)m, &cb, 0)) && cb == m;
}

// ---------------------------------------------------------------- DPAPI
bool Protect(const Bytes& in, Bytes& out, bool machineScope) {
  DATA_BLOB a{(DWORD)in.size(), (BYTE*)in.data()}, b{};
  DWORD flags = CRYPTPROTECT_UI_FORBIDDEN | (machineScope ? CRYPTPROTECT_LOCAL_MACHINE : 0);
  if (!CryptProtectData(&a, L"DeskLink", nullptr, nullptr, nullptr, flags, &b)) return false;
  out.assign(b.pbData, b.pbData + b.cbData);
  LocalFree(b.pbData);
  return true;
}

bool Unprotect(const Bytes& in, Bytes& out) {
  DATA_BLOB a{(DWORD)in.size(), (BYTE*)in.data()}, b{};
  if (!CryptUnprotectData(&a, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &b)) return false;
  out.assign(b.pbData, b.pbData + b.cbData);
  SecureZeroMemory(b.pbData, b.cbData);
  LocalFree(b.pbData);
  return true;
}

}  // namespace crypto
