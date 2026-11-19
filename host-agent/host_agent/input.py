"""Mouse and keyboard injection (Kanban T-13 to T-15).

The phone sends JSON events with normalized 0-1 coordinates (Sketch, Section 4), e.g.
    {"type": "tap", "x": 0.42, "y": 0.31}
and this module maps them onto the PC's primary display. Anything that does not match
the small whitelist below is rejected, so a client cannot ask for arbitrary actions.
"""
from __future__ import annotations

import logging
import math
from typing import Protocol

log = logging.getLogger("astra.input")

BUTTONS = {"left", "right", "middle"}
MODIFIERS = {"ctrl", "alt", "shift", "win"}
KEYS = {
    "enter", "backspace", "tab", "esc", "delete", "insert", "home", "end", "page_up", "page_down",
    "up", "down", "left", "right", "space", "caps_lock", "print_screen", "menu", "win",
    *(f"f{i}" for i in range(1, 13)),
}
MAX_TEXT = 512


class InputError(ValueError):
    pass


class Backend(Protocol):
    def move_to(self, x: int, y: int) -> None: ...
    def press(self, button: str) -> None: ...
    def release(self, button: str) -> None: ...
    def click(self, button: str, count: int) -> None: ...
    def scroll(self, dx: int, dy: int) -> None: ...
    def type_text(self, text: str) -> None: ...
    def key(self, key: str, mods: list[str]) -> None: ...
    def position(self) -> tuple[int, int]: ...


class PynputBackend:
    def __init__(self):
        from pynput import keyboard, mouse  # lazy: needs a desktop session
        self._kb_mod, self._mouse_mod = keyboard, mouse
        self._mouse = mouse.Controller()
        self._kb = keyboard.Controller()
        K = keyboard.Key
        self._keys = {
            "enter": K.enter, "backspace": K.backspace, "tab": K.tab, "esc": K.esc, "delete": K.delete,
            "insert": K.insert, "home": K.home, "end": K.end, "page_up": K.page_up, "page_down": K.page_down,
            "up": K.up, "down": K.down, "left": K.left, "right": K.right, "space": K.space,
            "caps_lock": K.caps_lock, "print_screen": K.print_screen, "menu": K.menu,
            "ctrl": K.ctrl, "alt": K.alt, "shift": K.shift, "win": K.cmd,
            **{f"f{i}": getattr(K, f"f{i}") for i in range(1, 13)},
        }
        self._buttons = {"left": mouse.Button.left, "right": mouse.Button.right, "middle": mouse.Button.middle}

    def move_to(self, x, y):
        self._mouse.position = (x, y)

    def press(self, button):
        self._mouse.press(self._buttons[button])

    def release(self, button):
        self._mouse.release(self._buttons[button])

    def click(self, button, count):
        self._mouse.click(self._buttons[button], count)

    def scroll(self, dx, dy):
        self._mouse.scroll(dx, dy)

    def type_text(self, text):
        self._kb.type(text)

    def key(self, key, mods):
        target = self._keys.get(key, key)  # single characters (e.g. "c" for Ctrl+C) pass through
        held = [self._keys[m] for m in mods]
        for m in held:
            self._kb.press(m)
        try:
            self._kb.press(target)
            self._kb.release(target)
        finally:
            for m in reversed(held):
                self._kb.release(m)

    def position(self):
        x, y = self._mouse.position
        return int(x), int(y)


class InputInjector:
    def __init__(self, backend: Backend, monitor: dict):
        """monitor: {"left", "top", "width", "height"} of the primary display in physical pixels."""
        self.backend = backend
        self.monitor = monitor
        self._scroll_acc = [0.0, 0.0]
        self._held: set[str] = set()

    def _point(self, ev: dict) -> tuple[int, int]:
        try:
            x, y = float(ev["x"]), float(ev["y"])
        except (KeyError, TypeError, ValueError) as exc:
            raise InputError("x and y must be numbers from 0 to 1") from exc
        if not (math.isfinite(x) and math.isfinite(y)):
            raise InputError("x and y must be finite")
        x, y = min(1.0, max(0.0, x)), min(1.0, max(0.0, y))
        m = self.monitor
        return (m["left"] + round(x * (m["width"] - 1)), m["top"] + round(y * (m["height"] - 1)))

    @staticmethod
    def _button(ev: dict) -> str:
        b = ev.get("button", "left")
        if b not in BUTTONS:
            raise InputError(f"unknown button {b!r}")
        return b

    def handle(self, ev: dict) -> None:
        t = ev.get("type")
        b = self.backend
        if t == "move":
            b.move_to(*self._point(ev))
        elif t == "tap":
            count = int(ev.get("count", 1))
            if count not in (1, 2, 3):
                raise InputError("count must be 1, 2 or 3")
            if "x" in ev:
                b.move_to(*self._point(ev))
            b.click(self._button(ev), count)
        elif t == "down":
            button = self._button(ev)
            if "x" in ev:
                b.move_to(*self._point(ev))
            b.press(button)
            self._held.add(button)
        elif t == "up":
            button = self._button(ev)
            if "x" in ev:
                b.move_to(*self._point(ev))
            b.release(button)
            self._held.discard(button)
        elif t == "scroll":
            # dx / dy are in wheel notches; fractions add up across events.
            try:
                self._scroll_acc[0] += max(-20.0, min(20.0, float(ev.get("dx", 0))))
                self._scroll_acc[1] += max(-20.0, min(20.0, float(ev.get("dy", 0))))
            except (TypeError, ValueError) as exc:
                raise InputError("dx and dy must be numbers") from exc
            sx, sy = int(self._scroll_acc[0]), int(self._scroll_acc[1])
            if sx or sy:
                self._scroll_acc[0] -= sx
                self._scroll_acc[1] -= sy
                b.scroll(sx, sy)
        elif t == "text":
            text = ev.get("text")
            if not isinstance(text, str) or not text or len(text) > MAX_TEXT:
                raise InputError("text must be 1-512 characters")
            b.type_text(text)
        elif t == "key":
            key = ev.get("key")
            mods = ev.get("mods") or []
            if not isinstance(mods, list) or any(m not in MODIFIERS for m in mods):
                raise InputError("mods must be a list of ctrl / alt / shift / win")
            if key not in KEYS and not (isinstance(key, str) and len(key) == 1 and key.isprintable()):
                raise InputError(f"unknown key {key!r}")
            b.key(key, list(dict.fromkeys(mods)))
        elif t == "cad":
            # Windows does not let a normal app inject the real Ctrl+Alt+Del (the secure
            # attention sequence). The closest safe equivalent is Ctrl+Shift+Esc, which
            # opens Task Manager. See docs/RUNBOOK.md.
            b.key("esc", ["ctrl", "shift"])
        else:
            raise InputError(f"unknown event type {t!r}")

    def release_all(self) -> None:
        """Called when a session ends so no mouse button is left stuck down."""
        for button in list(self._held):
            try:
                self.backend.release(button)
            except Exception:
                pass
        self._held.clear()
