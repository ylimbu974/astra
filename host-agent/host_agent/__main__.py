"""Start the Host Agent:  python -m host_agent        (HEADLESS=1 for no window)

The server and heartbeat run on an asyncio loop in a background thread; the Tk
window (P1/P2/P3) owns the main thread, as Tk requires.
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
import threading

import uvicorn

from .capture import ScreenCapturer
from .config import load_config
from .input import PynputBackend
from .pin import PinManager
from .registry_client import RegistryClient
from .server import AgentState, create_app, heartbeat_loop, heartbeat_once
from . import desktop, tailscale

log = logging.getLogger("astra")


def make_dpi_aware() -> None:
    """Without this, Windows display scaling (125 %, 150 %) makes clicks land in the wrong place."""
    if sys.platform != "win32":
        return
    import ctypes
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # per-monitor aware
    except (AttributeError, OSError):
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except (AttributeError, OSError):
            pass


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # no line per heartbeat
    make_dpi_aware()
    trouble = desktop.problem()
    if trouble:
        print(trouble, file=sys.stderr)
        sys.exit(desktop.EXIT_NO_DESKTOP)  # start-host.sh does not retry this one
    cfg = load_config()
    if not cfg.host_api_key or cfg.host_api_key == "change-me":
        sys.exit("Set HOST_API_KEY in host-agent/.env (same value as on the Pi). See .env.example.")

    ts = tailscale.detect()
    bind = cfg.host_bind or ts.ip
    if not bind:
        sys.exit("Tailscale is not connected, so there is no safe address to listen on. "
                 "Sign in to Tailscale, or set HOST_BIND=127.0.0.1 for a local-only test.")
    if bind in ("0.0.0.0", "::"):
        log.warning("HOST_BIND=%s listens on every network, not just Tailscale. Only do this for testing.", bind)

    state = AgentState(cfg=cfg, tailscale=ts)
    pins = PinManager(cfg.pin_ttl_minutes)
    registry = RegistryClient(cfg.registry_url, cfg.host_api_key)

    pointer = None
    try:
        from pynput import mouse
        _m = mouse.Controller()
        pointer = lambda: _m.position  # noqa: E731
    except Exception as exc:  # e.g. no desktop session
        log.warning("pointer position unavailable: %s", exc)
    capturer = ScreenCapturer(cfg.frame_quality, cursor_pos=pointer)

    loop = asyncio.new_event_loop()
    state.loop = loop
    server_holder: dict = {}
    stopped = threading.Event()

    def stop():
        """Stop host: tell the Pi we are offline right away, then shut down."""
        if stopped.is_set():
            return
        stopped.set()

        async def shutdown():
            await heartbeat_once(state, registry, status="offline")
            srv = server_holder.get("server")
            if srv:
                if state.session:
                    state.session.request_end("host_stopped")
                srv.should_exit = True

        if loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(shutdown(), loop)
            try:
                fut.result(timeout=10)
            except Exception:
                pass
        if hasattr(ui, "close"):
            ui.close()

    if os.environ.get("HEADLESS") == "1":
        from .ui import ConsoleUI
        ui = ConsoleUI()
        print(f"[P1] Pairing PIN: {pins.snapshot()['pin']} (changes every {cfg.pin_ttl_minutes} min)", flush=True)
    else:
        from .gui import TkUI
        ui = TkUI(state, pins, on_stop=stop)

    app = create_app(state, ui, pins, registry, capturer,
                     backend_factory=PynputBackend, monitor_fn=capturer.primary_monitor)

    ssl_kwargs = {}
    if cfg.scheme == "https":
        ssl_kwargs = {"ssl_certfile": cfg.tls_cert_file, "ssl_keyfile": cfg.tls_key_file}
    server = uvicorn.Server(uvicorn.Config(app, host=bind, port=cfg.host_port, log_level="info",
                                           ws_max_size=1 << 20, **ssl_kwargs))
    server.install_signal_handlers = lambda: None  # signals belong to the main (GUI) thread
    server_holder["server"] = server

    async def serve():
        hb = asyncio.create_task(heartbeat_loop(state, registry, ui))
        try:
            await server.serve()
        finally:
            hb.cancel()
            await registry.aclose()
            capturer.close()

    def run_loop():
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(serve())
        except SystemExit:
            log.error("server failed to start (is port %s already in use?)", cfg.host_port)
        finally:
            if not stopped.is_set():
                stopped.set()
                if hasattr(ui, "close"):
                    ui.close()

    log.info("Host Agent '%s' on %s://%s:%s (registry %s)", cfg.device_name, cfg.scheme, bind, cfg.host_port,
             cfg.registry_url)
    t = threading.Thread(target=run_loop, name="astra-server", daemon=True)
    t.start()

    if hasattr(ui, "run"):
        ui.run()
        stop()
    else:
        try:
            while t.is_alive():
                t.join(0.5)
        except KeyboardInterrupt:
            stop()
    t.join(5)


if __name__ == "__main__":
    main()
