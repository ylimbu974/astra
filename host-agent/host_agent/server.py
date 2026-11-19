"""The Host Agent's HTTP + WebSocket server.

    GET  /health                 liveness (T-10)
    GET  /                       the web viewer page (T-22, served from ../web-viewer)
    POST /api/pair               phone pairing: PIN check, then Allow on P2 (Flow B)
    WS   /stream                 one remote session: hello -> approval -> frames + input (Flow C)
    GET  /debug/screenshot.jpg   single screenshot (T-11), off by default and only from this PC

Only one session or approval prompt runs at a time. Every session needs a fresh Allow
on the PC; there is no way to skip it in the MVP.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import time
from dataclasses import dataclass, field

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, ValidationError

from . import __version__
from .capture import PRESETS, ScreenCapturer
from .config import Config
from .desktop import os_label
from .input import Backend, InputError, InputInjector
from .pin import PinManager
from .registry_client import RegistryClient, RegistryError
from .tailscale import TailscaleInfo
from .ui import ALLOW, DENY, TIMEOUT, HostUI

log = logging.getLogger("astra.server")

HELLO_TIMEOUT = 10
KEYFRAME_SECONDS = 2.0  # resend an unchanged picture this often so the viewer knows we are alive


@dataclass
class ActiveSession:
    label: str
    session_id: int | None
    quality: str = "auto"
    started: float = field(default_factory=time.monotonic)
    bytes_sent: int = 0
    end_event: asyncio.Event = field(default_factory=asyncio.Event)
    end_reason: str | None = None

    def request_end(self, reason: str) -> None:
        if not self.end_reason:
            self.end_reason = reason
        self.end_event.set()


@dataclass
class AgentState:
    cfg: Config
    tailscale: TailscaleInfo | None = None
    device_id: int | None = None
    registry_ok: bool = False
    busy: bool = False                 # an approval prompt or a session is in progress
    session: ActiveSession | None = None
    loop: asyncio.AbstractEventLoop | None = None

    def qr_payload(self) -> str | None:
        """What B2 scans: PC name + Tailscale address. Never the PIN."""
        if self.device_id is None:
            return None
        ts = self.tailscale
        host = self.cfg.tailscale_hostname or (ts.dns_name if ts else None) or (ts.ip if ts else None)
        return json.dumps({
            "astra": 1, "device_id": self.device_id, "name": self.cfg.device_name, "host": host,
            "ip": ts.ip if ts else None, "port": self.cfg.host_port, "scheme": self.cfg.scheme,
        }, separators=(",", ":"))

    def end_session_from_pc(self) -> None:
        """P3 'End session' (called from the GUI thread)."""
        if self.session and self.loop:
            self.loop.call_soon_threadsafe(self.session.request_end, "ended_by_pc")


class PairIn(BaseModel):
    pairing_id: int
    pin: str = Field(pattern=r"^\d{6}$")
    phone_name: str = Field(min_length=1, max_length=64)


def error(status: int, code: str, detail: str, **extra) -> JSONResponse:
    return JSONResponse({"error": code, "detail": detail, **extra}, status_code=status)


async def heartbeat_once(state: AgentState, registry: RegistryClient, status: str = "online") -> None:
    cfg, ts = state.cfg, state.tailscale
    hostname = cfg.tailscale_hostname or (ts.dns_name if ts else None) or (ts.ip if ts else None) or platform.node()
    try:
        out = await registry.heartbeat(name=cfg.device_name, hostname=hostname, tailscale_ip=ts.ip if ts else None,
                                       os=os_label(),
                                       status=status, port=cfg.host_port, scheme=cfg.scheme)
        state.device_id = out["device_id"]
        state.registry_ok = True
    except RegistryError as exc:
        if state.registry_ok:
            log.warning("heartbeat failed: %s", exc)
        state.registry_ok = False


async def heartbeat_loop(state: AgentState, registry: RegistryClient, ui: HostUI) -> None:
    while True:
        await heartbeat_once(state, registry)
        ui.refresh()
        await asyncio.sleep(state.cfg.heartbeat_seconds)


def create_app(state: AgentState, ui: HostUI, pins: PinManager, registry: RegistryClient,
               capturer: ScreenCapturer, backend_factory, monitor_fn) -> FastAPI:
    """backend_factory() -> input Backend; monitor_fn() -> primary display rect."""
    cfg = state.cfg
    app = FastAPI(title="Astra Host Agent", version=__version__, docs_url=None, redoc_url=None, openapi_url=None)

    async def ask(kind: str, label: str, cancel_on: WebSocket | None = None) -> str:
        """Show P2 and wait. With a WebSocket, a close or {"type":"cancel"} from the phone cancels."""
        fut = asyncio.wrap_future(ui.ask_approval(kind, label, cfg.approval_timeout_seconds))
        waiters = {fut}
        recv = None
        if cancel_on is not None:
            recv = asyncio.ensure_future(cancel_on.receive())
            waiters.add(recv)
        done, _ = await asyncio.wait(waiters, timeout=cfg.approval_timeout_seconds + 5,
                                     return_when=asyncio.FIRST_COMPLETED)
        if fut in done:
            if recv:
                recv.cancel()
            return fut.result()
        ui.dismiss_approval()
        fut.cancel()
        if recv in done:
            return "cancelled"
        if recv:
            recv.cancel()
        return TIMEOUT

    # ---------- basics ----------

    @app.get("/health")
    def health():
        return {"ok": True, "service": "astra-host", "version": __version__}

    @app.get("/api/info")
    def info():
        return {"name": cfg.device_name, "device_id": state.device_id, "scheme": cfg.scheme,
                "registry_reachable": state.registry_ok, "busy": state.busy, "version": __version__}

    if cfg.enable_debug_screenshot:
        @app.get("/debug/screenshot.jpg")
        async def screenshot(request: Request):
            if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
                return error(403, "local_only", "Debug screenshots only work from this PC.")
            frame = await capturer.grab("auto")
            return Response(frame.jpeg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})

    # ---------- pairing (Flow B: B3 -> P2 -> B4) ----------

    @app.post("/api/pair")
    async def pair(request: Request):
        try:
            body = PairIn.model_validate(await request.json())
        except (ValidationError, ValueError):
            return error(422, "invalid_request", "Send pairing_id, a 6-digit pin and phone_name.")
        if state.device_id is None:
            return error(503, "registry_unreachable", "This PC has not reached the Pi registry yet.")
        result = pins.check(body.pin)
        if not result.ok:
            await registry.audit(state.device_id, "pin_failed", f"pairing={body.pairing_id} {result.error}")
            if result.error == "locked":
                return error(423, "locked", "Too many wrong PINs. The PC made a new PIN; try again in 5 minutes.",
                             retry_after=result.retry_after)
            return error(401, "wrong_pin", "That PIN did not match.", attempts_left=result.attempts_left)
        if state.busy:
            return error(409, "busy", "The PC is already showing a prompt or running a session.")
        state.busy = True
        try:
            decision = await ask("pairing", body.phone_name)
            if decision != ALLOW:
                try:
                    await registry.reject_pairing(body.pairing_id, state.device_id, decision)
                except RegistryError:
                    pass
                return error(403, "not_approved", "The PC did not approve.", reason=decision)
            try:
                await registry.approve_pairing(body.pairing_id, state.device_id, body.pin)
            except RegistryError as exc:
                return error(exc.status or 502, exc.error, exc.detail)
            return {"status": "approved", "device_id": state.device_id, "name": cfg.device_name}
        finally:
            state.busy = False
            ui.refresh()

    # ---------- session (Flow C) ----------

    @app.websocket("/stream")
    async def stream(ws: WebSocket):
        await ws.accept()

        async def fail(code: str, detail: str, **extra):
            try:
                await ws.send_json({"type": "error", "error": code, "detail": detail, **extra})
                await ws.close(code=4000)
            except Exception:
                pass

        try:
            hello = json.loads(await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT))
        except (asyncio.TimeoutError, WebSocketDisconnect, ValueError, KeyError):
            return await fail("bad_hello", "The viewer did not say hello.")
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            return await fail("bad_hello", "The first message must be a hello.")
        if state.busy:
            return await fail("busy", "This PC is already in a session or showing a prompt.")
        if state.device_id is None and hello.get("token"):
            return await fail("registry_unreachable", "This PC cannot reach the Pi registry right now.")

        state.busy = True
        session: ActiveSession | None = None
        try:
            quality = hello.get("quality") if hello.get("quality") in PRESETS else "auto"
            token, pin = hello.get("token"), hello.get("pin")
            session_id, label = None, None

            # 1. Who is this? A paired phone (token, checked by the Pi) or a browser (PIN shown on this PC).
            if token:
                try:
                    out = await registry.open_session(state.device_id, str(token))
                except RegistryError as exc:
                    return await fail(exc.error, exc.detail)
                session_id, label = out["session_id"], out["phone_name"]
            elif pin:
                result = pins.check(str(pin))
                if not result.ok:
                    await registry.audit(state.device_id, "pin_failed", f"session {result.error}")
                    return await fail(result.error, "That PIN did not match." if result.error == "wrong_pin"
                                      else "Too many wrong PINs. Try again in 5 minutes.",
                                      attempts_left=result.attempts_left, retry_after=result.retry_after)
                label = str(hello.get("phone_name") or "Browser")[:64] + " (PIN)"
                if state.device_id is not None:
                    try:
                        out = await registry.open_session(state.device_id, None, label)
                        session_id = out["session_id"]
                    except RegistryError as exc:
                        log.warning("could not record browser session: %s", exc)
            else:
                return await fail("auth_required", "Enter the PIN shown on the PC.")

            # 2. P2: the person at the PC must allow it. Phone shows C1 meanwhile.
            #    Exception: AUTO_ALLOW_PAIRED_PHONES=1 lets a phone the Pi says is paired straight in
            #    (unattended Pi, nobody at its screen). PIN/browser sessions always ask.
            if token and cfg.auto_allow_paired:
                decision = ALLOW
                await registry.audit(state.device_id, "auto_allowed", f"session for {label}")
            else:
                await ws.send_json({"type": "waiting_approval", "timeout": cfg.approval_timeout_seconds,
                                    "name": cfg.device_name})
                decision = await ask("session", label, cancel_on=ws)
            if decision != ALLOW:
                if session_id:
                    try:
                        await registry.session_event(session_id, "denied", decision)
                        await registry.close_session(session_id, decision if decision != DENY else "denied", 0)
                    except RegistryError:
                        pass
                if decision != "cancelled":
                    await ws.send_json({"type": "not_approved", "reason": decision})
                    await ws.close(code=4003)
                return

            # 3. C2: stream frames out, take input in.
            if session_id:
                try:
                    await registry.session_event(session_id, "approved")
                except RegistryError:
                    pass
            session = ActiveSession(label=label, session_id=session_id, quality=quality)
            state.session = session
            monitor = monitor_fn()
            injector = InputInjector(backend_factory(), monitor)
            await ws.send_json({"type": "approved", "name": cfg.device_name,
                                "screen": {"width": monitor["width"], "height": monitor["height"]}})
            ui.session_started(label)
            await run_session(ws, session, injector)
        finally:
            state.busy = False
            state.session = None
            if session:
                ui.session_ended(session.end_reason or "ended")
                if session.session_id:
                    try:
                        await registry.close_session(session.session_id, session.end_reason or "ended",
                                                     session.bytes_sent)
                    except RegistryError as exc:
                        log.warning("could not close session record: %s", exc)
            ui.refresh()

    async def run_session(ws: WebSocket, session: ActiveSession, injector: InputInjector) -> None:
        loop = asyncio.get_running_loop()

        async def send_frames():
            interval = 1.0 / cfg.max_fps
            last, last_sent = None, 0.0
            while True:
                t0 = loop.time()
                frame = await capturer.grab(session.quality)
                if frame.jpeg != last or t0 - last_sent >= KEYFRAME_SECONDS:
                    await ws.send_bytes(frame.jpeg)
                    session.bytes_sent += len(frame.jpeg)
                    last, last_sent = frame.jpeg, t0
                await asyncio.sleep(max(0.0, interval - (loop.time() - t0)))

        async def receive_input():
            while True:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    session.request_end("connection_lost")
                    return
                text = msg.get("text")
                if text is None:
                    continue
                try:
                    ev = json.loads(text)
                except ValueError:
                    continue
                if not isinstance(ev, dict):
                    continue
                kind = ev.get("type")
                if kind == "bye":
                    session.request_end("ended_by_phone")
                    return
                if kind == "ping":
                    await ws.send_json({"type": "pong", "t": ev.get("t")})
                    continue
                if kind == "quality":
                    if ev.get("value") in PRESETS:
                        session.quality = ev["value"]
                    continue
                try:
                    injector.handle(ev)
                except InputError as exc:
                    await ws.send_json({"type": "warn", "detail": str(exc)})

        tasks = [asyncio.create_task(send_frames()), asyncio.create_task(receive_input()),
                 asyncio.create_task(session.end_event.wait())]
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                if t.exception() and not session.end_reason:
                    session.request_end("connection_lost")
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            injector.release_all()
            if session.end_reason == "ended_by_pc":
                try:
                    await ws.send_json({"type": "ended", "reason": "ended_by_pc"})
                except Exception:
                    pass
            try:
                await ws.close(code=1000)
            except Exception:
                pass

    # The viewer files last, so the routes above win.
    if os.path.isdir(cfg.web_viewer_dir):
        app.mount("/", StaticFiles(directory=cfg.web_viewer_dir, html=True), name="viewer")
    else:
        log.warning("web viewer folder not found: %s", cfg.web_viewer_dir)

    return app
