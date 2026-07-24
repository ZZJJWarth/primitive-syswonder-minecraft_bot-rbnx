# Robonix Minecraft primitive

This package connects a real Minecraft Java client to Robonix. A client-only
Forge Mod captures the rendered framebuffer, applies bounded player controls,
and serves a loopback JSON-lines interface. The Python package exposes that
client through existing camera, chassis, perception, and Minecraft primitive
contracts.

## Versions

- Minecraft Java 1.20.4
- Forge 49.2.0
- Eclipse Temurin OpenJDK 17.0.19+10
- ROS 2 Jazzy
- Robonix development baseline
  `74b4ca0f7c0db3c237730321753501e12646c626`

The package tracks the latest Robonix development version. Compatibility is
verified again for every package release; the commit above is the reproducible
baseline for `v0.1.0`.

The installed Java 21 can remain the system default. The build script uses
`JAVA17_HOME`, defaulting to
`$HOME/.local/share/jdks/jdk-17.0.19+10`.

## Build

```bash
JAVA_HOME="$HOME/.local/share/jdks/jdk-17.0.19+10" ./gradlew build
```

Copy `build/libs/robonix_minecraft-0.1.0.jar` into the Forge 1.20.4 client's
`mods/` directory, then launch the client and join the target world or server.
The server does not need to install this Mod.
Set the Forge launcher profile's Java executable to
`$HOME/.local/share/jdks/jdk-17.0.19+10/bin/java`.

With `rbnx` installed and configured against a compatible Robonix source tree,
validate and build the complete package:

```bash
rbnx validate .
rbnx build -p .
```

## Start

The package start entry launches the Forge development client, local viewer,
and Python providers. Atlas must already be running:

```bash
RBNX_MINECRAFT_USE_NVIDIA=1 rbnx start -p . \
  --endpoint 127.0.0.1:50051 \
  --set ready_timeout_ms=120000
```

The Mod listens only on `127.0.0.1:25576`. The package reuses a client already
listening there instead of launching another one. For a complete deployment,
use `syswonder/robot-syswonder-minecraft_bot`, which owns the player URDF,
Soma description, service selection, skill selection, and runtime parameters.

Set `RBNX_MINECRAFT_USE_NVIDIA=1` to let boot start the client with NVIDIA
Xorg. If NVIDIA rendering cannot be prepared, startup falls back to the
inherited `DISPLAY`. Use `0` (the default) to use that display directly. Since
boot is non-interactive, creating a new NVIDIA Xorg requires cached `sudo`
authorization; otherwise startup falls back. For unattended startup, run a
persistent NVIDIA Xorg service instead. An existing NVIDIA display requires no
authorization. Boot also reuses a client already listening on the Mod command
port.

The package starts a local framebuffer viewer at `http://127.0.0.1:8765/`. The page
shows the latest Mod JPEG beside a fixed-scale depth visualization without
controlling the player. Depth colors cover 0 through 32 blocks, with invalid
sky pixels shown as black. Set
`RBNX_MINECRAFT_VIEWER_HOST=0.0.0.0` to listen on other interfaces only on a
trusted network; the viewer has no authentication. A non-loopback bind also
requires `RBNX_MINECRAFT_VIEWER_ALLOWED_HOST` to match the hostname or IP used
in the browser URL. The port defaults to `8765` and can be changed with
`RBNX_MINECRAFT_VIEWER_PORT`.

Use `rbnx chat` from another terminal. The model can call the existing camera
snapshot, depth snapshot, and chassis move capabilities; movement is applied
to the same player whose framebuffer and GPU depth are captured. Depth uses
little-endian `32FC1` metres with sky pixels set to `NaN`. When that player
dies, the Mod automatically requests a respawn and releases any active
movement key.

Chassis move has three exclusive modes. `forward_m` is a signed relative
distance in blocks (positive forward, negative backward, absolute value at
most `32`). `rotate_deg` is a signed relative total yaw angle in degrees
(positive left/counterclockwise, negative right/clockwise); it is not a speed.
In velocity mode, `linear_x` and `linear_y` are player-relative speeds in
blocks per second, with positive/negative values meaning forward/backward and
left/right. Their combined magnitude cannot exceed `4.317`. `angular_z` and
`angular_y` are yaw and pitch rates in radians per second, with
positive/negative values meaning left/right and down/up. Their resulting
relative angles equal the rate multiplied by `duration_sec`.

Velocity fields can run together for `duration_sec`; zero selects one second,
while an explicit duration must be from `0.05` through `30` seconds.
`linear_z` and `angular_x` are unsupported and must remain zero. Keep fields
from the other modes zero, and change one axis at a time for visual
micro-adjustments before inspecting a new snapshot.

