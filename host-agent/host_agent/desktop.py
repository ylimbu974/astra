"""Which desktop is the agent running on, and can it capture and control it?

Windows always works. On Linux (Raspberry Pi OS) screen capture (mss) and input
(pynput) use X11. A Wayland desktop (labwc or wayfire, the Pi OS default) does not let
ordinary apps read the screen or move the mouse, so the agent stops with clear steps
instead of streaming a black picture.
"""
from __future__ import annotations

import os
import platform
import socket
import sys
from pathlib import Path

EXIT_NO_DESKTOP = 3

SWITCH_TO_X11 = (
    "This Raspberry Pi desktop is running Wayland, which does not let Astra see the screen or "
    "move the mouse.\n"
    "Switch the desktop to X11 once:\n"
    "  1. In a terminal run:  sudo raspi-config\n"
    "  2. Choose Advanced Options > Wayland > X11 (it may be called 'W1 X11'), then OK and Finish\n"
    "  3. Reboot when it asks. Astra starts again by itself after the reboot.\n"
    "See docs/SETUP.md, section 'Pi only'."
)
NO_DISPLAY = (
    "No desktop found (DISPLAY is not set). Start Astra from the Pi's desktop session, not over SSH. "
    "The autostart entry from install-pi.sh does this for you after the next login."
)


def problem(env: dict | None = None, plat: str | None = None) -> str | None:
    """Return a message explaining why capture/input cannot work here, or None if it can."""
    env = os.environ if env is None else env
    plat = plat or sys.platform
    if not plat.startswith("linux"):
        return None
    session = (env.get("XDG_SESSION_TYPE") or "").lower()
    if session == "wayland" or (env.get("WAYLAND_DISPLAY") and session != "x11"):
        return SWITCH_TO_X11
    if not env.get("DISPLAY"):
        return NO_DISPLAY
    return None


def os_label() -> str:
    """Shown on the Pi's W1 page and in the app, e.g. 'Windows 11' or 'Debian GNU/Linux 12 (bookworm)'."""
    if os.name == "nt":
        return f"Windows {platform.release()}"
    try:
        for line in Path("/etc/os-release").read_text(encoding="utf-8").splitlines():
            if line.startswith("PRETTY_NAME="):
                name = line.split("=", 1)[1].strip().strip('"')
                if name:
                    return name[:64]
    except OSError:
        pass
    return platform.system() or "Unknown"


def default_device_name() -> str:
    return os.environ.get("COMPUTERNAME") or socket.gethostname() or "DESKTOP-PC"
