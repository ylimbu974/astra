"""Host Agent tests that run without Windows, a screen or Tailscale.

The real Pi registry app is wired in through httpx's ASGI transport, so pairing and
sessions are exercised end to end: phone -> Pi -> PC -> P2 approval -> Pi.
"""
import concurrent.futures
import json
import sys
import time
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "registry"))

from registry.config import Settings  # noqa: E402
from registry.main import create_app as create_registry  # noqa: E402

from host_agent.capture import Frame, ScreenCapturer  # noqa: E402
from host_agent.config import Config  # noqa: E402
from host_agent.input import InputError, InputInjector  # noqa: E402
from host_agent.pin import PinManager  # noqa: E402
from host_agent.registry_client import RegistryClient  # noqa: E402
from host_agent.server import AgentState, create_app, heartbeat_once  # noqa: E402
from host_agent.tailscale import TailscaleInfo  # noqa: E402
from host_agent.ui import ALLOW, DENY, TIMEOUT  # noqa: E402

MONITOR = {"left": 0, "top": 0, "width": 1920, "height": 1080}


class FakeUI:
    def __init__(self, answer=ALLOW, delay=0.0):
        self.answer, self.delay, self.events = answer, delay, []

    def ask_approval(self, kind, label, timeout):
        self.events.append(("ask", kind, label))
        fut = concurrent.futures.Future()
        if self.answer is None:  # never answers (tests cancel)
            return fut
        if self.delay:
            import threading
            threading.Timer(self.delay, lambda: fut.done() or fut.set_result(self.answer)).start()
        else:
            fut.set_result(self.answer)
        return fut

    def dismiss_approval(self):
        self.events.append(("dismiss",))

    def session_started(self, label):
        self.events.append(("started", label))

    def session_ended(self, reason):
        self.events.append(("ended", reason))

    def refresh(self):
        pass


class FakeBackend:
    def __init__(self):
        self.calls = []

    def __getattr__(self, name):
        return lambda *a: self.calls.append((name, *a))


class FakeCapturer(ScreenCapturer):
    def __init__(self):
        super().__init__(70)
        self.n = 0

    async def grab(self, preset="auto"):
        self.n += 1
        img = Image.new("RGB", (320, 180), (self.n % 255, 40, 90))
        return self.encode(img, preset)


@pytest.fixture()
def env(tmp_path):
    reg_app = create_registry(Settings(f"sqlite:///{tmp_path}/r.db", "k", "me@x", 720, 90))
    reg_http = TestClient(reg_app)
    reg_http.__enter__()
    registry = RegistryClient("http://pi", "k", transport=httpx.ASGITransport(app=reg_app))
    cfg = Config(device_name="DESKTOP-PC", host_api_key="k", max_fps=30,
                 web_viewer_dir=str(ROOT / "web-viewer"), approval_timeout_seconds=2)
    state = AgentState(cfg=cfg, tailscale=TailscaleInfo(True, "100.64.0.9", "desktop-pc.tail.ts.net"))
    pins = PinManager(10)
    ui = FakeUI()
    backend = FakeBackend()
    app = create_app(state, ui, pins, registry, FakeCapturer(), backend_factory=lambda: backend,
                     monitor_fn=lambda: MONITOR)
    with TestClient(app) as host:
        host.portal.call(heartbeat_once, state, registry)
        yield dict(host=host, reg=reg_http, state=state, pins=pins, ui=ui, backend=backend, registry=registry)
    reg_http.__exit__(None, None, None)


def phone_pairs(env, pin=None):
    """B2 -> B3: the phone asks the Pi for a pairing, then sends the PIN to the PC."""
    pin = pin or env["pins"].snapshot()["pin"]
    r = env["reg"].post("/api/pairings", json={"device_id": env["state"].device_id,
                                               "phone_name": "Samsung phone", "pin": pin})
    assert r.status_code == 201, r.text
    p = r.json()
    r = env["host"].post("/api/pair", json={"pairing_id": p["pairing_id"], "pin": pin, "phone_name": "Samsung phone"})
    return p, r


# ---------------- unit pieces ----------------

def test_pin_rotation_and_lockout():
    now = [0.0]
    pm = PinManager(10, clock=lambda: now[0])
    first = pm.snapshot()["pin"]
    assert len(first) == 6 and first.isdigit()
    wrong = "000000" if first != "000000" else "111111"
    assert pm.check(wrong).attempts_left == 2
    assert pm.check(wrong).attempts_left == 1
    r = pm.check(wrong)
    assert r.error == "locked" and r.retry_after == 300
    assert pm.check(pm.snapshot()["pin"]).error == "locked"  # even the new PIN is locked out
    now[0] += 301
    assert pm.check(pm.snapshot()["pin"]).ok
    now[0] += 601
    assert pm.snapshot()["seconds_left"] == 600  # a fresh PIN after 10 minutes


