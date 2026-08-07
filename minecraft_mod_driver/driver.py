#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Expose the Forge client Mod through its confirmed primitive contracts."""
from __future__ import annotations

import base64
import json
import math
import os
import re
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from io import BytesIO
from pathlib import Path
from typing import Any

from robonix_api import Err, Ok, Primitive
from robonix_api.ros import RosBackend
import chassis_mcp  # noqa: E402
import minecraft_mcp  # noqa: E402
import chassis_pb2  # noqa: E402
import std_msgs_pb2  # noqa: E402

import builtin_interfaces_mcp  # noqa: E402
import std_msgs_mcp  # noqa: E402
from sensor_msgs_mcp import Image  # noqa: E402
from std_msgs_mcp import Empty  # noqa: E402


MAX_RESPONSE_BYTES = 64 * 1024 * 1024
# One deploy manifest entry is one Atlas provider and one lifecycle owner.
# Keep the camera driver as this aggregate primitive's package-level lifecycle
# contract while declaring the colocated chassis, lidar, perception, and
# Minecraft contracts on the same provider.
minecraft_mod = Primitive(id="minecraft_mod", namespace="robonix/primitive/camera")
minecraft_camera = minecraft_mod
minecraft_chassis = minecraft_mod
minecraft_perception = minecraft_mod
minecraft_lidar = minecraft_mod
minecraft_actions = minecraft_mod
rgb_publisher = None
depth_publisher = None
extrinsics_publisher = None
intrinsics_publisher = None
odom_publisher = None
objects_publisher = None
lidar_publisher = None
odom_transform_broadcaster = None
static_transform_broadcaster = None
cmd_vel_subscription = None
camera_stream_stop = threading.Event()
odom_stream_stop = threading.Event()
lidar_stream_stop = threading.Event()
cmd_vel_bridge_stop = threading.Event()
camera_stream_thread: threading.Thread | None = None
odom_stream_thread: threading.Thread | None = None
lidar_stream_thread: threading.Thread | None = None
cmd_vel_bridge_thread: threading.Thread | None = None
camera_lifecycle_lock = threading.Lock()
chassis_lifecycle_lock = threading.Lock()
camera_worker_lock = threading.Lock()
odom_worker_lock = threading.Lock()
lidar_worker_lock = threading.Lock()
cmd_vel_worker_lock = threading.Lock()
cmd_vel_state_lock = threading.Lock()
stream_error_lock = threading.Lock()
last_stream_errors: dict[str, tuple[str, float]] = {}
semantic_blocks_lock = threading.Lock()
semantic_block_positions: dict[str, tuple[int, int, int]] = {}
semantic_entity_targets: dict[str, tuple[str, str]] = {}
route_obstacles_lock = threading.Lock()


@dataclass(frozen=True)
class RouteObstacle:
    """Native target retained behind one short-lived opaque handle."""

    block_name: str
    position: tuple[int, int, int]
    recovery_token: str
    expires_monotonic: float


@dataclass(frozen=True)
class RoutePathInspection:
    """Native dirt targets retained behind one short-lived path handle."""

    dirt_positions: tuple[tuple[int, int, int], ...]
    other_count: int
    recovery_token: str
    expires_monotonic: float


route_obstacles: dict[str, RouteObstacle] = {}
route_path_inspections: dict[str, RoutePathInspection] = {}
latest_cmd_vel: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)
previous_odom_sample: tuple[float, float, float, float] | None = None
camera_stream_hz = 5.0
odom_stream_hz = 10.0
lidar_stream_hz = 5.0
lidar_ray_count = 180
lidar_range_max = 16.0
camera_frame_id = "minecraft_camera_optical_frame"
base_frame_id = "minecraft_view_base"
lidar_frame_id = base_frame_id
odom_frame_id = "minecraft_odom"
eye_height = 1.62
rgb_topic = "/minecraft/camera/rgb"
depth_topic = "/minecraft/camera/depth"
extrinsics_topic = "/minecraft/camera/extrinsics"
intrinsics_topic = "/minecraft/camera/intrinsics"
odom_topic = "/minecraft/chassis/odom"
objects_topic = "/minecraft/perception/objects"
lidar_topic = "/minecraft/lidar/scan"
cmd_vel_topic = "/cmd_vel"
cmd_vel_command_duration_sec = 0.25
cmd_vel_timeout_sec = 0.5
cmd_vel_max_linear = 1.2
cmd_vel_max_angular = 1.0


class MinecraftModClient:
    """Sends serialized requests to the Forge Mod loopback interface."""

    def __init__(self) -> None:
        """Load loopback connection settings and reject an unsafe request timeout."""
        self.host = os.environ.get("RBNX_MINECRAFT_MOD_HOST", "127.0.0.1")
        self.port = int(os.environ.get("RBNX_MINECRAFT_MOD_PORT", "25576"))
        self.timeout = float(os.environ.get("RBNX_MINECRAFT_MOD_TIMEOUT_SEC", "35"))
        if not math.isfinite(self.timeout) or not 0.1 <= self.timeout <= 300.0:
            raise ValueError("RBNX_MINECRAFT_MOD_TIMEOUT_SEC must be from 0.1 through 300")
        self.id_lock = threading.Lock()
        self.next_id = 1

    def request(self, payload: dict[str, Any], timeout_sec: float | None = None) -> dict[str, Any]:
        """Send one JSON-lines request over a short-lived loopback connection."""
        timeout = self.timeout if timeout_sec is None else timeout_sec
        if not math.isfinite(timeout) or not 0.1 <= timeout <= 300.0:
            raise ValueError("request timeout must be from 0.1 through 300 seconds")
        with self.id_lock:
            request_id = str(self.next_id)
            self.next_id += 1
        request = {"id": request_id, **payload}
        with socket.create_connection((self.host, self.port), timeout=timeout) as connection:
            connection.settimeout(timeout)
            connection.sendall(json.dumps(request).encode("utf-8") + b"\n")
            response = self._read_response(connection)
        if str(response.get("id")) != request_id:
            raise RuntimeError("Minecraft Mod response id mismatch")
        if not response.get("ok"):
            error = response.get("error") or {}
            message = error.get("message") if isinstance(error, dict) else str(error)
            raise RuntimeError(message or "Minecraft Mod command failed")
        result = response.get("result") or {}
        if not isinstance(result, dict):
            raise RuntimeError("Minecraft Mod result is not an object")
        return result

    def wait_ready(self, timeout_ms: int) -> dict[str, Any]:
        """Wait until the Forge client has joined a world and can be observed."""
        deadline = time.monotonic() + timeout_ms / 1000.0
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            try:
                return self.request({"cmd": "observe"})
            except (OSError, RuntimeError) as exc:
                last_error = exc
                time.sleep(0.25)
        raise TimeoutError(f"Minecraft Mod was not ready within {timeout_ms}ms: {last_error}")

    @staticmethod
    def _read_response(connection: socket.socket) -> dict[str, Any]:
        """Read one bounded response line and reject truncated or oversized data."""
        chunks = bytearray()
        while len(chunks) <= MAX_RESPONSE_BYTES:
            chunk = connection.recv(65536)
            if not chunk:
                break
            chunks.extend(chunk)
            newline = chunks.find(b"\n")
            if newline >= 0:
                if newline > MAX_RESPONSE_BYTES:
                    raise RuntimeError("Minecraft Mod response is too large")
                response = json.loads(chunks[:newline].decode("utf-8"))
                if not isinstance(response, dict):
                    raise RuntimeError("Minecraft Mod response is not an object")
                return response
            if len(chunks) > MAX_RESPONSE_BYTES:
                raise RuntimeError("Minecraft Mod response is too large")
        raise RuntimeError("Minecraft Mod returned no complete response")


client = MinecraftModClient()
OBSERVATION_FILE = Path(os.environ.get(
    "MINECRAFT_OBSERVATION_FILE",
    str(Path(__file__).resolve().parents[1] / "rbnx-build" / "minecraft_observation.json"),
))


def persist_observation(observation: dict[str, Any]) -> None:
    """Atomically write the latest structured observation for other services."""
    if not observation:
        return
    try:
        OBSERVATION_FILE.parent.mkdir(parents=True, exist_ok=True)
        temporary = OBSERVATION_FILE.with_suffix(".tmp")
        temporary.write_text(json.dumps(observation), encoding="utf-8")
        temporary.replace(OBSERVATION_FILE)
    except Exception as exc:  # noqa: BLE001
        print(f"[minecraft-mod] persist observation failed: {exc}")


def now_header(frame_id: str, timestamp_sec: float | None = None) -> std_msgs_mcp.Header:
    """Build an image header using a valid capture time or the current time."""
    now = timestamp_sec if timestamp_sec is not None and math.isfinite(timestamp_sec) else time.time()
    if now <= 0.0:
        now = time.time()
    sec = int(now)
    nanosec = int((now % 1) * 1e9) % 1_000_000_000
    return std_msgs_mcp.Header(
        stamp=builtin_interfaces_mcp.Time(sec=sec, nanosec=nanosec),
        frame_id=frame_id,
    )


def image_error(message: str) -> Image:
    """Encode a capture failure as the established zero-size image payload."""
    raw = message.encode("utf-8")
    return Image(
        header=now_header("minecraft_mod_error"),
        height=0,
        width=0,
        encoding="error",
        is_bigendian=0,
        step=len(raw),
        data=raw,
    )


def rendered_view_to_image(result: dict[str, Any]) -> Image:
    """Convert the Mod rendered-view response into a JPEG image payload."""
    view = result.get("renderedView") or {}
    image = view.get("image") or {}
    data_url = image.get("dataUrl") or ""
    prefix = "data:image/jpeg;base64,"
    if not data_url.startswith(prefix):
        return image_error("renderedView JPEG image missing")
    raw = base64.b64decode(data_url[len(prefix):], validate=True)
    return Image(
        header=now_header(camera_frame_id, float(view.get("timestampMs") or 0) / 1000.0),
        height=int(view.get("height") or 0),
        width=int(view.get("width") or 0),
        encoding="jpeg",
        is_bigendian=0,
        step=len(raw),
        data=raw,
    )


def rendered_view_to_rgb_image(result: dict[str, Any]) -> Image:
    """Decode the Mod JPEG into the uncompressed RGB8 stream contract."""
    compressed = rendered_view_to_image(result)
    if compressed.encoding == "error":
        return compressed
    try:
        from PIL import Image as PILImage

        with PILImage.open(BytesIO(compressed.data)) as source:
            rgb = source.convert("RGB")
            if rgb.size != (compressed.width, compressed.height):
                return image_error("renderedView dimensions do not match the JPEG")
            raw = rgb.tobytes()
    except Exception as exc:  # noqa: BLE001
        return image_error(f"renderedView JPEG decode failed: {exc}")
    return Image(
        header=compressed.header,
        height=compressed.height,
        width=compressed.width,
        encoding="rgb8",
        is_bigendian=0,
        step=compressed.width * 3,
        data=raw,
    )


