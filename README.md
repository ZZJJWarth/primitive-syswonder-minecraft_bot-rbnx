# Robonix Minecraft primitive

This package connects a real Minecraft Java client to Robonix. A
Forge Mod captures the rendered framebuffer, applies bounded player controls,
and serves a loopback JSON-lines interface. The Python package exposes that
client through existing camera, chassis, perception, and Minecraft primitive
contracts.

## Versions

- Minecraft Java 1.20.4
- Forge 49.2.0
- Eclipse Temurin OpenJDK 17.0.19+10
- ROS 2 Lyrical on Ubuntu 26.04 (Python 3.14); Jazzy on Ubuntu 24.04 (Python 3.12)
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

Copy `build/libs/robonix_minecraft-0.3.0.jar` into the Forge 1.20.4 client's
`mods/` directory, then launch the client and join the target world or server.
The camera and player controls run on the client. The optional elevator
controller adds a custom block, which requires the Mod on the game server
as well. Dedicated servers expose a separate loopback elevator interface at
`127.0.0.1:25577`; elevator Robonix controls it directly without a game client.
The legacy client-to-server Forge control path still requires operator level 2.
Set the Forge launcher profile's Java executable to
`$HOME/.local/share/jdks/jdk-17.0.19+10/bin/java`.

The [elevator controller guide](docs/elevator-controller.md) describes the
redstone block, local control script, two-floor command-block setup and
headless transport tests.
For a dedicated server, follow the [multiplayer deployment steps](docs/elevator-multiplayer.md).

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

The client Mod listens on `127.0.0.1:25576`. The independent dedicated-server
elevator interface listens on `127.0.0.1:25577`. The package reuses a client already
listening there instead of launching another one. For a complete deployment,
use `syswonder/robot-syswonder-minecraft_bot`, which owns the player URDF,
Soma description, service selection, skill selection, and runtime parameters.

Set `RBNX_MINECRAFT_USE_NVIDIA=1` to render on a separate, rootless NVIDIA
Xorg display (`:48` by default). No `sudo` or physical monitor is needed. The
display runs on a dedicated seat, disables physical input/GPU auto-discovery,
and uses `-novtswitch -sharevts` so the local desktop remains usable. This
requires the native Xorg binary and NVIDIA Xorg/GLX modules, `nvidia-smi`,
`xauth`, `mcookie`, `flock`, and `xdpyinfo`. Display setup failures stop startup.
Use `0` (the default) to use the inherited `DISPLAY` directly.

Boot creates and cleans up its own display automatically, or reuses an
existing NVIDIA display. To keep a display running in a separate tmux window,
run `./scripts/run-nvidia-display.sh` there; Ctrl-C stops it. Both `48` and `:48`
are accepted for `MC_NVIDIA_DISPLAY`. Authentication uses
`${XDG_RUNTIME_DIR:-$HOME/.cache/robonix}/minecraft-xauth-48`, independently of
the desktop's Xauthority. Boot also reuses a client already listening on the
Mod command port. See [off-screen rendering](docs/offscreen-rendering.md).

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

### Server chat

The package also starts a Minecraft-chat frontend automatically. A server
player can submit natural-language tasks without running `rbnx chat`:

```text
@bot 探索附近区域
@bot 现在在哪里
@bot stop
```

The Forge Mod captures only signed player messages beginning with
`RBNX_MINECRAFT_CHAT_PREFIX` (default `@bot`). A local Python bridge forwards
the instruction to Liaison, keeps one Pilot session per player UUID, and sends
concise progress and final answers back as the bot player. Set
`RBNX_MINECRAFT_CHAT_ALLOWED_PLAYERS` to a comma-separated list of player UUIDs
or exact player names on shared servers. An empty list permits every player.
`stop`, `cancel`, `abort`, `停止`, and `取消` request cancellation of that
player's current Pilot turn.

