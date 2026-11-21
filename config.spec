config:
  # integer, milliseconds, default: 30000; must be > 0.
  # Maximum time to wait for the Forge client Mod to join a world.
  ready_timeout_ms: 30000

  # boolean, default: false.
  # Use Minecraft's authoritative 20 Hz gameTime clock for every sensor,
  # odometry, TF, and ground-truth header, and publish it on ROS /clock.
  use_sim_time: false

  # string, ROS topic, default: /minecraft/ground_truth/pose.
  # Exact Minecraft pose for benchmark scoring; never use it as a SLAM input.
  ground_truth_topic: /minecraft/ground_truth/pose

  # string, ROS frame ID, default: minecraft_camera_optical_frame.
  # Optical frame assigned to RGB, depth, camera calibration, and the dynamic
  # pitched base-frame transform; initialization fails when ROS 2 rejects the
  # configured frame or topic setup.
  camera_frame_id: minecraft_camera_optical_frame

  # string, ROS frame ID, default: minecraft_view_base.
  # Level, yaw-only player base used as the odometry/TF child and camera parent.
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

  # list of {name: string, height_m: float}, default: [] (legacy single scan).
  # Each entry adds one collision-ray ring above the player's feet. Names must
  # be unique; heights are finite and within [0, 3]. When non-empty the driver
  # requests per-ring scans and publishes lidar_rings as PointCloud2.
  lidar_rings: []

  # string, ROS topic, default: /minecraft/lidar/points.
  # Combined body-relative point cloud (x/y/z FLOAT32) for configured rings.
  lidar_rings_topic: /minecraft/lidar/points

  # string, ROS topic, default: /minecraft/lidar/scan_low.
  # Optional LaserScan view for a ring named "low".
  lidar_low_topic: /minecraft/lidar/scan_low

  # string, ROS topic, default: /minecraft/lidar/scan_high.
  # Optional LaserScan view for a ring named "high".
  lidar_high_topic: /minecraft/lidar/scan_high

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

  # float, blocks/metres, default: 0.8; must be finite and within [0.3, 1.5].
  # Half-width of the swept corridor checked on route-obstacle recovery.
  route_inspection_half_width_m: 0.8

  # float, blocks/metres, default: 0.25; must be finite and within [0.1, 1].
  # Distance between exact block-collision samples along the Navigation path.
  route_inspection_sample_spacing_m: 0.25

  # integer, blocks, default: 64; must be within [1, 256].
  # Maximum combined dirt and non-dirt obstacles accepted in one inspection.
  route_inspection_max_blocks: 64

  # integer, blocks, default: 2048; must be within [256, 16384].
  # Maximum block positions inspected per client tick during an area search.
  search_area_blocks_per_tick: 2048

  # integer, blocks, default: 262144; must be within [1, 1048576].
  # Maximum candidate blocks accepted for one search_area_for_object request.
  search_area_max_blocks: 262144

  # integer, raycasts, default: 64; must be within [8, 512].
  # Maximum line-of-sight checks performed per client tick during an area search.
  search_area_los_per_tick: 64

  # float, seconds, default: 8; must be finite and within [1, 15].
  # Default scan deadline used when the caller sends timeout_s=0.
  search_area_default_timeout_s: 8.0

  # integer, blocks, default: 32; must be within [1, 64].
  # Default result cap used when the caller sends max_results=0.
  search_area_default_max_results: 32

  # float, blocks/metres, default: 32; must be finite and within [1, 48].
  # Default eye-to-block-center distance used when max_distance_m=0.
  search_area_default_max_distance_m: 32.0

  # boolean, default: false.
  # Publish simulated onboard RTK GNSS fix and status from placed rtk_base_station blocks.
  rtk_enabled: false

  # string, ROS topic, default: /minecraft/gnss/rtk_fix.
  rtk_topic: /minecraft/gnss/rtk_fix

  # string, ROS topic, default: /minecraft/gnss/rtk_status.
  rtk_status_topic: /minecraft/gnss/rtk_status

  # string, ROS frame ID, default: campus_rtk.
  rtk_frame_id: campus_rtk

  # float, Hz, default: 5.0; must be finite and within [0.5, 20].
  rtk_stream_hz: 5.0

  # float, metres, default: 256.0; must be finite and within [10, 4096].
  rtk_max_range_m: 256.0

  # boolean, default: true.
  # Apply realistic fix-quality-dependent Gaussian noise to RTK coordinates.
  rtk_noise_enabled: true
