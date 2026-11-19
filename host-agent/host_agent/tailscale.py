"""Find this PC's Tailscale address and MagicDNS name."""
from __future__ import annotations

import ipaddress
import json
import shutil
import socket
import subprocess
import sys
from dataclasses import dataclass

TAILNET = ipaddress.ip_network("100.64.0.0/10")
_WIN_PATH = r"C:\Program Files\Tailscale\tailscale.exe"


@dataclass
class TailscaleInfo:
    connected: bool
    ip: str | None
    dns_name: str | None


def _cli() -> str | None:
    found = shutil.which("tailscale")
    if found:
        return found
    if sys.platform == "win32":
        import os
        return _WIN_PATH if os.path.exists(_WIN_PATH) else None
    return None


def _run(args: list[str]) -> str | None:
    cli = _cli()
    if not cli:
        return None
    try:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        out = subprocess.run([cli, *args], capture_output=True, text=True, timeout=5, creationflags=flags)
        return out.stdout if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def _ip_from_interfaces() -> str | None:
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addr = info[4][0]
            if ipaddress.ip_address(addr) in TAILNET:
                return addr
    except OSError:
        pass
    return None


def detect() -> TailscaleInfo:
    ip, dns = None, None
    status = _run(["status", "--json"])
    if status:
        try:
            data = json.loads(status)
            self_node = data.get("Self") or {}
            dns = (self_node.get("DNSName") or "").rstrip(".") or None
            for addr in self_node.get("TailscaleIPs") or []:
                if ":" not in addr:
                    ip = addr
                    break
            if data.get("BackendState") not in (None, "Running"):
                return TailscaleInfo(False, ip, dns)
        except (ValueError, AttributeError):
            pass
    if not ip:
        ip = _ip_from_interfaces()
    return TailscaleInfo(ip is not None, ip, dns)