The bridge uses `ROBONIX_LIAISON_ENDPOINT` (default `127.0.0.1:50081`) and the
existing loopback-only Mod command interface. Set
`RBNX_MINECRAFT_CHAT_ENABLED=0` to disable it.

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
services can associate each frame with the corresponding player pose. In
simulation-time mode, rendered sensors use the Minecraft tick plus the render
partial-tick fraction instead of quantizing every capture to the 50 ms world
tick. RGB and depth from one render cycle share a `renderFrameId` and one
canonical sub-tick capture identity, then asynchronous encoders are paired by
that integer ID. The collision LiDAR uses the same continuous clock and
interpolates player position and yaw to its own independent sampling fraction.
The odometry frame uses ROS ENU
coordinates (`x` east, `y` north), treats one block as one metre, and places
`minecraft_view_base` at the configurable nominal eye height. This navigation
base contains yaw but stays level; player pitch is carried by the dynamic
camera extrinsics so depth ground points and the planar LiDAR retain their
correct geometry.
Each odometry sample is also broadcast as the dynamic TF
`minecraft_odom → minecraft_view_base` with the same timestamp and pose. The
timestamp-matched pitched view-base-to-camera transform is broadcast over
`/tf`, providing the standard TF chain consumed by generic Robonix mapping.
The active chassis also subscribes to generic Navigation's guarded `/cmd_vel`
output. It validates planar Twist limits, applies the newest command in bounded
0.25-second Mod chunks, and sends an explicit stop after a 0.5-second command
timeout or any chassis lifecycle stop.

The provider also publishes `/minecraft/lidar/scan` through
`robonix/primitive/lidar/lidar`. This body-relative 360-degree
`sensor_msgs/LaserScan` combines exact Minecraft collision raycasts at waist
and head height. It is independent of the rendered depth buffer, so side and
rear obstacles do not require turning the first-person camera.

For a stationary Chronos Linux-reference test, the camera also supports
`clock_emulation.realsense.time_base: host_monotonic`. A read-only collector
emits device observations separately from independent score evidence; the
existing game-time behavior remains the default. See
[the camera test instructions](docs/chronos-camera-test.md) for building,
synthetic verification and manually running the collector against Minecraft.

For clock-synchronization benchmarks, the optional `clock_emulation` config
adds raw device-clock shadows. The camera shadows emulate RealSense hardware
uptime; the LiDAR shadow emulates a PTP-disciplined clock with configurable
initial lock, locked residual error, holdover drift, and step or slew
relocking. Established capability topics use exact Minecraft truth time by
default. `mapping_test.primary_timestamp_mode: corrupted` instead applies
configurable common-domain residual offsets directly to those topics, so an
existing RTAB-Map deployment can demonstrate the effect before a correction
module is available. `device` puts those capability topics in the emulated
RealSense uptime and LiDAR PTP domains for Chronos integration:

```yaml
clock_emulation:
  enabled: true
  seed: 42
  realsense:
    initial_uptime_sec: 5
    drift_ppm: 50
    jitter_ms: 1
    reset_at_sec: null
  lidar_ptp:
    initial_offset_ms: 30
    oscillator_drift_ppm: 20
    locked_offset_us: 100
    locked_jitter_us: 50
    lock_after_sec: 5
    lose_lock_at_sec: 120
    relock_at_sec: 180
    relock_duration_sec: 10
    relock_mode: slew
  mapping_test:
    primary_timestamp_mode: truth  # truth | corrupted | device
    camera_offset_ms: 100
    lidar_offset_ms: 0
```

In `corrupted` mode, RGB, depth, and CameraInfo receive the same camera offset,
while LaserScan receives the LiDAR offset. A useful initial comparison for the
default 20 Hz camera and 10 Hz LiDAR is a truth baseline followed by a run with
the camera at `+100 ms` and the LiDAR at `0 ms`. Use a fresh RTAB-Map database
for each run so an existing optimized map does not hide the difference.
`corrupted` and `device` modes require `clock_emulation.enabled: true`.

In `device` mode the RGB, depth, and CameraInfo capability declarations carry
the `minecraft_realsense` time descriptor, while LaserScan carries
`minecraft_lidar_ptp`. The same primitive implements
`robonix/primitive/clock/get_status` and
`robonix/primitive/clock/watch_status`; these expose the emulated LiDAR servo
state, Grandmaster identity, measured offset, and uncertainty without exposing
Minecraft capture truth. The current Chronos runtime does not automatically consume this status or
supervise PTP. The simulated Grandmaster uses game time; a `LOCKED` status does
not establish synchronization to Chronos's Linux reference.

The separate benchmark-shadow topics remain available at
`/minecraft/time_sync/raw/camera/{rgb,depth,camera_info}` and
`/minecraft/time_sync/raw/lidar/scan`. Each raw sample has a JSON truth record
on `/minecraft/time_sync/diagnostics`, including `capture_truth_ns`, device
timestamp, injected offset, drift, sequence, and lock state. In corrupted mode,
the deliberately biased primary stamps are reported there as `camera_primary`
and `lidar_primary` records as well. A synchronization module should consume
the raw topics and publish corrected common-clock topics; RTAB-Map can then be
remapped to those corrected outputs. Topic names can be overridden under
`clock_emulation.topics` with the keys `rgb`, `depth`, `camera_info`, `lidar`,
and `diagnostics`.

