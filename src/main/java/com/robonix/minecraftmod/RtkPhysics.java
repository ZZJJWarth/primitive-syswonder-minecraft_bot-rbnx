// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shared physical RTK/GNSS simulation used by both the rover client (rtk_fix)
 * and the dedicated server handheld mapper (rtk_mapper_poll).
 *
 * Only emits standard GNSS/RTK observables (ENU position, covariance, fix type,
 * sky visibility, nearest base station distance). Never exposes blocks, chests,
 * entities, or world layout.
 */
public final class RtkPhysics {
    public static final int FIX_NO_FIX = 0;
    public static final int FIX_SINGLE_GNSS = 1;
    public static final int FIX_RTK_FIXED = 4;
    public static final int FIX_RTK_FLOAT = 5;

    private static final double SKY_RAY_LENGTH = 64.0;
    private static final double HIGH_ELEVATION_RAD = Math.toRadians(72.0);
    private static final double MID_ELEVATION_RAD = Math.toRadians(48.0);
    private static final double SKY_NO_FIX_THRESHOLD = 0.15;
    private static final double LOS_FIXED_RANGE_M = 48.0;
    private static final double[][] SKY_DIRS = buildSkyDirs();

    public record FixResult(
            boolean valid,
            String fixType,
            int fixCode,
            double east,
            double north,
            double up,
            double sigmaXY,
            double sigmaZ,
            double skyVisibility,
            int numSats,
            String baseId,
            double baseDistanceM,
            int stationCount,
            double yawRad,
            double timestampSec
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("valid", valid);
            obj.addProperty("fix_type", fixType);
            obj.addProperty("fix_code", fixCode);
            obj.addProperty("east", east);
            obj.addProperty("north", north);
            obj.addProperty("up", up);
            obj.addProperty("sigma_xy", sigmaXY);
            obj.addProperty("sigma_z", sigmaZ);
            obj.addProperty("sky_visibility", skyVisibility);
            obj.addProperty("num_sats", numSats);
            obj.addProperty("base_id", baseId);
            obj.addProperty("base_distance_m", baseDistanceM);
            obj.addProperty("station_count", stationCount);
            obj.addProperty("yaw", yawRad);
            obj.addProperty("timestamp", timestampSec);
            return obj;
        }
    }

    private RtkPhysics() {}

    private static double[][] buildSkyDirs() {
        double[][] dirs = new double[17][3];
        dirs[0] = new double[] {0.0, 1.0, 0.0};
        double cosHi = Math.cos(HIGH_ELEVATION_RAD);
        double sinHi = Math.sin(HIGH_ELEVATION_RAD);
        double cosMid = Math.cos(MID_ELEVATION_RAD);
        double sinMid = Math.sin(MID_ELEVATION_RAD);
        for (int i = 0; i < 8; i++) {
            double az = i * (Math.PI / 4.0);
            dirs[1 + i] = new double[] {cosHi * Math.cos(az), sinHi, cosHi * Math.sin(az)};
            dirs[9 + i] = new double[] {cosMid * Math.cos(az), sinMid, cosMid * Math.sin(az)};
        }
        return dirs;
    }

    /** Checks whether the entity has direct line-of-sight to a specific RTK base station block. */
    public static boolean hasStationLineOfSight(
            Level level, Entity entity, RtkStationSavedData.Station station) {
        Vec3 start = new Vec3(entity.getX(), entity.getY() + entity.getEyeHeight() + 0.10, entity.getZ());
        Vec3 target = new Vec3(station.x() + 0.5, station.y() + 0.85, station.z() + 0.5);
        BlockPos stationPos = new BlockPos(station.x(), station.y(), station.z());
        BlockHitResult hit = level.clip(new ClipContext(
                start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit.getBlockPos().equals(stationPos)) {
            return true;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        return state.getBlock() instanceof LeavesBlock
                || state.is(BlockTags.LEAVES)
                || !state.canOcclude();
    }

    /** Computes fraction of upper sky hemisphere unobstructed above the entity. */
    public static double computeSkyVisibility(Level level, Entity entity) {
        Vec3 start = new Vec3(entity.getX(), entity.getY() + entity.getEyeHeight() + 0.15, entity.getZ());
        double total = 0.0;
        for (double[] dir : SKY_DIRS) {
            Vec3 end = start.add(dir[0] * SKY_RAY_LENGTH, dir[1] * SKY_RAY_LENGTH, dir[2] * SKY_RAY_LENGTH);
            BlockHitResult hit = level.clip(new ClipContext(
                    start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (hit.getType() == HitResult.Type.MISS) {
                total += 1.0;
            } else {
                BlockState state = level.getBlockState(hit.getBlockPos());
                if (state.getBlock() instanceof LeavesBlock
                        || state.is(BlockTags.LEAVES)
                        || !state.canOcclude()) {
                    total += 0.45;
                }
            }
        }
        return total / SKY_DIRS.length;
    }

    /** Pure classification & noise solver testable without a Minecraft level. */
    public static FixResult solveFix(
            double mcX,
            double mcY,
            double mcZ,
            double yawRad,
            double skyVisibility,
            List<RtkStationSavedData.Station> stations,
            double maxRangeM,
            boolean noiseEnabled,
            long noiseSeed,
            double timestampSec
    ) {
        return solveFix(
                mcX, mcY, mcZ, yawRad, skyVisibility,
                false, -1.0, "",
                stations, maxRangeM, noiseEnabled, noiseSeed, timestampSec);
    }

    /** Pure classification & noise solver with optional direct base-station LOS link. */
    public static FixResult solveFix(
            double mcX,
            double mcY,
            double mcZ,
            double yawRad,
            double skyVisibility,
            boolean baseLineOfSight,
            double losDistanceM,
            String losStationId,
            List<RtkStationSavedData.Station> stations,
            double maxRangeM,
            boolean noiseEnabled,
            long noiseSeed,
            double timestampSec
    ) {
        double trueEast = mcX;
        double trueNorth = -mcZ;
        double trueUp = mcY;

        RtkStationSavedData.Station nearest = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (var station : stations) {
            double dx = trueEast - station.east();
            double dy = trueNorth - station.north();
            double dz = trueUp - station.up();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < bestDist) {
                bestDist = d;
                nearest = station;
            }
        }

        boolean inRange = nearest != null && bestDist <= maxRangeM;
        boolean losInRange = baseLineOfSight && losDistanceM >= 0.0 && losDistanceM <= maxRangeM;
        String baseId = losInRange && !losStationId.isEmpty()
                ? losStationId
                : (inRange ? nearest.id() : "");
        double baseDist = losInRange ? losDistanceM : (nearest != null ? bestDist : -1.0);

        String fixType;
        int fixCode;
        double sigmaXY;
        double sigmaZ;
        int numSats;
        boolean valid;

        if (skyVisibility < SKY_NO_FIX_THRESHOLD) {
            if (losInRange) {
                // Direct line-of-sight to a local RTK/pseudolite base station keeps a valid fix
                // under overhangs, corridors, or along building walls.
                valid = true;
                if (losDistanceM <= LOS_FIXED_RANGE_M) {
                    fixType = "RTK_FIXED";
                    fixCode = FIX_RTK_FIXED;
                    sigmaXY = 0.08;
                    sigmaZ = 0.12;
                    numSats = 10;
                } else {
                    fixType = "RTK_FLOAT";
                    fixCode = FIX_RTK_FLOAT;
                    sigmaXY = 0.35;
                    sigmaZ = 0.50;
                    numSats = 7;
                }
            } else {
                valid = false;
                fixType = "NO_FIX";
                fixCode = FIX_NO_FIX;
                sigmaXY = 999.0;
                sigmaZ = 999.0;
                numSats = (int) Math.round(skyVisibility * 12.0);
            }
        } else if (inRange && (skyVisibility >= 0.65 || (losInRange && losDistanceM <= LOS_FIXED_RANGE_M))) {
            valid = true;
            fixType = "RTK_FIXED";
            fixCode = FIX_RTK_FIXED;
            sigmaXY = 0.05;
            sigmaZ = 0.10;
            numSats = 14 + (int) Math.round(Math.max(0.0, skyVisibility - 0.65) / 0.35 * 4.0);
        } else if (inRange || losInRange) {
            valid = true;
            fixType = "RTK_FLOAT";
            fixCode = FIX_RTK_FLOAT;
            sigmaXY = 0.40;
            sigmaZ = 0.60;
            numSats = 7 + (int) Math.round(Math.max(0.0, skyVisibility - SKY_NO_FIX_THRESHOLD) / 0.50 * 4.0);
        } else if (skyVisibility >= 0.45) {
            valid = true;
            fixType = "SINGLE_GNSS";
            fixCode = FIX_SINGLE_GNSS;
            sigmaXY = 2.00;
            sigmaZ = 3.00;
            numSats = 8 + (int) Math.round((skyVisibility - 0.45) / 0.55 * 4.0);
        } else {
            valid = false;
            fixType = "NO_FIX";
            fixCode = FIX_NO_FIX;
            sigmaXY = 999.0;
            sigmaZ = 999.0;
            numSats = 3;
        }

        double outEast = valid ? trueEast : 0.0;
        double outNorth = valid ? trueNorth : 0.0;
        double outUp = valid ? trueUp : 0.0;
        if (valid && noiseEnabled) {
            Random rng = new Random(noiseSeed);
            outEast += clampGaussian(rng) * sigmaXY;
            outNorth += clampGaussian(rng) * sigmaXY;
            outUp += clampGaussian(rng) * sigmaZ;
        }
        return new FixResult(
                valid,
                fixType,
                fixCode,
                outEast,
                outNorth,
                outUp,
                noiseEnabled ? sigmaXY : 0.01,
                noiseEnabled ? sigmaZ : 0.01,
                skyVisibility,
                numSats,
                baseId,
                baseDist,
                stations.size(),
                yawRad,
                timestampSec
        );
    }

    /** Converts Minecraft yaw (0=+Z south, 90=-X west) to ENU yaw (0=+X east, pi/2=+Y north). */
    public static double minecraftYawToEnuRad(float mcYawDeg) {
        double rad = Math.toRadians(-mcYawDeg - 90.0);
        return Math.atan2(Math.sin(rad), Math.cos(rad));
    }

    public static FixResult computeFix(
            Level level,
            Entity entity,
            List<RtkStationSavedData.Station> stations,
            double maxRangeM,
            boolean noiseEnabled,
            double timestampSec
    ) {
        double sky = computeSkyVisibility(level, entity);
        double yaw = minecraftYawToEnuRad(entity.getYRot());
        long seed = level.getGameTime() * 31L + entity.getId() * 131L + Double.doubleToLongBits(maxRangeM);

        boolean hasLos = false;
        double bestLosDist = Double.POSITIVE_INFINITY;
        String bestLosId = "";
        double trueEast = entity.getX();
        double trueNorth = -entity.getZ();
        double trueUp = entity.getY();
        for (var station : stations) {
            double dx = trueEast - station.east();
            double dy = trueNorth - station.north();
            double dz = trueUp - station.up();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= maxRangeM && d < bestLosDist && hasStationLineOfSight(level, entity, station)) {
                hasLos = true;
                bestLosDist = d;
                bestLosId = station.id();
            }
        }

        return solveFix(
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                yaw,
                sky,
                hasLos,
                hasLos ? bestLosDist : -1.0,
                bestLosId,
                stations,
                maxRangeM,
                noiseEnabled,
                seed,
                timestampSec
        );
    }

    public static JsonArray stationsToJson(List<RtkStationSavedData.Station> stations) {
        JsonArray arr = new JsonArray();
        for (var st : stations) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", st.id());
            obj.addProperty("x", st.x());
            obj.addProperty("y", st.y());
            obj.addProperty("z", st.z());
            obj.addProperty("east", st.east());
            obj.addProperty("north", st.north());
            obj.addProperty("up", st.up());
            arr.add(obj);
        }
        return arr;
    }

    private static double clampGaussian(Random rng) {
        double g = rng.nextGaussian();
        return Math.max(-2.5, Math.min(2.5, g));
    }
}
