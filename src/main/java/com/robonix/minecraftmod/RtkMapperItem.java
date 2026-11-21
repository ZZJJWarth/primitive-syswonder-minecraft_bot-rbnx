// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Handheld RTK surveyor tool. Any player holding this item in either hand is
 * tracked during manual campus mapping mode; right-clicking drops an instant
 * survey mark event for the Web UI.
 */
public final class RtkMapperItem extends Item {
    private static final double DEFAULT_RTK_RANGE_M = 256.0;

    public RtkMapperItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            var data = RtkStationSavedData.get(serverLevel);
            if (data.pruneMissing(serverLevel)) {
                RtkNetwork.broadcastStations(serverLevel);
            }
            var stations = data.listStations();
            double ts = serverLevel.getGameTime() / 20.0;
            var fix = RtkPhysics.computeFix(serverLevel, serverPlayer, stations, DEFAULT_RTK_RANGE_M, true, ts);
            boolean closing = serverPlayer.isShiftKeyDown();
            String action = closing ? "close" : "mark";
            RtkMapperTracker.recordMark(serverPlayer, fix, action);
            String msg;
            if (closing) {
                msg = "RTK 测绘仪: 已发送「闭合当前建筑/地块」指令 (8093 建图模式生效)";
            } else if (fix.valid()) {
                msg = String.format("RTK 采点 (E %.1f, N %.1f) [%s · 基站 %s %.0fm · 潜行右键闭合]",
                        fix.east(), fix.north(), fix.fixType(),
                        fix.baseId().isEmpty() ? "无" : fix.baseId(),
                        Math.max(0.0, fix.baseDistanceM()));
            } else {
                msg = String.format("RTK 测绘仪: 无定位信号 [NO_FIX · 天空 %.0f%% · 请移至露天]",
                        fix.skyVisibility() * 100.0);
            }
            serverPlayer.displayClientMessage(Component.literal(msg), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
