"""Phone-to-phone relay (relay.py), against a real uvicorn server and real WebSockets.

A small fake "host phone" here does what the Android app's ShareService does: register on
/relay/host, answer pairing requests, and open /relay/host/session/<sid> when asked.
"""
import asyncio
import json
import socket
import threading
import time

import httpx
import pytest
import uvicorn
import websockets

from registry.config import Settings
from registry.main import create_app

KEY = "test-host-key"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture()
def server(tmp_path):
    viewer = tmp_path / "web-viewer"
    viewer.mkdir()
    (viewer / "index.html").write_text("<html>viewer</html>")
    (viewer / "viewer.js").write_text("// js")
    (viewer / "viewer.css").write_text("/* css */")
    settings = Settings(f"sqlite:///{tmp_path}/r.db", KEY, "me@example.com", 720, 90, str(viewer))
    port = free_port()
    srv = uvicorn.Server(uvicorn.Config(create_app(settings), host="127.0.0.1", port=port, log_level="warning"))
    t = threading.Thread(target=srv.run, daemon=True)
    t.start()
    for _ in range(100):
        if srv.started:
            break
        time.sleep(0.05)
    yield f"127.0.0.1:{port}"
    srv.should_exit = True
    t.join(5)


class FakeHostPhone:
    """Approves pairings whose PIN is 111111 and streams two frames per session."""

    def __init__(self, base: str, name="Pixel 8", install_id="abcdef123456"):
        self.base, self.name, self.install_id = base, name, install_id
        self.device_id = None
        self.inputs: list[dict] = []
        self.tasks: list[asyncio.Task] = []

    async def connect(self):
        self.ctl = await websockets.connect(f"ws://{self.base}/relay/host", additional_headers={"X-Host-Key": KEY})
        await self.ctl.send(json.dumps({"type": "register", "name": self.name, "install_id": self.install_id,
                                        "os": "Android 14"}))
        reg = json.loads(await self.ctl.recv())
        assert reg["type"] == "registered"
        self.device_id = reg["device_id"]
        self.tasks.append(asyncio.create_task(self.loop()))

    async def loop(self):
        async for raw in self.ctl:
            msg = json.loads(raw)
            if msg["type"] == "http":
                ok = msg["body"].get("pin") == "111111"
                await self.ctl.send(json.dumps({
                    "type": "http_result", "rid": msg["rid"], "status": 200 if ok else 401,
                    "body": {"status": "approved"} if ok else {"error": "wrong_pin", "detail": "nope", "attempts_left": 2}}))
            elif msg["type"] == "session":
                self.tasks.append(asyncio.create_task(self.session(msg["sid"])))

    async def session(self, sid):
        async with websockets.connect(f"ws://{self.base}/relay/host/session/{sid}",
                                      additional_headers={"X-Host-Key": KEY}) as s:
            hello = json.loads(await s.recv())
            assert hello["type"] == "hello"
            await s.send(json.dumps({"type": "approved", "name": self.name, "platform": "android",
                                     "screen": {"width": 1080, "height": 2400}}))
            await s.send(b"\xff\xd8frame1")
            async for raw in s:
                ev = json.loads(raw)
                if ev["type"] == "bye":
                    break
                self.inputs.append(ev)
                await s.send(b"\xff\xd8frame2")

    async def close(self):
        await self.ctl.close()
        for t in self.tasks:
            t.cancel()


def run(coro):
    return asyncio.run(coro)


def test_host_needs_key(server):
    async def go():
        with pytest.raises(websockets.exceptions.InvalidStatus):
            async with websockets.connect(f"ws://{server}/relay/host", additional_headers={"X-Host-Key": "wrong"}) as ws:
                await ws.recv()
    run(go())


