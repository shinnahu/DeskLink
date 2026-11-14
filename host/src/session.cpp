#include "session.h"

#include <algorithm>
#include <chrono>

#include "app.h"
#include "protocol.h"
#include "screenshot.h"
#include "ui.h"

static constexpr size_t kBulkQueueCap = 2 * 1024 * 1024;
static constexpr size_t kAudioQueueCap = 50;  // AAC frames: about a second
static constexpr int kPhoneLogPerMinute = 30;  // CLIENT_LOG lines written to the log; more are dropped
static constexpr size_t kPhoneLogMax = 400;    // bytes of one line

Session::Session(std::unique_ptr<SecureChannel> ch, PairedDevice dev) : ch_(std::move(ch)), dev_(std::move(dev)) {
  lastRecv_ = NowMs();
  streamer_ = std::make_unique<Streamer>(this);
  audio_ = std::make_unique<AudioStreamer>([this](Bytes&& m) { SendAudio(std::move(m)); });
  files_ = std::make_unique<FileReceiver>(this);
}

Session::~Session() { Log("session %s: destroyed", dev_.name.c_str()); }

void Session::Start() {
  // Liveness: the phone pings every second. Silence for 10 s ends the session.
  ch_->SetRecvTimeout(10000);
  DWORD sndTimeout = 15000;
  setsockopt(ch_->sock(), SOL_SOCKET, SO_SNDTIMEO, (const char*)&sndTimeout, sizeof(sndTimeout));
  auto self = shared_from_this();
  std::thread([self] { self->WriterLoop(); }).detach();
  std::thread([self] { self->ReaderLoop(); }).detach();
  Log("session %s: started from %s", dev_.name.c_str(), ch_->peer().c_str());
}

void Session::Stop(uint8_t byeReason, const std::string& msgText) {
  if (stopping_.exchange(true)) return;
  Log("session %s: stopping (reason %u: %s)", dev_.name.c_str(), byeReason, msgText.c_str());
  {
    std::lock_guard<std::mutex> lk(qmu_);
    Writer w(msg::BYE);
    w.u8(byeReason).str(msgText);
    hi_.push_back(std::move(w.b));
    au_.clear();
    lo_.clear();
    loBytes_ = 0;
  }
  qcv_.notify_all();
  outCv_.notify_all();
}

void Session::SendControl(Bytes&& m) {
  {
    std::lock_guard<std::mutex> lk(qmu_);
    if (stopping_) return;
    hi_.push_back(std::move(m));
  }
  qcv_.notify_one();
}

void Session::SendStream(Bytes&& m) { SendControl(std::move(m)); }

void Session::SendAudio(Bytes&& m) {
  {
    std::lock_guard<std::mutex> lk(qmu_);
    if (stopping_) return;
    // AUDIO_FORMAT always goes; when the link can't keep up, the oldest frames are dropped rather than played late
    while (au_.size() >= kAudioQueueCap) {
      auto it = std::find_if(au_.begin(), au_.end(), [](const Bytes& b) { return !b.empty() && b[0] == msg::AUDIO_DATA; });
      if (it == au_.end()) break;
      au_.erase(it);
    }
    au_.push_back(std::move(m));
  }
  qcv_.notify_one();
}

bool Session::SendBulk(Bytes&& m) {
  std::unique_lock<std::mutex> lk(qmu_);
  qcv_.wait(lk, [&] { return stopping_ || loBytes_ < kBulkQueueCap; });
  if (stopping_) return false;
  loBytes_ += m.size();
  lo_.push_back(std::move(m));
  lk.unlock();
  qcv_.notify_all();
  return true;
}

void Session::SendNotice(uint8_t level, const std::string& text) {
  Writer w(msg::NOTICE);
  w.u8(level).str(text);
  SendControl(std::move(w.b));
}

void Session::SendClipboard(const std::string& utf8) {
  if (!clipboardSync_ || utf8.empty() || utf8.size() > 1024 * 1024) return;
  {
    std::lock_guard<std::mutex> lk(clipMu_);
    if (utf8 == lastClipSent_) return;
    lastClipSent_ = utf8;
  }
  Writer w(msg::CLIPBOARD);
  w.raw(utf8.data(), utf8.size());
  SendControl(std::move(w.b));
}

