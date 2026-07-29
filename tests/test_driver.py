# SPDX-License-Identifier: MulanPSL-2.0
"""Smoke tests for the Forge Mod provider protocol and image conversion."""
from __future__ import annotations

import base64
import json
import math
import os
import socket
import sys
import threading
import tomllib
import unittest
from io import BytesIO
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import Mock, patch

from PIL import Image as PILImage

import minecraft_mod_driver.driver as driver
from minecraft_mod_driver.driver import (
    MinecraftModClient,
    configured_eye_height,
    configured_frequency,
    configured_lidar_range,
    configured_lidar_ray_count,
    depth_view_to_image,
    execute_move_command,
    observation_pose,
    observation_timestamp,
    lidar_scan_values,
    quaternion_from_yaw_pitch,
    rendered_view_to_rgb_image,
    rendered_view_to_image,
    semantic_objects_payload,
)


class MinecraftModClientTest(unittest.TestCase):
    """Exercises the provider side without launching Minecraft."""

    def test_providers_own_contracts_under_matching_namespaces(self) -> None:
        """Keep camera, chassis, and exact perception declarations namespace-valid."""
        self.assertEqual(driver.minecraft_camera.namespace, "robonix/primitive/camera")
        self.assertEqual(driver.minecraft_chassis.namespace, "robonix/primitive/chassis")
        self.assertEqual(driver.minecraft_perception.namespace, "robonix/primitive/perception")
        self.assertEqual(driver.minecraft_lidar.namespace, "robonix/primitive/lidar")
        self.assertEqual(driver.minecraft_actions.namespace, "robonix/primitive/minecraft")

    def test_deposit_prompt_requires_conversion_aim_and_manual(self) -> None:
        """Keep the VLM-facing contract explicit about all recovery preconditions."""
        contract = (
            Path(__file__).resolve().parents[1]
            / "capabilities"
            / "minecraft"
            / "deposit_item.v1.toml"
        )
        with contract.open("rb") as stream:
            description = tomllib.load(stream)["contract"]["description"]
        self.assertIn("native Minecraft BlockPos", description)
        self.assertIn("x=floor(scene_x)", description)
        self.assertIn("first call aim_at_block", description)
        self.assertIn("call minecraft_manual.manual_lookup", description)
        self.assertIn("before any retry", description)

    def test_lidar_scan_normalizes_no_return_and_preserves_angles(self) -> None:
        """Convert JSON null to the standard positive-infinity LaserScan return."""
        result = {
            "lidarScan": {
                "timestampMs": 1_750_000_000_250,
                "angleMin": -math.pi,
                "angleMax": -math.pi + 15 * math.pi / 8,
                "angleIncrement": math.pi / 8,
                "rangeMin": 0.05,
                "rangeMax": 16.0,
                "ranges": [None, 2.5] + [4.0] * 14,
            },
        }

        scan = lidar_scan_values(result)

        self.assertTrue(math.isinf(scan["ranges"][0]))
        self.assertEqual(scan["ranges"][1], 2.5)
        self.assertAlmostEqual(scan["timestamp"], 1_750_000_000.25)
        self.assertAlmostEqual(scan["angle_increment"], math.pi / 8)

    def test_lidar_scan_rejects_inconsistent_angular_metadata(self) -> None:
        """Do not publish a scan whose declared angles disagree with its samples."""
        result = {
            "lidarScan": {
                "timestampMs": 1_750_000_000_250,
                "angleMin": -math.pi,
                "angleMax": math.pi,
                "angleIncrement": math.pi / 8,
                "rangeMin": 0.05,
                "rangeMax": 16.0,
                "ranges": [None] * 16,
            },
        }
        with self.assertRaisesRegex(ValueError, "angular metadata"):
            lidar_scan_values(result)

    def test_lidar_configuration_is_bounded(self) -> None:
        """Reject scan settings that can overload the client thread or leave the map."""
        self.assertEqual(configured_lidar_ray_count({"lidar_ray_count": 180}), 180)
        self.assertEqual(configured_lidar_range({"lidar_range_max": 16}), 16.0)
        with self.assertRaisesRegex(ValueError, "16 through 1440"):
            configured_lidar_ray_count({"lidar_ray_count": 8})
        with self.assertRaisesRegex(ValueError, "1 through 64"):
            configured_lidar_range({"lidar_range_max": float("inf")})

    def test_inventory_maps_all_stable_slots(self) -> None:
        """Translate the exact 36 main slots and offhand slot without mutation."""
        slots = [
            {"slot": slot, "item": "minecraft:air", "count": 0}
            for slot in range(36)
        ]
        slots.append({"slot": 40, "item": "minecraft:dirt", "count": 2})
        with patch.object(
            driver.client,
            "request",
            return_value={"selected_slot": 3, "slots": slots},
        ) as request:
            response = driver.inventory_mcp(driver.minecraft_mcp.Inventory_Request())
        request.assert_called_once_with(
            {"cmd": "inventory"},
            timeout_sec=max(driver.client.timeout, driver.ACTION_REQUEST_TIMEOUT_SEC),
        )
        self.assertEqual(response.selected_slot, 3)
        self.assertEqual((response.slots[-1].slot, response.slots[-1].count), (40, 2))

    def test_inventory_rejects_inconsistent_empty_slot(self) -> None:
        """Reject a Mod response that pairs air with a nonzero count."""
        slots = [
            {"slot": slot, "item": "minecraft:air", "count": int(slot == 0)}
            for slot in range(36)
        ]
        slots.append({"slot": 40, "item": "minecraft:air", "count": 0})
        with patch.object(driver.client, "request", return_value={"selected_slot": 0, "slots": slots}):
            with self.assertRaisesRegex(RuntimeError, "inconsistent"):
                driver.inventory_mcp(driver.minecraft_mcp.Inventory_Request())

    def test_get_state_returns_fresh_minecraft_world_pose(self) -> None:
        """Validate and expose a compact dynamic snapshot without mutation."""
        observation = {
            "timestamp": "2026-07-23T04:31:02.315Z",
            "coordinateFrame": "minecraft_world",
            "dimension": "minecraft:overworld",
            "position": {"x": 12.35, "y": 64.0, "z": -7.82},
            "yaw": 1.25,
            "pitch": -0.2,
            "onGround": True,
            "health": 20.0,
            "food": 18,
            "gameMode": "survival",
        }
        with patch.object(driver.client, "request", return_value=observation) as request:
            response = driver.get_state_mcp(driver.minecraft_mcp.GetState_Request())
        request.assert_called_once_with({"cmd": "observe"})
        self.assertEqual((response.block_x, response.block_y, response.block_z), (12, 64, -8))
        self.assertEqual(response.coordinate_frame, "minecraft_world")
        self.assertAlmostEqual(response.yaw_rad, 1.25)
        self.assertEqual(response.navigation_frame, driver.odom_frame_id)
        self.assertAlmostEqual(response.navigation_x, 12.35)
        self.assertAlmostEqual(response.navigation_y, 7.82)
        self.assertAlmostEqual(
            response.navigation_yaw_rad,
            math.atan2(math.sin(1.25 + math.pi / 2.0), math.cos(1.25 + math.pi / 2.0)),
        )

    def test_get_state_rejects_nonfinite_pose(self) -> None:
        """Never expose an unusable live-state block to Pilot."""
        observation = {
            "timestamp": "2026-07-23T04:31:02.315Z",
            "coordinateFrame": "minecraft_world",
            "dimension": "minecraft:overworld",
            "position": {"x": float("nan"), "y": 64.0, "z": -7.82},
            "yaw": 1.25,
            "pitch": -0.2,
            "onGround": True,
            "health": 20.0,
            "food": 18,
            "gameMode": "survival",
        }
        with patch.object(driver.client, "request", return_value=observation):
            with self.assertRaisesRegex(RuntimeError, "non-finite"):
                driver.get_state_mcp(driver.minecraft_mcp.GetState_Request())

    def test_break_block_targets_crosshair_and_verifies_pickup(self) -> None:
        """Request the crosshair target and require verified dirt pickup."""
        request_message = driver.minecraft_mcp.BreakBlock_Request(
            expected_block="minecraft:dirt", collect=True,
        )
        result = {
            "block_broken": True,
            "original_block": "minecraft:dirt",
            "collected_item": "minecraft:dirt",
            "collected_count": 1,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.break_block_mcp(request_message)
        self.assertEqual(response.collected_count, 1)
        self.assertEqual(request.call_args.args[0], {
            "cmd": "break_block",
            "expected_block": "minecraft:dirt",
            "collect": True,
        })

    def test_aim_at_block_converts_scene_centroid_and_verifies_live_hit(self) -> None:
        """Convert the selected Scene block centre instead of relying on VLM arithmetic."""
        message = driver.minecraft_mcp.AimAtBlock_Request(
            navigation_x=9.5,
            navigation_y=-7.5,
            navigation_z=0.5,
            expected_block="minecraft:chest",
        )
        result = {
            "aimed": True,
            "actual_block": "minecraft:chest",
            "hit_face": "north",
            "final_yaw_deg": 35.5,
            "final_pitch_deg": -12.25,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.aim_at_block_mcp(message)
        self.assertTrue(response.aimed)
        self.assertEqual(response.hit_face, "north")
        self.assertEqual(request.call_args.args[0], {
            "cmd": "aim_at_block",
            "x": 9,
            "y": 0,
            "z": 7,
            "expected_block": "minecraft:chest",
        })

    def test_aim_at_block_rejects_unverified_mod_result(self) -> None:
        """Reject mismatched blocks, invalid faces, and impossible final pitch."""
        message = driver.minecraft_mcp.AimAtBlock_Request(
            navigation_x=2.5,
            navigation_y=2.5,
            navigation_z=64.5,
            expected_block="minecraft:chest",
        )
        valid = {
            "aimed": True,
            "actual_block": "minecraft:chest",
            "hit_face": "north",
            "final_yaw_deg": 35.5,
            "final_pitch_deg": -12.25,
        }
        cases = (
            ({**valid, "actual_block": "minecraft:dirt"}, "crosshair target"),
            ({**valid, "hit_face": "inside"}, "hit face"),
            ({**valid, "final_pitch_deg": 91.0}, "final pitch"),
        )
        for result, error in cases:
            with self.subTest(error=error), patch.object(
                driver.client, "request", return_value=result,
            ):
                with self.assertRaisesRegex(RuntimeError, error):
                    driver.aim_at_block_mcp(message)

    def test_aim_at_block_rejects_nonfinite_scene_centroid(self) -> None:
        """Do not turn a malformed Scene coordinate into an arbitrary BlockPos."""
        message = driver.minecraft_mcp.AimAtBlock_Request(
            navigation_x=float("nan"),
            navigation_y=-7.5,
            navigation_z=0.5,
            expected_block="minecraft:crafting_table",
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "must be finite"):
                driver.aim_at_block_mcp(message)
        request.assert_not_called()

    def test_aim_at_block_rejects_robot_or_approach_pose(self) -> None:
        """Only exact half-block Scene centroids may cross the frame boundary."""
        message = driver.minecraft_mcp.AimAtBlock_Request(
            navigation_x=10.401299,
            navigation_y=-6.178009,
            navigation_z=0.5,
            expected_block="minecraft:crafting_table",
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "exact Scene block-centre"):
                driver.aim_at_block_mcp(message)
        request.assert_not_called()

    def test_break_block_rejects_unsupported_block_before_request(self) -> None:
        """Reject blocks outside the confirmed dirt and crafting-table set."""
        message = driver.minecraft_mcp.BreakBlock_Request(expected_block="minecraft:stone")
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "minecraft:crafting_table"):
                driver.break_block_mcp(message)
        request.assert_not_called()

    def test_break_block_verifies_crafting_table_pickup(self) -> None:
        """Accept a crafting table and require the matching collected item."""
        message = driver.minecraft_mcp.BreakBlock_Request(
            expected_block="minecraft:crafting_table", collect=True,
        )
        result = {
            "block_broken": True,
            "original_block": "minecraft:crafting_table",
            "collected_item": "minecraft:crafting_table",
            "collected_count": 1,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.break_block_mcp(message)
        self.assertEqual(response.collected_item, "minecraft:crafting_table")
        self.assertEqual(request.call_args.args[0]["expected_block"], "minecraft:crafting_table")

        result["collected_item"] = "minecraft:dirt"
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "unexpected collected item"):
                driver.break_block_mcp(message)

    def test_deposit_item_requires_verified_exact_count_changes(self) -> None:
        """Return the synchronized player and chest counts for one dirt transfer."""
        message = driver.minecraft_mcp.DepositItem_Request(
            x=2, y=64, z=1, item="minecraft:dirt", count=1,
        )
        result = {
            "transferred_count": 1,
            "player_count_before": 2,
            "player_count_after": 1,
            "container_count_before": 5,
            "container_count_after": 6,
        }
        with patch.object(driver.client, "request", return_value=result):
            response = driver.deposit_item_mcp(message)
        self.assertEqual(response.transferred_count, 1)
        self.assertEqual((response.player_count_after, response.container_count_after), (1, 6))

        result["container_count_after"] = 5
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "container counts"):
                driver.deposit_item_mcp(message)

    def test_deposit_item_accepts_one_crafting_table(self) -> None:
        """Forward a supported crafting table deposit without changing its item name."""
        message = driver.minecraft_mcp.DepositItem_Request(
            x=2, y=64, z=1, item="minecraft:crafting_table", count=1,
        )
        result = {
            "transferred_count": 1,
            "player_count_before": 1,
            "player_count_after": 0,
            "container_count_before": 0,
            "container_count_after": 1,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.deposit_item_mcp(message)
        self.assertEqual(response.container_count_after, 1)
        self.assertEqual(request.call_args.args[0]["item"], "minecraft:crafting_table")

    def test_deposit_item_rejects_unsupported_item(self) -> None:
        """Reject items outside the confirmed dirt and crafting-table set."""
        message = driver.minecraft_mcp.DepositItem_Request(
            item="minecraft:stone", count=1,
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "minecraft:crafting_table"):
                driver.deposit_item_mcp(message)
        request.assert_not_called()

    def test_action_timeout_remains_longer_than_mod_deadline(self) -> None:
        """Override a short general timeout for timing-sensitive item actions."""
        previous = driver.client.timeout
        driver.client.timeout = 1.0
        try:
            with patch.object(driver.client, "request", return_value={}) as request:
                driver.action_request({"cmd": "inventory"})
        finally:
            driver.client.timeout = previous
        request.assert_called_once_with(
            {"cmd": "inventory"},
            timeout_sec=driver.ACTION_REQUEST_TIMEOUT_SEC,
        )

    def test_request_round_trip(self) -> None:
        """Preserve request ids and return the Mod result object."""
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def serve() -> None:
            """Return one valid response from a temporary loopback listener."""
            with listener:
                connection, _ = listener.accept()
                with connection:
                    request = json.loads(connection.recv(4096).split(b"\n", 1)[0])
                    response = {"id": request["id"], "ok": True, "result": {"health": 20}}
                    connection.sendall(json.dumps(response).encode("utf-8") + b"\n")

        thread = threading.Thread(target=serve)
        thread.start()
        client = MinecraftModClient()
        client.port = port
        self.assertEqual(client.request({"cmd": "observe"}), {"health": 20})
        thread.join(timeout=2)
        self.assertFalse(thread.is_alive())

    def test_client_rejects_unsafe_request_timeout(self) -> None:
        """Reject request deadlines that are non-finite, non-positive, or excessive."""
        for value in ("nan", "inf", "0", "301"):
            with self.subTest(value=value), patch.dict(
                os.environ,
                {"RBNX_MINECRAFT_MOD_TIMEOUT_SEC": value},
            ):
                with self.assertRaises(ValueError):
                    MinecraftModClient()

    def test_rendered_view_to_image(self) -> None:
        """Decode the confirmed JPEG response shape without modifying bytes."""
        jpeg = b"\xff\xd8test\xff\xd9"
        result = {
            "renderedView": {
                "width": 320,
                "height": 180,
                "image": {
                    "dataUrl": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii"),
                },
            },
        }
        image = rendered_view_to_image(result)
        self.assertEqual(image.encoding, "jpeg")
        self.assertEqual((image.width, image.height), (320, 180))
        self.assertEqual(image.data, jpeg)

    def test_rendered_view_to_rgb_image_is_uncompressed(self) -> None:
        """Decode the snapshot JPEG into the continuous RGB8 wire shape."""
        buffer = BytesIO()
        PILImage.new("RGB", (2, 1), (20, 40, 60)).save(buffer, format="JPEG")
        result = {
            "renderedView": {
                "width": 2,
                "height": 1,
                "image": {
                    "dataUrl": "data:image/jpeg;base64,"
                    + base64.b64encode(buffer.getvalue()).decode("ascii"),
                },
            },
        }
        image = rendered_view_to_rgb_image(result)
        self.assertEqual((image.encoding, image.step), ("rgb8", 6))
        self.assertEqual((image.width, image.height, len(image.data)), (2, 1, 6))
        self.assertEqual(image.header.frame_id, driver.camera_frame_id)
        self.assertTrue(all(abs(actual - expected) <= 2 for actual, expected in zip(
            image.data[:3], (20, 40, 60), strict=True,
        )))

    def test_depth_view_to_image_preserves_float_payload(self) -> None:
        """Return raw little-endian 32FC1 bytes with exact dimensions."""
        depth = b"\x00\x00\x80?\x00\x00\x00@"
        result = {
            "depthView": {
                "width": 2,
                "height": 1,
                "timestampMs": 1_700_000_000_123,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64,"
                    + base64.b64encode(depth).decode("ascii"),
                },
            },
        }
        image = depth_view_to_image(result)
        self.assertEqual((image.width, image.height, image.step), (2, 1, 8))
        self.assertEqual((image.encoding, image.is_bigendian), ("32FC1", 0))
        self.assertEqual(image.data, depth)
        self.assertEqual(image.header.stamp.sec, 1_700_000_000)
        self.assertAlmostEqual(image.header.stamp.nanosec, 123_000_000, delta=1000)

    def test_depth_view_to_image_rejects_wrong_payload_size(self) -> None:
        """Surface a zero-size error image for a truncated depth payload."""
        result = {
            "depthView": {
                "width": 2,
                "height": 1,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64,AAAAAA==",
                },
            },
        }
        image = depth_view_to_image(result)
        self.assertEqual((image.width, image.height, image.encoding), (0, 0, "error"))

    def test_observation_pose_uses_ros_enu_view_origin(self) -> None:
        """Map Minecraft east/south position and north-zero yaw into ROS ENU."""
        observation = {
            "position": {"x": 4.0, "y": 64.0, "z": 3.0},
            "yaw": 0.0,
            "pitch": 0.25,
        }
        x, y, z, yaw, pitch = observation_pose(observation)
        self.assertEqual((x, y), (4.0, -3.0))
        self.assertAlmostEqual(z, 65.62)
        self.assertAlmostEqual(yaw, math.pi / 2.0)
        self.assertEqual(pitch, 0.25)

    def test_observation_timestamp_uses_client_capture_time(self) -> None:
        """Convert the existing client observation timestamp into ROS Unix time."""
        timestamp = observation_timestamp({"timestamp": "2023-11-14T22:13:20.123Z"})
        self.assertAlmostEqual(timestamp, 1_700_000_000.123, places=3)
        with self.assertRaisesRegex(ValueError, "timestamp"):
            observation_timestamp({"timestamp": ""})

    def test_semantic_objects_payload_converts_block_centers_to_ros_enu(self) -> None:
        """Publish exact labels and centered block coordinates in the odometry frame."""
        payload = semantic_objects_payload({
            "timestamp": "2023-11-14T22:13:20.123Z",
            "semanticBlocks": [{
                "id": "minecraft:overworld@4,64,3",
                "name": "minecraft:crafting_table",
                "position": {"x": 4, "y": 64, "z": 3},
                "lastSeen": "2023-11-14T22:13:19.000Z",
            }],
        })
        self.assertEqual(payload["frame_id"], driver.odom_frame_id)
        self.assertEqual(payload["objects"][0]["label"], "minecraft:crafting_table")
        self.assertEqual(
            (payload["objects"][0]["x"], payload["objects"][0]["y"], payload["objects"][0]["z"]),
            (4.5, -3.5, 64.5),
        )
        self.assertEqual(payload["objects"][0]["last_seen_unix"], 1_699_999_999.0)

    def test_view_quaternion_is_normalized(self) -> None:
        """Represent yaw and positive-down pitch with the expected ROS axes."""
        quaternion = quaternion_from_yaw_pitch(math.pi / 2.0, 0.25)
        self.assertAlmostEqual(sum(value * value for value in quaternion), 1.0)
        neutral = quaternion_from_yaw_pitch(0.0, 0.0)
        self.assertEqual(neutral, (-0.0, 0.0, 0.0, 1.0))
        down = quaternion_from_yaw_pitch(0.0, math.pi / 2.0)
        self.assertAlmostEqual(down[1], math.sqrt(0.5))
        self.assertAlmostEqual(down[3], math.sqrt(0.5))

    def test_configured_frequency_rejects_nonfinite_or_out_of_range(self) -> None:
        """Reject rates that would kill a worker or create a tight request loop."""
        for value in ("nan", "inf", "0", "61"):
            with self.subTest(value=value), patch.dict(os.environ, {"TEST_STREAM_HZ": value}):
                with self.assertRaises(ValueError):
                    configured_frequency("TEST_STREAM_HZ", 5.0)

    def test_configured_eye_height_preserves_zero_and_rejects_invalid_values(self) -> None:
        """Accept an explicit zero while rejecting non-finite or implausible heights."""
        self.assertEqual(configured_eye_height({"eye_height": 0.0}), 0.0)
        for value in ("nan", "inf", "-0.1", "3.1"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    configured_eye_height({"eye_height": value})

    def test_camera_stream_failure_does_not_suppress_depth(self) -> None:
        """Publish depth even when the independent RGB conversion fails."""
        depth = b"\x00\x00\x80?"
        depth_result = {
            "depthView": {
                "width": 1,
                "height": 1,
                "verticalFovDeg": 70.0,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64,"
                    + base64.b64encode(depth).decode("ascii"),
                },
            },
        }
        published: list[str] = []

        def publish(_publisher, image) -> None:
            """Record valid output and stop the single test iteration."""
            if image.encoding == "error":
                raise RuntimeError("invalid RGB")
            published.append(image.encoding)
            driver.camera_stream_stop.set()

        driver.camera_stream_stop.clear()
        try:
            with (
                patch.object(driver.client, "request", side_effect=[{}, depth_result]),
                patch.object(driver, "publish_intrinsics") as publish_intrinsics,
                patch.object(driver, "publish_ros_image", side_effect=publish),
                patch.object(driver, "log_stream_error"),
            ):
                driver.camera_stream_loop()
        finally:
            driver.camera_stream_stop.clear()
        self.assertEqual(published, ["32FC1"])
        publish_intrinsics.assert_called_once_with(1, 1, 70.0)

    def test_publish_intrinsics_uses_depth_shape_and_vertical_fov(self) -> None:
        """Publish a latched-compatible pinhole matrix for the captured frame."""
        class FakeCameraInfo:
            """Provide mutable sensor_msgs fields populated by the provider."""

            def __init__(self) -> None:
                """Initialize the ROS message fields needed by the helper."""
                self.header = SimpleNamespace(frame_id="")
                self.width = 0
                self.height = 0
                self.distortion_model = ""
                self.d: list[float] = []
                self.k: list[float] = []
                self.r: list[float] = []
                self.p: list[float] = []

        sensor_msgs = ModuleType("sensor_msgs")
        sensor_msgs_msg = ModuleType("sensor_msgs.msg")
        sensor_msgs_msg.CameraInfo = FakeCameraInfo
        sensor_msgs.msg = sensor_msgs_msg
        publisher = SimpleNamespace(publish=Mock())
        previous_publisher = driver.intrinsics_publisher
        previous_calibration = driver.last_intrinsics
        driver.intrinsics_publisher = publisher
        driver.last_intrinsics = None
        try:
            with patch.dict(
                sys.modules,
                {"sensor_msgs": sensor_msgs, "sensor_msgs.msg": sensor_msgs_msg},
            ):
                driver.publish_intrinsics(960, 540, 70.0)
                driver.publish_intrinsics(960, 540, 70.0)
        finally:
            driver.intrinsics_publisher = previous_publisher
            driver.last_intrinsics = previous_calibration
        publisher.publish.assert_called_once()
        message = publisher.publish.call_args.args[0]
        focal = 540.0 / (2.0 * math.tan(math.radians(35.0)))
        self.assertEqual((message.width, message.height), (960, 540))
        self.assertEqual(message.header.frame_id, driver.camera_frame_id)
        self.assertAlmostEqual(message.k[0], focal)
        self.assertAlmostEqual(message.k[4], focal)
        self.assertEqual((message.k[2], message.k[5]), (479.5, 269.5))

    def test_publish_odom_maps_frames_pose_and_velocity(self) -> None:
        """Populate ROS odometry fields with ENU pose and body-frame velocity."""
        class FakeOdometry:
            """Provide the nested mutable fields used by publish_odom."""

            def __init__(self) -> None:
                """Create the ROS message fields populated by the provider."""
                vector = lambda: SimpleNamespace(x=0.0, y=0.0, z=0.0)
                self.header = SimpleNamespace(
                    stamp=SimpleNamespace(sec=0, nanosec=0),
                    frame_id="",
                )
                self.child_frame_id = ""
                self.pose = SimpleNamespace(pose=SimpleNamespace(
                    position=vector(),
                    orientation=SimpleNamespace(x=0.0, y=0.0, z=0.0, w=0.0),
                ))
                self.twist = SimpleNamespace(twist=SimpleNamespace(
                    linear=vector(),
                    angular=vector(),
                ))

        nav_msgs = ModuleType("nav_msgs")
        nav_msgs_msg = ModuleType("nav_msgs.msg")
        nav_msgs_msg.Odometry = FakeOdometry
        nav_msgs.msg = nav_msgs_msg
        publisher = SimpleNamespace(publish=Mock())
        observation = {
            "position": {"x": 4.0, "y": 64.0, "z": 3.0},
            "yaw": 0.0,
            "pitch": 0.0,
        }
        previous_publisher = driver.odom_publisher
        previous_sample = driver.previous_odom_sample
        driver.odom_publisher = publisher
        driver.previous_odom_sample = (9.0, 4.0, -2.0, math.pi / 2.0)
        try:
            with patch.dict(sys.modules, {"nav_msgs": nav_msgs, "nav_msgs.msg": nav_msgs_msg}):
                driver.publish_odom(observation, timestamp=10.0)
        finally:
            driver.odom_publisher = previous_publisher
            driver.previous_odom_sample = previous_sample
        message = publisher.publish.call_args.args[0]
        self.assertEqual((message.header.frame_id, message.child_frame_id), (
            driver.odom_frame_id, driver.base_frame_id,
        ))
        self.assertEqual((message.pose.pose.position.x, message.pose.pose.position.y), (4.0, -3.0))
        self.assertAlmostEqual(message.twist.twist.linear.x, -1.0)
        self.assertAlmostEqual(message.twist.twist.linear.y, 0.0)

    def test_zero_move_stops_without_forward_motion(self) -> None:
        """Translate an all-zero chassis command to stop rather than forward."""
        command = SimpleNamespace(
            forward_m=0.0,
            rotate_deg=0.0,
            linear_x=0.0,
            linear_y=0.0,
            angular_y=0.0,
            angular_z=0.0,
            duration_sec=0.0,
        )
        with patch("minecraft_mod_driver.driver.client.request", return_value={"position": {}}) as request:
            result = execute_move_command(command)
        request.assert_called_once_with({"cmd": "stop"})
        self.assertEqual(result["status"], "done")

    def test_forward_distance_is_forwarded_without_timing_guess(self) -> None:
        """Preserve the chassis distance for client-position feedback control."""
        command = SimpleNamespace(forward_m=-2.5, rotate_deg=0.0)
        with patch("minecraft_mod_driver.driver.client.request", return_value={"position": {}}) as request:
            result = execute_move_command(command)
        request.assert_called_once_with({"cmd": "move", "direction": "back", "distance": 2.5})
        self.assertEqual(result["status"], "done")

    def test_velocity_fields_map_to_movement_and_view(self) -> None:
        """Forward planar motion and yaw/pitch rates through the existing command."""
        command = SimpleNamespace(
            forward_m=0.0,
            rotate_deg=0.0,
            linear_x=0.5,
            linear_y=-0.25,
            angular_y=0.5,
            angular_z=1.0,
            duration_sec=2.0,
        )
        with patch("minecraft_mod_driver.driver.client.request", return_value={"position": {}}) as request:
            execute_move_command(command)
        sent = request.call_args.args[0]
        self.assertEqual(sent["cmd"], "move")
        self.assertEqual((sent["linear_x"], sent["linear_y"]), (0.5, -0.25))
        self.assertAlmostEqual(sent["yaw_degrees"], math.degrees(2.0))
        self.assertAlmostEqual(sent["pitch_degrees"], math.degrees(1.0))
        self.assertEqual(sent["duration_ms"], 2000)

    def test_move_description_explains_every_supported_direction(self) -> None:
        """Expose signed directions, units, and mode semantics to the VLM."""
        description = " ".join((driver.move_mcp.__doc__ or "").split())
        for expected in (
            "Positive moves forward",
            "negative moves backward",
            "relative total yaw angle in degrees, not an angular speed",
            "Positive turns the view left/counterclockwise",
            "negative turns it right/clockwise",
            "positive/negative `linear_y` strafes left/right",
            "`angular_z` is a yaw rate",
            "both in radians per second",
            "angular rate multiplied by `duration_sec`",
            "`linear_z` and `angular_x` must always be zero",
            "change one axis at a time",
        ):
            self.assertIn(expected, description)

    def test_move_rejects_unsupported_minecraft_axes(self) -> None:
        """Reject generic chassis axes that Minecraft cannot execute."""
        command = SimpleNamespace(linear_z=0.2, angular_x=0.0)
        with patch("minecraft_mod_driver.driver.client.request") as request:
            result = execute_move_command(command)
        request.assert_not_called()
        self.assertIn("linear_z and angular_x are unsupported", result["error"])

    def test_move_rejects_mixed_distance_and_velocity_modes(self) -> None:
        """Reject ambiguous commands instead of silently prioritizing one mode."""
        command = SimpleNamespace(forward_m=1.0, linear_y=0.2)
        with patch("minecraft_mod_driver.driver.client.request") as request:
            result = execute_move_command(command)
        request.assert_not_called()
        self.assertIn("cannot be combined", result["error"])

    def test_velocity_mode_rejects_negative_duration(self) -> None:
        """Reject a duration that would invert integrated view rotation."""
        command = SimpleNamespace(
            forward_m=0.0,
            rotate_deg=0.0,
            linear_x=0.5,
            linear_y=0.0,
            angular_y=0.0,
            angular_z=0.0,
            duration_sec=-1.0,
        )
        with patch("minecraft_mod_driver.driver.client.request") as request:
            result = execute_move_command(command)
        request.assert_not_called()
        self.assertIn("duration_sec", result["error"])

    def test_velocity_mode_uses_default_duration_for_zero(self) -> None:
        """Keep the chassis contract's zero-duration default in velocity mode."""
        command = SimpleNamespace(
            forward_m=0.0,
            rotate_deg=0.0,
            linear_x=0.5,
            linear_y=0.0,
            angular_y=0.0,
            angular_z=0.0,
            duration_sec=0.0,
        )
        with patch("minecraft_mod_driver.driver.client.request", return_value={}) as request:
            execute_move_command(command)
        self.assertEqual(request.call_args.args[0]["duration_ms"], 1000)

    def test_snapshot_request_is_not_blocked_by_movement(self) -> None:
        """Allow independent short-lived sockets to serve a frame during movement."""
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(2)
        port = listener.getsockname()[1]
        move_seen = threading.Event()
        release_move = threading.Event()

        def handle(connection: socket.socket) -> None:
            """Delay movement while answering other commands immediately."""
            with connection:
                request = json.loads(connection.makefile("r", encoding="utf-8").readline())
                if request["cmd"] == "move":
                    move_seen.set()
                    release_move.wait(timeout=2)
                response = {"id": request["id"], "ok": True, "result": {"cmd": request["cmd"]}}
                connection.sendall(json.dumps(response).encode("utf-8") + b"\n")

        def serve() -> None:
            """Accept two commands and process them on separate local workers."""
            workers = []
            with listener:
                for _ in range(2):
                    connection, _ = listener.accept()
                    worker = threading.Thread(target=handle, args=(connection,))
                    worker.start()
                    workers.append(worker)
            for worker in workers:
                worker.join(timeout=2)

        server = threading.Thread(target=serve)
        server.start()
        client = MinecraftModClient()
        client.port = port
        movement = threading.Thread(target=lambda: client.request({"cmd": "move"}))
        movement.start()
        self.assertTrue(move_seen.wait(timeout=1))
        try:
            self.assertEqual(client.request({"cmd": "snapshot"}), {"cmd": "snapshot"})
        finally:
            release_move.set()
        movement.join(timeout=2)
        server.join(timeout=2)
        self.assertFalse(movement.is_alive())
        self.assertFalse(server.is_alive())


if __name__ == "__main__":
    unittest.main()