def depth_view_to_image(result: dict[str, Any]) -> Image:
    """Convert the Mod depth response into a raw little-endian 32FC1 image."""
    view = result.get("depthView") or {}
    image = view.get("image") or {}
    data_url = image.get("dataUrl") or ""
    prefix = "data:application/octet-stream;base64,"
    if image.get("encoding") != "32FC1" or not data_url.startswith(prefix):
        return image_error("depthView 32FC1 image missing")
    raw = base64.b64decode(data_url[len(prefix):], validate=True)
    width = int(view.get("width") or 0)
    height = int(view.get("height") or 0)
    if width < 1 or height < 1 or len(raw) != width * height * 4:
        return image_error("depthView 32FC1 dimensions or payload are invalid")
    return Image(
        header=now_header(camera_frame_id, float(view.get("timestampMs") or 0) / 1000.0),
        height=height,
        width=width,
        encoding="32FC1",
        is_bigendian=0,
        step=width * 4,
        data=raw,
    )


def lidar_scan_values(result: dict[str, Any]) -> dict[str, Any]:
    """Validate one Mod collision scan and normalize missing returns to infinity."""
    scan = result.get("lidarScan") or {}
    raw_ranges = scan.get("ranges")
    if not isinstance(raw_ranges, list) or len(raw_ranges) < 16:
        raise ValueError("lidar scan must contain at least 16 ranges")
    values = {
        name: float(scan.get(source))
        for name, source in (
            ("timestamp", "timestampMs"),
            ("angle_min", "angleMin"),
            ("angle_max", "angleMax"),
            ("angle_increment", "angleIncrement"),
            ("range_min", "rangeMin"),
            ("range_max", "rangeMax"),
        )
    }
    if not all(math.isfinite(value) for value in values.values()):
        raise ValueError("lidar scan metadata must be finite")
    if values["timestamp"] <= 0.0:
        raise ValueError("lidar timestamp must be positive")
    if values["angle_increment"] <= 0.0:
        raise ValueError("lidar angle increment must be positive")
    if not 0.0 <= values["range_min"] < values["range_max"]:
        raise ValueError("lidar range limits are invalid")
    expected_max = values["angle_min"] + (len(raw_ranges) - 1) * values["angle_increment"]
    if not math.isclose(values["angle_max"], expected_max, rel_tol=1e-6, abs_tol=1e-6):
        raise ValueError("lidar angular metadata does not match its ranges")
    ranges: list[float] = []
    for raw in raw_ranges:
        if raw is None:
            ranges.append(math.inf)
            continue
        distance = float(raw)
        if not math.isfinite(distance) or distance < 0.0:
            raise ValueError("lidar ranges must be finite non-negative values or null")
        ranges.append(distance)
    values["timestamp"] /= 1000.0
    values["ranges"] = ranges
    return values


def publish_lidar_scan(result: dict[str, Any]) -> None:
    """Publish one validated body-relative collision scan as ROS LaserScan."""
    if lidar_publisher is None:
        raise RuntimeError("lidar publisher is not initialized")
    from sensor_msgs.msg import LaserScan  # type: ignore

    values = lidar_scan_values(result)
    message = LaserScan()
    timestamp = values["timestamp"]
    message.header.stamp.sec = int(timestamp)
    message.header.stamp.nanosec = int((timestamp % 1.0) * 1e9)
    message.header.frame_id = lidar_frame_id
    message.angle_min = values["angle_min"]
    message.angle_max = values["angle_max"]
    message.angle_increment = values["angle_increment"]
    message.time_increment = 0.0
    message.scan_time = 1.0 / lidar_stream_hz
    message.range_min = values["range_min"]
    message.range_max = values["range_max"]
    message.ranges = values["ranges"]
    lidar_publisher.publish(message)


def publish_ros_image(publisher: Any, image: Image) -> None:
    """Publish one validated MCP image through the equivalent ROS message."""
    if image.encoding == "error":
        raise RuntimeError(bytes(image.data).decode("utf-8", errors="replace"))
    from sensor_msgs.msg import Image as RosImage  # type: ignore

    message = RosImage()
    message.header.stamp.sec = int(image.header.stamp.sec)
    message.header.stamp.nanosec = int(image.header.stamp.nanosec)
    message.header.frame_id = image.header.frame_id
    message.height = int(image.height)
    message.width = int(image.width)
    message.encoding = image.encoding
    message.is_bigendian = int(image.is_bigendian)
    message.step = int(image.step)
    message.data = bytes(image.data)
    publisher.publish(message)


def quaternion_from_yaw_pitch(yaw: float, pitch: float) -> tuple[float, float, float, float]:
    """Return a ROS quaternion for zero roll, world yaw, and view pitch."""
    half_yaw = yaw / 2.0
    half_pitch = pitch / 2.0
    sin_yaw, cos_yaw = math.sin(half_yaw), math.cos(half_yaw)
    sin_pitch, cos_pitch = math.sin(half_pitch), math.cos(half_pitch)
    return (
        -sin_pitch * sin_yaw,
        sin_pitch * cos_yaw,
        cos_pitch * sin_yaw,
        cos_pitch * cos_yaw,
    )


def observation_pose(observation: dict[str, Any]) -> tuple[float, float, float, float, float]:
    """Convert Minecraft east/up/south coordinates and view angles to ROS ENU."""
    position = observation.get("position") or {}
    minecraft_x = float(position["x"])
    minecraft_y = float(position["y"])
    minecraft_z = float(position["z"])
    north_zero_yaw = float(observation["yaw"])
    pitch = float(observation.get("pitch") or 0.0)
    values = (minecraft_x, minecraft_y, minecraft_z, north_zero_yaw, pitch)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("Minecraft observation pose must be finite")
    yaw = math.atan2(
        math.sin(north_zero_yaw + math.pi / 2.0),
        math.cos(north_zero_yaw + math.pi / 2.0),
    )
    return minecraft_x, -minecraft_z, minecraft_y + eye_height, yaw, pitch


def observation_timestamp(observation: dict[str, Any]) -> float:
    """Parse the client-thread observation time as Unix seconds for ROS headers."""
    raw = str(observation.get("timestamp") or "")
    try:
        captured = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("Minecraft observation timestamp is invalid") from exc
    if captured.tzinfo is None:
        raise ValueError("Minecraft observation timestamp must include a timezone")
    timestamp = captured.timestamp()
    if not math.isfinite(timestamp) or timestamp < 0.0:
        raise ValueError("Minecraft observation timestamp is invalid")
    return timestamp


def semantic_objects_payload(observation: dict[str, Any]) -> dict[str, Any]:
    """Convert native targets to generic ROS objects and refresh ID resolution."""
    objects = []
    native_positions: dict[str, tuple[int, int, int]] = {}
    native_entities: dict[str, tuple[str, str]] = {}
    for block in observation.get("semanticBlocks") or []:
        position = block.get("position") or {}
        minecraft_x = float(position["x"])
        minecraft_y = float(position["y"])
        minecraft_z = float(position["z"])
        last_seen = observation_timestamp({"timestamp": block["lastSeen"]})
        values = (minecraft_x, minecraft_y, minecraft_z, last_seen)
        if not all(math.isfinite(value) for value in values):
            raise ValueError("Minecraft semantic block coordinates must be finite")
        if not all(value.is_integer() for value in (minecraft_x, minecraft_y, minecraft_z)):
            raise ValueError("Minecraft semantic block positions must be integer BlockPos")
        native_id = str(block["id"] or "").strip()
        if not native_id:
            raise ValueError("Minecraft semantic block id must be non-empty")
        object_id = f"{minecraft_mod.id}/{native_id}"
        native_positions[object_id] = (
            int(minecraft_x), int(minecraft_y), int(minecraft_z),
        )
        objects.append({
            "id": object_id,
            "label": str(block["name"]),
            "x": minecraft_x + 0.5,
            "y": -(minecraft_z + 0.5),
            "z": minecraft_y + 0.5,
            "yaw": 0.0,
            "last_seen_unix": last_seen,
            "size_x": 1.0,
            "size_y": 1.0,
            "size_z": 1.0,
        })
    for entity in observation.get("nearbyEntities") or []:
        position = entity.get("position") or {}
        minecraft_x = float(position["x"])
        minecraft_y = float(position["y"])
        minecraft_z = float(position["z"])
        north_zero_yaw = float(entity["yaw"])
        width = float(entity["width"])
        height = float(entity["height"])
        depth = float(entity["depth"])
        last_seen = observation_timestamp({"timestamp": entity["lastSeen"]})
        values = (
            minecraft_x, minecraft_y, minecraft_z, north_zero_yaw,
            width, height, depth, last_seen,
        )
        if not all(math.isfinite(value) for value in values):
            raise ValueError("Minecraft semantic entity fields must be finite")
        if width <= 0.0 or height <= 0.0 or depth <= 0.0:
            raise ValueError("Minecraft semantic entity dimensions must be positive")
        native_id = str(entity["id"] or "").strip()
        entity_uuid = str(entity["uuid"] or "").strip()
        label = str(entity["name"] or "").strip()
        if not native_id or not entity_uuid or not label:
            raise ValueError("Minecraft semantic entity identity must be non-empty")
        object_id = f"{minecraft_mod.id}/{native_id}"
        native_entities[object_id] = (entity_uuid, label)
        ros_yaw = math.atan2(
            math.sin(north_zero_yaw + math.pi / 2.0),
            math.cos(north_zero_yaw + math.pi / 2.0),
        )
        objects.append({
            "id": object_id,
            "label": label,
            "x": minecraft_x,
            "y": -minecraft_z,
            "z": minecraft_y,
            "yaw": ros_yaw,
            "last_seen_unix": last_seen,
            "size_x": width,
            "size_y": depth,
            "size_z": height,
        })
    with semantic_blocks_lock:
        semantic_block_positions.clear()
        semantic_block_positions.update(native_positions)
        semantic_entity_targets.clear()
        semantic_entity_targets.update(native_entities)
    return {
        "frame_id": odom_frame_id,
        "timestamp": observation_timestamp(observation),
        "objects": objects,
    }


def publish_semantic_objects(observation: dict[str, Any]) -> None:
    """Publish the complete discovered-block snapshot when its publisher is active."""
    if objects_publisher is None:
        return
    from std_msgs.msg import String  # type: ignore

    payload = semantic_objects_payload(observation)
    objects_publisher.publish(String(data=json.dumps(payload, separators=(",", ":"))))


