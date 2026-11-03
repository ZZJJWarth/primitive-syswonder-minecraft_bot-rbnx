# SPDX-License-Identifier: MulanPSL-2.0
"""Independent scoring must not invert injection parameters or use training frames."""
from copy import deepcopy
import unittest

from minecraft_mod_driver.camera_clock_score import summarize


class CameraClockScoreTest(unittest.TestCase):
    def test_scores_only_independent_reference_and_held_out_conversions(self):
        key = {"domain_id": "minecraft_realsense", "captureSessionId": "a", "device_generation": 1}
        scores = [{**key, "sequence_id": n, "reference_timestamp_ns": 1_000_000_000,
            "captureReferenceUncertaintyNanos": 1_000_000, "reference_time_scale": "linux_system",
            "reference_verified": False, "drift_ppm": 50, "injected_offset_ns": 100,
            "capture_truth_ns": 123} for n in (1, 2)]
        conversions = [{**key, "sequence_id": 1, "held_out": True, "reference_timestamp_ns": 1_002_000_000,
            "within_validity": False, "uncertainty_ns": (1 << 64) - 1},
            {**key, "sequence_id": 2, "held_out": False, "reference_timestamp_ns": 9_999_000_000}]
        result = summarize(conversions, scores)
        altered = deepcopy(scores)
        for record in altered:
            record.update(drift_ppm=99999, injected_offset_ns=-8888, capture_truth_ns=0)
        self.assertEqual(result, summarize(conversions, altered))
        self.assertEqual(result["abs_error_max_ns"], 2_000_000)
        self.assertEqual(result["converted_frames"], 1)
        self.assertEqual(result["unbounded_uncertainty_frames"], 1)
        with self.assertRaisesRegex(ValueError, "duplicate scoring"):
            summarize(conversions, scores + scores[:1])
        with self.assertRaisesRegex(ValueError, "no matching"):
            summarize(conversions, scores[:1])


if __name__ == "__main__":
    unittest.main()
