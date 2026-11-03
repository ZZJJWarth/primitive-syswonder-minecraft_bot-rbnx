// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

/** Standalone marker checks; no Minecraft runtime or game movement required. */
public final class CaptureClockTest {
    public static void main(String[] args) {
        long[] ticks = {10L, 110L, 115L, 210L, 220L};
        int[] index = {0};
        CaptureClock clock = new CaptureClock("test-session", () -> ticks[index[0]++], () -> 1234L);
        CaptureClock.Sample first = clock.capture();
        CaptureClock.Sample second = clock.capture();
        if (!first.sessionId().equals(second.sessionId()) || first.elapsedNanos() != 102L
                || first.referenceMs() != 1234L || first.uncertaintyNanos() != 1_000_003L
                || second.elapsedNanos() != 205L) {
            throw new AssertionError("capture bracket or session identity was lost");
        }
        System.out.println("CaptureClock checks passed");
    }
}
