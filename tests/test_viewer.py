# SPDX-License-Identifier: MulanPSL-2.0
from __future__ import annotations

import base64
import importlib.util
import json
import math
import socket
import struct
import threading
import unittest
import urllib.error
import urllib.request
import zlib
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch


VIEWER_PATH = Path(__file__).resolve().parents[1] / "scripts" / "viewer.py"
SPEC = importlib.util.spec_from_file_location("minecraft_mod_viewer", VIEWER_PATH)
assert SPEC and SPEC.loader
viewer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(viewer)


class SnapshotServer:
    """Serve one canned Mod response for a viewer client test."""

    def __init__(self, response: dict[str, object], command: str = "snapshot") -> None:
        self.response = response
        self.command = command
        self.socket = socket.socket()
        self.socket.bind(("127.0.0.1", 0))
        self.socket.listen(1)
        self.port = self.socket.getsockname()[1]
        self.thread = threading.Thread(target=self.serve, daemon=True)

    def serve(self) -> None:
        """Accept one request and return the configured JSON line."""
        connection, _ = self.socket.accept()
        with connection:
            request = connection.recv(4096)
            if f'"cmd": "{self.command}"'.encode() not in request:
                return
            connection.sendall(json.dumps(self.response).encode("utf-8") + b"\n")
        self.socket.close()

    def __enter__(self) -> "SnapshotServer":
        """Start the background server for a test context."""
        self.thread.start()
        return self

    def __exit__(self, *args: object) -> None:
        """Wait briefly for the one-shot server to finish."""
        _ = args
        self.thread.join(timeout=2)
        self.socket.close()