The provider also publishes exact semantic observations for visible chests,
trapped chests, crafting tables, furnaces, empty cauldrons, and water
cauldrons, plus currently visible live zombies identified by Entity UUID.
Discovery is local and occurs
during exploration rather than preloading the world. Discovered blocks remain
in the snapshot after the player leaves, while revisiting a changed location
updates or removes its entry. Scene consumes this stream alongside visual
perception, so exact Minecraft blocks do not depend on YOLO vocabulary.
Zombie entries update in place while moving and disappear from the complete
snapshot when dead, out of range, or no longer visible.

Pilot can also compose the Minecraft state and action capabilities directly:

- `robonix/primitive/minecraft/inventory` reports main inventory slots 0-35
  (hotbar 0-8) and offhand slot 40 with exact item registry names.
- `robonix/primitive/minecraft/select_inventory_item` selects the largest
  matching main-inventory stack into the main hand. Hotbar items are selected
  directly; slots 9-35 are swapped into the current hotbar slot and verified
  for three synchronized client ticks. For an empty bottle, pass the exact
  item name `minecraft:glass_bottle`.
- `robonix/primitive/minecraft/get_state` returns a fresh timestamped compact
  snapshot of the player's Minecraft-world pose and status without changing
  the player or world, together with its converted ROS navigation pose. A
  Pilot deployment may inject this result into every planning round as
  authoritative live state. Generic navigation uses the `navigation_*`
  fields; native Minecraft coordinate targets use
  `robonix/service/navigation/navigate_world`.
- `robonix/primitive/minecraft/aim_at_block` gradually turns toward an exact
  Scene object at bounded yaw and pitch rates. Pass the object's stable ID
  unchanged; the provider resolves it to the native Minecraft block position.
  Scene coordinates and `scene/goal_near` approach poses are rejected. It succeeds only
  after the live crosshair stably hits the visible, interaction-reachable block
  with the expected registry name, and does not interact with that block.
- `robonix/primitive/minecraft/break_block` targets the block currently under
  the crosshair, requires survival mode and either `minecraft:dirt` or
  `minecraft:crafting_table`, and can wait for matching synchronized pickup
  verification. It stops attacking and walks toward the former block centre
  when collection is requested. Aim at the intended block before calling it.
- Route-obstacle recovery uses `inspect_route_path` followed by
  `clear_route_path`; the bounded path inspection retains an opaque
  90-second handle and clearing can remove every verified dirt candidate in
  the corridor. Minecraft coordinates remain private to the provider.
- Path-aware recovery is available through `inspect_route_path` and
  `clear_route_path`. A recovery skill passes path points transformed into
  `minecraft_odom`. The provider supplies its configured footprint half-width,
  sample spacing, and obstacle limit, while the Mod derives the body layers
  from the live player height. It samples the swept corridor, identifies exact
  `minecraft:dirt` blocks, and clears only those candidates after navigation
  has stopped. A stale traversed path prefix is discarded by aligning the live
  player to the nearest route segment. The support layer is never cleared;
  non-dirt blockers remain for a fresh navigation decision.
- `robonix/primitive/minecraft/search_area_for_object` searches a known area
  for objects that Scene has not registered yet. Pass the region `points_xy`
  from `scene/list_regions` unchanged. The Mod scans only loaded chunks around
  the robot and returns just the blocks that have a clear line of sight within
  32 metres by default. Whitelisted objects such as chests are registered into
  Scene, so a subsequent `navigate_to_object` can use the returned `object_id`;
  other labels return native coordinates with `registered=false`.
- `robonix/primitive/minecraft/attack_entity` accepts a Scene-selected
  `minecraft:zombie`, or a player permitted by the configured combat mode,
  `minecraft:player`, object ID and resolves it to the live Entity UUID. The Mod
  performs bounded pursuit, gradual aiming, cooldown-aware attacks, and death
  verification. It stops on low health, timeout, disappearance, or excessive
  chase distance. Player combat is disabled by default. Use
  `RBNX_MINECRAFT_PLAYER_ATTACK_MODE=allowlist` with permitted UUIDs/names in
  `RBNX_MINECRAFT_ATTACKABLE_PLAYERS`, or use mode `all` for every other visible
  player. Set `RBNX_MINECRAFT_PLAYER_CHASE_MODE=until_death` to remove the normal
  player-combat timeout, travel-distance, attack-attempt, and unsafe-drop limits.
  Low health, death, target disappearance, disconnection, and policy changes
  still stop combat. The local player and all other entity types remain rejected.
