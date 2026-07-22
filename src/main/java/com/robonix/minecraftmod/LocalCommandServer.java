// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** Serves the confirmed loopback JSON-lines command interface. */
public final class LocalCommandServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final FrameCapture frameCapture;
    private final DepthCapture depthCapture;
    private final MinecraftControl control;
    private final MinecraftActions actions;
    private final int port = envPort();
    private final ExecutorService connections = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "robonix-command-connection");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ServerSocket serverSocket;

    public LocalCommandServer(
            FrameCapture frameCapture,
            DepthCapture depthCapture,
            MinecraftControl control,
            MinecraftActions actions
    ) {
        this.frameCapture = frameCapture;
        this.depthCapture = depthCapture;
        this.control = control;
        this.actions = actions;
    }

    /** Binds the loopback listener in a daemon thread without delaying client startup. */
    public void start() {
        Thread listener = new Thread(this::acceptLoop, "robonix-command-listener");
        listener.setDaemon(true);
        listener.start();
    }

    /** Accepts local connections until shutdown, delegating each request to a worker. */
    private void acceptLoop() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            serverSocket = socket;
            RobonixMinecraftMod.LOGGER.info("Robonix Minecraft command interface listening on 127.0.0.1:{}", port);
            while (!socket.isClosed()) {
                Socket connection = socket.accept();
                connections.execute(() -> handle(connection));
            }
        } catch (IOException exception) {
            if (serverSocket == null || !serverSocket.isClosed()) {
                RobonixMinecraftMod.LOGGER.error("Minecraft command interface stopped", exception);
            }
        }
    }

    /** Reads one bounded request from a connection and writes exactly one response. */
    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(35_000);
            JsonObject response;
            try {
                String line = readBoundedLine(reader, 65_536);
                if (line == null || line.isEmpty()) {
                    throw new IllegalArgumentException("empty command");
                }
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                response = responseFor(request);
            } catch (Exception exception) {
                response = errorResponse("", exception);
            }
            writer.write(GSON.toJson(response));
            writer.newLine();
            writer.flush();
        } catch (Exception exception) {
            RobonixMinecraftMod.LOGGER.warn("Failed to handle local Minecraft command", exception);
        }
    }

    /** Executes a command and preserves the existing id/ok/result response shape. */
    private JsonObject responseFor(JsonObject request) {
        String id = request.has("id") ? request.get("id").getAsString() : "";
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("id", id);
        try {
            JsonObject result = dispatch(request).get(35, TimeUnit.SECONDS);
            response.addProperty("ok", true);
            response.add("result", result);
        } catch (Exception exception) {
            return errorResponse(id, exception);
        }
        return response;
    }

    /** Builds one structured protocol error while unwrapping asynchronous failures. */
    private static JsonObject errorResponse(String id, Exception exception) {
        Throwable cause = (exception instanceof CompletionException || exception instanceof ExecutionException)
                && exception.getCause() != null
                ? exception.getCause() : exception;
        JsonObject error = new JsonObject();
        error.addProperty("message", cause.getMessage() == null ? cause.toString() : cause.getMessage());
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("id", id);
        response.addProperty("ok", false);
        response.add("error", error);
        return response;
    }

    /** Routes the confirmed command names while keeping game access on the client thread. */
    private CompletableFuture<JsonObject> dispatch(JsonObject request) {
        String command = requiredString(request, "cmd");
        return switch (command) {
            case "observe" -> observe();
            case "snapshot" -> CompletableFuture.completedFuture(snapshot());
            case "depth_snapshot" -> CompletableFuture.completedFuture(depthSnapshot());
            case "move" -> request.has("distance")
                    ? control.moveDistance(
                            request.has("direction") ? request.get("direction").getAsString() : "forward",
                            request.get("distance").getAsDouble())
                    : request.has("linear_x") || request.has("linear_y")
                            || request.has("yaw_degrees") || request.has("pitch_degrees")
                    ? control.moveVelocity(
                            request.has("linear_x") ? request.get("linear_x").getAsDouble() : 0.0,
                            request.has("linear_y") ? request.get("linear_y").getAsDouble() : 0.0,
                            request.has("yaw_degrees") ? request.get("yaw_degrees").getAsDouble() : 0.0,
                            request.has("pitch_degrees") ? request.get("pitch_degrees").getAsDouble() : 0.0,
                            request.has("duration_ms") ? request.get("duration_ms").getAsLong() : 1000L)
                    : control.move(
                            request.has("direction") ? request.get("direction").getAsString() : "forward",
                            request.has("duration_ms") ? request.get("duration_ms").getAsLong() : 500L);
            case "turn" -> control.turn(
                    request.has("degrees") ? request.get("degrees").getAsDouble() : 0.0,
                    request.has("pitch_degrees") ? request.get("pitch_degrees").getAsDouble() : 0.0);
            case "stop" -> control.stop();
            case "inventory" -> actions.inventory();
            case "break_block" -> actions.breakBlock(
                    requiredString(request, "expected_block"),
                    requiredBoolean(request, "collect"));
            case "deposit_item" -> actions.depositItem(
                    requiredInt(request, "x"),
                    requiredInt(request, "y"),
                    requiredInt(request, "z"),
                    requiredString(request, "item"),
                    requiredLong(request, "count"));
            default -> CompletableFuture.failedFuture(
                    new IllegalArgumentException("unsupported command: " + command));
        };
    }

    /** Schedules observation creation on the Minecraft client thread. */
    private static CompletableFuture<JsonObject> observe() {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                result.complete(ObservationFactory.create(minecraft));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Returns the latest encoded framebuffer using the existing rendered-view shape. */
    private JsonObject snapshot() {
        FrameCapture.Frame frame = frameCapture.latest();
        if (frame == null) {
            throw new IllegalStateException("no rendered frame is available yet");
        }
        JsonObject image = new JsonObject();
        image.addProperty("mimeType", "image/jpeg");
        image.addProperty("dataUrl", "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(frame.data()));
        JsonObject view = new JsonObject();
        view.addProperty("schemaVersion", 1);
        view.addProperty("type", "first_person_rendered");
        view.addProperty("width", frame.width());
        view.addProperty("height", frame.height());
        view.addProperty("timestampMs", frame.timestampMs());
        view.add("image", image);
        JsonObject result = new JsonObject();
        result.add("renderedView", view);
        return result;
    }

    /** Returns the latest linearized little-endian 32FC1 depth image. */
    private JsonObject depthSnapshot() {
        DepthCapture.Frame frame = depthCapture.latest();
        if (frame == null) {
            throw new IllegalStateException("no rendered depth frame is available yet");
        }
        JsonObject image = new JsonObject();
        image.addProperty("encoding", "32FC1");
        image.addProperty("dataUrl", "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(frame.data()));
        JsonObject view = new JsonObject();
        view.addProperty("schemaVersion", 1);
        view.addProperty("type", "first_person_depth");
        view.addProperty("width", frame.width());
        view.addProperty("height", frame.height());
        view.addProperty("timestampMs", frame.timestampMs());
        view.addProperty("verticalFovDeg", Minecraft.getInstance().options.fov().get());
        view.addProperty("unit", "metre");
        view.add("image", image);
        JsonObject result = new JsonObject();
        result.add("depthView", view);
        return result;
    }

    /** Reads a required string property from an incoming request. */
    private static String requiredString(JsonObject request, String name) {
        if (!request.has(name)
                || !request.get(name).isJsonPrimitive()
                || !request.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("missing string field: " + name);
        }
        return request.get(name).getAsString();
    }

    /** Reads a required exact integer property without accepting fractional coordinates. */
    private static int requiredInt(JsonObject request, String name) {
        long value = requiredLong(request, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer field is outside int32 range: " + name);
        }
        return (int) value;
    }

    /** Reads a required integral JSON number without silently rounding it. */
    private static long requiredLong(JsonObject request, String name) {
        if (!request.has(name)
                || !request.get(name).isJsonPrimitive()
                || !request.getAsJsonPrimitive(name).isNumber()) {
            throw new IllegalArgumentException("missing integer field: " + name);
        }
        try {
            return request.get(name).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("field must be an exact integer: " + name, exception);
        }
    }

    /** Reads a required JSON boolean without accepting strings or numbers. */
    private static boolean requiredBoolean(JsonObject request, String name) {
        if (!request.has(name)
                || !request.get(name).isJsonPrimitive()
                || !request.getAsJsonPrimitive(name).isBoolean()) {
            throw new IllegalArgumentException("missing boolean field: " + name);
        }
        return request.get(name).getAsBoolean();
    }

    /** Reads one line without allowing BufferedReader to allocate beyond the limit. */
    private static String readBoundedLine(BufferedReader reader, int limit) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(limit, 1024));
        while (line.length() <= limit) {
            int value = reader.read();
            if (value == -1) {
                return line.isEmpty() ? null : line.toString();
            }
            if (value == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) value);
        }
        throw new IllegalArgumentException("command exceeds " + limit + " characters");
    }

    /** Reads the local command port and rejects values outside the TCP range. */
    private static int envPort() {
        String raw = System.getenv().getOrDefault("RBNX_MINECRAFT_MOD_PORT", "25576");
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 65_535) {
                throw new IllegalArgumentException("RBNX_MINECRAFT_MOD_PORT is outside 1..65535");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("RBNX_MINECRAFT_MOD_PORT is not an integer", exception);
        }
    }

    /** Closes the listener and interrupts outstanding connection workers. */
    @Override
    public void close() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                RobonixMinecraftMod.LOGGER.debug("Command listener was already closed", exception);
            }
        }
        connections.shutdownNow();
    }
}
