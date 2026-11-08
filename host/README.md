# DeskLink Host (Windows)

Native C++ (Win32 + Direct3D 11 + Media Foundation + CNG). One 640 KB `.exe`, no runtime to install.

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

## Source map

| file | what |
|---|---|
| `main.cpp` | modes: agent (default), `--install`, `--uninstall`, `--service`, self-tests |
| `server.cpp` | TCP listener, handshake (+ the knock required from the internet), AUTH / STATUS_QUERY / pairing, UDP LAN discovery |
| `remote.cpp` | mobile data: UPnP on the home router (port forward, IPv6 pinhole), renewed every 30 min; home-vs-internet peers |
| `session.cpp` | one phone connection: reader + writer threads (video before file data), ping, dispatch |
| `channel.cpp`, `crypto.cpp` | ECDH P-256 + ECDSA host identity + HKDF + AES-256-GCM (Windows CNG) |
| `capture.cpp`, `encoder.cpp`, `h264.cpp` | capture, colour conversion, H.264, flow/rate control, cursor |
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
DeskLinkHostTest.exe --data %TEMP%\dl --test-input                   :: injection checked by swallowing hooks
DeskLinkHostTest.exe --data %TEMP%\dl --test-render-ui %TEMP%\png    :: pairing window PNGs
DeskLinkHostTest.exe --data %TEMP%\dl --bind 127.0.0.1 --port 47910 --no-ui --auto-approve
node tools\testclient.mjs 127.0.0.1 47910 %TEMP%\work                :: 17 end-to-end protocol checks
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
```

## Files on disk

* user mode: `%LOCALAPPDATA%\DeskLink\config.txt` + `host.log`
* service mode: `%ProgramData%\DeskLink\` (ACL: SYSTEM + Administrators only), exe in `%ProgramFiles%\DeskLink\`
* `config.txt` holds the PC identity key (DPAPI-encrypted) and the linked phones (only SHA-256 of their tokens).
