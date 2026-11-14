#pragma once
#include <map>

#include "audio.h"
#include "capture.h"
#include "channel.h"
#include "common.h"
#include "config.h"
#include "files.h"

// One authenticated phone connection. Owns a reader thread (decrypt + dispatch) and a writer
// thread (three queues: sound first, then control/video, then file data; periodic PING).
class Session : public StreamSink, public std::enable_shared_from_this<Session> {
 public:
  Session(std::unique_ptr<SecureChannel> ch, PairedDevice dev);
  ~Session() override;

  SecureChannel& channel() { return *ch_; }
  const PairedDevice& device() const { return dev_; }
  bool alive() const { return !stopping_; }
  uint64_t lastRecvMs() const { return lastRecv_; }

  void Start();
  void Stop(uint8_t byeReason, const std::string& msg);

  void SendControl(Bytes&& m);
  bool SendBulk(Bytes&& m);  // blocks while the bulk queue is full; false once the session stops
  void SendAudio(Bytes&& m);  // goes out first; on a slow link the oldest is dropped (sound never lags behind)
  void SendNotice(uint8_t level, const std::string& text);
  void SendClipboard(const std::string& utf8);

  // host -> phone file transfers
  uint32_t NewOutgoingId();
  void RegisterOutgoing(uint32_t id);
  bool WaitForWindow(uint32_t id, uint64_t sent, uint64_t window);
  int WaitForDone(uint32_t id, int timeoutMs);  // -1 timeout/ended, else FILE_DONE status
  bool OutgoingCancelled(uint32_t id);
  void ForgetOutgoing(uint32_t id);

  // StreamSink
  void SendStream(Bytes&& msg) override;
  bool IsClientCursorPos(int x, int y) override;
  void SetMonitorRect(const RECT& r) override;

 private:
  void ReaderLoop();
  void WriterLoop();
  void Handle(const Bytes& m);
  void Cleanup();

  std::unique_ptr<SecureChannel> ch_;
  PairedDevice dev_;

  std::mutex qmu_;
  std::condition_variable qcv_;
  std::deque<Bytes> au_, hi_, lo_;
  size_t loBytes_ = 0;
  std::atomic<bool> stopping_{false};
  std::atomic<bool> writerDone_{false};
  std::atomic<uint64_t> lastRecv_{0};

  std::unique_ptr<Streamer> streamer_;
  std::unique_ptr<AudioStreamer> audio_;
  bool audioWanted_ = false, streamPaused_ = false;  // (reader thread only)
  uint64_t phoneLogMinute_ = 0;  // CLIENT_LOG lines written this minute, at most kPhoneLogPerMinute (reader thread)
  int phoneLogLines_ = 0;
  std::unique_ptr<FileReceiver> files_;
  std::atomic<bool> clipboardSync_{true};
  std::string lastClipSent_;
  std::mutex clipMu_;

  struct Out {
    uint64_t acked = 0;
    int done = -1;
    bool cancelled = false;
  };
  std::mutex outMu_;
  std::condition_variable outCv_;
  std::map<uint32_t, Out> out_;
  uint32_t nextOutId_ = 2;
  int rttMs_ = 0;
};
