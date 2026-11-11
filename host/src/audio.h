#pragma once
#include "common.h"

// The PC's sound for the phone: what its speakers play (WASAPI loopback of the default output), as AAC-LC stereo
// made by Windows' own Media Foundation encoder at 128 kbps, handed to `send` as AUDIO_FORMAT / AUDIO_DATA messages
// (PROTOCOL.md). After a second of quiet nothing is sent until there is sound again. Without an output device (say
// the only speakers were a monitor's, and it's off) AUDIO_FORMAT says acodec::NONE and it looks again every 2 s;
// another default output is picked up within a second.
class AudioStreamer {
 public:
  using Send = std::function<void(Bytes&&)>;
  explicit AudioStreamer(Send send) : send_(std::move(send)) {}
  ~AudioStreamer() { Stop(); }

  void Start();  // starts its thread (already running: nothing)
  void Stop();   // stops it and waits for it
  bool running();

 private:
  void Run();

  Send send_;
  std::mutex mu_;
  std::thread thread_;
  HANDLE stop_ = nullptr;
};

namespace audio {
// The AAC-LC AudioSpecificConfig (ISO 14496-3) for a rate and channel count; empty for a rate AAC has no index for.
Bytes AacConfig(int rate, int channels);
}  // namespace audio