def test_input_mapping_and_whitelist():
    b = FakeBackend()
    inj = InputInjector(b, {"left": -1920, "top": 0, "width": 1920, "height": 1080})
    inj.handle({"type": "tap", "x": 0.5, "y": 0.5})
    assert b.calls[:2] == [("move_to", -960, 540), ("click", "left", 1)]
    inj.handle({"type": "tap", "x": 2, "y": -1, "button": "right"})
    assert b.calls[2] == ("move_to", -1, 0)  # clamped to the display
    inj.handle({"type": "scroll", "dx": 0, "dy": 0.6})
    inj.handle({"type": "scroll", "dx": 0, "dy": 0.6})
    assert ("scroll", 0, 1) in b.calls  # fractions accumulate
    inj.handle({"type": "key", "key": "c", "mods": ["ctrl"]})
    inj.handle({"type": "text", "text": "héllo"})
    inj.handle({"type": "cad"})
    assert b.calls[-1] == ("key", "esc", ["ctrl", "shift"])
    inj.handle({"type": "down", "x": 0.1, "y": 0.1})
    inj.release_all()
    assert b.calls[-1] == ("release", "left")
    for bad in ({"type": "exec", "cmd": "calc"}, {"type": "key", "key": "launch_rockets"},
                {"type": "key", "key": "a", "mods": ["hyper"]}, {"type": "tap", "x": "nan?", "y": 0},
                {"type": "text", "text": "x" * 600}, {"type": "tap", "x": 0, "y": 0, "button": "x"}):
        with pytest.raises(InputError):
            inj.handle(bad)


def test_capture_presets_scale_down():
    cap = ScreenCapturer(70)
    img = Image.new("RGB", (2560, 1440), (10, 20, 30))
    assert cap.encode(img, "high").width == 2560
    assert cap.encode(img, "auto").width == 1600
    low = cap.encode(img, "low")
    assert (low.width, low.height) == (1024, 576) and low.screen_width == 2560


def test_qr_payload_never_has_pin(env):
    payload = json.loads(env["state"].qr_payload())
    assert payload["host"] == "desktop-pc.tail.ts.net" and payload["device_id"] == env["state"].device_id
    assert env["pins"].snapshot()["pin"] not in json.dumps(payload)
    assert "pin" not in payload


# ---------------- HTTP / pairing ----------------

def test_health_info_and_viewer_served(env):
    assert env["host"].get("/health").json()["ok"] is True
    assert env["host"].get("/api/info").json()["name"] == "DESKTOP-PC"
    assert "Astra Viewer" in env["host"].get("/").text
    assert env["host"].get("/viewer.js").status_code == 200
    assert env["host"].get("/debug/screenshot.jpg").status_code == 404  # off by default


def test_pairing_approved(env):
    p, r = phone_pairs(env)
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "approved", "device_id": env["state"].device_id, "name": "DESKTOP-PC"}
    assert ("ask", "pairing", "Samsung phone") in env["ui"].events
    auth = {"Authorization": f"Bearer {p['phone_token']}"}
    assert env["reg"].get("/api/devices", headers=auth).json()[0]["paired"] is True


def test_pairing_wrong_pin_then_lock(env):
    real = env["pins"].snapshot()["pin"]
    wrong = "000000" if real != "000000" else "111111"
    _, r = phone_pairs(env, pin=wrong)
    assert r.status_code == 401 and r.json()["attempts_left"] == 2
    _, r = phone_pairs(env, pin=wrong)
    _, r = phone_pairs(env, pin=wrong)
    assert r.status_code == 423 and r.json()["error"] == "locked"
    assert not any(e[0] == "ask" for e in env["ui"].events)  # P2 never shown for a wrong PIN


def test_pairing_denied(env):
    env["ui"].answer = DENY
    p, r = phone_pairs(env)
    assert r.status_code == 403 and r.json()["reason"] == "deny"
    auth = {"Authorization": f"Bearer {p['phone_token']}"}
    assert env["reg"].get("/api/devices", headers=auth).json()[0]["paired"] is False


# ---------------- WebSocket sessions ----------------

def paired_token(env):
    p, r = phone_pairs(env)
    assert r.status_code == 200
    return p["phone_token"]


def test_session_with_token_streams_and_injects(env):
    token = paired_token(env)
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token, "quality": "low"})
        assert ws.receive_json()["type"] == "waiting_approval"
        approved = ws.receive_json()
        assert approved["type"] == "approved" and approved["screen"] == {"width": 1920, "height": 1080}
        frame = ws.receive_bytes()
        assert frame[:2] == b"\xff\xd8"  # a JPEG
        ws.send_json({"type": "tap", "x": 0.25, "y": 0.5})
        ws.send_json({"type": "exec", "cmd": "calc"})
        # Read until the warning for the bad event arrives (frames interleave).
        for _ in range(200):
            msg = ws.receive()
            if msg.get("text"):
                assert json.loads(msg["text"]) == {"type": "warn", "detail": "unknown event type 'exec'"}
                break
        ws.send_json({"type": "bye"})
    time.sleep(0.2)
    assert ("move_to", 480, 540) in env["backend"].calls and ("click", "left", 1) in env["backend"].calls
    assert env["ui"].events[-1] == ("ended", "ended_by_phone")
    assert env["state"].busy is False


