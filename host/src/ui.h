#pragma once
#include "common.h"

// Tray icon, "shared with" overlay pill, pairing window, clipboard bridge and notifications.
// All functions are safe to call from any thread (they post to the UI thread).
namespace ui {

constexpr wchar_t kMainClass[] = L"DeskLinkHostMain";
constexpr UINT WM_APP_SHOW_PAIRING = WM_APP + 40;  // may be posted by another DeskLinkHost process
constexpr UINT WM_APP_QUIT = WM_APP + 41;

bool Init(bool withTray);
int Run();
void Quit();

void SessionStarted(const std::string& deviceName);
void SessionEnded();
void SetClipboardText(const std::string& utf8);
void RequestClipboardPush();  // send the current PC clipboard to the phone
void FileReceived(const std::wstring& path);
void Notify(const std::wstring& title, const std::wstring& text);
void RefreshTray();
void ShowPairingWindow();

// Blocks the calling (network) thread until the user clicks Allow/Deny or the timeout expires.
// Returns 1 = allowed, 0 = denied, -1 = timeout.
int AskPairing(const std::string& deviceName, const std::string& model, const std::string& code, int timeoutMs);
void PairingFinished(bool ok, const std::string& deviceName);

#ifdef DESKLINK_TESTING
int RenderPreviews(const std::wstring& dir);  // saves pairing-window PNGs for design review
#endif

}  // namespace ui
