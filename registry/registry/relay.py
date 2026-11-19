"""Phone-to-phone relay: an Android phone shares its screen through the Pi.

A PC or the Pi runs the Python host agent, which the viewer reaches directly. An Android
phone can't easily accept incoming connections, so it dials *out* to the Pi instead and
the Pi passes traffic between the two phones:

    host phone  ──WS /relay/host──────────────▶  Pi   (control link: register, heartbeats,
                                                        "a viewer wants a session", pairing)
    host phone  ──WS /relay/host/session/<sid>─▶  Pi ◀── WS /relay/d/<id>/stream ── viewer phone
                                                  (the Pi pipes these two sockets together)

The viewer side speaks exactly the protocol of a host agent (hello -> approval -> JPEG
frames + JSON input), so the app's viewer works unchanged. The host phone does the PIN
check, the Allow prompt and the token check itself, through the normal registry API.
Frames only pass through the Pi's memory; nothing is stored.

Relay devices are kept in the normal `devices` table. Their hostname is "relay.<install id>"
(never a real DNS name), which is how the registry tells them apart without a schema change.
"""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import re
import secrets
from dataclasses import dataclass, field

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from sqlalchemy import select

from .models import Device, utcnow

log = logging.getLogger("astra.relay")

RELAY_PREFIX = "relay."
HELLO_TIMEOUT = 10          # seconds for a host phone to register
SESSION_DIAL_TIMEOUT = 15   # seconds for the host phone to open its side of a session
PAIR_TIMEOUT = 90           # PIN check + Allow on the phone (60 s) + slack
MAX_WAITING_PER_DEVICE = 3
MAX_INPUT_MESSAGE = 64 * 1024
VIEWER_FILES = {"index.html": "text/html", "viewer.js": "text/javascript", "viewer.css": "text/css"}
INSTALL_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def is_relay_hostname(hostname: str) -> bool:
    return hostname.startswith(RELAY_PREFIX)


@dataclass
class HostLink:
    ws: WebSocket
    device_id: int
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def send(self, obj: dict) -> None:
        async with self.lock:
            await self.ws.send_text(json.dumps(obj))


@dataclass
class Waiting:
    device_id: int
    future: asyncio.Future


@dataclass
class RelayState:
    links: dict[int, HostLink] = field(default_factory=dict)
    waiting: dict[str, Waiting] = field(default_factory=dict)


