// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread-only controller operations, shared by the bridge and game tests. */
public final class ElevatorControllerAccess {
    private ElevatorControllerAccess() {}

    public static JsonObject read(ServerLevel level, BlockPos pos) {
        BlockState state = requireController(level, pos);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        result.addProperty("facing", state.getValue(ElevatorControllerBlock.FACING).getName());
        result.addProperty("powered", state.getValue(ElevatorControllerBlock.POWERED));
        result.addProperty("signal", state.getValue(ElevatorControllerBlock.POWERED) ? 15 : 0);
        result.addProperty("game_time", level.getGameTime());
        return result;
    }

    public static JsonObject set(ServerLevel level, BlockPos pos, boolean powered) {
        BlockState state = requireController(level, pos);
        if (state.getValue(ElevatorControllerBlock.POWERED) != powered) {
            if (!level.setBlock(pos, state.setValue(ElevatorControllerBlock.POWERED, powered), Block.UPDATE_ALL)) {
                throw new IllegalStateException("could not update elevator controller");
            }
        }
        // This acknowledges block state only; a command block executes on a later tick.
        return read(level, pos);
    }

    private static BlockState requireController(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("controller access requires the game server thread");
        }
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
            throw new IllegalArgumentException("controller position is outside world bounds or in an unloaded chunk");
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(ElevatorControllerRegistry.CONTROLLER.get())) {
            throw new IllegalArgumentException("no elevator controller at the requested position");
        }
        return state;
    }
}