def publish_extrinsics() -> None:
    """Publish the fixed view-base to optical-frame axis conversion."""
    if extrinsics_publisher is None:
        raise RuntimeError("camera extrinsics publisher is not initialized")
    if static_transform_broadcaster is None:
        raise RuntimeError("static transform broadcaster is not initialized")
    from geometry_msgs.msg import TransformStamped  # type: ignore

    message = TransformStamped()
    now = time.time()
    message.header.stamp.sec = int(now)
    message.header.stamp.nanosec = int((now % 1.0) * 1e9)
    message.header.frame_id = base_frame_id
    message.child_frame_id = camera_frame_id
    message.transform.rotation.x = -0.5
    message.transform.rotation.y = 0.5
    message.transform.rotation.z = -0.5
    message.transform.rotation.w = 0.5
    extrinsics_publisher.publish(message)
    static_transform_broadcaster.sendTransform(message)


def publish_intrinsics(
    width: int,
    height: int,
    vertical_fov_deg: float,
    stamp: Any,
) -> None:
    """Publish timestamp-matched pinhole calibration for one depth frame.

    Minecraft's FOV setting is vertical. Capture scaling preserves aspect
    ratio, so square pixels use the same focal length on both axes.
    """
    if intrinsics_publisher is None:
        raise RuntimeError("camera intrinsics publisher is not initialized")
    values = (float(width), float(height), float(vertical_fov_deg))
    if not all(math.isfinite(value) and value > 0.0 for value in values):
        raise ValueError("camera dimensions and vertical FOV must be positive and finite")
    if vertical_fov_deg >= 180.0:
        raise ValueError("camera vertical FOV must be less than 180 degrees")
    from sensor_msgs.msg import CameraInfo  # type: ignore

    focal = height / (2.0 * math.tan(math.radians(vertical_fov_deg) / 2.0))
    cx = (width - 1.0) / 2.0
    cy = (height - 1.0) / 2.0
    message = CameraInfo()
    message.header.frame_id = camera_frame_id
    message.header.stamp.sec = int(stamp.sec)
    message.header.stamp.nanosec = int(stamp.nanosec)
    message.width = width
    message.height = height
    message.distortion_model = "plumb_bob"
    message.d = [0.0] * 5
    message.k = [focal, 0.0, cx, 0.0, focal, cy, 0.0, 0.0, 1.0]
    message.r = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    message.p = [focal, 0.0, cx, 0.0, 0.0, focal, cy, 0.0, 0.0, 0.0, 1.0, 0.0]
    intrinsics_publisher.publish(message)


def publish_odom(observation: dict[str, Any], timestamp: float | None = None) -> None:
    """Publish exact player view odometry and its matching dynamic transform."""
    global previous_odom_sample
    if odom_publisher is None:
        raise RuntimeError("chassis odom publisher is not initialized")
    if odom_transform_broadcaster is None:
        raise RuntimeError("odometry transform broadcaster is not initialized")
    from geometry_msgs.msg import TransformStamped  # type: ignore
    from nav_msgs.msg import Odometry  # type: ignore

    now = timestamp if timestamp is not None else time.time()
    x, y, z, yaw, pitch = observation_pose(observation)
    forward = left = angular = 0.0
    if previous_odom_sample is not None:
        previous_time, previous_x, previous_y, previous_yaw = previous_odom_sample
        elapsed = now - previous_time
        if elapsed > 0.0:
            world_x = (x - previous_x) / elapsed
            world_y = (y - previous_y) / elapsed
            forward = math.cos(yaw) * world_x + math.sin(yaw) * world_y
            left = -math.sin(yaw) * world_x + math.cos(yaw) * world_y
            yaw_delta = math.atan2(
                math.sin(yaw - previous_yaw),
                math.cos(yaw - previous_yaw),
            )
            angular = yaw_delta / elapsed
    previous_odom_sample = (now, x, y, yaw)

    message = Odometry()
    message.header.stamp.sec = int(now)
    message.header.stamp.nanosec = int((now % 1.0) * 1e9)
    message.header.frame_id = odom_frame_id
    message.child_frame_id = base_frame_id
    message.pose.pose.position.x = x
    message.pose.pose.position.y = y
    message.pose.pose.position.z = z
    quaternion = quaternion_from_yaw_pitch(yaw, pitch)
    message.pose.pose.orientation.x = quaternion[0]
    message.pose.pose.orientation.y = quaternion[1]
    message.pose.pose.orientation.z = quaternion[2]
    message.pose.pose.orientation.w = quaternion[3]
    message.twist.twist.linear.x = forward
    message.twist.twist.linear.y = left
    message.twist.twist.angular.z = angular
    odom_publisher.publish(message)

    transform = TransformStamped()
    transform.header.stamp.sec = message.header.stamp.sec
    transform.header.stamp.nanosec = message.header.stamp.nanosec
    transform.header.frame_id = message.header.frame_id
    transform.child_frame_id = message.child_frame_id
    transform.transform.translation.x = message.pose.pose.position.x
    transform.transform.translation.y = message.pose.pose.position.y
    transform.transform.translation.z = message.pose.pose.position.z
    transform.transform.rotation.x = message.pose.pose.orientation.x
    transform.transform.rotation.y = message.pose.pose.orientation.y
    transform.transform.rotation.z = message.pose.pose.orientation.z
    transform.transform.rotation.w = message.pose.pose.orientation.w
    odom_transform_broadcaster.sendTransform(transform)


def configured_frequency(name: str, fallback: float) -> float:
    """Read one stream frequency and require a finite rate from 0.1 through 60 Hz."""
    value = float(os.environ.get(name, str(fallback)))
    if not math.isfinite(value) or not 0.1 <= value <= 60.0:
        raise ValueError(f"{name} must be finite and from 0.1 through 60")
    return value


def configured_lidar_ray_count(config: dict[str, Any]) -> int:
    """Read the full-revolution sample count within the Mod's safe bound."""
    raw = config.get("lidar_ray_count", os.environ.get(
        "RBNX_MINECRAFT_LIDAR_RAY_COUNT",
        "180",
    ))
    if isinstance(raw, bool):
        raise ValueError("lidar_ray_count must be an integer from 16 through 1440")
    value = int(raw)
    if value != float(raw) or not 16 <= value <= 1440:
        raise ValueError("lidar_ray_count must be an integer from 16 through 1440")
    return value


def configured_lidar_range(config: dict[str, Any]) -> float:
    """Read the collision-ray range within loaded-world and map bounds."""
    value = float(config.get("lidar_range_max", os.environ.get(
        "RBNX_MINECRAFT_LIDAR_RANGE_MAX",
        "16.0",
    )))
    if not math.isfinite(value) or not 1.0 <= value <= 64.0:
        raise ValueError("lidar_range_max must be finite and from 1 through 64")
    return value


def configured_eye_height(config: dict[str, Any]) -> float:
    """Read nominal eye height and require a finite value from zero through three blocks."""
    raw = config["eye_height"] if "eye_height" in config else os.environ.get(
        "RBNX_MINECRAFT_EYE_HEIGHT", "1.62",
    )
    value = float(raw)
    if not math.isfinite(value) or not 0.0 <= value <= 3.0:
        raise ValueError("eye_height must be finite and from 0 through 3")
    return value


def configured_cmd_vel_bridge(
    config: dict[str, Any],
) -> tuple[str, float, float, float, float]:
    """Validate the bounded ROS Twist bridge configuration."""
    topic = str(config.get("cmd_vel_topic") or os.environ.get(
        "RBNX_MINECRAFT_CMD_VEL_TOPIC", "/cmd_vel",
    )).strip()
    token = r"[A-Za-z_][A-Za-z0-9_]*"
    if re.fullmatch(rf"/{token}(?:/{token})*", topic) is None:
        raise ValueError("cmd_vel_topic must be a fully-qualified ROS topic")
    duration = float(config.get("cmd_vel_command_duration_sec", os.environ.get(
        "RBNX_MINECRAFT_CMD_VEL_DURATION_SEC", "0.25",
    )))
    timeout = float(config.get("cmd_vel_timeout_sec", os.environ.get(
        "RBNX_MINECRAFT_CMD_VEL_TIMEOUT_SEC", "0.5",
    )))
    max_linear = float(config.get("cmd_vel_max_linear", os.environ.get(
        "RBNX_MINECRAFT_CMD_VEL_MAX_LINEAR", "1.2",
    )))
    max_angular = float(config.get("cmd_vel_max_angular", os.environ.get(
        "RBNX_MINECRAFT_CMD_VEL_MAX_ANGULAR", "1.0",
    )))
    values = (duration, timeout, max_linear, max_angular)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("cmd_vel bridge limits must be finite")
    if not 0.05 <= duration <= 1.0:
        raise ValueError("cmd_vel_command_duration_sec must be within [0.05, 1]")
    if not duration <= timeout <= 5.0:
        raise ValueError("cmd_vel_timeout_sec must be from command duration through 5")
    if not 0.0 < max_linear <= 4.317:
        raise ValueError("cmd_vel_max_linear must be within (0, 4.317]")
    if not 0.0 < max_angular <= math.pi:
        raise ValueError("cmd_vel_max_angular must be within (0, pi]")
    return topic, duration, timeout, max_linear, max_angular


def cmd_vel_message_values(message: Any) -> tuple[float, float, float]:
    """Validate a planar Nav2 Twist and return Minecraft-relative velocities."""
    linear = message.linear
    angular = message.angular
    values = tuple(float(value) for value in (
        linear.x,
        linear.y,
        linear.z,
        angular.x,
        angular.y,
        angular.z,
    ))
    if not all(math.isfinite(value) for value in values):
        raise ValueError("cmd_vel fields must be finite")
    linear_x, linear_y, linear_z, angular_x, angular_y, angular_z = values
    if any(abs(value) > 1e-9 for value in (linear_z, angular_x, angular_y)):
        raise ValueError("cmd_vel supports only planar linear x/y and angular z")
    if math.hypot(linear_x, linear_y) > cmd_vel_max_linear:
        raise ValueError("cmd_vel planar velocity exceeds configured limit")
    if abs(angular_z) > cmd_vel_max_angular:
        raise ValueError("cmd_vel angular velocity exceeds configured limit")
    return linear_x, linear_y, angular_z


def cmd_vel_payload(
    linear_x: float,
    linear_y: float,
    angular_z: float,
    duration_sec: float,
) -> dict[str, Any]:
    """Translate one validated planar Twist sample into the Mod protocol."""
    if linear_x == 0.0 and linear_y == 0.0 and angular_z == 0.0:
        return {"cmd": "stop"}
    return {
        "cmd": "move",
        "linear_x": linear_x,
        "linear_y": linear_y,
        "yaw_degrees": math.degrees(angular_z * duration_sec),
        "pitch_degrees": 0.0,
        "duration_ms": round(duration_sec * 1000),
    }