class ViewerTests(unittest.TestCase):
    """Validate decoding of the Mod's existing rendered-view response."""

    def test_fetch_snapshot_decodes_jpeg(self) -> None:
        """Return JPEG bytes and dimensions from a valid snapshot response."""
        jpeg = b"\xff\xd8test\xff\xd9"
        response = {
            "id": "viewer",
            "ok": True,
            "result": {"renderedView": {
                "width": 960,
                "height": 540,
                "image": {"dataUrl": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()},
            }},
        }
        with SnapshotServer(response) as server:
            self.assertEqual(viewer.fetch_snapshot("127.0.0.1", server.port), (jpeg, 960, 540))

    def test_fetch_snapshot_surfaces_mod_error(self) -> None:
        """Raise the Mod error instead of returning an empty image."""
        with SnapshotServer({"id": "viewer", "ok": False, "error": {"message": "no frame"}}) as server:
            with self.assertRaisesRegex(RuntimeError, "no frame"):
                viewer.fetch_snapshot("127.0.0.1", server.port)

    def test_fetch_snapshot_rejects_non_jpeg_payload(self) -> None:
        """Reject valid base64 that is not actually a JPEG image."""
        response = {
            "id": "viewer",
            "ok": True,
            "result": {"renderedView": {
                "width": 1,
                "height": 1,
                "image": {"dataUrl": "data:image/jpeg;base64," + base64.b64encode(b"not-jpeg").decode()},
            }},
        }
        with SnapshotServer(response) as server:
            with self.assertRaisesRegex(RuntimeError, "not a JPEG"):
                viewer.fetch_snapshot("127.0.0.1", server.port)

    def test_fetch_depth_png_visualizes_float_depth(self) -> None:
        """Convert valid depth and NaN sky pixels into a browser PNG."""
        raw = struct.pack("<2f", 1.0, math.nan)
        response = {
            "id": "viewer",
            "ok": True,
            "result": {"depthView": {
                "width": 2,
                "height": 1,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64," + base64.b64encode(raw).decode(),
                },
            }},
        }
        with SnapshotServer(response, "depth_snapshot") as server:
            png, width, height = viewer.fetch_depth_png("127.0.0.1", server.port)
        self.assertEqual((width, height), (2, 1))
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        self.assertEqual(struct.unpack(">II", png[16:24]), (2, 1))
        offset = 8
        compressed = bytearray()
        while offset < len(png):
            length = struct.unpack(">I", png[offset:offset + 4])[0]
            kind = png[offset + 4:offset + 8]
            data = png[offset + 8:offset + 8 + length]
            if kind == b"IDAT":
                compressed.extend(data)
            offset += 12 + length
        scanline = zlib.decompress(compressed)
        self.assertEqual(scanline[0], 0)
        self.assertEqual(scanline[1:4], viewer.DEPTH_PALETTE[8])
        self.assertEqual(scanline[4:7], b"\x00\x00\x00")

    def test_capture_loop_survives_unexpected_error(self) -> None:
        """Keep capturing after an unexpected protocol/client exception."""
        cache = viewer.FrameCache()
        stop = threading.Event()
        jpeg = b"\xff\xd8test\xff\xd9"
        calls = 0

        def fetch(*args: object) -> tuple[bytes, int, int]:
            """Fail once, then stop the loop while returning a valid frame."""
            nonlocal calls
            _ = args
            calls += 1
            if calls == 1:
                raise TypeError("bad shape")
            stop.set()
            return jpeg, 320, 180

        with patch.object(viewer, "fetch_snapshot", side_effect=fetch):
            viewer.capture_rgb_loop(cache, stop, "127.0.0.1", 25576, 0)
        self.assertEqual(cache.snapshot(), (jpeg, 320, 180, ""))

    def test_stale_frame_returns_service_unavailable(self) -> None:
        """Tell the browser a retained frame is stale after capture fails."""
        cache = viewer.FrameCache()
        cache.update(b"\xff\xd8test\xff\xd9", 320, 180)
        cache.fail("connection lost")
        viewer.ViewerHandler.cache = cache
        server = ThreadingHTTPServer(("127.0.0.1", 0), viewer.ViewerHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/frame.jpg"
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(url)
            self.assertEqual(raised.exception.code, 503)
            self.assertIn("connection lost", raised.exception.read().decode())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_depth_endpoint_returns_cached_png(self) -> None:
        """Serve the depth visualization independently of RGB readiness."""
        cache = viewer.FrameCache()
        cache.update_depth(b"\x89PNG\r\n\x1a\n", 2, 1)
        viewer.ViewerHandler.cache = cache
        server = ThreadingHTTPServer(("127.0.0.1", 0), viewer.ViewerHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            response = urllib.request.urlopen(f"http://127.0.0.1:{server.server_port}/depth.png")
            self.assertEqual(response.headers.get_content_type(), "image/png")
            self.assertEqual(response.read(), b"\x89PNG\r\n\x1a\n")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_depth_failure_does_not_hide_rgb_or_health(self) -> None:
        """Keep RGB ready while reporting an independent depth failure."""
        cache = viewer.FrameCache()
        cache.update(b"\xff\xd8test\xff\xd9", 320, 180)
        cache.fail_depth("depth unavailable")
        viewer.ViewerHandler.cache = cache
        server = ThreadingHTTPServer(("127.0.0.1", 0), viewer.ViewerHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            self.assertEqual(urllib.request.urlopen(base + "/frame.jpg").status, 200)
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(base + "/depth.png")
            self.assertEqual(raised.exception.code, 503)
            health = json.load(urllib.request.urlopen(base + "/health"))
            self.assertTrue(health["ready"])
            self.assertFalse(health["depthReady"])
            self.assertEqual(health["depthError"], "depth unavailable")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_malformed_host_is_rejected(self) -> None:
        """Return a bounded HTTP error for an invalid IPv6 Host header."""
        server = ThreadingHTTPServer(("127.0.0.1", 0), viewer.ViewerHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = HTTPConnection("127.0.0.1", server.server_port)
            connection.putrequest("GET", "/", skip_host=True)
            connection.putheader("Host", "[")
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(response.status, 421)
            self.assertEqual(response.read(), b"host is not allowed\n")
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_ipv6_server_uses_ipv6_address_family(self) -> None:
        """Keep the explicit IPv6 loopback server compatible with its bind."""
        self.assertEqual(viewer.IPv6ThreadingHTTPServer.address_family, socket.AF_INET6)


if __name__ == "__main__":
    unittest.main()
