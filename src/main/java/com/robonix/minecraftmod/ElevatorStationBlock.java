// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Reusable zero-command-block elevator floor station threshold block.
 *
 * Placement:
 * - Embed 1 block in the doorway floor center (Y = floorY - 1) on each floor of a shaft,
 *   aligned vertically on the same (X, Z) column, facing INTO the elevator shaft.
 * - Normal right-click: calls the cabin to this floor & opens doors, and displays clickable
 *   floor buttons [1F] [2F] [3F] ... in chat so human players can ride easily too.
 * - Shift + right-click: auto-carves the shaft and builds the standard quartz enclosure + cabin.
 */
public final class ElevatorStationBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ElevatorStationBlock> CODEC = simpleCodec(ElevatorStationBlock::new);

    public ElevatorStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Points INTO the elevator shaft (the horizontal direction the builder is facing)
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.is(oldState.getBlock()) && level instanceof ServerLevel serverLevel) {
            ElevatorSavedData data = ElevatorSavedData.get(serverLevel);
            ElevatorSavedData.ElevatorRecord rec = data.registerStation(pos, state.getValue(FACING));
            // Align newly placed station block facing to match the shaft column's primary facing
            if (state.getValue(FACING) != rec.facing) {
                serverLevel.setBlock(pos, state.setValue(FACING, rec.facing), 3);
            }
            RobonixMinecraftMod.LOGGER.info(
                    "Registered elevator station {} @ {} (facing={}, total floors={})",
                    rec.id, pos, rec.facing, rec.floors.size());
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            ElevatorSavedData data = ElevatorSavedData.get(serverLevel);
            ElevatorSavedData.ElevatorRecord rec = data.getByColumn(pos.getX(), pos.getZ());
            if (rec == null) {
                rec = data.registerStation(pos, state.getValue(FACING));
            }
            if (rec.floors.size() >= 2) {
                ElevatorEngine.ensureCabinAndLandingDoors(serverLevel, data, rec);
            }
            if (placer instanceof ServerPlayer sp) {
                ElevatorSavedData.FloorStop stop = rec.findFloorByAnchorY(pos.getY());
                String floorName = stop != null ? stop.label : "?";
                sp.sendSystemMessage(Component.literal(String.format(
                        "电梯层站已注册 [%s] %s @ (%d, %d, %d) 井道方向=%s（共 %d 层；Shift+右键可一键生成井道外壳与轿厢）",
                        rec.id, floorName, pos.getX(), pos.getY(), pos.getZ(), rec.facing.getName(), rec.floors.size()))
                        .withStyle(ChatFormatting.AQUA));
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            ElevatorSavedData data = ElevatorSavedData.get(serverLevel);
            data.removeStation(pos);
            RobonixMinecraftMod.LOGGER.info("Removed elevator station @ {}", pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public InteractionResult use(
            BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        // Holding indoor_mapper: bind this floor's elevator geometry into the offline 8093 IndoorFloorMap!
        boolean holdingIndoor = player.getMainHandItem().is(RtkRegistry.INDOOR_MAPPER.get())
                || player.getOffhandItem().is(RtkRegistry.INDOOR_MAPPER.get());
        if (holdingIndoor) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
                ElevatorSavedData data = ElevatorSavedData.get(serverLevel);
                ElevatorSavedData.ElevatorRecord rec = data.getByColumn(pos.getX(), pos.getZ());
                if (rec == null) {
                    rec = data.registerStation(pos, state.getValue(FACING));
                }
                ElevatorSavedData.FloorStop here = rec.findFloorByAnchorY(pos.getY());
                if (here == null) {
                    rec = data.registerStation(pos, state.getValue(FACING));
                    here = rec.findFloorByAnchorY(pos.getY());
                }
                double ts = serverLevel.getGameTime() / 20.0;
                var odom = IndoorMapperTracker.computePose(sp, ts);
                JsonObject extra = rec.floorSurveyExtraJson(here);
                IndoorMapperTracker.recordMark(sp, odom, "bind_elevator", extra);
                sp.displayClientMessage(
                        Component.literal(String.format(
                                "室内建图仪: 已绑定电梯 [%s] %s（候梯口与轿厢范围已写入离线楼层图）",
                                rec.id, here.label)),
                        true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
            ElevatorSavedData data = ElevatorSavedData.get(serverLevel);
            ElevatorSavedData.ElevatorRecord rec = data.getByColumn(pos.getX(), pos.getZ());
            if (rec == null) {
                rec = data.registerStation(pos, state.getValue(FACING));
            }
            if (player.isShiftKeyDown()) {
                ElevatorEngine.buildShaftAndCabin(serverLevel, data, rec);
                sp.sendSystemMessage(Component.literal(String.format(
                        "已自动构建电梯井道与轿厢 [%s]（%d 层，净空 %d×%d×%d，当前停靠 %s）",
                        rec.id, rec.floors.size(), rec.width, rec.depth, rec.height, rec.currentFloorLabel()))
                        .withStyle(ChatFormatting.GREEN));
                return InteractionResult.CONSUME;
            }

            ElevatorSavedData.FloorStop here = rec.findFloorByAnchorY(pos.getY());
            if (here != null) {
                if (!rec.moving && rec.currentY == here.floorY()) {
                    // Toggle door at this floor, respecting light-curtain obstacle detection
                    boolean ok = ElevatorEngine.setDoorAtFloor(serverLevel, data, rec, here, !rec.doorOpen);
                    if (!ok) {
                        sp.sendSystemMessage(Component.literal(
                                String.format("电梯 [%s] 门口防夹传感器检测到障碍物，已保持开门", rec.id))
                                .withStyle(ChatFormatting.RED));
                    }
                } else {
                    boolean ok = ElevatorEngine.dispatchToFloor(serverLevel, data, rec, here, true);
                    if (ok) {
                        sp.sendSystemMessage(Component.literal(
                                String.format("正在呼叫电梯 [%s] 前往 %s...", rec.id, here.label))
                                .withStyle(ChatFormatting.YELLOW));
                    } else {
                        sp.sendSystemMessage(Component.literal(
                                String.format("电梯 [%s] 当前停靠楼层门口被遮挡，暂时无法关门发车", rec.id))
                                .withStyle(ChatFormatting.RED));
                    }
                }
            }
            sendFloorPanel(sp, rec);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static void sendFloorPanel(ServerPlayer player, ElevatorSavedData.ElevatorRecord rec) {
        MutableComponent msg = Component.literal("[" + rec.id + " 电梯面板] ").withStyle(ChatFormatting.GOLD);
        for (ElevatorSavedData.FloorStop stop : rec.floors) {
            boolean isCurrent = !rec.moving && rec.currentY == stop.floorY();
            String cmd = "/robonix_elevator call " + rec.id + " " + stop.label;
            MutableComponent btn = Component.literal(" [" + stop.label + "]")
                    .withStyle(Style.EMPTY
                            .withColor(isCurrent ? ChatFormatting.GREEN : ChatFormatting.AQUA)
                            .withBold(isCurrent)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击乘梯前往 " + stop.label))));
            msg.append(btn);
        }
        String doorCmd = "/robonix_elevator door " + rec.id + " " + (rec.doorOpen ? "close" : "open");
        msg.append(Component.literal(rec.doorOpen ? " [关门]" : " [开门]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, doorCmd))));
        player.sendSystemMessage(msg);
    }
}
