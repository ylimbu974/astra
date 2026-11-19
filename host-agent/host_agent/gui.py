"""The PC-side screens from the wireframes, in plain Tk (ships with Python on Windows;
on Raspberry Pi OS it is the python3-tk package).

    P1  Host window: status, QR code, rotating PIN, tailnet address, Copy / New PIN / Stop
    P2  Approval popup: Allow / Deny with a 60 second auto-deny countdown
    P3  Session active: who is connected and for how long, End session / Minimize
    +   A slim always-on-top banner while a session is live

Tk is not thread-safe, so the server thread never touches widgets: it queues small
functions that the Tk thread runs every 100 ms.
"""
from __future__ import annotations

import base64
import concurrent.futures
import io
import queue
import time
import tkinter as tk
from tkinter import messagebox

import qrcode

from .pin import PinManager
from .server import AgentState
from .ui import ALLOW, DENY, TIMEOUT

BG, FG, MUTED, ACCENT, DANGER, OK = "#ffffff", "#14171c", "#69707d", "#2457d6", "#c62828", "#127a3e"
FONT = ("Segoe UI", 10)


def _fmt_secs(s: int) -> str:
    return f"{s // 60:02d}:{s % 60:02d}"


def _qr_photo(payload: str, size: int = 220) -> tk.PhotoImage:
    img = qrcode.make(payload, border=2).get_image().convert("RGB").resize((size, size))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return tk.PhotoImage(data=base64.b64encode(buf.getvalue()))


