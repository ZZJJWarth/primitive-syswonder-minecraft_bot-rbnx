# SPDX-License-Identifier: MulanPSL-2.0
"""Read-only simulated camera device over the Mod's existing RGB-D interface.

Observation JSONL contains no reference truth or injection parameters. A
separate score file records independent capture reference evidence. Chronos
must sample its own ObservationSnapshot when these observations arrive.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import json
import math
from pathlib import Path
import socket
import sys
import time
from typing import Any

from minecraft_mod_driver.clock_emulation import ClockEmulator


def _nonnegative_int(payload: dict, name: str) -> int:
    """Reject floats, booleans and missing capture counters without coercion."""
    value = payload.get(name)
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")
    return value


class CameraClockDevice:
    """Emit one host-driven clock observation per unique paired render frame."""

    def __init__(self, emulator: ClockEmulator) -> None:
        """Require host mode and retain ordering per capture session."""
        if not emulator.enabled or emulator.realsense.time_base != "host_monotonic":
            raise ValueError("camera device requires enabled host_monotonic clock emulation")
        self.emulator = emulator
        self._session: str | None = None
        self._last_sequence = -1
        self._last_frame = -1
        self._last_elapsed = -1
        self._retired: set[str] = set()

    def sample(self, result: dict, received_monotonic_ns: int) -> tuple[dict, dict] | None:
        """Validate capture identity, suppress cache repeats and split fit/score data.

        Linux reference evidence is read only after device sampling and never
        influences the device timestamp or its generation. RGB and depth must
        share the canonical host marker as well as their render identity.
        """
        rgb = result.get("renderedView")
        depth = result.get("depthView")
        if not isinstance(rgb, dict) or not isinstance(depth, dict):
            raise ValueError("paired renderedView and depthView are required")
        identity_fields = ("captureSessionId", "captureSequence", "renderFrameId", "captureElapsedNanos")
        if any(rgb.get(key) != depth.get(key) for key in identity_fields):
            raise ValueError("RGB-D capture identities differ")
        session = depth.get("captureSessionId")
        if not isinstance(session, str) or not session.strip():
            raise ValueError("captureSessionId is required; rebuild the Mod")
        sequence = _nonnegative_int(depth, "captureSequence")
        frame = _nonnegative_int(depth, "renderFrameId")
        elapsed = _nonnegative_int(depth, "captureElapsedNanos")
        if frame == 0:
            raise ValueError("unpaired render frame cannot provide a camera observation")
        if session in self._retired:
            raise ValueError("stale capture session")
        same_session = session == self._session
        if same_session and (sequence == self._last_sequence or frame == self._last_frame):
            if (sequence, frame, elapsed) != (self._last_sequence, self._last_frame, self._last_elapsed):
                raise ValueError("capture identity was reused with different metadata")
            return None
        if same_session and (sequence < self._last_sequence or frame < self._last_frame or elapsed <= self._last_elapsed):
            raise ValueError("out-of-order camera capture")
        # Game truth is diagnostic only; even a paused or regressing game clock
        # cannot change the host-driven device stamp.
        truth = _nonnegative_int(depth, "simTimeNanos")
        sample = self.emulator.realsense_sample(
            truth, sequence, host_elapsed_ns=elapsed, capture_session_id=session,
        )
        observation = {
            "domain_id": "minecraft_realsense",
            "captureSessionId": session,
            "device_generation": sample.device_generation,
            "sequence_id": sequence,
            "device_timestamp_ns": sample.device_timestamp_ns,
        }
        reference_ms = _nonnegative_int(depth, "captureReferenceMs")
        uncertainty = _nonnegative_int(depth, "captureReferenceUncertaintyNanos")
        if rgb.get("captureReferenceMs") != reference_ms or rgb.get("captureReferenceUncertaintyNanos") != uncertainty:
            raise ValueError("RGB-D reference evidence differs")
        if uncertainty < 1_000_000:
            raise ValueError("reference uncertainty must include millisecond quantization")
        score = {
            **observation,
            **sample.payload(),
            "renderFrameId": frame,
            "captureElapsedNanos": elapsed,
            "captureReferenceMs": reference_ms,
            "captureReferenceUncertaintyNanos": uncertainty,
            "reference_timestamp_ns": reference_ms * 1_000_000,
            "reference_time_scale": "linux_system",
            "reference_verified": False,
            "collector_receive_monotonic_ns": received_monotonic_ns,
        }
        if not same_session and self._session is not None:
            self._retired.add(self._session)
        self._session = session
        self._last_sequence, self._last_frame, self._last_elapsed = sequence, frame, elapsed
        return observation, score


def read_rgbd(host: str, port: int, timeout: float, request_id: int) -> dict:
    """Request one cached pair without movement, using a bounded loopback response."""
    identity = str(request_id)
    request = json.dumps({"id": identity, "cmd": "rgbd_snapshot"}).encode() + b"\n"
    with socket.create_connection((host, port), timeout=timeout) as connection:
        connection.sendall(request)
        with connection.makefile("rb") as reader:
            raw = reader.readline(64 * 1024 * 1024 + 1)
    if len(raw) > 64 * 1024 * 1024 or not raw.endswith(b"\n"):
        raise ValueError("truncated or oversized Mod response")
    response = json.loads(raw)
    if str(response.get("id")) != identity or response.get("ok") is not True:
        raise ValueError(f"Mod RGB-D request failed: {response.get('error')}")
    result = response.get("result")
    if not isinstance(result, dict):
        raise ValueError("Mod result must be an object")
    return result


def main(argv: list[str] | None = None) -> int:
    """Collect a finite stationary run; pause emission while capture and Chronos continue."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="provider-format clock_emulation YAML")
    parser.add_argument("--observations", required=True, help="new JSONL path or - for stdout")
    parser.add_argument("--scores", type=Path, required=True, help="separate new scoring JSONL path")
    parser.add_argument("--duration-sec", type=float, default=30)
    parser.add_argument("--hz", type=float, default=20)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25576)
    parser.add_argument("--timeout-sec", type=float, default=5)
    parser.add_argument("--pause-at-sec", type=float, default=None)
    parser.add_argument("--pause-duration-sec", type=float, default=0)
    args = parser.parse_args(argv)
    if any(not math.isfinite(x) or x <= 0 for x in (args.duration_sec, args.hz, args.timeout_sec)):
        parser.error("duration, hz and timeout must be finite and positive")
    if not math.isfinite(args.pause_duration_sec) or args.pause_duration_sec < 0:
        parser.error("pause duration must be finite and non-negative")
    if args.pause_at_sec is not None and (not math.isfinite(args.pause_at_sec) or args.pause_at_sec < 0):
        parser.error("pause start must be finite and non-negative")
    import yaml
    device = CameraClockDevice(ClockEmulator.from_config(yaml.safe_load(args.config.read_text())))
    if args.observations != "-" and Path(args.observations).resolve() == args.scores.resolve():
        parser.error("observations and scores must use different files")
    with ExitStack() as stack:
        observations = sys.stdout if args.observations == "-" else stack.enter_context(open(args.observations, "x"))
        scores = stack.enter_context(args.scores.open("x"))
        started = time.monotonic()
        request_id = 0
        emitted = 0
        duplicates = 0
        while time.monotonic() - started < args.duration_sec:
            poll_started = time.monotonic()
            elapsed = poll_started - started
            paused = args.pause_at_sec is not None and args.pause_at_sec <= elapsed < args.pause_at_sec + args.pause_duration_sec
            if not paused:
                request_id += 1
                result = read_rgbd(args.host, args.port, args.timeout_sec, request_id)
                pair = device.sample(result, time.monotonic_ns())
                if pair is None:
                    duplicates += 1
                else:
                    observation, score = pair
                    scores.write(json.dumps(score, separators=(",", ":")) + "\n")
                    scores.flush()
                    observations.write(json.dumps(observation, separators=(",", ":")) + "\n")
                    observations.flush()
                    emitted += 1
            time.sleep(max(0, 1 / args.hz - (time.monotonic() - poll_started)))
        print(f"camera observations={emitted}, cached repeats={duplicates}", file=sys.stderr)
        if emitted == 0:
            raise RuntimeError("run produced no camera observations")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(f"camera clock failed: {error}", file=sys.stderr)
        raise SystemExit(1)
