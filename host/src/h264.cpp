#include "h264.h"

#include <algorithm>

namespace h264 {

std::vector<Nal> SplitAnnexB(const uint8_t* p, size_t n) {
  std::vector<size_t> starts;
  size_t i = 0;
  while (i + 3 <= n) {
    if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
      starts.push_back(i + 3);
      i += 3;
    } else {
      i++;
    }
  }
  std::vector<Nal> out;
  for (size_t k = 0; k < starts.size(); k++) {
    size_t s = starts[k];
    size_t e = k + 1 < starts.size() ? starts[k + 1] - 3 : n;
    while (e > s && p[e - 1] == 0) e--;  // trailing zero / 4-byte start code prefix
    if (e > s) out.push_back(Nal{p + s, e - s, p[s] & 0x1f});
  }
  return out;
}

bool ContainsIdr(const Bytes& au) {
  for (auto& nal : SplitAnnexB(au.data(), au.size()))
    if (nal.type == 5) return true;
  return false;
}

// ---------------------------------------------------------------- bit IO
namespace {

struct BitReader {
  const Bytes& b;
  size_t bit = 0;
  bool ok = true;
  explicit BitReader(const Bytes& v) : b(v) {}
  uint32_t u(int k) {
    uint32_t v = 0;
    for (int i = 0; i < k; i++) {
      if (bit >= b.size() * 8) {
        ok = false;
        return 0;
      }
      v = (v << 1) | ((b[bit >> 3] >> (7 - (bit & 7))) & 1);
      bit++;
    }
    return v;
  }
  uint32_t ue() {
    int zeros = 0;
    while (u(1) == 0) {
      if (!ok || ++zeros > 31) {
        ok = false;
        return 0;
      }
    }
    if (zeros == 0) return 0;
    return ((1u << zeros) - 1) + u(zeros);
  }
  int32_t se() {
    uint32_t k = ue();
    return (k & 1) ? (int32_t)((k + 1) / 2) : -(int32_t)(k / 2);
  }
};

struct BitWriter {
  Bytes b;
  int used = 0;  // bits used in the last byte (0 = need a new byte)
  void put(uint32_t bit) {
    if (used == 0) b.push_back(0);
    if (bit) b.back() |= (uint8_t)(0x80 >> used);
    used = (used + 1) & 7;
  }
  void u(int k, uint32_t v) {
    for (int i = k - 1; i >= 0; i--) put((v >> i) & 1);
  }
  void ue(uint32_t v) {
    uint64_t x = (uint64_t)v + 1;
    int len = 0;
    while ((x >> (len + 1)) != 0) len++;
    for (int i = 0; i < len; i++) put(0);
    for (int i = len; i >= 0; i--) put((uint32_t)(x >> i) & 1);
  }
  void trailing() {
    put(1);
    while (used != 0) put(0);
  }
};

void CopyBits(const Bytes& src, size_t from, size_t to, BitWriter& w) {
  for (size_t i = from; i < to; i++) w.put((src[i >> 3] >> (7 - (i & 7))) & 1);
}

Bytes ToRbsp(const uint8_t* p, size_t n) {
  Bytes r;
  r.reserve(n);
  int zeros = 0;
  for (size_t i = 0; i < n; i++) {
    if (zeros >= 2 && p[i] == 0x03) {
      zeros = 0;
      continue;
    }
    r.push_back(p[i]);
    zeros = p[i] == 0 ? zeros + 1 : 0;
  }
  return r;
}

Bytes FromRbsp(const Bytes& r) {
  Bytes o;
  o.reserve(r.size() + 8);
  int zeros = 0;
  for (uint8_t c : r) {
    if (zeros >= 2 && c <= 3) {
      o.push_back(0x03);
      zeros = 0;
    }
    o.push_back(c);
    zeros = c == 0 ? zeros + 1 : 0;
  }
  return o;
}

void SkipScalingList(BitReader& r, int size) {
  int last = 8, next = 8;
  for (int j = 0; j < size; j++) {
    if (next != 0) {
      int delta = r.se();
      next = (last + delta + 256) % 256;
    }
    last = next == 0 ? last : next;
  }
}

void SkipHrd(BitReader& r) {
  uint32_t cpbCnt = r.ue();
  r.u(4);
  r.u(4);
  for (uint32_t i = 0; i <= cpbCnt && r.ok; i++) {
    r.ue();
    r.ue();
    r.u(1);
  }
  r.u(5);
  r.u(5);
  r.u(5);
  r.u(5);
}

}  // namespace

