#pragma once
#include <map>

#include "common.h"

class Session;

// Runs a block of file-system work in the signed-in user's security context when the agent
// runs as SYSTEM (service mode); a no-op in normal user mode.
class UserContext {
 public:
  UserContext();
  ~UserContext();
  bool ok() const { return ok_; }
  HANDLE token() const { return token_; }

 private:
  HANDLE token_ = nullptr;
  bool impersonating_ = false;
  bool ok_ = true;
};

// Receives files from the phone into Downloads\DeskLink (reader thread of the session).
class FileReceiver {
 public:
  explicit FileReceiver(Session* s) : s_(s) {}
  ~FileReceiver();
  void OnBegin(uint32_t id, uint64_t size, const std::string& name, const std::string& mime);
  void OnData(uint32_t id, const uint8_t* p, size_t n);
  void OnEnd(uint32_t id);
  void OnCancel(uint32_t id);
  void CancelAll();

 private:
  struct In {
    HANDLE h = INVALID_HANDLE_VALUE;
    std::wstring dir, tmp, name;
    uint64_t size = 0, got = 0, lastAck = 0;
  };
  void Fail(uint32_t id, const std::string& why);
  Session* s_;
  std::map<uint32_t, In> in_;
};

std::wstring ReceivedFilesDir();
void SendFilesToPhone(std::shared_ptr<Session> s, std::vector<std::wstring> paths);
// Sends data to the phone as a file of that kind (fkind::*), on this thread: the phone's FILE_DONE status, or -1.
int SendBytesToPhone(Session& s, const std::string& name, const std::string& mime, uint8_t kind, const Bytes& data);
void PickAndSendFiles(std::shared_ptr<Session> s, bool replyNoneIfCancelled);
int RunPickFilesHelper();
