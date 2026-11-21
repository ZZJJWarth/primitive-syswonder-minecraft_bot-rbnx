// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Simulated RTK differential GNSS base station block.
 * Placing or removing it updates per-dimension station storage and notifies clients.
 */
public final class RtkBaseBlock extends Block {
    public RtkBaseBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.is(oldState.getBlock()) && level instanceof ServerLevel serverLevel) {
            String id = RtkStationSavedData.get(serverLevel).addStation(pos);
            RtkNetwork.broadcastStations(serverLevel);
            RobonixMinecraftMod.LOGGER.info("Registered RTK base station {} at {}", id, pos);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel && placer != null) {
            String id = RtkStationSavedData.get(serverLevel).addStation(pos);
            placer.sendSystemMessage(Component.literal(
                    String.format("RTK 基站已部署 [%s] @ (%d, %d, %d)", id, pos.getX(), pos.getY(), pos.getZ())));
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            if (RtkStationSavedData.get(serverLevel).removeStation(pos)) {
                RtkNetwork.broadcastStations(serverLevel);
                RobonixMinecraftMod.LOGGER.info("Removed RTK base station at {}", pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
