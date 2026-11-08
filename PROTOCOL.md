# DeskLink wire protocol — v1

DeskLink = Windows host (`host/`, C++) + Android client (`android/`, Java).
This document is the single source of truth for everything that crosses the network.

## 0. Conventions

* TCP port **47810** (session), UDP port **47811** (LAN discovery).
* All integers are **big-endian**. `i32`/`i16` are two's complement.
* `str` = `u16 byteLength` + UTF-8 bytes (no terminator).
* `tail` = all remaining bytes of the message.
* Crypto: NIST P-256 (secp256r1) for ECDH and ECDSA, SHA-256, HMAC-SHA256, AES-256-GCM (16-byte tag).

## 1. LAN discovery (UDP 47811, plaintext)

* Client → broadcast (`255.255.255.255` **and** the Wi-Fi subnet broadcast, 3 times at 0/300/800 ms):
  ASCII `DESKLINK?1` (10 bytes).
* Host → unicast reply to the request's source IP:port:
  ASCII `DESKLINK!1` immediately followed by a UTF-8 JSON object:
  ```json
  {"id":"<32 hex hostId>","name":"ASUS-PC","port":47810,"fp":"<64 hex fingerprint>","pairing":true,"busy":false,"ver":1}
  ```
  `pairing` = host currently accepts new pairings; `busy` = a session is active. The client takes the IP from the UDP source address.
* The same request may also be sent **unicast** to a known host address (cheap LAN liveness check).
* The host answers only requests from the home network (see "local" in §2).

## 2. Handshake (TCP 47810)

Before encryption, each handshake message is framed as `u32 length` + `body` (length ≤ 4096).

### Knock (connections from the internet)
A peer is **local** if its address is loopback, private or link-local IPv4, or IPv6 loopback, link-local or
unique-local (`fc00::/7`); IPv4-mapped IPv6 counts as its IPv4. `100.64.0.0/10` is local only when the connection
arrived on one of the host's own Tailscale addresses: internet companies use the same range for their customers
(CGNAT), and those are strangers. Anyone else is on the internet (mobile data), and its first frame must be a knock,
body = 60 bytes:

| field | size |
|---|---|
| magic ASCII `DLKN` | 4 |
| `u64` unix time (seconds) | 8 |
| nonce (random) | 16 |
| `HMAC-SHA256(key = SHA256(token), the 28 bytes above)` | 32 |

The host accepts it if the time is within ±600 s of its clock, the MAC matches one of its paired devices, and the
nonce wasn't used in the last 25 minutes. Otherwise, or without a knock, it closes the connection without reading
anything else; failed internet tries count toward the per-address limit (10 per 10 minutes). The ClientHello follows
the knock as usual. From a local peer a knock is optional. The client sends one only to internet addresses, and only
to hosts with capability `KNOCK` (older hosts would reject it). The host refuses internet peers entirely while
mobile-data access is off on the PC.

### ClientHello (client → host), body = 102 bytes
| field | size |
|---|---|
| magic ASCII `DLNK` | 4 |
| version `u8` = 1 | 1 |
| client ephemeral P-256 public key, uncompressed `0x04‖X‖Y` | 65 |
| clientRandom | 32 |

### ServerHello (host → client), body = 231 bytes
| field | size |
|---|---|
| magic ASCII `DLNK` | 4 |
| version `u8` = 1 | 1 |
| host ephemeral P-256 public key `0x04‖X‖Y` | 65 |
| hostRandom | 32 |
| host **static** (identity) P-256 public key `0x04‖X‖Y` | 65 |
| signature: raw `r‖s` (32+32 bytes) | 64 |

`signature = ECDSA-P256-SHA256(hostStaticPriv, M)` where `M = ClientHelloBody ‖ ServerHelloBody[0..167)`
(i.e. everything before the signature). Java verifies with `SHA256withECDSA` after converting `r‖s` to DER.

If the host does not support the client's version it closes the socket.

### Host identity
`fingerprint = SHA256(hostStaticPub)` (65-byte uncompressed point) → 64 lowercase hex chars.
Short display form: first 6 bytes as `AB12-CD34-EF56`.
A paired client **pins** the fingerprint and aborts if it ever differs ("PC identity changed").
The client always verifies the signature.

