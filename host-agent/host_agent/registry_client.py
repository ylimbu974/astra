"""Calls from the Host Agent to the Pi registry. Metadata only: no frames, no PINs in clear."""
from __future__ import annotations

import logging

import httpx

log = logging.getLogger("astra.registry")


class RegistryError(Exception):
    def __init__(self, status: int, error: str, detail: str = ""):
        super().__init__(f"{status} {error}: {detail}")
        self.status, self.error, self.detail = status, error, detail


class RegistryClient:
    def __init__(self, base_url: str, host_key: str, transport: httpx.AsyncBaseTransport | None = None):
        self._client = httpx.AsyncClient(base_url=base_url, headers={"X-Host-Key": host_key},
                                         timeout=httpx.Timeout(8.0), transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _call(self, method: str, path: str, json: dict | None = None) -> dict:
        try:
            r = await self._client.request(method, path, json=json)
        except httpx.HTTPError as exc:
            raise RegistryError(0, "registry_unreachable", f"Could not reach the Pi registry: {exc}") from exc
        if r.status_code >= 400:
            try:
                body = r.json()
            except ValueError:
                body = {}
            raise RegistryError(r.status_code, body.get("error", "registry_error"), body.get("detail", r.text[:200]))
        return r.json()

    async def heartbeat(self, **fields) -> dict:
        return await self._call("POST", "/api/devices/heartbeat", fields)

    async def approve_pairing(self, pairing_id: int, device_id: int, pin: str) -> dict:
        return await self._call("POST", f"/api/pairings/{pairing_id}/approve", {"device_id": device_id, "pin": pin})

    async def reject_pairing(self, pairing_id: int, device_id: int, reason: str) -> dict:
        return await self._call("POST", f"/api/pairings/{pairing_id}/reject", {"device_id": device_id, "reason": reason})

    async def open_session(self, device_id: int, phone_token: str | None, client_label: str | None = None) -> dict:
        return await self._call("POST", "/api/sessions", {"device_id": device_id, "phone_token": phone_token,
                                                          "client_label": client_label})

    async def session_event(self, session_id: int, event: str, detail: str | None = None) -> None:
        await self._call("POST", f"/api/sessions/{session_id}/event", {"event": event, "detail": detail})

    async def close_session(self, session_id: int, end_reason: str, bytes_estimate: int) -> None:
        await self._call("POST", f"/api/sessions/{session_id}/close",
                         {"end_reason": end_reason, "bytes_estimate": bytes_estimate})

    async def audit(self, device_id: int | None, event: str, detail: str | None = None) -> None:
        try:
            await self._call("POST", "/api/audit", {"device_id": device_id, "event": event, "detail": detail})
        except RegistryError as exc:  # audit is best effort; never block the user on it
            log.warning("audit write failed: %s", exc)
