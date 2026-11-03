# SPDX-License-Identifier: MulanPSL-2.0
"""Device tests without ROS, Atlas or a Minecraft process."""
from copy import deepcopy
import unittest

from minecraft_mod_driver.camera_clock_device import CameraClockDevice
from minecraft_mod_driver.clock_emulation import ClockEmulator


def capture(elapsed: int, sequence: int, session: str = "session-a", truth: int = 7) -> dict:
    """Create a shared marker with independent, quantized Linux reference evidence."""
    view = {
        "captureSessionId": session, "captureElapsedNanos": elapsed,
        "captureSequence": sequence, "renderFrameId": sequence,
        "simTimeNanos": truth, "captureReferenceMs": 1_800_000_000_000 + elapsed // 1_000_000,
        "captureReferenceUncertaintyNanos": 1_000_100,
    }
    return {"renderedView": dict(view), "depthView": dict(view)}


def device(reset: float | None = None) -> CameraClockDevice:
    """Build a no-jitter device so ordering and reset errors are unambiguous."""
    return CameraClockDevice(ClockEmulator.from_config({"clock_emulation": {
        "enabled": True, "realsense": {
            "time_base": "host_monotonic", "drift_ppm": 100, "jitter_ms": 0,
            "initial_uptime_sec": 5, "reset_at_sec": reset,
        },
    }}))


class CameraClockDeviceTest(unittest.TestCase):
    def test_paused_or_regressing_game_time_does_not_drive_device(self):
        camera = device()
        first, _ = camera.sample(capture(0, 1, truth=900), 100)
        second, _ = camera.sample(capture(1_000_000_000, 2, truth=900), 200)
        third, _ = camera.sample(capture(2_000_000_000, 3, truth=2), 300)
        self.assertEqual(second["device_timestamp_ns"] - first["device_timestamp_ns"], 1_000_100_000)
        self.assertEqual(third["device_timestamp_ns"] - second["device_timestamp_ns"], 1_000_100_000)

    def test_truth_and_reference_changes_cannot_change_observation(self):
        original = capture(2_000_000_000, 1)
        altered = deepcopy(original)
        for view in altered.values():
            view["simTimeNanos"] = 999_999_999_999
            view["captureReferenceMs"] += 10_000
        observation, score = device().sample(original, 100)
        other_observation, other_score = device().sample(altered, 1_000_000)
        self.assertEqual(observation, other_observation)
        self.assertNotEqual(score["reference_timestamp_ns"], other_score["reference_timestamp_ns"])
        self.assertEqual(set(observation), {
            "domain_id", "captureSessionId", "device_generation", "sequence_id", "device_timestamp_ns",
        })

    def test_cached_frame_is_suppressed_and_identity_reuse_rejected(self):
        camera = device()
        pair = capture(0, 1)
        camera.sample(pair, 1)
        self.assertIsNone(camera.sample(pair, 2))
        with self.assertRaisesRegex(ValueError, "reused"):
            camera.sample(capture(1_000_000, 1), 3)

    def test_reset_and_mod_restart_advance_identity(self):
        camera = device(reset=2)
        first, _ = camera.sample(capture(0, 1), 1)
        reset, _ = camera.sample(capture(2_500_000_000, 2), 2)
        continued, _ = camera.sample(capture(3_000_000_000, 3), 3)
        restarted, _ = camera.sample(capture(0, 1, session="session-b"), 4)
        self.assertEqual([first["device_generation"], reset["device_generation"], continued["device_generation"], restarted["device_generation"]], [1, 2, 2, 3])
        self.assertLess(reset["device_timestamp_ns"], first["device_timestamp_ns"])
        self.assertNotEqual(restarted["captureSessionId"], first["captureSessionId"])
        with self.assertRaisesRegex(ValueError, "stale"):
            camera.sample(capture(4_000_000_000, 4), 5)

    def test_mismatch_and_out_of_order_fail_explicitly(self):
        camera = device()
        camera.sample(capture(1_000_000_000, 4), 1)
        with self.assertRaisesRegex(ValueError, "out-of-order"):
            camera.sample(capture(500_000_000, 3), 2)
        bad = capture(2_000_000_000, 5)
        bad["renderedView"]["captureElapsedNanos"] += 1
        with self.assertRaisesRegex(ValueError, "identities differ"):
            camera.sample(bad, 3)

    def test_host_mode_cannot_silently_fall_back_to_game(self):
        emulator = device().emulator
        with self.assertRaisesRegex(ValueError, "captureElapsedNanos"):
            emulator.realsense_sample(1, 1)
        with self.assertRaisesRegex(ValueError, "host_monotonic"):
            ClockEmulator.from_config({"clock_emulation": {"realsense": {"time_base": "linux_time"}}})
        self.assertEqual(ClockEmulator.from_config({}).realsense.time_base, "game")

    def test_reference_quantization_is_not_hidden(self):
        bad = capture(0, 1)
        for view in bad.values():
            view["captureReferenceUncertaintyNanos"] = 1
        with self.assertRaisesRegex(ValueError, "millisecond quantization"):
            device().sample(bad, 1)


if __name__ == "__main__":
    unittest.main()