### Key schedule (HKDF-SHA256)
```
Z      = ECDH(clientEph, hostEph)            32-byte big-endian X coordinate
TH     = SHA256(ClientHelloBody ‖ ServerHelloBody)        (all 102 + 231 bytes)
PRK    = HMAC(key=TH,  data=Z)
T1     = HMAC(key=PRK, data="desklink-v1-keys" ‖ 0x01)
T2     = HMAC(key=PRK, data=T1 ‖ "desklink-v1-keys" ‖ 0x02)
keyC2H = T1   (AES-256-GCM, client → host)
keyH2C = T2   (AES-256-GCM, host → client)
```
(Windows CNG note: `BCRYPT_KDF_RAW_SECRET` returns Z little-endian — reverse it.)

## 3. Encrypted records (everything after ServerHello, both directions)

```
u32 len          ciphertext length including the 16-byte GCM tag (17 ≤ len ≤ 16 MiB)
byte[len]        ciphertext ‖ tag
```
* nonce (12 bytes) = `00 00 00 00 ‖ u64 seq` ; `seq` starts at 0 for each direction and increments per record.
* AAD = the 4 bytes of `len`.
* plaintext = `u8 type ‖ payload`.
* Any decryption failure → close the connection.

## 4. Authentication / pairing

After the handshake the client sends **either** `AUTH` (already paired, starts a session), `STATUS_QUERY`
(already paired, just asks for status — never starts a session) **or** `PAIR_REQUEST`.
The host closes the connection if none arrives within 15 s.

### Status check (for the PC list on the phone)
client → `STATUS_QUERY`, host → `STATUS_RESULT`, then the host closes the connection.
The phone probes every paired PC (all known addresses) when the list is shown and every 30 s while visible,
and stores `lastSeenOnline` locally so offline PCs can show "Offline · last seen 2 h ago".

### Pairing (numeric comparison with commitment)
1. client → `PAIR_REQUEST` with `commit = SHA256(nonceC)` (nonceC = 32 random bytes).
2. host → `PAIR_CHALLENGE` with `nonceH` (32 random bytes) — or `PAIR_RESULT` with an error if pairing is closed.
3. client → `PAIR_REVEAL` with `nonceC`. Host checks the commitment.
4. Both compute the 6-digit code:
   `d = SHA256("desklink-v1-sas" ‖ TH ‖ nonceC ‖ nonceH)`, `code = (u32 from d[0..4)) mod 1 000 000`, shown as `123 456`.
   The phone shows the code; the PC shows "<device> wants to control this PC — code 123 456 — Allow / Deny".
5. host → `PAIR_RESULT`. On success the client stores `{hostId, hostName, fingerprint, deviceId, token, addresses}`
   and continues on the same connection with `AUTH`.

The host only accepts pairing while "pairing mode" is on (tray menu → *Pair a phone*, or automatically while no phone is paired),
and only from local peers: from the internet `PAIR_REQUEST` gets `PAIR_RESULT` `PAIRING_CLOSED`. So does one from the
host's own default gateway address (some routers pass connections from the internet on under it).

## 5. Messages

### Auth
| type | name | dir | payload |
|---|---|---|---|
| 0x01 | AUTH | C→H | `deviceId[16] token[32] str deviceName u16 clientVersion(=1)` |
| 0x02 | PAIR_REQUEST | C→H | `commit[32] str deviceName str deviceModel` |
| 0x03 | PAIR_REVEAL | C→H | `nonceC[32]` |
| 0x04 | STATUS_QUERY | C→H | `deviceId[16] token[32]` |
| 0x84 | STATUS_RESULT | H→C | `u8 status(0 OK,1 UNKNOWN_DEVICE,2 BAD_TOKEN) u8 flags(bit0 IN_USE by another device, bit1 SECURE_DESKTOP locked/UAC, bit2 SERVICE_MODE) str hostName str activeDeviceName u32 uptimeSeconds` |
| 0x80 | AUTH_RESULT | H→C | `u8 status str message` |
| 0x81 | PAIR_CHALLENGE | H→C | `nonceH[32]` |
| 0x82 | PAIR_RESULT | H→C | `u8 status` then, only if status==0: `deviceId[16] token[32] hostId[16] str hostName`; always last: `str message` |
| 0x83 | HOST_INFO | H→C | see below |

Status codes: `0 OK, 1 UNKNOWN_DEVICE, 2 BAD_TOKEN, 3 BUSY, 4 PAIRING_CLOSED, 5 DENIED, 6 RATE_LIMITED, 7 VERSION, 8 TIMEOUT`.

