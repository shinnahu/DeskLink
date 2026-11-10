#pragma once
#include <string>

// A virtual screen for Windows to draw on while the PC has no screen: its monitor is switched off (a DisplayPort
// monitor tells Windows it was unplugged) or unplugged. With no screen Windows draws no desktop, so there is nothing
// to capture.
//
// Made with Parsec's virtual display driver (Parsec VDD), which is installed separately (README). DeskLink adds one
// screen while a phone needs it and keeps it alive with a ping every 100 ms, sent from a thread of its own so nothing
// can hold the pings up. The driver takes it away by itself about a second after the pings stop, so a closed or
// crashed DeskLink never leaves one behind; should it take the screen away while a phone still needs it, a new one is
// put in. The driver's control codes are the ones documented by github.com/nomi-san/parsec-vdd.
namespace vscreen {

enum class State {
  NoDriver,  // the driver isn't installed
  Starting,  // added (or about to be added again): Windows is putting it on the desktop
  Waiting,   // not added: a monitor was just connected and Windows is turning it on
  Failed,    // the driver is there but no screen could be added, Windows didn't take it, or the driver kept taking it
             // away
};

// A phone stream started (Hold) or ended (Release). Once nobody holds it, the virtual screen goes after 30 s; a phone
// that reconnects within that time gets it back.
void Hold();
void Release();

// The capture found no screen: put the virtual one in (or keep it). It goes again when a real monitor has been on the
// desktop for 2 s. force (tests): put it in even while a monitor is on, and keep it while one is.
State Ensure(bool force = false);

bool Installed();                         // the driver is there
bool IsVirtual(const wchar_t* gdiName);   // \\.\DISPLAYn is one of the driver's virtual screens
std::wstring VirtualOnDesktop();          // the virtual screen's \\.\DISPLAYn while it's on the desktop, else empty
bool RealMonitorOnDesktop();              // a real monitor (not a virtual screen) is on the desktop
// \\.\DISPLAYn still has its monitor. When the last monitor goes (switched off, unplugged), Windows keeps its screen on
// the desktop with nothing behind it and draws nothing to it: no frames ever come from it. True when it can't tell.
bool HasMonitor(const wchar_t* gdiName);
void Unpin();                             // tests: a forced screen goes again when a monitor is on
void RemoveNow();                         // tests: take it away now
// tests: the driver takes our screen away by itself (as when the pings came late) while the pings go on; false when
// there is none
bool SimulateDrop();

}  // namespace vscreen