def receive_cmd_vel(message: Any) -> None:
    """Retain only the newest safe Nav2 velocity command for the worker."""
    global latest_cmd_vel
    try:
        linear_x, linear_y, angular_z = cmd_vel_message_values(message)
    except Exception as exc:  # noqa: BLE001
        log_stream_error("cmd_vel", exc)
        linear_x = linear_y = angular_z = 0.0
    with cmd_vel_state_lock:
        latest_cmd_vel = (linear_x, linear_y, angular_z, time.monotonic())


def cmd_vel_bridge_loop() -> None:
    """Execute short Twist chunks and stop when Nav2 commands become stale."""
    active = False
    while not cmd_vel_bridge_stop.is_set():
        with cmd_vel_state_lock:
            linear_x, linear_y, angular_z, received_at = latest_cmd_vel
        if received_at <= 0.0 or time.monotonic() - received_at > cmd_vel_timeout_sec:
            linear_x = linear_y = angular_z = 0.0
        moving = any(value != 0.0 for value in (linear_x, linear_y, angular_z))
        if not moving and not active:
            cmd_vel_bridge_stop.wait(0.05)
            continue
        try:
            client.request(cmd_vel_payload(
                linear_x,
                linear_y,
                angular_z,
                cmd_vel_command_duration_sec,
            ))
            active = moving
        except Exception as exc:  # noqa: BLE001
            if not cmd_vel_bridge_stop.is_set():
                log_stream_error("cmd_vel", exc)
            active = False
    try:
        client.request({"cmd": "stop"})
    except Exception as exc:  # noqa: BLE001
        log_stream_error("cmd_vel stop", exc)


def log_stream_error(stream: str, error: Exception) -> None:
    """Print changed stream errors immediately and repeated errors at most every five seconds."""
    message = str(error)
    now = time.monotonic()
    with stream_error_lock:
        previous_message, previous_time = last_stream_errors.get(stream, ("", 0.0))
        if message == previous_message and now - previous_time < 5.0:
            return
        last_stream_errors[stream] = (message, now)
    print(f"[minecraft-mod] {stream} stream publish failed: {message}")


def camera_stream_loop() -> None:
    """Poll the Mod at the configured rate and publish each available camera stream."""
    interval = 1.0 / camera_stream_hz
    while not camera_stream_stop.is_set():
        started = time.monotonic()
        try:
            rgb = rendered_view_to_rgb_image(client.request({"cmd": "snapshot"}))
            publish_ros_image(rgb_publisher, rgb)
        except Exception as exc:  # noqa: BLE001
            log_stream_error("RGB", exc)
        if camera_stream_stop.is_set():
            break
        try:
            depth_result = client.request({"cmd": "depth_snapshot"})
            depth = depth_view_to_image(depth_result)
            depth_view = depth_result.get("depthView") or {}
            publish_intrinsics(
                depth.width,
                depth.height,
                float(depth_view.get("verticalFovDeg") or 0.0),
                depth.header.stamp,
            )
            publish_ros_image(depth_publisher, depth)
        except Exception as exc:  # noqa: BLE001
            log_stream_error("depth", exc)
        camera_stream_stop.wait(max(0.0, interval - (time.monotonic() - started)))


def odom_stream_loop() -> None:
    """Poll structured observations and publish the current player view pose."""
    interval = 1.0 / odom_stream_hz
    while not odom_stream_stop.is_set():
        started = time.monotonic()
        try:
            observation = client.request({"cmd": "observe"})
        except Exception as exc:  # noqa: BLE001
            log_stream_error("observation", exc)
            odom_stream_stop.wait(max(0.0, interval - (time.monotonic() - started)))
            continue
        try:
            publish_odom(observation, observation_timestamp(observation))
        except Exception as exc:  # noqa: BLE001
            log_stream_error("odom", exc)
        try:
            publish_semantic_objects(observation)
        except Exception as exc:  # noqa: BLE001
            log_stream_error("objects", exc)
        odom_stream_stop.wait(max(0.0, interval - (time.monotonic() - started)))


def lidar_stream_loop() -> None:
    """Poll exact collision rays independently from the rendered camera streams."""
    interval = 1.0 / lidar_stream_hz
    while not lidar_stream_stop.is_set():
        started = time.monotonic()
        try:
            result = client.request({
                "cmd": "lidar_scan",
                "ray_count": lidar_ray_count,
                "range_max": lidar_range_max,
            })
            publish_lidar_scan(result)
        except Exception as exc:  # noqa: BLE001
            log_stream_error("lidar", exc)
        lidar_stream_stop.wait(max(0.0, interval - (time.monotonic() - started)))


def start_camera_stream() -> None:
    """Start one camera publisher worker unless it is already alive."""
    global camera_stream_thread
    with camera_worker_lock:
        if camera_stream_thread is not None and camera_stream_thread.is_alive():
            return
        camera_stream_stop.clear()
        camera_stream_thread = threading.Thread(
            target=camera_stream_loop,
            name="minecraft-camera-stream",
            daemon=True,
        )
        camera_stream_thread.start()


def start_odom_stream() -> None:
    """Start one odometry publisher worker with fresh velocity state."""
    global odom_stream_thread, previous_odom_sample
    with odom_worker_lock:
        if odom_stream_thread is not None and odom_stream_thread.is_alive():
            return
        previous_odom_sample = None
        odom_stream_stop.clear()
        odom_stream_thread = threading.Thread(
            target=odom_stream_loop,
            name="minecraft-odom-stream",
            daemon=True,
        )
        odom_stream_thread.start()


def start_lidar_stream() -> None:
    """Start one collision-scan publisher worker unless it is already alive."""
    global lidar_stream_thread
    with lidar_worker_lock:
        if lidar_stream_thread is not None and lidar_stream_thread.is_alive():
            return
        lidar_stream_stop.clear()
        lidar_stream_thread = threading.Thread(
            target=lidar_stream_loop,
            name="minecraft-lidar-stream",
            daemon=True,
        )
        lidar_stream_thread.start()


def start_cmd_vel_bridge() -> None:
    """Start one latest-command Twist worker with an initially stopped state."""
    global cmd_vel_bridge_thread, latest_cmd_vel
    with cmd_vel_worker_lock:
        if cmd_vel_bridge_thread is not None and cmd_vel_bridge_thread.is_alive():
            return
        with cmd_vel_state_lock:
            latest_cmd_vel = (0.0, 0.0, 0.0, 0.0)
        cmd_vel_bridge_stop.clear()
        cmd_vel_bridge_thread = threading.Thread(
            target=cmd_vel_bridge_loop,
            name="minecraft-cmd-vel-bridge",
            daemon=True,
        )
        cmd_vel_bridge_thread.start()


def stop_camera_stream() -> bool:
    """Signal the RGB/depth worker, join its current request, and report shutdown."""
    with camera_worker_lock:
        camera_stream_stop.set()
        worker = camera_stream_thread
        if worker is None or worker is threading.current_thread():
            return True
        worker.join(timeout=client.timeout + 1.0)
        return not worker.is_alive()


def stop_odom_stream() -> bool:
    """Signal the odometry worker, join its current request, and report shutdown."""
    with odom_worker_lock:
        odom_stream_stop.set()
        worker = odom_stream_thread
        if worker is None or worker is threading.current_thread():
            return True
        worker.join(timeout=client.timeout + 1.0)
        return not worker.is_alive()


def stop_lidar_stream() -> bool:
    """Signal the LiDAR worker, join its current request, and report shutdown."""
    with lidar_worker_lock:
        lidar_stream_stop.set()
        worker = lidar_stream_thread
        if worker is None or worker is threading.current_thread():
            return True
        worker.join(timeout=client.timeout + 1.0)
        return not worker.is_alive()


def stop_cmd_vel_bridge() -> bool:
    """Stop Nav2 motion immediately and join the Twist worker."""
    with cmd_vel_worker_lock:
        cmd_vel_bridge_stop.set()
        worker = cmd_vel_bridge_thread
        try:
            client.request({"cmd": "stop"})
        except Exception as exc:  # noqa: BLE001
            log_stream_error("cmd_vel stop", exc)
        if worker is None or worker is threading.current_thread():
            return True
        worker.join(timeout=client.timeout + 1.0)
        return not worker.is_alive()


REGISTRY_NAME = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ACTION_REQUEST_TIMEOUT_SEC = 30.0
BREAKABLE_BLOCKS = frozenset(("minecraft:dirt", "minecraft:crafting_table"))
# Recovery calls are planned in separate Pilot rounds.  Keep the opaque
# inspection handle long enough for that round-trip (including history
# compaction and model latency), while still expiring it promptly if a caller
# abandons recovery.
ROUTE_OBSTACLE_HANDLE_TTL_SEC = 90.0


def exact_int(value: Any, name: str, minimum: int, maximum: int) -> int:
    """Require a real integer in the contract's closed numeric range."""
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an integer from {minimum} through {maximum}")
    return value


def registry_name(value: Any, name: str) -> str:
    """Require one lowercase namespaced Minecraft registry name."""
    if not isinstance(value, str) or REGISTRY_NAME.fullmatch(value) is None:
        raise ValueError(f"{name} must be an exact namespaced Minecraft registry name")
    return value


def action_request(payload: dict[str, Any]) -> dict[str, Any]:
    """Send an item action with a socket deadline longer than its Mod deadline."""
    return client.request(payload, timeout_sec=max(client.timeout, ACTION_REQUEST_TIMEOUT_SEC))


@minecraft_actions.mcp("robonix/primitive/minecraft/inventory")
def inventory_mcp(
    req: minecraft_mcp.Inventory_Request,
) -> minecraft_mcp.Inventory_Response:
    """Inspect slots 0-35 and offhand slot 40 without changing inventory."""
    _ = req
    result = action_request({"cmd": "inventory"})
    selected = exact_int(result.get("selected_slot"), "selected_slot", 0, 8)
    raw_slots = result.get("slots")
    if not isinstance(raw_slots, list) or len(raw_slots) != 37:
        raise RuntimeError("Minecraft Mod inventory must contain slots 0-35 and 40")
    slots = []
    expected_slots = [*range(36), 40]
    for expected, raw in zip(expected_slots, raw_slots, strict=True):
        if not isinstance(raw, dict):
            raise RuntimeError("Minecraft Mod inventory slot is not an object")
        slot = exact_int(raw.get("slot"), "slot", 0, 40)
        if slot != expected:
            raise RuntimeError(f"Minecraft Mod inventory returned unexpected slot {slot}")
        item = registry_name(raw.get("item"), "item")
        count = exact_int(raw.get("count"), "count", 0, 4_294_967_295)
        if (item == "minecraft:air") != (count == 0):
            raise RuntimeError("Minecraft Mod inventory empty slot is inconsistent")
        slots.append(minecraft_mcp.InventorySlot(slot=slot, item=item, count=count))
    return minecraft_mcp.Inventory_Response(selected_slot=selected, slots=slots)


