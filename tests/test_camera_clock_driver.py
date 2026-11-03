# SPDX-License-Identifier: MulanPSL-2.0
"""Host-clock raw publication and cache-repeat checks without ROS or Minecraft."""
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import minecraft_mod_driver.driver as driver


class CameraDriverClockTest(unittest.TestCase):
    def test_host_clock_raw_and_primary_share_marker_and_skip_repeated_capture(self):
        emulator = driver.ClockEmulator.from_config({"clock_emulation": {
            "enabled": True, "realsense": {
                "time_base": "host_monotonic", "drift_ppm": 0, "jitter_ms": 0,
            }, "mapping_test": {"primary_timestamp_mode": "device"},
        }})
        first = {"depthView": {"simTimeNanos": 123, "captureSequence": 1,
            "captureSessionId": "a", "captureElapsedNanos": 0, "verticalFovDeg": 70}}
        second = {"depthView": {**first["depthView"], "captureSequence": 2,
            "captureElapsedNanos": 1_000_000_000}}
        image = SimpleNamespace(header=SimpleNamespace(stamp=SimpleNamespace(sec=0, nanosec=0)), width=1, height=1)
        diagnostics = []

        def diagnostic(sample, payload):
            """Stop after two unique paired frames, including primary and raw reports."""
            diagnostics.append(sample)
            if len(diagnostics) == 4:
                driver.camera_stream_stop.set()

        driver.camera_stream_stop.clear()
        try:
            with (
                patch.object(driver, "use_sim_time", True),
                patch.object(driver, "clock_emulator", emulator),
                patch.object(driver.client, "request", side_effect=[first, first, second]) as request,
                patch.object(driver, "rendered_view_to_rgb_image", return_value=image),
                patch.object(driver, "depth_view_to_image", return_value=image),
                patch.object(driver, "publish_ros_image") as images,
                patch.object(driver, "publish_intrinsics_to"),
                patch.object(driver, "publish_clock_diagnostic", side_effect=diagnostic),
                patch.object(driver, "log_stream_error") as errors,
            ):
                driver.camera_stream_loop()
        finally:
            driver.camera_stream_stop.clear()
        errors.assert_not_called()
        self.assertEqual(request.call_count, 3)
        self.assertEqual(images.call_count, 8)
        self.assertEqual([row.device_timestamp_ns for row in diagnostics],
            [5_000_000_000, 5_000_000_000, 6_000_000_000, 6_000_000_000])
        descriptor, _ = driver.configured_sensor_time_descriptors(emulator)
        self.assertFalse(descriptor.is_hardware_timestamped)
        self.assertEqual(descriptor.timestamp_semantics, "acquisition")


if __name__ == "__main__":
    unittest.main()
