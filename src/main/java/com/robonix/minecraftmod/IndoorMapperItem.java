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
 * Handheld indoor odometry mapper item (robonix_minecraft:indoor_mapper).
 *
 * - Shift + Right-click: set building entrance/exit anchor & reset indoor odometry.
 * - Right-click: mark doorway / room navigation point in the 8093 indoor workflow.
 */
public final class IndoorMapperItem extends Item {
    private static final double DEFAULT_RTK_RANGE_M = 256.0;

    public IndoorMapperItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            double ts = serverLevel.getGameTime() / 20.0;
            if (serverPlayer.isShiftKeyDown()) {
                // Anchor entrance: align with RTK if valid at doorway, otherwise current ENU pose.
                var data = RtkStationSavedData.get(serverLevel);
                var stations = data.listStations();
                var fix = RtkPhysics.computeFix(serverLevel, serverPlayer, stations, DEFAULT_RTK_RANGE_M, false, ts);
                double anchorE = fix.valid() ? fix.east() : serverPlayer.getX();
                double anchorN = fix.valid() ? fix.north() : -serverPlayer.getZ();
                double anchorU = fix.valid() ? fix.up() : serverPlayer.getY();
                var pose = IndoorMapperTracker.anchorEntrance(serverPlayer, anchorE, anchorN, anchorU, ts);
                IndoorMapperTracker.recordMark(serverPlayer, pose, "anchor_entrance");
                String src = fix.valid() ? fix.fixType() : "ODOM";
                String msg = String.format(
                        "室内建图仪: 已设建筑出口点 (E %.1f, N %.1f) [%s 对齐 · 里程计已归零]",
                        pose.east(), pose.north(), src);
                serverPlayer.displayClientMessage(Component.literal(msg), true);
            } else {
                var pose = IndoorMapperTracker.computePose(serverPlayer, ts);
                IndoorMapperTracker.recordMark(serverPlayer, pose, "mark_step");
                String msg;
                if (pose.anchored()) {
                    msg = String.format(
                            "室内建图仪: 已发室内标点 (E %.1f, N %.1f | 相对出口 dE %+.1f, dN %+.1f)",
                            pose.east(), pose.north(), pose.odomDx(), pose.odomDy());
                } else {
                    msg = "室内建图仪: 请先在建筑大门 Shift+右键 设置出口点（已记录当前点位）";
                }
                serverPlayer.displayClientMessage(Component.literal(msg), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
