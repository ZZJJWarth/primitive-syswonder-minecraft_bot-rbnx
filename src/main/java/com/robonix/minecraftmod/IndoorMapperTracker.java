// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tracks indoor odometry anchors and right-click mark events for players
 * holding robonix_minecraft:indoor_mapper.
 *
 * Unlike RTK GNSS, indoor odometry does not depend on sky visibility: once the
 * player anchors an entrance/exit point at the building doorway, subsequent
 * corridor and room coordinates are computed from relative odometry
 * displacement anchored to that doorway.
 */
public final class IndoorMapperTracker {
    public record Anchor(
            String dimension,
            double anchorEast,
            double anchorNorth,
            double anchorUp,
            double originMcX,
            double originMcY,
            double originMcZ,
            long wallTimeMs
    ) {}

    public record OdomPose(
            boolean anchored,
            double east,
            double north,
            double up,
            double odomDx,
            double odomDy,
            double odomDz,
            double yawRad,
            double anchorEast,
            double anchorNorth,
            double anchorUp,
            double timestampSec
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("anchored", anchored);
            obj.addProperty("east", east);
            obj.addProperty("north", north);
            obj.addProperty("up", up);
            obj.addProperty("odom_dx", odomDx);
            obj.addProperty("odom_dy", odomDy);
            obj.addProperty("odom_dz", odomDz);
            obj.addProperty("yaw", yawRad);
            obj.addProperty("anchor_east", anchorEast);
            obj.addProperty("anchor_north", anchorNorth);
            obj.addProperty("anchor_up", anchorUp);
            obj.addProperty("timestamp", timestampSec);
            return obj;
        }
    }

    public record MarkEvent(
            long seq,
            String action,
            String playerName,
            String dimension,
            OdomPose pose,
            long wallTimeMs,
            JsonObject extra
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("seq", seq);
            obj.addProperty("action", action);
            obj.addProperty("player", playerName);
            obj.addProperty("dimension", dimension);
            obj.addProperty("wall_time_ms", wallTimeMs);
            obj.add("pose", pose.toJson());
            if (extra != null) {
                obj.add("extra", extra);
            }
            return obj;
        }
    }

    private static final Map<UUID, Anchor> ANCHORS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong(0);
    private static volatile MarkEvent lastMark = null;

    private IndoorMapperTracker() {}

    /** Anchors the player's indoor odometry origin at the building entrance. */
    public static OdomPose anchorEntrance(
            ServerPlayer player, double anchorEast, double anchorNorth, double anchorUp, double tsSec) {
        String dim = player.serverLevel().dimension().location().toString();
        Anchor anchor = new Anchor(
                dim,
                anchorEast,
                anchorNorth,
                anchorUp,
                player.getX(),
                player.getY(),
                player.getZ(),
                System.currentTimeMillis()
        );
        ANCHORS.put(player.getUUID(), anchor);
        return computePose(player, tsSec);
    }

    /** Computes current indoor odometry pose (relative to entrance anchor if set). */
    public static OdomPose computePose(ServerPlayer player, double tsSec) {
        String dim = player.serverLevel().dimension().location().toString();
        double yaw = RtkPhysics.minecraftYawToEnuRad(player.getYRot());
        Anchor anchor = ANCHORS.get(player.getUUID());
        if (anchor != null && anchor.dimension().equals(dim)) {
            double dx = player.getX() - anchor.originMcX();
            double dy = -(player.getZ() - anchor.originMcZ());
            double dz = player.getY() - anchor.originMcY();
            return new OdomPose(
                    true,
                    anchor.anchorEast() + dx,
                    anchor.anchorNorth() + dy,
                    anchor.anchorUp() + dz,
                    dx,
                    dy,
                    dz,
                    yaw,
                    anchor.anchorEast(),
                    anchor.anchorNorth(),
                    anchor.anchorUp(),
                    tsSec
            );
        }
        double east = player.getX();
        double north = -player.getZ();
        double up = player.getY();
        return new OdomPose(false, east, north, up, 0.0, 0.0, 0.0, yaw, east, north, up, tsSec);
    }

    public static MarkEvent recordMark(ServerPlayer player, OdomPose pose, String action) {
        return recordMark(player, pose, action, null);
    }

    public static MarkEvent recordMark(ServerPlayer player, OdomPose pose, String action, JsonObject extra) {
        MarkEvent ev = new MarkEvent(
                SEQ.incrementAndGet(),
                action == null || action.isBlank() ? "mark_step" : action,
                player.getGameProfile().getName(),
                player.serverLevel().dimension().location().toString(),
                pose,
                System.currentTimeMillis(),
                extra
        );
        lastMark = ev;
        return ev;
    }

    public static MarkEvent latestMark() {
        return lastMark;
    }
}