def test_phone_registers_and_lists_as_relay_device(server):
    async def go():
        host = FakeHostPhone(server)
        await host.connect()
        async with httpx.AsyncClient() as http:
            devices = (await http.get(f"http://{server}/api/devices")).json()
            assert len(devices) == 1
            d = devices[0]
            assert d["status"] == "online" and d["relay"] is True and d["os"] == "Android 14"
            assert d["hostname"] == "127.0.0.1" and d["path"] == f"/relay/d/{host.device_id}"
            assert d["viewer_url"] == f"http://{server}/relay/d/{host.device_id}/"
            page = (await http.get(f"http://{server}/")).text
            assert "Android 14 phone, through the Pi" in page
            info = (await http.get(f"http://{server}/relay/d/{host.device_id}/api/info")).json()
            assert info["name"] == "Pixel 8" and info["online"] is True
            # the viewer page is served for the phone, and only the three viewer files
            assert (await http.get(f"http://{server}/relay/d/{host.device_id}/")).text == "<html>viewer</html>"
            assert (await http.get(f"http://{server}/relay/d/{host.device_id}/viewer.js")).status_code == 200
            assert (await http.get(f"http://{server}/relay/d/{host.device_id}/..%2Fr.db")).status_code == 404
            r = await http.get(f"http://{server}/relay/d/{host.device_id}?app=1", follow_redirects=False)
            assert r.status_code == 307 and r.headers["location"] == f"/relay/d/{host.device_id}/?app=1"
            await host.close()
            await asyncio.sleep(0.3)
            devices = (await http.get(f"http://{server}/api/devices")).json()
            assert devices[0]["status"] == "offline"
    run(go())


def test_same_phone_reconnecting_keeps_its_device_id(server):
    async def go():
        a = FakeHostPhone(server)
        await a.connect()
        b = FakeHostPhone(server, name="Pixel 8 renamed")
        await b.connect()
        assert a.device_id == b.device_id
        await asyncio.sleep(0.2)
        async with httpx.AsyncClient() as http:
            d = (await http.get(f"http://{server}/api/devices")).json()[0]
            assert d["name"] == "Pixel 8 renamed" and d["status"] == "online"  # old link closing must not flip it offline
        await a.close()
        await b.close()
    run(go())


def test_pairing_is_forwarded_to_the_phone(server):
    async def go():
        host = FakeHostPhone(server)
        await host.connect()
        async with httpx.AsyncClient() as http:
            url = f"http://{server}/relay/d/{host.device_id}/api/pair"
            ok = await http.post(url, json={"pairing_id": 1, "pin": "111111", "phone_name": "Galaxy"})
            assert ok.status_code == 200 and ok.json() == {"status": "approved"}
            bad = await http.post(url, json={"pairing_id": 1, "pin": "222222", "phone_name": "Galaxy"})
            assert bad.status_code == 401 and bad.json()["error"] == "wrong_pin"
            junk = await http.post(url, content=b"not json")
            assert junk.status_code == 422
        await host.close()
    run(go())


def test_session_is_piped_both_ways(server):
    async def go():
        host = FakeHostPhone(server)
        await host.connect()
        async with websockets.connect(f"ws://{server}/relay/d/{host.device_id}/stream") as viewer:
            await viewer.send(json.dumps({"type": "hello", "token": "t", "phone_name": "Galaxy"}))
            approved = json.loads(await viewer.recv())
            assert approved["type"] == "approved" and approved["platform"] == "android"
            assert await viewer.recv() == b"\xff\xd8frame1"
            await viewer.send(json.dumps({"type": "tap", "x": 0.5, "y": 0.5, "button": "left"}))
            assert await viewer.recv() == b"\xff\xd8frame2"
            await viewer.send(json.dumps({"type": "nav", "action": "home"}))
            assert await viewer.recv() == b"\xff\xd8frame2"
            await viewer.send(json.dumps({"type": "bye"}))
            with pytest.raises(websockets.exceptions.ConnectionClosed):
                await asyncio.wait_for(viewer.recv(), 3)
        assert host.inputs == [{"type": "tap", "x": 0.5, "y": 0.5, "button": "left"},
                               {"type": "nav", "action": "home"}]
        await host.close()
    run(go())


def test_viewer_gets_plain_error_when_phone_is_not_sharing(server):
    async def go():
        host = FakeHostPhone(server)
        await host.connect()
        dev = host.device_id
        await host.close()
        await asyncio.sleep(0.2)
        async with websockets.connect(f"ws://{server}/relay/d/{dev}/stream") as viewer:
            msg = json.loads(await viewer.recv())
            assert msg["type"] == "error" and msg["error"] == "host_offline"
        async with httpx.AsyncClient() as http:
            r = await http.post(f"http://{server}/relay/d/{dev}/api/pair", json={"pin": "111111"})
            assert r.status_code == 503 and r.json()["error"] == "host_offline"
    run(go())
