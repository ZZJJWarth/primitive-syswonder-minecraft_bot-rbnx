// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;

/** Captures the final client framebuffer and retains the latest JPEG frame. */
public final class FrameCapture implements AutoCloseable {
    private static final int HISTORY_SIZE = 8;
    private final SimulationClock simulationClock;
    private final RgbdFrameCoordinator rgbdCoordinator;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final ArrayDeque<Frame> history = new ArrayDeque<>(HISTORY_SIZE);
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "robonix-frame-encoder");
        thread.setDaemon(true);
        return thread;
    });
    private final long intervalNanos = Math.min(
            envLong("RBNX_MINECRAFT_CAPTURE_INTERVAL_MS", 200L), 60_000L) * 1_000_000L;
    private final int outputWidth = Math.min(envInt("RBNX_MINECRAFT_CAPTURE_WIDTH", 960), 3840);
    private final int outputHeight = Math.min(envInt("RBNX_MINECRAFT_CAPTURE_HEIGHT", 540), 2160);
    private final float jpegQuality = envFloat("RBNX_MINECRAFT_JPEG_QUALITY", 0.8F);
    private long lastCaptureNanos;

    public FrameCapture(
            SimulationClock simulationClock,
            RgbdFrameCoordinator rgbdCoordinator
    ) {
        this.simulationClock = simulationClock;
        this.rgbdCoordinator = rgbdCoordinator;
    }

    /** Reads a frame on the render thread at a bounded rate and encodes it asynchronously. */
    public void onRenderGui(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            RgbdFrameCoordinator.FrameToken token = rgbdCoordinator.claimForRgb();
            if (token != null) {
                capture(token);
            }
        }
    }

    /** Captures after an open inventory, menu, or other screen has rendered. */
    public void onRenderScreen(ScreenEvent.Render.Post event) {
        capture(rgbdCoordinator.claimForRgb());
    }

    /** Takes one owned native image when rate and encoder capacity permit. */
    private void capture(RgbdFrameCoordinator.FrameToken token) {
        long now = System.nanoTime();
        if ((token == null && now - lastCaptureNanos < intervalNanos)
                || !encoding.compareAndSet(false, true)) {
            return;
        }
        lastCaptureNanos = now;
        NativeImage image = null;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            SimulationClock.SampleContext sample = token == null
                    ? simulationClock.capture(minecraft, minecraft.getPartialTick())
                    : token.sample();
            long renderFrameId = token == null ? 0L : token.renderFrameId();
            CaptureClock.Sample hostClock = token == null
                    ? rgbdCoordinator.captureHostClock() : token.hostClock();
            long capturedWallMillis = System.currentTimeMillis();
            image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
            NativeImage ownedImage = image;
            encoder.execute(() -> encode(
                    ownedImage, renderFrameId, sample, hostClock, capturedWallMillis, now));
        } catch (RuntimeException exception) {
            if (image != null) {
                image.close();
            }
            encoding.set(false);
            RobonixMinecraftMod.LOGGER.error("Failed to capture Minecraft frame", exception);
        }
    }

    /** Returns a recent encoded frame, or null when capture has stopped or not completed. */
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

    /** Converts one owned native image to a scaled JPEG and releases native memory. */
    private void encode(
            NativeImage image,
            long renderFrameId,
            SimulationClock.SampleContext sample,
            CaptureClock.Sample hostClock,
            long capturedWallMillis,
            long capturedWallNanos
    ) {
        try (image) {
            BufferedImage buffered = scale(image, outputWidth, outputHeight);
            byte[] data = writeJpeg(buffered, jpegQuality);
            Frame frame = new Frame(data, buffered.getWidth(), buffered.getHeight(),
                    renderFrameId,
                    sample.tickId(), sample.partialTick(), sample.simTimeNanos(),
                    sample.captureSequence(), capturedWallMillis, capturedWallNanos, hostClock);
            latest.set(frame);
            synchronized (this) {
                history.addLast(frame);
                while (history.size() > HISTORY_SIZE) {
                    history.removeFirst();
                }
            }
        } catch (Exception exception) {
            RobonixMinecraftMod.LOGGER.error("Failed to encode Minecraft frame", exception);
        } finally {
            encoding.set(false);
        }
    }

    /** Scales the native ABGR pixels to an RGB image with nearest-neighbor sampling. */
    private static BufferedImage scale(NativeImage source, int requestedWidth, int requestedHeight) {
        double scale = Math.min(1.0, Math.min(
                (double) requestedWidth / source.getWidth(),
                (double) requestedHeight / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int sourceY = y * source.getHeight() / height;
            for (int x = 0; x < width; x++) {
                int sourceX = x * source.getWidth() / width;
                int abgr = source.getPixelRGBA(sourceX, sourceY);
                int rgb = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >>> 16) & 0xFF);
                target.setRGB(x, y, rgb);
            }
        }
        return target;
    }

    /** Encodes an RGB image using an explicit JPEG compression quality. */
    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(Math.max(0.1F, Math.min(1.0F, quality)));
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
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

    /** Reads a floating-point environment setting with a safe default. */
    private static float envFloat(String name, float fallback) {
        try {
            float value = Float.parseFloat(System.getenv().getOrDefault(name, String.valueOf(fallback)));
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Stops the background encoder when the client exits. */
    @Override
    public void close() {
        encoder.shutdownNow();
    }

    /** Immutable encoded camera frame shared with command connections. */
    public record Frame(
            byte[] data,
            int width,
            int height,
            long renderFrameId,
            long tickId,
            float partialTick,
            long simTimeNanos,
            long captureSequence,
            long timestampMs,
            long capturedWallNanos,
            CaptureClock.Sample hostClock
    ) {}
}
