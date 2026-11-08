#pragma once
#include "common.h"

// Helpers to follow the *input* desktop (Default <-> Winlogon/secure desktop).
// Only a SYSTEM process can attach to the secure desktop; in user mode these calls
// simply report that the secure desktop is active.
class DesktopFollower {
 public:
  ~DesktopFollower();
  // Attaches the calling thread to the current input desktop if it changed.
  // Returns true when the thread's desktop changed. Must be called on a thread
  // that owns no windows or hooks.
  bool Sync();
  bool secure() const { return secure_; }
  const std::wstring& name() const { return name_; }

 private:
  HDESK cur_ = nullptr;
  std::wstring name_;
  bool secure_ = false;
};

bool IsInputDesktopSecure();

struct MonitorDesc {
  int index;
  RECT rect;  // virtual-desktop physical pixels
  bool primary;
  std::string name;
};
std::vector<MonitorDesc> ListMonitors();
