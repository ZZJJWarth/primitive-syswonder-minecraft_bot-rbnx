# Minecraft Forge client primitive

This package exposes a real Forge 1.20.4 client through existing contracts.

## Capabilities

- `robonix/primitive/camera/snapshot`
  - Returns the latest client framebuffer as a JPEG `sensor_msgs/Image`.
  - Frame capture is rate-limited and JPEG encoding runs outside the render
    thread.
- `robonix/primitive/camera/depth_snapshot`
  - Returns GPU depth registered to the first-person view as a little-endian
    `32FC1` `sensor_msgs/Image` in metres, with sky pixels set to `NaN`.
  - Depth capture is rate-limited and linearization runs outside the render
    thread.
- `robonix/primitive/camera/rgb`
  - Publishes the first-person view as an uncompressed ROS 2 `rgb8` stream.
- `robonix/primitive/camera/depth`
  - Publishes little-endian `32FC1` metres in the RGB optical frame over ROS 2.
  - RGB and depth samples are acquired sequentially rather than as an atomic
    pair; either stream remains available if the other capture fails.
- `robonix/primitive/camera/extrinsics`
  - Publishes the fixed view-base to camera-optical transform with latched
    delivery.
- `robonix/primitive/chassis/move`
  - Applies forward/backward distances up to 32 blocks through Minecraft key
    mappings and stops using measured player displacement.
  - `forward_m` is a signed relative distance in blocks: positive moves
    forward and negative moves backward.
  - `rotate_deg` is a signed relative total yaw angle in degrees: positive
    turns left/counterclockwise and negative turns right/clockwise.
  - In velocity mode, `linear_x` and `linear_y` are player-relative speeds in
    blocks per second. Positive/negative values move forward/backward and
    strafe left/right respectively; planar magnitude is limited to `4.317`.
  - `angular_z` and `angular_y` are yaw and pitch rates in radians per second.
    Positive/negative values look left/right and down/up respectively; each
    resulting angle is its rate multiplied by `duration_sec`.
  - `duration_sec=0` selects one second in velocity mode; explicit values must
    be from `0.05` through `30`. `linear_z` and `angular_x` are unsupported.
  - Distance, turn, and velocity modes cannot be combined. For visual
    adjustment, change one axis at a time and inspect a new snapshot.
  - Must not be called directly while a navigation goal reports `navigating`;
    refine the navigation goal and wait until navigation is terminal.
- `robonix/primitive/camera/driver`
  - Waits for the Forge client Mod to join a world before becoming ready.
- `robonix/primitive/chassis/odom`
  - Publishes player pose with a configurable nominal eye height and derived
    planar velocity in a ROS ENU odometry frame. One block is one metre.
- `robonix/primitive/chassis/driver`
  - Shares the same Forge client connection as the camera primitive.
- `robonix/primitive/perception/objects`
  - Is declared by the colocated `minecraft_mod_perception` primitive so its
    provider namespace matches the perception contract.
  - Publishes the complete set of locally discovered chest, trapped chest,
    crafting table, and furnace blocks with exact registry names and block
    centers in the odometry frame.
  - A block is first added only when visible, remains in later snapshots after
    leaving the area, and is updated or removed when its location is revisited.
- `robonix/primitive/minecraft/inventory`
  - Returns all 36 main inventory slots plus offhand slot 40 using exact item
    registry names. Slots 0-8 are the hotbar, and empty slots report
    `minecraft:air` with count zero.
- `robonix/primitive/minecraft/break_block`
  - Breaks the visible, reachable `minecraft:dirt` or
    `minecraft:crafting_table` block currently under the crosshair through
    normal survival interaction. Aim at the intended block before calling it.
    When collection is requested, the Bot stops attacking, walks toward the
    former block centre, and waits for the matching synchronized inventory
    count to increase.
- `robonix/primitive/minecraft/deposit_item`
  - Deposits exactly one main-inventory `minecraft:dirt` or
    `minecraft:crafting_table` into an explicit, visible, reachable chest or
    trapped chest through normal menu clicks. Success requires the player count
    to fall by one and the container count to rise by one.

The three Minecraft item-action capabilities are declared by the colocated
`minecraft_mod_actions` primitive only after the Forge client has joined a
world. Break and deposit operations are mutually exclusive and reject invalid
game mode, target, reach, visibility, replacement, synchronization, and timeout
conditions through the capability error path.

The Mod binds its command interface to loopback only. The Python provider does
not launch or stop the Minecraft client.
