#pragma once
#include "common.h"

namespace h264 {

struct Nal {
  const uint8_t* p;  // first byte = NAL header
  size_t n;
  int type;
};

std::vector<Nal> SplitAnnexB(const uint8_t* p, size_t n);
bool ContainsIdr(const Bytes& au);

// Rewrites the SPS of an Annex-B access unit so that decoders can output every frame
// immediately (VUI bitstream_restriction: max_num_reorder_frames = 0) and so colours are
// signalled as BT.709 limited range. Caches the last rewrite.
class SpsFixer {
 public:
  void Process(Bytes& au);

 private:
  Bytes lastIn_, lastOut_;
  bool lastOk_ = false;
};

bool RewriteSps(const uint8_t* nal, size_t n, Bytes& out);

}  // namespace h264
