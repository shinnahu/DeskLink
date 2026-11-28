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

// A file of the phone's backup (FILE_BEGIN of kind fkind::BACKUP).
struct BackupFile {
  std::string folder;       // the phone's folder, '/' between its parts ("DCIM/Camera")
  uint64_t modifiedMs = 0;  // when the phone last changed it (unix ms); 0: not known
  uint64_t offset = 0;      // where its data starts: what the PC already had of it (BACKUP_STATE)
};

// One file of a BACKUP_CHECK.
struct BackupEntry {
  std::string folder, name;
  uint64_t size = 0;
};

// Receives files from the phone (reader thread of the session): plain files into Downloads\DeskLink, backup files into
// Pictures\DeskLink backup\<phone>\<the phone's folders>, with the phone's dates. A backup file's part stays (hidden,
// next to where the file goes) when the connection drops, and the next backup goes on from there.
class FileReceiver {
 public:
  FileReceiver(Session* s, std::string deviceName) : s_(s), device_(std::move(deviceName)) {}
  ~FileReceiver();
  void OnBegin(uint32_t id, uint64_t size, const std::string& name, const std::string& mime,
               const BackupFile* backup = nullptr);
  void OnData(uint32_t id, const uint8_t* p, size_t n);
  void OnEnd(uint32_t id);
  void OnCancel(uint32_t id);
  void CancelAll();
  // What the PC has of each: its size (here already), the length of a part to go on from, or 0.
  std::vector<uint64_t> Check(const std::vector<BackupEntry>& entries);
  // The phone's backup is over: one notice for all of it (files: how many came this time).
  void BackupEnded(uint32_t files, uint64_t bytes, uint32_t failed);

 private:
  struct In {
    HANDLE h = INVALID_HANDLE_VALUE;
    std::wstring dir, tmp, name;
    uint64_t size = 0, got = 0, lastAck = 0;
    bool backup = false;      // a backup file: its part stays when the connection drops
    uint64_t modifiedMs = 0;  // (backup) the phone's date, given to the file
  };
  void Fail(uint32_t id, const std::string& why);
  Session* s_;
  std::string device_;
  std::map<uint32_t, In> in_;
};

// Where a phone's backup goes on this PC (tests: under --data): <Pictures>\DeskLink backup\<phone>; empty if unknown.
// Must be called in the user's context (UserContext).
std::wstring BackupFolderFor(HANDLE token, const std::string& deviceName);

std::wstring ReceivedFilesDir();
void SendFilesToPhone(std::shared_ptr<Session> s, std::vector<std::wstring> paths);
// Sends data to the phone as a file of that kind (fkind::*), on this thread: the phone's FILE_DONE status, or -1.
int SendBytesToPhone(Session& s, const std::string& name, const std::string& mime, uint8_t kind, const Bytes& data);
void PickAndSendFiles(std::shared_ptr<Session> s, bool replyNoneIfCancelled);
int RunPickFilesHelper();
