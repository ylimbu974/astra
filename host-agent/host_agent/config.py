import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

from .desktop import default_device_name

ROOT = Path(__file__).resolve().parent.parent


@dataclass
class Config:
    device_name: str = "DESKTOP-PC"
    registry_url: str = "http://pi-server:8000"
    host_api_key: str = ""
    host_port: int = 8765
    host_bind: str = ""
    pin_ttl_minutes: int = 10
    frame_quality: int = 70
    max_fps: int = 15
    tls_cert_file: str = ""
    tls_key_file: str = ""
    tailscale_hostname: str = ""
    enable_debug_screenshot: bool = False
    # Unattended use (Pi only): a phone that is already paired connects without the P2
    # Allow popup. First pairing still needs the PIN and Allow on this machine; browser
    # (PIN) sessions always need Allow. The session banner still shows.
    auto_allow_paired: bool = False
    approval_timeout_seconds: int = 60
    heartbeat_seconds: int = 30
    web_viewer_dir: str = str(ROOT.parent / "web-viewer")

    @property
    def scheme(self) -> str:
        return "https" if self.tls_cert_file and self.tls_key_file else "http"


def _int(name: str, default: int) -> int:
    raw = (os.environ.get(name) or "").split("#", 1)[0].strip()
    return int(raw) if raw else default


def _str(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).split(" #", 1)[0].strip()


def load_config(env_file: str | os.PathLike | None = None) -> Config:
    load_dotenv(env_file or ROOT / ".env")
    cfg = Config(
        device_name=_str("DEVICE_NAME", default_device_name()),
        registry_url=_str("REGISTRY_URL", "http://pi-server:8000").rstrip("/"),
        host_api_key=_str("HOST_API_KEY"),
        host_port=_int("HOST_PORT", 8765),
        host_bind=_str("HOST_BIND"),
        pin_ttl_minutes=_int("PAIRING_PIN_TTL_MINUTES", 10),
        frame_quality=max(10, min(95, _int("FRAME_QUALITY", 70))),
        max_fps=max(1, min(60, _int("MAX_FPS", 15))),
        tls_cert_file=_str("TLS_CERT_FILE"),
        tls_key_file=_str("TLS_KEY_FILE"),
        tailscale_hostname=_str("TAILSCALE_HOSTNAME"),
        enable_debug_screenshot=_str("ENABLE_DEBUG_SCREENSHOT", "0") == "1",
        auto_allow_paired=_str("AUTO_ALLOW_PAIRED_PHONES", "0") == "1",
    )
    web_dir = _str("WEB_VIEWER_DIR")
    if web_dir:
        cfg.web_viewer_dir = web_dir
    return cfg
