#pragma once
#include "common.h"

// Windows-service mode: the service (LocalSystem) keeps one "agent" process running in the active
// console session with a SYSTEM token, so the agent can capture/control the lock screen and UAC prompts.
constexpr wchar_t kServiceName[] = L"DeskLinkHost";
constexpr wchar_t kAgentQuitEvent[] = L"Global\\DeskLinkHostAgentQuit";
constexpr wchar_t kUserQuitEvent[] = L"Local\\DeskLinkHostUserQuit";

bool IsServiceInstalled();
bool IsServiceRunning();
bool IsProcessElevated();
int RunService();
int RunInstall(bool quiet);
std::wstring InstalledExePath();  // Program Files\DeskLink\DeskLinkHost.exe (where the service runs from)
int RunUninstall(bool quiet);