class TkUI:
    def __init__(self, state: AgentState, pins: PinManager, on_stop):
        self.state, self.pins, self.on_stop = state, pins, on_stop
        self._q: queue.Queue = queue.Queue()
        self._popup: tk.Toplevel | None = None
        self._popup_future: concurrent.futures.Future | None = None
        self._banner: tk.Toplevel | None = None
        self._session_label: str | None = None
        self._session_started = 0.0
        self._qr_payload: str | None = None
        self._qr_img = None

        self.root = tk.Tk()
        self.root.title(f"Host Agent — {state.cfg.device_name}")
        self.root.configure(bg=BG)
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self._confirm_stop)
        self._build_p1()
        self._build_p3()
        self._show_p1()
        pins.on_change(lambda: self._q.put(self._update_pin))
        self.root.after(100, self._drain)
        self.root.after(1000, self._tick)

    # ---------- thread-safe API used by the server ----------

    def ask_approval(self, kind, label, timeout):
        fut: concurrent.futures.Future = concurrent.futures.Future()
        self._q.put(lambda: self._open_popup(fut, kind, label, timeout))
        return fut

    def dismiss_approval(self):
        self._q.put(lambda: self._close_popup(None))

    def session_started(self, label):
        self._q.put(lambda: self._start_session(label))

    def session_ended(self, reason):
        self._q.put(lambda: self._end_session(reason))

    def refresh(self):
        self._q.put(self._update_status)

    def run(self):
        self.root.mainloop()

    def close(self):
        self._q.put(self.root.destroy)

    # ---------- plumbing ----------

    def _drain(self):
        while True:
            try:
                fn = self._q.get_nowait()
            except queue.Empty:
                break
            try:
                fn()
            except tk.TclError:
                pass
        self.root.after(100, self._drain)

    def _tick(self):
        self._update_pin()
        if self._session_label:
            mins = int((time.monotonic() - self._session_started) // 60)
            self.p3_title.config(text=f"SESSION ACTIVE · CONNECTED {mins} MIN · {self._session_label.upper()}")
        self.root.after(1000, self._tick)

    def _label(self, parent, text="", size=10, bold=False, color=FG, **kw):
        return tk.Label(parent, text=text, bg=BG, fg=color,
                        font=("Segoe UI", size, "bold" if bold else "normal"), **kw)

    def _button(self, parent, text, cmd, primary=False, danger=False):
        color = DANGER if danger else (ACCENT if primary else "#e9ecf1")
        fg = "#ffffff" if (primary or danger) else FG
        return tk.Button(parent, text=text, command=cmd, bg=color, fg=fg, activebackground=color,
                         activeforeground=fg, relief="flat", padx=14, pady=6, font=FONT, cursor="hand2")

    # ---------- P1 ----------

    def _build_p1(self):
        f = self.p1 = tk.Frame(self.root, bg=BG, padx=20, pady=16)
        self.status = self._label(f, size=9, bold=True, color=MUTED)
        self.status.grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 12))

        self.qr = tk.Label(f, bg=BG, text="Waiting for the\nPi registry…", fg=MUTED, width=28, height=12)
        self.qr.grid(row=1, column=0, rowspan=6, padx=(0, 20))

        self._label(f, "PAIRING PIN", 9, True, MUTED).grid(row=1, column=1, sticky="w")
        self.pin = self._label(f, "--- ---", 30, True)
        self.pin.grid(row=2, column=1, sticky="w")
        self.pin_note = self._label(f, "", 9, color=MUTED)
        self.pin_note.grid(row=3, column=1, sticky="w")
        self._label(f, "ADDRESS", 9, True, MUTED).grid(row=4, column=1, sticky="sw", pady=(14, 0))
        self.addr = self._label(f, "", 11)
        self.addr.grid(row=5, column=1, sticky="w")

        self.require = tk.BooleanVar(value=True)
        tk.Checkbutton(f, text="Require approval for every session (on, locked for MVP)", variable=self.require,
                       state="disabled", bg=BG, font=FONT).grid(row=7, column=0, columnspan=2, sticky="w", pady=(12, 8))

        row = tk.Frame(f, bg=BG)
        row.grid(row=8, column=0, columnspan=2, sticky="we")
        self._button(row, "Copy address", self._copy_address).pack(side="left")
        self._button(row, "New PIN", self.pins.rotate).pack(side="left", padx=8)
        self._button(row, "Stop host", self._confirm_stop, danger=True).pack(side="right")
        self._update_status()
        self._update_pin()

    def _address(self) -> str:
        ts, cfg = self.state.tailscale, self.state.cfg
        return cfg.tailscale_hostname or (ts.dns_name if ts else None) or (ts.ip if ts else None) or "Tailscale not found"

    def _copy_address(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self._address())

    def _update_status(self):
        ts = self.state.tailscale
        ts_text = "CONNECTED" if ts and ts.connected else "NOT CONNECTED"
        reg = "REACHABLE" if self.state.registry_ok else "UNREACHABLE"
        self.status.config(text=f"STATUS: RUNNING · TAILSCALE: {ts_text} · PI REGISTRY: {reg}",
                           fg=OK if (ts and ts.connected and self.state.registry_ok) else DANGER)
        self.addr.config(text=self._address())
        payload = self.state.qr_payload()
        if payload and payload != self._qr_payload:
            self._qr_payload = payload
            self._qr_img = _qr_photo(payload)
            self.qr.config(image=self._qr_img, text="", width=220, height=220)

    def _update_pin(self):
        snap = self.pins.snapshot()
        if snap["locked_for"]:
            self.pin.config(text="LOCKED", fg=DANGER)
            self.pin_note.config(text=f"3 wrong PINs. Pairing unlocks in {_fmt_secs(snap['locked_for'])}")
            return
        p = snap["pin"]
        self.pin.config(text=f"{p[:3]} {p[3:]}", fg=FG)
        note = f"New PIN in {_fmt_secs(snap['seconds_left'])}"
        if snap["failures"]:
            note += f" · {snap['failures']} wrong {'try' if snap['failures'] == 1 else 'tries'}"
        self.pin_note.config(text=note)

    def _confirm_stop(self):
        if self._session_label and not messagebox.askyesno("Stop host", "A session is active. Stop the host anyway?"):
            return
        if not self._session_label and not messagebox.askyesno(
                "Stop host", "Stop the Host Agent? This PC will show as offline."):
            return
        self.on_stop()

    # ---------- P3 + banner ----------

    def _build_p3(self):
        f = self.p3 = tk.Frame(self.root, bg=BG, padx=20, pady=20)
        self.p3_title = self._label(f, "", 11, True, OK)
        self.p3_title.pack(anchor="w", pady=(0, 6))
        self._label(f, "Someone is controlling this PC. A banner stays at the top of the screen while it lasts.",
                    9, color=MUTED, wraplength=420, justify="left").pack(anchor="w", pady=(0, 14))
        row = tk.Frame(f, bg=BG)
        row.pack(fill="x")
        self._button(row, "End session", self.state.end_session_from_pc, danger=True).pack(side="left")
        self._button(row, "Minimize", self.root.iconify).pack(side="right")

    def _show_p1(self):
        self.p3.pack_forget()
        self.p1.pack()

    def _start_session(self, label):
        self._session_label, self._session_started = label, time.monotonic()
        self.p1.pack_forget()
        self.p3.pack()
        self._tick_once_title()
        self._show_banner(label)

    def _tick_once_title(self):
        self.p3_title.config(text=f"SESSION ACTIVE · CONNECTED 0 MIN · {self._session_label.upper()}")

    def _end_session(self, _reason):
        self._session_label = None
        if self._banner:
            self._banner.destroy()
            self._banner = None
        self._show_p1()

    def _show_banner(self, label):
        b = self._banner = tk.Toplevel(self.root)
        b.overrideredirect(True)
        b.attributes("-topmost", True)
        b.configure(bg=DANGER)
        tk.Label(b, text=f"●  Remote control active — {label}", bg=DANGER, fg="#ffffff",
                 font=("Segoe UI", 10, "bold"), padx=12, pady=4).pack(side="left")
        tk.Button(b, text="End", command=self.state.end_session_from_pc, bg="#ffffff", fg=DANGER, relief="flat",
                  padx=10, font=("Segoe UI", 9, "bold")).pack(side="left", padx=(0, 6), pady=3)
        b.update_idletasks()
        x = (b.winfo_screenwidth() - b.winfo_reqwidth()) // 2
        b.geometry(f"+{x}+0")

    # ---------- P2 ----------

    def _open_popup(self, fut, kind, label, timeout):
        if self._popup is not None:  # server prevents this, but never stack prompts
            fut.set_result(DENY)
            return
        self._popup_future = fut
        p = self._popup = tk.Toplevel(self.root)
        p.title("Incoming connection")
        p.configure(bg=BG, padx=24, pady=18)
        p.resizable(False, False)
        p.attributes("-topmost", True)
        p.protocol("WM_DELETE_WINDOW", lambda: self._close_popup(DENY))
        what = "pair with this PC" if kind == "pairing" else "control this PC"
        self._label(p, f"A phone wants to {what}", 13, True).pack(anchor="w")
        self._label(p, label, 10, color=MUTED).pack(anchor="w", pady=(2, 12))
        countdown = self._label(p, "", 10, color=DANGER)
        countdown.pack(anchor="w", pady=(0, 14))
        row = tk.Frame(p, bg=BG)
        row.pack(fill="x")
        self._button(row, "Allow", lambda: self._close_popup(ALLOW), primary=True).pack(side="left")
        self._button(row, "Deny", lambda: self._close_popup(DENY)).pack(side="left", padx=10)

        deadline = time.monotonic() + timeout

        def tick():
            if self._popup is not p:
                return
            left = int(deadline - time.monotonic())
            if left <= 0:
                self._close_popup(TIMEOUT)
                return
            countdown.config(text=f"Auto-denies in {left // 60}:{left % 60:02d}")
            p.after(250, tick)

        tick()
        p.deiconify()
        p.lift()
        p.focus_force()
        p.bell()

    def _close_popup(self, result):
        fut, self._popup_future = self._popup_future, None
        if self._popup is not None:
            self._popup.destroy()
            self._popup = None
        if fut is not None and not fut.done():
            fut.set_result(result if result is not None else DENY)
