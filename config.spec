config:
  # integer, milliseconds, default: 30000; must be > 0.
  # Maximum time to wait for the Forge client Mod to join a world.
  ready_timeout_ms: 30000

  # string, ROS frame ID, default: minecraft_camera_optical_frame.
  # Optical frame assigned to RGB, depth, camera calibration, and the static
  # base-frame transform; initialization fails when ROS 2 rejects the
  # configured frame or topic setup.
  camera_frame_id: minecraft_camera_optical_frame

  # string, ROS frame ID, default: minecraft_view_base.
  # Player view-base frame used as the odometry/TF child and camera parent.
  base_frame_id: minecraft_view_base

  # string, ROS frame ID, default: minecraft_odom.
  # Fixed ENU frame used for player odometry, its dynamic TF, and exact block
  # observations.
  odom_frame_id: minecraft_odom

  # float, blocks/metres, default: 1.62; must be finite and within [0, 3].
  # Nominal player eye height above the view base; one Minecraft block is one metre.
  eye_height: 1.62

  # string, ROS topic, default: /minecraft/camera/rgb.
  rgb_topic: /minecraft/camera/rgb

  # string, ROS topic, default: /minecraft/camera/depth.
  depth_topic: /minecraft/camera/depth

  # string, ROS topic, default: /minecraft/camera/extrinsics.
  extrinsics_topic: /minecraft/camera/extrinsics

  # string, ROS topic, default: /minecraft/camera/intrinsics.
  intrinsics_topic: /minecraft/camera/intrinsics

  # string, ROS topic, default: /minecraft/chassis/odom.
  odom_topic: /minecraft/chassis/odom

  # string, ROS topic, default: /minecraft/perception/objects.
  objects_topic: /minecraft/perception/objects

  # string, ROS topic, default: /minecraft/lidar/scan.
  lidar_topic: /minecraft/lidar/scan

  # string, ROS frame ID, default: base_frame_id.
  # Scan angle zero points forward and positive angles point left.
  lidar_frame_id: minecraft_view_base

  # integer, rays/revolution, default: 180; must be within [16, 1440].
  lidar_ray_count: 180

  # float, metres, default: 16; must be finite and within [1, 64].
  lidar_range_max: 16.0

  # string, fully-qualified ROS topic, default: /cmd_vel.
  # Generic Navigation's guarded Twist output. The bridge is active only while
  # the chassis lifecycle is ACTIVE.
  cmd_vel_topic: /cmd_vel

  # float, seconds, default: 0.25; must be within [0.05, 1].
  # Each latest-command velocity is executed as this bounded Mod movement chunk.
  cmd_vel_command_duration_sec: 0.25

  # float, seconds, default: 0.5; must be >= command duration and <= 5.
  # A stale Nav2 command triggers an explicit stop.
  cmd_vel_timeout_sec: 0.5

  # float, blocks/metres per second, default: 1.2; must be within (0, 4.317].
  cmd_vel_max_linear: 1.2

  # float, radians per second, default: 1.0; must be within (0, pi].
  cmd_vel_max_angular: 1.0

  # float, blocks/metres, default: 1.5; must be finite and within [0.3, 1.5].
  # Half-width of the swept corridor checked on route-obstacle recovery.
  route_inspection_half_width_m: 1.5

  # float, blocks/metres, default: 0.25; must be finite and within [0.1, 1].
  # Distance between exact block-collision samples along the Navigation path.
  route_inspection_sample_spacing_m: 0.25

  # integer, blocks, default: 64; must be within [1, 256].
  # Maximum combined dirt and non-dirt obstacles accepted in one inspection.
  route_inspection_max_blocks: 64
