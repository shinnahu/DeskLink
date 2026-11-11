# DeskLink Host (Windows)

Native C++ (Win32 + Direct3D 11 + Media Foundation + WASAPI + CNG). One 687 KB `.exe`, no runtime to install.

## Pipeline

```
Desktop Duplication (GPU) -> D3D11 video processor (BGRA->NV12, scale) -> Media Foundation H.264
(NVIDIA/AMD/Intel hardware MFT, Microsoft software MFT as fallback) -> SPS rewrite -> encrypted TCP
```

* Frames are only produced when the screen changes; a few "top-off" frames sharpen the picture after motion stops.
* At most 3–10 frames may be un-acknowledged (scaled to the round-trip time), and the bitrate adapts
  (500 kbps – 20 Mbps). A slow link drops frame rate instead of building up lag.
* The SPS is rewritten (`max_num_reorder_frames = 0`, BT.709 limited) so Android decoders output every frame
  immediately.
* The cursor is **not** drawn into the video: the shape + position are sent separately and the phone draws it,
  so pointer movement feels instant.
* All screens (monitor `0xFE`): every screen is duplicated and copied into its place in one BGRA picture (the
  rectangle around them, black where no screen is), which then goes the same way. Screens on the main graphics card are
  copied on the GPU; a screen on another card, or a turned one, through the CPU. The picture is at most 3840×2160 and
  within the decoder size the phone sends; if it can't be made, a smaller one, then the main screen alone.
* No screen (the monitor switched off, which DisplayPort reports as unplugged, or no monitor at all): Windows keeps
  the last screen on the desktop with no monitor behind it (its display path is "available", forced by the system,
  but the target has no monitor device path) and draws nothing to it, so such screens are left out, and a shown one
  whose monitor goes is noticed within a second. With Parsec's virtual display driver installed, a virtual screen is
  then added while a phone streams and kept alive with a ping every 100 ms, sent from a thread that does nothing else:
  the driver takes its screens away about a second after the last ping, and looking at the display layout can wait
  that long while Windows rearranges the screens. Windows moves the desktop onto it. It goes 2 s after a real monitor
  is back on the desktop, or 30 s after the last stream ends; the driver removes it by itself about a second after the
  pings stop. Should the driver take it away while it's needed (its monitor device gone for a second), a new one is put
  in 1.5 s later (10 s after the third time in a minute), and the capture, which looks for a vanished screen once a
  second, starts again on it.

```
WASAPI loopback of the default output (48 kHz 16-bit stereo, converted by Windows) -> Media Foundation AAC-LC
128 kbps -> AUDIO_DATA, ahead of everything else on the wire
```

* Sound (`audio.cpp`), while the phone asks for it and the stream isn't paused: what the PC's speakers play. If
  Windows won't convert, the mix format is taken as it is (44.1 or 48 kHz; float or 16/24/32-bit) and turned into
  stereo here, surround channels mixed in. After a second of quiet, or as soon as Windows hands out nothing (nothing
  plays), the encoder gets silence to push the last sound out and nothing more is sent until there is sound again.
  Another default output (headphones plugged in) is picked up within a second. Without any output (the only speakers
  were the monitor's and it's off), the phone is told so (codec 0) and it looks again every 2 s.
* The session sends sound first: about a second of AAC frames waits at most, and on a slow link the oldest are dropped
  rather than played late.
* While it takes sound it logs a line a minute: the packets Windows handed out (and those it marked silent), the looks
  with sound in them, and the AAC frames sent. A capture that hears nothing shows there.

## Source map

