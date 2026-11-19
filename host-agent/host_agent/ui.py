"""The interface between the server (asyncio thread) and whatever the PC user sees.

The real one is the Tk window in gui.py (P1, P2, P3 and the session banner).
ConsoleUI is a fallback for running without a window (HEADLESS=1).
"""
from __future__ import annotations

import concurrent.futures
import threading
from typing import Protocol

ALLOW, DENY, TIMEOUT = "allow", "deny", "timeout"


class HostUI(Protocol):
    def ask_approval(self, kind: str, label: str, timeout: int) -> concurrent.futures.Future:
        """Show P2. kind is "session" or "pairing". Resolves to ALLOW, DENY or TIMEOUT.

        Closing the popup counts as DENY. No "always allow" option exists in the MVP.
        """

    def dismiss_approval(self) -> None:
        """The phone cancelled (C1 Cancel): close P2 if it is still open."""

    def session_started(self, label: str) -> None:
        """Switch P1 to P3 and show the slim banner at the top of the screen."""

    def session_ended(self, reason: str) -> None:
        """Back to P1 and remove the banner."""

    def refresh(self) -> None:
        """Agent status changed (registry reachable, device id known, ...)."""


class ConsoleUI:
    """Approval prompts in the terminal. Only for HEADLESS=1 test runs."""

    def __init__(self):
        self._lock = threading.Lock()

    def ask_approval(self, kind, label, timeout):
        fut: concurrent.futures.Future = concurrent.futures.Future()

        def ask():
            with self._lock:
                what = "pair with" if kind == "pairing" else "control"
                print(f"\n[P2] {label} wants to {what} this PC. Allow? Type y within {timeout}s: ", end="", flush=True)
                box: list[str] = []
                reader = threading.Thread(target=lambda: box.append(input()), daemon=True)
                reader.start()
                reader.join(timeout)
                if fut.done():
                    return
                if not box:
                    fut.set_result(TIMEOUT)
                else:
                    fut.set_result(ALLOW if box[0].strip().lower() in ("y", "yes") else DENY)

        threading.Thread(target=ask, daemon=True).start()
        return fut

    def dismiss_approval(self):
        print("\n[P2] request cancelled by the phone")

    def session_started(self, label):
        print(f"[P3] Session active: {label}")

    def session_ended(self, reason):
        print(f"[P1] Session ended ({reason})")

    def refresh(self):
        pass
