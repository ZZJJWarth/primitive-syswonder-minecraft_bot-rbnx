# Minecraft Forge client primitive

This package exposes a real Forge 1.20.4 client through existing contracts.

## Capabilities

- `robonix/primitive/lidar/lidar`
  - Publishes a best-effort `sensor_msgs/LaserScan` generated with exact
    Minecraft collision raycasts rather than the rendered depth buffer.
  - The full 360-degree scan combines waist and head-height rays, uses the
    chassis base frame, and reports no-return rays as positive infinity.
  - Default acquisition is 180 rays at 5 Hz with a 16 metre maximum range.
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
  - Broadcasts the same fixed transform over `/tf_static` for standard ROS
    consumers.
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
  - Broadcasts every odometry pose with the same timestamp as the dynamic TF
    `minecraft_odom → minecraft_view_base` (using the configured frame names),
    enabling generic mapping and Nav2 providers without a Minecraft-specific
    transform adapter.
  - While the chassis lifecycle is active, a bounded `/cmd_vel` bridge accepts
    planar ROS Twist commands from generic Navigation. It executes only short
    latest-command chunks and explicitly stops when commands become stale,
    during deactivation, or during shutdown.
  - Shares the aggregate `minecraft_mod` provider and Forge client connection
    with the camera, lidar, perception, and Minecraft action capabilities.
  - In `use_sim_time` mode, the provider publishes Minecraft `gameTime` on
    `/clock`, stamps all streams with the capture tick, and publishes an exact
    scoring-only pose on `/minecraft/ground_truth/pose`.
- `robonix/primitive/perception/objects`
  - Is declared by the aggregate `minecraft_mod` provider.
  - Publishes the complete set of locally discovered chest, trapped chest,
    crafting table, furnace, empty cauldron (`minecraft:cauldron`), and water
    cauldron (`minecraft:water_cauldron`) blocks with exact registry names and
    block centers and one-block geometry in the odometry frame. Each globally
    stable object ID is also retained in the primitive's native BlockPos map.
  - Also publishes visible live `minecraft:zombie` entities with stable UUID
    identity, moving centre pose, and live bounding-box geometry. Transient
    entities disappear from the complete snapshot when dead, out of range, or
    no longer visible.
  - A block is first added only when visible, remains in later snapshots after
    leaving the area, and is updated or removed when its location is revisited.
- `robonix/primitive/minecraft/inventory`
  - Returns all 36 main inventory slots plus offhand slot 40 using exact item
    registry names. Slots 0-8 are the hotbar, and empty slots report
    `minecraft:air` with count zero.
- `robonix/primitive/minecraft/select_inventory_item`
  - Selects the largest stack matching one exact registry name into the main
    hand. A stack already in slots 0-8 is selected directly; a stack in slots
    9-35 is swapped into the current hotbar slot with the native pick-item
    operation. Success is returned only after the main hand is stable for
    three client ticks.
  - `minecraft:glass_bottle` selects an empty bottle. If the name is invalid or
    absent, the capability returns a distinct registered-name or inventory
    error without pretending that selection succeeded.
- `robonix/primitive/minecraft/get_state`
  - Returns a fresh timestamped snapshot of the player's Minecraft-world
    position, block position, view angles, dimension, grounded state, health,
    food, and game mode without changing the player or world.
  - Also returns `navigation_frame`, `navigation_x`, `navigation_y`, and
    `navigation_yaw_rad` after converting Minecraft east/up/south coordinates
    to ROS ENU. Use these fields with generic navigation. Native Minecraft
    targets must go through `navigation/navigate_world`; do not relabel their
    coordinates by merely changing or clearing a `PoseStamped` frame.
- `robonix/primitive/minecraft/aim_at_block`
  - Accepts a stable object ID returned by Scene and an expected registry
    name. The primitive resolves the ID to its own native BlockPos. Unknown or
    stale IDs are rejected and require a fresh `scene/list_objects` query.
  - Succeeds only after the live Minecraft crosshair hits that visible,
    interaction-reachable block for three consecutive client ticks. It does
    not attack, use, place, or otherwise change the target block.
- `robonix/primitive/minecraft/break_block`
  - Breaks the visible, reachable `minecraft:dirt` or
    `minecraft:crafting_table` block currently under the crosshair through
    normal survival interaction. Aim at the intended block before calling it.
    When collection is requested, the Bot stops attacking, walks toward the
    former block centre, and waits for the matching synchronized inventory
    count to increase.
- `robonix/primitive/minecraft/inspect_route_path`
  - Accepts a bounded sequence of planar points already transformed into
    `minecraft_odom`. The provider applies its configured footprint half-width,
    sampling interval, and obstacle limit; the Mod derives the first body layer
    from the live player position. It checks both body layers across the full
    width and returns an opaque handle for the exact dirt candidates plus a
    count of non-clearable collision blocks.
  - A planner snapshot may retain the route prefix already traversed by the
    robot. The Mod aligns the live player to the nearest path segment, rejects
    the request only when the entire route is more than two metres away, and
    scans forward from that aligned segment instead of requiring the original
    first point to remain near the player.
  - It never checks or clears the support block below the live body layer. Navigation
    frame points must be transformed explicitly; `map` coordinates and raw
    Scene coordinates are rejected by the caller before this primitive.
