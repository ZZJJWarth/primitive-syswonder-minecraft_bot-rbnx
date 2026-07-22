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
from datetime import datetime
from io import BytesIO
from pathlib import Path
from typing import Any

from robonix_api import ATLAS, Err, Ok, Primitive, Transport
from robonix_api.lifecycle import CMD_ACTIVATE, CMD_DEACTIVATE, CMD_INIT
import chassis_mcp  # noqa: E402
import minecraft_mcp  # noqa: E402
import chassis_pb2  # noqa: E402
import std_msgs_pb2  # noqa: E402

import builtin_interfaces_mcp  # noqa: E402
import std_msgs_mcp  # noqa: E402
from sensor_msgs_mcp import Image  # noqa: E402
from std_msgs_mcp import Empty  # noqa: E402


MAX_RESPONSE_BYTES = 64 * 1024 * 1024
minecraft_camera = Primitive(id="minecraft_mod", namespace="robonix/primitive/camera")
minecraft_chassis = Primitive(id="minecraft_mod_chassis", namespace="robonix/primitive/chassis")
minecraft_perception = Primitive(
    id="minecraft_mod_perception",
    namespace="robonix/primitive/perception",
)
minecraft_actions = Primitive(
    id="minecraft_mod_actions",
    namespace="robonix/primitive/minecraft",
)
chassis_bootstrapped = False
perception_bootstrapped = False
actions_bootstrapped = False
rgb_publisher = None
depth_publisher = None
extrinsics_publisher = None
intrinsics_publisher = None
odom_publisher = None
objects_publisher = None
camera_stream_stop = threading.Event()
odom_stream_stop = threading.Event()
camera_stream_thread: threading.Thread | None = None
odom_stream_thread: threading.Thread | None = None
camera_lifecycle_lock = threading.Lock()
chassis_lifecycle_lock = threading.Lock()
camera_worker_lock = threading.Lock()
odom_worker_lock = threading.Lock()
stream_error_lock = threading.Lock()
last_stream_errors: dict[str, tuple[str, float]] = {}
previous_odom_sample: tuple[float, float, float, float] | None = None
last_intrinsics: tuple[int, int, float] | None = None
camera_stream_hz = 5.0
odom_stream_hz = 10.0
camera_frame_id = "minecraft_camera_optical_frame"
base_frame_id = "minecraft_view_base"
odom_frame_id = "minecraft_odom"
eye_height = 1.62
rgb_topic = "/minecraft/camera/rgb"
depth_topic = "/minecraft/camera/depth"
extrinsics_topic = "/minecraft/camera/extrinsics"
intrinsics_topic = "/minecraft/camera/intrinsics"
odom_topic = "/minecraft/chassis/odom"
objects_topic = "/minecraft/perception/objects"


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
    """Convert the Mod's persistent exact-block snapshot into ROS ENU coordinates."""
    objects = []
    for block in observation.get("semanticBlocks") or []:
        position = block.get("position") or {}
        minecraft_x = float(position["x"])
        minecraft_y = float(position["y"])
        minecraft_z = float(position["z"])
        last_seen = observation_timestamp({"timestamp": block["lastSeen"]})
        values = (minecraft_x, minecraft_y, minecraft_z, last_seen)
        if not all(math.isfinite(value) for value in values):
            raise ValueError("Minecraft semantic block coordinates must be finite")
        objects.append({
            "id": str(block["id"]),
            "label": str(block["name"]),
            "x": minecraft_x + 0.5,
            "y": -(minecraft_z + 0.5),
            "z": minecraft_y + 0.5,
            "yaw": 0.0,
            "last_seen_unix": last_seen,
        })
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


