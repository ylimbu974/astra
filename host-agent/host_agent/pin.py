"""Rotating 6-digit pairing PIN (P1, B3, E3; Kanban T-16).

* A new PIN every PAIRING_PIN_TTL_MINUTES (10 by default).
* 3 wrong tries -> a new PIN is issued and PIN entry locks for 5 minutes.
* The PIN only ever appears in the PC's own window. It is never sent to the registry
  by the PC, never put in the QR code, and never logged.
"""
from __future__ import annotations

import hmac
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Callable

MAX_ATTEMPTS = 3
LOCK_SECONDS = 5 * 60


@dataclass
class PinResult:
    ok: bool
    error: str | None = None        # "wrong_pin" | "locked"
    attempts_left: int = MAX_ATTEMPTS
    retry_after: int = 0            # seconds, when locked


class PinManager:
    def __init__(self, ttl_minutes: int = 10, clock: Callable[[], float] = time.monotonic):
        self._ttl = ttl_minutes * 60
        self._clock = clock
        self._lock = threading.Lock()
        self._listeners: list[Callable[[], None]] = []
        self._locked_until = 0.0
        self._new_pin()

    def _new_pin(self) -> None:
        self._pin = f"{secrets.randbelow(1_000_000):06d}"
        self._expires = self._clock() + self._ttl
        self._failures = 0

    def _maybe_rotate(self) -> None:
        if self._clock() >= self._expires:
            self._new_pin()

    def on_change(self, fn: Callable[[], None]) -> None:
        self._listeners.append(fn)

    def _notify(self) -> None:
        for fn in self._listeners:
            try:
                fn()
            except Exception:  # GUI listeners must never break PIN checks
                pass

    # ---- read by the P1 window ----
    def snapshot(self) -> dict:
        with self._lock:
            self._maybe_rotate()
            now = self._clock()
            return {
                "pin": self._pin,
                "seconds_left": max(0, int(self._expires - now)),
                "failures": self._failures,
                "locked_for": max(0, int(self._locked_until - now)),
            }

    def rotate(self) -> None:
        """The NEW PIN button on P1."""
        with self._lock:
            self._new_pin()
        self._notify()

    def check(self, pin: str) -> PinResult:
        with self._lock:
            self._maybe_rotate()
            now = self._clock()
            if now < self._locked_until:
                return PinResult(False, "locked", 0, int(self._locked_until - now) + 1)
            if hmac.compare_digest(str(pin), self._pin):
                self._failures = 0
                return PinResult(True, attempts_left=MAX_ATTEMPTS)
            self._failures += 1
            if self._failures >= MAX_ATTEMPTS:
                self._new_pin()
                self._locked_until = now + LOCK_SECONDS
                result = PinResult(False, "locked", 0, LOCK_SECONDS)
            else:
                result = PinResult(False, "wrong_pin", MAX_ATTEMPTS - self._failures)
        self._notify()
        return result
