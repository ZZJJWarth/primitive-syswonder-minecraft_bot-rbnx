# SPDX-License-Identifier: MulanPSL-2.0
"""Synthetic loopback Mod -> real collector -> real Linux Chronos runtime.

Run manually after building chronos-camera-probe. This never connects to the
real Mod, ROS or a deployment, and tests no movement or image rendering.
"""
from __future__ import annotations

import json
from pathlib import Path
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import unittest

import yaml

from minecraft_mod_driver.camera_clock_score import summarize


ROOT = Path(__file__).resolve().parents[1]
PROBE_ROOT = ROOT.parent / "minecraft-time-sync-experiment" / "chronos-camera-probe"


class SyntheticMod(socketserver.TCPServer):
    """Expose only paired snapshot requests on an ephemeral loopback port."""

    def __init__(self):
        self.started = time.monotonic_ns()
        self.latest = None
        self.last_identity = None
        super().__init__(("127.0.0.1", 0), SnapshotHandler)

    def snapshot(self):
        """Create cached 10 Hz captures, keep game time paused, and restart at 12 s."""
        now = time.monotonic_ns()
        elapsed = now - self.started
        restarted = elapsed >= 12_000_000_000
        session = "synthetic-b" if restarted else "synthetic-a"
        elapsed = elapsed - 12_000_000_000 if restarted else elapsed
        sequence = elapsed // 100_000_000 + 1
        if (session, sequence) != self.last_identity:
            before = time.monotonic_ns()
            wall = time.time_ns() // 1_000_000
            after = time.monotonic_ns()
            view = {
                "captureSessionId": session, "captureElapsedNanos": elapsed,
                "captureReferenceMs": wall,
                "captureReferenceUncertaintyNanos": 1_000_000 + after - before,
                "captureSequence": sequence, "renderFrameId": sequence,
                "simTimeNanos": 123_456_789,
            }
            self.latest = {"renderedView": dict(view), "depthView": dict(view)}
            self.last_identity = (session, sequence)
        return self.latest


class SnapshotHandler(socketserver.StreamRequestHandler):
    def handle(self):
        """Reject every command other than the already existing read-only snapshot."""
        request = json.loads(self.rfile.readline(4096))
        if request["cmd"] != "rgbd_snapshot":
            raise AssertionError("unexpected command")
        response = {"id": request["id"], "ok": True, "result": self.server.snapshot()}
        self.wfile.write(json.dumps(response).encode() + b"\n")


class CameraIntegrationTest(unittest.TestCase):
    def test_models_degrade_recover_and_reset_using_only_observations(self):
        """Require held-out accuracy and identity changes across three real runtime fits."""
        binary = PROBE_ROOT / "target/debug/chronos-camera-probe"
        self.assertTrue(binary.exists(), "build chronos-camera-probe first")
        with tempfile.TemporaryDirectory(prefix="camera-clock-integration-") as directory:
            run = Path(directory)
            config = yaml.safe_load((ROOT / "config/camera-clock-host.yaml").read_text())
            config["clock_emulation"]["realsense"]["reset_at_sec"] = 9
            (run / "device.yaml").write_text(yaml.safe_dump(config))
            with SyntheticMod() as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    with (run / "conversions.jsonl").open("w") as output, (run / "quality.log").open("w") as quality:
                        probe = subprocess.Popen([str(binary), str(PROBE_ROOT / "chronos.yaml")],
                            stdin=subprocess.PIPE, stdout=output, stderr=quality, text=True)
                        collector = subprocess.Popen([sys.executable, "-m", "minecraft_mod_driver.camera_clock_device",
                            "--config", str(run / "device.yaml"), "--observations", "-",
                            "--scores", str(run / "scores.jsonl"), "--port", str(server.server_address[1]),
                            "--duration-sec", "16", "--pause-at-sec", "3", "--pause-duration-sec", "4"],
                            cwd=ROOT, stdout=probe.stdin, stderr=subprocess.PIPE, text=True)
                        probe.stdin.close()
                        try:
                            _, errors = collector.communicate(timeout=22)
                            self.assertEqual(collector.returncode, 0, errors)
                            self.assertEqual(probe.wait(timeout=5), 0, (run / "quality.log").read_text())
                        finally:
                            for process in (collector, probe):
                                if process.poll() is None:
                                    process.terminate()
                                    process.wait(timeout=5)
                finally:
                    server.shutdown()
                    thread.join(timeout=2)
            scores = [json.loads(line) for line in (run / "scores.jsonl").read_text().splitlines()]
            conversions = [json.loads(line) for line in (run / "conversions.jsonl").read_text().splitlines()]
            self.assertEqual(len(scores), len(conversions))
            self.assertEqual({record["device_generation"] for record in conversions}, {1, 2, 3})
            self.assertEqual({record["generation"] for record in conversions if "reference_timestamp_ns" in record}, {1, 2, 3})
            quality = (run / "quality.log").read_text()
            for state in ("Initializing", "Tracking", "Holdover", "Unsynced"):
                self.assertIn(state, quality)
            self.assertIn('"reference_verified":false', quality)
            for generation in (1, 2, 3):
                self.assertTrue(any(row.get("generation") == generation and row.get("state") == "Tracking"
                    for row in conversions), f"generation {generation} never tracked")
            result = summarize(conversions, scores)
            # Synthetic loopback pipeline only: this limit is not a Minecraft,
            # UTC or hardware-accuracy requirement.
            self.assertLess(result["abs_error_max_ns"], 50_000_000, result)
            self.assertGreater(result["converted_frames"], 8)
            self.assertIn("cached repeats=", errors)
            print(json.dumps(result, indent=2))


if __name__ == "__main__":
    unittest.main()