def publish_intrinsics(width: int, height: int, vertical_fov_deg: float) -> None:
    """Publish pinhole calibration for the current Minecraft depth frame.

    Minecraft's FOV setting is vertical. Capture scaling preserves aspect
    ratio, so square pixels use the same focal length on both axes.
    """
    global last_intrinsics
    if intrinsics_publisher is None:
        raise RuntimeError("camera intrinsics publisher is not initialized")
    values = (float(width), float(height), float(vertical_fov_deg))
    if not all(math.isfinite(value) and value > 0.0 for value in values):
        raise ValueError("camera dimensions and vertical FOV must be positive and finite")
    if vertical_fov_deg >= 180.0:
        raise ValueError("camera vertical FOV must be less than 180 degrees")
    calibration = (width, height, vertical_fov_deg)
    if calibration == last_intrinsics:
        return
    from sensor_msgs.msg import CameraInfo  # type: ignore

    focal = height / (2.0 * math.tan(math.radians(vertical_fov_deg) / 2.0))
    cx = (width - 1.0) / 2.0
    cy = (height - 1.0) / 2.0
    message = CameraInfo()
    message.header.frame_id = camera_frame_id
    message.width = width
    message.height = height
    message.distortion_model = "plumb_bob"
    message.d = [0.0] * 5
    message.k = [focal, 0.0, cx, 0.0, focal, cy, 0.0, 0.0, 1.0]
    message.r = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    message.p = [focal, 0.0, cx, 0.0, 0.0, focal, cy, 0.0, 0.0, 0.0, 1.0, 0.0]
    intrinsics_publisher.publish(message)
    last_intrinsics = calibration


def publish_odom(observation: dict[str, Any], timestamp: float | None = None) -> None:
    """Publish exact player view pose and finite-difference planar velocity."""
    global previous_odom_sample
    if odom_publisher is None:
        raise RuntimeError("chassis odom publisher is not initialized")
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


def configured_frequency(name: str, fallback: float) -> float:
    """Read one stream frequency and require a finite rate from 0.1 through 60 Hz."""
    value = float(os.environ.get(name, str(fallback)))
    if not math.isfinite(value) or not 0.1 <= value <= 60.0:
        raise ValueError(f"{name} must be finite and from 0.1 through 60")
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


def drive_chassis(command: int, config: dict[str, Any] | None = None):
    """Send one existing Driver lifecycle command to the colocated chassis provider."""
    import grpc
    import lifecycle_pb2  # type: ignore
    import robonix_contracts_pb2_grpc  # type: ignore

    lifecycle_config = config or {}
    if command == CMD_INIT:
        ready_timeout_ms = int(lifecycle_config.get("ready_timeout_ms") or os.environ.get(
            "RBNX_MINECRAFT_MOD_READY_TIMEOUT_MS", "30000",
        ))
        rpc_timeout = ready_timeout_ms / 1000.0 + client.timeout + 5.0
    else:
        rpc_timeout = client.timeout + 5.0
    try:
        with ATLAS.connect_capability(
            consumer_id=minecraft_camera.id,
            provider_id=minecraft_chassis.id,
            contract_id="robonix/primitive/chassis/driver",
            transport=Transport.GRPC,
        ) as route:
            with grpc.insecure_channel(route.endpoint) as grpc_channel:
                stub = robonix_contracts_pb2_grpc.RobonixPrimitiveChassisDriverStub(
                    grpc_channel,
                )
                response = stub.Driver(
                    lifecycle_pb2.Driver_Request(
                        command=command,
                        config_json=json.dumps(lifecycle_config),
                    ),
                    timeout=rpc_timeout,
                )
    except Exception as exc:  # noqa: BLE001
        return Err(f"chassis lifecycle request failed: {exc}")
    if not response.ok:
        return Err(response.error or f"chassis lifecycle entered {response.state}")
    return Ok()


def bootstrap_chassis():
    """Start chassis serving once and require its existing Driver capability in Atlas."""
    global chassis_bootstrapped
    if not chassis_bootstrapped:
        minecraft_chassis.bootstrap()
        chassis_bootstrapped = True
    drivers = ATLAS.find_capability(
        contract_id="robonix/primitive/chassis/driver",
        provider_id=minecraft_chassis.id,
        transport=Transport.GRPC,
    )
    if not drivers:
        return Err("chassis registration failed; restart the provider")
    return Ok()


def bootstrap_perception() -> None:
    """Register the exact-object publisher under its matching primitive namespace."""
    global perception_bootstrapped
    if perception_bootstrapped:
        return
    minecraft_perception.bootstrap()
    perception_bootstrapped = True