- `robonix/primitive/minecraft/clear_route_path`
  - Consumes one short-lived path inspection handle and sequentially
    revalidates, aims at, and breaks every inspected `minecraft:dirt` target.
    Non-dirt blocks are reported and never changed. The handle is consumed even
    when a target changes, so callers must inspect a fresh path after any
    partial or failed clear.
  - Stop as soon as a route exists and clear no more than the provider's
    configured obstacle limit per recovery attempt.
- `robonix/primitive/minecraft/attack_entity`
  - Resolves one Scene-selected `minecraft:zombie`, or `minecraft:player`
    permitted by disabled/allowlist/all combat mode, ID to its native Entity UUID
    and owns the local combat loop. It tracks the moving target, approaches
    within normal reach, waits for attack cooldown, uses the normal client
    attack interaction, and returns only after observing target death.
  - Combat aborts below four health points, after 25 seconds or 30 attack
    attempts, when either chase bound is exceeded, or when the UUID disappears
    before death is confirmed. Cleanup always restores forward input.
  - Players, villagers, pets, skeletons, and every other non-whitelisted entity
    type are rejected before any attack.
- `robonix/primitive/minecraft/deposit_item`
  - Deposits an exact positive quantity of any registered item into an explicit,
    visible, reachable chest or trapped chest through normal menu clicks. It
    fails before mutation when the item ID is not registered, the main inventory
    has too few items, or the chest lacks capacity. Success requires matching
    player and container count changes.
- `robonix/primitive/minecraft/withdraw_item`
  - Withdraws an exact positive quantity of any registered item from an
    explicit, visible, reachable chest or trapped chest. It fails before
  mutation when the item is absent, the available quantity is insufficient,
  or the player's main inventory lacks capacity. Success requires matching
  player and container count changes.
- `robonix/primitive/minecraft/inspect_container`
  - Opens an explicit, visible, reachable chest or trapped chest, waits for its
    menu to synchronize, and returns every slot's exact registered item name
    and count without moving any item. The menu is closed after success or
    failure.
- `robonix/primitive/minecraft/fill_water_bottles`
  - Fills exactly one through three `minecraft:glass_bottle` items from an
    explicit, visible, reachable `minecraft:water_cauldron` using normal
    main-hand interactions. It validates water level, bottle count, and
    inventory capacity before mutation, then verifies the empty-bottle,
    water-bottle, and cauldron-level deltas after every fill.
  - The operation can select glass bottles internally. For an explicit Pilot
    workflow, call `select_inventory_item` with `minecraft:glass_bottle` after
    `withdraw_item`, verify `selected=true`, then call this capability.
  - `aim_at_block`, `deposit_item`, `withdraw_item`, and
    `fill_water_bottles` all accept the same stable Scene object ID. Copy that
    ID unchanged between calls; never derive an interaction target from Scene
    coordinates or a navigation approach pose.
  - Before depositing into a Scene-selected chest, call `aim_at_block` with
    that object ID and require `aimed=true`; then pass the same ID to the item
    action.
  - A reach, visibility, wrong-target, rejected-interaction, synchronization,
    or timeout error is contextual. Call
    `robonix/service/minecraft/manual/lookup` with the exact failure before any
    retry. Never repeat identical deposit arguments without new evidence and
    manual guidance.

The Minecraft state and action capabilities are declared by the aggregate
`minecraft_mod` provider. Its package lifecycle does not activate until the
Forge client has joined a world. Aim, attack, break, and deposit operations are
mutually exclusive and reject invalid game mode, target, reach, visibility,
replacement, synchronization, and timeout conditions through the capability
error path.

## Target identity provenance

For every Scene-selected Minecraft block, first call
`robonix/system/scene/list_objects`, select the intended object by its label or
id, and copy that stable `id` into the Minecraft action unchanged.

- A camera snapshot may confirm the target visually, but it does not produce a
  stable object ID.
- `robonix/primitive/minecraft/get_state` reports the player's position, not
  the target block's position; neither it nor remembered coordinates may be
  used to construct an action target.
- Scene coordinates and `goal_near` poses are for generic navigation only.
  Stop and refresh `scene/list_objects` when an object ID is unknown or stale.

The same identity rule applies to combat: select a live
`minecraft:zombie` from `scene/list_objects`, pass its unchanged ID to
`attack_entity`, and let the primitive reacquire its UUID every client tick.

The cauldron workflow is therefore:
`scene/list_objects` -> copy the selected `minecraft:water_cauldron.id`
unchanged -> `select_inventory_item(item=minecraft:glass_bottle)` ->
`aim_at_block` -> `fill_water_bottles`. Pass the same ID unchanged to both
block operations.

The Mod binds its command interface to loopback only. The Python provider does
not launch or stop the Minecraft client.