`HOST_INFO` (sent right after a successful `AUTH_RESULT`):
```
hostId[16] str hostName u32 capabilities
u8 monitorCount { u8 index i32 left i32 top i32 width i32 height u8 isPrimary str name }*
u8 addressCount { str address }*          // LAN IPv4, Tailscale 100.x, global IPv6, computer name, and the
                                          // router's public IPv4 ("a.b.c.d" or "a.b.c.d:port") while it forwards the port
u8 remoteFlags                            // only with capability KNOCK: what reaches the PC over mobile data
```
capabilities: `1 CTRL_ALT_DEL, 2 FILES, 4 CLIPBOARD, 8 SERVICE_MODE (lock screen / UAC visible), 16 KNOCK, 32 REL_MOUSE,
64 ALL_SCREENS` (monitor `0xFE` shows every screen at once).

remoteFlags: `1 IPV4` (the router forwards the port; the public address is in the list), `2 IPV6` (the router's IPv6
firewall lets connections through), `4 IPV6_MAYBE` (public IPv6, router can't say), `8 NO_UPNP` (no router answered, or
it refused), `16 SHARED_IPV4` (CGNAT: the router's own address isn't public), `64 CHECKING`, `128 OFF` (turned off on
the PC). Older clients stop reading after the addresses.

