// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Produces a body-relative planar scan from exact Minecraft collision rays. */
public final class LidarScan {
    private static final double ANGLE_MIN = -Math.PI;
    private static final double RANGE_MIN = 0.05;
    private static final double[] SENSOR_HEIGHTS = {0.6, 1.5};
    private static final int MAX_RINGS = 4;

    private LidarScan() {}

    /** One named ring at a height above the player's feet. */
    public record Ring(String name, double height) {}

    /** Cast one full revolution at two body heights on the Minecraft client thread. */
    public static JsonObject capture(
            Minecraft minecraft,
            SimulationClock simulationClock,
            int rayCount,
            double rangeMax
    ) {
        Setup setup = prepare(minecraft, simulationClock, rayCount, rangeMax);
        double[] merged = new double[rayCount];
        java.util.Arrays.fill(merged, Double.POSITIVE_INFINITY);
        for (double height : SENSOR_HEIGHTS) {
            double[] distances = scanHeight(
                    setup.level(), setup.player(),
                    setup.x(), setup.y(), setup.z(), setup.yaw(),
                    rayCount, rangeMax, height);
            mergeNearest(merged, distances);
        }
        JsonObject result = new JsonObject();
        result.add("lidarScan", describeScan(setup, merged, rangeMax));
        return result;
    }

    /** Cast one full revolution per configured ring, plus a min-merged legacy scan. */
    public static JsonObject captureRings(
            Minecraft minecraft,
            SimulationClock simulationClock,
            int rayCount,
            double rangeMax,
            List<Ring> rings
    ) {
        if (rings == null || rings.isEmpty()) {
            throw new IllegalArgumentException("lidar_scan_rings requires at least one ring");
        }
        if (rings.size() > MAX_RINGS) {
            throw new IllegalArgumentException("lidar_scan_rings accepts at most " + MAX_RINGS + " rings");
        }
        Set<String> names = new HashSet<>();
        for (Ring ring : rings) {
            if (ring == null || ring.name() == null || ring.name().isBlank()) {
                throw new IllegalArgumentException("lidar ring names must be non-empty");
            }
            if (!names.add(ring.name())) {
                throw new IllegalArgumentException("lidar ring names must be unique");
            }
            if (!Double.isFinite(ring.height()) || ring.height() < 0.0 || ring.height() > 3.0) {
                throw new IllegalArgumentException("lidar ring heights must be from 0 through 3 metres");
            }
        }

        Setup setup = prepare(minecraft, simulationClock, rayCount, rangeMax);
        double eyeHeight = setup.player().getEyeHeight();
        double[] merged = new double[rayCount];
        java.util.Arrays.fill(merged, Double.POSITIVE_INFINITY);
        JsonArray ringScans = new JsonArray();
        for (Ring ring : rings) {
            double[] distances = scanHeight(
                    setup.level(), setup.player(),
                    setup.x(), setup.y(), setup.z(), setup.yaw(),
                    rayCount, rangeMax, ring.height());
            mergeNearest(merged, distances);
            JsonObject scan = describeScan(setup, distances, rangeMax);
            scan.addProperty("name", ring.name());
            scan.addProperty("height", ring.height());
            scan.addProperty("z", ring.height() - eyeHeight);
            ringScans.add(scan);
        }

        JsonObject result = new JsonObject();
        result.add("lidarScan", describeScan(setup, merged, rangeMax));
        result.add("lidarRings", ringScans);
        result.addProperty("eyeHeight", eyeHeight);
        return result;
    }

    private static Setup prepare(
            Minecraft minecraft,
            SimulationClock simulationClock,
            int rayCount,
            double rangeMax
    ) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        if (rayCount < 16 || rayCount > 1440) {
            throw new IllegalArgumentException("lidar ray_count must be from 16 through 1440");
        }
        if (!Double.isFinite(rangeMax) || rangeMax < 1.0 || rangeMax > 64.0) {
            throw new IllegalArgumentException("lidar range_max must be from 1 through 64 metres");
        }
        float partialTick = minecraft.getPartialTick();
        SimulationClock.SampleContext sample = simulationClock.capture(minecraft, partialTick);
        partialTick = sample.partialTick();
        double playerX = Mth.lerp((double) partialTick, player.xo, player.getX());
        double playerY = Mth.lerp((double) partialTick, player.yo, player.getY());
        double playerZ = Mth.lerp((double) partialTick, player.zo, player.getZ());
        double yaw = Math.toRadians(Mth.rotLerp(partialTick, player.yRotO, player.getYRot()));
        return new Setup(level, player, sample, partialTick, playerX, playerY, playerZ, yaw);
    }

    private static double[] scanHeight(
            ClientLevel level,
            LocalPlayer player,
            double playerX,
            double playerY,
            double playerZ,
            double yaw,
            int rayCount,
            double rangeMax,
            double height
    ) {
        double angleIncrement = Math.PI * 2.0 / rayCount;
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double[] distances = new double[rayCount];
        for (int index = 0; index < rayCount; index++) {
            double angle = ANGLE_MIN + index * angleIncrement;
            double directionX = forwardX * Math.cos(angle) + forwardZ * Math.sin(angle);
            double directionZ = forwardZ * Math.cos(angle) - forwardX * Math.sin(angle);
            Vec3 start = new Vec3(playerX, playerY + height, playerZ);
            Vec3 end = start.add(directionX * rangeMax, 0.0, directionZ * rangeMax);
            BlockHitResult hit = level.clip(new ClipContext(
                    start,
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player));
            distances[index] = hit.getType() == HitResult.Type.BLOCK
                    ? start.distanceTo(hit.getLocation())
                    : Double.POSITIVE_INFINITY;
        }
        return distances;
    }

    private static void mergeNearest(double[] merged, double[] distances) {
        for (int index = 0; index < merged.length; index++) {
            merged[index] = Math.min(merged[index], distances[index]);
        }
    }

    private static JsonObject describeScan(Setup setup, double[] distances, double rangeMax) {
        JsonArray ranges = new JsonArray();
        for (double distance : distances) {
            if (Double.isFinite(distance)) {
                ranges.add(distance);
            } else {
                ranges.add(JsonNull.INSTANCE);
            }
        }
        double angleIncrement = Math.PI * 2.0 / distances.length;
        JsonObject scan = new JsonObject();
        scan.addProperty("schemaVersion", 1);
        scan.addProperty("type", "minecraft_collision_lidar");
        scan.addProperty("tickId", setup.sample().tickId());
        scan.addProperty("partialTick", setup.sample().partialTick());
        scan.addProperty("simTimeNanos", setup.sample().simTimeNanos());
        scan.addProperty("captureSequence", setup.sample().captureSequence());
        scan.addProperty("timestampMs", System.currentTimeMillis());
        scan.addProperty("angleMin", ANGLE_MIN);
        scan.addProperty("angleMax", ANGLE_MIN + (distances.length - 1) * angleIncrement);
        scan.addProperty("angleIncrement", angleIncrement);
        scan.addProperty("rangeMin", RANGE_MIN);
        scan.addProperty("rangeMax", rangeMax);
        scan.add("ranges", ranges);
        return scan;
    }

    private record Setup(
            ClientLevel level,
            LocalPlayer player,
            SimulationClock.SampleContext sample,
            float partialTick,
            double x,
            double y,
            double z,
            double yaw
    ) {}
}