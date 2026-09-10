// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Authoritative logical clock derived only from Minecraft world ticks. */
public final class SimulationClock {
    public static final long TICK_NANOS = 50_000_000L;
    private ClientLevel currentLevel;
    private long tickOffset;
    private long lastRawTick = Long.MIN_VALUE;
    private long lastLogicalTick = Long.MIN_VALUE;

    /** Capture the immutable time identity of the current world state. */
    public synchronized SampleContext capture(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        long rawTick = level.getGameTime();
        if (currentLevel == null) {
            currentLevel = level;
        } else if (level != currentLevel || rawTick < lastRawTick) {
            tickOffset = Math.subtractExact(Math.addExact(lastLogicalTick, 1L), rawTick);
            currentLevel = level;
        }
        long logicalTick = Math.addExact(rawTick, tickOffset);
        if (logicalTick < lastLogicalTick) {
            throw new IllegalStateException("Minecraft logical clock moved backwards");
        }
        lastRawTick = rawTick;
        lastLogicalTick = logicalTick;
        return new SampleContext(logicalTick, Math.multiplyExact(logicalTick, TICK_NANOS));
    }

    /** Expose the same clock over the loopback protocol for the ROS /clock publisher. */
    public JsonObject describe(Minecraft minecraft) {
        SampleContext sample = capture(minecraft);
        JsonObject result = new JsonObject();
        result.addProperty("tickId", sample.tickId());
        result.addProperty("simTimeNanos", sample.simTimeNanos());
        result.addProperty("tickDurationNanos", TICK_NANOS);
        return result;
    }

    /** Shared capture identity carried through encoding and publication. */
    public record SampleContext(long tickId, long simTimeNanos) {}
}
