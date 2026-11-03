// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Dedicated-server loopback control. No client classes, player session or arbitrary commands. */
public final class ServerControllerEndpoint implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerSocket listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "robonix-elevator-connection");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean closed;

    public ServerControllerEndpoint(MinecraftServer server, int port) throws IOException {
        this.server = server;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", port));
    }

    public int port() { return listener.getLocalPort(); }

    public void start() {
        Thread thread = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    try { workers.execute(() -> handle(socket)); }
                    catch (RejectedExecutionException exception) { sockets.remove(socket); socket.close(); }
                } catch (IOException exception) {
                    if (!closed) RobonixMinecraftMod.LOGGER.warn("Elevator control listener failed", exception);
                }
            }
        }, "robonix-elevator-listener");
        thread.setDaemon(true);
        thread.start();
        RobonixMinecraftMod.LOGGER.info("Robonix server elevator interface listening on 127.0.0.1:{}", port());
    }

    private void handle(Socket socket) {
        String id = "";
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(10_000);
            JsonObject response = new JsonObject();
            try {
                StringBuilder line = new StringBuilder();
                while (true) {
                    int ch = reader.read();
                    if (ch == '\n') break;
                    if (ch < 0) throw new IllegalArgumentException("command must end with newline");
                    if (line.length() >= 65_536) throw new IllegalArgumentException("command exceeds 65536 characters");
                    line.append((char) ch);
                }
                JsonObject request = JsonParser.parseString(line.toString()).getAsJsonObject();
                if (request.has("id")) id = request.get("id").getAsString();
                response.add("result", dispatch(request).get(10, TimeUnit.SECONDS));
                response.addProperty("ok", true);
            } catch (Exception exception) {
                Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                        ? exception.getCause() : exception;
                JsonObject error = new JsonObject();
                error.addProperty("message", cause.getMessage() == null ? cause.toString() : cause.getMessage());
                response.addProperty("ok", false);
                response.add("error", error);
            }
            response.addProperty("type", "response");
            response.addProperty("id", id);
            writer.write(response.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            if (!closed) RobonixMinecraftMod.LOGGER.debug("Elevator control connection closed", exception);
        } finally { sockets.remove(socket); }
    }

    private CompletableFuture<JsonObject> dispatch(JsonObject json) {
        String cmd = string(json, "cmd");
        if (!cmd.equals("elevator_controller_get") && !cmd.equals("elevator_controller_set")) {
            throw new IllegalArgumentException("unsupported server command: " + cmd);
        }
        Boolean powered = null;
        if (cmd.equals("elevator_controller_set")) {
            if (!json.has("powered") || !json.get("powered").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("powered").isBoolean()) {
                throw new IllegalArgumentException("powered must be a JSON boolean");
            }
            powered = json.get("powered").getAsBoolean();
        }
        var request = new ControllerNetwork.Request(0, string(json, "dimension"), position(json), powered,
                java.util.List.of(), json.has("door_region") ? DoorRegion.parse(json.getAsJsonObject("door_region")) : null);
        String playerName = json.has("player") ? string(json, "player") : null;
        BlockPos command = json.has("command_block") ? position(json.getAsJsonObject("command_block")) : null;
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        result.orTimeout(9, TimeUnit.SECONDS);
        server.execute(() -> {
            if (closed || result.isDone()) {
                result.completeExceptionally(new IllegalStateException("server endpoint stopped or request expired"));
                return;
            }
            try {
                var player = playerName == null ? null : server.getPlayerList().getPlayerByName(playerName);
                result.complete(ServerControllerAccess.execute(server, request, player, command));
            } catch (Exception exception) { result.completeExceptionally(exception); }
        });
        return result;
    }

    private static String string(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive() || !json.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("missing string field: " + name);
        }
        return json.get(name).getAsString();
    }

    private static BlockPos position(JsonObject json) {
        int[] values = new int[3];
        String[] names = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            var value = json.get(names[i]);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("coordinates must be integers");
            }
            try { values[i] = value.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException exception) { throw new IllegalArgumentException("coordinates must be int32 integers"); }
        }
        return new BlockPos(values[0], values[1], values[2]);
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
