// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/** Tracks the most recent right-click mark event from any player holding rtk_mapper. */
public final class RtkMapperTracker {
    public record MarkEvent(
            long seq,
            String action,
            String playerName,
            String dimension,
            RtkPhysics.FixResult fix,
            long wallTimeMs
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("seq", seq);
            obj.addProperty("action", action);
            obj.addProperty("player", playerName);
            obj.addProperty("dimension", dimension);
            obj.addProperty("wall_time_ms", wallTimeMs);
            obj.add("fix", fix.toJson());
            return obj;
        }
    }

    private static final AtomicLong SEQ = new AtomicLong(0);
    private static volatile MarkEvent lastMark = null;

    private RtkMapperTracker() {}

    public static MarkEvent recordMark(ServerPlayer player, RtkPhysics.FixResult fix, String action) {
        MarkEvent ev = new MarkEvent(
                SEQ.incrementAndGet(),
                action == null || action.isBlank() ? "mark" : action,
                player.getGameProfile().getName(),
                player.serverLevel().dimension().location().toString(),
                fix,
                System.currentTimeMillis()
        );
        lastMark = ev;
        return ev;
    }

    public static MarkEvent latestMark() {
        return lastMark;
    }
}