bool Session::IsClientCursorPos(int x, int y) { return g_app->input.IsRecentClientPos(x, y); }

void Session::SetMonitorRect(const RECT& r) { g_app->input.SetMonitorRect(r); }

// ---------------------------------------------------------------- outgoing files bookkeeping
uint32_t Session::NewOutgoingId() {
  std::lock_guard<std::mutex> lk(outMu_);
  uint32_t id = nextOutId_;
  nextOutId_ += 2;  // host ids are even
  return id;
}

void Session::RegisterOutgoing(uint32_t id) {
  std::lock_guard<std::mutex> lk(outMu_);
  out_[id] = Out{};
}

bool Session::WaitForWindow(uint32_t id, uint64_t sent, uint64_t window) {
  std::unique_lock<std::mutex> lk(outMu_);
  return outCv_.wait_for(lk, std::chrono::seconds(60), [&] {
    auto it = out_.find(id);
    return stopping_ || it == out_.end() || it->second.cancelled || sent < it->second.acked + window;
  }) && !stopping_ && out_.count(id) && !out_[id].cancelled;
}

int Session::WaitForDone(uint32_t id, int timeoutMs) {
  std::unique_lock<std::mutex> lk(outMu_);
  outCv_.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] {
    auto it = out_.find(id);
    return stopping_ || it == out_.end() || it->second.done >= 0;
  });
  auto it = out_.find(id);
  return it == out_.end() ? -1 : it->second.done;
}

bool Session::OutgoingCancelled(uint32_t id) {
  std::lock_guard<std::mutex> lk(outMu_);
  auto it = out_.find(id);
  return it == out_.end() || it->second.cancelled;
}

void Session::ForgetOutgoing(uint32_t id) {
  std::lock_guard<std::mutex> lk(outMu_);
  out_.erase(id);
}

// ---------------------------------------------------------------- threads
void Session::WriterLoop() {
  uint64_t lastPing = NowMs();
  for (;;) {
    Bytes m;
    bool exitNow = false;
    {
      std::unique_lock<std::mutex> lk(qmu_);
      qcv_.wait_for(lk, std::chrono::milliseconds(250),
                    [&] { return !au_.empty() || !hi_.empty() || !lo_.empty() || stopping_.load(); });
      if (!au_.empty()) {  // sound first: a few hundred bytes each, and late sound is worse than late video
        m = std::move(au_.front());
        au_.pop_front();
      } else if (!hi_.empty()) {
        m = std::move(hi_.front());
        hi_.pop_front();
      } else if (!lo_.empty()) {
        m = std::move(lo_.front());
        lo_.pop_front();
        loBytes_ -= m.size();
        lk.unlock();
        qcv_.notify_all();
      } else if (stopping_) {
        exitNow = true;
      }
    }
    if (exitNow) break;
    if (!m.empty() && !ch_->Send(m)) {
      Log("session %s: send failed", dev_.name.c_str());
      break;
    }
    uint64_t now = NowMs();
    if (now - lastPing >= 2000 && !stopping_) {
      lastPing = now;
      Writer p(msg::PING);
      p.u64(now);
      if (!ch_->Send(p.b)) break;
    }
  }
  writerDone_ = true;
  ch_->Shutdown();  // wakes the reader
}

void Session::ReaderLoop() {
  Bytes m;
  while (ch_->Recv(m)) {
    lastRecv_ = NowMs();
    if (!m.empty()) Handle(m);
  }
  Stop(bye::TIMEOUT, "Connection lost");
  Cleanup();
}

void Session::Cleanup() {
  audio_->Stop();
  streamer_->Stop();
  InputEvent rel;
  rel.type = InputEvent::ReleaseAll;
  g_app->input.Post(std::move(rel));
  files_->CancelAll();
  outCv_.notify_all();
  // give the writer a moment to flush BYE
  for (int i = 0; i < 40 && !writerDone_; i++) Sleep(25);
  ch_->Shutdown();
  g_app->cfg.TouchDevice(dev_.id);
  g_app->OnSessionEnded(this);
  Log("session %s: ended", dev_.name.c_str());
}

