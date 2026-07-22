#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Serve the Forge Mod framebuffer as a small local web viewer."""
from __future__ import annotations

import array
import base64
import binascii
import json
import math
import os
import socket
import struct
import sys
import threading
import time
import zlib
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit


MAX_RESPONSE_BYTES = 64 * 1024 * 1024
DEPTH_DISPLAY_MAX_METRES = 32.0
MAX_DEPTH_PIXELS = 3840 * 2160
MAX_DEPTH_BASE64_BYTES = ((MAX_DEPTH_PIXELS * 4 + 2) // 3) * 4
DEPTH_VIEWER_WIDTH = 960
DEPTH_VIEWER_HEIGHT = 540
HTML = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Robonix Minecraft Viewer</title>
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; }
    body { margin: 0; background: #101418; color: #e7edf3; font: 14px system-ui, sans-serif; }
    main { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
    section { width: min(1600px, 100%); }
    .views { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
    figure { margin: 0; }
    figcaption { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
    img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: contain;
      background: #050708; border: 1px solid #2b3540; border-radius: 8px; }
    .status, .legend { color: #9aa8b5; }
    .scale { height: 8px; margin-top: 8px; border-radius: 4px;
      background: linear-gradient(90deg, #f00 0%, #ff0 33.3%, #0ff 66.7%, #00f 100%); }
    .legend { display: flex; justify-content: space-between; margin-top: 4px; font-size: 12px; }
    @media (max-width: 900px) { .views { grid-template-columns: 1fr; } }
  </style>
</head>
<body><main><section>
  <div class="views">
    <figure>
      <figcaption><strong>Minecraft framebuffer</strong><span class="status" id="rgb-status">waiting</span></figcaption>
      <img id="frame" alt="Minecraft framebuffer">
    </figure>
    <figure>
      <figcaption><strong>Depth buffer</strong><span class="status" id="depth-status">waiting</span></figcaption>
      <img id="depth" alt="Minecraft depth visualization">
      <div class="scale"></div><div class="legend"><span>near</span><span>32 blocks / sky</span></div>
    </figure>
  </div>
</section></main>
<script>
const frame = document.getElementById("frame");
const depth = document.getElementById("depth");
async function refreshImage(image, status, path) {
  try {
    const response = await fetch(`${path}?t=${Date.now()}`, { cache: "no-store" });
    if (!response.ok) throw new Error(await response.text());
    const blob = await response.blob();
    const previous = image.src;
    image.src = URL.createObjectURL(blob);
    if (previous.startsWith("blob:")) URL.revokeObjectURL(previous);
    status.textContent = `updated ${new Date().toLocaleTimeString()}`;
  } catch (error) {
    status.textContent = `waiting: ${error.message}`;
  }
}
let stopped = false;
async function refresh() {
  await Promise.allSettled([
    refreshImage(frame, document.getElementById("rgb-status"), "/frame.jpg"),
    refreshImage(depth, document.getElementById("depth-status"), "/depth.png")
  ]);
  if (!stopped) setTimeout(refresh, 200);
}
window.addEventListener("beforeunload", () => {
  stopped = true;
  for (const image of [frame, depth]) {
    if (image.src.startsWith("blob:")) URL.revokeObjectURL(image.src);
  }
});
refresh();
</script></body></html>
"""


def read_response(connection: socket.socket) -> dict[str, Any]:
    """Read one bounded JSON-lines response from the Mod command socket."""
    chunks = bytearray()
    while len(chunks) <= MAX_RESPONSE_BYTES:
        chunk = connection.recv(65536)
        if not chunk:
            break
        chunks.extend(chunk)
        newline = chunks.find(b"\n")
        if newline >= 0:
            if newline > MAX_RESPONSE_BYTES:
                raise RuntimeError("Minecraft Mod response exceeds the viewer limit")
            response = json.loads(chunks[:newline].decode("utf-8"))
            if not isinstance(response, dict):
                raise RuntimeError("Minecraft Mod response is not an object")
            return response
    raise RuntimeError("Minecraft Mod returned no complete bounded response")


def request_mod(host: str, port: int, command: str, timeout: float) -> dict[str, Any]:
    """Request one Mod command and return its validated result object."""
    request = json.dumps({"id": "viewer", "cmd": command}).encode("utf-8") + b"\n"
    with socket.create_connection((host, port), timeout=timeout) as connection:
        connection.settimeout(timeout)
        connection.sendall(request)
        response = read_response(connection)
    if response.get("id") != "viewer":
        raise RuntimeError("Minecraft Mod response id mismatch")
    if not response.get("ok"):
        error = response.get("error") or {}
        message = error.get("message") if isinstance(error, dict) else str(error)
        raise RuntimeError(str(message or f"{command} failed"))
    result = response.get("result") or {}
    if not isinstance(result, dict):
        raise RuntimeError(f"{command} result is not an object")
    return result


def fetch_snapshot(host: str, port: int, timeout: float = 3.0) -> tuple[bytes, int, int]:
    """Request and decode the latest JPEG framebuffer from the Forge Mod."""
    result = request_mod(host, port, "snapshot", timeout)
    view = result.get("renderedView") or {}
    if not isinstance(view, dict):
        raise RuntimeError("renderedView is not an object")
    image = view.get("image") or {}
    if not isinstance(image, dict):
        raise RuntimeError("renderedView image is not an object")
    data_url = image.get("dataUrl") or ""
    prefix = "data:image/jpeg;base64,"
    if not isinstance(data_url, str) or not data_url.startswith(prefix):
        raise RuntimeError("snapshot JPEG is missing")
    frame = base64.b64decode(data_url[len(prefix):], validate=True)
    width = int(view.get("width") or 0)
    height = int(view.get("height") or 0)
    if not frame.startswith(b"\xff\xd8") or not frame.endswith(b"\xff\xd9"):
        raise RuntimeError("snapshot payload is not a JPEG")
    if width < 1 or height < 1:
        raise RuntimeError("snapshot JPEG or dimensions are empty")
    return frame, width, height


def png_chunk(kind: bytes, data: bytes) -> bytes:
    """Encode one length-prefixed PNG chunk with its CRC checksum."""
    checksum = binascii.crc32(kind + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)


def depth_color(depth: float) -> tuple[int, int, int]:
    """Map a valid forward distance to a fixed near-red, far-blue scale."""
    if not math.isfinite(depth) or depth <= 0.0:
        return 0, 0, 0
    value = min(1.0, depth / DEPTH_DISPLAY_MAX_METRES)
    if value < 1.0 / 3.0:
        return 255, round(255 * value * 3.0), 0
    if value < 2.0 / 3.0:
        return round(255 * (2.0 - value * 3.0)), 255, round(255 * (value * 3.0 - 1.0))
    return 0, round(255 * (3.0 - value * 3.0)), 255


DEPTH_PALETTE = tuple(
    bytes(depth_color(index * DEPTH_DISPLAY_MAX_METRES / 255.0))
    for index in range(256)
)


def encode_depth_png(raw: bytes, width: int, height: int) -> tuple[bytes, int, int]:
    """Downsample little-endian 32FC1 metres into an RGB PNG visualization."""
    pixel_count = width * height
    if width < 1 or height < 1 or pixel_count > MAX_DEPTH_PIXELS or len(raw) != pixel_count * 4:
        raise RuntimeError("depth dimensions or payload are invalid")
    values = array.array("f")
    values.frombytes(raw)
    if sys.byteorder != "little":
        values.byteswap()
    scale = min(1.0, DEPTH_VIEWER_WIDTH / width, DEPTH_VIEWER_HEIGHT / height)
    output_width = max(1, round(width * scale))
    output_height = max(1, round(height * scale))
    pixels = bytearray()
    for y in range(output_height):
        source_row = (y * height // output_height) * width
        for x in range(output_width):
            depth = values[source_row + x * width // output_width]
            if not math.isfinite(depth) or depth <= 0.0:
                pixels.extend(b"\x00\x00\x00")
            else:
                palette_index = min(255, round(depth * 255.0 / DEPTH_DISPLAY_MAX_METRES))
                pixels.extend(DEPTH_PALETTE[palette_index])
    stride = output_width * 3
    rows = b"".join(
        b"\x00" + pixels[offset:offset + stride]
        for offset in range(0, len(pixels), stride)
    )
    header = struct.pack(">IIBBBBB", output_width, output_height, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"IDAT", zlib.compress(rows))
        + png_chunk(b"IEND", b"")
    )
    return png, output_width, output_height


def fetch_depth_png(host: str, port: int, timeout: float = 3.0) -> tuple[bytes, int, int]:
    """Request the latest 32FC1 depth buffer and render it as a PNG."""
    result = request_mod(host, port, "depth_snapshot", timeout)
    view = result.get("depthView") or {}
    image = view.get("image") or {}
    data_url = image.get("dataUrl") or ""
    prefix = "data:application/octet-stream;base64,"
    if (
        image.get("encoding") != "32FC1"
        or not isinstance(data_url, str)
        or not data_url.startswith(prefix)
    ):
        raise RuntimeError("depth snapshot 32FC1 image is missing")
    width = int(view.get("width") or 0)
    height = int(view.get("height") or 0)
    encoded = data_url[len(prefix):]
    if width < 1 or height < 1 or width * height > MAX_DEPTH_PIXELS or len(encoded) > MAX_DEPTH_BASE64_BYTES:
        raise RuntimeError("depth snapshot exceeds the viewer limit")
    raw = base64.b64decode(encoded, validate=True)
    return encode_depth_png(raw, width, height)


class FrameCache:
    """Thread-safe latest-frame storage shared by capture and HTTP threads."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.frame: bytes | None = None
        self.width = 0
        self.height = 0
        self.error = "no rendered frame is available yet"
        self.depth: bytes | None = None
        self.depth_width = 0
        self.depth_height = 0
        self.depth_error = "no rendered depth frame is available yet"

    def update(self, frame: bytes, width: int, height: int) -> None:
        """Replace the cached frame and clear the last capture error."""
        with self.lock:
            self.frame = frame
            self.width = width
            self.height = height
            self.error = ""

    def fail(self, message: str) -> None:
        """Record the latest capture error without discarding a valid frame."""
        with self.lock:
            self.error = message

    def snapshot(self) -> tuple[bytes | None, int, int, str]:
        """Return one consistent copy of the current frame state."""
        with self.lock:
            return self.frame, self.width, self.height, self.error

    def update_depth(self, frame: bytes, width: int, height: int) -> None:
        """Replace the cached depth visualization and clear its error."""
        with self.lock:
            self.depth = frame
            self.depth_width = width
            self.depth_height = height
            self.depth_error = ""

    def fail_depth(self, message: str) -> None:
        """Record a depth capture error without affecting the RGB frame."""
        with self.lock:
            self.depth_error = message

    def depth_snapshot(self) -> tuple[bytes | None, int, int, str]:
        """Return one consistent copy of the current depth visualization."""
        with self.lock:
            return self.depth, self.depth_width, self.depth_height, self.depth_error


def capture_rgb_loop(cache: FrameCache, stop: threading.Event, host: str, port: int, interval: float) -> None:
    """Refresh the RGB cache independently at a bounded rate."""
    while not stop.is_set():
        started = time.monotonic()
        try:
            cache.update(*fetch_snapshot(host, port))
        except Exception as exc:  # thread boundary: surface errors through /health
            cache.fail(f"{type(exc).__name__}: {exc}")
        remaining = max(0.0, interval - (time.monotonic() - started))
        stop.wait(remaining)


def capture_depth_loop(cache: FrameCache, stop: threading.Event, host: str, port: int, interval: float) -> None:
    """Refresh the depth visualization without delaying RGB capture."""
    while not stop.is_set():
        started = time.monotonic()
        try:
            cache.update_depth(*fetch_depth_png(host, port))
        except Exception as exc:  # thread boundary: keep RGB available
            cache.fail_depth(f"{type(exc).__name__}: {exc}")
        remaining = max(0.0, interval - (time.monotonic() - started))
        stop.wait(remaining)


class ViewerHandler(BaseHTTPRequestHandler):
    """Serve the viewer page, cached JPEG, and a lightweight health response."""

    cache = FrameCache()
    allowed_hosts = {"127.0.0.1", "localhost", "::1"}

    def do_GET(self) -> None:  # noqa: N802
        """Route viewer HTTP requests without exposing filesystem content."""
        try:
            hostname = urlsplit(f"//{self.headers.get('Host', '')}").hostname
        except ValueError:
            hostname = None
        if hostname is None or hostname.lower() not in self.allowed_hosts:
            self.send_body(HTTPStatus.MISDIRECTED_REQUEST, "text/plain; charset=utf-8", b"host is not allowed\n")
            return
        path = self.path.split("?", 1)[0]
        if path == "/":
            self.send_body(HTTPStatus.OK, "text/html; charset=utf-8", HTML.encode("utf-8"))
            return
        frame, width, height, error = self.cache.snapshot()
        depth, depth_width, depth_height, depth_error = self.cache.depth_snapshot()
        if path == "/frame.jpg":
            if frame is None or error:
                self.send_body(HTTPStatus.SERVICE_UNAVAILABLE, "text/plain; charset=utf-8", error.encode("utf-8"))
            else:
                self.send_body(HTTPStatus.OK, "image/jpeg", frame)
            return
        if path == "/depth.png":
            if depth is None or depth_error:
                self.send_body(
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    "text/plain; charset=utf-8",
                    depth_error.encode("utf-8"),
                )
            else:
                self.send_body(HTTPStatus.OK, "image/png", depth)
            return
        if path == "/health":
            body = json.dumps({
                "ready": frame is not None and not error,
                "stale": frame is not None and bool(error),
                "width": width,
                "height": height,
                "error": error,
                "depthReady": depth is not None and not depth_error,
                "depthStale": depth is not None and bool(depth_error),
                "depthWidth": depth_width,
                "depthHeight": depth_height,
                "depthError": depth_error,
            }).encode("utf-8")
            self.send_body(HTTPStatus.OK, "application/json; charset=utf-8", body)
            return
        self.send_body(HTTPStatus.NOT_FOUND, "text/plain; charset=utf-8", b"not found\n")

    def send_body(self, status: HTTPStatus, content_type: str, body: bytes) -> None:
        """Write one no-cache response with an exact content length."""
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        """Suppress per-frame access logs from browser polling."""
        _ = format, args


class IPv6ThreadingHTTPServer(ThreadingHTTPServer):
    """Serve the viewer on an explicitly configured IPv6 address."""

    address_family = socket.AF_INET6


def env_port(name: str, default: int) -> int:
    """Read and validate one TCP port environment variable."""
    value = int(os.environ.get(name, str(default)))
    if value < 1 or value > 65535:
        raise ValueError(f"{name} is outside 1..65535")
    return value


def main() -> None:
    """Start frame capture and HTTP serving until the process is terminated."""
    mod_host = os.environ.get("RBNX_MINECRAFT_MOD_HOST", "127.0.0.1")
    mod_port = env_port("RBNX_MINECRAFT_MOD_PORT", 25576)
    viewer_host = os.environ.get("RBNX_MINECRAFT_VIEWER_HOST", "127.0.0.1")
    viewer_port = env_port("RBNX_MINECRAFT_VIEWER_PORT", 8765)
    allowed_host = os.environ.get("RBNX_MINECRAFT_VIEWER_ALLOWED_HOST", "").strip().lower()
    if viewer_host not in {"127.0.0.1", "localhost", "::1"} and not allowed_host:
        raise ValueError("RBNX_MINECRAFT_VIEWER_ALLOWED_HOST is required for a non-loopback bind")
    ViewerHandler.allowed_hosts = {"127.0.0.1", "localhost", "::1"}
    if allowed_host:
        ViewerHandler.allowed_hosts.add(allowed_host)
    interval_ms = max(50, int(os.environ.get("RBNX_MINECRAFT_VIEWER_INTERVAL_MS", "200")))
    stop = threading.Event()
    rgb_capture = threading.Thread(
        target=capture_rgb_loop,
        args=(ViewerHandler.cache, stop, mod_host, mod_port, interval_ms / 1000.0),
        name="minecraft-viewer-rgb",
        daemon=True,
    )
    depth_capture = threading.Thread(
        target=capture_depth_loop,
        args=(ViewerHandler.cache, stop, mod_host, mod_port, interval_ms / 1000.0),
        name="minecraft-viewer-depth",
        daemon=True,
    )
    server_type = IPv6ThreadingHTTPServer if viewer_host == "::1" else ThreadingHTTPServer
    server = server_type((viewer_host, viewer_port), ViewerHandler)
    server.daemon_threads = True
    rgb_capture.start()
    depth_capture.start()
    display_host = f"[{viewer_host}]" if ":" in viewer_host else viewer_host
    print(f"[minecraft-mod/viewer] http://{display_host}:{viewer_port}/", flush=True)
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()
        rgb_capture.join(timeout=3)
        depth_capture.join(timeout=3)


if __name__ == "__main__":
    main()
