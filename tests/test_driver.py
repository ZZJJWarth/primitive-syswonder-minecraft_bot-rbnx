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
import yaml

import minecraft_mod_driver.driver as driver
from minecraft_mod_driver.driver import (
    MinecraftModClient,
    cmd_vel_message_values,
    cmd_vel_payload,
    camera_quaternion_from_pitch,
    configured_cmd_vel_bridge,
    configured_clock_emulation,
    configured_eye_height,
    configured_frequency,
    configured_lidar_range,
    configured_lidar_ray_count,
    configured_route_inspection,
    configured_use_sim_time,
    capture_identity,
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


def semantic_target(x: int = 2, y: int = 64, z: int = 1) -> str:
    """Register one stable semantic ID as the primitive's native target."""
    object_id = f"test:dimension@{x},{y},{z}"
    with driver.semantic_blocks_lock:
        driver.semantic_block_positions[object_id] = (x, y, z)
    return object_id


def semantic_entity_target(
    entity_uuid: str = "12345678-1234-5678-1234-567812345678",
    label: str = "minecraft:zombie",
) -> str:
    """Register one stable Scene identity as a live native entity target."""
    object_id = f"{driver.minecraft_mod.id}/entity:test@{entity_uuid}"
    with driver.semantic_blocks_lock:
        driver.semantic_entity_targets[object_id] = (entity_uuid, label)
    return object_id


class MinecraftModClientTest(unittest.TestCase):
    """Exercises the provider side without launching Minecraft."""

    def test_one_provider_owns_all_colocated_contracts(self) -> None:
        """Keep one deploy instance, provider identity, and lifecycle owner."""
        self.assertEqual(driver.minecraft_camera.namespace, "robonix/primitive/camera")
        self.assertIs(driver.minecraft_camera, driver.minecraft_mod)
        self.assertIs(driver.minecraft_chassis, driver.minecraft_mod)
        self.assertIs(driver.minecraft_perception, driver.minecraft_mod)
        self.assertIs(driver.minecraft_lidar, driver.minecraft_mod)
        self.assertIs(driver.minecraft_actions, driver.minecraft_mod)

    def test_aggregate_provider_uses_implicit_shared_lifecycle_contract(self) -> None:
        """Keep callbacks on one provider and let the runtime select shared Driver."""
        provider = driver.minecraft_mod
        self.assertIs(provider._on_init, driver.init_camera)
        self.assertIs(provider._on_activate, driver.activate_camera)
        self.assertIs(provider._on_deactivate, driver.deactivate_camera)
        self.assertIs(provider._on_shutdown, driver.shutdown_camera)

        manifest_path = Path(__file__).resolve().parents[1] / "package_manifest.yaml"
        manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        lifecycle_contracts = [
            capability["name"]
            for capability in manifest["capabilities"]
            if capability["name"].endswith("/driver")
        ]
        self.assertEqual(lifecycle_contracts, [])

    def test_legacy_deposit_prompt_forbids_scene_coordinate_conversion(self) -> None:
        """Keep the compatibility contract native and direct Scene callers to v2."""
        contract = (
            Path(__file__).resolve().parents[1]
            / "capabilities"
            / "minecraft"
            / "deposit_item.v1.toml"
        )
        with contract.open("rb") as stream:
            description = tomllib.load(stream)["contract"]["description"]
        self.assertIn("native Minecraft BlockPos", description)
        self.assertIn("must never be derived from Scene coordinates", description)
        self.assertIn("v2 with a stable object_id", description)

    def test_deposit_mod_separates_reach_from_crosshair_failure(self) -> None:
        """Keep deposit errors actionable instead of reporting every miss as reach."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "MinecraftActions.java"
        )
        source = source_path.read_text(encoding="utf-8")
        validation = source[
            source.index("private static BlockHitResult validateDepositTarget"):
            source.index(
                "private static BlockHitResult validateExpectedTarget",
                source.index("private static BlockHitResult validateDepositTarget"),
            )
        ]
        reach_error = validation.index("target block is outside interaction reach")
        aim_error = validation.index("crosshair is not aimed at target chest")
        self.assertLess(reach_error, aim_error)
        self.assertIn("minecraft.hitResult", validation)

    def test_deposit_mod_accepts_registered_ids_and_preflights_exact_quantity(self) -> None:
        """Keep arbitrary registry IDs, inventory checks, and capacity checks in the Mod."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "MinecraftActions.java"
        )
        source = source_path.read_text(encoding="utf-8")
        deposit = source[
            source.index("public CompletableFuture<JsonObject> depositItem"):
            source.index(
                "public CompletableFuture<JsonObject> withdrawItem",
                source.index("public CompletableFuture<JsonObject> depositItem"),
            )
        ]
        tick = source[
            source.index("private void tickDeposit"):
            source.index(
                "private static void pickNextDepositSource",
                source.index("private void tickDeposit"),
            )
        ]
        self.assertIn("item name is not registered", deposit)
        self.assertIn("player main inventory contains only ", deposit)
        self.assertNotIn("item must be minecraft:dirt", deposit)
        self.assertIn("containerCapacity", tick)
        self.assertLess(tick.index("containerCapacity"), tick.index("pickNextDepositSource"))
        self.assertIn("action.requestedCount()", tick)

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

    def test_sim_time_configuration_is_explicit(self) -> None:
        """Do not accidentally enable the graph clock for arbitrary truthy values."""
        self.assertTrue(configured_use_sim_time({"use_sim_time": True}))
        self.assertTrue(configured_use_sim_time({"use_sim_time": "1"}))
        self.assertFalse(configured_use_sim_time({"use_sim_time": False}))
        with self.assertRaisesRegex(ValueError, "boolean"):
            configured_use_sim_time({"use_sim_time": "yes"})

    def test_clock_emulation_topics_are_explicit_and_unique(self) -> None:
        """Keep raw device-clock streams separate from truth topics."""
        emulator, topics = configured_clock_emulation({
            "clock_emulation": {
                "enabled": True,
                "topics": {"lidar": "/test/raw/lidar"},
            },
        })
        self.assertTrue(emulator.enabled)
        self.assertEqual(topics[3], "/test/raw/lidar")
        self.assertEqual(len(topics), len(set(topics)))
        with self.assertRaisesRegex(ValueError, "fully-qualified"):
            configured_clock_emulation({
                "clock_emulation": {"topics": {"depth": "relative/depth"}},
            })

    def test_capture_identity_requires_integer_truth_metadata(self) -> None:
        """Do not emulate a hardware clock from publication or wall time."""
        self.assertEqual(
            capture_identity({"simTimeNanos": 123, "captureSequence": 7}),
            (123, 7),
        )
        with self.assertRaisesRegex(ValueError, "captureSequence"):
            capture_identity({"simTimeNanos": 123})

    def test_cmd_vel_bridge_configuration_is_bounded(self) -> None:
        """Reject ambiguous topics and unsafe command/deadman limits."""
        self.assertEqual(
            configured_cmd_vel_bridge({}),
            ("/cmd_vel", 0.25, 0.5, 1.2, 1.0),
        )
        with self.assertRaisesRegex(ValueError, "fully-qualified"):
            configured_cmd_vel_bridge({"cmd_vel_topic": "cmd_vel"})
        with self.assertRaisesRegex(ValueError, "command duration"):
            configured_cmd_vel_bridge({
                "cmd_vel_command_duration_sec": 0.5,
                "cmd_vel_timeout_sec": 0.25,
            })

    def test_cmd_vel_translates_planar_twist_to_bounded_mod_chunk(self) -> None:
        """Preserve REP-103 forward/left/yaw signs at the Minecraft boundary."""
        vector = lambda x=0.0, y=0.0, z=0.0: SimpleNamespace(x=x, y=y, z=z)
        message = SimpleNamespace(
            linear=vector(x=0.8),
            angular=vector(z=0.4),
        )

        self.assertEqual(cmd_vel_message_values(message), (0.8, 0.0, 0.4))
        payload = cmd_vel_payload(0.8, 0.0, 0.4, 0.25)
        self.assertEqual(payload["cmd"], "move")
        self.assertEqual(payload["duration_ms"], 250)
        self.assertAlmostEqual(payload["yaw_degrees"], math.degrees(0.1))
        self.assertEqual(cmd_vel_payload(0.0, 0.0, 0.0, 0.25), {"cmd": "stop"})

    def test_cmd_vel_rejects_nonplanar_or_excessive_motion(self) -> None:
        """Fail closed before unsupported Nav2 motion reaches the Mod."""
        vector = lambda x=0.0, y=0.0, z=0.0: SimpleNamespace(x=x, y=y, z=z)
        with self.assertRaisesRegex(ValueError, "only planar"):
            cmd_vel_message_values(SimpleNamespace(
                linear=vector(z=0.1),
                angular=vector(),
            ))
        with self.assertRaisesRegex(ValueError, "configured limit"):
            cmd_vel_message_values(SimpleNamespace(
                linear=vector(x=driver.cmd_vel_max_linear + 0.1),
                angular=vector(),
            ))

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

    def test_select_inventory_item_verifies_main_hand_selection(self) -> None:
        """Expose the selected glass-bottle stack and whether it moved to hotbar."""
        message = driver.minecraft_mcp.SelectInventoryItem_Request(
            item="minecraft:glass_bottle",
        )
        result = {
            "selected": True,
            "item": "minecraft:glass_bottle",
            "source_slot": 17,
            "selected_slot": 3,
            "selected_count": 4,
            "moved_to_hotbar": True,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.select_inventory_item_mcp(message)
        self.assertTrue(response.selected)
        self.assertEqual(response.item, "minecraft:glass_bottle")
        self.assertEqual((response.source_slot, response.selected_slot), (17, 3))
        self.assertEqual(response.selected_count, 4)
        self.assertTrue(response.moved_to_hotbar)
        request.assert_called_once_with(
            {"cmd": "select_inventory_item", "item": "minecraft:glass_bottle"},
            timeout_sec=max(driver.client.timeout, driver.ACTION_REQUEST_TIMEOUT_SEC),
        )

    def test_select_inventory_item_rejects_inconsistent_mod_response(self) -> None:
        """Do not claim a verified hand selection from contradictory slot metadata."""
        message = driver.minecraft_mcp.SelectInventoryItem_Request(
            item="minecraft:glass_bottle",
        )
        result = {
            "selected": True,
            "item": "minecraft:glass_bottle",
            "source_slot": 17,
            "selected_slot": 3,
            "selected_count": 4,
            "moved_to_hotbar": False,
        }
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "inconsistent hotbar"):
                driver.select_inventory_item_mcp(message)

    def test_select_inventory_item_mod_handles_hotbar_and_main_inventory(self) -> None:
        """Keep absent-item errors explicit and inventory-to-hotbar swaps native."""
        source = (
            Path(__file__).resolve().parents[1]
            / "src/main/java/com/robonix/minecraftmod/MinecraftActions.java"
        ).read_text(encoding="utf-8")
        method = source[
            source.index("public CompletableFuture<JsonObject> selectInventoryItem"):
            source.index("public CompletableFuture<JsonObject> aimAtBlock")
        ]
        self.assertIn("player main inventory contains no ", method)
        self.assertIn("minecraft.gameMode.handlePickItem(sourceSlot)", method)
        self.assertIn("player.getInventory().selected = sourceSlot", method)
        self.assertIn("item name is not registered: ", method)

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

    def test_route_obstacle_uses_opaque_handle_and_clears_one_allowlisted_block(self) -> None:
        """Keep native coordinates inside the primitive across inspect and clear."""
        inspect_request = driver.minecraft_mcp.InspectRouteObstacle_Request(
            recovery_token="recovery-1", max_distance_m=3.0,
        )
        inspected = {
            "found": True,
            "block_name": "minecraft:dirt",
            "x": 12,
            "y": 64,
            "z": -8,
            "distance_m": 1.4,
        }
        with patch.object(driver.client, "request", return_value=inspected):
            inspection = driver.inspect_route_obstacle_mcp(inspect_request)
        self.assertTrue(inspection.found)
        self.assertTrue(inspection.clearable)
        self.assertNotIn("12", inspection.detail)

        clear_request = driver.minecraft_mcp.ClearRouteObstacle_Request(
            obstacle_handle=inspection.obstacle_handle,
        )
        results = [
            {
                "aimed": True,
                "actual_block": "minecraft:dirt",
                "hit_face": "north",
                "final_yaw_deg": 0.0,
                "final_pitch_deg": 0.0,
            },
            {
                "block_broken": True,
                "original_block": "minecraft:dirt",
                "collected_item": "",
                "collected_count": 0,
            },
        ]
        with patch.object(driver.client, "request", side_effect=results) as request:
            cleared = driver.clear_route_obstacle_mcp(clear_request)
        self.assertTrue(cleared.cleared)
        self.assertEqual(request.call_args_list[0].args[0]["x"], 12)
        self.assertNotIn("x", clear_request.to_dict())

    def test_route_obstacle_refuses_non_allowlisted_block(self) -> None:
        """Report arbitrary blockers but never clear them outside the allowlist."""
        message = driver.minecraft_mcp.InspectRouteObstacle_Request(
            recovery_token="recovery-2", max_distance_m=2.0,
        )
        result = {
            "found": True,
            "block_name": "minecraft:chest",
            "x": 1,
            "y": 64,
            "z": 1,
            "distance_m": 1.0,
        }
        with patch.object(driver.client, "request", return_value=result):
            inspection = driver.inspect_route_obstacle_mcp(message)
        self.assertFalse(inspection.clearable)
        with self.assertRaisesRegex(ValueError, "allowlist"):
            driver.clear_route_obstacle_mcp(
                driver.minecraft_mcp.ClearRouteObstacle_Request(
                    obstacle_handle=inspection.obstacle_handle,
                )
            )

    def test_route_path_retains_only_dirt_targets_behind_handle(self) -> None:
        """Keep path coordinates private and expose non-dirt blockers as a count."""
        message = driver.minecraft_mcp.InspectRoutePath_Request(
            recovery_token="recovery-path",
            navigation_x=[1.0, 2.0],
            navigation_y=[-4.0, -4.0],
        )
        inspected = {
            "found": True,
            "dirt_count": 2,
            "other_count": 1,
            "dirt_blocks": [
                {"block_name": "minecraft:dirt", "x": 1, "y": 64, "z": 4},
                {"block_name": "minecraft:dirt", "x": 2, "y": 65, "z": 4},
            ],
            "other_blocks": [
                {"block_name": "minecraft:stone", "x": 1, "y": 64, "z": 5},
            ],
        }
        with patch.object(driver.client, "request", return_value=inspected) as request:
            inspection = driver.inspect_route_path_mcp(message)
        self.assertTrue(inspection.found)
        self.assertEqual(inspection.dirt_count, 2)
        self.assertEqual(inspection.other_count, 1)
        self.assertNotIn("1,64,4", inspection.detail)
        self.assertEqual(set(message.to_dict()), {
            "recovery_token", "navigation_x", "navigation_y",
        })
        sent = request.call_args.args[0]
        self.assertEqual(sent["cmd"], "inspect_route_path")
        self.assertEqual(sent["navigation_x"], [1.0, 2.0])
        self.assertEqual(sent["half_width"], driver.route_inspection_half_width_m)
        self.assertEqual(sent["sample_spacing"], driver.route_inspection_sample_spacing_m)
        self.assertEqual(sent["max_blocks"], driver.route_inspection_max_blocks)

        clear = driver.minecraft_mcp.ClearRoutePath_Request(
            inspection_handle=inspection.inspection_handle,
        )
        self.assertEqual(clear.to_dict(), {
            "inspection_handle": inspection.inspection_handle,
        })
        with patch.object(
            driver.client,
            "request",
            side_effect=[
                {"aimed": True, "actual_block": "minecraft:dirt"},
                {"block_broken": True, "original_block": "minecraft:dirt"},
                {"aimed": True, "actual_block": "minecraft:dirt"},
                {"block_broken": True, "original_block": "minecraft:dirt"},
            ],
        ):
            cleared = driver.clear_route_path_mcp(clear)
        self.assertTrue(cleared.cleared)
        self.assertEqual(cleared.cleared_count, 2)
        self.assertEqual(cleared.other_count, 1)

    def test_aim_at_block_resolves_stable_object_id_and_verifies_live_hit(self) -> None:
        """Resolve the selected Scene object ID without VLM coordinate arithmetic."""
        message = driver.minecraft_mcp.AimAtBlockV2_Request(
            object_id=semantic_target(9, 0, 7),
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

    def test_object_id_resolves_native_block_position(self) -> None:
        """Keep the native coordinate lookup entirely inside the primitive."""
        object_id = semantic_target(44, 0, -10)
        self.assertEqual(driver.native_block_pos(object_id), (44, 0, -10))

    def test_object_id_rejects_unknown_and_empty_values(self) -> None:
        """Never infer a BlockPos from unknown or malformed object identity."""
        with self.assertRaisesRegex(ValueError, "unknown or stale"):
            driver.native_block_pos("missing:object")
        with self.assertRaisesRegex(ValueError, "non-empty"):
            driver.native_block_pos("")

    def test_aim_at_block_rejects_unverified_mod_result(self) -> None:
        """Reject mismatched blocks, invalid faces, and impossible final pitch."""
        message = driver.minecraft_mcp.AimAtBlockV2_Request(
            object_id=semantic_target(2, 64, -3),
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

    def test_aim_at_block_rejects_unknown_object_id(self) -> None:
        """Do not turn an unknown Scene identity into an arbitrary BlockPos."""
        message = driver.minecraft_mcp.AimAtBlockV2_Request(
            object_id="missing:object",
            expected_block="minecraft:crafting_table",
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "unknown or stale"):
                driver.aim_at_block_mcp(message)
        request.assert_not_called()

    def test_aim_at_block_rejects_empty_object_id(self) -> None:
        """Require a stable structured-object identity at the boundary."""
        message = driver.minecraft_mcp.AimAtBlockV2_Request(
            object_id="",
            expected_block="minecraft:crafting_table",
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "non-empty"):
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

    def test_attack_entity_resolves_uuid_and_verifies_kill(self) -> None:
        """Keep moving-target coordinates out of the native combat request."""
        object_id = semantic_entity_target()
        message = driver.minecraft_mcp.AttackEntity_Request(
            object_id=object_id,
            expected_entity="minecraft:zombie",
        )
        result = {
            "killed": True,
            "actual_entity": "minecraft:zombie",
            "attacks_attempted": 4,
            "attacks_landed": 3,
            "bot_health_before": 20.0,
            "bot_health_after": 16.0,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.attack_entity_mcp(message)
        self.assertTrue(response.killed)
        self.assertEqual(response.object_id, object_id)
        self.assertEqual(response.attacks_landed, 3)
        self.assertEqual(request.call_args.args[0], {
            "cmd": "attack_entity",
            "entity_uuid": "12345678-1234-5678-1234-567812345678",
            "expected_entity": "minecraft:zombie",
        })

    def test_attack_entity_accepts_player_and_rejects_unknown_or_other_targets(self) -> None:
        """Reject stale identity and every non-whitelisted entity before mutation."""
        unknown = driver.minecraft_mcp.AttackEntity_Request(
            object_id="missing:entity",
            expected_entity="minecraft:zombie",
        )
        skeleton = driver.minecraft_mcp.AttackEntity_Request(
            object_id=semantic_entity_target(
                entity_uuid="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                label="minecraft:skeleton",
            ),
            expected_entity="minecraft:skeleton",
        )
        player_id = semantic_entity_target(label="minecraft:player")
        player = driver.minecraft_mcp.AttackEntity_Request(
            object_id=player_id,
            expected_entity="minecraft:player",
        )
        player_result = {
            "killed": True,
            "actual_entity": "minecraft:player",
            "attacks_attempted": 5,
            "attacks_landed": 4,
            "bot_health_before": 20.0,
            "bot_health_after": 14.0,
        }
        with patch.object(driver.client, "request", return_value=player_result) as request:
            response = driver.attack_entity_mcp(player)
        self.assertTrue(response.killed)
        self.assertEqual(response.actual_entity, "minecraft:player")
        self.assertEqual(request.call_args.args[0]["expected_entity"], "minecraft:player")
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "unknown or stale"):
                driver.attack_entity_mcp(unknown)
            with self.assertRaisesRegex(ValueError, "minecraft:zombie or minecraft:player"):
                driver.attack_entity_mcp(skeleton)
        request.assert_not_called()

    def test_player_until_death_chase_waits_without_response_timeout(self) -> None:
        """Keep the provider socket open while the Mod pursues a permitted player."""
        player = driver.minecraft_mcp.AttackEntity_Request(
            object_id=semantic_entity_target(label="minecraft:player"),
            expected_entity="minecraft:player",
        )
        result = {
            "killed": True,
            "actual_entity": "minecraft:player",
            "attacks_attempted": 40,
            "attacks_landed": 20,
            "bot_health_before": 20.0,
            "bot_health_after": 10.0,
        }
        with patch.dict(os.environ, {
            "RBNX_MINECRAFT_PLAYER_CHASE_MODE": "until_death",
        }), patch.object(driver, "action_request", return_value=result) as request:
            response = driver.attack_entity_mcp(player)
        self.assertTrue(response.killed)
        self.assertTrue(request.call_args.kwargs["wait_until_response"])

    def test_deposit_item_requires_exact_requested_count_changes(self) -> None:
        """Forward any registry-shaped item ID and verify both requested deltas."""
        message = driver.minecraft_mcp.DepositItemV2_Request(
            object_id=semantic_target(), item="minecraft:glass_bottle", count=3,
        )
        result = {
            "transferred_count": 3,
            "player_count_before": 5,
            "player_count_after": 2,
            "container_count_before": 5,
            "container_count_after": 8,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.deposit_item_mcp(message)
        self.assertEqual(response.transferred_count, 3)
        self.assertEqual((response.player_count_after, response.container_count_after), (2, 8))
        self.assertEqual(request.call_args.args[0]["item"], "minecraft:glass_bottle")
        self.assertEqual(request.call_args.args[0]["count"], 3)

        result["container_count_after"] = 7
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "container counts"):
                driver.deposit_item_mcp(message)

    def test_deposit_item_accepts_other_registry_shaped_item(self) -> None:
        """Do not retain the old dirt and crafting-table whitelist in Python."""
        message = driver.minecraft_mcp.DepositItemV2_Request(
            object_id=semantic_target(), item="minecraft:stone", count=1,
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
        self.assertEqual(request.call_args.args[0]["item"], "minecraft:stone")

    def test_withdraw_item_requires_exact_requested_count_changes(self) -> None:
        """Forward an arbitrary registered item and verify both count deltas."""
        message = driver.minecraft_mcp.WithdrawItemV2_Request(
            object_id=semantic_target(), item="minecraft:iron_ingot", count=3,
        )
        result = {
            "transferred_count": 3,
            "player_count_before": 2,
            "player_count_after": 5,
            "container_count_before": 10,
            "container_count_after": 7,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.withdraw_item_mcp(message)
        self.assertEqual(response.transferred_count, 3)
        self.assertEqual((response.player_count_after, response.container_count_after), (5, 7))
        self.assertEqual(
            request.call_args.args[0],
            {
                "cmd": "withdraw_item",
                "x": 2,
                "y": 64,
                "z": 1,
                "item": "minecraft:iron_ingot",
                "count": 3,
            },
        )

        result["container_count_after"] = 8
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "container counts"):
                driver.withdraw_item_mcp(message)

    def test_inspect_container_returns_exact_read_only_slots(self) -> None:
        """Resolve a Scene chest and validate every returned container slot."""
        message = driver.minecraft_mcp.InspectContainerV2_Request(
            object_id=semantic_target(),
        )
        result = {
            "container_type": "minecraft:chest",
            "slot_count": 3,
            "slots": [
                {"slot": 0, "item": "minecraft:crafting_table", "count": 1},
                {"slot": 1, "item": "minecraft:air", "count": 0},
                {"slot": 2, "item": "minecraft:glass_bottle", "count": 3},
            ],
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.inspect_container_mcp(message)
        self.assertEqual(response.container_type, "minecraft:chest")
        self.assertEqual(response.slot_count, 3)
        self.assertEqual(response.slots[0].item, "minecraft:crafting_table")
        self.assertEqual(response.slots[2].count, 3)
        self.assertEqual(request.call_args.args[0], {
            "cmd": "inspect_container", "x": 2, "y": 64, "z": 1,
        })

        result["slots"][1] = {"slot": 1, "item": "minecraft:air", "count": 1}
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "empty slot"):
                driver.inspect_container_mcp(message)

    def test_withdraw_item_rejects_zero_before_request(self) -> None:
        """Require a positive quantity at the capability boundary."""
        message = driver.minecraft_mcp.WithdrawItemV2_Request(
            item="minecraft:iron_ingot", count=0,
        )
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "count"):
                driver.withdraw_item_mcp(message)
        request.assert_not_called()

    def test_withdraw_item_preserves_absent_item_error(self) -> None:
        """Expose the Mod's actionable no-item error without rewriting it."""
        message = driver.minecraft_mcp.WithdrawItemV2_Request(
            object_id=semantic_target(), item="minecraft:diamond", count=1,
        )
        with patch.object(
            driver.client,
            "request",
            side_effect=RuntimeError("chest contains no minecraft:diamond"),
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "chest contains no minecraft:diamond",
            ):
                driver.withdraw_item_mcp(message)

    def test_legacy_withdraw_contract_forbids_scene_coordinate_conversion(self) -> None:
        """Keep item aliases while directing Scene-driven callers to v2."""
        contract = (
            Path(__file__).resolve().parents[1]
            / "capabilities"
            / "minecraft"
            / "withdraw_item.v1.toml"
        )
        with contract.open("rb") as stream:
            description = tomllib.load(stream)["contract"]["description"]
        self.assertIn("minecraft:bread", description)
        self.assertIn("minecraft:potion", description)
        self.assertIn("minecraft:water_bottle is not registered", description)
        self.assertIn("must never be derived from Scene coordinates", description)
        self.assertIn("v2 with a stable object_id", description)

    def test_withdraw_mod_checks_availability_before_clicking_items(self) -> None:
        """Keep absent and insufficient-item failures non-mutating."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "MinecraftActions.java"
        )
        source = source_path.read_text(encoding="utf-8")
        withdraw = source[
            source.index("private void tickWithdraw"):
            source.index(
                "private static void pickNextWithdrawSource",
                source.index("private void tickWithdraw"),
            )
        ]
        self.assertLess(
            withdraw.index("chest contains no "),
            withdraw.index("pickNextWithdrawSource"),
        )
        self.assertIn("chest contains only ", withdraw)
        self.assertIn("player main inventory can hold only ", withdraw)

    def test_withdraw_requires_live_crosshair_on_requested_chest(self) -> None:
        """Keep a reachable crosshair miss distinct from an out-of-reach target."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "MinecraftActions.java"
        )
        source = source_path.read_text(encoding="utf-8")
        withdraw = source[
            source.index("public CompletableFuture<JsonObject> withdrawItem"):
            source.index(
                "public CompletableFuture<JsonObject> fillWaterBottles",
                source.index("public CompletableFuture<JsonObject> withdrawItem"),
            )
        ]
        validation = source[
            source.index("private static BlockHitResult validateDepositTarget"):
            source.index(
                "private static BlockHitResult validateCauldronTarget",
                source.index("private static BlockHitResult validateDepositTarget"),
            )
        ]
        self.assertIn("validateDepositTarget", withdraw)
        self.assertIn("target block is outside interaction reach", validation)
        self.assertIn("crosshair is not aimed at target chest", validation)
        self.assertLess(
            validation.index("target block is outside interaction reach"),
            validation.index("crosshair is not aimed at target chest"),
        )

    def test_fill_water_bottles_verifies_bottle_and_level_deltas(self) -> None:
        """Return only a synchronized exact two-bottle fill."""
        message = driver.minecraft_mcp.FillWaterBottlesV2_Request(
            object_id=semantic_target(), count=2,
        )
        result = {
            "filled_count": 2,
            "empty_bottles_before": 3,
            "empty_bottles_after": 1,
            "water_bottles_before": 0,
            "water_bottles_after": 2,
            "cauldron_level_before": 3,
            "cauldron_level_after": 1,
        }
        with patch.object(driver.client, "request", return_value=result) as request:
            response = driver.fill_water_bottles_mcp(message)
        self.assertEqual(response.filled_count, 2)
        self.assertEqual(response.water_bottles_after, 2)
        self.assertEqual(response.cauldron_level_after, 1)
        self.assertEqual(
            request.call_args.args[0],
            {"cmd": "fill_water_bottles", "x": 2, "y": 64, "z": 1, "count": 2},
        )

        result["water_bottles_after"] = 1
        with patch.object(driver.client, "request", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "water bottle counts"):
                driver.fill_water_bottles_mcp(message)

    def test_fill_water_bottles_rejects_impossible_count_before_request(self) -> None:
        """A vanilla water cauldron can supply at most three bottles."""
        message = driver.minecraft_mcp.FillWaterBottlesV2_Request(count=4)
        with patch.object(driver.client, "request") as request:
            with self.assertRaisesRegex(ValueError, "count"):
                driver.fill_water_bottles_mcp(message)
        request.assert_not_called()

    def test_fill_water_bottles_preserves_missing_bottle_error(self) -> None:
        """Expose an actionable empty-bottle precondition failure."""
        message = driver.minecraft_mcp.FillWaterBottlesV2_Request(
            object_id=semantic_target(), count=1,
        )
        with patch.object(
            driver.client,
            "request",
            side_effect=RuntimeError(
                "player main inventory contains no minecraft:glass_bottle"
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "contains no minecraft:glass_bottle"):
                driver.fill_water_bottles_mcp(message)

    def test_fill_mod_checks_all_preconditions_before_cauldron_use(self) -> None:
        """Keep water-level, bottle-count, and capacity failures non-mutating."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "MinecraftActions.java"
        )
        source = source_path.read_text(encoding="utf-8")
        setup = source[
            source.index("public CompletableFuture<JsonObject> fillWaterBottles"):
            source.index(
                "public void onClientTick",
                source.index("public CompletableFuture<JsonObject> fillWaterBottles"),
            )
        ]
        self.assertIn("Blocks.WATER_CAULDRON", setup)
        self.assertIn("LayeredCauldronBlock.LEVEL", setup)
        self.assertIn("minecraft:glass_bottle", setup)
        self.assertIn("mainInventoryEmptySlots", setup)
        self.assertNotIn("useItemOn", setup)

    def test_target_identity_docs_forbid_coordinate_guessing(self) -> None:
        """Keep stable target identity visible to Pilot in every v2 action."""
        package_root = Path(__file__).resolve().parents[1]
        capability_doc = (package_root / "CAPABILITY.md").read_text(encoding="utf-8")
        self.assertIn("robonix/system/scene/list_objects", capability_doc)
        self.assertIn("copy that stable `id`", capability_doc)
        self.assertIn("reports the player's position, not", capability_doc)
        self.assertIn("Scene coordinates and `goal_near` poses", capability_doc)

        for name in (
            "aim_at_block.v2.toml",
            "deposit_item.v2.toml",
            "withdraw_item.v2.toml",
            "inspect_container.v2.toml",
            "fill_water_bottles.v2.toml",
        ):
            with (package_root / "capabilities" / "minecraft" / name).open("rb") as stream:
                description = tomllib.load(stream)["contract"]["description"]
            self.assertIn("robonix/system/scene/list_objects", description, name)
            self.assertIn("stable id", description, name)
            self.assertIn("minecraft/get_state", description, name)
            self.assertIn("scene coordinates", description.lower(), name)
            self.assertIn("unknown or stale", description.lower(), name)

    def test_live_mcp_descriptions_require_scene_target_provenance(self) -> None:
        """Expose coordinate-source constraints in the descriptions Pilot sees."""
        for operation in (
            driver.aim_at_block_mcp,
            driver.deposit_item_mcp,
            driver.withdraw_item_mcp,
            driver.inspect_container_mcp,
            driver.fill_water_bottles_mcp,
        ):
            description = " ".join((operation.__doc__ or "").split())
            self.assertIn("MUST call ``scene_list_objects``", description)
            self.assertIn("stable ``id``", description)
            self.assertIn("minecraft_get_state", description)
            self.assertIn("cannot supply this ID", description)
            self.assertIn("object_id", description)

        state_description = " ".join((driver.get_state_mcp.__doc__ or "").split())
        self.assertIn("belongs to the Bot/player", state_description)
        self.assertIn("MUST NOT copy or adapt", state_description)
        self.assertIn("MUST call ``scene_list_objects``", state_description)
        self.assertIn("Never infer a target from the Bot's position", state_description)

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

    def test_simulation_capture_time_uses_integer_nanoseconds(self) -> None:
        """Use gameTime in sim mode and never fall back to a wall timestamp."""
        with patch.object(driver, "use_sim_time", True):
            self.assertEqual(
                observation_timestamp({"simTimeNanos": 12_350_000_000}),
                12.35,
            )
            with self.assertRaisesRegex(ValueError, "simTimeNanos"):
                observation_timestamp({"timestamp": "2023-11-14T22:13:20Z"})

    def test_sim_objects_keep_wall_clock_last_seen(self) -> None:
        """Object age remains Unix time while the message header uses simulation time."""
        observation = {
            "simTimeNanos": 12_350_000_000,
            "semanticBlocks": [{
                "id": "minecraft:overworld@4,64,3",
                "name": "minecraft:crafting_table",
                "position": {"x": 4, "y": 64, "z": 3},
                "lastSeen": "2023-11-14T22:13:19.000Z",
            }],
        }
        with patch.object(driver, "use_sim_time", True):
            payload = semantic_objects_payload(observation)
        self.assertEqual(payload["timestamp"], 12.35)
        self.assertEqual(payload["objects"][0]["last_seen_unix"], 1_699_999_999.0)

    def test_clock_sample_preserves_nanoseconds_and_rejects_regression(self) -> None:
        """Publish exact /clock fields without permitting time to move backwards."""
        class FakeClock:
            def __init__(self) -> None:
                self.clock = SimpleNamespace(sec=0, nanosec=0)

        rosgraph_msgs = ModuleType("rosgraph_msgs")
        rosgraph_msgs_msg = ModuleType("rosgraph_msgs.msg")
        rosgraph_msgs_msg.Clock = FakeClock
        rosgraph_msgs.msg = rosgraph_msgs_msg
        publisher = SimpleNamespace(publish=Mock())
        with (
            patch.dict(sys.modules, {
                "rosgraph_msgs": rosgraph_msgs,
                "rosgraph_msgs.msg": rosgraph_msgs_msg,
            }),
            patch.object(driver, "clock_publisher", publisher),
            patch.object(driver.client, "request", return_value={
                "simTimeNanos": 12_350_000_001,
            }),
        ):
            self.assertEqual(driver.publish_clock_sample(12_350_000_000), 12_350_000_001)
            message = publisher.publish.call_args.args[0]
            self.assertEqual((message.clock.sec, message.clock.nanosec), (12, 350_000_001))
            with self.assertRaisesRegex(ValueError, "backwards"):
                driver.publish_clock_sample(12_350_000_002)

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
            payload["objects"][0]["id"],
            f"{driver.minecraft_mod.id}/minecraft:overworld@4,64,3",
        )
        self.assertEqual(
            (payload["objects"][0]["x"], payload["objects"][0]["y"], payload["objects"][0]["z"]),
            (4.5, -3.5, 64.5),
        )
        self.assertEqual(payload["objects"][0]["last_seen_unix"], 1_699_999_999.0)
        self.assertEqual(
            tuple(payload["objects"][0][name] for name in ("size_x", "size_y", "size_z")),
            (1.0, 1.0, 1.0),
        )
        self.assertEqual(
            driver.native_block_pos(
                f"{driver.minecraft_mod.id}/minecraft:overworld@4,64,3",
            ),
            (4, 64, 3),
        )

    def test_semantic_objects_payload_tracks_moving_zombie_by_uuid(self) -> None:
        """Publish entity centre and geometry while retaining only native UUID identity."""
        entity_uuid = "12345678-1234-5678-1234-567812345678"
        payload = semantic_objects_payload({
            "timestamp": "2023-11-14T22:13:20.123Z",
            "semanticBlocks": [],
            "nearbyEntities": [{
                "id": f"entity:minecraft:overworld@{entity_uuid}",
                "uuid": entity_uuid,
                "name": "minecraft:zombie",
                "position": {"x": 4.0, "y": 65.0, "z": 3.0},
                "yaw": 0.0,
                "width": 0.6,
                "height": 1.95,
                "depth": 0.6,
                "lastSeen": "2023-11-14T22:13:20.123Z",
            }],
        })
        obj = payload["objects"][0]
        self.assertEqual(obj["label"], "minecraft:zombie")
        self.assertEqual((obj["x"], obj["y"], obj["z"]), (4.0, -3.0, 65.0))
        self.assertAlmostEqual(obj["yaw"], math.pi / 2.0)
        self.assertEqual(
            (obj["size_x"], obj["size_y"], obj["size_z"]),
            (0.6, 0.6, 1.95),
        )
        self.assertEqual(
            driver.native_entity_target(obj["id"], "minecraft:zombie"),
            (entity_uuid, "minecraft:zombie"),
        )

        semantic_objects_payload({
            "timestamp": "2023-11-14T22:13:21.123Z",
            "semanticBlocks": [],
            "nearbyEntities": [],
        })
        with self.assertRaisesRegex(ValueError, "unknown or stale"):
            driver.native_entity_target(obj["id"], "minecraft:zombie")

    def test_mod_combat_is_uuid_bound_bounded_and_policy_guarded(self) -> None:
        """Keep combat policy in the Minecraft Mod rather than Scene."""
        root = Path(__file__).resolve().parents[1]
        actions = (
            root / "src/main/java/com/robonix/minecraftmod/MinecraftActions.java"
        ).read_text(encoding="utf-8")
        observation = (
            root / "src/main/java/com/robonix/minecraftmod/ObservationFactory.java"
        ).read_text(encoding="utf-8")
        self.assertIn("UUID.fromString(entityUuid)", actions)
        self.assertIn("MAX_ATTACK_ATTEMPTS = 30", actions)
        self.assertIn("MIN_COMBAT_HEALTH = 4.0F", actions)
        self.assertIn("FULL_ATTACK_STRENGTH = 0.999F", actions)
        self.assertIn("MIN_ATTACK_INTERVAL_NANOS = 650_000_000L", actions)
        self.assertIn("getAttackStrengthScale(0.0F) < FULL_ATTACK_STRENGTH", actions)
        self.assertIn("living.hurtTime > 0", actions)
        self.assertIn("!action.attackIntervalElapsed(now)", actions)
        self.assertIn("minecraft.gameMode.attack(player, entity)", actions)
        self.assertIn("ATTACKABLE_ENTITIES = Set.of(ZOMBIE, PLAYER)", actions)
        self.assertIn("PlayerCombatPolicy.permits(target)", actions)
        self.assertIn("chaseUntilDeath ? Long.MAX_VALUE", actions)
        self.assertIn("!action.chaseUntilDeath()", actions)
        self.assertIn(
            "!action.chaseUntilDeath() && !hasSafeCombatStep", actions,
        )
        self.assertIn("SEMANTIC_ENTITY_WHITELIST = Set.of", observation)
        self.assertIn('"minecraft:zombie"', observation)
        self.assertIn('"minecraft:player"', observation)
        self.assertIn("PlayerCombatPolicy.permits(targetPlayer)", observation)
        policy = (
            root / "src/main/java/com/robonix/minecraftmod/PlayerCombatPolicy.java"
        ).read_text(encoding="utf-8")
        self.assertIn('MODE_ENV = "RBNX_MINECRAFT_PLAYER_ATTACK_MODE"', policy)
        self.assertIn('CHASE_MODE_ENV = "RBNX_MINECRAFT_PLAYER_CHASE_MODE"', policy)
        self.assertIn('equalsIgnoreCase("until_death")', policy)
        self.assertIn('mode.equals("all")', policy)
        self.assertIn('mode.equals("allowlist")', policy)
        self.assertIn('Set.of("disabled", "allowlist", "all")', policy)
        self.assertIn('getOrDefault(ENABLED_ENV, "false")', policy)
        self.assertIn('getOrDefault(ALLOWLIST_ENV, "")', policy)
        self.assertIn("allowed.contains(target.getUUID().toString()", policy)
        self.assertIn("allowed.contains(target.getGameProfile().getName()", policy)

    def test_semantic_whitelist_distinguishes_empty_and_water_cauldrons(self) -> None:
        """Keep both cauldron block-state registry names visible to Scene."""
        source_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "main"
            / "java"
            / "com"
            / "robonix"
            / "minecraftmod"
            / "ObservationFactory.java"
        )
        source = source_path.read_text(encoding="utf-8")
        whitelist = source[
            source.index("SEMANTIC_BLOCK_WHITELIST"):
            source.index("DISCOVERED_BLOCKS")
        ]
        self.assertIn('"minecraft:cauldron"', whitelist)
        self.assertIn('"minecraft:water_cauldron"', whitelist)

    def test_view_quaternion_is_normalized(self) -> None:
        """Represent yaw and positive-down pitch with the expected ROS axes."""
        quaternion = quaternion_from_yaw_pitch(math.pi / 2.0, 0.25)
        self.assertAlmostEqual(sum(value * value for value in quaternion), 1.0)
        neutral = quaternion_from_yaw_pitch(0.0, 0.0)
        self.assertEqual(neutral, (-0.0, 0.0, 0.0, 1.0))
        down = quaternion_from_yaw_pitch(0.0, math.pi / 2.0)
        self.assertAlmostEqual(down[1], math.sqrt(0.5))
        self.assertAlmostEqual(down[3], math.sqrt(0.5))

    def test_camera_quaternion_carries_pitch_after_optical_axis_conversion(self) -> None:
        """Keep head pitch on the camera child while the navigation base stays level."""
        neutral = camera_quaternion_from_pitch(0.0)
        self.assertEqual(neutral, (-0.5, 0.5, -0.5, 0.5))
        pitched = camera_quaternion_from_pitch(0.4)
        self.assertAlmostEqual(sum(value * value for value in pitched), 1.0)
        self.assertNotEqual(pitched, neutral)

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

    def test_route_inspection_configuration_is_system_owned_and_bounded(self) -> None:
        """Load corridor geometry from provider config and reject unsafe values."""
        self.assertEqual(configured_route_inspection({}), (0.8, 0.25, 64))
        self.assertEqual(configured_route_inspection({
            "route_inspection_half_width_m": 0.5,
            "route_inspection_sample_spacing_m": 0.1,
            "route_inspection_max_blocks": 32,
        }), (0.5, 0.1, 32))
        invalid = (
            {"route_inspection_half_width_m": 1.6},
            {"route_inspection_sample_spacing_m": 0.0},
            {"route_inspection_max_blocks": True},
            {"route_inspection_max_blocks": 256.5},
        )
        for config in invalid:
            with self.subTest(config=config), self.assertRaises(ValueError):
                configured_route_inspection(config)

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
        published: list[object] = []

        def publish(_publisher, image) -> None:
            """Record valid output and stop the single test iteration."""
            if image.encoding == "error":
                raise RuntimeError("invalid RGB")
            published.append(image)
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
        self.assertEqual([image.encoding for image in published], ["32FC1"])
        stamp = publish_intrinsics.call_args.args[3]
        self.assertEqual(
            (stamp.sec, stamp.nanosec),
            (
                published[0].header.stamp.sec,
                published[0].header.stamp.nanosec,
            ),
        )
        publish_intrinsics.assert_called_once_with(1, 1, 70.0, stamp)

    def test_sim_camera_stream_publishes_one_tick_matched_rgbd_group(self) -> None:
        """Fetch RGB-D atomically and preserve one capture stamp through CameraInfo."""
        buffer = BytesIO()
        PILImage.new("RGB", (1, 1), (20, 40, 60)).save(buffer, format="JPEG")
        sample = {
            "renderedView": {
                "width": 1,
                "height": 1,
                "tickId": 247,
                "simTimeNanos": 12_350_000_000,
                "image": {
                    "dataUrl": "data:image/jpeg;base64,"
                    + base64.b64encode(buffer.getvalue()).decode("ascii"),
                },
            },
            "depthView": {
                "width": 1,
                "height": 1,
                "tickId": 247,
                "simTimeNanos": 12_350_000_000,
                "verticalFovDeg": 70.0,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64,"
                    + base64.b64encode(b"\x00\x00\x80?").decode("ascii"),
                },
            },
        }
        published: list[object] = []

        def publish(_publisher, image) -> None:
            published.append(image)
            if len(published) == 2:
                driver.camera_stream_stop.set()

        driver.camera_stream_stop.clear()
        try:
            with (
                patch.object(driver, "use_sim_time", True),
                patch.object(driver.client, "request", return_value=sample) as request,
                patch.object(driver, "publish_intrinsics") as intrinsics,
                patch.object(driver, "publish_ros_image", side_effect=publish),
            ):
                driver.camera_stream_loop()
        finally:
            driver.camera_stream_stop.clear()
        request.assert_called_once_with({"cmd": "rgbd_snapshot"})
        self.assertEqual(
            [(item.header.stamp.sec, item.header.stamp.nanosec) for item in published],
            [(12, 350_000_000), (12, 350_000_000)],
        )
        stamp = intrinsics.call_args.args[3]
        self.assertEqual((stamp.sec, stamp.nanosec), (12, 350_000_000))

    def test_sim_camera_stream_adds_realsense_uptime_shadows(self) -> None:
        """Keep truth topics intact while publishing raw device-clock copies."""
        buffer = BytesIO()
        PILImage.new("RGB", (1, 1), (20, 40, 60)).save(buffer, format="JPEG")
        sample = {
            "renderedView": {
                "width": 1,
                "height": 1,
                "tickId": 247,
                "partialTick": 0.5,
                "captureSequence": 10,
                "simTimeNanos": 12_375_000_000,
                "image": {"dataUrl": "data:image/jpeg;base64,"
                          + base64.b64encode(buffer.getvalue()).decode("ascii")},
            },
            "depthView": {
                "width": 1,
                "height": 1,
                "tickId": 247,
                "partialTick": 0.5,
                "captureSequence": 11,
                "simTimeNanos": 12_375_000_000,
                "verticalFovDeg": 70.0,
                "image": {
                    "encoding": "32FC1",
                    "dataUrl": "data:application/octet-stream;base64,"
                    + base64.b64encode(b"\x00\x00\x80?").decode("ascii"),
                },
            },
        }
        emulator = driver.ClockEmulator.from_config({
            "clock_emulation": {
                "enabled": True,
                "realsense": {"initial_uptime_sec": 5, "drift_ppm": 0, "jitter_ms": 0},
            },
        })
        published: list[tuple[object, int | None]] = []

        def publish(publisher, _image, *, timestamp_ns=None) -> None:
            published.append((publisher, timestamp_ns))
            if len(published) == 4:
                driver.camera_stream_stop.set()

        driver.camera_stream_stop.clear()
        try:
            with (
                patch.object(driver, "use_sim_time", True),
                patch.object(driver, "clock_emulator", emulator),
                patch.object(driver.client, "request", return_value=sample),
                patch.object(driver, "publish_intrinsics"),
                patch.object(driver, "publish_intrinsics_to") as raw_info,
                patch.object(driver, "publish_clock_diagnostic") as diagnostic,
                patch.object(driver, "publish_ros_image", side_effect=publish),
                patch.object(driver, "raw_rgb_publisher", "raw-rgb"),
                patch.object(driver, "raw_depth_publisher", "raw-depth"),
                patch.object(driver, "raw_intrinsics_publisher", "raw-info"),
            ):
                driver.camera_stream_loop()
        finally:
            driver.camera_stream_stop.clear()
        self.assertEqual(published[-2:], [("raw-rgb", 5_000_000_000),
                                          ("raw-depth", 5_000_000_000)])
        self.assertEqual(raw_info.call_args.args[0], "raw-info")
        diagnostic.assert_called_once()

    def test_lidar_stream_adds_ptp_shadow(self) -> None:
        """Publish a PTP-stamped scan beside the original truth scan."""
        result = {
            "lidarScan": {
                "tickId": 20,
                "partialTick": 0.25,
                "captureSequence": 9,
                "simTimeNanos": 1_012_500_000,
            },
        }
        emulator = driver.ClockEmulator.from_config({
            "clock_emulation": {
                "enabled": True,
                "lidar_ptp": {"locked_offset_us": 100, "locked_jitter_us": 0},
            },
        })
        published: list[tuple[object | None, int | None]] = []

        def publish(_result, *, publisher=None, timestamp_ns=None) -> None:
            published.append((publisher, timestamp_ns))
            if len(published) == 2:
                driver.lidar_stream_stop.set()

        driver.lidar_stream_stop.clear()
        try:
            with (
                patch.object(driver, "clock_emulator", emulator),
                patch.object(driver.client, "request", return_value=result),
                patch.object(driver, "publish_lidar_scan", side_effect=publish),
                patch.object(driver, "publish_clock_diagnostic") as diagnostic,
                patch.object(driver, "raw_lidar_publisher", "raw-lidar"),
            ):
                driver.lidar_stream_loop()
        finally:
            driver.lidar_stream_stop.clear()
        self.assertEqual(published, [(None, None), ("raw-lidar", 1_012_600_000)])
        diagnostic.assert_called_once()

    def test_publish_intrinsics_uses_depth_shape_and_vertical_fov(self) -> None:
        """Publish a latched-compatible pinhole matrix for the captured frame."""
        class FakeCameraInfo:
            """Provide mutable sensor_msgs fields populated by the provider."""

            def __init__(self) -> None:
                """Initialize the ROS message fields needed by the helper."""
                self.header = SimpleNamespace(
                    stamp=SimpleNamespace(sec=0, nanosec=0),
                    frame_id="",
                )
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
        driver.intrinsics_publisher = publisher
        stamp = SimpleNamespace(sec=123, nanosec=456)
        try:
            with patch.dict(
                sys.modules,
                {"sensor_msgs": sensor_msgs, "sensor_msgs.msg": sensor_msgs_msg},
            ):
                driver.publish_intrinsics(960, 540, 70.0, stamp)
                driver.publish_intrinsics(960, 540, 70.0, stamp)
        finally:
            driver.intrinsics_publisher = previous_publisher
        self.assertEqual(publisher.publish.call_count, 2)
        message = publisher.publish.call_args.args[0]
        focal = 540.0 / (2.0 * math.tan(math.radians(35.0)))
        self.assertEqual((message.width, message.height), (960, 540))
        self.assertEqual(message.header.frame_id, driver.camera_frame_id)
        self.assertEqual(
            (message.header.stamp.sec, message.header.stamp.nanosec),
            (123, 456),
        )
        self.assertAlmostEqual(message.k[0], focal)
        self.assertAlmostEqual(message.k[4], focal)
        self.assertEqual((message.k[2], message.k[5]), (479.5, 269.5))

    def test_publish_odom_maps_frames_pose_and_velocity(self) -> None:
        """Publish matching ENU odometry and odom-to-base TF samples."""
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

        class FakeTransformStamped:
            """Provide the nested mutable fields used by the TF broadcaster."""

            def __init__(self) -> None:
                """Create one mutable TransformStamped-shaped object."""
                vector = lambda: SimpleNamespace(x=0.0, y=0.0, z=0.0)
                self.header = SimpleNamespace(
                    stamp=SimpleNamespace(sec=0, nanosec=0),
                    frame_id="",
                )
                self.child_frame_id = ""
                self.transform = SimpleNamespace(
                    translation=vector(),
                    rotation=SimpleNamespace(x=0.0, y=0.0, z=0.0, w=0.0),
                )

        nav_msgs = ModuleType("nav_msgs")
        nav_msgs_msg = ModuleType("nav_msgs.msg")
        nav_msgs_msg.Odometry = FakeOdometry
        nav_msgs.msg = nav_msgs_msg
        geometry_msgs = ModuleType("geometry_msgs")
        geometry_msgs_msg = ModuleType("geometry_msgs.msg")
        geometry_msgs_msg.TransformStamped = FakeTransformStamped
        geometry_msgs.msg = geometry_msgs_msg
        publisher = SimpleNamespace(publish=Mock())
        broadcaster = SimpleNamespace(sendTransform=Mock())
        extrinsics_publisher = SimpleNamespace(publish=Mock())
        camera_broadcaster = SimpleNamespace(sendTransform=Mock())
        observation = {
            "position": {"x": 4.0, "y": 64.0, "z": 3.0},
            "yaw": 0.0,
            "pitch": 0.4,
        }
        previous_publisher = driver.odom_publisher
        previous_broadcaster = driver.odom_transform_broadcaster
        previous_extrinsics_publisher = driver.extrinsics_publisher
        previous_camera_broadcaster = driver.camera_transform_broadcaster
        previous_sample = driver.previous_odom_sample
        driver.odom_publisher = publisher
        driver.odom_transform_broadcaster = broadcaster
        driver.extrinsics_publisher = extrinsics_publisher
        driver.camera_transform_broadcaster = camera_broadcaster
        driver.previous_odom_sample = (9.0, 4.0, -2.0, math.pi / 2.0)
        try:
            with patch.dict(sys.modules, {
                "geometry_msgs": geometry_msgs,
                "geometry_msgs.msg": geometry_msgs_msg,
                "nav_msgs": nav_msgs,
                "nav_msgs.msg": nav_msgs_msg,
            }):
                driver.publish_odom(observation, timestamp=10.0)
        finally:
            driver.odom_publisher = previous_publisher
            driver.odom_transform_broadcaster = previous_broadcaster
            driver.extrinsics_publisher = previous_extrinsics_publisher
            driver.camera_transform_broadcaster = previous_camera_broadcaster
            driver.previous_odom_sample = previous_sample
        message = publisher.publish.call_args.args[0]
        self.assertEqual((message.header.frame_id, message.child_frame_id), (
            driver.odom_frame_id, driver.base_frame_id,
        ))
        self.assertEqual((message.pose.pose.position.x, message.pose.pose.position.y), (4.0, -3.0))
        self.assertEqual((
            message.pose.pose.orientation.x,
            message.pose.pose.orientation.y,
            message.pose.pose.orientation.z,
            message.pose.pose.orientation.w,
        ), quaternion_from_yaw_pitch(math.pi / 2.0, 0.0))
        self.assertAlmostEqual(message.twist.twist.linear.x, -1.0)
        self.assertAlmostEqual(message.twist.twist.linear.y, 0.0)
        transform = broadcaster.sendTransform.call_args.args[0]
        self.assertEqual(
            (transform.header.stamp.sec, transform.header.stamp.nanosec),
            (message.header.stamp.sec, message.header.stamp.nanosec),
        )
        self.assertEqual((transform.header.frame_id, transform.child_frame_id), (
            driver.odom_frame_id, driver.base_frame_id,
        ))
        self.assertEqual((
            transform.transform.translation.x,
            transform.transform.translation.y,
            transform.transform.translation.z,
        ), (
            message.pose.pose.position.x,
            message.pose.pose.position.y,
            message.pose.pose.position.z,
        ))
        self.assertEqual((
            transform.transform.rotation.x,
            transform.transform.rotation.y,
            transform.transform.rotation.z,
            transform.transform.rotation.w,
        ), (
            message.pose.pose.orientation.x,
            message.pose.pose.orientation.y,
            message.pose.pose.orientation.z,
            message.pose.pose.orientation.w,
        ))
        camera_transform = camera_broadcaster.sendTransform.call_args.args[0]
        self.assertEqual((camera_transform.header.stamp.sec, camera_transform.header.stamp.nanosec), (
            message.header.stamp.sec, message.header.stamp.nanosec,
        ))
        self.assertEqual((camera_transform.header.frame_id, camera_transform.child_frame_id), (
            driver.base_frame_id, driver.camera_frame_id,
        ))
        self.assertEqual((
            camera_transform.transform.rotation.x,
            camera_transform.transform.rotation.y,
            camera_transform.transform.rotation.z,
            camera_transform.transform.rotation.w,
        ), camera_quaternion_from_pitch(0.4))

    def test_publish_extrinsics_broadcasts_timestamped_pitch_tf(self) -> None:
        """Expose the same pitched camera transform on the contract and TF tree."""
        class FakeTransformStamped:
            """Provide the fixed transform fields populated by the provider."""

            def __init__(self) -> None:
                """Create one mutable TransformStamped-shaped object."""
                self.header = SimpleNamespace(
                    stamp=SimpleNamespace(sec=0, nanosec=0),
                    frame_id="",
                )
                self.child_frame_id = ""
                self.transform = SimpleNamespace(
                    rotation=SimpleNamespace(x=0.0, y=0.0, z=0.0, w=0.0),
                )

        geometry_msgs = ModuleType("geometry_msgs")
        geometry_msgs_msg = ModuleType("geometry_msgs.msg")
        geometry_msgs_msg.TransformStamped = FakeTransformStamped
        geometry_msgs.msg = geometry_msgs_msg
        publisher = SimpleNamespace(publish=Mock())
        broadcaster = SimpleNamespace(sendTransform=Mock())
        previous_publisher = driver.extrinsics_publisher
        previous_broadcaster = driver.camera_transform_broadcaster
        driver.extrinsics_publisher = publisher
        driver.camera_transform_broadcaster = broadcaster
        try:
            with patch.dict(sys.modules, {
                "geometry_msgs": geometry_msgs,
                "geometry_msgs.msg": geometry_msgs_msg,
            }):
                driver.publish_extrinsics(pitch=0.4, timestamp=12.25)
        finally:
            driver.extrinsics_publisher = previous_publisher
            driver.camera_transform_broadcaster = previous_broadcaster
        message = publisher.publish.call_args.args[0]
        broadcaster.sendTransform.assert_called_once_with(message)
        self.assertEqual((message.header.frame_id, message.child_frame_id), (
            driver.base_frame_id, driver.camera_frame_id,
        ))
        self.assertEqual((message.header.stamp.sec, message.header.stamp.nanosec), (
            12, 250_000_000,
        ))
        self.assertEqual((
            message.transform.rotation.x,
            message.transform.rotation.y,
            message.transform.rotation.z,
            message.transform.rotation.w,
        ), camera_quaternion_from_pitch(0.4))

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
