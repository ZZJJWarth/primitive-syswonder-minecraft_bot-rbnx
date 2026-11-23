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
    private static final double STAIR_FOOT_HEIGHT = 0.15;
    private static final double STAIR_LOW_HEIGHT = 0.65;
    private static final double STAIR_HIGH_HEIGHT = 1.50;
    private static final double STAIR_TRACE_STEP_M = 0.50;
    private static final double STAIR_TRACE_MAX_HEIGHT_M = 8.10;
    private static final double STAIR_MIN_RUN_M = 0.28;
    private static final double STAIR_MAX_RUN_M = 1.65;
    // Downward-pitched cliff / pit-edge sensor (pure geometric collider raycast, zero block peeking).
    // Distinguishes walkable 0.5 m / 1.0 m downward stairs (step drop <= 1.0 m per probe) from
    // >= 2-block deep pits / cliffs (step drop >= 1.55 m) even inside the horizontal camera's blind spot.
    private static final double CLIFF_SCAN_MIN_M = 0.45;
    private static final double CLIFF_SCAN_MAX_M = 2.85;
    private static final double CLIFF_SCAN_STEP_M = 0.40;
    private static final double CLIFF_DROP_THRESHOLD_M = 1.55;
    private static final double CLIFF_PROBE_HEADROOM_M = 0.55;
    private static final double CLIFF_PROBE_DEPTH_M = 3.00;
    private static final int MAX_RINGS = 4;

    private LidarScan() {}

    /** One named ring at a height above the player's feet. */
    public record Ring(String name, double height) {}

    /** Cast one single horizontal collision ray at (angle, height above feet). */
    private static double castSingleRay(
            ClientLevel level,
            LocalPlayer player,
            double playerX,
            double playerY,
            double playerZ,
            double forwardX,
            double forwardZ,
            double angle,
            double rangeMax,
            double height
    ) {
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
        return hit.getType() == HitResult.Type.BLOCK
                ? start.distanceTo(hit.getLocation())
                : Double.POSITIVE_INFINITY;
    }

    /**
     * Pure geometric multi-height stair & headroom tracer (zero block-type peeking).
     *
     * Along each ray angle, compares pure collision distances at foot (0.30 m) and low (0.60 m):
     * - Walls, closed doors, chests (0.875 m), fences (1.5 m), and stair sides present vertical
     *   faces where d(0.60) - d(0.30) < 0.28 m -> kept immediately as solid obstacles.
     * - When d(0.60) - d(0.30) is in [0.28, 1.65] m (a stepped half-block tread), traces upward
     *   in +0.50 m height increments along the same ray until either:
     *     (a) the staircase reaches the open top landing (d_next == inf -> ray is clear!),
     *     (b) a real vertical wall or low ceiling is reached (d_next - d_cur < 0.28 m -> wall at d_cur), or
     *     (c) the staircase tops out onto an upper floor with a distant wall (d_next - d_cur > 1.65 m -> wall at d_next).
     */
    private static void filterWalkableStairRays(
            Setup setup,
            int rayCount,
            double rangeMax,
            double[] foot,
            double[] low,
            double[] high,
            double[] outLow,
            double[] outHigh
    ) {
        double angleIncrement = Math.PI * 2.0 / rayCount;
        double forwardX = -Math.sin(setup.yaw());
        double forwardZ = Math.cos(setup.yaw());

        for (int i = 0; i < rayCount; i++) {
            double d0 = foot[i];
            double d1 = low[i];
            double d2 = high[i];
            double fl = d1;
            double fh = d2;

            boolean step1 = Double.isFinite(d1)
                    && Double.isFinite(d0)
                    && (d1 - d0) >= STAIR_MIN_RUN_M
                    && (d1 - d0) <= STAIR_MAX_RUN_M
                    && (!Double.isFinite(d2) || (d2 - d1) >= 0.45);
            if (step1) {
                double angle = ANGLE_MIN + i * angleIncrement;
                double dCur = d1;
                double hCur = STAIR_LOW_HEIGHT;
                double wallDist = Double.POSITIVE_INFINITY;

                while (hCur + STAIR_TRACE_STEP_M <= STAIR_TRACE_MAX_HEIGHT_M) {
                    double hNext = hCur + STAIR_TRACE_STEP_M;
                    double dNext = castSingleRay(
                            setup.level(), setup.player(),
                            setup.x(), setup.y(), setup.z(),
                            forwardX, forwardZ, angle, rangeMax, hNext);
                    if (!Double.isFinite(dNext)) {
                        // Cleared the top of the staircase into open space!
                        wallDist = Double.POSITIVE_INFINITY;
                        break;
                    }
                    double delta = dNext - dCur;
                    if (delta < STAIR_MIN_RUN_M) {
                        // Hit a real vertical wall (e.g. landing wall) or low ceiling/beam!
                        wallDist = Math.min(dCur, dNext);
                        break;
                    }
                    if (delta > STAIR_MAX_RUN_M) {
                        // Staircase topped out onto an upper floor; dNext is a distant wall on upper floor.
                        wallDist = dNext;
                        break;
                    }
                    // Continuous ascending staircase tread: advance and keep tracing upward.
                    dCur = dNext;
                    hCur = hNext;
                    wallDist = Double.POSITIVE_INFINITY;
                }

                // Clear low ring up to real wall; update high ring only if high hit was part of the stairs.
                fl = wallDist;
                if (Double.isFinite(d2) && (!Double.isFinite(wallDist) || d2 < wallDist - 0.15)) {
                    fh = wallDist;
                }
            }
            outLow[i] = fl;
            outHigh[i] = fh;
        }
        applyDownwardCliffScan(setup, rayCount, outLow);
    }

    /**
     * Pure-geometric downward cliff / pit-edge scanner (virtual negative-obstacle LiDAR channel).
     *
     * Probes ground elevation every 0.40 m outward from 0.45 m to 2.85 m along every 2nd ray
     * (<= 0.10 m arc spacing at 2.85 m):
     * - Flat road (drop ~0.0 m), ascending/descending 0.5 m stairs (step drop 0.5 m), and
     *   1-block steps (step drop 1.0 m) all stay below CLIFF_DROP_THRESHOLD_M (1.55 m) -> PASSABLE.
     * - Any >= 2-block deep dug pit, hole, trench, or cliff edge has step drop >= 1.55 m ->
     *   fused into outLow at the exact pit rim distance so local_mapping & Nav2 route around it.
     */
    private static void applyDownwardCliffScan(
            Setup setup,
            int rayCount,
            double[] outLow
    ) {
        double angleIncrement = Math.PI * 2.0 / rayCount;
        double forwardX = -Math.sin(setup.yaw());
        double forwardZ = Math.cos(setup.yaw());
        int stride = rayCount >= 180 ? 2 : 1;

        for (int i = 0; i < rayCount; i += stride) {
            double wallLimit = outLow[i];
            double maxProbeDist = Double.isFinite(wallLimit)
                    ? Math.min(CLIFF_SCAN_MAX_M, wallLimit - 0.15)
                    : CLIFF_SCAN_MAX_M;
            if (maxProbeDist < CLIFF_SCAN_MIN_M) {
                continue;
            }
            double angle = ANGLE_MIN + i * angleIncrement;
            double dirX = forwardX * Math.cos(angle) + forwardZ * Math.sin(angle);
            double dirZ = forwardZ * Math.cos(angle) - forwardX * Math.sin(angle);

            double prevGroundY = setup.y();
            double cliffDist = Double.POSITIVE_INFINITY;
            for (double r = CLIFF_SCAN_MIN_M; r <= maxProbeDist + 1e-6; r += CLIFF_SCAN_STEP_M) {
                double px = setup.x() + dirX * r;
                double pz = setup.z() + dirZ * r;
                Vec3 top = new Vec3(px, prevGroundY + CLIFF_PROBE_HEADROOM_M, pz);
                Vec3 bot = new Vec3(px, prevGroundY - CLIFF_PROBE_DEPTH_M, pz);
                BlockHitResult hit = setup.level().clip(new ClipContext(
                        top,
                        bot,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        setup.player()));
                double groundY = hit.getType() == HitResult.Type.BLOCK
                        ? hit.getLocation().y
                        : (prevGroundY - CLIFF_PROBE_DEPTH_M);
                double stepDrop = prevGroundY - groundY;
                if (stepDrop >= CLIFF_DROP_THRESHOLD_M) {
                    cliffDist = r;
                    break;
                }
                // Advance tracked ground elevation smoothly (clamped upward so overhangs don't pull it up).
                prevGroundY = Math.min(groundY, prevGroundY + CLIFF_PROBE_HEADROOM_M);
            }
            if (Double.isFinite(cliffDist)) {
                outLow[i] = Math.min(outLow[i], cliffDist);
                if (stride == 2 && (i + 1) < rayCount) {
                    outLow[i + 1] = Math.min(outLow[i + 1], cliffDist);
                }
            }
        }
    }

    /** Cast one full revolution with pure multi-height geometric stair & headroom tracing. */
    public static JsonObject capture(
            Minecraft minecraft,
            SimulationClock simulationClock,
            int rayCount,
            double rangeMax
    ) {
        Setup setup = prepare(minecraft, simulationClock, rayCount, rangeMax);
        double[] foot = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_FOOT_HEIGHT);
        double[] low = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_LOW_HEIGHT);
        double[] high = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_HIGH_HEIGHT);
        double[] fLow = new double[rayCount];
        double[] fHigh = new double[rayCount];
        filterWalkableStairRays(setup, rayCount, rangeMax, foot, low, high, fLow, fHigh);

        double[] merged = new double[rayCount];
        java.util.Arrays.fill(merged, Double.POSITIVE_INFINITY);
        mergeNearest(merged, fLow);
        mergeNearest(merged, fHigh);
        JsonObject result = new JsonObject();
        result.add("lidarScan", describeScan(setup, merged, rangeMax));
        return result;
    }

    /** Cast one full revolution per configured ring, plus a stair-filtered min-merged scan. */
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
        double[] foot = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_FOOT_HEIGHT);
        double[] refLow = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_LOW_HEIGHT);
        double[] refHigh = scanHeight(setup.level(), setup.player(), setup.x(), setup.y(), setup.z(), setup.yaw(), rayCount, rangeMax, STAIR_HIGH_HEIGHT);
        double[] fLow = new double[rayCount];
        double[] fHigh = new double[rayCount];
        filterWalkableStairRays(setup, rayCount, rangeMax, foot, refLow, refHigh, fLow, fHigh);

        double[] merged = new double[rayCount];
        java.util.Arrays.fill(merged, Double.POSITIVE_INFINITY);
        JsonArray ringScans = new JsonArray();
        for (Ring ring : rings) {
            double[] distances;
            if (Math.abs(ring.height() - STAIR_LOW_HEIGHT) < 0.15) {
                distances = fLow;
            } else if (Math.abs(ring.height() - STAIR_HIGH_HEIGHT) < 0.15) {
                distances = fHigh;
            } else {
                distances = scanHeight(
                        setup.level(), setup.player(),
                        setup.x(), setup.y(), setup.z(), setup.yaw(),
                        rayCount, rangeMax, ring.height());
            }
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