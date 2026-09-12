// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Authoritative logical clock derived only from Minecraft world ticks. */
public final class SimulationClock {
    public static final long TICK_NANOS = 50_000_000L;
    private static final float MAX_PARTIAL_TICK = Math.nextDown(1.0F);
    private ClientLevel currentLevel;
    private long tickOffset;
    private long lastRawTick = Long.MIN_VALUE;
    private long lastLogicalTick = Long.MIN_VALUE;
    private long captureSequence;

    /** Capture the immutable time identity of the current world state. */
    public synchronized SampleContext capture(Minecraft minecraft) {
        return capture(minecraft, 0.0F);
    }

    /** Capture the current world tick together with a render-time sub-tick fraction. */
    public synchronized SampleContext capture(Minecraft minecraft, float partialTick) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("Minecraft partial tick must be finite");
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
        float boundedPartialTick = Math.max(0.0F, Math.min(MAX_PARTIAL_TICK, partialTick));
        long partialNanos = Math.min(TICK_NANOS - 1L,
                Math.round((double) boundedPartialTick * TICK_NANOS));
        long simTimeNanos = Math.addExact(
                Math.multiplyExact(logicalTick, TICK_NANOS), partialNanos);
        return new SampleContext(logicalTick, boundedPartialTick,
                simTimeNanos, ++captureSequence);
    }

    /** Expose the same clock over the loopback protocol for the ROS /clock publisher. */
    public JsonObject describe(Minecraft minecraft) {
        SampleContext sample = capture(minecraft, minecraft.getPartialTick());
        JsonObject result = new JsonObject();
        result.addProperty("tickId", sample.tickId());
        result.addProperty("partialTick", sample.partialTick());
        result.addProperty("simTimeNanos", sample.simTimeNanos());
        result.addProperty("captureSequence", sample.captureSequence());
        result.addProperty("tickDurationNanos", TICK_NANOS);
        return result;
    }

    /** Shared capture identity carried through encoding and publication. */
    public record SampleContext(
            long tickId,
            float partialTick,
            long simTimeNanos,
            long captureSequence
    ) {}
}