def test_session_denied_sends_not_approved(env):
    token = paired_token(env)
    env["ui"].answer = TIMEOUT
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token})
        assert ws.receive_json()["type"] == "waiting_approval"
        assert ws.receive_json() == {"type": "not_approved", "reason": "timeout"}
    assert not any(e[0] == "started" for e in env["ui"].events)


def test_unpaired_token_refused(env):
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": "not-a-real-token"})
        msg = ws.receive_json()
        assert msg["type"] == "error" and msg["error"] == "not_paired"


def test_browser_session_with_pin(env):
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "pin": "999999" if env["pins"].snapshot()["pin"] != "999999" else "888888"})
        msg = ws.receive_json()
        assert msg["error"] == "wrong_pin" and msg["attempts_left"] == 2
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "pin": env["pins"].snapshot()["pin"], "phone_name": "Pixel"})
        assert ws.receive_json()["type"] == "waiting_approval"
        assert ws.receive_json()["type"] == "approved"
        ws.receive_bytes()
        ws.send_json({"type": "bye"})
    assert ("ask", "session", "Pixel (PIN)") in env["ui"].events


def test_cancel_while_waiting_dismisses_popup(env):
    token = paired_token(env)
    env["ui"].answer = None  # the PC user never answers
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token})
        assert ws.receive_json()["type"] == "waiting_approval"
        ws.send_json({"type": "cancel"})
    time.sleep(0.3)
    assert ("dismiss",) in env["ui"].events
    assert env["state"].busy is False


def test_pc_can_end_session(env):
    token = paired_token(env)
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token})
        ws.receive_json(); ws.receive_json()
        ws.receive_bytes()
        env["host"].portal.call(lambda: env["state"].session.request_end("ended_by_pc"))
        for _ in range(200):
            msg = ws.receive()
            if msg.get("text"):
                assert json.loads(msg["text"]) == {"type": "ended", "reason": "ended_by_pc"}
                break


def test_second_viewer_is_busy(env):
    token = paired_token(env)
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token})
        ws.receive_json(); ws.receive_json()
        with env["host"].websocket_connect("/stream") as ws2:
            ws2.send_json({"type": "hello", "token": token})
            assert ws2.receive_json()["error"] == "busy"
        ws.send_json({"type": "bye"})


def test_desktop_check_for_pi():
    from host_agent import desktop
    assert desktop.problem({}, "win32") is None
    assert desktop.problem({"DISPLAY": ":0", "XDG_SESSION_TYPE": "x11"}, "linux") is None
    # Pi OS default (labwc / wayfire): DISPLAY exists via Xwayland, but capture would be black
    assert desktop.problem({"DISPLAY": ":0", "WAYLAND_DISPLAY": "wayland-0", "XDG_SESSION_TYPE": "wayland"},
                           "linux") == desktop.SWITCH_TO_X11
    assert desktop.problem({"DISPLAY": ":0", "WAYLAND_DISPLAY": "wayland-0"}, "linux") == desktop.SWITCH_TO_X11
    assert desktop.problem({"XDG_SESSION_TYPE": "tty"}, "linux") == desktop.NO_DISPLAY
    assert "raspi-config" in desktop.SWITCH_TO_X11
    assert desktop.os_label()
    assert desktop.default_device_name()


def test_auto_allow_paired_phone_skips_popup_but_not_pin(env):
    """Unattended Pi: AUTO_ALLOW_PAIRED_PHONES=1 lets a paired phone in without P2."""
    token = paired_token(env)
    env["state"].cfg.auto_allow_paired = True
    env["ui"].answer = DENY  # nobody at the Pi; a popup would deny
    asks_before = sum(1 for e in env["ui"].events if e[0] == "ask")
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "token": token})
        assert ws.receive_json()["type"] == "approved"
        assert ws.receive_bytes()[:2] == b"\xff\xd8"
        ws.send_json({"type": "bye"})
    time.sleep(0.2)
    assert sum(1 for e in env["ui"].events if e[0] == "ask") == asks_before
    # A browser with only the PIN still has to be allowed at the Pi.
    with env["host"].websocket_connect("/stream") as ws:
        ws.send_json({"type": "hello", "pin": env["pins"].snapshot()["pin"]})
        assert ws.receive_json()["type"] == "waiting_approval"
        assert ws.receive_json()["type"] == "not_approved"
