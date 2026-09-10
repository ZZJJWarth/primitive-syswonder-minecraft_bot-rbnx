// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Captures and linearizes the rendered world depth buffer. */
public final class DepthCapture implements AutoCloseable {
    private static final int HISTORY_SIZE = 8;
    private static final float NEAR_PLANE = 0.05F;
    private static final int MAX_CAPTURE_PIXELS = 3840 * 2160;
    private final SimulationClock simulationClock;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final ArrayDeque<Frame> history = new ArrayDeque<>(HISTORY_SIZE);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService converter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "robonix-depth-converter");
        thread.setDaemon(true);
        return thread;
    });
    private final long intervalNanos = Math.min(
            envLong("RBNX_MINECRAFT_CAPTURE_INTERVAL_MS", 200L), 60_000L) * 1_000_000L;
    private final int outputWidth = Math.min(envInt("RBNX_MINECRAFT_CAPTURE_WIDTH", 960), 3840);
    private final int outputHeight = Math.min(envInt("RBNX_MINECRAFT_CAPTURE_HEIGHT", 540), 2160);
    private long lastCaptureNanos;

    public DepthCapture(SimulationClock simulationClock) {
        this.simulationClock = simulationClock;
    }

    /** Reads world depth after level rendering and before GUI overlays. */
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (closed.get() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCaptureNanos < intervalNanos || !processing.compareAndSet(false, true)) {
            return;
        }
        lastCaptureNanos = now;
        readDepthBuffer();
    }

    /** Returns a recent depth frame or null when capture is unavailable or stale. */
    public Frame latest() {
        Frame frame = latest.get();
        long maxAgeMillis = Math.max(5000L, TimeUnit.NANOSECONDS.toMillis(intervalNanos) * 3L);
        if (frame == null || System.nanoTime() - frame.capturedWallNanos()
                > TimeUnit.MILLISECONDS.toNanos(maxAgeMillis)) {
            return null;
        }
        return frame;
    }

    /** Returns recent fresh frames so RGB-D can select a common simulation tick. */
    public synchronized List<Frame> recent() {
        long oldest = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(
                Math.max(5000L, TimeUnit.NANOSECONDS.toMillis(intervalNanos) * 3L));
        return history.stream().filter(frame -> frame.capturedWallNanos() >= oldest).toList();
    }

    /** Copies GPU depth on the render thread and schedules CPU linearization. */
    private void readDepthBuffer() {
        Minecraft minecraft = Minecraft.getInstance();
        SimulationClock.SampleContext sample = simulationClock.capture(minecraft);
        long capturedWallMillis = System.currentTimeMillis();
        long capturedWallNanos = System.nanoTime();
        RenderTarget target = minecraft.getMainRenderTarget();
        int width = target.viewWidth;
        int height = target.viewHeight;
        FloatBuffer buffer = null;
        int previousReadFramebuffer = -1;
        try {
            if (width < 1 || height < 1) {
                throw new IllegalStateException("depth framebuffer dimensions are empty");
            }
            int pixelCount = Math.multiplyExact(width, height);
            if (pixelCount > MAX_CAPTURE_PIXELS) {
                throw new IllegalStateException("depth framebuffer exceeds the 4K capture limit");
            }
            buffer = MemoryUtil.memAllocFloat(pixelCount);
            previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            clearGlErrors();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
            int glError = GL11.glGetError();
            if (glError != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("depth buffer read failed with OpenGL error 0x"
                        + Integer.toHexString(glError));
            }
            float[] depth = new float[pixelCount];
            buffer.get(depth);
            float farPlane = minecraft.gameRenderer.getDepthFar();
            converter.execute(() -> convert(
                    depth, width, height, farPlane, sample,
                    capturedWallMillis, capturedWallNanos));
        } catch (RuntimeException | OutOfMemoryError exception) {
            processing.set(false);
            RobonixMinecraftMod.LOGGER.error("Failed to read Minecraft depth buffer", exception);
        } finally {
            if (previousReadFramebuffer >= 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            }
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    /** Clears pre-existing OpenGL errors so readback failures can be attributed. */
    private static void clearGlErrors() {
        boolean found = false;
        for (int attempt = 0; attempt < 16; attempt++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) {
                if (found) {
                    RobonixMinecraftMod.LOGGER.debug("Cleared pre-existing OpenGL errors before depth capture");
                }
                return;
            }
            found = true;
        }
        throw new IllegalStateException("OpenGL error queue did not clear before depth capture");
    }

    /** Scales bottom-up depth samples and writes little-endian 32FC1 metres. */
    private void convert(
            float[] source,
            int sourceWidth,
            int sourceHeight,
            float farPlane,
            SimulationClock.SampleContext sample,
            long capturedWallMillis,
            long capturedWallNanos
    ) {
        try {
            double scale = Math.min(1.0, Math.min(
                    (double) outputWidth / sourceWidth,
                    (double) outputHeight / sourceHeight));
            int width = Math.max(1, (int) Math.round(sourceWidth * scale));
            int height = Math.max(1, (int) Math.round(sourceHeight * scale));
            ByteBuffer output = ByteBuffer.allocate(width * height * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) {
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                int sourceY = sourceHeight - 1 - y * sourceHeight / height;
                for (int x = 0; x < width; x++) {
                    int sourceX = x * sourceWidth / width;
                    output.putFloat(linearDepth(source[sourceY * sourceWidth + sourceX], farPlane));
                }
            }
            if (!closed.get() && !Thread.currentThread().isInterrupted()) {
                Frame frame = new Frame(output.array(), width, height,
                        sample.tickId(), sample.simTimeNanos(),
                        capturedWallMillis, capturedWallNanos);
                latest.set(frame);
                synchronized (this) {
                    history.addLast(frame);
                    while (history.size() > HISTORY_SIZE) {
                        history.removeFirst();
                    }
                }
            }
        } catch (RuntimeException | OutOfMemoryError exception) {
            RobonixMinecraftMod.LOGGER.error("Failed to convert Minecraft depth buffer", exception);
        } finally {
            processing.set(false);
        }
    }

    /** Converts OpenGL depth to forward view distance and marks sky as NaN. */
    private static float linearDepth(float depth, float farPlane) {
        if (!Float.isFinite(depth) || depth < 0.0F || depth >= 1.0F || farPlane <= NEAR_PLANE) {
            return Float.NaN;
        }
        float normalized = depth * 2.0F - 1.0F;
        return 2.0F * NEAR_PLANE * farPlane
                / (farPlane + NEAR_PLANE - normalized * (farPlane - NEAR_PLANE));
    }

    /** Reads a positive integer environment setting with a safe default. */
    private static int envInt(String name, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback))));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Reads a positive long environment setting with a safe default. */
    private static long envLong(String name, long fallback) {
        try {
            return Math.max(1L, Long.parseLong(System.getenv().getOrDefault(name, String.valueOf(fallback))));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Stops background depth conversion when the client exits. */
    @Override
    public void close() {
        closed.set(true);
        converter.shutdownNow();
        processing.set(false);
    }

    /** Immutable raw depth image shared with command connections. */
    public record Frame(
            byte[] data,
            int width,
            int height,
            long tickId,
            long simTimeNanos,
            long timestampMs,
            long capturedWallNanos
    ) {}
}
