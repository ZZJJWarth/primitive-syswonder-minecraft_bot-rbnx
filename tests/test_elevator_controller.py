# SPDX-License-Identifier: MulanPSL-2.0
"""Checks that the smoke tool never equates a redstone acknowledgement with arrival."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    "elevator_controller", Path(__file__).resolve().parents[1] / "scripts/elevator_controller.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def observation(y: float, dimension: str = "minecraft:overworld") -> dict:
    return {"player_online": True, "player": {"name": "robot", "uuid": "robot-uuid",
            "dimension": dimension, "x": 1.5, "y": y, "z": 1.5}}


class ControllerToolTest(unittest.TestCase):
    def test_pulse_resets_after_failure(self):
        controller = tool.Controller(Mock(), "minecraft:overworld", [0, 64, 0])
        controller.set = Mock(return_value={"game_time": 20})
        controller.wait_ticks = Mock(side_effect=[{}, TimeoutError("paused")])
        with self.assertRaisesRegex(TimeoutError, "paused"):
            controller.pulse(4, 1)
        self.assertEqual([call.args[0] for call in controller.set.call_args_list], [False, True, False])

    def test_ride_requires_actual_target_observation(self):
        controller = tool.Controller(Mock(), "minecraft:overworld", [0, 64, 0])
        controller.pulse = Mock()
        controller.client.request.side_effect = [observation(64), observation(64), observation(72)]
        with patch.object(tool.time, "sleep"):
            result = tool.ride(controller, [1, 64, 1, 2, 66, 2], [1, 72, 1, 2, 74, 2], 4, 1)
        self.assertTrue(result["arrived"])
        self.assertEqual(result["before"]["y"], 64)
        self.assertEqual(result["after"]["y"], 72)

    def test_acknowledged_output_without_teleport_times_out(self):
        controller = tool.Controller(Mock(), "minecraft:overworld", [0, 64, 0])
        controller.pulse = Mock(return_value={"powered": True})
        controller.client.request.return_value = observation(64)
        with patch.object(tool.time, "monotonic", side_effect=[0, 0.5, 2]), patch.object(tool.time, "sleep"):
            with self.assertRaisesRegex(TimeoutError, "did not reach target"):
                tool.ride(controller, [1, 64, 1, 2, 66, 2], [1, 72, 1, 2, 74, 2], 4, 1)

    def test_wrong_source_dimension_and_overlapping_bounds_do_not_trigger(self):
        for source, target, before in [
            ([1, 64, 1, 2, 66, 2], [1, 72, 1, 2, 74, 2], observation(72)),
            ([1, 64, 1, 2, 66, 2], [1, 72, 1, 2, 74, 2], observation(64, "minecraft:the_nether")),
            ([1, 64, 1, 2, 66, 2], [1, 65, 1, 2, 67, 2], observation(64)),
        ]:
            with self.subTest(before=before, target=target):
                controller = tool.Controller(Mock(), "minecraft:overworld", [0, 64, 0])
                controller.pulse = Mock()
                controller.client.request.return_value = before
                with self.assertRaises((ValueError, RuntimeError)):
                    tool.ride(controller, source, target, 4, 1)
                controller.pulse.assert_not_called()


if __name__ == "__main__":
    unittest.main()