def add_relay_routes(app: FastAPI, *, settings, session_scope, owner_id, audit) -> RelayState:
    state = RelayState()
    viewer_dir = settings.web_viewer_dir

    def key_ok(ws: WebSocket) -> bool:
        key = ws.headers.get("x-host-key")
        return bool(key) and hmac.compare_digest(key, settings.host_api_key)

    def set_status(device_id: int, status: str) -> None:
        with session_scope() as db:
            dev = db.get(Device, device_id)
            if dev:
                dev.status = status
                dev.last_seen_at = utcnow()

    def relay_device(device_id: int) -> Device | None:
        with session_scope() as db:
            dev = db.get(Device, device_id)
            return dev if dev and is_relay_hostname(dev.hostname) else None

    # ---------- the host phone's control link ----------

    @app.websocket("/relay/host")
    async def relay_host(ws: WebSocket):
        if not key_ok(ws):
            await ws.close(code=4401)
            return
        await ws.accept()
        try:
            hello = json.loads(await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT))
        except (asyncio.TimeoutError, WebSocketDisconnect, ValueError):
            await ws.close(code=4000)
            return
        name = str(hello.get("name") or "").strip()[:64] if isinstance(hello, dict) else ""
        install_id = str(hello.get("install_id") or "") if isinstance(hello, dict) else ""
        if hello.get("type") != "register" or not name or not INSTALL_ID.match(install_id):
            await ws.send_text(json.dumps({"type": "error", "error": "bad_register",
                                           "detail": "Send type=register with a name and install_id."}))
            await ws.close(code=4000)
            return
        os_label = str(hello.get("os") or "Android")[:64]
        hostname = RELAY_PREFIX + install_id
        with session_scope() as db:
            dev = db.scalar(select(Device).where(Device.hostname == hostname))
            if not dev:
                dev = Device(owner_id=owner_id(db), hostname=hostname, name=name)
                db.add(dev)
                db.flush()
                audit(db, "device_registered", dev.id, f"{name} (phone, via relay)")
            dev.name, dev.os, dev.tailscale_ip = name, os_label, None
            dev.port, dev.scheme, dev.status = 0, "http", "online"
            dev.last_seen_at = utcnow()
            db.flush()
            device_id = dev.id

        old = state.links.get(device_id)
        link = HostLink(ws, device_id)
        state.links[device_id] = link
        if old:  # the same phone reconnected; the old socket is dead or about to be
            try:
                await old.ws.close(code=4001)
            except Exception:
                pass
        await link.send({"type": "registered", "device_id": device_id, "name": name})
        log.info("host phone %s (%s) connected", name, device_id)
        try:
            while True:
                text = await ws.receive_text()
                try:
                    msg = json.loads(text)
                except ValueError:
                    continue
                if not isinstance(msg, dict):
                    continue
                kind = msg.get("type")
                if kind == "heartbeat":
                    set_status(device_id, "online")
                    await link.send({"type": "heartbeat_ok"})
                elif kind == "http_result":
                    fut = link.pending.pop(str(msg.get("rid")), None)
                    if fut and not fut.done():
                        fut.set_result(msg)
                elif kind == "offline":  # "Stop sharing" on the phone
                    break
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            for fut in link.pending.values():
                if not fut.done():
                    fut.cancel()
            if state.links.get(device_id) is link:
                del state.links[device_id]
                set_status(device_id, "offline")
            log.info("host phone %s disconnected", device_id)
            try:
                await ws.close()
            except Exception:
                pass

    # ---------- the host phone's side of one session ----------

    @app.websocket("/relay/host/session/{sid}")
    async def relay_host_session(ws: WebSocket, sid: str):
        if not key_ok(ws):
            await ws.close(code=4401)
            return
        entry = state.waiting.get(sid)
        if not entry or entry.future.done():
            await ws.close(code=4404)
            return
        await ws.accept()
        finished = asyncio.Event()
        entry.future.set_result((ws, finished))
        await finished.wait()  # the viewer's handler pipes the two sockets, then sets this

    # ---------- what the viewer phone talks to ----------

    def offline_error() -> JSONResponse:
        return JSONResponse({"error": "host_offline",
                             "detail": "That phone is not sharing right now. Open Astra on it and tap Start sharing."},
                            status_code=503)

    @app.get("/relay/d/{device_id}/api/info")
    def relay_info(device_id: int):
        dev = relay_device(device_id)
        if not dev:
            return JSONResponse({"error": "unknown_device", "detail": "No such phone."}, status_code=404)
        return {"name": dev.name, "device_id": dev.id, "scheme": "http", "registry_reachable": True,
                "online": device_id in state.links, "platform": "android"}

    @app.post("/relay/d/{device_id}/api/pair")
    async def relay_pair(device_id: int, request: Request):
        link = state.links.get(device_id)
        if not link:
            return offline_error()
        raw = await request.body()
        try:
            body = json.loads(raw) if len(raw) <= 4096 else None
        except ValueError:
            body = None
        if not isinstance(body, dict):
            return JSONResponse({"error": "invalid_request", "detail": "Send pairing_id, a 6-digit pin and phone_name."},
                                status_code=422)
        rid = secrets.token_hex(8)
        fut = asyncio.get_running_loop().create_future()
        link.pending[rid] = fut
        try:
            await link.send({"type": "http", "rid": rid, "method": "POST", "path": "/api/pair", "body": body})
            result = await asyncio.wait_for(fut, PAIR_TIMEOUT)
        except (asyncio.TimeoutError, asyncio.CancelledError, RuntimeError, WebSocketDisconnect):
            return JSONResponse({"error": "host_offline", "detail": "The phone stopped answering."}, status_code=504)
        finally:
            link.pending.pop(rid, None)
        status = result.get("status")
        out = result.get("body")
        return JSONResponse(out if isinstance(out, dict) else {},
                            status_code=status if isinstance(status, int) and 200 <= status < 600 else 502)

    @app.websocket("/relay/d/{device_id}/stream")
    async def relay_stream(viewer: WebSocket, device_id: int):
        await viewer.accept()

        async def fail(code: str, detail: str):
            try:
                await viewer.send_text(json.dumps({"type": "error", "error": code, "detail": detail}))
                await viewer.close(code=4000)
            except Exception:
                pass

        link = state.links.get(device_id)
        if not link:
            return await fail("host_offline", "That phone is not sharing right now. Open Astra on it and tap Start sharing.")
        if sum(1 for w in state.waiting.values() if w.device_id == device_id) >= MAX_WAITING_PER_DEVICE:
            return await fail("busy", "That phone is busy. Try again in a moment.")
        sid = secrets.token_urlsafe(16)
        fut = asyncio.get_running_loop().create_future()
        state.waiting[sid] = Waiting(device_id, fut)
        try:
            await link.send({"type": "session", "sid": sid})
            host, finished = await asyncio.wait_for(fut, SESSION_DIAL_TIMEOUT)
        except (asyncio.TimeoutError, RuntimeError, WebSocketDisconnect):
            return await fail("host_offline", "The phone did not answer. Check that it is still sharing.")
        finally:
            state.waiting.pop(sid, None)
        try:
            await pipe(viewer, host)
        finally:
            finished.set()

    async def pipe(viewer: WebSocket, host: WebSocket) -> None:
        async def forward(src: WebSocket, dst: WebSocket, limit: int | None):
            while True:
                msg = await src.receive()
                if msg["type"] == "websocket.disconnect":
                    return
                text, data = msg.get("text"), msg.get("bytes")
                if text is not None:
                    if limit is None or len(text) <= limit:
                        await dst.send_text(text)
                elif data is not None:
                    if limit is None or len(data) <= limit:
                        await dst.send_bytes(data)

        tasks = [asyncio.create_task(forward(viewer, host, MAX_INPUT_MESSAGE)),
                 asyncio.create_task(forward(host, viewer, None))]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            for s in (viewer, host):
                try:
                    await s.close(code=1000)
                except Exception:
                    pass

    # ---------- the viewer page, served from the Pi for phone hosts ----------

    @app.get("/relay/d/{device_id}")
    def relay_page_redirect(device_id: int, request: Request):
        q = f"?{request.url.query}" if request.url.query else ""
        return RedirectResponse(f"/relay/d/{device_id}/{q}", status_code=307)

    @app.get("/relay/d/{device_id}/")
    def relay_page(device_id: int):
        return viewer_file(device_id, "index.html")

    @app.get("/relay/d/{device_id}/{name}")
    def relay_asset(device_id: int, name: str):
        return viewer_file(device_id, name)

    def viewer_file(device_id: int, name: str):
        if name not in VIEWER_FILES or not relay_device(device_id):
            return JSONResponse({"error": "not_found", "detail": "Not found."}, status_code=404)
        path = os.path.join(viewer_dir, name)
        if not os.path.isfile(path):
            return JSONResponse({"error": "viewer_missing",
                                 "detail": f"The web viewer files are not at {viewer_dir}. See docs/SETUP.md."},
                                status_code=500)
        return FileResponse(path, media_type=VIEWER_FILES[name], headers={"Cache-Control": "no-cache"})

    return state