The provider also publishes continuous ROS 2 RGB, depth in the RGB optical
frame, camera intrinsics and extrinsics, and chassis odometry through their
existing capability contracts. RGB is `rgb8`; depth is little-endian `32FC1`
metres. The latched `CameraInfo` uses the active Minecraft vertical FOV and the
actual scaled depth dimensions, so Scene receives calibration that matches
each capture size.
Depth and odometry headers retain their client-side capture timestamps so map
services can associate each frame with the corresponding player pose.
RGB and depth are acquired sequentially, not as an atomic pair, and either
stream continues if the other capture fails. The odometry frame uses ROS ENU
coordinates (`x` east, `y` north), treats one block as one metre, and places
`minecraft_view_base` at the configurable nominal eye height so view pitch
remains part of the published pose while camera extrinsics stay fixed.

The provider also publishes `/minecraft/lidar/scan` through
`robonix/primitive/lidar/lidar`. This body-relative 360-degree
`sensor_msgs/LaserScan` combines exact Minecraft collision raycasts at waist
and head height. It is independent of the rendered depth buffer, so side and
rear obstacles do not require turning the first-person camera.

The provider also publishes exact semantic observations for visible chests,
trapped chests, crafting tables, and furnaces. Discovery is local and occurs
during exploration rather than preloading the world. Discovered blocks remain
in the snapshot after the player leaves, while revisiting a changed location
updates or removes its entry. Scene consumes this stream alongside visual
perception, so exact Minecraft blocks do not depend on YOLO vocabulary.

Pilot can also compose five Minecraft state and action capabilities directly:

- `robonix/primitive/minecraft/inventory` reports main inventory slots 0-35
  (hotbar 0-8) and offhand slot 40 with exact item registry names.
- `robonix/primitive/minecraft/get_state` returns a fresh timestamped compact
  snapshot of the player's Minecraft-world pose and status without changing
  the player or world, together with its converted ROS navigation pose. A
  Pilot deployment may inject this result into every planning round as
  authoritative live state. Generic navigation uses the `navigation_*`
  fields; native Minecraft coordinate targets use
  `robonix/service/navigation/navigate_world`.
- `robonix/primitive/minecraft/aim_at_block` gradually turns toward an exact
  Scene object at bounded yaw and pitch rates. Pass the object's
  `minecraft_odom` centroid unchanged as `navigation_x/y/z`; the provider
  converts it to the native Minecraft block position. Do not pass the robot
  pose or the approach pose returned by `scene/goal_near`. It succeeds only
  after the live crosshair stably hits the visible, interaction-reachable block
  with the expected registry name, and does not interact with that block.
- `robonix/primitive/minecraft/break_block` targets the block currently under
  the crosshair, requires survival mode and either `minecraft:dirt` or
  `minecraft:crafting_table`, and can wait for matching synchronized pickup
  verification. It stops attacking and walks toward the former block centre
  when collection is requested. Aim at the intended block before calling it.
- `robonix/primitive/minecraft/deposit_item` accepts explicit chest coordinates
  and transfers exactly one main-inventory `minecraft:dirt` or
  `minecraft:crafting_table`, reporting synchronized player and container
  counts.

These capabilities use ordinary client state and interactions rather than
changing the world directly. Targets must be loaded, visible, and within
interaction reach. Only one aim, break, or deposit operation runs at a time;
disconnect, death, menu replacement, timeout, or a failed server
synchronization returns an error. Do not call aim directly while navigation
reports `navigating`; wait for navigation to become terminal first.
A typical Pilot composition is inventory inspection, explicit dirt breaking,
another inventory inspection, Scene chest discovery and navigation, deposit,
and final inventory verification.

## Command-line client

With the Minecraft server running and an X display available, start the Forge
client and connect without using the Minecraft menus:

```bash
DISPLAY=:10.0 scripts/run-client.sh
```

The script waits up to 30 seconds for the server, then launches Minecraft with
`--quickPlayMultiplayer`. It also disables the first-run accessibility prompt
in the development client's `options.txt`, allowing Quick Play to proceed
without a click. It uses these optional settings:

- `MC_HOST`: Minecraft server host, default `127.0.0.1`.
- `MC_PORT`: Minecraft server port, default `25565`.
- `MC_USERNAME`: offline-mode username, default `robonix_agent`.
- `MC_CLIENT_WIDTH`, `MC_CLIENT_HEIGHT`: client window size, default
  `1280x720`.
- `JAVA17_HOME`: JDK 17 location.

This removes all in-game clicks but still needs a functioning X display and
OpenGL renderer. A persistent NVIDIA Xorg display can replace the interactive
desktop display for fully unattended operation.

