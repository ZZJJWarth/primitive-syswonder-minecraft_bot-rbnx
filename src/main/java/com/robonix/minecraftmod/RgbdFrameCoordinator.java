// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.client.Minecraft;

/** Assigns one canonical simulation-time identity to an RGB-D render frame. */
public final class RgbdFrameCoordinator {
    private final SimulationClock simulationClock;
    private final CaptureClock captureClock = new CaptureClock();
    private long renderFrameId;
    private FrameToken pendingRgbToken;

    public RgbdFrameCoordinator(SimulationClock simulationClock) {
        this.simulationClock = simulationClock;
    }

    /** Discard an unclaimed token when the next rendered world frame begins. */
    public synchronized void beginRenderFrame() {
        pendingRgbToken = null;
    }

    /** Start one paired capture using the depth render stage as canonical time. */
    public synchronized FrameToken startCapture(Minecraft minecraft, float partialTick) {
        FrameToken token = new FrameToken(
                ++renderFrameId,
                simulationClock.capture(minecraft, partialTick), captureClock.capture());
        pendingRgbToken = token;
        return token;
    }

    /** Return the current frame token to RGB at most once. */
    public synchronized FrameToken claimForRgb() {
        FrameToken token = pendingRgbToken;
        pendingRgbToken = null;
        return token;
    }

    /** Sample the same host clock for unpaired RGB captures. */
    CaptureClock.Sample captureHostClock() {
        return captureClock.capture();
    }

    /** Immutable identity shared by RGB and depth from one rendered frame. */
    public record FrameToken(
            long renderFrameId,
            SimulationClock.SampleContext sample,
            CaptureClock.Sample hostClock
    ) {}
}
