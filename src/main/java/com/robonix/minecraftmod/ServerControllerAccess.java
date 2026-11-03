// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Shared server-thread operations. Callers own transport authentication/permissions. */
public final class ServerControllerAccess {
    private ServerControllerAccess() {}

    public static JsonObject execute(MinecraftServer server, ControllerNetwork.Request request,
                                     ServerPlayer player, BlockPos commandPosition) {
        if (!server.isSameThread()) throw new IllegalStateException("controller access requires server thread");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(request.dimension()));
        ServerLevel level = server.getLevel(key);
        if (level == null) throw new IllegalArgumentException("unknown dimension");
        for (BlockPos position : request.doors()) requireDoor(level, position);
        // Validate the entire observation before allowing a write to the controller.
        JsonObject region = request.doorRegion() == null ? null : request.doorRegion().observe(level);
        ElevatorControllerAccess.read(level, request.position());
        var controllerState = level.getBlockState(request.position());
        BlockPos output = commandPosition != null ? commandPosition
                : request.position().relative(controllerState.getValue(ElevatorControllerBlock.FACING));
        if (commandPosition != null && (!level.isInWorldBounds(output) || !level.hasChunkAt(output)
                || !(level.getBlockEntity(output) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity))) {
            throw new IllegalArgumentException("configured command block is unloaded, outside bounds or missing");
        }
        JsonObject result = request.powered() == null
                ? ElevatorControllerAccess.read(level, request.position())
                : ElevatorControllerAccess.set(level, request.position(), request.powered());
        JsonArray doors = new JsonArray();
        for (BlockPos position : request.doors()) {
            var state = requireDoor(level, position);
            JsonObject door = new JsonObject();
            door.addProperty("x", position.getX());
            door.addProperty("y", position.getY());
            door.addProperty("z", position.getZ());
            door.addProperty("open", state.getValue(BlockStateProperties.OPEN));
            doors.add(door);
        }
        result.add("doors", doors);
        if (region != null) result.add("door_region", region);
        result.addProperty("player_online", player != null);
        if (player != null) {
            JsonObject observedPlayer = new JsonObject();
            observedPlayer.addProperty("name", player.getGameProfile().getName());
            observedPlayer.addProperty("uuid", player.getUUID().toString());
            observedPlayer.addProperty("dimension", player.level().dimension().location().toString());
            observedPlayer.addProperty("x", player.getX());
            observedPlayer.addProperty("y", player.getY());
            observedPlayer.addProperty("z", player.getZ());
            var bounds = player.getBoundingBox();
            JsonObject playerBounds = new JsonObject();
            playerBounds.add("min", vector(bounds.minX, bounds.minY, bounds.minZ));
            playerBounds.add("max", vector(bounds.maxX, bounds.maxY, bounds.maxZ));
            observedPlayer.add("bounds", playerBounds);
            result.add("player", observedPlayer);
        }

        if (level.hasChunkAt(output) && level.getBlockEntity(output) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity command) {
            result.addProperty("command_success", command.getCommandBlock().getSuccessCount());
            result.addProperty("command_tick", command.getCommandBlock()
                    .save(new net.minecraft.nbt.CompoundTag()).getLong("LastExecution"));
        }
        return result;
    }
    private static JsonArray vector(double x, double y, double z) {
        JsonArray values = new JsonArray();
        values.add(x); values.add(y); values.add(z);
        return values;
    }

    private static net.minecraft.world.level.block.state.BlockState requireDoor(ServerLevel level, BlockPos position) {
        if (!level.isInWorldBounds(position) || !level.hasChunkAt(position)) {
            throw new IllegalArgumentException("door is outside world bounds or unloaded");
        }
        var state = level.getBlockState(position);
        if (!state.hasProperty(BlockStateProperties.OPEN)) {
            throw new IllegalArgumentException("configured door has no open property");
        }
        return state;
    }
}