void Session::Handle(const Bytes& m) {
  Reader r(m.data() + 1, m.size() - 1);
  switch (m[0]) {
    case msg::STREAM_START: {
      StreamParams p;
      uint8_t mon = r.u8();
      p.monitor = mon == kMonitorPrimary ? -1 : mon == kMonitorAll ? kAllScreens : mon;
      p.maxW = r.u16();
      p.maxH = r.u16();
      p.maxFps = r.u8();
      p.kbps = (int)r.u32();
      uint8_t flags = r.u8();
      if (!r.ok) break;
      if (r.n - r.pos >= 4) {  // newer phones: the largest picture their decoder takes (all screens stay within it)
        p.decW = r.u16();
        p.decH = r.u16();
      }
      if (p.maxW == 0) p.maxW = 1920;
      if (p.maxH == 0) p.maxH = 1200;
      if (p.maxFps == 0) p.maxFps = 60;
      clipboardSync_ = (flags & sflag::CLIPBOARD) != 0;
      audioWanted_ = (flags & sflag::AUDIO) != 0;
      streamPaused_ = false;
      Log("session %s: STREAM_START monitor=%d max=%dx%d fps=%d kbps=%d clip=%d audio=%d decoder=%dx%d",
          dev_.name.c_str(), p.monitor, p.maxW, p.maxH, p.maxFps, p.kbps, (int)clipboardSync_, (int)audioWanted_,
          p.decW, p.decH);
      streamer_->Start(p);
      if (audioWanted_) audio_->Start();
      else audio_->Stop();
      if (clipboardSync_) ui::RequestClipboardPush();
      break;
    }
    case msg::STREAM_PAUSE:
      streamPaused_ = true;
      streamer_->Pause();
      audio_->Stop();
      break;
    case msg::STREAM_RESUME:
      streamPaused_ = false;
      // A phone that connected while in the background may RESUME without ever sending START.
      if (!streamer_->running()) {
        Log("session %s: RESUME before START, starting with defaults", dev_.name.c_str());
        streamer_->Start(StreamParams{});
      } else {
        streamer_->Resume();
        if (audioWanted_) audio_->Start();
      }
      break;
    case msg::FRAME_ACK: streamer_->OnAck(r.u32()); break;
    case msg::KEYFRAME_REQUEST: streamer_->RequestKeyframe(); break;
    case msg::STREAM_CONFIG: {
      int w = r.u16(), h = r.u16(), fps = r.u8(), kbps = (int)r.u32();
      if (r.ok) streamer_->Configure(w, h, fps, kbps);
      break;
    }
    case msg::SET_OPTIONS: {
      uint8_t flags = r.u8();
      if (r.ok) {
        bool was = clipboardSync_;
        clipboardSync_ = (flags & sflag::CLIPBOARD) != 0;
        if (clipboardSync_ && !was) ui::RequestClipboardPush();
        audioWanted_ = (flags & sflag::AUDIO) != 0;
        if (audioWanted_ && streamer_->running() && !streamPaused_) audio_->Start();
        else if (!audioWanted_) audio_->Stop();
      }
      break;
    }
    case msg::SELECT_MONITOR: {
      uint8_t idx = r.u8();
      if (r.ok) streamer_->SelectMonitor(idx == kMonitorPrimary ? -1 : idx == kMonitorAll ? kAllScreens : idx);
      break;
    }
    case msg::SCREENSHOT: {
      // taken on the capture thread, then made into a PNG and sent like a file on a thread of its own
      std::weak_ptr<Session> weak = weak_from_this();
      streamer_->RequestShot([weak](Shot&& shot) {
        if (auto s = weak.lock()) SendShot(s, std::move(shot));
      });
      break;
    }
    case msg::MOUSE_MOVE: {
      InputEvent e;
      e.type = InputEvent::Move;
      e.x = r.i32();
      e.y = r.i32();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::MOUSE_REL: {
      InputEvent e;
      e.type = InputEvent::RelMove;
      e.x = r.i16();
      e.y = r.i16();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::MOUSE_BUTTON: {
      InputEvent e;
      e.type = InputEvent::Button;
      e.button = r.u8();
      e.down = r.u8();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::MOUSE_WHEEL: {
      InputEvent e;
      e.type = InputEvent::Wheel;
      e.dy = r.i16();
      e.dx = r.i16();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::KEY: {
      InputEvent e;
      e.type = InputEvent::Key;
      e.vk = r.u16();
      e.down = r.u8();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::TEXT: {
      size_t n = 0;
      const uint8_t* p = r.rest(n);
      InputEvent e;
      e.type = InputEvent::Text;
      e.text = U2W(std::string((const char*)p, n));
      if (!e.text.empty()) g_app->input.Post(std::move(e));
      break;
    }
    case msg::RELEASE_ALL: {
      InputEvent e;
      e.type = InputEvent::ReleaseAll;
      g_app->input.Post(std::move(e));
      break;
    }
    case msg::ACTION: {
      InputEvent e;
      e.type = InputEvent::Action;
      e.action = r.u8();
      if (r.ok) g_app->input.Post(std::move(e));
      break;
    }
    case msg::CLIPBOARD: {
      size_t n = 0;
      const uint8_t* p = r.rest(n);
      if (clipboardSync_ && n > 0 && n <= 1024 * 1024) {
        std::string text((const char*)p, n);
        {
          std::lock_guard<std::mutex> lk(clipMu_);
          lastClipSent_ = text;  // don't echo it back
        }
        ui::SetClipboardText(text);
      }
      break;
    }
    case msg::FILE_START: {
      uint32_t id = r.u32();
      uint64_t size = r.u64();
      std::string name = r.str(), mime = r.str();
      if (r.ok) files_->OnBegin(id, size, name, mime);
      break;
    }
    case msg::FILE_DATA: {
      uint32_t id = r.u32();
      size_t n = 0;
      const uint8_t* p = r.rest(n);
      if (r.ok) files_->OnData(id, p, n);
      break;
    }
    case msg::FILE_FINISH: files_->OnEnd(r.u32()); break;
    case msg::FILE_CANCEL: {
      uint32_t id = r.u32();
      if (id % 2 == 1) {
        files_->OnCancel(id);
      } else {
        std::lock_guard<std::mutex> lk(outMu_);
        auto it = out_.find(id);
        if (it != out_.end()) it->second.cancelled = true;
        outCv_.notify_all();
      }
      break;
    }
    case msg::FILE_PROGRESS: {
      uint32_t id = r.u32();
      uint64_t got = r.u64();
      std::lock_guard<std::mutex> lk(outMu_);
      auto it = out_.find(id);
      if (it != out_.end() && got > it->second.acked) it->second.acked = got;
      outCv_.notify_all();
      break;
    }
    case msg::FILE_DONE: {
      uint32_t id = r.u32();
      uint8_t st = r.u8();
      std::lock_guard<std::mutex> lk(outMu_);
      auto it = out_.find(id);
      if (it != out_.end()) it->second.done = st;
      outCv_.notify_all();
      break;
    }
    case msg::FILE_REQUEST:
      PickAndSendFiles(shared_from_this(), true);
      break;
    case msg::PING: {
      Writer w(msg::PONG);
      w.u64(r.u64());
      SendControl(std::move(w.b));
      break;
    }
    case msg::PONG: {
      uint64_t t = r.u64();
      if (r.ok && t <= NowMs()) rttMs_ = (int)(NowMs() - t);
      break;
    }
    case msg::CLIENT_LOG: {
      // what the phone's side did (its video decoder): written here, where it can be read after the fact
      size_t n = 0;
      const uint8_t* p = r.rest(n);
      uint64_t now = NowMs();
      if (now - phoneLogMinute_ >= 60000) {
        if (phoneLogLines_ > kPhoneLogPerMinute)
          Log("session %s: phone: (%d more lines that minute left out)", dev_.name.c_str(),
              phoneLogLines_ - kPhoneLogPerMinute);
        phoneLogMinute_ = now;
        phoneLogLines_ = 0;
      }
      if (n == 0 || ++phoneLogLines_ > kPhoneLogPerMinute) break;
      if (n > kPhoneLogMax) {
        n = kPhoneLogMax;
        while (n > 0 && (p[n] & 0xC0) == 0x80) n--;  // not in the middle of a character
      }
      std::string line((const char*)p, n);
      for (char& ch : line)
        if ((unsigned char)ch < 0x20 || ch == 0x7F) ch = ' ';
      Log("session %s: phone: %s", dev_.name.c_str(), line.c_str());
      break;
    }
    case msg::BYE:
      Stop(bye::NORMAL, "Bye");
      break;
    default:
      break;
  }
}