- `robonix/primitive/minecraft/deposit_item` accepts a stable chest object ID
  and transfers an exact positive quantity of any registered item ID, reporting
  synchronized player and container counts. It fails before mutation when the
  main inventory has too few items or the chest lacks capacity. It accepts
  and resolves it through the primitive's current native BlockPos registry.
- `robonix/primitive/minecraft/withdraw_item` accepts the same stable chest
  object ID, any exact registered item name, and a positive quantity. It
  transfers exactly that quantity or fails before mutation when the chest has
  none, has too few, or the player's main inventory lacks capacity.
- `robonix/primitive/minecraft/inspect_container` accepts the same stable chest
  object ID, opens the chest through the normal interaction, returns every
  slot's exact item ID and count without moving anything, and closes the menu.
- `robonix/primitive/minecraft/fill_water_bottles` accepts a stable water
  cauldron object ID and a count from one through three. It checks water
  level, main-inventory glass bottles, and inventory capacity before using the
  cauldron, then verifies all bottle and level deltas after every interaction.
  It retains an internal bottle-selection fallback, but a visible workflow can
  call `select_inventory_item(minecraft:glass_bottle)` after withdrawal first.

These capabilities use ordinary client state and interactions rather than
changing the world directly. Targets must be loaded, visible, and within
interaction reach. Only one aim, attack, item-selection, break, deposit, withdraw, or bottle-fill
operation runs at a time;
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

- `RBNX_MINECRAFT_MOD_PORT`: client loopback command port, default `25576`.
- `RBNX_MINECRAFT_ELEVATOR_PORT`: dedicated-server elevator port, default `25577`; set on the server process, `0` disables it.
- `RBNX_MINECRAFT_CHAT_ENABLED`: start the server-chat frontend, default `1`.
- `RBNX_MINECRAFT_CHAT_PREFIX`: addressed-message prefix, default `@bot`.
- `RBNX_MINECRAFT_CHAT_ALLOWED_PLAYERS`: optional comma-separated UUID/name
  allowlist; empty permits all players.
- `RBNX_MINECRAFT_CHAT_PROGRESS_SEC`: minimum interval between non-critical
  progress messages, default `5`.
- `ROBONIX_LIAISON_ENDPOINT`: Liaison gRPC endpoint, default
  `127.0.0.1:50081`.
- `RBNX_MINECRAFT_ROS_SETUP`: ROS 2 `setup.bash`; defaults to an installed
  Lyrical, Jazzy, or Humble setup (prefers `ROS_DISTRO` when set).
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
- `RBNX_MINECRAFT_USE_SIM_TIME`: when `true`, publishes Minecraft's monotonic
  `gameTime` plus render partial-tick time on `/clock`. RGB, depth, CameraInfo,
  and LiDAR carry sub-tick capture stamps; RGB-D output is rejected unless both
  frames have the same complete capture time. Odometry, TF, and ground truth
  retain their authoritative world-tick stamps. Default `false` preserves the
  wall-clock deployment mode.
- `RBNX_MINECRAFT_GROUND_TRUTH_TOPIC`: exact pose stream reserved for benchmark
  scoring, default `/minecraft/ground_truth/pose`.
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
- `RBNX_MINECRAFT_ENTITY_RADIUS`: visible attackable-entity scan radius, default
  `16`.
- `RBNX_MINECRAFT_PLAYER_ATTACK_MODE`: `disabled` (default), `allowlist`, or
  `all`. `all` exposes and permits every other visible player.
- `RBNX_MINECRAFT_ATTACKABLE_PLAYERS`: comma-separated player UUID/name
  allowlist used in `allowlist` mode; an empty list permits no players.
- `RBNX_MINECRAFT_PLAYER_CHASE_MODE`: `bounded` (default) retains normal combat
  limits; `until_death` pursues a permitted player without timeout, origin
  distance, target displacement, attack-attempt, or unsafe-drop limits.
- `RBNX_MINECRAFT_ALLOW_PLAYER_ATTACK`: legacy compatibility switch. `true` or
  `1` selects `allowlist` when `RBNX_MINECRAFT_PLAYER_ATTACK_MODE` is unset.
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
