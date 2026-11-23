// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
        if (cmd.equals("rtk_stations_list")) {
            return dispatchRtkStations(json);
        }
        if (cmd.equals("rtk_mapper_poll")) {
            return dispatchRtkMapperPoll(json);
        }
        if (cmd.equals("elevator_list")
                || cmd.equals("elevator_status")
                || cmd.equals("elevator_call")
                || cmd.equals("elevator_door")
                || cmd.equals("elevator_move")) {
            return dispatchNativeElevator(cmd, json);
        }
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

    private CompletableFuture<JsonObject> dispatchNativeElevator(String cmd, JsonObject json) {
        String dimension = json.has("dimension") ? string(json, "dimension") : "minecraft:overworld";
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        result.orTimeout(9, TimeUnit.SECONDS);
        server.execute(() -> {
            if (closed || result.isDone()) {
                result.completeExceptionally(new IllegalStateException("server endpoint stopped"));
                return;
            }
            try {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
                ServerLevel level = server.getLevel(key);
                if (level == null) {
                    throw new IllegalArgumentException("unknown dimension: " + dimension);
                }
                ElevatorSavedData data = ElevatorSavedData.get(level);
                data.pruneMissing(level);

                if (cmd.equals("elevator_list")) {
                    // Pure IoT elevator registry: IDs, states, floor labels only — ZERO world coordinates!
                    JsonObject out = new JsonObject();
                    out.addProperty("dimension", dimension);
                    JsonArray arr = new JsonArray();
                    for (var rec : data.listElevators()) {
                        arr.add(rec.toRuntimeJson(level));
                    }
                    out.add("elevators", arr);
                    result.complete(out);
                    return;
                }

                String elevId = string(json, "elevator_id");
                ElevatorSavedData.ElevatorRecord rec = data.getById(elevId);
                if (rec == null) {
                    throw new IllegalArgumentException("unknown elevator_id: " + elevId);
                }

                switch (cmd) {
                    case "elevator_status" -> {
                        result.complete(rec.toRuntimeJson(level));
                    }
                    case "elevator_call" -> {
                        String floor = string(json, "floor");
                        boolean openDoor = !json.has("open_door") || json.get("open_door").getAsBoolean();
                        var stop = rec.resolveFloor(floor);
                        if (stop == null) {
                            throw new IllegalArgumentException("unknown floor " + floor + " on " + elevId);
                        }
                        boolean accepted = ElevatorEngine.dispatchToFloor(level, data, rec, stop, openDoor);
                        JsonObject out = rec.toRuntimeJson(level);
                        out.addProperty("action_accepted", accepted);
                        if (!accepted) {
                            out.addProperty("door_blocked", true);
                        }
                        result.complete(out);
                    }
                    case "elevator_move" -> {
                        String floor = string(json, "floor");
                        var stop = rec.resolveFloor(floor);
                        if (stop == null) {
                            throw new IllegalArgumentException("unknown floor " + floor + " on " + elevId);
                        }
                        boolean accepted = ElevatorEngine.dispatchToFloor(level, data, rec, stop, false);
                        JsonObject out = rec.toRuntimeJson(level);
                        out.addProperty("action_accepted", accepted);
                        if (!accepted) {
                            out.addProperty("door_blocked", true);
                        }
                        result.complete(out);
                    }
                    case "elevator_door" -> {
                        if (!json.has("open") || !json.get("open").isJsonPrimitive()
                                || !json.getAsJsonPrimitive("open").isBoolean()) {
                            throw new IllegalArgumentException("open must be a JSON boolean");
                        }
                        boolean open = json.get("open").getAsBoolean();
                        boolean accepted;
                        if (json.has("floor") && !json.get("floor").isJsonNull()) {
                            String floor = string(json, "floor");
                            var stop = rec.resolveFloor(floor);
                            if (stop == null) {
                                throw new IllegalArgumentException("unknown floor " + floor + " on " + elevId);
                            }
                            accepted = ElevatorEngine.setDoorAtFloor(level, data, rec, stop, open);
                        } else if (!open) {
                            accepted = ElevatorEngine.closeAllDoors(level, rec);
                            if (accepted) {
                                data.markDirty();
                            }
                        } else {
                            accepted = ElevatorEngine.setDoorAtCurrentFloor(level, data, rec, true);
                        }
                        JsonObject out = rec.toRuntimeJson(level);
                        out.addProperty("action_accepted", accepted);
                        if (!accepted) {
                            out.addProperty("door_blocked", true);
                        }
                        result.complete(out);
                    }
                    default -> throw new IllegalArgumentException("unsupported elevator command: " + cmd);
                }
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private CompletableFuture<JsonObject> dispatchRtkStations(JsonObject json) {
        String dimension = json.has("dimension") ? string(json, "dimension") : "minecraft:overworld";
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        result.orTimeout(9, TimeUnit.SECONDS);
        server.execute(() -> {
            if (closed || result.isDone()) {
                result.completeExceptionally(new IllegalStateException("server endpoint stopped"));
                return;
            }
            try {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
                ServerLevel level = server.getLevel(key);
                if (level == null) {
                    throw new IllegalArgumentException("unknown dimension: " + dimension);
                }
                var data = RtkStationSavedData.get(level);
                if (data.pruneMissing(level)) {
                    RtkNetwork.broadcastStations(level);
                }
                var stations = data.listStations();
                JsonObject out = new JsonObject();
                out.addProperty("dimension", dimension);
                out.add("stations", RtkPhysics.stationsToJson(stations));
                result.complete(out);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private CompletableFuture<JsonObject> dispatchRtkMapperPoll(JsonObject json) {
        String dimension = json.has("dimension") ? string(json, "dimension") : "minecraft:overworld";
        double maxRangeM = json.has("max_range_m") ? json.get("max_range_m").getAsDouble() : 256.0;
        boolean noiseEnabled = !json.has("noise_enabled") || json.get("noise_enabled").getAsBoolean();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        result.orTimeout(9, TimeUnit.SECONDS);
        server.execute(() -> {
            if (closed || result.isDone()) {
                result.completeExceptionally(new IllegalStateException("server endpoint stopped"));
                return;
            }
            try {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
                ServerLevel level = server.getLevel(key);
                if (level == null) {
                    throw new IllegalArgumentException("unknown dimension: " + dimension);
                }
                var data = RtkStationSavedData.get(level);
                if (data.pruneMissing(level)) {
                    RtkNetwork.broadcastStations(level);
                }
                var stations = data.listStations();
                double ts = level.getGameTime() / 20.0;
                JsonArray holders = new JsonArray();
                JsonArray indoorHolders = new JsonArray();
                for (ServerPlayer player : level.players()) {
                    boolean holdingRtk = player.getMainHandItem().is(RtkRegistry.RTK_MAPPER.get())
                            || player.getOffhandItem().is(RtkRegistry.RTK_MAPPER.get());
                    if (holdingRtk) {
                        var fix = RtkPhysics.computeFix(level, player, stations, maxRangeM, noiseEnabled, ts);
                        JsonObject entry = new JsonObject();
                        entry.addProperty("player", player.getGameProfile().getName());
                        entry.addProperty("dimension", dimension);
                        entry.add("fix", fix.toJson());
                        holders.add(entry);
                    }
                    boolean holdingIndoor = player.getMainHandItem().is(RtkRegistry.INDOOR_MAPPER.get())
                            || player.getOffhandItem().is(RtkRegistry.INDOOR_MAPPER.get());
                    if (holdingIndoor) {
                        var odom = IndoorMapperTracker.computePose(player, ts);
                        JsonObject entry = new JsonObject();
                        entry.addProperty("player", player.getGameProfile().getName());
                        entry.addProperty("dimension", dimension);
                        entry.add("pose", odom.toJson());
                        indoorHolders.add(entry);
                    }
                }
                JsonObject out = new JsonObject();
                out.addProperty("dimension", dimension);
                out.add("stations", RtkPhysics.stationsToJson(stations));
                out.add("holders", holders);
                out.add("indoor_holders", indoorHolders);
                var mark = RtkMapperTracker.latestMark();
                if (mark != null && mark.dimension().equals(dimension)) {
                    out.add("last_mark", mark.toJson());
                }
                var indoorMark = IndoorMapperTracker.latestMark();
                if (indoorMark != null && indoorMark.dimension().equals(dimension)) {
                    out.add("last_indoor_mark", indoorMark.toJson());
                }
                result.complete(out);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
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
