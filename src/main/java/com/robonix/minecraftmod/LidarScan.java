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

/** Produces a body-relative planar scan from exact Minecraft collision rays. */
public final class LidarScan {
    private static final double ANGLE_MIN = -Math.PI;
    private static final double RANGE_MIN = 0.05;
    private static final double[] SENSOR_HEIGHTS = {0.6, 1.5};

    private LidarScan() {}

    /** Cast one full revolution at two body heights on the Minecraft client thread. */
    public static JsonObject capture(
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

        double angleIncrement = Math.PI * 2.0 / rayCount;
        double playerX = Mth.lerp((double) partialTick, player.xo, player.getX());
        double playerY = Mth.lerp((double) partialTick, player.yo, player.getY());
        double playerZ = Mth.lerp((double) partialTick, player.zo, player.getZ());
        double yaw = Math.toRadians(Mth.rotLerp(partialTick, player.yRotO, player.getYRot()));
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        JsonArray ranges = new JsonArray();
        for (int index = 0; index < rayCount; index++) {
            double angle = ANGLE_MIN + index * angleIncrement;
            double directionX = forwardX * Math.cos(angle) + forwardZ * Math.sin(angle);
            double directionZ = forwardZ * Math.cos(angle) - forwardX * Math.sin(angle);
            double nearest = Double.POSITIVE_INFINITY;
            for (double height : SENSOR_HEIGHTS) {
                Vec3 start = new Vec3(playerX, playerY + height, playerZ);
                Vec3 end = start.add(directionX * rangeMax, 0.0, directionZ * rangeMax);
                BlockHitResult hit = level.clip(new ClipContext(
                        start,
                        end,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    nearest = Math.min(nearest, start.distanceTo(hit.getLocation()));
                }
            }
            if (Double.isFinite(nearest)) {
                ranges.add(nearest);
            } else {
                ranges.add(JsonNull.INSTANCE);
            }
        }

        JsonObject scan = new JsonObject();
        scan.addProperty("schemaVersion", 1);
        scan.addProperty("type", "minecraft_collision_lidar");
        scan.addProperty("tickId", sample.tickId());
        scan.addProperty("partialTick", sample.partialTick());
        scan.addProperty("simTimeNanos", sample.simTimeNanos());
        scan.addProperty("captureSequence", sample.captureSequence());
        scan.addProperty("timestampMs", System.currentTimeMillis());
        scan.addProperty("angleMin", ANGLE_MIN);
        scan.addProperty("angleMax", ANGLE_MIN + (rayCount - 1) * angleIncrement);
        scan.addProperty("angleIncrement", angleIncrement);
        scan.addProperty("rangeMin", RANGE_MIN);
        scan.addProperty("rangeMax", rangeMax);
        scan.add("ranges", ranges);
        JsonObject result = new JsonObject();
        result.add("lidarScan", scan);
        return result;
    }
}
