"""Astra Pi Registry (Kanban T-18 to T-21).

Two kinds of callers:
  * Host Agents (the PCs) authenticate with the shared HOST_API_KEY header.
  * Phones authenticate with the bearer token they receive when they start a pairing.
    A token alone authorizes nothing: only an *approved* pairing lets that phone
    open a session on that PC, and every session still needs Allow on the PC (P2).

The device list (GET /api/devices) and the W1 page are read-only and open to
anyone on the tailnet, because the B3 manual path and W1 need them before a
phone is paired. Nothing on this service is ever exposed outside Tailscale.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
from contextlib import contextmanager
from datetime import datetime, timedelta

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import select

from .config import Settings, load_settings
from .models import (AuditLog, Device, Pairing, Phone, SessionRecord, User,
                     make_session_factory, utcnow)
from .pages import render_device_page

PAIRING_TTL = timedelta(minutes=10)  # matches the PIN rotation on the PC


class ApiError(Exception):
    """Every error leaves the API as {"error": code, "detail": text} (Sketch, Section 4)."""

    def __init__(self, status: int, error: str, detail: str = ""):
        self.status, self.error, self.detail = status, error, detail


# ---------- helpers ----------

def sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def hash_pin(pin: str) -> str:
    salt = secrets.token_hex(8)
    return f"{salt}${sha256(salt + pin)}"


def pin_matches(pin: str, stored: str) -> bool:
    salt, digest = stored.split("$", 1)
    return hmac.compare_digest(sha256(salt + pin), digest)


def iso(dt: datetime | None) -> str | None:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ") if dt else None


# ---------- request bodies ----------

class HeartbeatIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    hostname: str = Field(min_length=1, max_length=253)
    tailscale_ip: str | None = Field(default=None, max_length=64)
    os: str | None = Field(default="Windows", max_length=64)
    status: str = Field(default="online", pattern="^(online|offline)$")
    port: int = Field(default=8765, ge=1, le=65535)
    scheme: str = Field(default="http", pattern="^(http|https)$")


class PairingIn(BaseModel):
    device_id: int
    phone_name: str = Field(min_length=1, max_length=64)
    pin: str = Field(pattern=r"^\d{6}$")


class ApproveIn(BaseModel):
    device_id: int
    pin: str = Field(pattern=r"^\d{6}$")


class RejectIn(BaseModel):
    device_id: int
    reason: str = Field(default="denied", max_length=64)


class SessionIn(BaseModel):
    device_id: int
    phone_token: str | None = None  # None = browser session that passed the PIN check on the PC
    client_label: str | None = Field(default=None, max_length=64)


class SessionEventIn(BaseModel):
    event: str = Field(pattern="^(approved|denied)$")
    detail: str | None = Field(default=None, max_length=200)


class SessionCloseIn(BaseModel):
    end_reason: str = Field(max_length=64)
    bytes_estimate: int = Field(default=0, ge=0)


class AuditIn(BaseModel):
    device_id: int | None = None
    event: str = Field(max_length=64)
    detail: str | None = Field(default=None, max_length=500)


class PhoneUpdateIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)


# ---------- app factory ----------

def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    if not settings.host_api_key or settings.host_api_key == "change-me":
        raise RuntimeError("Set HOST_API_KEY in the registry .env before starting (see .env.example).")

    SessionLocal = make_session_factory(settings.database_url)
    app = FastAPI(title="Astra Pi Registry", version="1.0.0", docs_url=None, redoc_url=None)

    with SessionLocal() as db:
        if not db.scalar(select(User).limit(1)):
            db.add(User(email=settings.owner_email))
            db.commit()

    @contextmanager
    def session_scope():
        db = SessionLocal()
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def get_db():
        with session_scope() as db:
            yield db

    def owner_id(db) -> int:
        return db.scalar(select(User.id).order_by(User.id).limit(1))

    def audit(db, event: str, device_id: int | None = None, detail: str | None = None):
        db.add(AuditLog(user_id=owner_id(db), device_id=device_id, event=event, detail=detail))

    def refresh_statuses(db) -> None:
        """Flip devices to offline after OFFLINE_AFTER_SECONDS without a heartbeat."""
        cutoff = utcnow() - timedelta(seconds=settings.offline_after_seconds)
        for dev in db.scalars(select(Device).where(Device.status == "online")):
            if not dev.last_seen_at or dev.last_seen_at < cutoff:
                dev.status = "offline"

    def phone_from_token(db, token: str | None) -> Phone | None:
        if not token:
            return None
        phone = db.scalar(select(Phone).where(Phone.device_token_hash == sha256(token)))
        if not phone:
            return None
        ttl = timedelta(hours=settings.token_ttl_hours)
        if phone.last_seen_at and utcnow() - phone.last_seen_at > ttl:
            return None
        phone.last_seen_at = utcnow()
        return phone

    def bearer(authorization: str | None) -> str | None:
        if authorization and authorization.lower().startswith("bearer "):
            return authorization[7:].strip()
        return None

    def require_host(x_host_key: str | None = Header(default=None)) -> None:
        if not x_host_key or not hmac.compare_digest(x_host_key, settings.host_api_key):
            raise ApiError(401, "bad_host_key", "This call needs the Host Agent key.")

    def require_phone(authorization: str | None = Header(default=None), db=Depends(get_db)) -> Phone:
        phone = phone_from_token(db, bearer(authorization))
        if not phone:
            raise ApiError(401, "bad_token", "This phone is not paired, or its pairing expired. Pair again.")
        return phone

    def device_json(dev: Device, paired_ids: set[int] | None = None) -> dict:
        out = {
            "id": dev.id, "name": dev.name, "hostname": dev.hostname,
            "tailscale_ip": dev.tailscale_ip, "os": dev.os, "status": dev.status,
            "last_seen_at": iso(dev.last_seen_at), "port": dev.port, "scheme": dev.scheme,
            "viewer_url": f"{dev.scheme}://{dev.hostname}:{dev.port}/",
        }
        if paired_ids is not None:
            out["paired"] = dev.id in paired_ids
        return out

    # ---------- error shape ----------

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        return JSONResponse({"error": exc.error, "detail": exc.detail}, status_code=exc.status)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", []) if p != "body")
        return JSONResponse({"error": "invalid_request", "detail": f"{where}: {first.get('msg', '')}"},
                            status_code=422)

    # ---------- health ----------

    @app.get("/health")
    def health():
        return {"ok": True, "service": "astra-registry"}

    # ---------- devices (T-18, T-19) ----------

    @app.post("/api/devices/heartbeat", dependencies=[Depends(require_host)])
    def heartbeat(body: HeartbeatIn, db=Depends(get_db)):
        dev = db.scalar(select(Device).where(Device.hostname == body.hostname))
        if not dev:
            dev = Device(owner_id=owner_id(db), hostname=body.hostname, name=body.name)
            db.add(dev)
            db.flush()
            audit(db, "device_registered", dev.id, body.name)
        dev.name, dev.tailscale_ip, dev.os = body.name, body.tailscale_ip, body.os
        dev.port, dev.scheme, dev.status = body.port, body.scheme, body.status
        dev.last_seen_at = utcnow()
        db.flush()
        return {"device_id": dev.id, "status": dev.status}

    @app.get("/api/devices")
    def list_devices(authorization: str | None = Header(default=None), db=Depends(get_db)):
        refresh_statuses(db)
        phone = phone_from_token(db, bearer(authorization))
        paired_ids = None
        if phone:
            paired_ids = set(db.scalars(select(Pairing.device_id).where(
                Pairing.phone_id == phone.id, Pairing.approved.is_(True))))
        devices = db.scalars(select(Device).order_by(Device.name)).all()
        return [device_json(d, paired_ids) for d in devices]

    # ---------- pairings (Flow B) ----------

    @app.post("/api/pairings", status_code=201)
    def start_pairing(body: PairingIn, authorization: str | None = Header(default=None), db=Depends(get_db)):
        dev = db.get(Device, body.device_id)
        if not dev:
            raise ApiError(404, "unknown_device", "That code is not a PC from this app.")
        phone = phone_from_token(db, bearer(authorization))
        new_token = None
        if not phone:
            new_token = secrets.token_urlsafe(32)
            phone = Phone(owner_id=owner_id(db), name=body.phone_name,
                          device_token_hash=sha256(new_token), last_seen_at=utcnow())
            db.add(phone)
            db.flush()
        else:
            phone.name = body.phone_name
        pairing = Pairing(phone_id=phone.id, device_id=dev.id, pin_hash=hash_pin(body.pin),
                          expires_at=utcnow() + PAIRING_TTL, approved=False)
        db.add(pairing)
        db.flush()
        audit(db, "pairing_requested", dev.id, f"phone={phone.name} pairing={pairing.id}")
        out = {"pairing_id": pairing.id, "phone_id": phone.id, "device_id": dev.id,
               "expires_at": iso(pairing.expires_at)}
        if new_token:
            out["phone_token"] = new_token  # shown once; only its hash is stored
        return out

    @app.get("/api/pairings/{pairing_id}")
    def pairing_status(pairing_id: int, phone: Phone = Depends(require_phone), db=Depends(get_db)):
        p = db.get(Pairing, pairing_id)
        if not p or p.phone_id != phone.id:
            raise ApiError(404, "unknown_pairing", "No such pairing for this phone.")
        state = "approved" if p.approved else ("expired" if p.expires_at < utcnow() else "pending")
        return {"pairing_id": p.id, "device_id": p.device_id, "state": state}

    @app.post("/api/pairings/{pairing_id}/approve", dependencies=[Depends(require_host)])
    def approve_pairing(pairing_id: int, body: ApproveIn, db=Depends(get_db)):
        p = db.get(Pairing, pairing_id)
        if not p or p.device_id != body.device_id:
            raise ApiError(404, "unknown_pairing", "No such pairing for this PC.")
        if p.approved:
            raise ApiError(409, "already_approved", "This pairing was already approved.")
        if p.expires_at < utcnow():
            audit(db, "pairing_expired", p.device_id, f"pairing={p.id}")
            raise ApiError(410, "pairing_expired", "The pairing request expired. Start again on the phone.")
        if not pin_matches(body.pin, p.pin_hash):
            audit(db, "pairing_pin_mismatch", p.device_id, f"pairing={p.id}")
            raise ApiError(403, "pin_mismatch", "The PIN sent by the phone does not match the PC.")
        # One live pairing per phone/PC pair: drop older ones.
        for old in db.scalars(select(Pairing).where(Pairing.phone_id == p.phone_id,
                                                    Pairing.device_id == p.device_id,
                                                    Pairing.id != p.id)):
            db.delete(old)
        p.approved = True
        phone = db.get(Phone, p.phone_id)
        audit(db, "pairing_approved", p.device_id, f"phone={phone.name} pairing={p.id}")
        return {"pairing_id": p.id, "approved": True, "phone_name": phone.name}

    @app.post("/api/pairings/{pairing_id}/reject", dependencies=[Depends(require_host)])
    def reject_pairing(pairing_id: int, body: RejectIn, db=Depends(get_db)):
        p = db.get(Pairing, pairing_id)
        if not p or p.device_id != body.device_id:
            raise ApiError(404, "unknown_pairing", "No such pairing for this PC.")
        audit(db, "pairing_rejected", p.device_id, f"pairing={p.id} reason={body.reason}")
        if not p.approved:
            db.delete(p)
        return {"pairing_id": pairing_id, "approved": False}

    @app.delete("/api/pairings/device/{device_id}")
    def remove_pairing(device_id: int, phone: Phone = Depends(require_phone), db=Depends(get_db)):
        """A3 'Remove a device': the phone gives up its pairing; it must pair again later."""
        n = 0
        for p in db.scalars(select(Pairing).where(Pairing.phone_id == phone.id, Pairing.device_id == device_id)):
            db.delete(p)
            n += 1
        audit(db, "pairing_removed", device_id if db.get(Device, device_id) else None, f"phone={phone.name}")
        return {"removed": n}

    # ---------- phones ----------

    @app.get("/api/phones/me")
    def phone_me(phone: Phone = Depends(require_phone), db=Depends(get_db)):
        ids = list(db.scalars(select(Pairing.device_id).where(
            Pairing.phone_id == phone.id, Pairing.approved.is_(True))))
        return {"id": phone.id, "name": phone.name, "paired_device_ids": ids}

    @app.patch("/api/phones/me")
    def phone_rename(body: PhoneUpdateIn, phone: Phone = Depends(require_phone)):
        phone.name = body.name
        return {"id": phone.id, "name": phone.name}

    # ---------- sessions (metadata only) ----------

    @app.post("/api/sessions", status_code=201, dependencies=[Depends(require_host)])
    def open_session(body: SessionIn, db=Depends(get_db)):
        dev = db.get(Device, body.device_id)
        if not dev:
            raise ApiError(404, "unknown_device", "Unknown PC.")
        phone = None
        if body.phone_token is not None:
            phone = phone_from_token(db, body.phone_token)
            paired = phone and db.scalar(select(Pairing.id).where(
                Pairing.phone_id == phone.id, Pairing.device_id == dev.id, Pairing.approved.is_(True)))
            if not paired:
                audit(db, "session_refused_not_paired", dev.id, body.client_label)
                raise ApiError(403, "not_paired", "This phone is not paired with this PC. Pair it again.")
        rec = SessionRecord(device_id=dev.id, phone_id=phone.id if phone else None)
        db.add(rec)
        db.flush()
        label = phone.name if phone else (body.client_label or "Browser (PIN)")
        audit(db, "session_requested", dev.id, f"session={rec.id} client={label}")
        return {"session_id": rec.id, "phone_name": label, "paired": phone is not None}

    @app.post("/api/sessions/{session_id}/event", dependencies=[Depends(require_host)])
    def session_event(session_id: int, body: SessionEventIn, db=Depends(get_db)):
        rec = db.get(SessionRecord, session_id)
        if not rec:
            raise ApiError(404, "unknown_session", "Unknown session.")
        audit(db, f"session_{body.event}", rec.device_id, f"session={rec.id} {body.detail or ''}".strip())
        return {"ok": True}

    @app.post("/api/sessions/{session_id}/close", dependencies=[Depends(require_host)])
    def close_session(session_id: int, body: SessionCloseIn, db=Depends(get_db)):
        rec = db.get(SessionRecord, session_id)
        if not rec:
            raise ApiError(404, "unknown_session", "Unknown session.")
        if rec.ended_at is None:
            rec.ended_at = utcnow()
            rec.end_reason = body.end_reason
            rec.bytes_estimate = body.bytes_estimate
            audit(db, "session_closed", rec.device_id, f"session={rec.id} reason={body.end_reason}")
        return {"session_id": rec.id, "ended_at": iso(rec.ended_at)}

    # ---------- audit ----------

    @app.post("/api/audit", status_code=201, dependencies=[Depends(require_host)])
    def add_audit(body: AuditIn, db=Depends(get_db)):
        dev_id = body.device_id if body.device_id and db.get(Device, body.device_id) else None
        audit(db, body.event, dev_id, body.detail)
        return {"ok": True}

    # ---------- W1: device registry page (T-20) ----------

    @app.get("/", response_class=HTMLResponse)
    def device_page(db=Depends(get_db)):
        refresh_statuses(db)
        devices = [device_json(d) for d in db.scalars(select(Device).order_by(Device.name))]
        return HTMLResponse(render_device_page(devices, utcnow()))

    return app
