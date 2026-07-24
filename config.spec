config:
  # integer, milliseconds, default: 30000; must be > 0.
  # Maximum time to wait for the Forge client Mod to join a world.
  ready_timeout_ms: 30000

  # string, ROS frame ID, default: minecraft_camera_optical_frame.
  # Optical frame assigned to RGB, depth, and camera calibration messages;
  # initialization fails when ROS 2 rejects the configured frame or topic setup.
  camera_frame_id: minecraft_camera_optical_frame

  # string, ROS frame ID, default: minecraft_view_base.
  # Player view-base frame used as the odometry child and camera parent.
  base_frame_id: minecraft_view_base

  # string, ROS frame ID, default: minecraft_odom.
  # Fixed ENU frame used for player odometry and exact block observations.
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