@minecraft_actions.mcp("robonix/primitive/minecraft/select_inventory_item")
def select_inventory_item_mcp(
    req: minecraft_mcp.SelectInventoryItem_Request,
) -> minecraft_mcp.SelectInventoryItem_Response:
    """Select one exact main-inventory item into the live main hand."""
    item = registry_name(req.item, "item")
    result = action_request({"cmd": "select_inventory_item", "item": item})
    selected = result.get("selected")
    actual_item = registry_name(result.get("item"), "item")
    source_slot = exact_int(result.get("source_slot"), "source_slot", 0, 35)
    selected_slot = exact_int(result.get("selected_slot"), "selected_slot", 0, 8)
    selected_count = exact_int(
        result.get("selected_count"), "selected_count", 1, 4_294_967_295,
    )
    moved_to_hotbar = result.get("moved_to_hotbar")
    if selected is not True or actual_item != item:
        raise RuntimeError("Minecraft Mod did not verify the selected main-hand item")
    if not isinstance(moved_to_hotbar, bool):
        raise RuntimeError("Minecraft Mod returned invalid hotbar movement state")
    if moved_to_hotbar != (source_slot >= 9):
        raise RuntimeError("Minecraft Mod returned inconsistent hotbar movement state")
    return minecraft_mcp.SelectInventoryItem_Response(
        selected=True,
        item=actual_item,
        source_slot=source_slot,
        selected_slot=selected_slot,
        selected_count=selected_count,
        moved_to_hotbar=moved_to_hotbar,
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/get_state")
def get_state_mcp(
    req: minecraft_mcp.GetState_Request,
) -> minecraft_mcp.GetState_Response:
    """Return a fresh timestamped Minecraft-world Bot/player state snapshot.

    Every position in this response belongs to the Bot/player, not to any
    chest, cauldron, block, or other interaction target. MUST NOT copy or adapt
    these coordinates into aim_at_block, fill_water_bottles, withdraw_item, or
    deposit_item. For a physical-object target, MUST call
    ``scene_list_objects`` and copy that object's stable ID unchanged. Never
    infer a target from the Bot's position.
    """
    _ = req
    observation = client.request({"cmd": "observe"})
    captured_at = str(observation.get("timestamp") or "")
    observation_timestamp(observation)
    coordinate_frame = str(observation.get("coordinateFrame") or "")
    dimension = registry_name(observation.get("dimension"), "dimension")
    game_mode = str(observation.get("gameMode") or "")
    position = observation.get("position") or {}
    values = {
        name: float(value)
        for name, value in (
            ("x", position.get("x")),
            ("y", position.get("y")),
            ("z", position.get("z")),
            ("yaw_rad", observation.get("yaw")),
            ("pitch_rad", observation.get("pitch")),
            ("health", observation.get("health")),
        )
    }
    if coordinate_frame != "minecraft_world":
        raise RuntimeError("Minecraft Mod returned an unexpected state coordinate frame")
    if not all(math.isfinite(value) for value in values.values()):
        raise RuntimeError("Minecraft Mod returned non-finite player state")
    if not isinstance(observation.get("onGround"), bool):
        raise RuntimeError("Minecraft Mod returned invalid on-ground state")
    food = exact_int(observation.get("food"), "food", 0, 4_294_967_295)
    if not game_mode:
        raise RuntimeError("Minecraft Mod returned an empty game mode")
    navigation_x, navigation_y, _, navigation_yaw, _ = observation_pose(observation)
    return minecraft_mcp.GetState_Response(
        captured_at=captured_at,
        coordinate_frame=coordinate_frame,
        dimension=dimension,
        x=values["x"],
        y=values["y"],
        z=values["z"],
        block_x=math.floor(values["x"]),
        block_y=math.floor(values["y"]),
        block_z=math.floor(values["z"]),
        yaw_rad=values["yaw_rad"],
        pitch_rad=values["pitch_rad"],
        navigation_frame=odom_frame_id,
        navigation_x=navigation_x,
        navigation_y=navigation_y,
        navigation_yaw_rad=navigation_yaw,
        on_ground=observation["onGround"],
        health=values["health"],
        food=food,
        game_mode=game_mode,
    )


def native_block_pos(object_id: Any) -> tuple[int, int, int]:
    """Resolve a Scene-preserved structured object ID to native BlockPos."""
    target_id = str(object_id or "").strip()
    if not target_id:
        raise ValueError("object_id must be non-empty")
    with semantic_blocks_lock:
        position = semantic_block_positions.get(target_id)
    if position is None:
        raise ValueError(
            f"unknown or stale Minecraft semantic object_id: {target_id}; "
            "call scene/list_objects again"
        )
    return position


def native_entity_target(object_id: Any, expected_entity: Any) -> tuple[str, str]:
    """Resolve one stable Scene object ID to a live native entity UUID."""
    target_id = str(object_id or "").strip()
    expected = registry_name(expected_entity, "expected_entity")
    if not target_id:
        raise ValueError("object_id must be non-empty")
    with semantic_blocks_lock:
        target = semantic_entity_targets.get(target_id)
    if target is None:
        raise ValueError(
            f"unknown or stale Minecraft semantic entity object_id: {target_id}; "
            "call scene/list_objects again"
        )
    entity_uuid, actual = target
    if actual != expected:
        raise ValueError(f"target entity is {actual}, not {expected}")
    return entity_uuid, actual


def _prune_route_obstacles(now: float) -> None:
    """Remove expired native targets while the caller holds the handle lock."""
    expired = [
        handle
        for handle, obstacle in route_obstacles.items()
        if obstacle.expires_monotonic <= now
    ]
    for handle in expired:
        route_obstacles.pop(handle, None)
    expired_paths = [
        handle
        for handle, inspection in route_path_inspections.items()
        if inspection.expires_monotonic <= now
    ]
    for handle in expired_paths:
        route_path_inspections.pop(handle, None)


def inspect_route_obstacle_mcp(
    req: minecraft_mcp.InspectRouteObstacle_Request,
) -> minecraft_mcp.InspectRouteObstacle_Response:
    """Inspect the short forward corridor and retain native coordinates locally."""
    recovery_token = str(req.recovery_token or "").strip()
    if not recovery_token or len(recovery_token) > 128:
        raise ValueError("recovery_token must contain from 1 through 128 characters")
    distance = float(req.max_distance_m)
    if not math.isfinite(distance) or not 0.5 <= distance <= 4.5:
        raise ValueError("max_distance_m must be from 0.5 through 4.5")
    result = action_request({
        "cmd": "inspect_route_obstacle",
        "max_distance": distance,
    })
    found = result.get("found")
    if not isinstance(found, bool):
        raise RuntimeError("Minecraft Mod returned invalid obstacle presence")
    if not found:
        return minecraft_mcp.InspectRouteObstacle_Response(
            found=False,
            obstacle_handle="",
            block_name="",
            clearable=False,
            detail="no collidable block found in the short forward corridor",
        )
    block_name = registry_name(result.get("block_name"), "block_name")
    position = (
        exact_int(result.get("x"), "x", -30_000_000, 30_000_000),
        exact_int(result.get("y"), "y", -2048, 2048),
        exact_int(result.get("z"), "z", -30_000_000, 30_000_000),
    )
    handle = str(uuid.uuid4())
    now = time.monotonic()
    with route_obstacles_lock:
        _prune_route_obstacles(now)
        route_obstacles[handle] = RouteObstacle(
            block_name=block_name,
            position=position,
            recovery_token=recovery_token,
            expires_monotonic=now + ROUTE_OBSTACLE_HANDLE_TTL_SEC,
        )
    clearable = block_name in BREAKABLE_BLOCKS
    return minecraft_mcp.InspectRouteObstacle_Response(
        found=True,
        obstacle_handle=handle,
        block_name=block_name,
        clearable=clearable,
        detail=(
            "one allowlisted route obstacle can be cleared"
            if clearable
            else "the route obstacle is not on the clear allowlist"
        ),
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/inspect_route_path")
def inspect_route_path_mcp(
    req: minecraft_mcp.InspectRoutePath_Request,
) -> minecraft_mcp.InspectRoutePath_Response:
    """Inspect a footprint-width minecraft_odom path without mutating blocks."""
    recovery_token = str(req.recovery_token or "").strip()
    if not recovery_token or len(recovery_token) > 128:
        raise ValueError("recovery_token must contain from 1 through 128 characters")
    navigation_x = [float(value) for value in req.navigation_x]
    navigation_y = [float(value) for value in req.navigation_y]
    if not 1 <= len(navigation_x) <= 256 or len(navigation_x) != len(navigation_y):
        raise ValueError("path must contain from 1 through 256 paired points")
    if not all(math.isfinite(value) for value in navigation_x + navigation_y):
        raise ValueError("path coordinates must be finite")
    body_y = exact_int(int(req.body_y), "body_y", -2048, 2048)
    half_width = float(req.half_width_m)
    sample_spacing = float(req.sample_spacing_m)
    max_blocks = exact_int(int(req.max_blocks), "max_blocks", 1, 256)
    if not math.isfinite(half_width) or not 0.30 <= half_width <= 1.5:
        raise ValueError("half_width_m must be from 0.30 through 1.5")
    if not math.isfinite(sample_spacing) or not 0.1 <= sample_spacing <= 1.0:
        raise ValueError("sample_spacing_m must be from 0.1 through 1.0")
    result = action_request({
        "cmd": "inspect_route_path",
        "navigation_x": navigation_x,
        "navigation_y": navigation_y,
        "body_y": body_y,
        "half_width": half_width,
        "sample_spacing": sample_spacing,
        "max_blocks": max_blocks,
    })
    dirt_blocks = result.get("dirt_blocks")
    other_count = exact_int(result.get("other_count"), "other_count", 0, max_blocks)
    if not isinstance(dirt_blocks, list):
        raise RuntimeError("Minecraft Mod returned invalid dirt_blocks")
    dirt_positions: list[tuple[int, int, int]] = []
    seen: set[tuple[int, int, int]] = set()
    for raw in dirt_blocks:
        if not isinstance(raw, dict) or registry_name(raw.get("block_name"), "block_name") != "minecraft:dirt":
            raise RuntimeError("Minecraft Mod returned a non-dirt route candidate")
        position = (
            exact_int(raw.get("x"), "x", -30_000_000, 30_000_000),
            exact_int(raw.get("y"), "y", -2048, 2048),
            exact_int(raw.get("z"), "z", -30_000_000, 30_000_000),
        )
        if position not in seen:
            seen.add(position)
            dirt_positions.append(position)
    if len(dirt_positions) > max_blocks:
        raise RuntimeError("Minecraft Mod returned too many dirt route candidates")
    found = result.get("found")
    if not isinstance(found, bool):
        raise RuntimeError("Minecraft Mod returned invalid route inspection presence")
    handle = str(uuid.uuid4()) if found else ""
    if handle:
        now = time.monotonic()
        with route_obstacles_lock:
            _prune_route_obstacles(now)
            route_path_inspections[handle] = RoutePathInspection(
                dirt_positions=tuple(dirt_positions),
                other_count=other_count,
                recovery_token=recovery_token,
                expires_monotonic=now + ROUTE_OBSTACLE_HANDLE_TTL_SEC,
            )
    return minecraft_mcp.InspectRoutePath_Response(
        found=found,
        inspection_handle=handle,
        dirt_count=len(dirt_positions),
        other_count=other_count,
        detail=(
            f"found {len(dirt_positions)} dirt blocks in the footprint corridor"
            if dirt_positions else "no dirt block occupies the footprint corridor"
        ),
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/clear_route_path")
def clear_route_path_mcp(
    req: minecraft_mcp.ClearRoutePath_Request,
) -> minecraft_mcp.ClearRoutePath_Response:
    """Consume one path handle and clear only its revalidated dirt targets."""
    handle = str(req.inspection_handle or "").strip()
    if not handle:
        raise ValueError("inspection_handle must be non-empty")
    max_blocks = exact_int(int(req.max_blocks), "max_blocks", 1, 256)
    now = time.monotonic()
    with route_obstacles_lock:
        _prune_route_obstacles(now)
        inspection = route_path_inspections.pop(handle, None)
    if inspection is None:
        raise ValueError("unknown or expired inspection_handle; inspect the route again")
    if len(inspection.dirt_positions) > max_blocks:
        raise ValueError("inspection contains more blocks than max_blocks")
    cleared = 0
    for x, y, z in inspection.dirt_positions:
        aimed = action_request({
            "cmd": "aim_at_block",
            "x": x,
            "y": y,
            "z": z,
            "expected_block": "minecraft:dirt",
        })
        if aimed.get("aimed") is not True or aimed.get("actual_block") != "minecraft:dirt":
            raise RuntimeError(f"route dirt target changed before clearing at {x},{y},{z}")
        broken = action_request({
            "cmd": "break_block",
            "expected_block": "minecraft:dirt",
            "collect": False,
        })
        if broken.get("block_broken") is not True or broken.get("original_block") != "minecraft:dirt":
            raise RuntimeError(f"Minecraft Mod did not verify route dirt destruction at {x},{y},{z}")
        cleared += 1
    return minecraft_mcp.ClearRoutePath_Response(
        cleared=cleared == len(inspection.dirt_positions),
        cleared_count=cleared,
        remaining_count=len(inspection.dirt_positions) - cleared,
        other_count=inspection.other_count,
        detail=(
            f"cleared {cleared} verified dirt blocks; "
            f"{inspection.other_count} non-dirt obstacles remain unchanged"
        ),
    )


def clear_route_obstacle_mcp(
    req: minecraft_mcp.ClearRouteObstacle_Request,
) -> minecraft_mcp.ClearRouteObstacle_Response:
    """Consume one handle, re-aim at its live block, and clear exactly that block."""
    handle = str(req.obstacle_handle or "").strip()
    if not handle:
        raise ValueError("obstacle_handle must be non-empty")
    now = time.monotonic()
    with route_obstacles_lock:
        _prune_route_obstacles(now)
        obstacle = route_obstacles.pop(handle, None)
    if obstacle is None:
        raise ValueError("unknown or expired obstacle_handle; inspect the route obstacle again")
    if obstacle.block_name not in BREAKABLE_BLOCKS:
        raise ValueError("the inspected obstacle is not on the clear allowlist")
    x, y, z = obstacle.position
    aimed = action_request({
        "cmd": "aim_at_block",
        "x": x,
        "y": y,
        "z": z,
        "expected_block": obstacle.block_name,
    })
    if aimed.get("aimed") is not True or aimed.get("actual_block") != obstacle.block_name:
        raise RuntimeError("Minecraft Mod did not revalidate the inspected route obstacle")
    broken = action_request({
        "cmd": "break_block",
        "expected_block": obstacle.block_name,
        "collect": False,
    })
    if broken.get("block_broken") is not True or broken.get("original_block") != obstacle.block_name:
        raise RuntimeError("Minecraft Mod did not verify route obstacle destruction")
    return minecraft_mcp.ClearRouteObstacle_Response(
        cleared=True,
        block_name=obstacle.block_name,
        detail="cleared one verified route obstacle",
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/break_block")
def break_block_mcp(
    req: minecraft_mcp.BreakBlock_Request,
) -> minecraft_mcp.BreakBlock_Response:
    """Break the crosshair-targeted supported block and optionally verify pickup."""
    expected = registry_name(req.expected_block, "expected_block")
    if expected not in BREAKABLE_BLOCKS:
        raise ValueError("expected_block must be minecraft:dirt or minecraft:crafting_table")
    if not isinstance(req.collect, bool):
        raise ValueError("collect must be a boolean")
    result = action_request({
        "cmd": "break_block",
        "expected_block": expected,
        "collect": req.collect,
    })
    broken = result.get("block_broken")
    original = registry_name(result.get("original_block"), "original_block")
    collected_item = result.get("collected_item")
    collected_count = exact_int(result.get("collected_count"), "collected_count", 0, 4_294_967_295)
    if broken is not True or original != expected:
        raise RuntimeError("Minecraft Mod did not verify the requested block destruction")
    if collected_count > 0:
        registry_name(collected_item, "collected_item")
        if collected_item != expected:
            raise RuntimeError("Minecraft Mod reported an unexpected collected item")
    elif collected_item != "":
        raise RuntimeError("Minecraft Mod reported an item without a collected count")
    if req.collect and collected_count < 1:
        raise RuntimeError(f"Minecraft Mod did not verify {expected} pickup")
    return minecraft_mcp.BreakBlock_Response(
        block_broken=True,
        original_block=original,
        collected_item=collected_item,
        collected_count=collected_count,
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/attack_entity")
def attack_entity_mcp(
    req: minecraft_mcp.AttackEntity_Request,
) -> minecraft_mcp.AttackEntity_Response:
    """Kill one Scene-selected zombie through a bounded native combat loop.

    MUST call ``scene_list_objects`` first and copy the selected
    ``minecraft:zombie`` object's stable ``id`` unchanged into ``object_id``.
    The primitive resolves that identity to a live Entity UUID; Scene
    coordinates, camera estimates, and navigation poses are never targets.
    """
    entity_uuid, expected = native_entity_target(req.object_id, req.expected_entity)
    if expected != "minecraft:zombie":
        raise ValueError("expected_entity must be minecraft:zombie")
    result = action_request({
        "cmd": "attack_entity",
        "entity_uuid": entity_uuid,
        "expected_entity": expected,
    })
    killed = result.get("killed")
    actual = registry_name(result.get("actual_entity"), "actual_entity")
    attacks_attempted = exact_int(
        result.get("attacks_attempted"), "attacks_attempted", 0, 4_294_967_295,
    )
    attacks_landed = exact_int(
        result.get("attacks_landed"), "attacks_landed", 0, attacks_attempted,
    )
    health_before = float(result.get("bot_health_before"))
    health_after = float(result.get("bot_health_after"))
    if killed is not True or actual != expected:
        raise RuntimeError("Minecraft Mod did not verify the requested entity kill")
    if not all(math.isfinite(value) for value in (health_before, health_after)):
        raise RuntimeError("Minecraft Mod returned non-finite combat health")
    if health_before < 0.0 or health_after < 0.0:
        raise RuntimeError("Minecraft Mod returned negative combat health")
    return minecraft_mcp.AttackEntity_Response(
        killed=True,
        object_id=str(req.object_id),
        actual_entity=actual,
        attacks_attempted=attacks_attempted,
        attacks_landed=attacks_landed,
        bot_health_before=health_before,
        bot_health_after=health_after,
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/aim_at_block")
def aim_at_block_mcp(
    req: minecraft_mcp.AimAtBlockV2_Request,
) -> minecraft_mcp.AimAtBlockV2_Response:
    """Gradually aim the live crosshair at one visible reachable block.

    Before calling, MUST call ``scene_list_objects``, select the intended
    physical object, and copy its stable ``id`` unchanged into ``object_id``.
    A camera image, remembered coordinates, and ``minecraft_get_state`` cannot
    supply this ID. The primitive resolves it to native BlockPos internally.
    ``expected_block`` is the exact namespaced registry name. This capability
    only turns the view and never attacks or uses the target. Wait until
    navigation is terminal before calling it.
    """
    x, y, z = native_block_pos(req.object_id)
    expected = registry_name(req.expected_block, "expected_block")
    result = action_request({
        "cmd": "aim_at_block",
        "x": x,
        "y": y,
        "z": z,
        "expected_block": expected,
    })
    aimed = result.get("aimed")
    actual = registry_name(result.get("actual_block"), "actual_block")
    hit_face = result.get("hit_face")
    if aimed is not True or actual != expected:
        raise RuntimeError("Minecraft Mod did not verify the requested crosshair target")
    if hit_face not in {"down", "up", "north", "south", "west", "east"}:
        raise RuntimeError("Minecraft Mod returned an invalid block hit face")
    final_yaw = float(result.get("final_yaw_deg"))
    final_pitch = float(result.get("final_pitch_deg"))
    if not math.isfinite(final_yaw) or not math.isfinite(final_pitch):
        raise RuntimeError("Minecraft Mod returned non-finite final view angles")
    if not -90.0 <= final_pitch <= 90.0:
        raise RuntimeError("Minecraft Mod returned an invalid final pitch")
    return minecraft_mcp.AimAtBlockV2_Response(
        aimed=True,
        actual_block=actual,
        hit_face=hit_face,
        final_yaw_deg=final_yaw,
        final_pitch_deg=final_pitch,
    )


@minecraft_actions.mcp("robonix/primitive/minecraft/deposit_item")
def deposit_item_mcp(
    req: minecraft_mcp.DepositItemV2_Request,
) -> minecraft_mcp.DepositItemV2_Response:
    """Deposit an exact positive quantity of any registered item into a chest.

    Before calling, MUST call ``scene_list_objects``, select the intended
    chest, and copy its stable ``id`` unchanged into ``object_id``. Camera
    images, remembered coordinates, and ``minecraft_get_state`` cannot supply
    this ID. If Scene has no matching chest, stop instead of inventing one.
    """
    item = registry_name(req.item, "item")
    count = exact_int(req.count, "count", 1, 2_147_483_647)
    x, y, z = native_block_pos(req.object_id)
    result = action_request({
        "cmd": "deposit_item",
        "x": x,
        "y": y,
        "z": z,
        "item": item,
        "count": count,
    })
    fields = {
        name: exact_int(result.get(name), name, 0, 4_294_967_295)
        for name in (
            "transferred_count",
            "player_count_before",
            "player_count_after",
            "container_count_before",
            "container_count_after",
        )
    }
    if fields["transferred_count"] != count:
        raise RuntimeError("Minecraft Mod did not verify the requested transferred item count")
    if fields["player_count_after"] != fields["player_count_before"] - count:
        raise RuntimeError("Minecraft Mod player inventory counts are inconsistent")
    if fields["container_count_after"] != fields["container_count_before"] + count:
        raise RuntimeError("Minecraft Mod container counts are inconsistent")
    return minecraft_mcp.DepositItemV2_Response(**fields)


@minecraft_actions.mcp("robonix/primitive/minecraft/withdraw_item")
def withdraw_item_mcp(
    req: minecraft_mcp.WithdrawItemV2_Request,
) -> minecraft_mcp.WithdrawItemV2_Response:
    """Withdraw an exact positive quantity of one registry item from a chest.

    Before calling, MUST call ``scene_list_objects``, select the intended
    chest, and copy its stable ``id`` unchanged into ``object_id``. Camera
    images, remembered coordinates, and ``minecraft_get_state`` cannot supply
    this ID. If Scene has no matching chest, stop instead of inventing one.
    """
    item = registry_name(req.item, "item")
    count = exact_int(req.count, "count", 1, 4_294_967_295)
    x, y, z = native_block_pos(req.object_id)
    result = action_request({
        "cmd": "withdraw_item",
        "x": x,
        "y": y,
        "z": z,
        "item": item,
        "count": count,
    })
    fields = {
        name: exact_int(result.get(name), name, 0, 4_294_967_295)
        for name in (
            "transferred_count",
            "player_count_before",
            "player_count_after",
            "container_count_before",
            "container_count_after",
        )
    }
    if fields["transferred_count"] != count:
        raise RuntimeError("Minecraft Mod did not verify the requested transferred count")
    if fields["player_count_after"] != fields["player_count_before"] + count:
        raise RuntimeError("Minecraft Mod player inventory counts are inconsistent")
    if fields["container_count_after"] != fields["container_count_before"] - count:
        raise RuntimeError("Minecraft Mod container counts are inconsistent")
    return minecraft_mcp.WithdrawItemV2_Response(**fields)


@minecraft_actions.mcp("robonix/primitive/minecraft/fill_water_bottles")
def fill_water_bottles_mcp(
    req: minecraft_mcp.FillWaterBottlesV2_Request,
) -> minecraft_mcp.FillWaterBottlesV2_Response:
    """Fill an exact positive number of bottles from one water cauldron.

    Before calling, MUST call ``scene_list_objects``, select the
    ``minecraft:water_cauldron``, and copy its stable ``id`` unchanged into
    ``object_id``. A camera image, remembered coordinates, and
    ``minecraft_get_state`` cannot supply this ID. Use the same object ID for
    the preceding ``minecraft_aim_at_block`` call.
    """
    count = exact_int(req.count, "count", 1, 3)
    x, y, z = native_block_pos(req.object_id)
    result = action_request({
        "cmd": "fill_water_bottles",
        "x": x,
        "y": y,
        "z": z,
        "count": count,
    })
    fields = {
        name: exact_int(result.get(name), name, 0, 4_294_967_295)
        for name in (
            "filled_count",
            "empty_bottles_before",
            "empty_bottles_after",
            "water_bottles_before",
            "water_bottles_after",
            "cauldron_level_before",
            "cauldron_level_after",
        )
    }
    if fields["filled_count"] != count:
        raise RuntimeError("Minecraft Mod did not verify the requested filled bottle count")
    if fields["empty_bottles_after"] != fields["empty_bottles_before"] - count:
        raise RuntimeError("Minecraft Mod empty bottle counts are inconsistent")
    if fields["water_bottles_after"] != fields["water_bottles_before"] + count:
        raise RuntimeError("Minecraft Mod water bottle counts are inconsistent")
    if fields["cauldron_level_after"] != fields["cauldron_level_before"] - count:
        raise RuntimeError("Minecraft Mod cauldron levels are inconsistent")
    return minecraft_mcp.FillWaterBottlesV2_Response(**fields)


@minecraft_camera.mcp("robonix/primitive/camera/snapshot")
def snapshot(msg: Empty) -> Image:
    """Return the latest framebuffer captured by the Forge client Mod."""
    _ = msg
    try:
        return rendered_view_to_image(client.request({"cmd": "snapshot"}))
    except Exception as exc:  # noqa: BLE001
        return image_error(str(exc))


@minecraft_camera.mcp("robonix/primitive/camera/depth_snapshot")
def depth_snapshot(msg: Empty) -> Image:
    """Get first-person depth as a 32FC1 image in metres.

    Each little-endian float is forward distance from the player view; sky and
    other pixels without rendered geometry are NaN. The image dimensions match
    the configured Minecraft framebuffer capture size.
    """
    _ = msg
    try:
        return depth_view_to_image(client.request({"cmd": "depth_snapshot"}))
    except Exception as exc:  # noqa: BLE001
        return image_error(str(exc))


@minecraft_chassis.grpc("robonix/primitive/chassis/move")
def move_grpc(req: "chassis_pb2.ExecuteMoveCommand_Request") -> "chassis_pb2.ExecuteMoveCommand_Response":
    """Expose Mod movement to scripted gRPC callers."""
    status = execute_move_command(req.command)
    return chassis_pb2.ExecuteMoveCommand_Response(
        status=std_msgs_pb2.String(data=json.dumps(status)),
    )


@minecraft_chassis.mcp("robonix/primitive/chassis/move")
def move_mcp(req: chassis_mcp.ExecuteMoveCommand_Request) -> chassis_mcp.ExecuteMoveCommand_Response:
    """Control the Minecraft player with the shared chassis move fields.

    Do not call this capability while a navigation goal reports `navigating`.
    Refine the destination with another navigation goal and wait for navigation
    to become terminal before taking direct chassis control.

    Use exactly one of the following modes. Keep every field from the other
    modes zero; ambiguous mixed-mode commands are rejected.

    Distance mode: `forward_m` is a signed relative travel distance in blocks
    (one block is one metre). Positive moves forward in the current view
    direction; negative moves backward. Its absolute value must be at most 32.
    The Mod chooses the walking duration and stops after measuring the requested
    horizontal displacement. Set `rotate_deg`, all linear/angular fields, and
    `duration_sec` to zero.

    Turn mode: `rotate_deg` is a signed relative total yaw angle in degrees, not
    an angular speed. Positive turns the view left/counterclockwise; negative
    turns it right/clockwise. For example, 90 turns left by 90 degrees and -15
    turns right by 15 degrees. Set `forward_m`, all linear/angular fields, and
    `duration_sec` to zero.

    Velocity mode: `linear_x` and `linear_y` are player-relative planar speeds
    in blocks per second. Positive/negative `linear_x` moves forward/backward;
    positive/negative `linear_y` strafes left/right. Their combined magnitude
    must not exceed 4.317 blocks per second. `angular_z` is a yaw rate and
    `angular_y` is a pitch rate, both in radians per second: positive/negative
    `angular_z` looks left/right and positive/negative `angular_y` looks
    down/up. The resulting relative angles are each angular rate multiplied by
    `duration_sec`, then converted from radians. `duration_sec=0` selects one
    second; otherwise it must be 0.05 through 30 seconds. Set `forward_m` and
    `rotate_deg` to zero. Supported velocity fields may run together, but for
    visual micro-adjustment change one axis at a time and inspect a new snapshot
    before the next move.

    Minecraft does not support physical up/down movement or roll through this
    capability. `linear_z` and `angular_x` must always be zero. To stop an
    active direct movement, set every movement field and `duration_sec` to zero.
    """
    status = execute_move_command(req.command)
    return chassis_mcp.ExecuteMoveCommand_Response(
        status=std_msgs_mcp.String(data=json.dumps(status)),
    )


def execute_move_command(command: Any) -> dict[str, Any]:
    """Validate one exclusive movement mode and translate it into a Mod command."""
    try:
        distance = float(getattr(command, "forward_m", 0.0))
        rotation = float(getattr(command, "rotate_deg", 0.0))
        linear = float(getattr(command, "linear_x", 0.0))
        lateral = float(getattr(command, "linear_y", 0.0))
        vertical = float(getattr(command, "linear_z", 0.0))
        roll_rate = float(getattr(command, "angular_x", 0.0))
        pitch_rate = float(getattr(command, "angular_y", 0.0))
        yaw_rate = float(getattr(command, "angular_z", 0.0))
        duration_requested = float(getattr(command, "duration_sec", 0.0))
        values = (
            distance, rotation, linear, lateral, vertical, roll_rate,
            pitch_rate, yaw_rate, duration_requested,
        )
        if not all(math.isfinite(value) for value in values):
            raise ValueError("move fields must be finite")
        if vertical != 0.0 or roll_rate != 0.0:
            raise ValueError("linear_z and angular_x are unsupported and must be zero")
        velocity_requested = any(value != 0.0 for value in (linear, lateral, pitch_rate, yaw_rate))
        if distance != 0.0:
            if rotation != 0.0 or velocity_requested or duration_requested != 0.0:
                raise ValueError("forward_m cannot be combined with turn or velocity mode fields")
            direction = "forward" if distance > 0 else "back"
            observation = client.request({"cmd": "move", "direction": direction, "distance": abs(distance)})
        elif rotation != 0.0:
            if velocity_requested or duration_requested != 0.0:
                raise ValueError("rotate_deg cannot be combined with velocity mode fields")
            observation = client.request({"cmd": "turn", "degrees": rotation})
        else:
            duration_sec = duration_requested or 1.0
            if not math.isfinite(duration_sec) or duration_sec < 0.05 or duration_sec > 30.0:
                raise ValueError("duration_sec must be within [0.05, 30] seconds")
            if linear == 0.0 and lateral == 0.0 and pitch_rate == 0.0 and yaw_rate == 0.0:
                observation = client.request({"cmd": "stop"})
            else:
                observation = client.request({
                    "cmd": "move",
                    "linear_x": linear,
                    "linear_y": lateral,
                    "yaw_degrees": math.degrees(yaw_rate * duration_sec),
                    "pitch_degrees": math.degrees(pitch_rate * duration_sec),
                    "duration_ms": round(duration_sec * 1000),
                })
        persist_observation(observation)
        return {"status": "done", "result": observation}
    except Exception as exc:  # noqa: BLE001
        return {"error": str(exc)}


def init_mod(config: dict[str, Any]):
    """Wait for the separately launched Forge client to join a world."""
    timeout_ms = int(config.get("ready_timeout_ms") or os.environ.get(
        "RBNX_MINECRAFT_MOD_READY_TIMEOUT_MS", "30000",
    ))
    try:
        persist_observation(client.wait_ready(timeout_ms))
    except Exception as exc:  # noqa: BLE001
        return Err(str(exc))
    return Ok()


def initialize_camera(config: dict[str, Any]):
    """Initialize camera streams and the colocated chassis after Mod readiness."""
    global rgb_publisher, depth_publisher, extrinsics_publisher, intrinsics_publisher
    global objects_publisher, lidar_publisher
    global static_transform_broadcaster
    global camera_frame_id, base_frame_id, lidar_frame_id, eye_height
    global rgb_topic, depth_topic, extrinsics_topic, intrinsics_topic
    global odom_topic, odom_frame_id, objects_topic, lidar_topic
    global camera_stream_hz, odom_stream_hz, lidar_stream_hz
    global lidar_ray_count, lidar_range_max
    ready = init_mod(config)
    if isinstance(ready, Err):
        return ready
    camera_publishers = (
        rgb_publisher,
        depth_publisher,
        extrinsics_publisher,
        intrinsics_publisher,
        objects_publisher,
        lidar_publisher,
    )
    if all(publisher is not None for publisher in camera_publishers):
        if odom_publisher is not None:
            return Ok()
        return initialize_chassis(config)
    if any(publisher is not None for publisher in camera_publishers):
        return Err("camera publisher initialization is incomplete; restart the provider")
    try:
        from geometry_msgs.msg import TransformStamped  # type: ignore
        from sensor_msgs.msg import CameraInfo, Image as RosImage, LaserScan  # type: ignore
        from std_msgs.msg import String  # type: ignore

        camera_stream_hz = configured_frequency("RBNX_MINECRAFT_CAMERA_STREAM_HZ", 5.0)
        odom_stream_hz = configured_frequency("RBNX_MINECRAFT_ODOM_HZ", 10.0)
        lidar_stream_hz = configured_frequency("RBNX_MINECRAFT_LIDAR_HZ", 5.0)
        lidar_ray_count = configured_lidar_ray_count(config)
        lidar_range_max = configured_lidar_range(config)
        camera_frame_id = str(config.get("camera_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_CAMERA_FRAME_ID", camera_frame_id,
        ))
        base_frame_id = str(config.get("base_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_BASE_FRAME_ID", base_frame_id,
        ))
        lidar_frame_id = str(config.get("lidar_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_LIDAR_FRAME_ID", base_frame_id,
        ))
        eye_height = configured_eye_height(config)
        rgb_topic = str(config.get("rgb_topic") or os.environ.get(
            "RBNX_MINECRAFT_RGB_TOPIC", rgb_topic,
        ))
        depth_topic = str(config.get("depth_topic") or os.environ.get(
            "RBNX_MINECRAFT_DEPTH_TOPIC", depth_topic,
        ))
        extrinsics_topic = str(config.get("extrinsics_topic") or os.environ.get(
            "RBNX_MINECRAFT_EXTRINSICS_TOPIC", extrinsics_topic,
        ))
        intrinsics_topic = str(config.get("intrinsics_topic") or os.environ.get(
            "RBNX_MINECRAFT_INTRINSICS_TOPIC", intrinsics_topic,
        ))
        odom_topic = str(config.get("odom_topic") or os.environ.get(
            "RBNX_MINECRAFT_ODOM_TOPIC", odom_topic,
        ))
        objects_topic = str(config.get("objects_topic") or os.environ.get(
            "RBNX_MINECRAFT_OBJECTS_TOPIC", objects_topic,
        ))
        lidar_topic = str(config.get("lidar_topic") or os.environ.get(
            "RBNX_MINECRAFT_LIDAR_TOPIC", lidar_topic,
        ))
        odom_frame_id = str(config.get("odom_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_ODOM_FRAME_ID", odom_frame_id,
        ))
        rgb_publisher = minecraft_camera.create_publisher(
            "robonix/primitive/camera/rgb",
            topic=rgb_topic,
            msg_type=RosImage,
            qos="best_effort",
        )
        depth_publisher = minecraft_camera.create_publisher(
            "robonix/primitive/camera/depth",
            topic=depth_topic,
            msg_type=RosImage,
            qos="best_effort",
        )
        extrinsics_publisher = minecraft_camera.create_publisher(
            "robonix/primitive/camera/extrinsics",
            topic=extrinsics_topic,
            msg_type=TransformStamped,
            qos="latched",
        )
        intrinsics_publisher = minecraft_camera.create_publisher(
            "robonix/primitive/camera/intrinsics",
            topic=intrinsics_topic,
            msg_type=CameraInfo,
            qos="latched",
        )
        objects_publisher = minecraft_perception.create_publisher(
            "robonix/primitive/perception/objects",
            topic=objects_topic,
            msg_type=String,
            qos="latched",
        )
        lidar_publisher = minecraft_lidar.create_publisher(
            "robonix/primitive/lidar/lidar",
            topic=lidar_topic,
            msg_type=LaserScan,
            qos="best_effort",
        )
        from tf2_ros import StaticTransformBroadcaster  # type: ignore

        static_transform_broadcaster = StaticTransformBroadcaster(
            RosBackend.get().node,
        )
        publish_extrinsics()
    except Exception as exc:  # noqa: BLE001
        return Err(f"ROS2 camera stream initialization failed: {exc}")
    chassis_result = initialize_chassis(config)
    if isinstance(chassis_result, Err):
        return chassis_result
    return Ok()


@minecraft_camera.on_init
def init_camera(config: dict[str, Any]):
    """Serialize the complete camera and colocated chassis initialization transaction."""
    with camera_lifecycle_lock:
        return initialize_camera(config)


@minecraft_camera.on_activate
def activate_camera():
    """Activate every stream owned by the aggregate Minecraft provider."""
    with camera_lifecycle_lock:
        if any(publisher is None for publisher in (
            rgb_publisher,
            depth_publisher,
            extrinsics_publisher,
            intrinsics_publisher,
            lidar_publisher,
        )):
            return Err("camera and lidar streams are not initialized")
        chassis_result = activate_chassis()
        if isinstance(chassis_result, Err):
            return chassis_result
        try:
            publish_extrinsics()
            start_camera_stream()
            start_lidar_stream()
        except Exception as exc:  # noqa: BLE001
            camera_stopped = stop_camera_stream()
            lidar_stopped = stop_lidar_stream()
            rollback = deactivate_chassis()
            detail = f"camera stream activation failed: {exc}"
            if not camera_stopped:
                detail += "; camera worker rollback timed out"
            if not lidar_stopped:
                detail += "; lidar worker rollback timed out"
            if isinstance(rollback, Err):
                detail += f"; chassis rollback failed: {rollback.message}"
            return Err(detail)
    return Ok()


@minecraft_camera.on_deactivate
def deactivate_camera():
    """Stop every stream owned by the aggregate Minecraft provider."""
    with camera_lifecycle_lock:
        camera_stopped = stop_camera_stream()
        lidar_stopped = stop_lidar_stream()
        chassis_result = deactivate_chassis()
    if not camera_stopped:
        return Err("camera stream worker did not stop before timeout")
    if not lidar_stopped:
        return Err("lidar stream worker did not stop before timeout")
    if isinstance(chassis_result, Err):
        return chassis_result
    return Ok()


@minecraft_camera.on_shutdown
def shutdown_camera():
    """Stop every worker before the aggregate provider tears down."""
    with camera_lifecycle_lock:
        camera_stopped = stop_camera_stream()
        lidar_stopped = stop_lidar_stream()
        chassis_result = shutdown_chassis()
    if not camera_stopped:
        return Err("camera stream worker did not stop before timeout")
    if not lidar_stopped:
        return Err("lidar stream worker did not stop before timeout")
    if isinstance(chassis_result, Err):
        return chassis_result
    return Ok()


def initialize_chassis(config: dict[str, Any]):
    """Initialize the ROS odometry publisher under the chassis namespace."""
    global odom_publisher, odom_transform_broadcaster, cmd_vel_subscription
    global odom_topic, odom_frame_id, base_frame_id
    global cmd_vel_topic, cmd_vel_command_duration_sec, cmd_vel_timeout_sec
    global cmd_vel_max_linear, cmd_vel_max_angular
    if odom_publisher is not None and odom_transform_broadcaster is not None:
        return Ok()
    ready = init_mod(config)
    if isinstance(ready, Err):
        return ready
    try:
        from geometry_msgs.msg import Twist  # type: ignore
        from nav_msgs.msg import Odometry  # type: ignore

        odom_topic = str(config.get("odom_topic") or os.environ.get(
            "RBNX_MINECRAFT_ODOM_TOPIC", odom_topic,
        ))
        odom_frame_id = str(config.get("odom_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_ODOM_FRAME_ID", odom_frame_id,
        ))
        base_frame_id = str(config.get("base_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_BASE_FRAME_ID", base_frame_id,
        ))
        (
            cmd_vel_topic,
            cmd_vel_command_duration_sec,
            cmd_vel_timeout_sec,
            cmd_vel_max_linear,
            cmd_vel_max_angular,
        ) = configured_cmd_vel_bridge(config)
        if odom_publisher is None:
            odom_publisher = minecraft_chassis.create_publisher(
                "robonix/primitive/chassis/odom",
                topic=odom_topic,
                msg_type=Odometry,
                qos="reliable",
            )
        if cmd_vel_subscription is None:
            cmd_vel_subscription = RosBackend.get().node.create_subscription(
                Twist,
                cmd_vel_topic,
                receive_cmd_vel,
                10,
            )
        if odom_transform_broadcaster is None:
            from tf2_ros import TransformBroadcaster  # type: ignore

            odom_transform_broadcaster = TransformBroadcaster(
                RosBackend.get().node,
            )
    except Exception as exc:  # noqa: BLE001
        return Err(f"ROS2 odom stream initialization failed: {exc}")
    return Ok()


def init_chassis(config: dict[str, Any]):
    """Initialize the chassis portion of the aggregate provider."""
    with chassis_lifecycle_lock:
        return initialize_chassis(config)


def activate_chassis():
    """Start continuous odometry and the bounded Nav2 Twist bridge."""
    with chassis_lifecycle_lock:
        if odom_publisher is None:
            return Err("chassis odometry is not initialized")
        start_odom_stream()
        start_cmd_vel_bridge()
    return Ok()


def deactivate_chassis():
    """Stop odometry and fail closed on any active Nav2 velocity."""
    with chassis_lifecycle_lock:
        if not stop_cmd_vel_bridge():
            return Err("cmd_vel bridge worker did not stop before timeout")
        if not stop_odom_stream():
            return Err("odometry stream worker did not stop before timeout")
    return Ok()


def shutdown_chassis():
    """Stop all chassis workers before provider teardown."""
    with chassis_lifecycle_lock:
        if not stop_cmd_vel_bridge():
            return Err("cmd_vel bridge worker did not stop before timeout")
        if not stop_odom_stream():
            return Err("odometry stream worker did not stop before timeout")
    return Ok()


def run_providers() -> None:
    """Run the aggregate Minecraft provider until Robonix requests shutdown."""
    minecraft_mod.run()


if __name__ == "__main__":
    run_providers()
