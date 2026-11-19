"""Screen capture (Kanban T-11, T-12): primary display only, JPEG frames, never stored.

mss objects are tied to the thread that created them on Windows, so every grab runs
on one dedicated worker thread.
"""
from __future__ import annotations

import asyncio
import io
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from PIL import Image, ImageDraw

# A3 "Stream quality": Auto / High / Low (data saver).
PRESETS = {
    "high": {"max_width": 0, "quality_boost": 15},     # native resolution
    "auto": {"max_width": 1600, "quality_boost": 0},
    "low": {"max_width": 1024, "quality_boost": -25},
}


@dataclass
class Frame:
    jpeg: bytes
    width: int    # size of the picture sent (may be scaled down)
    height: int
    screen_width: int   # real size of the PC's primary display
    screen_height: int


class ScreenCapturer:
    def __init__(self, base_quality: int = 70, cursor_pos=None):
        self.base_quality = base_quality
        self._cursor_pos = cursor_pos  # callable -> (x, y) in screen pixels, or None
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="astra-capture")
        self._local = threading.local()

    def _sct(self):
        sct = getattr(self._local, "sct", None)
        if sct is None:
            import mss  # imported lazily so tests can run without a display
            sct = mss.mss()
            self._local.sct = sct
        return sct

    def primary_monitor(self) -> dict:
        return self._pool.submit(lambda: dict(self._sct().monitors[1])).result()

    def _grab(self, preset: str) -> Frame:
        sct = self._sct()
        mon = sct.monitors[1]  # [0] is all screens combined; [1] is the primary display
        shot = sct.grab(mon)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        self._draw_cursor(img, mon)
        return self.encode(img, preset)

    def _draw_cursor(self, img: Image.Image, mon: dict) -> None:
        """mss does not capture the mouse pointer, so draw a simple arrow where it is."""
        if not self._cursor_pos:
            return
        try:
            x, y = self._cursor_pos()
        except Exception:
            return
        x, y = x - mon["left"], y - mon["top"]
        if not (0 <= x < img.width and 0 <= y < img.height):
            return
        s = max(1, img.width // 1280)
        arrow = [(x, y), (x, y + 18 * s), (x + 5 * s, y + 13 * s), (x + 9 * s, y + 21 * s),
                 (x + 12 * s, y + 20 * s), (x + 8 * s, y + 12 * s), (x + 14 * s, y + 12 * s)]
        ImageDraw.Draw(img).polygon(arrow, fill=(255, 255, 255), outline=(0, 0, 0))

    def encode(self, img: Image.Image, preset: str) -> Frame:
        p = PRESETS.get(preset, PRESETS["auto"])
        sw, sh = img.size
        if p["max_width"] and sw > p["max_width"]:
            ratio = p["max_width"] / sw
            img = img.resize((p["max_width"], max(1, int(sh * ratio))), Image.BILINEAR)
        quality = max(10, min(95, self.base_quality + p["quality_boost"]))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, optimize=False)
        return Frame(buf.getvalue(), img.width, img.height, sw, sh)

    async def grab(self, preset: str = "auto") -> Frame:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._pool, self._grab, preset)

    def close(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)
