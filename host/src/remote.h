#pragma once
#include <cstdint>
#include <string>
#include <vector>

// Reaching this PC over mobile data with nothing else installed. The home router is asked (UPnP) to forward
// DeskLink's TCP port to this PC and, where it supports that, to let IPv6 connections to it through. This is renewed
// every 30 minutes and removed when DeskLink exits. Connections that come from the internet must start with a
// "knock" that only a paired phone can make (server.cpp), so nobody else gets past the first 64 bytes.
namespace remote {

// What the phone is told (HOST_INFO, after the addresses), so it can say what works. See PROTOCOL.md.
enum : uint8_t {
  IPV4 = 1,          // the router forwards the port: reachable at the public IPv4 address
  IPV6 = 2,          // the router's IPv6 firewall lets connections to this PC's port through
  IPV6_MAYBE = 4,    // the PC has public IPv6, but the router can't say whether it lets connections in
  NO_UPNP = 8,       // no router answered UPnP (turned off, or not supported)
  SHARED_IPV4 = 16,  // the router's internet address isn't public (CGNAT): IPv4 can't reach it
  CHECKING = 64,     // not found out yet
  OFF = 128,         // turned off on the PC
};

void Start(int port);
void Stop();                 // removes the forwarding / opening (on exit)
bool Enabled();
void SetEnabled(bool on);    // tray menu; saved in the config
uint8_t Flags();
std::string PublicAddress(); // "a.b.c.d" or "a.b.c.d:port" while the IPv4 forwarding works, else ""
std::wstring Describe();     // one line for the tray menu

// Is this address a home-network one, as opposed to the internet? Loopback, private and link-local IPv4,
// 100.64/10 (CGNAT / Tailscale), and IPv6 loopback, link-local and unique-local (fc00::/7). On its own it can't tell
// Tailscale from an internet company's CGNAT: decide on connections with IsHomeConnection.
bool IsLocalAddress(const std::string& ip);

// Is this connection from home (this PC's network, or its own Tailscale), as opposed to the internet? `peer` is the
// other end, `ours` this PC's end ("" when not known: then 100.64/10 is the internet). Like IsLocalAddress, except
// that 100.64.0.0/10 is home only when the connection came in through this PC's Tailscale address: internet companies
// give their customers addresses there too (CGNAT), and those are strangers.
bool IsHomeConnection(const std::string& peer, const std::string& ours);
// The same with this PC's Tailscale addresses given (self-test).
bool IsHomeConnection(const std::string& peer, const std::string& ours, const std::vector<std::string>& tailscale);
// This PC's IPv4 addresses on Tailscale's network adapter (none without Tailscale).
std::vector<std::string> TailscaleAddresses();

// The PC's public IPv6 addresses that stay put (not the temporary privacy ones).
std::vector<std::string> StableGlobalIpv6();

#ifdef DESKLINK_TESTING
// Talks to a given router description URL instead of discovering one (tests against a fake router on 127.0.0.1).
void SetTestLocation(const std::string& url);
// Runs one refresh now (tests); returns the flags.
uint8_t RefreshNowForTest(int port);
#endif
}  // namespace remote