| file | what |
|---|---|
| `main.cpp` | modes: agent (default), `--install`, `--uninstall`, `--service`, self-tests |
| `server.cpp` | TCP listener, handshake (+ the knock required from the internet), AUTH / STATUS_QUERY / pairing, UDP LAN discovery |
| `remote.cpp` | mobile data: UPnP on the home router (port forward, IPv6 pinhole), renewed every 30 min; home-vs-internet peers |
| `session.cpp` | one phone connection: reader + writer threads (video before file data), ping, dispatch |
| `channel.cpp`, `crypto.cpp` | ECDH P-256 + ECDSA host identity + HKDF + AES-256-GCM (Windows CNG) |
| `capture.cpp`, `encoder.cpp`, `h264.cpp` | capture, colour conversion, H.264, flow/rate control, cursor |
| `vdisplay.cpp` | the virtual screen (Parsec VDD) while no monitor is on: added, pinged, taken away |
| `audio.cpp` | the PC's sound: WASAPI loopback of the default output, AAC by Media Foundation, quiet gate |
| `input.cpp`, `desktop.cpp` | `SendInput` injection (absolute mouse, relative mouse for game cameras, VK keys, Unicode text), input-desktop following |
| `files.cpp` | phone→PC into `Downloads\DeskLink`, PC→phone via a file picker on the PC |
| `ui.cpp`, `strings.cpp` | tray menu, "Shared with …" pill, pairing window, clipboard bridge (EN/ES) |
| `service.cpp` | Windows service + SYSTEM agent in the console session, installer/uninstaller |

## Build

Visual Studio 2022 (Desktop C++ workload):

```bat
build.cmd          :: build\DeskLinkHost.exe
build.cmd test     :: also build\DeskLinkHostTest.exe (console + self-tests + --auto-approve)
```

## Self-tests (test build)

```bat
DeskLinkHostTest.exe --data %TEMP%\dl --test-capture 5 out.h264      :: capture+encode to a file
DeskLinkHostTest.exe --data %TEMP%\dl --test-capture 5 out.h264 1920 1920 30 0 all   :: all screens in one picture
:: with one screen: --fake-screens 3 shows it again as 3 pretend screens (as it is, upside down, turned 90°)
:: --fake-no-screen leaves the monitors out, as if switched off: the capture puts in a virtual screen and shows it
DeskLinkHostTest.exe --data %TEMP%\dl --fake-no-screen --test-capture 5 out.h264
:: --test-drop-virtual 10: 10 s in, the virtual screen is taken away as its driver might; the picture must come back
DeskLinkHostTest.exe --data %TEMP%\dl --fake-no-screen --test-drop-virtual 10 --test-capture 25 out.h264
DeskLinkHostTest.exe --data %TEMP%\dl --test-virtual-screen          :: Parsec VDD: a virtual screen added, kept, replaced, taken away
:: plays a quiet 1 kHz tone through the speakers for 3 s while the sound is taken, then decodes what was sent
:: (Windows' AAC decoder): the tone must come back, and nothing may follow a second of quiet; out.aac to listen
DeskLinkHostTest.exe --data %TEMP%\dl --test-audio 3 out.aac
DeskLinkHostTest.exe --data %TEMP%\dl --test-input                   :: injection checked by swallowing hooks
DeskLinkHostTest.exe --data %TEMP%\dl --test-home                    :: which addresses count as home, the router's (20 cases)
DeskLinkHostTest.exe --data %TEMP%\dl --test-render-ui %TEMP%\png    :: pairing window PNGs
DeskLinkHostTest.exe --data %TEMP%\dl --bind 127.0.0.1 --port 47910 --no-ui --auto-approve
node tools\testclient.mjs 127.0.0.1 47910 %TEMP%\work                :: 18 end-to-end protocol checks (sound too)
```

`--data` keeps test identities, logs and received files out of the real profile.

Mobile data, still only on 127.0.0.1 (test builds never look for the real router):

```bat
:: talk UPnP to a fake router at this URL instead of discovering one
DeskLinkHostTest.exe ... --upnp-url http://127.0.0.1:47999/rootDesc.xml
:: treat every peer as coming from the internet: the knock rules apply
DeskLinkHostTest.exe ... --peers-are-internet
:: log the mouse and keys a phone sends instead of injecting them (the desktop is left alone)
DeskLinkHostTest.exe ... --no-inject
:: treat 127.0.0.1 as this PC's router (pairing from it is refused)
DeskLinkHostTest.exe ... --router-is-loopback
```

## Files on disk

* user mode: `%LOCALAPPDATA%\DeskLink\config.txt` + `host.log`
* service mode: `%ProgramData%\DeskLink\` (ACL: SYSTEM + Administrators only), exe in `%ProgramFiles%\DeskLink\`
* `config.txt` holds the PC identity key (DPAPI-encrypted) and the linked phones (only SHA-256 of their tokens).
