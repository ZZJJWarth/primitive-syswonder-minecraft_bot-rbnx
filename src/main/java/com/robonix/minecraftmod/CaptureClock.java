// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.UUID;
import java.util.function.LongSupplier;

/** Host elapsed time and independent wall-time evidence for a capture marker. */
final class CaptureClock {
    private final String sessionId;
    private final LongSupplier monotonic;
    private final LongSupplier realtimeMillis;
    private final long origin;

    CaptureClock() {
        this(UUID.randomUUID().toString(), System::nanoTime, System::currentTimeMillis);
    }

    CaptureClock(String sessionId, LongSupplier monotonic, LongSupplier realtimeMillis) {
        this.sessionId = sessionId;
        this.monotonic = monotonic;
        this.realtimeMillis = realtimeMillis;
        origin = monotonic.getAsLong();
    }

    /** Bracket the independent millisecond wall read; never derive it from a device stamp. */
    Sample capture() {
        long before = monotonic.getAsLong();
        long referenceMs = realtimeMillis.getAsLong();
        long after = monotonic.getAsLong();
        long span = after - before;
        long elapsed = before - origin + span / 2L;
        if (span < 0L || elapsed < 0L) {
            throw new IllegalStateException("capture monotonic clock moved backwards");
        }
        // currentTimeMillis is quantized to milliseconds. This bound covers
        // read placement and quantization, not UTC accuracy or GPU pipeline delay.
        long uncertainty = Math.addExact(span / 2L + span % 2L, 1_000_000L);
        return new Sample(sessionId, elapsed, referenceMs, uncertainty);
    }

    record Sample(String sessionId, long elapsedNanos, long referenceMs, long uncertaintyNanos) {}
}
