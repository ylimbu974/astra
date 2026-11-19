from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from registry import models
from registry.config import Settings
from registry.main import create_app

KEY = {"X-Host-Key": "test-host-key"}


@pytest.fixture()
def client(tmp_path):
    settings = Settings(database_url=f"sqlite:///{tmp_path}/r.db", host_api_key="test-host-key",
                        owner_email="me@example.com", token_ttl_hours=720, offline_after_seconds=90)
    with TestClient(create_app(settings)) as c:
        yield c


def heartbeat(client, name="DESKTOP-PC", hostname="desktop-pc.tail.ts.net", status="online"):
    r = client.post("/api/devices/heartbeat", headers=KEY, json={
        "name": name, "hostname": hostname, "tailscale_ip": "100.64.0.5", "status": status})
    assert r.status_code == 200, r.text
    return r.json()["device_id"]


def pair(client, device_id, pin="482913", token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    r = client.post("/api/pairings", headers=headers,
                    json={"device_id": device_id, "phone_name": "Samsung phone", "pin": pin})
    assert r.status_code == 201, r.text
    return r.json()


def test_refuses_to_start_without_key(tmp_path):
    with pytest.raises(RuntimeError):
        create_app(Settings(f"sqlite:///{tmp_path}/x.db", "change-me", "a@b", 1, 90))


def test_heartbeat_needs_host_key(client):
    r = client.post("/api/devices/heartbeat", json={"name": "X", "hostname": "x"})
    assert r.status_code == 401
    assert r.json() == {"error": "bad_host_key", "detail": "This call needs the Host Agent key."}


def test_heartbeat_and_list(client):
    dev_id = heartbeat(client)
    assert heartbeat(client) == dev_id  # same hostname -> same row
    devices = client.get("/api/devices").json()
    assert len(devices) == 1
    d = devices[0]
    assert d["status"] == "online" and d["name"] == "DESKTOP-PC"
    assert d["viewer_url"] == "http://desktop-pc.tail.ts.net:8765/"
    assert "paired" not in d


def test_goes_offline_after_missed_heartbeats(client):
    heartbeat(client)
    assert client.get("/api/devices").json()[0]["status"] == "online"
    # Jump the clock 2 minutes ahead: no heartbeat for > 90 s means offline.
    import registry.main as m
    real = m.utcnow
    try:
        m.utcnow = lambda: real() + timedelta(seconds=120)
        assert client.get("/api/devices").json()[0]["status"] == "offline"
    finally:
        m.utcnow = real


def test_stop_host_marks_offline_immediately(client):
    heartbeat(client)
    heartbeat(client, status="offline")
    assert client.get("/api/devices").json()[0]["status"] == "offline"


def test_validation_error_shape(client):
    r = client.post("/api/pairings", json={"device_id": 1, "phone_name": "p", "pin": "12"})
    assert r.status_code == 422
    assert r.json()["error"] == "invalid_request"


def test_pairing_flow_and_session(client):
    dev_id = heartbeat(client)
    p = pair(client, dev_id)
    token = p["phone_token"]
    auth = {"Authorization": f"Bearer {token}"}

    # Not approved yet: a session with this token is refused.
    r = client.post("/api/sessions", headers=KEY, json={"device_id": dev_id, "phone_token": token})
    assert r.status_code == 403 and r.json()["error"] == "not_paired"
    assert client.get(f"/api/pairings/{p['pairing_id']}", headers=auth).json()["state"] == "pending"

    # PC approves with a PIN that does not match what the phone sent -> refused.
    r = client.post(f"/api/pairings/{p['pairing_id']}/approve", headers=KEY, json={"device_id": dev_id, "pin": "000000"})
    assert r.status_code == 403 and r.json()["error"] == "pin_mismatch"

    r = client.post(f"/api/pairings/{p['pairing_id']}/approve", headers=KEY, json={"device_id": dev_id, "pin": "482913"})
    assert r.status_code == 200 and r.json()["phone_name"] == "Samsung phone"
    assert client.get(f"/api/pairings/{p['pairing_id']}", headers=auth).json()["state"] == "approved"

    devices = client.get("/api/devices", headers=auth).json()
    assert devices[0]["paired"] is True
    assert client.get("/api/phones/me", headers=auth).json()["paired_device_ids"] == [dev_id]

    r = client.post("/api/sessions", headers=KEY, json={"device_id": dev_id, "phone_token": token})
    assert r.status_code == 201
    sid = r.json()["session_id"]
    assert r.json()["phone_name"] == "Samsung phone"
    assert client.post(f"/api/sessions/{sid}/event", headers=KEY, json={"event": "approved"}).status_code == 200
    r = client.post(f"/api/sessions/{sid}/close", headers=KEY, json={"end_reason": "ended_by_phone", "bytes_estimate": 1234})
    assert r.status_code == 200 and r.json()["ended_at"]

    # A second pairing from the same phone reuses its token (no new token issued).
    p2 = pair(client, dev_id, token=token)
    assert "phone_token" not in p2


def test_reject_and_remove(client):
    dev_id = heartbeat(client)
    p = pair(client, dev_id)
    auth = {"Authorization": f"Bearer {p['phone_token']}"}
    r = client.post(f"/api/pairings/{p['pairing_id']}/reject", headers=KEY, json={"device_id": dev_id, "reason": "timeout"})
    assert r.status_code == 200
    assert client.get(f"/api/pairings/{p['pairing_id']}", headers=auth).status_code == 404

    p = pair(client, dev_id, token=p["phone_token"])
    client.post(f"/api/pairings/{p['pairing_id']}/approve", headers=KEY, json={"device_id": dev_id, "pin": "482913"})
    assert client.delete(f"/api/pairings/device/{dev_id}", headers=auth).json()["removed"] == 1
    r = client.post("/api/sessions", headers=KEY, json={"device_id": dev_id, "phone_token": p.get("phone_token") or auth["Authorization"][7:]})
    assert r.status_code == 403


def test_expired_pairing(client):
    dev_id = heartbeat(client)
    p = pair(client, dev_id)
    import registry.main as m
    real = m.utcnow
    try:
        m.utcnow = lambda: real() + timedelta(minutes=11)
        r = client.post(f"/api/pairings/{p['pairing_id']}/approve", headers=KEY, json={"device_id": dev_id, "pin": "482913"})
        assert r.status_code == 410 and r.json()["error"] == "pairing_expired"
    finally:
        m.utcnow = real


def test_browser_session_without_phone(client):
    dev_id = heartbeat(client)
    r = client.post("/api/sessions", headers=KEY, json={"device_id": dev_id, "phone_token": None, "client_label": "Browser"})
    assert r.status_code == 201 and r.json()["paired"] is False


def test_bad_phone_token(client):
    r = client.get("/api/phones/me", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401 and r.json()["error"] == "bad_token"


def test_w1_page(client):
    assert "No PCs have checked in yet" in client.get("/").text
    heartbeat(client, name="WORK-PC", hostname="work-pc")
    heartbeat(client, name="<script>", hostname="evil")
    page = client.get("/").text
    assert "WORK-PC" in page and "Open viewer" in page and "http://work-pc:8765/" in page
    assert "<script>" not in page.split("<body>")[1]  # names are escaped
