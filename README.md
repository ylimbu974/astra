# Project Astra: self-hosted remote desktop (MVP)

Tap a PC on your Android phone and control it from anywhere, over your own Tailscale network.
No third-party cloud, no open router ports, and screen frames are never stored.

**No PC? Run it on the Pi alone.** The Raspberry Pi can be both the registry and the computer you control,
like OSLink: the host agent runs on the Pi's own desktop next to the registry. One script sets it up:
`bash install-pi.sh`. See [SETUP.md, "Pi only"](docs/SETUP.md#pi-only-no-windows-pc).

```
 Android app (A/B screens)          Raspberry Pi 4B                   Windows PC
 ┌─────────────────────┐   REST    ┌──────────────────┐  heartbeat  ┌────────────────────────┐
 │ device list, pairing│──────────▶│ registry (SQLite)│◀────────────│ host agent             │
 │ WebView viewer (C)  │           │ W1 web page      │   + approve │  P1 window: QR + PIN   │
 └─────────┬───────────┘           └──────────────────┘             │  P2 approval popup     │
           │        WebSocket: JPEG frames ◀── / ──▶ JSON input    │  capture (mss), input  │
           └───────────────────────────────────────────────────────▶│  (pynput), web viewer  │
                         all traffic inside Tailscale (WireGuard)   └────────────────────────┘
```

With a Windows PC, the Pi is never in the video path. It only keeps the device list, pairings and session metadata.
In the Pi-only setup, the "PC" box above is the Pi itself, so both boxes run on the same Pi.

## What's in here

| Folder | What it is | Runs on |
|---|---|---|
| `registry/` | Pi registry: FastAPI + SQLite (six tables from the tech sketch), W1 device page, Docker Compose | Raspberry Pi 4B |
| `host-agent/` | Host agent: screen capture, input injection, rotating PIN, QR, approval popup, session banner | Windows PC, or the Pi's desktop (X11) |
| `web-viewer/` | The viewer page (C1 to C4, E4, E5): touch gestures, keyboard, fit/zoom. Served by the host agent | Phone browser and inside the app |
| `android-app/` | Kotlin + Jetpack Compose app: A1 to A3, B1 to B4, E1 to E3, WebView wrapper for the viewer | Android phone |
| `install-pi.sh` | One-step setup for the Pi-only way: registry + host agent + autostart on the same Pi | Raspberry Pi OS with desktop |
| `docs/` | [SETUP.md](docs/SETUP.md) (do this first), [RUNBOOK.md](docs/RUNBOOK.md), [SECURITY.md](docs/SECURITY.md) | |

## Start here

Follow **[docs/SETUP.md](docs/SETUP.md)**. It starts by asking which way you want: **Pi only**, or **Pi + Windows PC**.
It takes about an hour the first time.

## How a session works

1. The host agent checks in with the Pi every 30 s. The Pi marks it offline after 90 s of silence, or straight away when you press **Stop host**.
2. **Pairing (once per phone and PC).** The phone scans the QR in the PC's host window (name and Tailscale address, never the PIN). It asks the Pi for a pairing and gets its phone token. Then it sends the 6-digit PIN to the PC. The PC checks the PIN (3 tries, then a new PIN and a 5-minute lock) and shows the **Allow / Deny** popup. On Allow, the PC tells the Pi to approve the pairing.
3. **Every session.** The viewer opens `ws(s)://<pc>:8765/stream` and presents the token (app) or the PIN (plain browser). The PC asks the Pi whether that phone is paired, then shows Allow / Deny again. There is no "always allow" in the MVP. After Allow, JPEG frames stream to the phone and input comes back as JSON with 0 to 1 coordinates.
4. Either side can end the session at any time. The PC shows a red banner at the top of its screen while a session is live.

## Kanban coverage

| Phase | Tasks | Status in this code |
|---|---|---|
| 1 Anywhere access | T-05 to T-09 | Hardware setup. Steps are in SETUP.md (§1, §2) |
| 2 Host agent | T-10 to T-17 | Done: `/health`, debug screenshot, frame stream, WebSocket input, mouse, keyboard, PIN, QR |
| 3 Pi registry | T-18 to T-21 | Done: check-in API, offline after 90 s, W1 page, Docker with auto-restart |
| 4 Web viewer | T-22 to T-25 | Done: full-screen stream, touch to mouse, keyboard, fit/zoom/pinch |
| 5 Android app | T-26 to T-30 | Written, **not yet compiled** (see below): device list, WebView viewer, QR + PIN pairing, saved devices |
| 6 Security & testing | T-31 to T-36 | T-31 in SECURITY.md, T-32 done (approval popup), T-36 in RUNBOOK.md. T-33 to T-35 need your real devices |

Notes against the Kanban wording:
* **T-12** asked for MJPEG over HTTP. The stream is JPEG frames over the WebSocket, which is the format the tech sketch picks and what the viewer uses. A separate HTTP stream would be a second unauthenticated way to watch the screen, so there isn't one.
* **T-11**'s screenshot endpoint exists, but it's off by default (`ENABLE_DEBUG_SCREENSHOT=1`) and answers only to the PC itself.

## What was tested, and what wasn't

* **Tested here:** 12 registry tests and 15 host agent tests, all passing on Python 3.12. They cover pairing through the real registry, wrong PIN and lockout, deny and timeout, cancel, the PC ending a session, a second viewer getting "busy", and the input whitelist. A live run also passed on a virtual Linux display: real registry, real host agent with its Tk window and `mss` capture, and the viewer in a phone-sized Chromium. That run did PIN entry, P2 Allow, a live stream, and a tap on the phone that clicked **End session** on the PC through `pynput`. It ended on "Ended on the PC" and wrote the session row on the Pi.
* **Pi-only additions, tested here:** 17 host agent tests (on Python 3.11, the version in Raspberry Pi OS Bookworm, and 3.13 for Trixie), plus a live run of `start-host.sh` on a virtual X11 desktop: a PIN session streamed real frames and a phone "move" put the real X11 pointer on the exact pixel. The Wayland check stopped with the switch-to-X11 steps as intended. `install-pi.sh` was run here with `sudo`, Docker and Tailscale stubbed out, to check the `.env` files and autostart entry it writes, and that a second run keeps them.
* **Not tested here (Pi only):** a real Raspberry Pi. Nothing here could run ARM hardware, Docker on the Pi, or the real Pi OS desktop, so the frame rate on a Pi 4 and the exact raspi-config menu names are unchecked.
* **Not tested here:** Windows itself (DPI scaling, the Windows firewall, Tailscale detection), the Pi's Docker image on ARM, and the Android app. The build environment couldn't download the Android SDK or Google's Maven libraries, so **the app has never been compiled**. Expect to fix small compile errors on the first Android Studio build.

Run the tests yourself:
```
cd registry   && python -m pip install -r requirements-dev.txt && python -m pytest -q
cd host-agent && python -m pip install -r requirements-dev.txt && python -m pytest -q
```

## Out of scope (V2, per the brief)

WebRTC/H.264 native viewer, self-hosted relay on the Pi, file transfer, clipboard sync, multi-monitor
(the primary display only is streamed), Wake-on-LAN, recording, sharing rules for other people, iOS, audio.