For an unattended NVIDIA-rendered client, use the wrapper script:

```bash
scripts/run-nvidia-client.sh
```

The script selects the NVIDIA GPU with the most free memory, starts a headless
Xorg on `:48` through `sudo`, waits for it to initialize, and then runs the
client. It reuses an existing `:48` display and only stops Xorg when it started
that process itself. Only one wrapper may use a display at a time. Set
`MC_NVIDIA_DISPLAY` to use another display number. Minecraft's
`run/logs/latest.log` reports the active OpenGL renderer.

## Configuration

- `RBNX_MINECRAFT_MOD_PORT`: loopback command port, default `25576`.
- `RBNX_MINECRAFT_ROS_SETUP`: ROS 2 `setup.bash`; defaults to an installed
  Jazzy or Humble setup.
- `RBNX_MINECRAFT_CAMERA_STREAM_HZ`: RGB/depth publication rate, default `5`;
  valid range `0.1` through `60`.
- `RBNX_MINECRAFT_ODOM_HZ`: odometry publication rate, default `10`; valid
  range `0.1` through `60`.
- `RBNX_MINECRAFT_LIDAR_HZ`: LiDAR publication rate, default `5`; valid range
  `0.1` through `60`.
- `RBNX_MINECRAFT_LIDAR_RAY_COUNT`: samples per full revolution, default `180`;
  valid range `16` through `1440`.
- `RBNX_MINECRAFT_LIDAR_RANGE_MAX`: maximum collision-ray distance in metres,
  default `16`; valid range `1` through `64`.
- `RBNX_MINECRAFT_RGB_TOPIC`, `RBNX_MINECRAFT_DEPTH_TOPIC`,
  `RBNX_MINECRAFT_EXTRINSICS_TOPIC`, `RBNX_MINECRAFT_INTRINSICS_TOPIC`,
  `RBNX_MINECRAFT_ODOM_TOPIC`, `RBNX_MINECRAFT_LIDAR_TOPIC`,
  `RBNX_MINECRAFT_OBJECTS_TOPIC`: ROS 2 topic overrides.
- `RBNX_MINECRAFT_CAMERA_FRAME_ID`, `RBNX_MINECRAFT_BASE_FRAME_ID`,
  `RBNX_MINECRAFT_ODOM_FRAME_ID`, `RBNX_MINECRAFT_LIDAR_FRAME_ID`: ROS frame
  overrides.
- `RBNX_MINECRAFT_EYE_HEIGHT`: nominal player eye height in blocks, default
  `1.62`; valid range `0` through `3`.
- `RBNX_MINECRAFT_CAPTURE_INTERVAL_MS`: minimum capture interval, default `200`.
- `RBNX_MINECRAFT_CAPTURE_WIDTH`: maximum RGB and depth capture width, default
  `960`.
- `RBNX_MINECRAFT_CAPTURE_HEIGHT`: maximum RGB and depth capture height,
  default `540`. Capture scaling preserves the client framebuffer aspect ratio.
- `RBNX_MINECRAFT_JPEG_QUALITY`: JPEG quality from `0.1` to `1.0`, default `0.8`.
- `RBNX_MINECRAFT_BLOCK_RADIUS`: structured block scan radius, default `5`.
- `RBNX_MINECRAFT_MOD_HOST`: provider connection host, default `127.0.0.1`.
- `RBNX_MINECRAFT_MOD_TIMEOUT_SEC`: provider command timeout, default `35`;
  valid range `0.1` through `300`.
- `RBNX_MINECRAFT_VIEWER_HOST`: web viewer bind host, default `127.0.0.1`.
- `RBNX_MINECRAFT_VIEWER_ALLOWED_HOST`: accepted browser `Host` when using a
  non-loopback bind; unset for the default localhost-only viewer.
- `RBNX_MINECRAFT_VIEWER_PORT`: web viewer port, default `8765`.
- `RBNX_MINECRAFT_VIEWER_INTERVAL_MS`: framebuffer refresh interval, default
  `200` with a minimum of `50`.
- `RBNX_MINECRAFT_MOD_READY_TIMEOUT_MS`: provider initialization timeout,
  default `30000`. A deployment can supply a larger `ready_timeout_ms` through
  provider configuration so the Forge client has time to start.

The capture contains the final rendered client image, including HUD and open
screens. Keep the client visible and rendering; minimized clients may stop or
throttle framebuffer updates depending on the desktop environment.

## Provenance

This package was extracted from `examples/minecraft-mod` in
`syswonder/robonix` at commit
`e30b99b4ca606d5a3463e9c42d7df2de81d680c4`. It is distributed under the
MulanPSL-2.0 license included in this repository.
