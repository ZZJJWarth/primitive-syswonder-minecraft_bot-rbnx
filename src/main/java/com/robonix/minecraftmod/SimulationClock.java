// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Authoritative logical clock derived only from Minecraft world ticks. */
public final class SimulationClock {
    public static final long TICK_NANOS = 50_000_000L;

    /** Capture the immutable time identity of the current world state. */
    public SampleContext capture(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        long tickId = level.getGameTime();
        return new SampleContext(tickId, Math.multiplyExact(tickId, TICK_NANOS));
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