bool RewriteSps(const uint8_t* nal, size_t n, Bytes& out) {
  if (n < 4 || (nal[0] & 0x1f) != 7) return false;
  Bytes rb = ToRbsp(nal + 1, n - 1);
  BitReader r(rb);
  uint32_t profile = r.u(8);
  r.u(8);
  r.u(8);
  r.ue();  // sps id
  if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 ||
      profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 ||
      profile == 135) {
    uint32_t chroma = r.ue();
    if (chroma == 3) r.u(1);
    r.ue();
    r.ue();
    r.u(1);
    if (r.u(1)) {
      int lists = chroma != 3 ? 8 : 12;
      for (int i = 0; i < lists && r.ok; i++)
        if (r.u(1)) SkipScalingList(r, i < 6 ? 16 : 64);
    }
  }
  r.ue();  // log2_max_frame_num_minus4
  uint32_t pocType = r.ue();
  if (pocType == 0) {
    r.ue();
  } else if (pocType == 1) {
    r.u(1);
    r.se();
    r.se();
    uint32_t cycle = r.ue();
    for (uint32_t i = 0; i < cycle && r.ok; i++) r.se();
  }
  uint32_t maxRefFrames = r.ue();
  r.u(1);
  r.ue();
  r.ue();
  if (!r.u(1)) r.u(1);  // frame_mbs_only / mb_adaptive
  r.u(1);               // direct_8x8
  if (r.u(1)) {
    r.ue();
    r.ue();
    r.ue();
    r.ue();
  }
  if (!r.ok) return false;
  size_t vuiFlagPos = r.bit;
  uint32_t vuiPresent = r.u(1);

  // VUI fields (defaults per spec when absent)
  uint32_t aspect = 0, aspectIdc = 0, sarW = 0, sarH = 0;
  uint32_t overscanInfo = 0, overscanAppr = 0;
  uint32_t videoFormat = 5;
  uint32_t chromaLoc = 0, chromaTop = 0, chromaBottom = 0;
  uint32_t timing = 0, numUnits = 0, timeScale = 0, fixedRate = 0;
  uint32_t nalHrd = 0, vclHrd = 0, lowDelay = 0;
  size_t nalHrdA = 0, nalHrdB = 0, vclHrdA = 0, vclHrdB = 0;
  uint32_t picStruct = 0;
  uint32_t mvOver = 1, maxBytesDenom = 2, maxBitsDenom = 1, log2MvH = 15, log2MvV = 15, maxDecBuf = 0;
  if (vuiPresent) {
    aspect = r.u(1);
    if (aspect) {
      aspectIdc = r.u(8);
      if (aspectIdc == 255) {
        sarW = r.u(16);
        sarH = r.u(16);
      }
    }
    overscanInfo = r.u(1);
    if (overscanInfo) overscanAppr = r.u(1);
    if (r.u(1)) {  // video_signal_type_present
      videoFormat = r.u(3);
      r.u(1);
      if (r.u(1)) {
        r.u(8);
        r.u(8);
        r.u(8);
      }
    }
    chromaLoc = r.u(1);
    if (chromaLoc) {
      chromaTop = r.ue();
      chromaBottom = r.ue();
    }
    timing = r.u(1);
    if (timing) {
      numUnits = r.u(32);
      timeScale = r.u(32);
      fixedRate = r.u(1);
    }
    nalHrd = r.u(1);
    if (nalHrd) {
      nalHrdA = r.bit;
      SkipHrd(r);
      nalHrdB = r.bit;
    }
    vclHrd = r.u(1);
    if (vclHrd) {
      vclHrdA = r.bit;
      SkipHrd(r);
      vclHrdB = r.bit;
    }
    if (nalHrd || vclHrd) lowDelay = r.u(1);
    picStruct = r.u(1);
    if (r.u(1)) {  // bitstream_restriction
      mvOver = r.u(1);
      maxBytesDenom = r.ue();
      maxBitsDenom = r.ue();
      log2MvH = r.ue();
      log2MvV = r.ue();
      r.ue();  // max_num_reorder_frames
      maxDecBuf = r.ue();
    }
    if (!r.ok) return false;
  }

  BitWriter w;
  CopyBits(rb, 0, vuiFlagPos, w);
  w.u(1, 1);  // vui_parameters_present_flag
  w.u(1, aspect);
  if (aspect) {
    w.u(8, aspectIdc);
    if (aspectIdc == 255) {
      w.u(16, sarW);
      w.u(16, sarH);
    }
  }
  w.u(1, overscanInfo);
  if (overscanInfo) w.u(1, overscanAppr);
  w.u(1, 1);  // video_signal_type_present
  w.u(3, videoFormat);
  w.u(1, 0);  // video_full_range_flag: limited
  w.u(1, 1);  // colour_description_present
  w.u(8, 1);  // BT.709 primaries
  w.u(8, 1);  // BT.709 transfer
  w.u(8, 1);  // BT.709 matrix
  w.u(1, chromaLoc);
  if (chromaLoc) {
    w.ue(chromaTop);
    w.ue(chromaBottom);
  }
  w.u(1, timing);
  if (timing) {
    w.u(32, numUnits);
    w.u(32, timeScale);
    w.u(1, fixedRate);
  }
  w.u(1, nalHrd);
  if (nalHrd) CopyBits(rb, nalHrdA, nalHrdB, w);
  w.u(1, vclHrd);
  if (vclHrd) CopyBits(rb, vclHrdA, vclHrdB, w);
  if (nalHrd || vclHrd) w.u(1, lowDelay);
  w.u(1, picStruct);
  w.u(1, 1);  // bitstream_restriction_flag
  w.u(1, mvOver);
  w.ue(maxBytesDenom);
  w.ue(maxBitsDenom);
  w.ue(log2MvH);
  w.ue(log2MvV);
  w.ue(0);  // max_num_reorder_frames
  (void)maxDecBuf;
  w.ue(std::max<uint32_t>(maxRefFrames, 1));  // max_dec_frame_buffering (must be >= max_num_ref_frames)
  w.trailing();

  out.clear();
  out.push_back(nal[0]);
  Bytes esc = FromRbsp(w.b);
  out.insert(out.end(), esc.begin(), esc.end());
  return true;
}

void SpsFixer::Process(Bytes& au) {
  auto nals = SplitAnnexB(au.data(), au.size());
  bool hasSps = false;
  for (auto& n : nals)
    if (n.type == 7) hasSps = true;
  if (!hasSps) return;
  Bytes rebuilt;
  rebuilt.reserve(au.size() + 16);
  static const uint8_t sc[4] = {0, 0, 0, 1};
  for (auto& n : nals) {
    rebuilt.insert(rebuilt.end(), sc, sc + 4);
    if (n.type == 7) {
      Bytes in(n.p, n.p + n.n);
      if (in != lastIn_) {
        lastIn_ = in;
        lastOk_ = RewriteSps(n.p, n.n, lastOut_);
        if (!lastOk_) Log("h264: SPS rewrite failed, sending original");
      }
      if (lastOk_) {
        rebuilt.insert(rebuilt.end(), lastOut_.begin(), lastOut_.end());
        continue;
      }
    }
    rebuilt.insert(rebuilt.end(), n.p, n.p + n.n);
  }
  au.swap(rebuilt);
}

}  // namespace h264