def bootstrap_actions() -> None:
    """Register item actions only after the Forge client is ready in a world."""
    global actions_bootstrapped
    if actions_bootstrapped:
        return
    minecraft_actions.bootstrap()
    actions_bootstrapped = True


REGISTRY_NAME = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ACTION_REQUEST_TIMEOUT_SEC = 30.0
BREAKABLE_BLOCKS = frozenset(("minecraft:dirt", "minecraft:crafting_table"))
DEPOSITABLE_ITEMS = BREAKABLE_BLOCKS


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


def validated_coordinates(req: Any) -> tuple[int, int, int]:
    """Validate signed int32 block coordinates from a generated MCP request."""
    return tuple(
        exact_int(getattr(req, axis), axis, -2_147_483_648, 2_147_483_647)
        for axis in ("x", "y", "z")
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


@minecraft_actions.mcp("robonix/primitive/minecraft/deposit_item")
def deposit_item_mcp(
    req: minecraft_mcp.DepositItem_Request,
) -> minecraft_mcp.DepositItem_Response:
    """Deposit one supported item into an explicit reachable chest and verify counts."""
    x, y, z = validated_coordinates(req)
    item = registry_name(req.item, "item")
    count = exact_int(req.count, "count", 1, 4_294_967_295)
    if item not in DEPOSITABLE_ITEMS:
        raise ValueError("item must be minecraft:dirt or minecraft:crafting_table")
    if count != 1:
        raise ValueError("count must be exactly 1")
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
    if fields["transferred_count"] != 1:
        raise RuntimeError("Minecraft Mod did not verify exactly one transferred item")
    if fields["player_count_after"] != fields["player_count_before"] - 1:
        raise RuntimeError("Minecraft Mod player inventory counts are inconsistent")
    if fields["container_count_after"] != fields["container_count_before"] + 1:
        raise RuntimeError("Minecraft Mod container counts are inconsistent")
    return minecraft_mcp.DepositItem_Response(**fields)


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
        bootstrap_actions()
    except Exception as exc:  # noqa: BLE001
        return Err(str(exc))
    return Ok()


def initialize_camera(config: dict[str, Any]):
    """Initialize camera streams and the colocated chassis after Mod readiness."""
    global rgb_publisher, depth_publisher, extrinsics_publisher, intrinsics_publisher
    global objects_publisher
    global camera_frame_id, base_frame_id, eye_height
    global rgb_topic, depth_topic, extrinsics_topic, intrinsics_topic
    global odom_topic, odom_frame_id, objects_topic
    global camera_stream_hz, odom_stream_hz
    ready = init_mod(config)
    if isinstance(ready, Err):
        return ready
    camera_publishers = (
        rgb_publisher,
        depth_publisher,
        extrinsics_publisher,
        intrinsics_publisher,
        objects_publisher,
    )
    if all(publisher is not None for publisher in camera_publishers):
        if odom_publisher is not None:
            return Ok()
        chassis_bootstrap = bootstrap_chassis()
        if isinstance(chassis_bootstrap, Err):
            return chassis_bootstrap
        return drive_chassis(CMD_INIT, config)
    if any(publisher is not None for publisher in camera_publishers):
        return Err("camera publisher initialization is incomplete; restart the provider")
    try:
        from geometry_msgs.msg import TransformStamped  # type: ignore
        from sensor_msgs.msg import CameraInfo, Image as RosImage  # type: ignore
        from std_msgs.msg import String  # type: ignore

        camera_stream_hz = configured_frequency("RBNX_MINECRAFT_CAMERA_STREAM_HZ", 5.0)
        odom_stream_hz = configured_frequency("RBNX_MINECRAFT_ODOM_HZ", 10.0)
        camera_frame_id = str(config.get("camera_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_CAMERA_FRAME_ID", camera_frame_id,
        ))
        base_frame_id = str(config.get("base_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_BASE_FRAME_ID", base_frame_id,
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
        odom_frame_id = str(config.get("odom_frame_id") or os.environ.get(
            "RBNX_MINECRAFT_ODOM_FRAME_ID", odom_frame_id,
        ))
        bootstrap_perception()
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
        publish_extrinsics()
    except Exception as exc:  # noqa: BLE001
        return Err(f"ROS2 camera stream initialization failed: {exc}")
    chassis_bootstrap = bootstrap_chassis()
    if isinstance(chassis_bootstrap, Err):
        return chassis_bootstrap
    chassis_result = drive_chassis(CMD_INIT, config)
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
    """Activate chassis odometry through Driver and start continuous camera streams."""
    with camera_lifecycle_lock:
        if any(publisher is None for publisher in (
            rgb_publisher,
            depth_publisher,
            extrinsics_publisher,
            intrinsics_publisher,
        )):
            return Err("camera streams are not initialized")
        chassis_result = drive_chassis(CMD_ACTIVATE)
        if isinstance(chassis_result, Err):
            return chassis_result
        try:
            publish_extrinsics()
            start_camera_stream()
        except Exception as exc:  # noqa: BLE001
            camera_stopped = stop_camera_stream()
            rollback = drive_chassis(CMD_DEACTIVATE)
            detail = f"camera stream activation failed: {exc}"
            if not camera_stopped:
                detail += "; camera worker rollback timed out"
            if isinstance(rollback, Err):
                detail += f"; chassis rollback failed: {rollback.message}"
            return Err(detail)
    return Ok()


@minecraft_camera.on_deactivate
def deactivate_camera():
    """Stop camera publication and deactivate chassis odometry through Driver."""
    with camera_lifecycle_lock:
        camera_stopped = stop_camera_stream()
        chassis_result = drive_chassis(CMD_DEACTIVATE)
    if not camera_stopped:
        return Err("camera stream worker did not stop before timeout")
    if isinstance(chassis_result, Err):
        return chassis_result
    return Ok()


@minecraft_camera.on_shutdown
def shutdown_camera():
    """Stop camera publication and deactivate chassis before process teardown."""
    with camera_lifecycle_lock:
        camera_stopped = stop_camera_stream()
        chassis_result = (
            drive_chassis(CMD_DEACTIVATE)
            if chassis_bootstrapped
            else Ok()
        )
    if not camera_stopped:
        return Err("camera stream worker did not stop before timeout")
    if isinstance(chassis_result, Err):
        return chassis_result
    return Ok()


def initialize_chassis(config: dict[str, Any]):
    """Initialize the ROS odometry publisher under the chassis namespace."""
    global odom_publisher, odom_topic, odom_frame_id, base_frame_id
    if odom_publisher is not None:
        return Ok()
    ready = init_mod(config)
    if isinstance(ready, Err):
        return ready
    try:
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
        odom_publisher = minecraft_chassis.create_publisher(
            "robonix/primitive/chassis/odom",
            topic=odom_topic,
            msg_type=Odometry,
            qos="reliable",
        )
    except Exception as exc:  # noqa: BLE001
        return Err(f"ROS2 odom stream initialization failed: {exc}")
    return Ok()


@minecraft_chassis.on_init
def init_chassis(config: dict[str, Any]):
    """Serialize the complete chassis publisher initialization transaction."""
    with chassis_lifecycle_lock:
        return initialize_chassis(config)


@minecraft_chassis.on_activate
def activate_chassis():
    """Start continuous player-view odometry publication."""
    with chassis_lifecycle_lock:
        if odom_publisher is None:
            return Err("chassis odometry is not initialized")
        start_odom_stream()
    return Ok()


@minecraft_chassis.on_deactivate
def deactivate_chassis():
    """Stop odometry publication while retaining its configured topic."""
    with chassis_lifecycle_lock:
        if not stop_odom_stream():
            return Err("odometry stream worker did not stop before timeout")
    return Ok()


@minecraft_chassis.on_shutdown
def shutdown_chassis():
    """Stop the odometry worker before chassis provider teardown."""
    with chassis_lifecycle_lock:
        if not stop_odom_stream():
            return Err("odometry stream worker did not stop before timeout")
    return Ok()


def run_providers() -> None:
    """Run the camera provider until Robonix requests shutdown."""
    minecraft_camera.run()


if __name__ == "__main__":
    run_providers()
