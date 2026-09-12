# SPDX-License-Identifier: MulanPSL-2.0
"""Unit tests for deterministic hardware-clock emulation."""
from __future__ import annotations

import unittest

from minecraft_mod_driver.clock_emulation import ClockEmulator


class ClockEmulatorTest(unittest.TestCase):
    def test_default_is_disabled_and_ptp_is_locked(self) -> None:
        emulator = ClockEmulator.from_config({})
        self.assertFalse(emulator.enabled)
        sample = emulator.lidar_ptp_sample(10_000_000_000, 1)
        self.assertEqual(sample.state, "LOCKED")
        self.assertLessEqual(abs(sample.injected_offset_ns - 100_000), 50_000)

    def test_realsense_uses_uptime_drift_and_resets(self) -> None:
        emulator = ClockEmulator.from_config({
            "clock_emulation": {
                "enabled": True,
                "realsense": {
                    "initial_uptime_sec": 5,
                    "drift_ppm": 100,
                    "jitter_ms": 0,
                    "reset_at_sec": 10,
                },
            },
        })
        first = emulator.realsense_sample(100_000_000_000, 1)
        before_reset = emulator.realsense_sample(109_000_000_000, 2)
        after_reset = emulator.realsense_sample(111_000_000_000, 3)
        self.assertEqual(first.device_timestamp_ns, 5_000_500_000)
        self.assertEqual(before_reset.device_timestamp_ns, 14_001_400_000)
        self.assertEqual(after_reset.device_timestamp_ns, 1_000_100_000)
        self.assertEqual(after_reset.state, "RESET")
        self.assertLess(after_reset.device_timestamp_ns, before_reset.device_timestamp_ns)

    def test_ptp_loss_holdover_and_slew_relock(self) -> None:
        emulator = ClockEmulator.from_config({
            "clock_emulation": {
                "enabled": True,
                "lidar_ptp": {
                    "initial_offset_ms": 20,
                    "locked_offset_us": 100,
                    "locked_jitter_us": 0,
                    "lock_after_sec": 2,
                    "oscillator_drift_ppm": 100,
                    "lose_lock_at_sec": 5,
                    "relock_at_sec": 10,
                    "relock_duration_sec": 4,
                    "relock_mode": "slew",
                },
            },
        })
        origin = 1_000_000_000
        initializing = emulator.lidar_ptp_sample(origin, 1)
        locked = emulator.lidar_ptp_sample(origin + 3_000_000_000, 2)
        holdover = emulator.lidar_ptp_sample(origin + 8_000_000_000, 3)
        relocking = emulator.lidar_ptp_sample(origin + 12_000_000_000, 4)
        recovered = emulator.lidar_ptp_sample(origin + 15_000_000_000, 5)
        self.assertEqual(initializing.state, "INITIALIZING")
        self.assertEqual(initializing.injected_offset_ns, 20_000_000)
        self.assertEqual(locked.state, "LOCKED")
        self.assertEqual(locked.injected_offset_ns, 100_000)
        self.assertEqual(holdover.state, "HOLDOVER")
        self.assertEqual(holdover.injected_offset_ns, 400_000)
        self.assertEqual(relocking.state, "RELOCKING")
        self.assertEqual(relocking.injected_offset_ns, 350_000)
        self.assertEqual(recovered.state, "LOCKED")
        self.assertEqual(recovered.injected_offset_ns, 100_000)

    def test_noise_is_sequence_deterministic(self) -> None:
        config = {
            "clock_emulation": {
                "enabled": True,
                "seed": 42,
                "lidar_ptp": {"locked_jitter_us": 50},
            },
        }
        first = ClockEmulator.from_config(config).lidar_ptp_sample(1_000_000_000, 9)
        second = ClockEmulator.from_config(config).lidar_ptp_sample(1_000_000_000, 9)
        self.assertEqual(first, second)

    def test_invalid_timeline_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "earlier lose_lock"):
            ClockEmulator.from_config({
                "clock_emulation": {
                    "lidar_ptp": {"relock_at_sec": 5},
                },
            })


if __name__ == "__main__":
    unittest.main()
