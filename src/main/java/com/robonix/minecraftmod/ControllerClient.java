// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Correlates loopback requests with server-authoritative responses. */
public final class ControllerClient {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();

    private ControllerClient() {}

    public static CompletableFuture<JsonObject> execute(String dimension, BlockPos position,
                                                        Boolean powered, List<BlockPos> doors, DoorRegion doorRegion) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        result.orTimeout(10, TimeUnit.SECONDS);
        Minecraft.getInstance().execute(() -> {
            if (result.isDone()) return;
            var listener = Minecraft.getInstance().getConnection();
            if (listener == null || !ControllerNetwork.CHANNEL.isRemotePresent(listener.getConnection())) {
                result.completeExceptionally(new IllegalStateException("connected server needs the elevator Mod"));
                return;
            }
            if (PENDING.size() >= 32) {
                result.completeExceptionally(new IllegalStateException("too many pending controller requests"));
                return;
            }
            int id = IDS.incrementAndGet();
            PENDING.put(id, result);
            result.whenComplete((value, error) -> PENDING.remove(id, result));
            try {
                ControllerNetwork.CHANNEL.send(new ControllerNetwork.Request(id, dimension, position, powered, doors, doorRegion),
                        listener.getConnection());
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    public static void accept(ControllerNetwork.Reply reply) {
        var result = PENDING.get(reply.id());
        if (result == null) return;
        if (!reply.error().isEmpty()) result.completeExceptionally(new IllegalStateException(reply.error()));
        else result.complete(JsonParser.parseString(reply.json()).getAsJsonObject());
    }
}