### Stream control
| type | name | dir | payload |
|---|---|---|---|
| 0x10 | STREAM_START | C→H | `u8 monitor(0xFF=primary, 0xFE=all screens) u16 maxWidth u16 maxHeight u8 maxFps u32 bitrateKbps(0=auto) u8 flags(bit0 clipboard sync)` then, optionally, `u16 decoderMaxWidth u16 decoderMaxHeight` (the largest picture the client decodes; all-screens pictures stay within it) |
| 0x11 | STREAM_PAUSE | C→H | — (app went to background: stop video, keep connection) |
| 0x12 | STREAM_RESUME | C→H | — (host sends keyframe + cursor shape/state) |
| 0x13 | FRAME_ACK | C→H | `u32 frameId` (acks every frame ≤ id) |
| 0x14 | KEYFRAME_REQUEST | C→H | — |
| 0x15 | STREAM_CONFIG | C→H | `u16 maxWidth u16 maxHeight u8 maxFps u32 bitrateKbps` (0 = keep current) |
| 0x16 | SET_OPTIONS | C→H | `u8 flags(bit0 clipboard sync)` |
| 0x17 | SELECT_MONITOR | C→H | `u8 index` (`0xFF` primary, `0xFE` all screens: only with capability `ALL_SCREENS`) |
| 0x90 | VIDEO_FORMAT | H→C | `u8 codec(1=H.264) u16 width u16 height u8 monitor i32 desktopLeft i32 desktopTop i32 desktopWidth i32 desktopHeight` |
| 0x91 | VIDEO_FRAME | H→C | `u32 frameId u8 flags(bit0 keyframe) u64 timestampUs tail annexB` |
| 0x92 | CURSOR_SHAPE | H→C | `u32 shapeId u16 w u16 h u16 hotX u16 hotY tail rgba` (w·h·4 bytes, straight alpha, row-major) |
| 0x93 | CURSOR_STATE | H→C | `i32 x i32 y u8 visible u32 shapeId` |
| 0x94 | HOST_STATUS | H→C | `u8 flags str message` (bit0 SECURE_DESKTOP: lock screen/UAC can't be captured; bit1 NO_SIGNAL: capture failing) |

Video rules
* H.264 Annex-B (`00 00 00 01` start codes), no B-frames. Every keyframe carries SPS+PPS+IDR in the same message.
* `VIDEO_FORMAT` precedes the first frame and every size change; the first frame after it is a keyframe.
* Flow control: the host keeps at most 3 un-acked frames in flight. The client sends `FRAME_ACK` as soon as a frame is
  received (before decoding). Static screens produce no frames — that is normal, liveness comes from PING/PONG.
* The cursor is **not** drawn into the video. The client draws it locally (shape from `CURSOR_SHAPE`, cached per session by id).
  `CURSOR_STATE` is sent when the shape or visibility changes, or when the cursor moved on the PC for reasons other than
  the client's input; the client adopts that position unless the user is currently touching.
* All input/cursor coordinates are **monitor-relative desktop pixels** (`0..desktopWidth-1`, `0..desktopHeight-1`),
  never encoded-frame pixels.
* All screens (`monitor` `0xFE` in `VIDEO_FORMAT`): the picture is the smallest rectangle around every screen, laid out
  as Windows arranges them, black where no screen is; the desktop rect is that rectangle and coordinates are relative
  to it. Each screen is as sharp as it would be alone (`maxWidth × maxHeight`), the whole picture at most 3840×2160 and
  within the client's decoder limit. If one picture of every screen can't be made, the host falls back to a smaller
  one, then to the primary screen; `VIDEO_FORMAT` says which.

### Input
| type | name | dir | payload |
|---|---|---|---|
| 0x20 | MOUSE_MOVE | C→H | `i32 x i32 y` |
| 0x21 | MOUSE_BUTTON | C→H | `u8 button(1 left 2 right 3 middle 4 back 5 forward) u8 down(1/0)` (at the last MOUSE_MOVE position) |
| 0x22 | MOUSE_WHEEL | C→H | `i16 dy i16 dx` (120 = one notch; dy>0 = wheel up / away from user; dx>0 = right) |
| 0x23 | KEY | C→H | `u16 windowsVirtualKey u8 down(1/0)` |
| 0x24 | TEXT | C→H | `tail utf8` typed via Unicode injection (`\n`→Enter, `\t`→Tab, `\b`→Backspace) |
| 0x25 | RELEASE_ALL | C→H | — releases every key/button the client holds |
| 0x26 | ACTION | C→H | `u8 action` 1 CTRL_ALT_DEL, 2 LOCK_PC, 3 TASK_MANAGER |
| 0x27 | MOUSE_REL | C→H | `i16 dx i16 dy` mouse counts, like a real mouse (games that read the mouse directly, e.g. a 3D camera, turn by them; a visible pointer moves by them with Windows' pointer speed). Only to hosts with capability `REL_MOUSE` |

* While Ctrl/Alt/Win is held (KEY down sent), the client sends letters/digits as `KEY` (VK `0x41..0x5A`, `0x30..0x39`) instead of `TEXT`, so shortcuts work.
* The host releases everything the client still holds when the session ends.
* Useful VKs: BACK 08, TAB 09, RETURN 0D, SHIFT 10, CONTROL 11, MENU(Alt) 12, ESCAPE 1B, SPACE 20, PRIOR 21, NEXT 22,
  END 23, HOME 24, LEFT 25, UP 26, RIGHT 27, DOWN 28, SNAPSHOT 2C, INSERT 2D, DELETE 2E, LWIN 5B, APPS 5D, F1..F12 70..7B.

### Clipboard
| 0x30 | CLIPBOARD | C↔H | `tail utf8` (≤ 1 MiB). Only sent while clipboard sync is on. |
|---|---|---|---|

The host sends its clipboard when STREAM_START (or SET_OPTIONS turning sync on) arrives, and then whenever it changes;
it skips text equal to what the client last sent. A client holding a newer copy of its own sends CLIPBOARD *before*
STREAM_START: the host applies it first, so it does not send its older clipboard back.

### Files (either direction; client-created ids are odd, host-created ids are even)
| type | name | dir | payload |
|---|---|---|---|
| 0x40 | FILE_BEGIN | ↔ | `u32 id u64 size str name str mime` |
| 0x41 | FILE_DATA | ↔ | `u32 id tail bytes` (≤ 256 KiB per message) |
| 0x42 | FILE_END | ↔ | `u32 id` |
| 0x43 | FILE_CANCEL | ↔ | `u32 id` (sender aborts, or receiver rejects) |
| 0x44 | FILE_PROGRESS | ↔ | `u32 id u64 bytesReceived` (receiver → sender, at least every 1 MiB; sender keeps ≤ 4 MiB un-acked) |
| 0x45 | FILE_DONE | ↔ | `u32 id u8 status(0 ok 1 error 2 cancelled) str info` (receiver → sender; host puts the saved path in info) |
| 0x46 | FILE_REQUEST | C→H | — host opens a Windows "pick files" dialog on the PC desktop and sends the chosen files |
| 0x47 | FILE_REQUEST_NONE | H→C | — the dialog was cancelled |

Video always has priority over file data on the wire.

### Housekeeping
| type | name | dir | payload |
|---|---|---|---|
| 0x50 | PING | ↔ | `u64 t` |
| 0x51 | PONG | ↔ | `u64 t` (echo) |
| 0x52 | NOTICE | H→C | `u8 level(0 info 1 warn 2 error) str text` |
| 0x5F | BYE | ↔ | `u8 reason str message` — 0 normal, 1 stopped on the PC, 2 replaced by a newer connection, 3 host exiting, 4 protocol/auth error, 5 timeout |

Liveness
* Client sends PING every 1 s; host answers PONG immediately. Host sends PING every 2 s; client answers PONG.
* Client: nothing received for 2.5 s → "weak connection" indicator; for 5 s → connection lost → close and auto-reconnect.
* Host: nothing received for 10 s → end the session.
* A device that reconnects while its old session still exists replaces it (old one gets `BYE 2`).
  A *different* device gets `AUTH_RESULT BUSY` unless the active session has been silent for > 5 s.
